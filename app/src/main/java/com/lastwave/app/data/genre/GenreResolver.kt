package com.lastwave.app.data.genre

import android.content.Context
import android.util.Log
import com.lastwave.app.data.artwork.awaitSuccessfulBodyOrNull
import com.lastwave.app.data.generate.GenerateJson
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.local.awaitLoadedSession
import com.lastwave.app.data.lyrics.LrclibLyricsApi
import com.lastwave.app.data.network.ITunesSearchClient
import com.lastwave.app.data.network.LastFmApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GenreResolver"
private const val CACHE_MAX = 600
private const val PREFS_NAME = "onyx_genre_cache_v2"
private const val PERSIST_MAX_ENTRIES = 1500

/**
 * Canonical genre normalizer & noise filter shared by [GenreResolver],
 * [com.lastwave.app.data.generate.GenresRepository], and Listening Stats.
 *
 * Canonical keys remain lowercase (e.g. `"hip-hop"`, `"r&b"`, `"jazz"`) for
 * stable grouping and test compatibility; use [formatDisplayName] for UI labels.
 */
object GenreNormalizer {

    private val yearRegex = Regex("""^(19|20)\d{2}s?$""")
    private val decadeRegex = Regex("""^\d{2}s$""")

    val ignoredTags: Set<String> = setOf(
        "seen live", "favorites", "favorite", "favourites", "favourite",
        "my favorite", "my favorites", "awesome", "love", "loved",
        "albums i own", "check out", "spotify", "music", "under 2000 listeners",
        "all", "female vocalists", "male vocalists", "vocalists", "american",
        "british", "canadian", "australian", "swedish", "german", "french",
        "japanese", "korean", "uk", "usa", "us", "english", "spanish",
        "best", "good", "great", "cool", "amazing", "beautiful", "nice",
        "playlist", "liked", "radio", "my music", "downloaded", "discover",
        "top", "hits", "various artists", "misc", "other", "unknown",
        "tag", "none", "n/a", "general", "popular", "mainstream",
    )

    private val aliasMap: Map<String, String> = mapOf(
        "hip-hop/rap" to "hip-hop",
        "hip hop/rap" to "hip-hop",
        "hip hop" to "hip-hop",
        "hiphop" to "hip-hop",
        "rap/hip-hop" to "hip-hop",
        "rap" to "hip-hop",
        "r&b/soul" to "r&b",
        "rnb/soul" to "r&b",
        "rnb" to "r&b",
        "r & b" to "r&b",
        "rhythm and blues" to "r&b",
        "contemporary r&b" to "r&b",
        "alternative/indie" to "alternative",
        "alt-rock" to "alternative rock",
        "alt rock" to "alternative rock",
        "indie/alternative" to "indie",
        "singer/songwriter" to "singer-songwriter",
        "singer songwriter" to "singer-songwriter",
        "kpop" to "k-pop",
        "k pop" to "k-pop",
        "jpop" to "j-pop",
        "j pop" to "j-pop",
        "c-pop" to "c-pop",
        "cpop" to "c-pop",
        "lofi" to "lo-fi",
        "lo fi" to "lo-fi",
        "lo-fi beats" to "lo-fi",
        "electronica" to "electronic",
        "electronic dance" to "electronic",
        "dance/electronic" to "electronic",
        "edm" to "electronic",
        "drum and bass" to "drum & bass",
        "dnb" to "drum & bass",
        "d&b" to "drum & bass",
        "synth-pop" to "synthpop",
        "synth pop" to "synthpop",
        "dream-pop" to "dream pop",
        "dreampop" to "dream pop",
        "post-punk" to "post-punk",
        "post punk" to "post-punk",
        "post-rock" to "post-rock",
        "post rock" to "post-rock",
        "pop/rock" to "pop rock",
        "rock/pop" to "pop rock",
        "film score" to "soundtrack",
        "ost" to "soundtrack",
        "movie soundtrack" to "soundtrack",
        "video game music" to "soundtrack",
        "anime" to "anime",
        "chillout" to "chill",
        "chill out" to "chill",
        "downtempo" to "chill",
        "trap music" to "trap",
        "afrobeat" to "afrobeats",
        "latino" to "latin",
        "urbano latino" to "latin",
        "heavy metal" to "metal",
        "hardcore punk" to "hardcore",
        "nu-metal" to "nu metal",
        "house music" to "house",
        "deep-house" to "deep house",
        "tech-house" to "tech house",
        "techno music" to "techno",
        "classical crossover" to "classical",
        "modern classical" to "classical",
        "neo-soul" to "neo soul",
        "spoken word" to "spoken word",
    )

