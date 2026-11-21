package com.lastwave.app.data.scrobble

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Log
import com.lastwave.app.data.local.ScrobblerPreferences
import com.lastwave.app.data.local.ScrobblerSettings
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.local.awaitLoadedSession
import com.lastwave.app.data.network.LastFmApiService
import com.lastwave.app.data.network.LastFmRateGuard
import com.lastwave.app.data.network.LastFmSigner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class ActiveNowPlaying(
    val title: String,
    val artist: String,
    val album: String = "",
    val sourcePackage: String = "",
    val sourceAppName: String = "",
    val startedAtMs: Long = System.currentTimeMillis(),
)

data class SyncFlushReport(
    val lastFmSynced: Int = 0,
    val listenBrainzSynced: Int = 0,
    val remainingPending: Int = 0,
)

/**
 * Unified persistent scrobble engine:
 * - Persists every scrobble event in Room ([OnyxScrobbleDatabase]) before remote upload.
 * - Applies Pano-style exact/regex edit rules automatically and retroactively.
 * - Suppresses duplicate scrobbles within a bounded window across Onyx + external players.
 * - Flushes pending scrobbles to Last.fm and ListenBrainz on writes and network restore.
 * - Handles two-way Last.fm Love synchronization (`track.love`, `track.unlove`, `user.getLovedTracks`).
 */
