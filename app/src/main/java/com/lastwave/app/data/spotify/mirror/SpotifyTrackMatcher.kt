package com.lastwave.app.data.spotify.mirror

import android.util.Log
import com.lastwave.app.data.spotify.SpotifyAuthRepository
import com.lastwave.app.playback.MediaType
import com.lastwave.app.playback.PlayableTrack
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Candidate track returned from Spotify's catalog API.
 */
data class SpotifyCatalogCandidate(
    val id: String,
    val uri: String,
    val title: String,
    val artists: List<String>,
    val durationMs: Long?,
    val isrc: String? = null,
)

/**
 * Resolution result for any track played in Onyx Music.
 */
sealed class SpotifyMatchResult {
    abstract val trackKey: String
    abstract val entity: SpotifyTrackMatchEntity

    data class Matched(
        override val trackKey: String,
        override val entity: SpotifyTrackMatchEntity,
        val fromCache: Boolean,
    ) : SpotifyMatchResult()

    data class LocalOnly(
        override val trackKey: String,
        override val entity: SpotifyTrackMatchEntity,
        val reason: String,
        val fromCache: Boolean,
    ) : SpotifyMatchResult()
}

/**
 * Network gateway for Spotify catalog track search, ISRC lookup, and URI validation.
 */
interface SpotifyCatalogClient {
    suspend fun searchByIsrc(isrc: String): List<SpotifyCatalogCandidate>
    suspend fun searchTracks(cleanTitle: String, cleanArtist: String): List<SpotifyCatalogCandidate>
    suspend fun fetchTrackById(trackId: String): SpotifyCatalogCandidate?
}

/**
 * Pure normalization and fuzzy scoring utilities shared by [SpotifyTrackMatcher] and unit tests.
 */
object SpotifyTrackNormalizer {
    private val SPOTIFY_URI_REGEX = Regex("""spotify:track:([A-Za-z0-9]{22})""")
    private val SPOTIFY_URL_REGEX =
        Regex("""https?://open\.spotify\.com/(?:intl-[a-z]{2}/)?track/([A-Za-z0-9]{22})""")
    private val BARE_SPOTIFY_ID_REGEX = Regex("""^[A-Za-z0-9]{22}$""")
    private val ISRC_PREFIX_REGEX = Regex("""(?i)\bisrc[:\s_-]*([A-Z]{2}[A-Z0-9]{3}\d{7})\b""")
    private val BARE_ISRC_REGEX = Regex("""^[A-Z]{2}[A-Z0-9]{3}\d{7}$""")

    private val DIACRITICS_REGEX = Regex("""\p{InCombiningDiacriticalMarks}+""")
    private val YOUTUBE_BRACKET_NOISE_REGEX = Regex(
        """(?i)\s*[\(\[\{]\s*(?:official\s*(?:music\s*)?video|official\s*audio|official\s*lyric\s*video|official\s*visualizer|music\s*video|lyric\s*video|lyrics?|visualizer|audio\s*only|audio|full\s*(?:song|audio|video)|m/?v|hd|4k|uhd|hq|explicit|clean(?:\s*version)?|prod\.?\s*by[^\)\]\}]*|directed\s*by[^\)\]\}]*|video\s*clip|clip\s*officiel|video\s*oficial|out\s*now)\s*[\)\]\}]""",
    )
    private val TRAILING_NOISE_PIPE_REGEX = Regex(
        """(?i)\s*(?:\||//)\s*(?:official\s*(?:music\s*)?video|official\s*audio|lyrics?|visualizer|hd|4k|vevo|audio).*$""",
    )
    private val REMASTER_SUFFIX_REGEX = Regex(
        """(?i)(?:\s*-\s*(?:\d{4}\s*)?remaster(?:ed)?(?:\s*\d{4})?|\s*[\(\[](?:\d{4}\s*)?remaster(?:ed)?(?:\s*\d{4})?(?:\s*version)?[\)\]])""",
    )
    private val FEATURED_BRACKET_REGEX = Regex(
        """(?i)\s*[\(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s+([^\)\]]+)[\)\]]""",
    )
    private val FEATURED_INLINE_REGEX = Regex(
        """(?i)\s+(?:feat\.?|ft\.?|featuring)\s+(.+)$""",
    )
    private val ARTIST_CHANNEL_NOISE_REGEX = Regex(
        """(?i)(?:\s*-\s*topic$|\s*vevo$|\s+official$|\s+music$)""",
    )
    private val ARTIST_SPLIT_REGEX = Regex(
        """(?i)\s*(?:,|&|\+|/|;|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\band\b|\bx\b|×)\s*""",
    )
    private val NON_ALNUM_REGEX = Regex("""[^\p{L}\p{N}\s]""")
    private val MULTI_SPACE_REGEX = Regex("""\s+""")

