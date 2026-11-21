package com.lastwave.app.playback.resolve

import android.util.Log
import com.lastwave.app.data.music.YouTubeAudioStream
import com.lastwave.app.data.music.isCooperativeCancellation
import com.lastwave.app.data.music.isExpectedAudioResolutionFailure
import com.lastwave.app.data.music.resolutionFailureDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

data class PrefetchItem(
    val identity: TrackIdentity,
    val durationMs: Long?,
    val onReady: suspend (StreamCache) -> Unit = {},
)

/**
 * Maintains a bounded lookahead prefetch window (default 3 upcoming tracks,
 * max 2 concurrent resolutions) with a short negative-cache TTL and
 * non-blocking cancellation when the user skips rapidly.
 *
 * It never claims the playback generation and never replaces a cache entry
 * playback has already stored.
 */
class PrefetchResolver(
    private val cache: PlaybackStreamCache,
    private val logger: ResolverLogger,
    private val maxLookahead: Int = DEFAULT_LOOKAHEAD,
    maxConcurrent: Int = DEFAULT_CONCURRENT_PREFETCH,
    private val negativeCacheTtlMs: Long = DEFAULT_NEGATIVE_CACHE_TTL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val gate = Any()
    private val jobs = LinkedHashMap<String, Job>()
    private val delivered = LinkedHashSet<String>()
    private val negativeCache = ConcurrentHashMap<String, Long>()
    private val concurrency = Semaphore(maxConcurrent.coerceAtLeast(1))

    fun cancel() {
        val toCancel = synchronized(gate) {
            val snapshot = jobs.values.toList()
            jobs.clear()
            snapshot
        }
        toCancel.forEach { it.cancel() }
    }

    fun clearNegativeCache(youtubeVideoId: String) {
        negativeCache.remove(youtubeVideoId)
    }

    internal fun isNegativelyCached(youtubeVideoId: String): Boolean {
        val failedAt = negativeCache[youtubeVideoId] ?: return false
        if (nowMs() - failedAt < negativeCacheTtlMs) return true
        negativeCache.remove(youtubeVideoId, failedAt)
        return false
    }

    internal fun activeVideoIds(): Set<String> = synchronized(gate) {
        jobs.entries.filter { it.value.isActive }.map { it.key }.toSet()
    }

    fun enqueue(
        scope: CoroutineScope,
        identity: TrackIdentity,
        durationMs: Long?,
        activePlayback: () -> TrackIdentity?,
        extract: suspend (youtubeVideoId: String) -> YouTubeAudioStream,
        onReady: suspend (StreamCache) -> Unit,
    ) {
        enqueueWindow(
            scope = scope,
            items = listOf(PrefetchItem(identity, durationMs, onReady)),
            activePlayback = activePlayback,
            extract = extract,
        )
    }

    /**
     * Reconciles active prefetch jobs against [items] (bounded to [maxLookahead]).
     * Jobs outside the new window are cancelled immediately without blocking.
     * Already-active jobs still inside the window continue uninterrupted.
     */
    fun enqueueWindow(
        scope: CoroutineScope,
        items: List<PrefetchItem>,
        activePlayback: () -> TrackIdentity?,
        extract: suspend (youtubeVideoId: String) -> YouTubeAudioStream,
    ) {
        val activeId = activePlayback()?.youtubeVideoId
        val window = LinkedHashMap<String, PrefetchItem>()
        for (item in items) {
            val videoId = item.identity.youtubeVideoId
            if (videoId.isBlank() || videoId == activeId) continue
            if (videoId !in window) {
                window[videoId] = item
                if (window.size >= maxLookahead.coerceAtLeast(1)) break
            }
        }

        val toCancel = mutableListOf<Job>()
        val toLaunch = mutableListOf<PrefetchItem>()

        synchronized(gate) {
            // Cancel any in-flight prefetch outside the new lookahead window
            // without blocking so rapid skips stay instantaneous.
            val iterator = jobs.entries.iterator()
            while (iterator.hasNext()) {
                val (videoId, job) = iterator.next()
                if (videoId !in window || !job.isActive) {
                    if (job.isActive) toCancel += job
                    iterator.remove()
                }
            }

            for ((videoId, item) in window) {
                val existing = jobs[videoId]
                if (existing?.isActive == true) {
                    safeLogInfo("[PREFETCH_SKIPPED_ALREADY_ACTIVE] youtubeVideoId=$videoId")
                    continue
                }
                if (videoId in delivered && cache.get(item.identity) != null) {
                    safeLogInfo("[PREFETCH_SKIPPED_CACHED] youtubeVideoId=$videoId")
                    continue
                }
                if (isNegativelyCached(videoId)) {
                    safeLogInfo("[PREFETCH_SKIPPED_NEGATIVE_CACHE] youtubeVideoId=$videoId")
                    continue
                }
                toLaunch += item
            }
        }

        toCancel.forEach { it.cancel() }

        for (item in toLaunch) {
            val videoId = item.identity.youtubeVideoId
            safeLogInfo("[PREFETCH_SCHEDULED] youtubeVideoId=$videoId")
            lateinit var launched: Job
            launched = scope.launch(Dispatchers.IO) {
                runPrefetchItem(item, activePlayback, extract)
            }
            val duplicate = synchronized(gate) {
                val existing = jobs[videoId]
                if (existing?.isActive == true) {
                    true
                } else {
                    jobs[videoId] = launched
                    false
                }
            }
            if (duplicate) {
                launched.cancel()
            } else {
                launched.invokeOnCompletion {
                    synchronized(gate) {
                        if (jobs[videoId] === launched) {
                            jobs.remove(videoId)
                        }
                    }
                }
            }
        }
    }

    private suspend fun runPrefetchItem(
        item: PrefetchItem,
        activePlayback: () -> TrackIdentity?,
        extract: suspend (youtubeVideoId: String) -> YouTubeAudioStream,
    ) {
        val identity = item.identity
        val videoId = identity.youtubeVideoId
        val durationMs = item.durationMs
        val started = System.nanoTime()
        try {
            logger.event(ResolveEvent.START, identity, 0L, durationMs, "prefetch")
            val hit = cache.get(identity)
            if (hit != null) {
                logger.event(
                    ResolveEvent.CACHE_HIT,
                    identity,
                    elapsedSince(started),
                    hit.durationMs ?: durationMs,
                    "prefetch",
                )
                if (activePlayback()?.youtubeVideoId == videoId) return
                item.onReady(hit)
                markDelivered(videoId)
                safeLogInfo("[PREFETCH_COMPLETED] youtubeVideoId=$videoId elapsedMs=${elapsedSince(started)}")
                return
            }
            logger.event(ResolveEvent.CACHE_MISS, identity, elapsedSince(started), durationMs, "prefetch")
            val extracted = concurrency.withPermit {
                currentCoroutineContext().ensureActive()
                if (activePlayback()?.youtubeVideoId == videoId) return
                extract(videoId)
            }
            currentCoroutineContext().ensureActive()
            if (extracted.videoId != videoId) {
                logger.event(
                    ResolveEvent.IGNORED_STALE,
                    identity,
                    elapsedSince(started),
                    durationMs,
                    "resolvedTrackId=${extracted.videoId}",
                )
                return
            }
            if (activePlayback()?.youtubeVideoId == videoId) {
                logger.event(
                    ResolveEvent.IGNORED_STALE,
                    identity,
                    elapsedSince(started),
                    durationMs,
                    "playback-owns",
                )
                return
            }
            val entry = extracted.toStreamCache(identity)
            val stored = if (cache.putIfAbsent(entry)) entry else cache.get(identity) ?: return
            negativeCache.remove(videoId)
            logger.event(
                ResolveEvent.SUCCESS,
                identity,
                elapsedSince(started),
                stored.durationMs ?: durationMs,
                "prefetch",
            )
            if (activePlayback()?.youtubeVideoId == videoId) return
            item.onReady(stored)
            markDelivered(videoId)
            safeLogInfo("[PREFETCH_COMPLETED] youtubeVideoId=$videoId elapsedMs=${elapsedSince(started)}")
        } catch (error: Throwable) {
            if (error.isCooperativeCancellation()) {
                logger.event(
                    ResolveEvent.CANCELLED,
                    identity,
                    elapsedSince(started),
                    durationMs,
                    "prefetch flight-continues",
                )
                throw error
            }
            recordNegativeCache(videoId)
            val detail = resolutionFailureDetail(error)
            logger.event(ResolveEvent.FAILED, identity, elapsedSince(started), durationMs, "prefetch $detail")
            safeLogWarn("[RESOLVE_FAILED] youtubeVideoId=$videoId $detail")
            if (!error.isExpectedAudioResolutionFailure()) throw error
        }
    }

    private fun markDelivered(videoId: String) {
        synchronized(gate) {
            delivered.remove(videoId)
            delivered += videoId
            while (delivered.size > MAX_DELIVERED_TRACKED) {
                val oldest = delivered.iterator().next()
                delivered.remove(oldest)
            }
        }
    }

    private fun recordNegativeCache(videoId: String) {
        val now = nowMs()
        negativeCache[videoId] = now
        if (negativeCache.size > MAX_NEGATIVE_CACHE_ENTRIES) {
            negativeCache.entries.removeIf { now - it.value >= negativeCacheTtlMs }
        }
    }

    private fun safeLogInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun safeLogWarn(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    companion object {
        private const val TAG = "LastWaveResolver"
        const val DEFAULT_LOOKAHEAD = 3
        const val DEFAULT_CONCURRENT_PREFETCH = 2
        const val DEFAULT_NEGATIVE_CACHE_TTL_MS = 15_000L
        private const val MAX_DELIVERED_TRACKED = 64
        private const val MAX_NEGATIVE_CACHE_ENTRIES = 64
    }
}
