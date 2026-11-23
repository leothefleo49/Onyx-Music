package com.lastwave.app.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Wraps an upstream [DataSource] to issue chunked HTTP Range requests (512 KB range chunks)
 * for YouTube / googlevideo media.
 *
 * 512 KB is InnerTubeX's limit for the token-free ANDROID_VR / TVHTML5_SIMPLY
 * clients (mediaRangeChunkSize); larger or open-ended ranges get served for a
 * while and then refused.
 *
 * When [refresher] is set, a chunk refused with 403/410 (URL expired or
 * revoked mid-song) is retried in place on a fresh URL for the SAME file —
 * same itag and byte length, so byte offsets stay valid — and playback never
 * sees an error. The loader runs ahead of the playhead, so the buffer covers
 * the re-resolve and there is no audible gap.
 *
 * This prevents YouTube's server-side bandwidth throttling on open-ended Range requests
 * and prevents HTTP 403 on client profiles (such as IOS) that strictly mandate bounded Range headers.
 *
 * End of input is reported only at the real end of the file: the requested
 * length, the total size from `Content-Range`, or an HTTP 416 past the last
 * byte. A failed chunk request (expired URL, dropped connection) is thrown as
 * an error instead, so playback retries / re-resolves the same song. Reporting
 * it as end of input made ExoPlayer treat the song as finished and skip to the
 * next one halfway through.
 */