    private val EDITION_MODIFIERS = listOf(
        "live",
        "instrumental",
        "karaoke",
        "sped up",
        "slowed",
        "reverb",
        "nightcore",
        "8d",
        "acoustic",
        "unplugged",
        "remix",
        "cover",
        "tribute",
        "demo",
        "acapella",
        "a cappella",
    )

    data class NormalizedQuery(
        val cleanTitle: String,
        val normalizedTitle: String,
        val primaryArtist: String,
        val allArtists: List<String>,
        val editionFlags: Set<String>,
    )

    fun extractSpotifyUri(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        SPOTIFY_URI_REGEX.find(trimmed)?.let { return "spotify:track:${it.groupValues[1]}" }
        SPOTIFY_URL_REGEX.find(trimmed)?.let { return "spotify:track:${it.groupValues[1]}" }
        if (BARE_SPOTIFY_ID_REGEX.matches(trimmed)) {
            return "spotify:track:$trimmed"
        }
        return null
    }

    fun extractSpotifyUri(track: PlayableTrack): String? =
        extractSpotifyUri(track.internalTrackId)
            ?: extractSpotifyUri(track.playbackUrl)
            ?: extractSpotifyUri(track.videoId)

    fun extractIsrc(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        ISRC_PREFIX_REGEX.find(trimmed)?.let { return it.groupValues[1].uppercase(Locale.US) }
        val compact = trimmed.replace("-", "").uppercase(Locale.US)
        if (BARE_ISRC_REGEX.matches(compact)) return compact
        return null
    }

    fun extractIsrc(track: PlayableTrack): String? =
        extractIsrc(track.internalTrackId)

