package com.lastwave.app.playback.resolve

import com.lastwave.app.data.music.YouTubeAudioStream
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * Playback resolver always wins. Prefetch maintains a bounded multi-track
 * lookahead window (up to 3 tracks) and cancels non-matching jobs without
 * blocking when the user selects or skips tracks.
 */
class StreamResolveCoordinator(
    cacheDir: File,
    private val scope: CoroutineScope,
    private val extract: suspend (youtubeVideoId: String) -> YouTubeAudioStream,
    private val logger: ResolverLogger = AndroidResolverLogger,
    negativeCacheTtlMs: Long = PrefetchResolver.DEFAULT_NEGATIVE_CACHE_TTL_MS,
    nowMs: () -> Long = System::currentTimeMillis,
    extractionTimeoutMs: Long = StreamExtraction.DEFAULT_FLIGHT_TIMEOUT_MS,
) {
    val cache = PlaybackStreamCache(cacheDir, nowMs = nowMs)
    val playback = PlaybackResolver(cache, logger)
    val prefetch = PrefetchResolver(
        cache = cache,
        logger = logger,
        negativeCacheTtlMs = negativeCacheTtlMs,
        nowMs = nowMs,
    )
    private val extraction = StreamExtraction(scope, cache, extract, extractionTimeoutMs)

    fun cancelAll(reason: String) {
        val previous = playback.requested
        playback.invalidate()
        prefetch.cancel()
        logger.event(ResolveEvent.CANCELLED, previous, 0L, null, reason)
    }

    /**
     * Resolves [identity] for the user-selected track.
     * Joins an in-flight resolve of the same identity. Any other identity is stale.
     */
    suspend fun resolvePlayback(identity: TrackIdentity, durationMs: Long?): StreamCache {
        prefetch.clearNegativeCache(identity.youtubeVideoId)
        val active = playback.requested
        val token = playback.claim(identity)
        if (active == null) prefetch.cancel()
        return playback.resolve(token, identity, durationMs, extraction::await)
    }

    /**
     * Resolves [identity] directly without acquiring the exclusive playback claim.
     * Used by background standby deck preloading and lookahead resolution to prevent
     * throwing [StaleResolveException] when another track is currently active.
     */
    suspend fun resolveTrackStream(identity: TrackIdentity, durationMs: Long?): StreamCache {
        val cached = cache.get(identity)?.takeIf { it.matches(identity) }
        if (cached != null) return cached
        val stream = extraction.await(identity.youtubeVideoId)
        val entry = stream.toStreamCache(identity)
        cache.putIfAbsent(entry)
        return entry
    }

    fun prefetchNext(
        identity: TrackIdentity,
        durationMs: Long?,
        onReady: suspend (StreamCache) -> Unit,
    ) {
        if (playback.requested?.youtubeVideoId == identity.youtubeVideoId) return
        prefetch.enqueue(
            scope = scope,
            identity = identity,
            durationMs = durationMs,
            activePlayback = playback::requested,
            extract = extraction::await,
            onReady = onReady,
        )
    }

    /**
     * Schedules a bounded 2-3 track lookahead prefetch window. Any previously
     * queued prefetch outside [items] is cancelled non-blockingly.
     */
    fun prefetchWindow(items: List<PrefetchItem>) {
        if (items.isEmpty()) {
            prefetch.cancel()
            return
        }
        prefetch.enqueueWindow(
            scope = scope,
            items = items,
            activePlayback = playback::requested,
            extract = extraction::await,
        )
    }
}
