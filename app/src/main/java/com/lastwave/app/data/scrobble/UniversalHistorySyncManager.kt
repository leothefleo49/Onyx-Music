package com.lastwave.app.data.scrobble

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import com.lastwave.app.data.local.ScrobblerPreferences
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.local.awaitLoadedSession
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.spotify.SpotifyAuthRepository
import com.lastwave.app.data.spotify.SpotifyPreferences
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    private val spotifyAuth: SpotifyAuthRepository,
    private val spotifyPreferences: SpotifyPreferences,
    private val okHttpClient: OkHttpClient,
    private val sessionPreferences: SessionPreferences,
    private val scrobblerPreferences: ScrobblerPreferences,
    private val externalScope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _progress = MutableStateFlow(HistorySyncProgress())
    val progress: StateFlow<HistorySyncProgress> = _progress.asStateFlow()

    @Volatile private var activeJob: Job? = null
    private val autoSyncStarted = AtomicBoolean(false)
    private val lastAutoSyncAtMs = AtomicLong(0L)

    /**
     * Starts lightweight automatic background history sync (Spotify recently-played
     * + YouTube Music history) every 15 minutes while foregrounded, keeping Stats
     * continuously up to date without manual taps.
     */
    fun startAutoSync() {
        if (!autoSyncStarted.compareAndSet(false, true)) return
        externalScope.launch(Dispatchers.IO) {
            delay(8_000L)
            while (true) {
                runCatching { runAutoSyncOnce() }
                delay(AUTO_SYNC_INTERVAL_MS)
            }
        }
    }

    suspend fun runAutoSyncOnce(force: Boolean = false) {
        val settings = runCatching { scrobblerPreferences.settings.first() }.getOrNull() ?: return
        if (!force && !settings.autoSyncHistoryEnabled) return
        val now = System.currentTimeMillis()
        if (!force && now - lastAutoSyncAtMs.get() < AUTO_SYNC_MIN_GAP_MS) return
        lastAutoSyncAtMs.set(now)

        if (spotifyPreferences.isConnected.first()) {
            runCatching { syncSpotifyRecentInternal(silent = true) }
        }
        if (ytAuth.connection.value.isConnected) {
            runCatching { syncYouTubeMusicInternal(maxPages = 4, silent = true) }
        }
    }

    fun deduplicateHistory() {
        startSyncJob("Deduplicate") {
            _progress.value = HistorySyncProgress(
                isRunning = true,
                sourceName = "Cleanup",
                statusText = "Scanning for duplicate plays across sources…",
            )
            val removed = scrobbleSyncEngine.deduplicateDatabase()
            val summary = if (removed > 0) {
                "Cleaned up $removed duplicate plays"
            } else {
                "No duplicate plays found — your history is clean!"
            }
            _progress.update {
                it.copy(
                    isRunning = false,
                    statusText = summary,
                    lastCompletedSummary = summary,
                    lastError = null,
                )
            }
        }
    }

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
     * Pulls recent Spotify plays automatically via `GET /v1/me/player/recently-played?limit=50`
     * with a persisted cursor so ongoing multi-device Spotify plays flow into Onyx Stats live.
     * (Spotify's API returns the last 50 plays; for lifetime history, import Spotify Extended
     * Streaming History ZIP/JSON via [importFromUri].)
     */
    fun syncSpotifyRecentHistory() {
        startSyncJob("Spotify") {
            if (!spotifyPreferences.isConnected.first()) {
                _progress.update {
                    it.copy(
                        isRunning = false,
                        lastError = "Connect Spotify in Settings first (or import your Spotify Extended History ZIP)",
                        statusText = "Spotify not connected",
                    )
                }
                return@startSyncJob
            }
            syncSpotifyRecentInternal(silent = false)
        }
    }

    private suspend fun syncSpotifyRecentInternal(silent: Boolean) {
        if (!silent) {
            _progress.value = HistorySyncProgress(
                isRunning = true,
                sourceName = "Spotify",
                statusText = "Fetching recent Spotify listening history…",
            )
        }
        val token = spotifyAuth.getPersonalToken()
        if (token.isNullOrBlank()) {
            if (!silent) {
                _progress.update {
                    it.copy(
                        isRunning = false,
                        lastError = "Spotify session expired — reconnect in Settings → Spotify",
                        statusText = "Spotify auth expired",
                    )
                }
            }
            return
        }
        val clientToken = spotifyAuth.getClientToken()
        val afterCursor = spotifyPreferences.getLastHistorySyncAfterMs()
        val url = if (afterCursor > 0L) {
            "https://api.spotify.com/v1/me/player/recently-played?limit=50&after=$afterCursor"
        } else {
            "https://api.spotify.com/v1/me/player/recently-played?limit=50"
        }
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
        if (!clientToken.isNullOrBlank()) {
            reqBuilder.header("client-token", clientToken)
        }
        val bodyText = okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                if (!silent) {
                    _progress.update {
                        it.copy(
                            isRunning = false,
                            lastError = "Spotify returned HTTP ${resp.code} — for full lifetime history, import Spotify Extended History ZIP",
                            statusText = "Spotify history unavailable (HTTP ${resp.code})",
                        )
                    }
                }
                return
            }
            resp.body?.string().orEmpty()
        }
        val root = runCatching { json.parseToJsonElement(bodyText).jsonObject }.getOrNull() ?: return
        val items = root["items"] as? JsonArray ?: JsonArray(emptyList())
        var maxPlayedAtMs = afterCursor
        val events = items.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val trackObj = obj["track"] as? JsonObject ?: return@mapNotNull null
            val title = (trackObj["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val artistsArr = trackObj["artists"] as? JsonArray
            val artist = artistsArr?.mapNotNull {
                (it as? JsonObject)?.get("name")?.let { n -> (n as? JsonPrimitive)?.contentOrNull?.trim() }
            }?.filter { it.isNotBlank() }?.joinToString(", ").orEmpty()
            if (title.isBlank() || artist.isBlank()) return@mapNotNull null
            val albumObj = trackObj["album"] as? JsonObject
            val album = (albumObj?.get("name") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val artUrl = (albumObj?.get("images") as? JsonArray)?.firstOrNull()
                ?.let { (it as? JsonObject)?.get("url") as? JsonPrimitive }?.contentOrNull
            val durMs = (trackObj["duration_ms"] as? JsonPrimitive)?.longOrNull ?: 210_000L
            val playedAtStr = (obj["played_at"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val playedAtMs = ScrobbleImportExport.parseIsoTimestampMs(playedAtStr) ?: return@mapNotNull null
            if (playedAtMs > maxPlayedAtMs) maxPlayedAtMs = playedAtMs
            val startedMs = (playedAtMs - durMs).coerceAtLeast(1L)
            ScrobbleEventEntity(
                trackKey = normalizeScrobbleTrackKey(artist, title),
                title = title,
                artist = artist,
                album = album,
                artworkUrl = artUrl,
                durationMs = durMs,
                listenedMs = durMs,
                startedAtMs = startedMs,
                scrobbledAtMs = startedMs,
                sourcePackage = "com.spotify.music",
                sourceAppName = "Spotify",
                lastFmSyncStatus = ScrobbleSyncStatus.SYNCED,
                listenBrainzSyncStatus = ScrobbleSyncStatus.SKIPPED,
                mediaCategory = ScrobbleMediaCategory.MUSIC,
            )
        }
        if (maxPlayedAtMs > afterCursor) {
            spotifyPreferences.saveLastHistorySyncAfterMs(maxPlayedAtMs)
        }
        val saved = scrobbleSyncEngine.importScrobbleEvents(events, "Spotify Live Sync")
        if (!silent) {
            val summary = if (saved.events.isNotEmpty()) {
                "Synced ${saved.events.size} new Spotify plays (${saved.skippedCount} already saved)"
            } else {
                "Spotify recent history is up to date (${events.size} checked). For lifetime history, import Spotify Extended History ZIP."
            }
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
        }
        if (saved.events.isNotEmpty()) {
            externalScope.launch(Dispatchers.IO) {
                runCatching { scrobbleSyncEngine.backfillMissingGenres(maxArtists = 25) }
            }
        }
    }

    /**
     * Pulls deep YouTube Music listening history directly across ALL continuation pages
     * of `FEmusic_history` using deterministic calendar-anchored shelf timestamps (never
     * duplicating on re-sync). For multi-year YouTube/YT Music history beyond InnerTube's
     * retention window, users can also drop Google Takeout `watch-history.json`/`.html`/`.zip`.
     */
    fun syncYouTubeMusicHistory() {
        startSyncJob("YouTube Music") {
            if (!ytAuth.connection.value.isConnected) {
                _progress.update {
                    it.copy(
                        isRunning = false,
                        lastError = "Connect YouTube Music in Settings first (or import Google Takeout ZIP/JSON/HTML)",
                        statusText = "YouTube Music not connected",
                    )
                }
                return@startSyncJob
            }
            syncYouTubeMusicInternal(maxPages = 25, silent = false)
        }
    }

    private suspend fun syncYouTubeMusicInternal(maxPages: Int, silent: Boolean) {
        if (!silent) {
            _progress.value = HistorySyncProgress(
                isRunning = true,
                sourceName = "YouTube Music",
                statusText = "Reading all available YouTube Music history pages…",
            )
        }
        val entries = innerTubeMusicApi.fetchFullListeningHistory(maxPages = maxPages) { page, count ->
            if (!silent) {
                _progress.update {
                    it.copy(
                        isRunning = true,
                        currentPage = page,
                        fetchedCount = count,
                        statusText = "YouTube Music: $count tracks read (page $page)…",
                    )
                }
            }
        }
        if (entries.isEmpty()) {
            if (!silent) {
                _progress.update {
                    it.copy(
                        isRunning = false,
                        statusText = "No YouTube Music history returned. For full all-time history, import Google Takeout ZIP/JSON/HTML.",
                    )
                }
            }
            return
        }
        val events = entries.mapNotNull { entry ->
            val item = entry.track
            val title = item.title.trim()
            val artist = item.artist.trim()
            if (title.isBlank() || artist.isBlank()) return@mapNotNull null
            val durMs = (item.durationSeconds?.takeIf { it > 0 }?.times(1000L)) ?: 210_000L
            // Deterministic anchor per date shelf + item index so re-syncing NEVER shifts timestamps
            // or creates duplicate rows!
            val anchoredTs = anchorTimestampForYtBucket(entry.bucketLabel, entry.indexInBucket)
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
                startedAtMs = anchoredTs,
                scrobbledAtMs = anchoredTs,
                sourcePackage = "com.google.android.apps.youtube.music",
                sourceAppName = "YouTube Music",
                lastFmSyncStatus = ScrobbleSyncStatus.SYNCED,
                listenBrainzSyncStatus = ScrobbleSyncStatus.SKIPPED,
                mediaCategory = category,
            )
        }
        val saved = scrobbleSyncEngine.importScrobbleEvents(events, "YouTube Music")
        if (!silent) {
            val summary = "Synced ${saved.events.size} new YouTube Music plays (${saved.skippedCount} already saved)"
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
        }
        if (saved.events.isNotEmpty()) {
            externalScope.launch(Dispatchers.IO) {
                runCatching { scrobbleSyncEngine.backfillMissingGenres(maxArtists = 35) }
            }
        }
    }

    /**
     * Converts a YouTube Music `FEmusic_history` shelf header ("Today", "Yesterday",
     * "This week", "Last week", "October 2026", "2025", etc.) into a stable, deterministic
     * epoch-ms timestamp so repeated syncs map the exact same track/slot to the same instant.
     */
    private fun anchorTimestampForYtBucket(bucketLabel: String, indexInBucket: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 20)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val low = bucketLabel.trim().lowercase(Locale.US)
        when {
            low.isBlank() || low == "today" || low == "recent" -> Unit
            low == "yesterday" -> cal.add(Calendar.DAY_OF_YEAR, -1)
            low.contains("this week") -> cal.add(Calendar.DAY_OF_YEAR, -3)
            low.contains("last week") -> cal.add(Calendar.DAY_OF_YEAR, -10)
            low.contains("this month") -> cal.add(Calendar.DAY_OF_YEAR, -14)
            low.contains("last month") -> cal.add(Calendar.MONTH, -1)
            else -> {
                val parsed = listOf("MMMM yyyy", "MMM yyyy", "yyyy").firstNotNullOfOrNull { pat ->
                    runCatching { SimpleDateFormat(pat, Locale.US).parse(bucketLabel.trim()) }.getOrNull()
                }
                if (parsed != null) {
                    cal.time = parsed
                    cal.set(Calendar.DAY_OF_MONTH, 15)
                    cal.set(Calendar.HOUR_OF_DAY, 18)
                } else {
                    cal.add(Calendar.DAY_OF_YEAR, -21)
                }
            }
        }
        // Space tracks 4 minutes apart backwards inside their deterministic calendar bucket
        // (capped to never cross into the future).
        val raw = cal.timeInMillis - (indexInBucket.toLong() + 1L) * 240_000L
        return raw.coerceAtMost(System.currentTimeMillis() - 60_000L)
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
        private const val AUTO_SYNC_INTERVAL_MS = 15L * 60L * 1000L
        private const val AUTO_SYNC_MIN_GAP_MS = 5L * 60L * 1000L
    }
}
