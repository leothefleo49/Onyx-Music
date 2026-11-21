package com.lastwave.app.data.scrobble

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.lastwave.app.data.local.ScrobblerPreferences
import com.lastwave.app.data.local.ScrobblerSettings
import com.lastwave.app.data.spotify.mirror.SpotifySilentMirrorManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * System-wide Android Notification Listener Scrobbler (Pano Scrobbler-style).
 *
 * Monitors active [MediaController] sessions across external music apps
 * (Spotify, Apple Music, YouTube Music, SoundCloud, TIDAL, Qobuz, Plexamp,
 * Poweramp, etc.) while ignoring Onyx Music's own package (tracked directly
 * by MusicPlaybackService with exact playback position).
 */
@AndroidEntryPoint
class OnyxNotificationScrobblerService : NotificationListenerService() {

    @Inject lateinit var syncEngine: ScrobbleSyncEngine
    @Inject lateinit var scrobblerPreferences: ScrobblerPreferences
    @Inject lateinit var appFilterDao: ScrobbleAppFilterDao
    @Inject lateinit var spotifySilentMirrorManager: SpotifySilentMirrorManager

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, error ->
                Log.e(TAG, "Unhandled scrobbler service error", error)
            },
    )

    @Volatile private var settings = ScrobblerSettings()
    @Volatile private var appFilters: Map<String, ScrobbleAppFilterEntity> = emptyMap()
    private var mediaSessionManager: MediaSessionManager? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var tickerJob: Job? = null

    private data class TrackedExternalSession(
        val packageName: String,
        val appName: String,
        val trackKey: String,
        val title: String,
        val artist: String,
        val album: String,
        val artworkUrl: String?,
        val durationMs: Long,
        var startedAtEpochSec: Long,
        var accumulatedPlayMs: Long = 0L,
        var lastTickElapsedMs: Long = SystemClock.elapsedRealtime(),
        var lastReportedPosMs: Long = 0L,
        var nowPlayingSent: Boolean = false,
        var scrobbled: Boolean = false,
    )

    private val activeTracked = LinkedHashMap<String, TrackedExternalSession>()
    private val trackedControllers = mutableListOf<MediaController>()
    private val controllerCallbacks = LinkedHashMap<MediaController, MediaController.Callback>()

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            scrobblerPreferences.settings.collect { settings = it }
        }
        serviceScope.launch {
            appFilterDao.observeAll().collect { filters ->
                appFilters = filters.associateBy { it.packageName }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnectedFlag = true
        attachSessionManager()
        startTicker()
    }

    override fun onListenerDisconnected() {
        isConnectedFlag = false
        detachSessionManager()
        tickerJob?.cancel()
        tickerJob = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        isConnectedFlag = false
        detachSessionManager()
        tickerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun attachSessionManager() {
        detachSessionManager()
        val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        mediaSessionManager = manager
        val component = ComponentName(this, OnyxNotificationScrobblerService::class.java)
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            updateControllers(controllers.orEmpty())
        }
        sessionsListener = listener
        runCatching {
            manager.addOnActiveSessionsChangedListener(listener, component)
            updateControllers(manager.getActiveSessions(component))
        }.onFailure { err ->
            Log.w(TAG, "Unable to attach MediaSessionManager listener: ${err.message}")
        }
    }

    private fun detachSessionManager() {
        val manager = mediaSessionManager
        val listener = sessionsListener
        if (manager != null && listener != null) {
            runCatching { manager.removeOnActiveSessionsChangedListener(listener) }
        }
        synchronized(this) {
            controllerCallbacks.forEach { (controller, cb) ->
                runCatching { controller.unregisterCallback(cb) }
            }
            controllerCallbacks.clear()
            trackedControllers.clear()
        }
        sessionsListener = null
    }

    private fun updateControllers(controllers: List<MediaController>) {
        synchronized(this) {
            controllerCallbacks.forEach { (controller, cb) ->
                runCatching { controller.unregisterCallback(cb) }
            }
            controllerCallbacks.clear()
            trackedControllers.clear()

            for (controller in controllers) {
                val pkg = controller.packageName
                // Always ignore our own package; internal playback is tracked directly.
                if (pkg == packageName) continue
                trackedControllers += controller
                val cb = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        inspectController(controller)
                    }

                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        inspectController(controller)
                    }

                    override fun onSessionDestroyed() {
                        activeTracked.remove(pkg)
                        syncEngine.clearNowPlaying(pkg)
                    }
                }
                runCatching {
                    controller.registerCallback(cb)
                    controllerCallbacks[controller] = cb
                }
                inspectController(controller)
            }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                val snapshot = synchronized(this@OnyxNotificationScrobblerService) {
                    trackedControllers.toList()
                }
                for (controller in snapshot) {
                    runCatching { inspectController(controller) }
                }
            }
        }
    }

    private fun isPackageAllowed(pkg: String): Boolean {
        if (pkg == packageName || pkg.isBlank()) return false
        // Avoid double-scrobbling Spotify while Onyx is silently mirroring playback to it.
        if (pkg in SPOTIFY_PACKAGES && spotifySilentMirrorManager.isMirroringActive.value) {
            return false
        }
        val current = settings
        if (!current.enabled) return false
        if (pkg in current.blockedPackages) return false

        val explicitFilter = appFilters[pkg]
        if (explicitFilter != null) return explicitFilter.isAllowed

        if (pkg in current.selectedPackages) return true
        if (current.autoScrobbleKnownPlayers && pkg in ScrobbleRulesEngine.KNOWN_MUSIC_PLAYERS) {
            return true
        }
        return false
    }

    private fun inspectController(controller: MediaController) {
        val pkg = controller.packageName
        if (!isPackageAllowed(pkg)) {
            if (activeTracked.remove(pkg) != null) {
                syncEngine.clearNowPlaying(pkg)
            }
            return
        }

        val playbackState = controller.playbackState
        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
        val metadata = controller.metadata ?: return

        val rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val rawAlbum = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
        val artworkUrl = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)

        if (rawTitle.isBlank() || rawArtist.isBlank()) return

        val cleaned = ScrobbleRulesEngine.cleanMetadata(ScrobbleMetadata(rawTitle, rawArtist, rawAlbum))
        val trackKey = normalizeScrobbleTrackKey(cleaned.artist, cleaned.title)
        val nowElapsed = SystemClock.elapsedRealtime()
        val reportedPos = playbackState?.position?.coerceAtLeast(0L) ?: 0L

        val existing = activeTracked[pkg]
        if (existing == null || existing.trackKey != trackKey) {
            // Submit previous track if it already crossed threshold before skipping
            if (existing != null && !existing.scrobbled &&
                ScrobbleRulesEngine.isEligibleForScrobble(
                    existing.durationMs,
                    existing.accumulatedPlayMs,
                    settings.scrobblePercent,
                )
            ) {
                submitTrackedSession(existing)
            }

            val appName = resolveAppLabel(pkg)
            val created = TrackedExternalSession(
                packageName = pkg,
                appName = appName,
                trackKey = trackKey,
                title = cleaned.title,
                artist = cleaned.artist,
                album = cleaned.album,
                artworkUrl = artworkUrl,
                durationMs = durationMs,
                startedAtEpochSec = System.currentTimeMillis() / 1000L,
                accumulatedPlayMs = 0L,
                lastTickElapsedMs = nowElapsed,
                lastReportedPosMs = reportedPos,
            )
            activeTracked[pkg] = created

            if (isPlaying && settings.submitNowPlaying) {
                created.nowPlayingSent = true
                announceNowPlayingAsync(created)
            }
            return
        }

        if (!isPlaying) {
            existing.lastTickElapsedMs = nowElapsed
            existing.lastReportedPosMs = reportedPos
            return
        }

        if (!existing.nowPlayingSent && settings.submitNowPlaying) {
            existing.nowPlayingSent = true
            announceNowPlayingAsync(existing)
        }

        // Detect track restart / repeat of the same song
        if (reportedPos in 0L..4_000L && existing.lastReportedPosMs > 20_000L && existing.scrobbled) {
            existing.accumulatedPlayMs = 0L
            existing.startedAtEpochSec = System.currentTimeMillis() / 1000L
            existing.scrobbled = false
            existing.nowPlayingSent = false
            if (settings.submitNowPlaying) {
                existing.nowPlayingSent = true
                announceNowPlayingAsync(existing)
            }
        }

        val deltaMs = (nowElapsed - existing.lastTickElapsedMs).coerceIn(0L, MAX_TICK_DELTA_MS)
        existing.accumulatedPlayMs += deltaMs
        existing.lastTickElapsedMs = nowElapsed
        existing.lastReportedPosMs = reportedPos

        if (!existing.scrobbled &&
            ScrobbleRulesEngine.isEligibleForScrobble(
                existing.durationMs,
                existing.accumulatedPlayMs,
                settings.scrobblePercent,
            )
        ) {
            submitTrackedSession(existing)
        }
    }

    private fun announceNowPlayingAsync(session: TrackedExternalSession) {
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                syncEngine.updateNowPlaying(
                    artist = session.artist,
                    track = session.title,
                    album = session.album.ifBlank { null },
                    durationMs = session.durationMs,
                    sourcePackage = session.packageName,
                    sourceAppName = session.appName,
                )
            }
        }
    }

    private fun submitTrackedSession(session: TrackedExternalSession) {
        session.scrobbled = true
        val listenedSnapshot = session.accumulatedPlayMs
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                syncEngine.recordAndSyncScrobble(
                    artist = session.artist,
                    track = session.title,
                    album = session.album.ifBlank { null },
                    artworkUrl = session.artworkUrl,
                    durationMs = session.durationMs,
                    listenedMs = listenedSnapshot,
                    startedAtSec = session.startedAtEpochSec,
                    sourcePackage = session.packageName,
                    sourceAppName = session.appName,
                )
            }.onFailure { err ->
                Log.w(TAG, "External scrobble failed for ${session.packageName}: ${err.message}")
            }
        }
    }

    private fun resolveAppLabel(pkg: String): String {
        ScrobbleRulesEngine.KNOWN_MUSIC_PLAYERS[pkg]?.let { return it }
        return runCatching {
            val pm = packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(pkg, android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, 0)
            }
            pm.getApplicationLabel(info).toString().ifBlank { pkg }
        }.getOrDefault(pkg)
    }

    companion object {
        private const val TAG = "OnyxScrobblerService"
        private const val TICK_INTERVAL_MS = 1_500L
        private const val MAX_TICK_DELTA_MS = 5_000L
        private val SPOTIFY_PACKAGES = setOf("com.spotify.music", "com.spotify.lite")

        @Volatile
        var isConnectedFlag: Boolean = false
            private set

        fun isNotificationAccessGranted(context: Context): Boolean =
            runCatching {
                NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.packageName)
            }.getOrDefault(false)

        fun notificationListenerSettingsIntent(): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
