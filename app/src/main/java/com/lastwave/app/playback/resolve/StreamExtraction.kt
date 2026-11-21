package com.lastwave.app.playback.resolve

import android.util.Log
import com.lastwave.app.data.music.YouTubeAudioStream
import com.lastwave.app.data.music.containedAudioResolutionFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * One player extract per YouTube video id.
 * A second caller waits on the same job. Cancelling the caller does not
 * cancel the job, so a prefetch that the user skips still writes the URL.
 */
class StreamExtraction(
    parent: CoroutineScope,
    private val cache: PlaybackStreamCache,
    private val extract: suspend (youtubeVideoId: String) -> YouTubeAudioStream,
    private val flightTimeoutMs: Long = DEFAULT_FLIGHT_TIMEOUT_MS,
) {
    // Independent of the caller's job. Cancelling prefetch or playback
    // must not cancel an extract that is about to write the URL.
    // The scope ends when the player scope ends.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + containedAudioResolutionFailure(TAG),
    )

    init {
        parent.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion {
            scope.cancel()
        }
    }
    private val flights = ConcurrentHashMap<String, Deferred<YouTubeAudioStream>>()
    private val lock = Any()

    suspend fun await(youtubeVideoId: String): YouTubeAudioStream {
        val flight = synchronized(lock) {
            val current = flights[youtubeVideoId]
            if (current != null && current.isActive) {
                safeLogInfo("[EXTRACT_JOIN] youtubeVideoId=$youtubeVideoId")
                current
            } else {
                val created = scope.async { execute(youtubeVideoId) }
                flights[youtubeVideoId] = created
                created.invokeOnCompletion { flights.remove(youtubeVideoId, created) }
                created
            }
        }
        return flight.await()
    }

    private suspend fun execute(youtubeVideoId: String): YouTubeAudioStream {
        val started = System.nanoTime()
        safeLogInfo("[EXTRACT_START] youtubeVideoId=$youtubeVideoId")
        try {
            cache.get(TrackIdentity(youtubeVideoId, youtubeVideoId))?.let { hit ->
                safeLogInfo(
                    "[EXTRACT_END] youtubeVideoId=$youtubeVideoId elapsedMs=${elapsedSince(started)} cached=true",
                )
                return hit.toYouTubeAudioStream()
            }
            val stream = if (flightTimeoutMs > 0L) {
                withTimeout(flightTimeoutMs) { extract(youtubeVideoId) }
            } else {
                extract(youtubeVideoId)
            }
            if (stream.videoId == youtubeVideoId && stream.url.isNotBlank()) {
                cache.putIfAbsent(stream.toStreamCache(TrackIdentity(youtubeVideoId, youtubeVideoId)))
            }
            safeLogInfo(
                "[EXTRACT_END] youtubeVideoId=$youtubeVideoId elapsedMs=${elapsedSince(started)} " +
                    "cached=${stream.videoId == youtubeVideoId && stream.url.isNotBlank()}",
            )
            return stream
        } catch (error: Throwable) {
            safeLogInfo(
                "[EXTRACT_END] youtubeVideoId=$youtubeVideoId elapsedMs=${elapsedSince(started)} " +
                    "cached=false error=${error::class.java.simpleName}",
            )
            throw error
        }
    }

    private fun safeLogInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    companion object {
        private const val TAG = "LastWaveResolver"
        const val DEFAULT_FLIGHT_TIMEOUT_MS = 15_000L
    }
}
