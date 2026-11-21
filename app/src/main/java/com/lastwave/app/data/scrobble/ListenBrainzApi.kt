package com.lastwave.app.data.scrobble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight ListenBrainz HTTP v1 client supporting token validation,
 * immediate `playing_now` updates, single listen submissions, and batched
 * `import` flushes.
 */
@Singleton
class ListenBrainzApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface SubmitResult {
        data object Success : SubmitResult
        data object Disabled : SubmitResult
        data class Failed(val message: String, val retryable: Boolean) : SubmitResult
    }

    data class TokenValidation(
        val valid: Boolean,
        val username: String? = null,
        val message: String = "",
    )

    suspend fun validateToken(token: String): TokenValidation = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        if (cleanToken.isBlank()) {
            return@withContext TokenValidation(valid = false, message = "Token is empty")
        }
        try {
            val request = Request.Builder()
                .url("$BASE_URL/1/validate-token")
                .header("Authorization", "Token $cleanToken")
                .header("Accept", "application/json")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext TokenValidation(
                        valid = false,
                        message = "HTTP ${response.code}",
                    )
                }
                val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                val isValid = parsed?.get("valid")?.jsonPrimitive?.booleanOrNull == true
                val userName = parsed?.get("user_name")?.jsonPrimitive?.contentOrNull
                val message = parsed?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty()
                TokenValidation(valid = isValid, username = userName, message = message)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            TokenValidation(valid = false, message = e.message ?: "Could not reach ListenBrainz")
        }
    }

    suspend fun submitNowPlaying(
        token: String,
        artist: String,
        track: String,
        album: String? = null,
        durationMs: Long? = null,
    ): SubmitResult {
        val cleanToken = token.trim()
        if (cleanToken.isBlank()) return SubmitResult.Disabled
        val payload = buildJsonObject {
            put("listen_type", "playing_now")
            put(
                "payload",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("track_metadata", buildTrackMetadata(artist, track, album, durationMs))
                        },
                    )
                },
            )
        }.toString()
        return postListens(cleanToken, payload)
    }

    suspend fun submitSingle(
        token: String,
        artist: String,
        track: String,
        album: String? = null,
        listenedAtSec: Long,
        durationMs: Long? = null,
    ): SubmitResult {
        val cleanToken = token.trim()
        if (cleanToken.isBlank()) return SubmitResult.Disabled
        val payload = buildJsonObject {
            put("listen_type", "single")
            put(
                "payload",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("listened_at", listenedAtSec)
                            put("track_metadata", buildTrackMetadata(artist, track, album, durationMs))
                        },
                    )
                },
            )
        }.toString()
        return postListens(cleanToken, payload)
    }

    suspend fun submitImportBatch(
        token: String,
        events: List<ScrobbleEventEntity>,
    ): SubmitResult {
        val cleanToken = token.trim()
        if (cleanToken.isBlank()) return SubmitResult.Disabled
        if (events.isEmpty()) return SubmitResult.Success
        val payload = buildJsonObject {
            put("listen_type", "import")
            put(
                "payload",
                buildJsonArray {
                    events.forEach { event ->
                        val tsSec = ((event.startedAtMs.takeIf { it > 0L } ?: event.scrobbledAtMs) / 1000L)
                            .coerceAtLeast(1L)
                        add(
                            buildJsonObject {
                                put("listened_at", tsSec)
                                put(
                                    "track_metadata",
                                    buildTrackMetadata(
                                        artist = event.artist,
                                        track = event.title,
                                        album = event.album.ifBlank { null },
                                        durationMs = event.durationMs.takeIf { it > 0L },
                                    ),
                                )
                            },
                        )
                    }
                },
            )
        }.toString()
        return postListens(cleanToken, payload)
    }

    private fun buildTrackMetadata(
        artist: String,
        track: String,
        album: String?,
        durationMs: Long?,
    ) = buildJsonObject {
        put("artist_name", artist)
        put("track_name", track)
        if (!album.isNullOrBlank()) {
            put("release_name", album)
        }
        put(
            "additional_info",
            buildJsonObject {
                put("media_player", "Onyx Music")
                put("submission_client", "Onyx Music")
                if (durationMs != null && durationMs > 0L) {
                    put("duration_ms", durationMs)
                }
            },
        )
    }

    private suspend fun postListens(token: String, jsonPayload: String): SubmitResult =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/1/submit-listens")
                    .header("Authorization", "Token $token")
                    .header("Content-Type", "application/json")
                    .post(jsonPayload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> SubmitResult.Success
                        response.code == 429 || response.code >= 500 -> SubmitResult.Failed(
                            message = "ListenBrainz temporary error (${response.code})",
                            retryable = true,
                        )
                        else -> SubmitResult.Failed(
                            message = "ListenBrainz rejected request (${response.code})",
                            retryable = false,
                        )
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: IOException) {
                SubmitResult.Failed(e.message ?: "Network error", retryable = true)
            } catch (e: Exception) {
                SubmitResult.Failed(e.message ?: "ListenBrainz error", retryable = false)
            }
        }

    private companion object {
        const val BASE_URL = "https://api.listenbrainz.org"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