    private val specialDisplayNames: Map<String, String> = mapOf(
        "hip-hop" to "Hip-Hop",
        "r&b" to "R&B",
        "k-pop" to "K-Pop",
        "j-pop" to "J-Pop",
        "c-pop" to "C-Pop",
        "lo-fi" to "Lo-Fi",
        "edm" to "EDM",
        "drum & bass" to "Drum & Bass",
        "uk drill" to "UK Drill",
        "uk garage" to "UK Garage",
        "mpb" to "MPB",
        "ost" to "OST",
        "idm" to "IDM",
        "ebm" to "EBM",
        "post-punk" to "Post-Punk",
        "post-rock" to "Post-Rock",
        "singer-songwriter" to "Singer-Songwriter",
        "nu metal" to "Nu Metal",
        "neo soul" to "Neo-Soul",
        "synthpop" to "Synthpop",
        "afrobeats" to "Afrobeats",
    )

    /**
     * Normalizes a single raw genre/tag string to a canonical lowercase name,
     * returning `null` if it is blank, noise, a year/decade, or too long.
     */
    fun normalizeSingle(raw: String): String? {
        val trimmed = raw.trim().replace(Regex("\\s+"), " ").lowercase()
        if (trimmed.length < 2 || trimmed.length > 36) return null
        if (trimmed in ignoredTags) return null
        if (yearRegex.matches(trimmed) || decadeRegex.matches(trimmed)) return null
        if (trimmed.all { it.isDigit() || it == '-' || it == '\'' }) return null
        val mapped = aliasMap[trimmed] ?: trimmed
        return mapped.takeIf { it.isNotBlank() && it !in ignoredTags }
    }

    /**
     * Extracts up to [maxTags] clean, distinct canonical genres from a stored
     * genre field (e.g. `"Hip-Hop/Rap, Trap"` -> `["hip-hop", "trap"]`),
     * preserving compound slash names like `"Hip-Hop/Rap"` and `"R&B/Soul"`.
     */
    fun splitAndNormalize(raw: String, maxTags: Int = 2): List<String> {
        if (raw.isBlank()) return emptyList()
        val results = LinkedHashSet<String>()
        for (part in raw.split(",", ";")) {
            val cleaned = part.trim()
            if (cleaned.isBlank()) continue
            val direct = normalizeSingle(cleaned)
            if (direct != null && (cleaned.lowercase() in aliasMap || '/' !in cleaned)) {
                results.add(direct)
            } else if ('/' in cleaned) {
                cleaned.split("/").forEach { sub ->
                    normalizeSingle(sub)?.let { results.add(it) }
                }
            } else if (direct != null) {
                results.add(direct)
            }
            if (results.size >= maxTags) break
        }
        return results.take(maxTags)
    }

    /** Formats a canonical lowercase genre for clean UI display. */
    fun formatDisplayName(canonical: String): String {
        val lower = canonical.trim().lowercase()
        if (lower.isEmpty()) return ""
        specialDisplayNames[lower]?.let { return it }
        return lower.split(" ").joinToString(" ") { word ->
            word.split("-").joinToString("-") { seg ->
                seg.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
            }
        }
    }

    /** Primary canonical genre (first clean tag) from a potentially comma-joined string. */
    fun primaryGenre(raw: String): String =
        splitAndNormalize(raw, maxTags = 1).firstOrNull() ?: raw.trim().lowercase()
}

/**
 * Resolves canonical track & artist genres with a persistent local cache and
 * multi-source waterfall:
 * 1. Persistent local cache (track key, then artist key)
 * 2. Last.fm `track.getInfo` + `artist.getTopTags` (when API key available)
 * 3. Deezer public search + album genres (free, no key needed)
 * 4. Single-shot iTunes `primaryGenreName` fallback
 *
 * Transient failures are never cached so tracks retry cleanly once online.
 */