    fun stripDiacritics(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFKD)
        return DIACRITICS_REGEX.replace(decomposed, "")
    }

    fun normalizeComparable(input: String): String =
        stripDiacritics(input)
            .lowercase(Locale.US)
            .replace(NON_ALNUM_REGEX, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()

    fun cleanArtistString(rawArtist: String): String =
        rawArtist.trim()
            .replace(ARTIST_CHANNEL_NOISE_REGEX, "")
            .trim()

    fun splitArtists(rawArtist: String): List<String> =
        cleanArtistString(rawArtist)
            .split(ARTIST_SPLIT_REGEX)
            .map { cleanArtistString(it) }
            .filter { it.isNotBlank() }
            .distinctBy { normalizeComparable(it) }

    fun normalizeQuery(rawTitle: String, rawArtist: String): NormalizedQuery {
        var workingTitle = rawTitle.trim()
        val baseArtists = splitArtists(rawArtist).toMutableList()

        // Strip leading "Artist - Title" prefix common on YouTube uploads when artist matches prefix.
        val dashIdx = workingTitle.indexOf(" - ")
        if (dashIdx > 0 && dashIdx < workingTitle.length - 3) {
            val prefix = workingTitle.substring(0, dashIdx).trim()
            val rest = workingTitle.substring(dashIdx + 3).trim()
            val normPrefix = normalizeComparable(prefix)
            val artistMatchesPrefix = baseArtists.any { a ->
                val na = normalizeComparable(a)
                na.isNotBlank() && (na == normPrefix || normPrefix.contains(na) || na.contains(normPrefix))
            }
            if (artistMatchesPrefix && rest.isNotBlank()) {
                workingTitle = rest
            }
        }

        // Pull out featured artists before stripping brackets so collaborations aid matching.
        FEATURED_BRACKET_REGEX.findAll(workingTitle).forEach { m ->
            baseArtists += splitArtists(m.groupValues[1])
        }
        workingTitle = workingTitle.replace(FEATURED_BRACKET_REGEX, " ")

        FEATURED_INLINE_REGEX.find(workingTitle)?.let { m ->
            baseArtists += splitArtists(m.groupValues[1])
            workingTitle = workingTitle.substring(0, m.range.first)
        }

        // Remove YouTube upload noise and remaster tags.
        var previous: String
        do {
            previous = workingTitle
            workingTitle = workingTitle
                .replace(YOUTUBE_BRACKET_NOISE_REGEX, " ")
                .replace(TRAILING_NOISE_PIPE_REGEX, " ")
                .replace(REMASTER_SUFFIX_REGEX, " ")
                .trim()
        } while (workingTitle != previous)

        // Strip dangling separators at end of title.
        workingTitle = workingTitle
            .trimEnd('-', '|', '/', ' ')
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()

        val cleanTitle = workingTitle.ifBlank { rawTitle.trim() }
        val distinctArtists = baseArtists
            .filter { it.isNotBlank() }
            .distinctBy { normalizeComparable(it) }
        val primaryArtist = distinctArtists.firstOrNull() ?: cleanArtistString(rawArtist)

        return NormalizedQuery(
            cleanTitle = cleanTitle,
            normalizedTitle = normalizeComparable(cleanTitle),
            primaryArtist = primaryArtist,
            allArtists = distinctArtists.ifEmpty { listOfNotNull(primaryArtist.takeIf { it.isNotBlank() }) },
            editionFlags = extractEditionFlags("$rawTitle $cleanTitle"),
        )
    }

    fun canonicalTrackKey(title: String, artist: String): String {
        val q = normalizeQuery(title, artist)
        val t = q.normalizedTitle.ifBlank { normalizeComparable(title) }
        val a = normalizeComparable(q.primaryArtist).ifBlank { normalizeComparable(artist) }
        return "$t|$a"
    }

    fun canonicalTrackKey(track: PlayableTrack): String =
        canonicalTrackKey(track.title, track.artist)

    fun extractEditionFlags(text: String): Set<String> {
        val norm = " ${normalizeComparable(text)} "
        return EDITION_MODIFIERS.filterTo(mutableSetOf()) { modifier ->
            norm.contains(" $modifier ")
        }
    }

    /**
     * Scores a Spotify catalog candidate against a normalized Onyx Music track query in [0f, 1f].
     */
    fun scoreCandidate(
        query: NormalizedQuery,
        sourceDurationMs: Long?,
        candidate: SpotifyCatalogCandidate,
    ): Float {
        val candNorm = normalizeQuery(
            rawTitle = candidate.title,
            rawArtist = candidate.artists.joinToString(", "),
        )
        val titleSim = textSimilarity(query.normalizedTitle, candNorm.normalizedTitle)
        val artistSim = artistSimilarity(query.allArtists, candNorm.allArtists)

        val hasDurations = sourceDurationMs != null && sourceDurationMs > 0L &&
            candidate.durationMs != null && candidate.durationMs > 0L

        var score = if (hasDurations) {
            val durationScore = durationSimilarity(sourceDurationMs!!, candidate.durationMs!!)
            (titleSim * 0.50f) + (artistSim * 0.35f) + (durationScore * 0.15f)
        } else {
            (titleSim * 0.58f) + (artistSim * 0.42f)
        }

        // Reject severe duration divergences (e.g. short clip vs full album track or extended mix).
        if (hasDurations) {
            val deltaMs = abs(sourceDurationMs!! - candidate.durationMs!!)
            if (deltaMs > HARD_DURATION_PENALTY_MS) {
                score -= 0.25f
            }
        }

        // Guard against matching a live/remix/karaoke/sped-up upload to a studio master or vice versa.
        val mismatchedEditions = (query.editionFlags - candNorm.editionFlags) +
            (candNorm.editionFlags - query.editionFlags)
        if (mismatchedEditions.isNotEmpty()) {
            score -= 0.22f
        }

        // Strong artist mismatch guard: a completely unrelated artist should not pass on title alone.
        if (query.allArtists.isNotEmpty() && candNorm.allArtists.isNotEmpty() && artistSim < 0.25f) {
            score = min(score, 0.55f)
        }

        return score.coerceIn(0f, 1f)
    }

    internal fun durationSimilarity(sourceMs: Long, candidateMs: Long): Float {
        val delta = abs(sourceMs - candidateMs)
        return when {
            delta <= 3_000L -> 1.0f
            delta <= 8_000L -> 1.0f - ((delta - 3_000L) / 5_000f) * 0.30f
            delta <= 15_000L -> 0.70f - ((delta - 8_000L) / 7_000f) * 0.45f
            else -> 0.0f
        }
    }

    internal fun textSimilarity(a: String, b: String): Float {
        if (a.isBlank() || b.isBlank()) return 0f
        if (a == b) return 1f
        val levenshtein = levenshteinSimilarity(a, b)
        val tokensA = a.split(' ').filter { it.isNotBlank() }.toSet()
        val tokensB = b.split(' ').filter { it.isNotBlank() }.toSet()
        val intersection = tokensA.intersect(tokensB).size
        val union = tokensA.union(tokensB).size
        val jaccard = if (union == 0) 0f else intersection.toFloat() / union.toFloat()
        val containment = if (min(tokensA.size, tokensB.size) == 0) 0f else {
            intersection.toFloat() / min(tokensA.size, tokensB.size).toFloat()
        }
        return max(levenshtein, (jaccard * 0.65f + containment * 0.35f)).coerceIn(0f, 1f)
    }

    internal fun artistSimilarity(sourceArtists: List<String>, candidateArtists: List<String>): Float {
        if (sourceArtists.isEmpty() || candidateArtists.isEmpty()) return 0f
        val srcNorm = sourceArtists.map(::normalizeComparable).filter { it.isNotBlank() }
        val candNorm = candidateArtists.map(::normalizeComparable).filter { it.isNotBlank() }
        if (srcNorm.isEmpty() || candNorm.isEmpty()) return 0f

        // Exact primary artist match gets full credit; featured collaborators boost partial matches.
        val primaryScore = candNorm.maxOf { c -> textSimilarity(srcNorm.first(), c) }
        val bestPairScores = srcNorm.map { s -> candNorm.maxOf { c -> textSimilarity(s, c) } }
        val avgCoverage = bestPairScores.average().toFloat()
        return max(primaryScore * 0.85f + avgCoverage * 0.15f, avgCoverage).coerceIn(0f, 1f)
    }

    internal fun levenshteinSimilarity(a: String, b: String): Float {
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1f
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(
                    min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost,
                )
            }
            System.arraycopy(curr, 0, prev, 0, prev.size)
        }
        return (1f - prev[b.length].toFloat() / maxLen.toFloat()).coerceIn(0f, 1f)
    }

    private const val HARD_DURATION_PENALTY_MS = 25_000L
}

