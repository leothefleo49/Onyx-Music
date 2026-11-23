package com.lastwave.app.data.scrobble

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import com.lastwave.app.data.local.ScrobblerPreferences
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.local.awaitLoadedSession
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import com.lastwave.app.data.repository.HomeRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Immutable
data class HistorySyncProgress(
    val isRunning: Boolean = false,
    val sourceName: String = "",
    val fetchedCount: Int = 0,
    val importedCount: Int = 0,
    val skippedCount: Int = 0,
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val statusText: String? = null,
    val lastError: String? = null,
    val lastCompletedSummary: String? = null,
)

/**
 * Application-scoped multi-platform history sync & import coordinator.
 *
 * Runs inside [externalScope] so navigating away from Settings or Stats never
 * interrupts a large Last.fm / ListenBrainz / YouTube Music / Spotify / Takeout
 * sync, and commits batches incrementally into [OnyxScrobbleDatabase] so newly
 * imported plays appear live in Stats immediately.
 */
@Singleton
class UniversalHistorySyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val homeRepository: HomeRepository,
    private val scrobbleSyncEngine: ScrobbleSyncEngine,
    private val listenBrainzApi: ListenBrainzApi,
    private val innerTubeMusicApi: InnerTubeMusicApi,
    private val ytAuth: YtMusicAuthManager,
    private val sessionPreferences: SessionPreferences,
    private val scrobblerPreferences: ScrobblerPreferences,
    private val externalScope: CoroutineScope,
) {
    private val _progress = MutableStateFlow(HistorySyncProgress())
    val progress: StateFlow<HistorySyncProgress> = _progress.asStateFlow()

    @Volatile private var activeJob: Job? = null

    fun cancelActiveSync() {
        activeJob?.cancel()
        activeJob = null
        _progress.update {
            it.copy(
                isRunning = false,
                statusText = "Sync paused (${it.importedCount} plays saved so far)",
            )
        }
    }

    fun clearBanner() {
        _progress.update { it.copy(statusText = null, lastError = null, lastCompletedSummary = null) }
    }

    /**
     * Pulls the connected user's full Last.fm history across ALL pages (no 200-page
     * cap), using [com.lastwave.app.data.model.ArtistRef.displayName] and
     * [com.lastwave.app.data.model.AlbumRef.displayName], with retry/backoff on
     * transient errors and incremental Room batch commits.
     */
    fun syncLastFmFullHistory() {
        startSyncJob("Last.fm") {
            val session = sessionPreferences.awaitLoadedSession()
            if (session.username.isBlank() || session.username.equals("Guest User", ignoreCase = true) || session.apiKey.isBlank()) {
                _progress.update {
                    it.copy(
                        isRunning = false,
                        lastError = "Connect your Last.fm account in Settings first",
                        statusText = "Last.fm not connected",
                    )
                }
                return@startSyncJob
            }

            _progress.value = HistorySyncProgress(
                isRunning = true,
                sourceName = "Last.fm",
                statusText = "Connecting to Last.fm for ${session.username}…",
            )

            var page = 1
            var totalPages = 1
            var totalFetched = 0
            var totalImported = 0
            var totalSkipped = 0
            val pendingBatch = mutableListOf<ScrobbleEventEntity>()
            var consecutiveFailures = 0

            while (page <= totalPages) {
                currentCoroutineContext().ensureActive()
                val result = homeRepository.fetchRecentTracks(
                    page = page,
                    limit = LASTFM_PAGE_SIZE,
                    username = session.username,
                    forceRefresh = true,
                )
                val data = result.getOrNull()
                if (data == null) {
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_PAGE_RETRIES) {
                        val errMsg = result.exceptionOrNull()?.message ?: "Last.fm paused after page $page"
                        if (pendingBatch.isNotEmpty()) {
                            val saved = scrobbleSyncEngine.importScrobbleEvents(pendingBatch, "Last.fm Sync")
                            totalImported += saved.events.size
                            totalSkipped += saved.skippedCount
                            pendingBatch.clear()
                        }
                        _progress.update {
                            it.copy(
                                isRunning = false,
                                fetchedCount = totalFetched,
                                importedCount = totalImported,
                                skippedCount = totalSkipped,
                                lastError = errMsg,
                                statusText = if (totalImported > 0) {
                                    "Saved $totalImported plays before Last.fm rate-limited ($errMsg)"
                                } else {
                                    "Last.fm sync failed: $errMsg"
                                },
                            )
                        }
                        return@startSyncJob
                    }
                    val backoffMs = 1_500L * consecutiveFailures
                    _progress.update {
                        it.copy(statusText = "Last.fm busy on page $page — retrying in ${backoffMs / 1000}s…")
                    }
                    delay(backoffMs)
                    continue
                }

                consecutiveFailures = 0
                totalPages = data.totalPages.coerceAtLeast(page)
                if (data.tracks.isEmpty()) break

                data.tracks.filterNot { it.isNowPlaying }.forEach { track ->
                    val seconds = track.date?.uts?.toLongOrNull() ?: return@forEach
                    val cleanTitle = track.name.trim()
                    val cleanArtist = track.artist.displayName.trim()
                    val cleanAlbum = track.album.displayName.trim()
                    if (cleanTitle.isBlank() || cleanArtist.isBlank()) return@forEach
                    val tsMs = seconds * 1000L
                    pendingBatch += ScrobbleEventEntity(
                        trackKey = normalizeScrobbleTrackKey(cleanArtist, cleanTitle),
                        title = cleanTitle,
                        artist = cleanArtist,
                        album = cleanAlbum,
                        artworkUrl = track.artworkUrl?.takeIf { it.isNotBlank() },
                        durationMs = 210_000L,
                        listenedMs = 210_000L,
                        startedAtMs = tsMs,
                        scrobbledAtMs = tsMs,
                        sourcePackage = "fm.last.android",
                        sourceAppName = "Last.fm",
                        lastFmSyncStatus = ScrobbleSyncStatus.SYNCED,
                        listenBrainzSyncStatus = ScrobbleSyncStatus.SKIPPED,
                        mediaCategory = ScrobbleMediaCategory.MUSIC,
                    )
                    totalFetched++
                }

                // Commit every 2 pages (up to 400 tracks) so Stats updates live and survives interruption.
                if (pendingBatch.size >= COMMIT_BATCH_SIZE || page >= totalPages) {
                    val saved = scrobbleSyncEngine.importScrobbleEvents(pendingBatch, "Last.fm Sync")
                    totalImported += saved.events.size
                    totalSkipped += saved.skippedCount
                    pendingBatch.clear()
                }

                _progress.update {
                    it.copy(
                        isRunning = true,
                        fetchedCount = totalFetched,
                        importedCount = totalImported,
                        skippedCount = totalSkipped,
                        currentPage = page,
                        totalPages = totalPages,
                        statusText = "Last.fm: $totalFetched fetched · $totalImported new (page $page/$totalPages)",
                    )
                }

                page++
                // Polite pacing keeps Last.fm under 5 req/sec so large libraries never trip error 29.
                delay(LASTFM_PAGE_DELAY_MS)
            }

            val summary = if (totalImported > 0) {
                "Synced $totalImported new plays from Last.fm ($totalSkipped already saved)"
            } else if (totalFetched > 0) {
                "Last.fm is up to date ($totalFetched plays checked)"
            } else {
                "No Last.fm history found for ${session.username}"
            }
            _progress.update {
                it.copy(
                    isRunning = false,
                    fetchedCount = totalFetched,
                    importedCount = totalImported,
                    skippedCount = totalSkipped,
                    statusText = summary,
                    lastCompletedSummary = summary,
                    lastError = null,
                )
            }
            // Enrich newly imported artists with genres in background.
            if (totalImported > 0) {
                externalScope.launch(Dispatchers.IO) {
                    runCatching { scrobbleSyncEngine.backfillMissingGenres(maxArtists = 40) }
                }
            }
        }
    }

    /**
     * Pulls full listen history from ListenBrainz for the configured user (or explicit username).
     */
    fun syncListenBrainzFullHistory(usernameOverride: String? = null) {
        startSyncJob("ListenBrainz") {
            val settings = runCatching { scrobblerPreferences.settings.first() }.getOrNull()
            val username = usernameOverride?.trim()?.takeIf { it.isNotBlank() }
                ?: settings?.listenBrainzUsername?.trim().orEmpty()
            if (username.isBlank()) {
                _progress.update {
                    it.copy(
                        isRunning = false,
                        lastError = "Connect your ListenBrainz token or enter a username first",
                        statusText = "ListenBrainz not connected",
                    )
                }
                return@startSyncJob
            }

            _progress.value = HistorySyncProgress(
                isRunning = true,
                sourceName = "ListenBrainz",
                statusText = "Fetching ListenBrainz listens for $username…",
            )

            var maxTs: Long? = null
            var page = 0
            var totalFetched = 0
            var totalImported = 0
            var totalSkipped = 0

            while (page < MAX_LB_PAGES) {
                currentCoroutineContext().ensureActive()
                page++
                val batchRes = listenBrainzApi.fetchUserListens(username, maxTs = maxTs, count = 100)
                val listens = batchRes.getOrElse { err ->
                    _progress.update {
                        it.copy(
                            isRunning = false,
                            lastError = err.message ?: "ListenBrainz request failed",
                            statusText = "ListenBrainz sync paused after $totalImported new plays",
                        )
                    }
                    return@startSyncJob
                }
                if (listens.isEmpty()) break

                val entities = listens.map { item ->
                    val tsMs = item.listenedAtSec * 1000L
                    ScrobbleEventEntity(
                        trackKey = normalizeScrobbleTrackKey(item.artist, item.title),
                        title = item.title,
                        artist = item.artist,
                        album = item.album,
                        durationMs = item.durationMs.takeIf { it > 0L } ?: 210_000L,
                        listenedMs = item.durationMs.takeIf { it > 0L } ?: 210_000L,
                        startedAtMs = tsMs,
                        scrobbledAtMs = tsMs,
                        sourcePackage = "org.listenbrainz.android",
                        sourceAppName = "ListenBrainz",
                        lastFmSyncStatus = ScrobbleSyncStatus.SYNCED,
                        listenBrainzSyncStatus = ScrobbleSyncStatus.SYNCED,
                        mediaCategory = ScrobbleMediaCategory.MUSIC,
                    )
                }
                totalFetched += entities.size
                val saved = scrobbleSyncEngine.importScrobbleEvents(entities, "ListenBrainz")
                totalImported += saved.events.size
                totalSkipped += saved.skippedCount

                _progress.update {
                    it.copy(
                        isRunning = true,
                        fetchedCount = totalFetched,
                        importedCount = totalImported,
                        skippedCount = totalSkipped,
                        currentPage = page,
                        statusText = "ListenBrainz: $totalFetched fetched · $totalImported new",
                    )
                }

                val oldestSec = listens.minOfOrNull { it.listenedAtSec } ?: break
                if (maxTs != null && oldestSec >= maxTs) break
                maxTs = oldestSec
                if (listens.size < 100) break
                delay(250L)
            }

            val summary = if (totalImported > 0) {
                "Synced $totalImported new plays from ListenBrainz"
            } else {
                "ListenBrainz is up to date ($totalFetched listens checked)"
            }
            _progress.update {
                it.copy(
                    isRunning = false,
                    statusText = summary,
                    lastCompletedSummary = summary,
                    lastError = null,
                )
            }
            if (totalImported > 0) {
                externalScope.launch(Dispatchers.IO) {
                    runCatching { scrobbleSyncEngine.backfillMissingGenres(maxArtists = 35) }
                }
            }
        }
    }

    /**
     * Pulls recent YouTube Music listening history directly from the connected YT Music account.
     * For full multi-year YouTube / YT Music history, users can also drop their Google Takeout
     * `watch-history.json` or `.zip` into [importFromUri].
     */
    fun syncYouTubeMusicHistory() {
        startSyncJob("YouTube Music") {
            if (!ytAuth.connection.value.isConnected) {
                _progress.update {
                    it.copy(
                        isRunning = false,
                        lastError = "Connect YouTube Music in Settings first (or import Google Takeout JSON/ZIP)",
                        statusText = "YouTube Music not connected",
                    )
                }
                return@startSyncJob
            }
            _progress.value = HistorySyncProgress(
                isRunning = true,
                sourceName = "YouTube Music",
                statusText = "Reading YouTube Music play history…",
            )
            val signals = runCatching {
                innerTubeMusicApi.fetchTasteSignals(recentLimit = 50, likedLimit = 0, feedLimit = 0)
            }.getOrNull()
            val tracks = signals?.recentTracks.orEmpty()
            if (tracks.isEmpty()) {
                _progress.update {
                    it.copy(
                        isRunning = false,
                        statusText = "No recent YouTube Music history returned. For full history, import Google Takeout ZIP/JSON.",
                    )
                }
                return@startSyncJob
            }
            val nowMs = System.currentTimeMillis()
            val events = tracks.mapIndexedNotNull { idx, item ->
                val title = item.title.trim()
                val artist = item.artist.trim()
                if (title.isBlank() || artist.isBlank()) return@mapIndexedNotNull null
                val durMs = (item.durationSeconds?.takeIf { it > 0 }?.times(1000L)) ?: 210_000L
                val approxTs = nowMs - (idx + 1) * 240_000L
                val category = MediaCategoryClassifier.classify(
                    title = title,
                    artist = artist,
                    album = item.album.orEmpty(),
                    sourcePackage = "com.google.android.apps.youtube.music",
                    sourceAppName = "YouTube Music",
                    durationMs = durMs,
                )
                ScrobbleEventEntity(
                    trackKey = normalizeScrobbleTrackKey(artist, title),
                    title = title,
                    artist = artist,
                    album = item.album.orEmpty(),
                    artworkUrl = item.artworkUrl,
                    durationMs = durMs,
                    listenedMs = durMs,
                    startedAtMs = approxTs,
                    scrobbledAtMs = approxTs,
                    sourcePackage = "com.google.android.apps.youtube.music",
                    sourceAppName = "YouTube Music",
                    lastFmSyncStatus = ScrobbleSyncStatus.SYNCED,
                    listenBrainzSyncStatus = ScrobbleSyncStatus.SKIPPED,
                    mediaCategory = category,
                )
            }
            val saved = scrobbleSyncEngine.importScrobbleEvents(events, "YouTube Music")
            val summary = "Synced ${saved.events.size} YouTube Music plays (${saved.skippedCount} already saved)"
            _progress.update {
                it.copy(
                    isRunning = false,
                    fetchedCount = events.size,
                    importedCount = saved.events.size,
                    skippedCount = saved.skippedCount,
                    statusText = summary,
                    lastCompletedSummary = summary,
                    lastError = null,
                )
            }
            if (saved.events.isNotEmpty()) {
                externalScope.launch(Dispatchers.IO) {
                    runCatching { scrobbleSyncEngine.backfillMissingGenres(maxArtists = 30) }
                }
            }
        }
    }

    /**
     * Universal file & `.zip` importer supporting Spotify (Extended + Account + ZIP),
     * YouTube / Google Takeout (JSON + ZIP), SoundCloud, stats.fm, Pano Scrobbler,
     * Last.fm (JSON + CSV), ListenBrainz (JSON/JSONL), Apple Music CSV, and Onyx backups.
     */
    fun importFromUri(uri: Uri, fileNameHint: String? = null) {
        startSyncJob("File Import") {
            _progress.value = HistorySyncProgress(
                isRunning = true,
                sourceName = "File Import",
                statusText = "Reading & analyzing export file…",
            )
            val result = runCatching {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open selected file")
                stream.use { input ->
                    scrobbleSyncEngine.importFromStream(input, fileNameHint = fileNameHint ?: uri.lastPathSegment)
                }
            }
            result.fold(
                onSuccess = { res ->
                    val musicCount = res.events.count { ScrobbleMediaCategory.isMusic(it.mediaCategory) }
                    val otherCount = res.events.size - musicCount
                    val breakdown = if (otherCount > 0) " ($musicCount music, $otherCount videos/podcasts)" else ""
                    val summary = "Imported ${res.events.size} plays$breakdown from ${res.formatDetected}" +
                        if (res.skippedCount > 0) " · ${res.skippedCount} skipped" else ""
                    _progress.update {
                        it.copy(
                            isRunning = false,
                            fetchedCount = res.events.size + res.skippedCount,
                            importedCount = res.events.size,
                            skippedCount = res.skippedCount,
                            statusText = summary,
                            lastCompletedSummary = summary,
                            lastError = null,
                        )
                    }
                    if (musicCount > 0) {
                        externalScope.launch(Dispatchers.IO) {
                            runCatching { scrobbleSyncEngine.backfillMissingGenres(maxArtists = 40) }
                        }
                    }
                },
                onFailure = { err ->
                    _progress.update {
                        it.copy(
                            isRunning = false,
                            lastError = err.message ?: "Import failed",
                            statusText = "Import failed: ${err.message ?: "Unsupported file"}",
                        )
                    }
                },
            )
        }
    }

    private fun startSyncJob(sourceName: String, block: suspend () -> Unit) {
        activeJob?.cancel()
        activeJob = externalScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "$sourceName sync failed", e)
                _progress.update {
                    it.copy(
                        isRunning = false,
                        lastError = e.message ?: "$sourceName sync failed",
                        statusText = "$sourceName sync failed: ${e.message ?: "unexpected error"}",
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "HistorySyncManager"
        private const val LASTFM_PAGE_SIZE = 200
        private const val COMMIT_BATCH_SIZE = 350
        private const val LASTFM_PAGE_DELAY_MS = 260L
        private const val MAX_PAGE_RETRIES = 4
        private const val MAX_LB_PAGES = 250
    }
}