class ChunkedRangeDataSource(
    private val upstream: DataSource,
    chunkSize: Long = DEFAULT_CHUNK_SIZE,
    private val refresher: StreamRefresher? = null,
    private val maxChunkDurationMs: Long = DEFAULT_MAX_CHUNK_DURATION_MS,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : DataSource {

    // Googlevideo strictly enforces <= 512 KB range chunks on token-free clients.
    private val effectiveChunkSize: Long = chunkSize.coerceIn(1L, MAX_CHUNK_SIZE)

    /** Replacement location for a refused googlevideo URL, or null when there is none. */
    fun interface StreamRefresher {
        fun refresh(failedUri: Uri): Replacement?
    }

    class Replacement(val uri: Uri, val requestHeaders: Map<String, String>)

    private var refreshesThisOpen = 0
    private var currentChunkStartedAtMs = 0L

    private var currentDataSpec: DataSpec? = null
    private var currentPosition: Long = 0L
    private var requestedLength: Long = C.LENGTH_UNSET.toLong()
    private var bytesReadTotal: Long = 0L
    private var currentChunkRemaining: Long = 0L
    private var isCurrentChunkOpen: Boolean = false
    private var chunked: Boolean = false
    /** Total resource size from Content-Range, or LENGTH_UNSET. */
    private var totalLength: Long = C.LENGTH_UNSET.toLong()

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        closeCurrentChunk()
        currentDataSpec = dataSpec
        currentPosition = dataSpec.position
        requestedLength = dataSpec.length
        bytesReadTotal = 0L
        totalLength = C.LENGTH_UNSET.toLong()
        refreshesThisOpen = 0

        val isYouTube = dataSpec.uri.host?.let { host ->
            host.contains("googlevideo.com") || host.contains("youtube.com")
        } == true

        chunked = isYouTube && (dataSpec.length == C.LENGTH_UNSET.toLong() || dataSpec.length > effectiveChunkSize)
        if (!chunked) {
            // Non-YouTube requests or small requests pass through directly
            return try {
                openUpstream(dataSpec)
            } catch (e: IOException) {
                throw e
            } catch (e: RuntimeException) {
                throw IOException(e.message ?: "Upstream open error", e)
            }
        }

        try {
            openNextChunk()
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            throw IOException(e.message ?: "Chunk open error", e)
        }
        return if (requestedLength != C.LENGTH_UNSET.toLong()) {
            requestedLength
        } else if (totalLength != C.LENGTH_UNSET.toLong()) {
            (totalLength - dataSpec.position).coerceAtLeast(0L)
        } else {
            C.LENGTH_UNSET.toLong()
        }
    }

    private fun closeCurrentChunk() {
        if (isCurrentChunkOpen) {
            runCatching { upstream.close() }
            isCurrentChunkOpen = false
            currentChunkRemaining = 0L
        }
    }

    private fun openNextChunk() {
        closeCurrentChunk()
        val spec = currentDataSpec ?: return
        val chunkLength = if (requestedLength != C.LENGTH_UNSET.toLong()) {
            val remaining = requestedLength - bytesReadTotal
            if (remaining <= 0) return
            minOf(effectiveChunkSize, remaining)
        } else if (totalLength != C.LENGTH_UNSET.toLong()) {
            val remaining = totalLength - currentPosition
            if (remaining <= 0) return
            minOf(effectiveChunkSize, remaining)
        } else {
            effectiveChunkSize
        }

        val chunkSpec = spec.buildUpon()
            .setPosition(currentPosition)
            .setLength(chunkLength)
            .build()

        val opened = openChunkRefreshingOnRejection(chunkSpec)
        currentChunkStartedAtMs = clockMs()
        currentChunkRemaining = if (opened != C.LENGTH_UNSET.toLong()) opened else chunkLength
        if (totalLength == C.LENGTH_UNSET.toLong()) {
            totalLength = parseContentRangeTotal(upstream.responseHeaders)
        }
    }

    /**
     * Opens [chunkSpec]; on 403/410 (or unexpected mid-file 416) swaps in a
     * fresh URL for the same file (at most [MAX_REFRESHES_PER_OPEN] times) and
     * re-requests the same bytes.
     */
    private fun openChunkRefreshingOnRejection(chunkSpec: DataSpec): Long {
        var spec = chunkSpec
        while (true) {
            try {
                return openUpstream(spec)
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                val unexpected416 = error.responseCode == 416 &&
                    ((totalLength != C.LENGTH_UNSET.toLong() && currentPosition < totalLength) ||
                        (requestedLength != C.LENGTH_UNSET.toLong() && bytesReadTotal < requestedLength))
                val refreshable = error.responseCode == 403 || error.responseCode == 410 || unexpected416
                if (!refreshable) throw error
                spec = tryRefreshSpec(spec) ?: throw error
            } catch (error: IOException) {
                // Mid-song network handoff (e.g. Wi-Fi -> Cellular) at a chunk boundary:
                // attempt in-place URL refresh before failing the stream.
                if (currentPosition <= 0L && bytesReadTotal <= 0L) throw error
                spec = tryRefreshSpec(spec) ?: throw error
            } catch (error: RuntimeException) {
                throw IOException(error.message ?: "Chunk open runtime failure", error)
            }
        }
    }

    private fun tryRefreshSpec(spec: DataSpec): DataSpec? {
        val activeRefresher = refresher ?: return null
        if (refreshesThisOpen >= MAX_REFRESHES_PER_OPEN) return null
        refreshesThisOpen++
        val replacement = runCatching { activeRefresher.refresh(spec.uri) }.getOrNull() ?: return null
        val updatedHeaders = spec.httpRequestHeaders + replacement.requestHeaders
        val refreshed = spec.buildUpon()
            .setUri(replacement.uri)
            .setHttpRequestHeaders(updatedHeaders)
            .build()
        currentDataSpec = currentDataSpec?.buildUpon()
            ?.setUri(replacement.uri)
            ?.setHttpRequestHeaders(updatedHeaders)
            ?.build()
        return refreshed
    }

    /** Opens the next chunk; false only when the file is genuinely finished. */
    private fun openNextChunkOrEnd(): Boolean {
        if (atEnd()) return false
        try {
            openNextChunk()
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            // 416: the range starts past the last byte - the real end of an
            // open-ended stream. Anything else is a failure, never an end.
            if (error.responseCode == 416 && requestedLength == C.LENGTH_UNSET.toLong()) return false
            throw error
        }
        return isCurrentChunkOpen && currentChunkRemaining > 0
    }

    private fun atEnd(): Boolean =
        (requestedLength != C.LENGTH_UNSET.toLong() && bytesReadTotal >= requestedLength) ||
            (totalLength != C.LENGTH_UNSET.toLong() && currentPosition >= totalLength)

    private fun openUpstream(dataSpec: DataSpec): Long {
        // DefaultDataSource assigns its active source before open() completes.
        // Even a failed open therefore needs close() before the next request.
        closeCurrentChunk()
        isCurrentChunkOpen = true
        return try {
            upstream.open(dataSpec)
        } catch (error: Exception) {
            closeCurrentChunk()
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        currentDataSpec ?: return C.RESULT_END_OF_INPUT
        if (!chunked) return upstream.read(buffer, offset, length)

        // Bounded: a chunk that opens but yields nothing twice in a row is
        // either the end (no size known) or a broken server response.
        var emptyChunks = 0
        while (true) {
            if (atEnd()) return C.RESULT_END_OF_INPUT
            if (isCurrentChunkOpen && maxChunkDurationMs > 0L &&
                clockMs() - currentChunkStartedAtMs > maxChunkDurationMs
            ) {
                // Chunk transfer stalled past its bounded budget: refresh the
                // URL if possible, otherwise fail fast with SocketTimeoutException.
                closeCurrentChunk()
                val spec = currentDataSpec
                if (spec == null || tryRefreshSpec(spec) == null) {
                    throw SocketTimeoutException("Chunk stalled past ${maxChunkDurationMs}ms at byte $currentPosition")
                }
            }
            if (currentChunkRemaining <= 0) {
                if (!openNextChunkOrEnd()) return C.RESULT_END_OF_INPUT
            }
            val bytesToRead = minOf(length.toLong(), currentChunkRemaining).toInt()
            val bytesRead = try {
                upstream.read(buffer, offset, bytesToRead)
            } catch (error: IOException) {
                // Mid-chunk transport drop or timeout: attempt in-place URL refresh
                // before surfacing the error so buffered audio keeps playing.
                closeCurrentChunk()
                val spec = currentDataSpec
                if (spec != null && tryRefreshSpec(spec) != null) {
                    continue
                }
                throw error
            } catch (error: RuntimeException) {
                closeCurrentChunk()
                val spec = currentDataSpec
                if (spec != null && tryRefreshSpec(spec) != null) {
                    continue
                }
                throw IOException(error.message ?: "Chunk read runtime failure", error)
            }
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                // This chunk closed early (connection dropped) or exactly at
                // its end. Either way continue from the byte we actually have.
                closeCurrentChunk()
                if (++emptyChunks >= 2) {
                    if (requestedLength == C.LENGTH_UNSET.toLong() && totalLength == C.LENGTH_UNSET.toLong()) {
                        return C.RESULT_END_OF_INPUT
                    }
                    val spec = currentDataSpec
                    if (spec != null && tryRefreshSpec(spec) != null) {
                        emptyChunks = 0
                        continue
                    }
                    throw IOException("Stream ended at byte $currentPosition before its declared length")
                }
                continue
            }
            currentPosition += bytesRead
            bytesReadTotal += bytesRead
            currentChunkRemaining -= bytesRead
            return bytesRead
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        closeCurrentChunk()
        currentDataSpec = null
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 512 * 1024L
        const val MAX_CHUNK_SIZE = 512 * 1024L
        const val DEFAULT_MAX_CHUNK_DURATION_MS = 15_000L
        private const val MAX_REFRESHES_PER_OPEN = 3

        private val CONTENT_RANGE_TOTAL = Regex("""/\s*(\d+)\s*$""")

        /** "bytes 0-1048575/3456789" -> 3456789; LENGTH_UNSET when absent or "*". */
        internal fun parseContentRangeTotal(headers: Map<String, List<String>>): Long {
            val value = headers.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                ?.value?.firstOrNull()
                ?: return C.LENGTH_UNSET.toLong()
            return CONTENT_RANGE_TOTAL.find(value)?.groupValues?.get(1)?.toLongOrNull()
                ?: C.LENGTH_UNSET.toLong()
        }
    }
}
