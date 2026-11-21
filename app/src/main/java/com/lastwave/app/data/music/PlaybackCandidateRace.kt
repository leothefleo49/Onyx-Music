package com.lastwave.app.data.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * An empty result is a miss; only a playable candidate wins and cancels the other path.
 * The loser is cancelled but not awaited: a blocking extractor that ignores
 * cancellation held a finished winner back until its own request returned.
 */
internal suspend fun <T> racePlaybackCandidates(
    first: suspend () -> T?,
    second: suspend () -> T?,
): T? {
    val candidates = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
    val a = candidates.async { first() }
    val b = candidates.async { second() }
    return try {
        select {
            a.onAwait { it ?: b.await() }
            b.onAwait { it ?: a.await() }
        }
    } finally {
        candidates.cancel()
    }
}

/**
 * Races an arbitrary list of candidate resolvers concurrently. The first non-null
 * result wins immediately and cancels all remaining contenders without waiting
 * for blocked losers. Individual contender errors are contained so one failing
 * client never cancels healthy siblings.
 */
internal suspend fun <T> racePlaybackCandidates(
    contenders: List<suspend () -> T?>,
    totalTimeoutMs: Long? = null,
): T? {
    if (contenders.isEmpty()) return null
    if (contenders.size == 1) {
        return runContender(contenders[0], totalTimeoutMs)
    }
    val raceScope = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
    val winners = Channel<T>(capacity = contenders.size)
    val remaining = AtomicInteger(contenders.size)
    try {
        for (contender in contenders) {
            raceScope.launch {
                val result = runContender(contender, totalTimeoutMs)
                if (result != null) {
                    winners.trySend(result)
                }
            }.invokeOnCompletion {
                if (remaining.decrementAndGet() == 0) {
                    winners.close()
                }
            }
        }
        return if (totalTimeoutMs != null && totalTimeoutMs > 0L) {
            withTimeoutOrNull(totalTimeoutMs) { winners.receiveCatching().getOrNull() }
        } else {
            winners.receiveCatching().getOrNull()
        }
    } finally {
        winners.close()
        raceScope.cancel()
    }
}

private suspend fun <T> runContender(
    contender: suspend () -> T?,
    timeoutMs: Long?,
): T? = try {
    if (timeoutMs != null && timeoutMs > 0L) {
        withTimeoutOrNull(timeoutMs) { contender() }
    } else {
        contender()
    }
} catch (cancellation: CancellationException) {
    currentCoroutineContext().ensureActive()
    null
} catch (_: Throwable) {
    null
}