@Singleton
class GenreResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: LastFmApiService,
    private val sessionPreferences: SessionPreferences,
    private val iTunesSearchClient: ITunesSearchClient,
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val memoryCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > CACHE_MAX
    }
    private val lock = Any()

    /** Fast non-blocking lookup from memory/disk cache only (never hits network). */
    fun peekCached(name: String, artist: String): String {
        if (artist.isBlank()) return ""
        val trackKey = trackCacheKey(name, artist)
        val artistKey = artistCacheKey(artist)
        synchronized(lock) {
            memoryCache[trackKey]?.takeIf { it.isNotBlank() }?.let { return it }
            memoryCache[artistKey]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val persisted = prefs.getString(trackKey, null)?.takeIf { it.isNotBlank() }
            ?: prefs.getString(artistKey, null)?.takeIf { it.isNotBlank() }
            ?: ""
        if (persisted.isNotBlank()) {
            synchronized(lock) { memoryCache[trackKey] = persisted }
        }
        return persisted
    }

    suspend fun resolve(name: String, artist: String): String {
        if (artist.isBlank() && name.isBlank()) return ""
        val cleanArtist = LrclibLyricsApi.cleanArtistName(artist).ifBlank { artist.trim() }
        val cleanTitle = LrclibLyricsApi.cleanTrackTitle(name).ifBlank { name.trim() }
        val trackKey = trackCacheKey(cleanTitle, cleanArtist)
        val artistKey = artistCacheKey(cleanArtist)

        peekCached(cleanTitle, cleanArtist).takeIf { it.isNotBlank() }?.let { return it }

        var tags: List<String> = emptyList()

        // Step 1: Last.fm track.getInfo -> artist.getTopTags (if session API key is present)
        tags = resolveViaLastFm(cleanTitle, cleanArtist)

        // Step 2: Deezer public API (free, fast, no API key required)
        if (tags.isEmpty()) {
            tags = resolveViaDeezer(cleanTitle, cleanArtist)
        }

        // Step 3: Single-shot iTunes fallback (1 request max)
        if (tags.isEmpty()) {
            tags = resolveViaITunes(cleanTitle, cleanArtist)
        }

        val result = tags.joinToString(", ") { GenreNormalizer.formatDisplayName(it) }
        if (result.isNotBlank()) {
            saveToCache(trackKey, artistKey, result)
        }
        return result
    }

    private suspend fun resolveViaLastFm(title: String, artist: String): List<String> {
        val session = runCatching { sessionPreferences.awaitLoadedSession() }.getOrNull() ?: return emptyList()
        if (session.apiKey.isBlank()) return emptyList()

        if (title.isNotBlank() && artist.isNotBlank()) {
            try {
                val response = api.get(
                    mapOf(
                        "method" to "track.getInfo",
                        "track" to title,
                        "artist" to artist,
                        "autocorrect" to "1",
                        "api_key" to session.apiKey,
                        "format" to "json",
                    ),
                )
                val body = response.body()?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val parsed = json.parseToJsonElement(body).jsonObject
                    val tagsEl = parsed["track"]?.jsonObject?.get("toptags")?.jsonObject?.get("tag")
                    val normalized = GenerateJson.asObjectList(tagsEl)
                        .mapNotNull { (it["name"] as? JsonPrimitive)?.content }
                        .mapNotNull { GenreNormalizer.normalizeSingle(it) }
                        .distinct()
                        .take(2)
                    if (normalized.isNotEmpty()) return normalized
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.d(TAG, "Last.fm track.getInfo miss for $title / $artist", e)
            }
        }

        if (artist.isNotBlank()) {
            try {
                val response = api.get(
                    mapOf(
                        "method" to "artist.getTopTags",
                        "artist" to artist,
                        "autocorrect" to "1",
                        "api_key" to session.apiKey,
                        "format" to "json",
                    ),
                )
                val body = response.body()?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val parsed = json.parseToJsonElement(body).jsonObject
                    val tagsEl = parsed["toptags"]?.jsonObject?.get("tag")
                    val normalized = GenerateJson.asObjectList(tagsEl)
                        .mapNotNull { (it["name"] as? JsonPrimitive)?.content }
                        .mapNotNull { GenreNormalizer.normalizeSingle(it) }
                        .distinct()
                        .take(2)
                    if (normalized.isNotEmpty()) return normalized
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.d(TAG, "Last.fm artist.getTopTags miss for $artist", e)
            }
        }
        return emptyList()
    }

    private suspend fun resolveViaDeezer(title: String, artist: String): List<String> = withContext(Dispatchers.IO) {
        val deezerUserAgent = "OnyxMusic/1.2 (Android)"
        val query = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (query.isBlank()) return@withContext emptyList()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val searchReq = Request.Builder()
                .url("https://api.deezer.com/search?q=$encoded&limit=3")
                .header("User-Agent", deezerUserAgent)
                .header("Accept", "application/json")
                .get()
                .build()
            val searchBody = okHttpClient.newCall(searchReq).awaitSuccessfulBodyOrNull() ?: return@withContext emptyList()
            val dataArr = json.parseToJsonElement(searchBody).jsonObject["data"]?.jsonArray ?: return@withContext emptyList()
            val targetArtist = artist.lowercase()
            val bestTrack = dataArr
                .mapNotNull { it as? kotlinx.serialization.json.JsonObject }
                .firstOrNull { obj ->
                    val dzArtist = (obj["artist"]?.jsonObject?.get("name") as? JsonPrimitive)?.content?.lowercase().orEmpty()
                    targetArtist.isBlank() || dzArtist.contains(targetArtist) || targetArtist.contains(dzArtist)
                }
                ?: (dataArr.firstOrNull() as? kotlinx.serialization.json.JsonObject)
                ?: return@withContext emptyList()

            val albumId = (bestTrack["album"]?.jsonObject?.get("id") as? JsonPrimitive)?.longOrNull ?: return@withContext emptyList()
            val albumReq = Request.Builder()
                .url("https://api.deezer.com/album/$albumId")
                .header("User-Agent", deezerUserAgent)
                .header("Accept", "application/json")
                .get()
                .build()
            val albumBody = okHttpClient.newCall(albumReq).awaitSuccessfulBodyOrNull() ?: return@withContext emptyList()
            val genresArr = json.parseToJsonElement(albumBody).jsonObject["genres"]?.jsonObject?.get("data")?.jsonArray
                ?: return@withContext emptyList()
            genresArr
                .mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.get("name") as? JsonPrimitive }
                .mapNotNull { GenreNormalizer.normalizeSingle(it.content) }
                .distinct()
                .take(2)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.d(TAG, "Deezer genre miss for $title / $artist", e)
            emptyList()
        }
    }

    private suspend fun resolveViaITunes(title: String, artist: String): List<String> {
        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (query.isBlank()) return emptyList()
        return try {
            val songs = iTunesSearchClient.searchSongs(query, limit = 3)
            val targetArtist = artist.lowercase()
            val best = songs.firstOrNull { song ->
                val a = song.artistName?.lowercase().orEmpty()
                targetArtist.isBlank() || a.contains(targetArtist) || targetArtist.contains(a)
            } ?: songs.firstOrNull()
            val rawGenre = best?.primaryGenreName ?: return emptyList()
            GenreNormalizer.splitAndNormalize(rawGenre, maxTags = 2)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveToCache(trackKey: String, artistKey: String, genre: String) {
        synchronized(lock) {
            memoryCache[trackKey] = genre
            if (artistKey.isNotBlank()) memoryCache[artistKey] = genre
        }
        try {
            val editor = prefs.edit()
            if (prefs.all.size > PERSIST_MAX_ENTRIES) {
                editor.clear()
            }
            editor.putString(trackKey, genre)
            if (artistKey.isNotBlank()) editor.putString(artistKey, genre)
            editor.apply()
        } catch (_: Exception) {}
    }

    private fun trackCacheKey(title: String, artist: String): String =
        "t:${title.trim().lowercase()}|${artist.trim().lowercase()}"

    private fun artistCacheKey(artist: String): String =
        if (artist.isBlank()) "" else "a:${artist.trim().lowercase()}"
}
