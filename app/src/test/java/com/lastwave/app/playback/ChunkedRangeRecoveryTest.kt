package com.lastwave.app.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ChunkedRangeRecoveryTest {

    private fun googlevideoSpec(url: String = "https://rr1.googlevideo.com/videoplayback?id=orig") =
        DataSpec.Builder().setUri(url).build()

    private fun readAll(source: ChunkedRangeDataSource, expectedLength: Int): ByteArray {
        val out = ByteArray(expectedLength)
        var total = 0
        while (total < expectedLength) {
            val n = source.read(out, total, expectedLength - total)
            if (n == C.RESULT_END_OF_INPUT) break
            total += n
        }
        return out.copyOf(total)
    }

    @Test
    fun midStream403RefreshesUrlAndResumesSeamlessly() {
        val totalBytes = 16
        val refreshCount = AtomicInteger(0)
        val freshUri = Uri.parse("https://rr2.googlevideo.com/videoplayback?id=fresh")
        val upstream = ScriptedChunkUpstream(
            totalBytes = totalBytes,
            sendContentRange = true,
            onOpen = { spec ->
                if (spec.uri.toString().contains("id=orig") && spec.position >= 4L) {
                    throw HttpDataSource.InvalidResponseCodeException(
                        403,
                        "Forbidden",
                        null,
                        emptyMap(),
                        spec,
                        ByteArray(0),
                    )
                }
            },
        )
        val source = ChunkedRangeDataSource(
            upstream = upstream,
            chunkSize = 4,
            refresher = {
                refreshCount.incrementAndGet()
                ChunkedRangeDataSource.Replacement(
                    uri = freshUri,
                    requestHeaders = mapOf("User-Agent" to "FreshClient/1.0"),
                )
            },
        )
        assertEquals(totalBytes.toLong(), source.open(googlevideoSpec()))
        val bytes = readAll(source, totalBytes)
        source.close()

        assertEquals(1, refreshCount.get())
        assertArrayEquals(ByteArray(totalBytes) { it.toByte() }, bytes)
        assertTrue(upstream.seenHeaders.any { it["User-Agent"] == "FreshClient/1.0" })
    }

    @Test
    fun midStream416BeforeKnownTotalRefreshesUrlAndCompletesStream() {
        val totalBytes = 12
        val refreshCount = AtomicInteger(0)
        val freshUri = Uri.parse("https://rr2.googlevideo.com/videoplayback?id=fresh416")
        val upstream = ScriptedChunkUpstream(
            totalBytes = totalBytes,
            sendContentRange = true,
            onOpen = { spec ->
                if (spec.uri.toString().contains("id=orig") && spec.position >= 4L) {
                    throw HttpDataSource.InvalidResponseCodeException(
                        416,
                        "Range Not Satisfiable",
                        null,
                        emptyMap(),
                        spec,
                        ByteArray(0),
                    )
                }
            },
        )
        val source = ChunkedRangeDataSource(
            upstream = upstream,
            chunkSize = 4,
            refresher = {
                refreshCount.incrementAndGet()
                ChunkedRangeDataSource.Replacement(freshUri, emptyMap())
            },
        )
        source.open(googlevideoSpec())
        val bytes = readAll(source, totalBytes)
        source.close()

        assertEquals(1, refreshCount.get())
        assertArrayEquals(ByteArray(totalBytes) { it.toByte() }, bytes)
    }

    @Test
    fun prematureEofMidChunkRefreshesUrlWithoutTruncatingAudio() {
        val totalBytes = 12
        val refreshCount = AtomicInteger(0)
        val freshUri = Uri.parse("https://rr2.googlevideo.com/videoplayback?id=freshEof")
        val upstream = ScriptedChunkUpstream(
            totalBytes = totalBytes,
            sendContentRange = true,
            prematureEofOn = { uri, pos -> uri.contains("id=orig") && pos == 6L },
        )
        val source = ChunkedRangeDataSource(
            upstream = upstream,
            chunkSize = 4,
            refresher = {
                refreshCount.incrementAndGet()
                ChunkedRangeDataSource.Replacement(freshUri, emptyMap())
            },
        )
        source.open(googlevideoSpec())
        val bytes = readAll(source, totalBytes)
        source.close()

        assertEquals(1, refreshCount.get())
        assertArrayEquals(ByteArray(totalBytes) { it.toByte() }, bytes)
    }

    @Test
    fun midChunkIoExceptionRefreshesUrlAndResumesAtExactOffset() {
        val totalBytes = 12
        val refreshCount = AtomicInteger(0)
        val freshUri = Uri.parse("https://rr2.googlevideo.com/videoplayback?id=freshIo")
        val upstream = ScriptedChunkUpstream(
            totalBytes = totalBytes,
            sendContentRange = true,
            failReadOn = { uri, pos -> uri.contains("id=orig") && pos == 5L },
        )
        val source = ChunkedRangeDataSource(
            upstream = upstream,
            chunkSize = 4,
            refresher = {
                refreshCount.incrementAndGet()
                ChunkedRangeDataSource.Replacement(freshUri, emptyMap())
            },
        )
        source.open(googlevideoSpec())
        val bytes = readAll(source, totalBytes)
        source.close()

        assertEquals(1, refreshCount.get())
        assertArrayEquals(ByteArray(totalBytes) { it.toByte() }, bytes)
    }

    @Test
    fun stalledChunkExceedingDeadlineTriggersRefreshOrThrowsTimeout() {
        val clock = AtomicLong(1_000L)
        val refreshCount = AtomicInteger(0)
        val freshUri = Uri.parse("https://rr2.googlevideo.com/videoplayback?id=freshStall")
        val upstream = ScriptedChunkUpstream(
            totalBytes = 8,
            sendContentRange = true,
            maxBytesPerRead = 2,
            onRead = { uri, _ ->
                if (uri.contains("id=orig")) {
                    // Simulate a chunk stalling for 20s after the first 2 bytes.
                    clock.addAndGet(20_000L)
                }
            },
        )
        val source = ChunkedRangeDataSource(
            upstream = upstream,
            chunkSize = 4,
            refresher = {
                refreshCount.incrementAndGet()
                ChunkedRangeDataSource.Replacement(freshUri, emptyMap())
            },
            maxChunkDurationMs = 5_000L,
            clockMs = clock::get,
        )
        source.open(googlevideoSpec())
        val bytes = readAll(source, 8)
        source.close()

        assertEquals(1, refreshCount.get())
        assertArrayEquals(ByteArray(8) { it.toByte() }, bytes)

        // Without a refresher, a stalled chunk must surface SocketTimeoutException.
        val clock2 = AtomicLong(1_000L)
        val stallingUpstream = ScriptedChunkUpstream(
            totalBytes = 8,
            sendContentRange = true,
            maxBytesPerRead = 2,
            onRead = { _, _ -> clock2.addAndGet(20_000L) },
        )
        val failingSource = ChunkedRangeDataSource(
            upstream = stallingUpstream,
            chunkSize = 4,
            refresher = null,
            maxChunkDurationMs = 5_000L,
            clockMs = clock2::get,
        )
        failingSource.open(googlevideoSpec())
        val buf = ByteArray(4)
        assertEquals(2, failingSource.read(buf, 0, 2))
        try {
            failingSource.read(buf, 2, 2)
            fail("Expected SocketTimeoutException on stalled chunk")
        } catch (_: SocketTimeoutException) {
            // Expected
        } finally {
            failingSource.close()
        }
    }

    @Test
    fun chunkSizeIsClampedTo512KbForGooglevideo() {
        val observedLengths = mutableListOf<Long>()
        val upstream = ScriptedChunkUpstream(
            totalBytes = 1024 * 1024,
            sendContentRange = true,
            onOpen = { spec -> observedLengths += spec.length },
        )
        // Requesting 2 MB must still be clamped to <= 512 KB for googlevideo.
        val source = ChunkedRangeDataSource(upstream = upstream, chunkSize = 2L * 1024L * 1024L)
        source.open(googlevideoSpec())
        source.close()

        assertEquals(1, observedLengths.size)
        assertEquals(ChunkedRangeDataSource.MAX_CHUNK_SIZE, observedLengths.single())
    }

    private class ScriptedChunkUpstream(
        private val totalBytes: Int,
        private val sendContentRange: Boolean = false,
        private val maxBytesPerRead: Int = Int.MAX_VALUE,
        private val onOpen: (DataSpec) -> Unit = {},
        private val onRead: (uri: String, pos: Long) -> Unit = { _, _ -> },
        private val prematureEofOn: (uri: String, pos: Long) -> Boolean = { _, _ -> false },
        private val failReadOn: (uri: String, pos: Long) -> Boolean = { _, _ -> false },
    ) : DataSource {
        val seenHeaders = mutableListOf<Map<String, String>>()
        private var currentUri: String = ""
        private var currentPos = 0L
        private var currentLength = 0L
        private var chunkRemaining = 0L

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            seenHeaders += dataSpec.httpRequestHeaders
            onOpen(dataSpec)
            currentUri = dataSpec.uri.toString()
            currentPos = dataSpec.position
            currentLength = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                minOf(dataSpec.length, (totalBytes - currentPos).coerceAtLeast(0L))
            } else {
                (totalBytes - currentPos).coerceAtLeast(0L)
            }
            if (currentPos >= totalBytes) throw IOException("EOF")
            chunkRemaining = currentLength
            return currentLength
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (chunkRemaining <= 0L) return C.RESULT_END_OF_INPUT
            if (failReadOn(currentUri, currentPos)) throw IOException("socket reset mid-chunk")
            if (prematureEofOn(currentUri, currentPos)) return C.RESULT_END_OF_INPUT
            val toRead = minOf(length.toLong(), chunkRemaining, maxBytesPerRead.toLong()).toInt()
            for (i in 0 until toRead) {
                val pos = currentPos + i
                if (failReadOn(currentUri, pos)) {
                    if (i > 0) {
                        currentPos += i
                        chunkRemaining -= i
                        return i
                    }
                    throw IOException("socket reset mid-chunk")
                }
                if (prematureEofOn(currentUri, pos)) {
                    if (i > 0) {
                        currentPos += i
                        chunkRemaining -= i
                        return i
                    }
                    return C.RESULT_END_OF_INPUT
                }
                buffer[offset + i] = (pos and 0xFF).toByte()
            }
            onRead(currentUri, currentPos)
            currentPos += toRead
            chunkRemaining -= toRead
            return toRead
        }

        override fun getUri(): Uri? = Uri.parse(currentUri)

        override fun getResponseHeaders(): Map<String, List<String>> =
            if (sendContentRange) {
                mapOf("Content-Range" to listOf("bytes $currentPos-${currentPos + currentLength - 1}/$totalBytes"))
            } else {
                emptyMap()
            }

        override fun close() = Unit
    }
}
