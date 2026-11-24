package com.lastwave.app.data.recognition

import java.io.IOException
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class RecognizedSong(
    val title: String,
    val artist: String,
    val album: String?,
    val coverUrl: String?,
    val shazamUrl: String?,
)

sealed class RecognitionFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NoMatch : RecognitionFailure("No match")
    class RateLimited : RecognitionFailure("Too many requests")
    class Network(cause: Throwable? = null) : RecognitionFailure("Network error", cause)
    class Server(val code: Int) : RecognitionFailure("Server error $code")
}

@Serializable
private data class TagRequest(
    val geolocation: Geo,
    val signature: Sig,
    val timestamp: Long,
    val timezone: String,
) {
    @Serializable data class Geo(val altitude: Double, val latitude: Double, val longitude: Double)
    @Serializable data class Sig(val samplems: Long, val timestamp: Long, val uri: String)
}

@Serializable
private data class TagResponse(
    val track: Track? = null,
    val retryms: Long? = null,
) {
    @Serializable
    data class Track(
        val title: String? = null,
        val subtitle: String? = null,
        val url: String? = null,
        val images: Images? = null,
        val sections: List<Section?>? = null,
    )

    @Serializable
    data class Images(val coverart: String? = null, @SerialName("coverarthq") val coverArtHq: String? = null)

    @Serializable
    data class Section(val type: String? = null, val metadata: List<Meta?>? = null)

    @Serializable
    data class Meta(val title: String? = null, val text: String? = null)
}

/**
 * Sends a fingerprint to the recognition service. Requests are serialised and spaced
 * out, 429s back off exponentially, and recent answers are cached by signature so a
 * retry of the same clip never hits the network twice.
 */
@Singleton
class RecognitionClient @Inject constructor(baseClient: OkHttpClient) {

    private val http = baseClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
    private val gate = Mutex()
    private var lastSentAt = 0L
    private val cache = object : LinkedHashMap<String, Pair<Long, RecognizedSong>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, RecognizedSong>>) =
            size > CACHE_SIZE
    }

    data class ProbeOutcome(
        val song: RecognizedSong?,
        val retryMs: Long? = null,
    )

    suspend fun recognize(signatureUri: String, sampleMs: Long): RecognizedSong =
        recognizeProbe(signatureUri, sampleMs, sessionUuid = UUID.randomUUID().toString()).song
            ?: throw RecognitionFailure.NoMatch()

    /**
     * Progressive recognition probe reusing [sessionUuid] across growing windows
     * (e.g. 2.8s -> 5.0s -> 7.5s -> 10.5s), matching the real Shazam app protocol.
     */
    suspend fun recognizeProbe(
        signatureUri: String,
        sampleMs: Long,
        sessionUuid: String,
    ): ProbeOutcome {
        synchronized(cache) { cache[signatureUri] }?.let { (at, song) ->
            if (System.currentTimeMillis() - at < CACHE_TTL_MS) return ProbeOutcome(song)
        }
        return gate.withLock {
            var backoff = INITIAL_BACKOFF_MS
            var attempt = 1
            while (true) {
                val wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastSentAt)
                if (wait > 0) delay(wait)
                lastSentAt = System.currentTimeMillis()
                try {
                    val outcome = send(signatureUri, sampleMs, sessionUuid)
                    outcome.song?.let { song ->
                        synchronized(cache) { cache[signatureUri] = System.currentTimeMillis() to song }
                    }
                    return@withLock outcome
                } catch (e: RecognitionFailure.RateLimited) {
                    if (attempt++ >= MAX_ATTEMPTS) throw e
                    delay(backoff)
                    backoff *= 2
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    }

    private suspend fun send(
        signatureUri: String,
        sampleMs: Long,
        sessionUuid: String,
    ): ProbeOutcome = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        val body = json.encodeToString(
            TagRequest(
                // Fixed placeholder: the service wants the fields, not the user's real position.
                geolocation = TagRequest.Geo(altitude = 300.0, latitude = 45.0, longitude = 2.0),
                signature = TagRequest.Sig(samplems = sampleMs, timestamp = nowMs, uri = signatureUri),
                timestamp = nowMs,
                timezone = TimeZone.getDefault().id,
            ),
        )
        val url = "$ENDPOINT/${UUID.randomUUID().toString().uppercase()}/$sessionUuid"
            .toHttpUrl().newBuilder()
            .addQueryParameter("sync", "true")
            .addQueryParameter("webv3", "true")
            .addQueryParameter("sampling", "true")
            .addQueryParameter("connected", "")
            .addQueryParameter("shazamapiversion", "v3")
            .addQueryParameter("sharehub", "true")
            .addQueryParameter("video", "v3")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Content-Language", "en_US")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val code = response.code
                when {
                    code == 429 -> throw RecognitionFailure.RateLimited()
                    code == 404 -> return@withContext ProbeOutcome(null)
                    code !in 200..299 -> throw RecognitionFailure.Server(code)
                }
                val text = response.body?.string().orEmpty()
                val parsed = runCatching { json.decodeFromString<TagResponse>(text) }.getOrNull()
                val track = parsed?.track
                    ?: return@withContext ProbeOutcome(null, retryMs = parsed?.retryms)
                val title = track.title?.takeIf { it.isNotBlank() }
                    ?: return@withContext ProbeOutcome(null, retryMs = parsed.retryms)
                val album = track.sections.orEmpty().firstOrNull { it?.type == "SONG" }
                    ?.metadata.orEmpty().firstOrNull { it?.title == "Album" }?.text
                ProbeOutcome(
                    song = RecognizedSong(
                        title = title,
                        artist = track.subtitle.orEmpty(),
                        album = album,
                        coverUrl = track.images?.coverArtHq ?: track.images?.coverart,
                        shazamUrl = track.url,
                    ),
                )
            }
        } catch (e: IOException) {
            throw RecognitionFailure.Network(e)
        }
    }

    private companion object {
        const val ENDPOINT = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag"
        const val MIN_INTERVAL_MS = 400L
        const val INITIAL_BACKOFF_MS = 1_500L
        const val MAX_ATTEMPTS = 2
        const val CACHE_TTL_MS = 5 * 60_000L
        const val CACHE_SIZE = 20

        // Modern Android Dalvik User-Agent matching current Shazam Android clients.
        const val USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/UQ1A.240205.004)"
    }
}
