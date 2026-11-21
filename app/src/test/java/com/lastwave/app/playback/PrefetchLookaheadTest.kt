package com.lastwave.app.playback

import com.lastwave.app.data.local.HiResStart
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.music.YouTubeAudioStream
import com.lastwave.app.playback.resolve.PrefetchItem
import com.lastwave.app.playback.resolve.ResolverLogger
import com.lastwave.app.playback.resolve.StreamResolveCoordinator
import com.lastwave.app.playback.resolve.TrackIdentity
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchLookaheadTest {

    private val noopLogger = ResolverLogger { _, _, _, _, _ -> }

    private fun id(suffix: String) = TrackIdentity("track-$suffix", "vid0000000$suffix")

    private fun stream(videoId: String, expiresAt: Long = System.currentTimeMillis() + 600_000L) =
        YouTubeAudioStream(
            videoId = videoId,
            url = "https://cdn.example/$videoId",
            itag = 251,
            mimeType = "audio/webm",
            codec = "opus",
            bitrate = 160_000,
            sampleRateHz = 48_000,
            durationMs = 180_000L,
            contentLength = 1_000L,
            isAdaptive = true,
            clientProfile = "ANDROID_VR",
            authScope = "anon",
            expiresAtEpochMs = expiresAt,
        )

    private fun tempDir(): File = File.createTempFile("prefetch-lookahead", "").apply {
        delete()
        mkdirs()
    }

    @Test
    fun hiResStartDefaultsToFastForImmediatePlayback() {
        assertEquals(HiResStart.FAST, MiscSettings().hiResStart)
        assertEquals(HiResStart.FAST, HiResStart.fromId(null, legacyAutoUpgrade = null))
        assertEquals(HiResStart.FAST, HiResStart.fromId("unknown-mode", legacyAutoUpgrade = null))
        assertEquals(HiResStart.FAST, HiResStart.fromId(null, legacyAutoUpgrade = true))
        assertEquals(HiResStart.NEVER, HiResStart.fromId(null, legacyAutoUpgrade = false))
    }

    @Test
    fun prefetchWindowWarmsBoundedThreeTrackLookaheadAndSkipsActiveTrack() = runBlocking {
        val active = id("0")
        val t1 = id("1")
        val t2 = id("2")
        val t3 = id("3")
        val t4 = id("4")
        val extracted = ConcurrentHashMap.newKeySet<String>()
        val coordinator = StreamResolveCoordinator(
            cacheDir = tempDir(),
            scope = this,
            extract = { videoId ->
                delay(15L)
                extracted += videoId
                stream(videoId)
            },
            logger = noopLogger,
        )
        coordinator.playback.begin(active)
        coordinator.prefetchWindow(
            listOf(
                PrefetchItem(active, 1_000L),
                PrefetchItem(t1, 1_000L),
                PrefetchItem(t2, 1_000L),
                PrefetchItem(t3, 1_000L),
                PrefetchItem(t4, 1_000L),
            ),
        )

        withTimeout(3_000L) {
            while (
                coordinator.cache.get(t1) == null ||
                coordinator.cache.get(t2) == null ||
                coordinator.cache.get(t3) == null
            ) {
                delay(15L)
            }
        }

        assertFalse("Active playback track must not be prefetched", active.youtubeVideoId in extracted)
        assertNotNull(coordinator.cache.get(t1))
        assertNotNull(coordinator.cache.get(t2))
        assertNotNull(coordinator.cache.get(t3))
        assertNull("Track beyond 3-track lookahead window must not be prefetched", coordinator.cache.get(t4))
    }

    @Test
    fun rapidWindowUpdateCancelsOutOfWindowPrefetchesNonBlockingly() = runBlocking {
        val t1 = id("1")
        val t2 = id("2")
        val t3 = id("3")
        val t4 = id("4")
        val t1Started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        val coordinator = StreamResolveCoordinator(
            cacheDir = tempDir(),
            scope = this,
            extract = { videoId ->
                if (videoId == t1.youtubeVideoId) t1Started.complete(Unit)
                if (videoId == t1.youtubeVideoId || videoId == t2.youtubeVideoId) {
                    gate.await()
                }
                stream(videoId)
            },
            logger = noopLogger,
        )

        coordinator.prefetchWindow(listOf(PrefetchItem(t1, 1_000L), PrefetchItem(t2, 1_000L)))
        withTimeout(3_000L) { t1Started.await() }

        // User skips rapidly to a new region of the queue: enqueueWindow must
        // return immediately without waiting for blocked t1/t2 extractions.
        val startNs = System.nanoTime()
        coordinator.prefetchWindow(listOf(PrefetchItem(t3, 1_000L), PrefetchItem(t4, 1_000L)))
        val updateElapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        assertTrue("Window switch must be non-blocking, took ${updateElapsedMs}ms", updateElapsedMs < 200L)
        assertFalse(t1.youtubeVideoId in coordinator.prefetch.activeVideoIds())
        assertFalse(t2.youtubeVideoId in coordinator.prefetch.activeVideoIds())

        // Release blocked flights so the test scope terminates cleanly.
        gate.complete(Unit)
        withTimeout(3_000L) {
            while (coordinator.cache.get(t3) == null || coordinator.cache.get(t4) == null) {
                delay(15L)
            }
        }
        assertNotNull(coordinator.cache.get(t3))
        assertNotNull(coordinator.cache.get(t4))
    }

    @Test
    fun shortNegativeCacheTtlSuppressesRepeatedPrefetchFailuresUntilExpiryOrExplicitPlay() = runBlocking {
        val now = AtomicLong(10_000L)
        val calls = AtomicInteger(0)
        val shouldFail = AtomicInteger(1)
        val target = id("9")

        val coordinator = StreamResolveCoordinator(
            cacheDir = tempDir(),
            scope = this,
            extract = { videoId ->
                calls.incrementAndGet()
                if (shouldFail.get() == 1) {
                    throw IOException("temporary 403")
                }
                stream(videoId, expiresAt = now.get() + 600_000L)
            },
            logger = noopLogger,
            negativeCacheTtlMs = 5_000L,
            nowMs = now::get,
        )

        coordinator.prefetchWindow(listOf(PrefetchItem(target, 1_000L)))
        withTimeout(3_000L) {
            while (!coordinator.prefetch.isNegativelyCached(target.youtubeVideoId)) {
                delay(10L)
            }
        }
        assertEquals(1, calls.get())

        // Re-enqueue within negative-cache TTL must skip redundant background extraction.
        now.addAndGet(1_000L)
        coordinator.prefetchWindow(listOf(PrefetchItem(target, 1_000L)))
        delay(80L)
        assertEquals(1, calls.get())

        // Explicit playback must bypass/clear the background negative cache immediately.
        shouldFail.set(0)
        val resolved = coordinator.resolvePlayback(target, 1_000L)
        assertEquals(target.youtubeVideoId, resolved.youtubeVideoId)
        assertFalse(coordinator.prefetch.isNegativelyCached(target.youtubeVideoId))
        assertEquals(2, calls.get())
    }
}
