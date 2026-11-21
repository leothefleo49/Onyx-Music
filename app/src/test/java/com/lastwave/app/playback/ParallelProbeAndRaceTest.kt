package com.lastwave.app.playback

import com.lastwave.app.data.music.racePlaybackCandidates
import com.lastwave.app.playback.resolve.ParallelProbe
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParallelProbeAndRaceTest {

    private data class Candidate(val id: String, val url: String)

    @Test
    fun parallelProbeReturnsFastHealthyCandidateWithoutWaitingForStalledOne() = runBlocking {
        val slowStarted = CompletableDeferred<Unit>()
        val slowCancelled = CompletableDeferred<Unit>()
        val candidates = listOf(
            Candidate("stalled", "https://cdn.example/stalled"),
            Candidate("fast-ok", "https://cdn.example/fast-ok"),
            Candidate("rejected", "https://cdn.example/rejected"),
        )
        val started = System.nanoTime()
        val winner = ParallelProbe.selectFirstPlayable(
            candidates = candidates,
            urlOf = { it.url },
            probe = { candidate ->
                when (candidate.id) {
                    "stalled" -> try {
                        slowStarted.complete(Unit)
                        delay(5_000L)
                        true
                    } catch (e: CancellationException) {
                        slowCancelled.complete(Unit)
                        throw e
                    }
                    "fast-ok" -> {
                        slowStarted.await()
                        delay(25L)
                        true
                    }
                    else -> false
                }
            },
            limit = 4,
            perProbeTimeoutMs = 1_500L,
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertEquals("fast-ok", winner?.id)
        assertTrue("Expected fast parallel completion, took ${elapsedMs}ms", elapsedMs < 800L)
        withTimeout(1_000L) { slowCancelled.await() }
    }

    @Test
    fun parallelProbeEnforcesPerProbeTimeoutAndReturnsNullWhenAllStall() = runBlocking {
        val candidates = listOf(
            Candidate("slow-1", "https://cdn.example/slow-1"),
            Candidate("slow-2", "https://cdn.example/slow-2"),
        )
        val started = System.nanoTime()
        val winner = ParallelProbe.selectFirstPlayable(
            candidates = candidates,
            urlOf = { it.url },
            probe = {
                delay(5_000L)
                true
            },
            limit = 4,
            perProbeTimeoutMs = 150L,
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertNull(winner)
        assertTrue("Expected bounded timeout, took ${elapsedMs}ms", elapsedMs < 1_000L)
    }

    @Test
    fun parallelProbeDeduplicatesUrlsAndHonorsLimit() = runBlocking {
        val probed = AtomicInteger(0)
        val candidates = listOf(
            Candidate("a1", "https://cdn.example/dup"),
            Candidate("a2", "https://cdn.example/dup"),
            Candidate("b", "https://cdn.example/b"),
            Candidate("c", "https://cdn.example/c"),
        )
        val winner = ParallelProbe.selectFirstPlayable(
            candidates = candidates,
            urlOf = { it.url },
            probe = {
                probed.incrementAndGet()
                false
            },
            limit = 2,
            perProbeTimeoutMs = 500L,
        )
        assertNull(winner)
        assertEquals(2, probed.get())
    }

    @Test
    fun nWayRaceReturnsFastestWinnerAndCancelsSlowerContenders() = runBlocking {
        val slowStarted = CompletableDeferred<Unit>()
        val slowCancelled = CompletableDeferred<Unit>()
        val started = System.nanoTime()
        val winner = racePlaybackCandidates(
            contenders = listOf(
                {
                    try {
                        slowStarted.complete(Unit)
                        delay(4_000L)
                        "slow"
                    } catch (e: CancellationException) {
                        slowCancelled.complete(Unit)
                        throw e
                    }
                },
                {
                    throw IllegalStateException("broken client")
                },
                {
                    slowStarted.await()
                    delay(30L)
                    "fast"
                },
            ),
            totalTimeoutMs = 1_500L,
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertEquals("fast", winner)
        assertTrue("Expected rapid race win, took ${elapsedMs}ms", elapsedMs < 800L)
        withTimeout(1_000L) { slowCancelled.await() }
    }

    @Test
    fun nWayRaceReturnsNullWhenTotalTimeoutExpires() = runBlocking {
        val started = System.nanoTime()
        val winner = racePlaybackCandidates(
            contenders = listOf(
                { delay(5_000L); "a" },
                { delay(5_000L); "b" },
            ),
            totalTimeoutMs = 150L,
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertNull(winner)
        assertTrue("Expected total timeout to fire promptly, took ${elapsedMs}ms", elapsedMs < 1_000L)
    }
}
