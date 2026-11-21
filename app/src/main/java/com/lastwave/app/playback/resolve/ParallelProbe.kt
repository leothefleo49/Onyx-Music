package com.lastwave.app.playback.resolve

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Probes up to [limit] distinct candidate URLs concurrently with a tight
 * [perProbeTimeoutMs] budget per probe. The first playable candidate wins
 * immediately and cancels the remaining in-flight probes without waiting for
 * slow or blocked losers to unwind.
 */
object ParallelProbe {
    const val DEFAULT_PER_PROBE_TIMEOUT_MS = 2_200L
    const val DEFAULT_LIMIT = 4

    suspend fun <T> selectFirstPlayable(
        candidates: List<T>,
        urlOf: (T) -> String,
        probe: suspend (T) -> Boolean,
        limit: Int = DEFAULT_LIMIT,
        perProbeTimeoutMs: Long = DEFAULT_PER_PROBE_TIMEOUT_MS,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): T? {
        if (candidates.isEmpty() || limit <= 0) return null
        val unique = ArrayList<T>(minOf(limit, candidates.size))
        val seenUrls = HashSet<String>()
        for (candidate in candidates) {
            val url = urlOf(candidate)
            if (url.isBlank() || !seenUrls.add(url)) continue
            unique.add(candidate)
            if (unique.size >= limit) break
        }
        if (unique.isEmpty()) return null
        if (unique.size == 1) {
            val single = unique[0]
            val ok = runBoundedProbe(single, probe, perProbeTimeoutMs)
            return if (ok) single else null
        }

        val probeScope = CoroutineScope(
            currentCoroutineContext().minusKey(Job) + SupervisorJob() + dispatcher,
        )
        val winners = Channel<T>(capacity = unique.size)
        val remaining = AtomicInteger(unique.size)
        try {
            for (candidate in unique) {
                probeScope.launch {
                    val ok = runBoundedProbe(candidate, probe, perProbeTimeoutMs)
                    if (ok) {
                        winners.trySend(candidate)
                    }
                }.invokeOnCompletion {
                    if (remaining.decrementAndGet() == 0) {
                        winners.close()
                    }
                }
            }
            return winners.receiveCatching().getOrNull()
        } finally {
            winners.close()
            probeScope.cancel()
        }
    }

    private suspend fun <T> runBoundedProbe(
        candidate: T,
        probe: suspend (T) -> Boolean,
        perProbeTimeoutMs: Long,
    ): Boolean {
        return try {
            if (perProbeTimeoutMs > 0L) {
                withTimeoutOrNull(perProbeTimeoutMs) { probe(candidate) } == true
            } else {
                probe(candidate)
            }
        } catch (cancellation: CancellationException) {
            currentCoroutineContext().ensureActive()
            false
        } catch (_: Throwable) {
            false
        }
    }
}