/**
 * Resolves tracks from any Onyx Music playback source to a Spotify catalog track URI,
 * caching both positive matches and Local-Only verdicts in [SpotifyMirrorDatabase].
 */
@Singleton
class SpotifyTrackMatcher @Inject constructor(
    private val matchDao: SpotifyTrackMatchDao,
    private val catalogClient: SpotifyCatalogClient,
) {
    /**
     * Resolves [track] via:
     * 1. Room cache (0 API calls for repeat listens or manual overrides)
     * 2. Existing Spotify URI on track metadata
     * 3. ISRC lookup (local DB index -> Spotify catalog ISRC search)
     * 4. Cleaned title/artist fuzzy search with duration delta tolerance
     *
     * Tracks below [minConfidence] or with no catalog hit are cached cleanly as Local-Only.
     */
    suspend fun resolveTrack(
        track: PlayableTrack,
        minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
        explicitIsrc: String? = null,
        forceRefresh: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): SpotifyMatchResult {
        val trackKey = SpotifyTrackNormalizer.canonicalTrackKey(track)

        if (track.mediaType == MediaType.PODCAST_EPISODE) {
            val entity = SpotifyTrackMatchEntity(
                trackKey = trackKey,
                sourceTitle = track.title,
                sourceArtist = track.artist,
                spotifyUri = null,
                isrc = null,
                matchedTitle = null,
                matchedArtist = null,
                matchedDurationMs = null,
                confidence = 0f,
                isCatalogMatch = false,
                resolvedAtMs = nowMs,
            )
            return SpotifyMatchResult.LocalOnly(
                trackKey = trackKey,
                entity = entity,
                reason = "Podcast episodes are not mirrored",
                fromCache = true,
            )
        }

        // 0. Cache check
        if (!forceRefresh) {
            val cached = matchDao.findByTrackKey(trackKey)
            if (cached != null) {
                val ageMs = nowMs - cached.resolvedAtMs
                val ttlMs = if (cached.isCatalogMatch) POSITIVE_CACHE_TTL_MS else NEGATIVE_CACHE_TTL_MS
                if (cached.isManualOverride || ageMs in 0L..ttlMs) {
                    return if (cached.isCatalogMatch && !cached.spotifyUri.isNullOrBlank()) {
                        SpotifyMatchResult.Matched(trackKey, cached, fromCache = true)
                    } else {
                        SpotifyMatchResult.LocalOnly(
                            trackKey = trackKey,
                            entity = cached,
                            reason = if (cached.isManualOverride) "Marked local-only" else "Not in Spotify catalog",
                            fromCache = true,
                        )
                    }
                }
            }
        }

        // 1. Existing Spotify URI directly on track
        val directUri = SpotifyTrackNormalizer.extractSpotifyUri(track)
        if (directUri != null) {
            val entity = SpotifyTrackMatchEntity(
                trackKey = trackKey,
                sourceTitle = track.title,
                sourceArtist = track.artist,
                spotifyUri = directUri,
                isrc = explicitIsrc ?: SpotifyTrackNormalizer.extractIsrc(track),
                matchedTitle = track.title,
                matchedArtist = track.artist,
                matchedDurationMs = track.durationMs,
                confidence = 1.0f,
                isCatalogMatch = true,
                resolvedAtMs = nowMs,
            )
            matchDao.upsert(entity)
            return SpotifyMatchResult.Matched(trackKey, entity, fromCache = false)
        }

        // 2. ISRC lookup
        val isrc = SpotifyTrackNormalizer.extractIsrc(explicitIsrc)
            ?: SpotifyTrackNormalizer.extractIsrc(track)
        if (isrc != null) {
            if (!forceRefresh) {
                val cachedByIsrc = matchDao.findByIsrc(isrc)
                if (cachedByIsrc != null && !cachedByIsrc.spotifyUri.isNullOrBlank()) {
                    val aliased = cachedByIsrc.copy(
                        trackKey = trackKey,
                        sourceTitle = track.title,
                        sourceArtist = track.artist,
                        resolvedAtMs = nowMs,
                    )
                    matchDao.upsert(aliased)
                    return SpotifyMatchResult.Matched(trackKey, aliased, fromCache = true)
                }
            }
            val isrcCandidates = runCatching { catalogClient.searchByIsrc(isrc) }.getOrDefault(emptyList())
            val bestIsrc = pickBestIsrcCandidate(isrcCandidates, track.durationMs)
            if (bestIsrc != null) {
                val entity = SpotifyTrackMatchEntity(
                    trackKey = trackKey,
                    sourceTitle = track.title,
                    sourceArtist = track.artist,
                    spotifyUri = bestIsrc.uri,
                    isrc = bestIsrc.isrc ?: isrc,
                    matchedTitle = bestIsrc.title,
                    matchedArtist = bestIsrc.artists.joinToString(", "),
                    matchedDurationMs = bestIsrc.durationMs,
                    confidence = 0.98f,
                    isCatalogMatch = true,
                    resolvedAtMs = nowMs,
                )
                matchDao.upsert(entity)
                return SpotifyMatchResult.Matched(trackKey, entity, fromCache = false)
            }
        }

        // 3. Cleaned title + artist fuzzy search
        val normalized = SpotifyTrackNormalizer.normalizeQuery(track.title, track.artist)
        if (normalized.cleanTitle.isBlank()) {
            return persistLocalOnly(trackKey, track, isrc, 0f, "Missing track title", nowMs)
        }

        val candidates = runCatching {
            catalogClient.searchTracks(normalized.cleanTitle, normalized.primaryArtist)
        }.getOrDefault(emptyList())

        if (candidates.isEmpty()) {
            return persistLocalOnly(trackKey, track, isrc, 0f, "No Spotify catalog match found", nowMs)
        }

        val scored = candidates.map { candidate ->
            candidate to SpotifyTrackNormalizer.scoreCandidate(normalized, track.durationMs, candidate)
        }.maxByOrNull { it.second }

        val bestCandidate = scored?.first
        val bestScore = scored?.second ?: 0f

        return if (bestCandidate != null && bestScore >= minConfidence) {
            val entity = SpotifyTrackMatchEntity(
                trackKey = trackKey,
                sourceTitle = track.title,
                sourceArtist = track.artist,
                spotifyUri = bestCandidate.uri,
                isrc = bestCandidate.isrc ?: isrc,
                matchedTitle = bestCandidate.title,
                matchedArtist = bestCandidate.artists.joinToString(", "),
                matchedDurationMs = bestCandidate.durationMs,
                confidence = bestScore,
                isCatalogMatch = true,
                resolvedAtMs = nowMs,
            )
            matchDao.upsert(entity)
            SpotifyMatchResult.Matched(trackKey, entity, fromCache = false)
        } else {
            persistLocalOnly(
                trackKey = trackKey,
                track = track,
                isrc = isrc,
                bestConfidence = bestScore,
                reason = "Low match confidence (${(bestScore * 100).toInt()}%)",
                nowMs = nowMs,
            )
        }
    }

    /**
     * Persists a user-supplied Spotify track URI or URL override for [track].
     */
    suspend fun setManualOverride(
        track: PlayableTrack,
        rawUriOrUrl: String,
        nowMs: Long = System.currentTimeMillis(),
    ): SpotifyMatchResult.Matched? {
        val uri = SpotifyTrackNormalizer.extractSpotifyUri(rawUriOrUrl) ?: return null
        val trackId = uri.removePrefix("spotify:track:")
        val trackKey = SpotifyTrackNormalizer.canonicalTrackKey(track)
        val fetched = runCatching { catalogClient.fetchTrackById(trackId) }.getOrNull()
        val entity = SpotifyTrackMatchEntity(
            trackKey = trackKey,
            sourceTitle = track.title,
            sourceArtist = track.artist,
            spotifyUri = uri,
            isrc = fetched?.isrc,
            matchedTitle = fetched?.title ?: track.title,
            matchedArtist = fetched?.artists?.joinToString(", ")?.takeIf { it.isNotBlank() } ?: track.artist,
            matchedDurationMs = fetched?.durationMs ?: track.durationMs,
            confidence = 1.0f,
            isCatalogMatch = true,
            isManualOverride = true,
            resolvedAtMs = nowMs,
        )
        matchDao.upsert(entity)
        return SpotifyMatchResult.Matched(trackKey, entity, fromCache = false)
    }

    suspend fun markAsLocalOnly(
        track: PlayableTrack,
        nowMs: Long = System.currentTimeMillis(),
    ): SpotifyMatchResult.LocalOnly {
        val trackKey = SpotifyTrackNormalizer.canonicalTrackKey(track)
        val entity = SpotifyTrackMatchEntity(
            trackKey = trackKey,
            sourceTitle = track.title,
            sourceArtist = track.artist,
            spotifyUri = null,
            isrc = null,
            matchedTitle = null,
            matchedArtist = null,
            matchedDurationMs = null,
            confidence = 0f,
            isCatalogMatch = false,
            isManualOverride = true,
            resolvedAtMs = nowMs,
        )
        matchDao.upsert(entity)
        return SpotifyMatchResult.LocalOnly(
            trackKey = trackKey,
            entity = entity,
            reason = "Marked local-only",
            fromCache = false,
        )
    }

    suspend fun clearMatchForTrack(track: PlayableTrack) {
        matchDao.deleteByTrackKey(SpotifyTrackNormalizer.canonicalTrackKey(track))
    }

    suspend fun clearAllCachedMatches() {
        matchDao.clearAll()
    }

    private suspend fun persistLocalOnly(
        trackKey: String,
        track: PlayableTrack,
        isrc: String?,
        bestConfidence: Float,
        reason: String,
        nowMs: Long,
    ): SpotifyMatchResult.LocalOnly {
        val entity = SpotifyTrackMatchEntity(
            trackKey = trackKey,
            sourceTitle = track.title,
            sourceArtist = track.artist,
            spotifyUri = null,
            isrc = isrc,
            matchedTitle = null,
            matchedArtist = null,
            matchedDurationMs = null,
            confidence = bestConfidence,
            isCatalogMatch = false,
            resolvedAtMs = nowMs,
        )
        matchDao.upsert(entity)
        return SpotifyMatchResult.LocalOnly(
            trackKey = trackKey,
            entity = entity,
            reason = reason,
            fromCache = false,
        )
    }

    private fun pickBestIsrcCandidate(
        candidates: List<SpotifyCatalogCandidate>,
        durationMs: Long?,
    ): SpotifyCatalogCandidate? {
        if (candidates.isEmpty()) return null
        if (durationMs == null || durationMs <= 0L) return candidates.first()
        return candidates.minByOrNull { c ->
            val d = c.durationMs
            if (d != null && d > 0L) abs(d - durationMs) else Long.MAX_VALUE
        } ?: candidates.first()
    }

    companion object {
        const val DEFAULT_MIN_CONFIDENCE = 0.72f
        const val POSITIVE_CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000L
        const val NEGATIVE_CACHE_TTL_MS = 24L * 60 * 60 * 1000L
    }
}