@Singleton
class ScrobbleSyncEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventDao: ScrobbleEventDao,
    private val editRuleDao: ScrobbleEditRuleDao,
    private val appFilterDao: ScrobbleAppFilterDao,
    private val lastFmApi: LastFmApiService,
    private val listenBrainzApi: ListenBrainzApi,
    private val sessionPreferences: SessionPreferences,
    private val scrobblerPreferences: ScrobblerPreferences,
    private val rateGuard: LastFmRateGuard,
    private val externalScope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lastFmMutex = Mutex()
    private val flushMutex = Mutex()
    private var lastWriteAtElapsed = 0L
    @Volatile private var lastNowPlayingKey: String? = null

    private val _scrobbleEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val scrobbleEvents: SharedFlow<Unit> = _scrobbleEvents.asSharedFlow()

    private val _nowPlaying = MutableStateFlow<ActiveNowPlaying?>(null)
    val nowPlaying: StateFlow<ActiveNowPlaying?> = _nowPlaying.asStateFlow()

    val pendingSyncCount: Flow<Int> = eventDao.observePendingSyncCount()
    val totalScrobbleCount: Flow<Int> = eventDao.observeCount()

    sealed interface RemoteResult {
        data object Success : RemoteResult
        data object NoSessionKey : RemoteResult
        data class Failed(val message: String, val retryable: Boolean = false) : RemoteResult
    }

    init {
        registerNetworkRestoreHook()
    }

    private fun registerNetworkRestoreHook() {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        externalScope.launch(Dispatchers.IO) {
                            runCatching { flushPendingQueue() }
                        }
                    }
                },
            )
        }.onFailure { err ->
            Log.d(TAG, "Network restore callback unavailable: ${err.message}")
        }
    }

    suspend fun updateNowPlaying(
        artist: String,
        track: String,
        album: String? = null,
        durationMs: Long = 0L,
        sourcePackage: String = context.packageName,
        sourceAppName: String = DEFAULT_OWN_APP_NAME,
    ): RemoteResult {
        if (artist.equals("Podcast", ignoreCase = true) || track.equals("Podcast", ignoreCase = true)) {
            return RemoteResult.Success
        }
        val rules = runCatching { editRuleDao.getEnabledRules() }.getOrDefault(emptyList())
        val cleaned = ScrobbleRulesEngine.cleanMetadata(ScrobbleMetadata(track, artist, album.orEmpty()))
        val edited = ScrobbleRulesEngine.applyRules(cleaned, rules)
        if (edited.artist.isBlank() || edited.title.isBlank()) return RemoteResult.Success

        _nowPlaying.value = ActiveNowPlaying(
            title = edited.title,
            artist = edited.artist,
            album = edited.album,
            sourcePackage = sourcePackage,
            sourceAppName = sourceAppName,
            startedAtMs = System.currentTimeMillis(),
        )

        val key = "${normalizeScrobbleTrackKey(edited.artist, edited.title)}|$sourcePackage"
        if (key == lastNowPlayingKey) {
            return RemoteResult.Success
        }

        val settings = currentScrobblerSettings()
        if (settings.listenBrainzEnabled && settings.listenBrainzToken.isNotBlank()) {
            externalScope.launch(Dispatchers.IO) {
                runCatching {
                    listenBrainzApi.submitNowPlaying(
                        token = settings.listenBrainzToken,
                        artist = edited.artist,
                        track = edited.title,
                        album = edited.album.ifBlank { null },
                        durationMs = durationMs.takeIf { it > 0L },
                    )
                }
            }
        }

        val lastFmResult = lastFmSignedCall(
            method = "track.updateNowPlaying",
            extra = buildMap {
                put("artist", edited.artist)
                put("track", edited.title)
                if (edited.album.isNotBlank()) put("album", edited.album)
                if (durationMs > 0L) put("duration", (durationMs / 1000L).toString())
            },
        )
        if (lastFmResult is RemoteResult.Success) {
            lastNowPlayingKey = key
        }
        return lastFmResult
    }

    fun clearNowPlaying(sourcePackage: String? = null) {
        val current = _nowPlaying.value
        if (sourcePackage == null || current?.sourcePackage == sourcePackage) {
            _nowPlaying.value = null
            lastNowPlayingKey = null
        }
    }

    /**
     * Records a scrobble persistently in Room (after applying Pano edit rules and
     * duplicate suppression), then immediately attempts remote sync to Last.fm and
     * ListenBrainz plus flushing any previously pending items.
     */
    suspend fun recordAndSyncScrobble(
        artist: String,
        track: String,
        album: String? = null,
        genre: String = "",
        artworkUrl: String? = null,
        durationMs: Long = 0L,
        listenedMs: Long = 0L,
        startedAtSec: Long = System.currentTimeMillis() / 1000L,
        sourcePackage: String = context.packageName,
        sourceAppName: String = DEFAULT_OWN_APP_NAME,
    ): RemoteResult = withContext(Dispatchers.IO) {
        if (artist.equals("Podcast", ignoreCase = true) || track.equals("Podcast", ignoreCase = true)) {
            return@withContext RemoteResult.Success
        }

        val rules = runCatching { editRuleDao.getEnabledRules() }.getOrDefault(emptyList())
        val cleaned = ScrobbleRulesEngine.cleanMetadata(ScrobbleMetadata(track, artist, album.orEmpty()))
        val edited = ScrobbleRulesEngine.applyRules(cleaned, rules)
        if (edited.artist.isBlank() || edited.title.isBlank()) {
            return@withContext RemoteResult.Success
        }

        val nowMs = System.currentTimeMillis()
        val startedAtMs = if (startedAtSec > 0L) startedAtSec * 1000L else nowMs
        val trackKey = normalizeScrobbleTrackKey(edited.artist, edited.title)

        // Deduplicate rapid/mirror scrobbles within +/- 120 seconds
        val existing = eventDao.findRecentDuplicate(
            trackKey = trackKey,
            minTimeMs = nowMs - ScrobbleRulesEngine.DEFAULT_DEDUP_WINDOW_MS,
            maxTimeMs = nowMs + ScrobbleRulesEngine.DEFAULT_DEDUP_WINDOW_MS,
        )
        if (existing != null) {
            return@withContext RemoteResult.Success
        }

        val session = sessionPreferences.awaitLoadedSession()
        val settings = currentScrobblerSettings()
        val wantsLastFm = session.apiKey.isNotBlank() && session.apiSecret.isNotBlank() && session.sessionKey.isNotBlank()
        val wantsListenBrainz = settings.listenBrainzEnabled && settings.listenBrainzToken.isNotBlank()

        val effectiveDuration = if (durationMs > 0L) durationMs else 210_000L
        val effectiveListened = if (listenedMs > 0L) listenedMs else effectiveDuration

        val insertedId = eventDao.insert(
            ScrobbleEventEntity(
                trackKey = trackKey,
                title = edited.title,
                artist = edited.artist,
                album = edited.album,
                genre = genre.trim(),
                artworkUrl = artworkUrl,
                durationMs = effectiveDuration,
                listenedMs = effectiveListened,
                startedAtMs = startedAtMs,
                scrobbledAtMs = nowMs,
                sourcePackage = sourcePackage,
                sourceAppName = sourceAppName.ifBlank { DEFAULT_OWN_APP_NAME },
                isLoved = false,
                lastFmSyncStatus = if (wantsLastFm) ScrobbleSyncStatus.PENDING else ScrobbleSyncStatus.SKIPPED,
                listenBrainzSyncStatus = if (wantsListenBrainz) ScrobbleSyncStatus.PENDING else ScrobbleSyncStatus.SKIPPED,
                retryCount = 0,
            ),
        )
        _scrobbleEvents.tryEmit(Unit)

        // Attempt ListenBrainz submission asynchronously if configured
        if (wantsListenBrainz) {
            externalScope.launch(Dispatchers.IO) {
                submitListenBrainzForEvent(
                    id = insertedId,
                    token = settings.listenBrainzToken,
                    artist = edited.artist,
                    title = edited.title,
                    album = edited.album,
                    startedAtSec = startedAtMs / 1000L,
                    durationMs = effectiveDuration,
                    currentRetry = 0,
                )
            }
        }

        if (!wantsLastFm) {
            return@withContext RemoteResult.NoSessionKey
        }

        // Flush older queued scrobbles first when not under active cooldown
        if (rateGuard.cooldownRemainingMs <= 0L) {
            runCatching { flushPendingQueue(excludeId = insertedId) }
        }

        val lastFmRes = lastFmSignedCall(
            method = "track.scrobble",
            extra = buildMap {
                put("artist", edited.artist)
                put("track", edited.title)
                put("timestamp", (startedAtMs / 1000L).toString())
                if (edited.album.isNotBlank()) put("album", edited.album)
            },
        )
        when (lastFmRes) {
            RemoteResult.Success -> {
                eventDao.updateLastFmStatus(insertedId, ScrobbleSyncStatus.SYNCED, 0)
                _scrobbleEvents.tryEmit(Unit)
            }
            RemoteResult.NoSessionKey -> {
                eventDao.updateLastFmStatus(insertedId, ScrobbleSyncStatus.SKIPPED, 0)
            }
            is RemoteResult.Failed -> {
                val status = if (lastFmRes.retryable) ScrobbleSyncStatus.PENDING else ScrobbleSyncStatus.FAILED
                eventDao.updateLastFmStatus(insertedId, status, 1)
            }
        }
        lastFmRes
    }

    /**
     * Flushes all persisted `PENDING` scrobbles to Last.fm and ListenBrainz.
     * Safe to invoke concurrently; guarded by [flushMutex].
     */
    suspend fun flushPendingQueue(excludeId: Long = -1L): SyncFlushReport = withContext(Dispatchers.IO) {
        flushMutex.withLock {
            var lastFmSynced = 0
            var listenBrainzSynced = 0

            val session = sessionPreferences.awaitLoadedSession()
            val hasLastFmSession = session.apiKey.isNotBlank() &&
                session.apiSecret.isNotBlank() &&
                session.sessionKey.isNotBlank()

            if (hasLastFmSession && rateGuard.cooldownRemainingMs <= 0L) {
                val pendingLastFm = eventDao.getPendingLastFm(MAX_RETRIES, FLUSH_BATCH_SIZE)
                    .filter { it.id != excludeId }
                for (event in pendingLastFm) {
                    val tsSec = ((event.startedAtMs.takeIf { it > 0L } ?: event.scrobbledAtMs) / 1000L)
                        .coerceAtLeast(1L)
                    val res = lastFmSignedCall(
                        method = "track.scrobble",
                        extra = buildMap {
                            put("artist", event.artist)
                            put("track", event.title)
                            put("timestamp", tsSec.toString())
                            if (event.album.isNotBlank()) put("album", event.album)
                        },
                    )
                    when (res) {
                        RemoteResult.Success -> {
                            eventDao.updateLastFmStatus(event.id, ScrobbleSyncStatus.SYNCED, event.retryCount)
                            lastFmSynced++
                            _scrobbleEvents.tryEmit(Unit)
                        }
                        RemoteResult.NoSessionKey -> break
                        is RemoteResult.Failed -> {
                            val nextRetry = event.retryCount + 1
                            val nextStatus = if (res.retryable && nextRetry < MAX_RETRIES) {
                                ScrobbleSyncStatus.PENDING
                            } else {
                                ScrobbleSyncStatus.FAILED
                            }
                            eventDao.updateLastFmStatus(event.id, nextStatus, nextRetry)
                            if (res.retryable) break
                        }
                    }
                }
            }

            val settings = currentScrobblerSettings()
            if (settings.listenBrainzEnabled && settings.listenBrainzToken.isNotBlank()) {
                val pendingLb = eventDao.getPendingListenBrainz(MAX_RETRIES, FLUSH_BATCH_SIZE)
                    .filter { it.id != excludeId }
                if (pendingLb.isNotEmpty()) {
                    val batchRes = listenBrainzApi.submitImportBatch(settings.listenBrainzToken, pendingLb)
                    when (batchRes) {
                        ListenBrainzApi.SubmitResult.Success -> {
                            pendingLb.forEach { ev ->
                                eventDao.updateListenBrainzStatus(ev.id, ScrobbleSyncStatus.SYNCED, ev.retryCount)
                                listenBrainzSynced++
                            }
                        }
                        ListenBrainzApi.SubmitResult.Disabled -> Unit
                        is ListenBrainzApi.SubmitResult.Failed -> {
                            pendingLb.forEach { ev ->
                                val nextRetry = ev.retryCount + 1
                                val nextStatus = if (batchRes.retryable && nextRetry < MAX_RETRIES) {
                                    ScrobbleSyncStatus.PENDING
                                } else {
                                    ScrobbleSyncStatus.FAILED
                                }
                                eventDao.updateListenBrainzStatus(ev.id, nextStatus, nextRetry)
                            }
                        }
                    }
                }
            }

            val remaining = eventDao.getPendingLastFm(MAX_RETRIES, 500).size +
                eventDao.getPendingListenBrainz(MAX_RETRIES, 500).size
            SyncFlushReport(
                lastFmSynced = lastFmSynced,
                listenBrainzSynced = listenBrainzSynced,
                remainingPending = remaining,
            )
        }
    }

    private suspend fun submitListenBrainzForEvent(
        id: Long,
        token: String,
        artist: String,
        title: String,
        album: String,
        startedAtSec: Long,
        durationMs: Long,
        currentRetry: Int,
    ) {
        val result = listenBrainzApi.submitSingle(
            token = token,
            artist = artist,
            track = title,
            album = album.ifBlank { null },
            listenedAtSec = startedAtSec.coerceAtLeast(1L),
            durationMs = durationMs.takeIf { it > 0L },
        )
        when (result) {
            ListenBrainzApi.SubmitResult.Success ->
                eventDao.updateListenBrainzStatus(id, ScrobbleSyncStatus.SYNCED, currentRetry)
            ListenBrainzApi.SubmitResult.Disabled ->
                eventDao.updateListenBrainzStatus(id, ScrobbleSyncStatus.SKIPPED, currentRetry)
            is ListenBrainzApi.SubmitResult.Failed -> {
                val nextRetry = currentRetry + 1
                val nextStatus = if (result.retryable && nextRetry < MAX_RETRIES) {
                    ScrobbleSyncStatus.PENDING
                } else {
                    ScrobbleSyncStatus.FAILED
                }
                eventDao.updateListenBrainzStatus(id, nextStatus, nextRetry)
            }
        }
    }

    /**
     * Two-way Last.fm Love/Unlove: updates local Room history immediately and
     * pushes `track.love` / `track.unlove` when two-way love sync is enabled.
     */
    suspend fun setTrackLoved(
        artist: String,
        title: String,
        loved: Boolean,
        eventId: Long? = null,
    ): RemoteResult = withContext(Dispatchers.IO) {
        val key = normalizeScrobbleTrackKey(artist, title)
        if (eventId != null) {
            eventDao.updateLovedById(eventId, loved)
        }
        eventDao.updateLovedByTrackKey(key, loved)
        _scrobbleEvents.tryEmit(Unit)

        val settings = currentScrobblerSettings()
        if (!settings.twoWayLoveSync) return@withContext RemoteResult.Success

        lastFmSignedCall(
            method = if (loved) "track.love" else "track.unlove",
            extra = mapOf(
                "artist" to artist.trim(),
                "track" to title.trim(),
            ),
        )
    }

    /**
     * Pulls the user's loved tracks from Last.fm (`user.getLovedTracks`) and
     * reconciles `isLoved` on matching events in [OnyxScrobbleDatabase].
     */
    suspend fun pullLovedTracksFromLastFm(limit: Int = 200): Result<Int> = withContext(Dispatchers.IO) {
        val session = sessionPreferences.awaitLoadedSession()
        if (session.username.isBlank() || session.apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Last.fm account not connected"))
        }
        try {
            val response = lastFmApi.get(
                mapOf(
                    "method" to "user.getLovedTracks",
                    "user" to session.username,
                    "api_key" to session.apiKey,
                    "limit" to limit.coerceIn(1, 500).toString(),
                    "format" to "json",
                ),
            )
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Last.fm HTTP ${response.code()}"))
            }
            val text = response.body()?.string().orEmpty()
            val root = json.parseToJsonElement(text).jsonObject
            val lovedTracksObj = root["lovedtracks"] as? JsonObject
            val tracksArray = lovedTracksObj?.get("track") as? JsonArray ?: JsonArray(emptyList())
            var syncedCount = 0
            for (item in tracksArray) {
                val obj = item as? JsonObject ?: continue
                val title = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val artistObj = obj["artist"] as? JsonObject
                val artist = (artistObj?.get("name") as? JsonPrimitive)?.contentOrNull?.trim()
                    ?: (obj["artist"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (title.isNotBlank() && artist.isNotBlank()) {
                    eventDao.updateLovedByTrackKey(normalizeScrobbleTrackKey(artist, title), true)
                    syncedCount++
                }
            }
            scrobblerPreferences.setLastLovedSyncAt(System.currentTimeMillis())
            _scrobbleEvents.tryEmit(Unit)
            Result.success(syncedCount)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Edits a scrobble event in place and optionally saves a Pano-style edit rule
     * (with optional retroactive application across all matching history rows).
     */
    suspend fun editScrobbleEvent(
        event: ScrobbleEventEntity,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        saveAsAutoRule: Boolean = false,
        applyRetroactively: Boolean = false,
    ): Int = withContext(Dispatchers.IO) {
        val cleanTitle = newTitle.trim().ifBlank { event.title }
        val cleanArtist = newArtist.trim().ifBlank { event.artist }
        val cleanAlbum = newAlbum.trim()
        eventDao.updateMetadata(
            id = event.id,
            trackKey = normalizeScrobbleTrackKey(cleanArtist, cleanTitle),
            title = cleanTitle,
            artist = cleanArtist,
            album = cleanAlbum,
        )
        var affected = 1
        if (saveAsAutoRule) {
            val rule = ScrobbleEditRuleEntity(
                matchArtist = event.artist,
                matchTitle = if (event.title != cleanTitle) event.title else "",
                matchAlbum = if (event.album != cleanAlbum && event.album.isNotBlank()) event.album else "",
                replaceArtist = if (event.artist != cleanArtist) cleanArtist else "",
                replaceTitle = if (event.title != cleanTitle) cleanTitle else "",
                replaceAlbum = if (event.album != cleanAlbum) cleanAlbum else "",
                isRegex = false,
                enabled = true,
            )
            editRuleDao.upsert(rule)
            if (applyRetroactively) {
                affected += applyRuleRetroactively(rule)
            }
        }
        _scrobbleEvents.tryEmit(Unit)
        affected
    }

    suspend fun saveEditRule(rule: ScrobbleEditRuleEntity, applyRetroactively: Boolean = false): Int =
        withContext(Dispatchers.IO) {
            editRuleDao.upsert(rule)
            val updated = if (applyRetroactively) applyRuleRetroactively(rule) else 0
            _scrobbleEvents.tryEmit(Unit)
            updated
        }

    suspend fun deleteEditRule(ruleId: Long) = withContext(Dispatchers.IO) {
        editRuleDao.deleteById(ruleId)
    }

    suspend fun applyRuleRetroactively(rule: ScrobbleEditRuleEntity): Int = withContext(Dispatchers.IO) {
        val all = eventDao.getAll()
        var updatedCount = 0
        for (ev in all) {
            val before = ScrobbleMetadata(ev.title, ev.artist, ev.album)
            val after = ScrobbleRulesEngine.applyRules(before, listOf(rule))
            if (after != before) {
                eventDao.updateMetadata(
                    id = ev.id,
                    trackKey = normalizeScrobbleTrackKey(after.artist, after.title),
                    title = after.title,
                    artist = after.artist,
                    album = after.album,
                )
                updatedCount++
            }
        }
        if (updatedCount > 0) _scrobbleEvents.tryEmit(Unit)
        updatedCount
    }

    suspend fun deleteScrobble(id: Long) = withContext(Dispatchers.IO) {
        eventDao.deleteById(id)
        _scrobbleEvents.tryEmit(Unit)
    }

    suspend fun importScrobbles(rawText: String): ScrobbleImportExport.ImportResult =
        withContext(Dispatchers.IO) {
            val parsed = ScrobbleImportExport.importFromText(rawText)
            if (parsed.events.isEmpty()) return@withContext parsed

            val rules = runCatching { editRuleDao.getEnabledRules() }.getOrDefault(emptyList())
            val existing = eventDao.getAll()
            val existingKeys = existing.map { "${it.trackKey}|${it.scrobbledAtMs / 60_000L}" }.toHashSet()

            val toInsert = mutableListOf<ScrobbleEventEntity>()
            var skippedDupes = 0
            for (rawEvent in parsed.events) {
                val edited = ScrobbleRulesEngine.applyRules(
                    ScrobbleMetadata(rawEvent.title, rawEvent.artist, rawEvent.album),
                    rules,
                )
                val key = normalizeScrobbleTrackKey(edited.artist, edited.title)
                val dedupBucket = "$key|${rawEvent.scrobbledAtMs / 60_000L}"
                if (!existingKeys.add(dedupBucket)) {
                    skippedDupes++
                    continue
                }
                toInsert += rawEvent.copy(
                    trackKey = key,
                    title = edited.title,
                    artist = edited.artist,
                    album = edited.album,
                )
            }
            if (toInsert.isNotEmpty()) {
                toInsert.chunked(250).forEach { chunk -> eventDao.insertAll(chunk) }
                _scrobbleEvents.tryEmit(Unit)
            }
            parsed.copy(
                events = toInsert,
                skippedCount = parsed.skippedCount + skippedDupes,
            )
        }

    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        ScrobbleImportExport.exportToJson(eventDao.getAll())
    }

    suspend fun exportCsv(): String = withContext(Dispatchers.IO) {
        ScrobbleImportExport.exportToCsv(eventDao.getAll())
    }

    suspend fun setAppAllowed(packageName: String, appName: String, allowed: Boolean) =
        withContext(Dispatchers.IO) {
            appFilterDao.upsert(
                ScrobbleAppFilterEntity(
                    packageName = packageName,
                    appName = appName,
                    isAllowed = allowed,
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
            scrobblerPreferences.setPackageAllowed(packageName, allowed)
        }

    private suspend fun currentScrobblerSettings(): ScrobblerSettings =
        runCatching { scrobblerPreferences.settings.first() }.getOrDefault(ScrobblerSettings())

    private suspend fun lastFmSignedCall(
        method: String,
        extra: Map<String, String>,
    ): RemoteResult = withContext(Dispatchers.IO) {
        lastFmMutex.withLock {
            val session = sessionPreferences.awaitLoadedSession()
            if (session.apiKey.isBlank() || session.apiSecret.isBlank()) {
                return@withLock RemoteResult.Failed("API credentials missing")
            }
            if (session.sessionKey.isBlank()) {
                return@withLock RemoteResult.NoSessionKey
            }

            if (!rateGuard.suspendAwaitClearance(WRITE_COOLDOWN_WAIT_MS)) {
                return@withLock RemoteResult.Failed(
                    "Rate limit cooldown active — queued for retry",
                    retryable = true,
                )
            }

            val sinceLastWrite = SystemClock.elapsedRealtime() - lastWriteAtElapsed
            if (sinceLastWrite in 1 until WRITE_SPACING_MS) {
                delay(WRITE_SPACING_MS - sinceLastWrite)
            }

            try {
                val signParams = extra + mapOf(
                    "method" to method,
                    "sk" to session.sessionKey,
                    "api_key" to session.apiKey,
                )
                val sig = LastFmSigner.sign(signParams, session.apiSecret)
                val body = signParams + mapOf("api_sig" to sig, "format" to "json")
                val response = lastFmApi.post(body)
                lastWriteAtElapsed = SystemClock.elapsedRealtime()

                if (response.code() == 429) {
                    rateGuard.onRequestLimited()
                    return@withLock RemoteResult.Failed("Last.fm rate limited (429)", retryable = true)
                }

                val text = response.body()?.string()
                    ?: return@withLock RemoteResult.Failed("Empty response from Last.fm")
                val parsed = json.parseToJsonElement(text).jsonObject
                val errorCode = (parsed["error"] as? JsonPrimitive)?.intOrNull
                if (errorCode != null) {
                    if (errorCode == 29 || errorCode == 11 || errorCode == 16) {
                        if (errorCode == 29) rateGuard.onRequestLimited()
                        return@withLock RemoteResult.Failed(
                            "Last.fm temporary error ($errorCode)",
                            retryable = true,
                        )
                    }
                    RemoteResult.Failed("Last.fm error $errorCode")
                } else {
                    rateGuard.onRequestSucceeded()
                    RemoteResult.Success
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: IOException) {
                RemoteResult.Failed(e.message ?: "Could not reach Last.fm", retryable = true)
            } catch (e: Exception) {
                RemoteResult.Failed(e.message ?: "Could not reach Last.fm")
            }
        }
    }

    private companion object {
        const val TAG = "ScrobbleSyncEngine"
        const val DEFAULT_OWN_APP_NAME = "Onyx Music"
        const val WRITE_COOLDOWN_WAIT_MS = 25_000L
        const val WRITE_SPACING_MS = 700L
        const val MAX_RETRIES = 8
        const val FLUSH_BATCH_SIZE = 50
    }
}