/**
 * OkHttp-backed implementation of [SpotifyCatalogClient] using Spotify's Web API catalog search.
 */
@Singleton
class HttpSpotifyCatalogClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val authRepository: SpotifyAuthRepository,
) : SpotifyCatalogClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun searchByIsrc(isrc: String): List<SpotifyCatalogCandidate> =
        withContext(Dispatchers.IO) {
            val token = authRepository.getPersonalToken() ?: return@withContext emptyList()
            executeSearch(token, "isrc:$isrc", limit = 3)
        }

    override suspend fun searchTracks(
        cleanTitle: String,
        cleanArtist: String,
    ): List<SpotifyCatalogCandidate> = withContext(Dispatchers.IO) {
        val token = authRepository.getPersonalToken() ?: return@withContext emptyList()

        // Field-scoped query first for high precision.
        if (cleanArtist.isNotBlank()) {
            val scopedQuery = """track:"${cleanTitle.replace("\"", "")}" artist:"${cleanArtist.replace("\"", "")}""""
            val scoped = executeSearch(token, scopedQuery, limit = 5)
            if (scoped.isNotEmpty()) return@withContext scoped
        }

        // Broad query fallback handles punctuation/script differences.
        val broadQuery = listOf(cleanTitle, cleanArtist).filter { it.isNotBlank() }.joinToString(" ")
        executeSearch(token, broadQuery, limit = 5)
    }

    override suspend fun fetchTrackById(trackId: String): SpotifyCatalogCandidate? =
        withContext(Dispatchers.IO) {
            val token = authRepository.getPersonalToken() ?: return@withContext null
            val request = Request.Builder()
                .url("$TRACK_ENDPOINT/$trackId")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()
            runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string().orEmpty()
                    val item = json.decodeFromString<CatalogTrackDto>(body)
                    item.toCandidate()
                }
            }.getOrNull()
        }

    private fun executeSearch(
        token: String,
        query: String,
        limit: Int,
    ): List<SpotifyCatalogCandidate> {
        val url = SEARCH_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("type", "track")
            .addQueryParameter("limit", limit.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        return runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Spotify catalog search failed HTTP ${response.code}")
                    return@use emptyList()
                }
                val body = response.body?.string().orEmpty()
                val parsed = json.decodeFromString<CatalogSearchResponseDto>(body)
                parsed.tracks?.items.orEmpty().mapNotNull { it.toCandidate() }
            }
        }.getOrElse { error ->
            Log.w(TAG, "Spotify catalog search error: ${error.message}")
            emptyList()
        }
    }

    @Serializable
    private data class CatalogSearchResponseDto(
        val tracks: CatalogTracksPageDto? = null,
    )

    @Serializable
    private data class CatalogTracksPageDto(
        val items: List<CatalogTrackDto> = emptyList(),
    )

    @Serializable
    private data class CatalogTrackDto(
        val id: String? = null,
        val uri: String? = null,
        val name: String? = null,
        val duration_ms: Long? = null,
        val artists: List<CatalogArtistDto> = emptyList(),
        val external_ids: CatalogExternalIdsDto? = null,
    ) {
        fun toCandidate(): SpotifyCatalogCandidate? {
            val resolvedId = id?.takeIf { it.isNotBlank() }
                ?: uri?.removePrefix("spotify:track:")?.takeIf { it.isNotBlank() }
                ?: return null
            val resolvedUri = uri?.takeIf { it.startsWith("spotify:track:") }
                ?: "spotify:track:$resolvedId"
            val title = name?.takeIf { it.isNotBlank() } ?: return null
            return SpotifyCatalogCandidate(
                id = resolvedId,
                uri = resolvedUri,
                title = title,
                artists = artists.mapNotNull { it.name?.takeIf(String::isNotBlank) },
                durationMs = duration_ms,
                isrc = external_ids?.isrc?.takeIf { it.isNotBlank() },
            )
        }
    }

    @Serializable
    private data class CatalogArtistDto(
        val name: String? = null,
    )

    @Serializable
    private data class CatalogExternalIdsDto(
        val isrc: String? = null,
    )

    private companion object {
        const val TAG = "SpotifyTrackMatcher"
        const val SEARCH_ENDPOINT = "https://api.spotify.com/v1/search"
        const val TRACK_ENDPOINT = "https://api.spotify.com/v1/tracks"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SpotifyMirrorBindingsModule {
    @Binds
    @Singleton
    abstract fun bindSpotifyCatalogClient(impl: HttpSpotifyCatalogClient): SpotifyCatalogClient

    @Binds
    @Singleton
    abstract fun bindSilentSpotifyTransport(impl: MutedWebPlaybackReceiver): SilentSpotifyTransport
}
