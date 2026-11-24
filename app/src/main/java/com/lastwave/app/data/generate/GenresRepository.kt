package com.lastwave.app.data.generate

import android.util.Log
import androidx.compose.runtime.Immutable
import com.lastwave.app.data.genre.GenreNormalizer
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.local.awaitLoadedSession
import com.lastwave.app.data.network.LastFmApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GenresRepository"

@Immutable
data class GenreStat(
    val name: String,
    val count: Long,
    val percentOfTop: Float,
    val sharePercent: Float = percentOfTop,
    val listeningMs: Long = 0L,
    val topArtists: List<String> = emptyList(),
)

/**
 * Genres engine powering Your Genres, Stats Hub genre breakdown, and Genre Detail.
 *
 * Uses local Room music scrobbles first (fast, non-blocking, with background
 * backfill for untagged artists), falling back to Last.fm tags/artists when
 * local history is sparse or when viewing another profile. Computes true share
 * percentages across all categorized listening rather than only the top N slice.
 */
@Singleton
class GenresRepository @Inject constructor(
    private val api: LastFmApiService,
    private val sessionPreferences: SessionPreferences,
    private val generateRepository: GenerateRepository,
    private val tasteProfileProvider: TasteProfileProvider,
    private val viewingProfileState: com.lastwave.app.data.repository.ViewingProfileState,
) {
    @Inject
    internal lateinit var injectedScrobbleEventDao: com.lastwave.app.data.scrobble.ScrobbleEventDao

    @Inject
    internal lateinit var injectedGenreResolver: com.lastwave.app.data.genre.GenreResolver

    private val scrobbleEventDao: com.lastwave.app.data.scrobble.ScrobbleEventDao?
        get() = if (::injectedScrobbleEventDao.isInitialized) injectedScrobbleEventDao else null

    private val genreResolver: com.lastwave.app.data.genre.GenreResolver?
        get() = if (::injectedGenreResolver.isInitialized) injectedGenreResolver else null

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backfillRunning = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun username(): String =
        viewingProfileState.viewingUsername.value ?: sessionPreferences.awaitLoadedSession().username

    private suspend fun call(params: Map<String, String>): JsonObject = generateRepository.call(params)

    /** Periods use only dated listening; missing period history stays empty.
     *  Overall retains the personal-tag fast path and undated guest/profile/chart fallbacks. */
    suspend fun fetchGenreStats(period: String): List<GenreStat> {
        val overall = period == "overall"
        val user = username()
        val guest = user.isBlank() || user.equals("Guest User", ignoreCase = true)
        val isOwnProfile = viewingProfileState.viewingUsername.value == null

        // First check local Room music scrobbles when viewing own profile.
        if (isOwnProfile && scrobbleEventDao != null) {
            val localStats = fetchLocalRoomGenreStats(period)
            if (localStats.size >= 3 || (guest && localStats.isNotEmpty())) {
                return localStats
            }
        }

        if (!overall && guest) return emptyList()

        // user.gettoptags has no period parameter and cannot represent a week/month.
        if (overall && !guest) try {
            val d = call(mapOf("method" to "user.gettoptags", "user" to user, "limit" to "35"))
            val merged = mutableMapOf<String, Long>()
            GenerateJson.asObjectList(d["toptags"]?.jsonObject?.get("tag")).forEach { obj ->
                val rawName = (obj["name"] as? JsonPrimitive)?.content?.trim() ?: return@forEach
                val count = (obj["count"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                val canonical = GenreNormalizer.normalizeSingle(rawName) ?: return@forEach
                if (count > 0L) {
                    merged[canonical] = (merged[canonical] ?: 0L) + count
                }
            }
            if (merged.size >= 3) {
                val totalAll = merged.values.sum().coerceAtLeast(1L)
                val top = merged.entries.sortedByDescending { it.value }.take(20).map { it.key to it.value }
                return normalizeStats(top, totalOverride = totalAll)
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Log.d(TAG, "user.gettoptags miss", e)
        }

        return try {
            val artistsD = if (guest) null else try {
                call(mapOf("method" to "user.gettopartists", "user" to user, "period" to period, "limit" to "30"))
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (!overall) throw e
                Log.d(TAG, "user.gettopartists miss", e)
                null
            }
            val artists = GenerateJson.asObjectList(artistsD?.get("topartists")?.jsonObject?.get("artist"))
                .mapNotNull { obj ->
                    val name = (obj["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    val playcount = (obj["playcount"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 1L
                    name to playcount
                }

            val profileArtists = if (overall) {
                val profile = runCatching { tasteProfileProvider.get() }.getOrNull()
                currentCoroutineContext().ensureActive()
                (profile?.topArtistsRaw.orEmpty() + profile?.ytMusicFeedRaw.orEmpty().map { it.artist })
                    .filter(String::isNotBlank)
                    .distinctBy { it.trim().lowercase() }
                    .mapIndexed { index, artist -> artist to (24L - index).coerceAtLeast(1L) }
            } else emptyList()

            val weighted = mutableMapOf<String, Long>()
            val genreArtists = mutableMapOf<String, LinkedHashSet<String>>()
            coroutineScope {
                val artistTagResults = (artists + profileArtists)
                    .distinctBy { it.first.trim().lowercase() }
                    .take(16)
                    .map { (artistName, playcount) ->
                        async(Dispatchers.IO) {
                            val tags = try {
                                val td = call(mapOf("method" to "artist.gettoptags", "artist" to artistName))
                                GenerateJson.asObjectList(td["toptags"]?.jsonObject?.get("tag"))
                                    .mapNotNull { (it["name"] as? JsonPrimitive)?.content?.trim() }
                                    .mapNotNull { GenreNormalizer.normalizeSingle(it) }
                                    .distinct()
                                    .take(3)
                            } catch (e: Exception) {
                                currentCoroutineContext().ensureActive()
                                Log.d(TAG, "artist.gettoptags miss for $artistName", e)
                                emptyList()
                            }
                            Triple(artistName, playcount, tags)
                        }
                    }.awaitAll()
                for ((artistName, playcount, tags) in artistTagResults) {
                    for (tag in tags) {
                        weighted[tag] = (weighted[tag] ?: 0L) + playcount
                        genreArtists.getOrPut(tag) { LinkedHashSet() }.add(artistName)
                    }
                }
            }
            val totalAll = weighted.values.sum().coerceAtLeast(1L)
            val sorted = weighted.entries.sortedByDescending { it.value }.take(20).map { it.key to it.value }
            if (sorted.isNotEmpty() || !overall) {
                normalizeStats(
                    tags = sorted,
                    totalOverride = totalAll,
                    topArtistsMap = emptyMap(), // preserve exact GenreStat equality for Last.fm/test results
                )
            } else {
                fetchChartTags()
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Log.d(TAG, "genre derivation failed for $period", e)
            if (overall) fetchChartTags() else emptyList()
        }
    }

    private suspend fun fetchLocalRoomGenreStats(period: String): List<GenreStat> {
        val dao = scrobbleEventDao ?: return emptyList()
        val now = System.currentTimeMillis()
        val startMs = when (period) {
            "7day" -> now - 7L * 86_400_000L
            "1month" -> now - 30L * 86_400_000L
            "12month" -> now - 365L * 86_400_000L
            else -> 0L
        }
        val events = runCatching { dao.getMusicInRange(startMs, now) }.getOrDefault(emptyList())
        if (events.isEmpty()) return emptyList()

        // Fast non-blocking cache fill + trigger async background backfill for untagged artists.
        val resolver = genreResolver
        val untaggedArtists = mutableMapOf<String, Pair<String, String>>()
        val counts = mutableMapOf<String, Long>()
        val durations = mutableMapOf<String, Long>()
        val artistCountsByGenre = mutableMapOf<String, MutableMap<String, Int>>()

        for (ev in events) {
            val rawGenre = ev.genre.ifBlank {
                resolver?.peekCached(ev.title, ev.artist).orEmpty()
            }
            if (rawGenre.isBlank()) {
                val aLower = ev.artist.trim().lowercase()
                if (aLower.isNotBlank() && aLower !in untaggedArtists) {
                    untaggedArtists[aLower] = ev.title to ev.artist
                }
                continue
            }
            val tags = GenreNormalizer.splitAndNormalize(rawGenre, maxTags = 2)
            val listenMs = ev.listenedMs.takeIf { it > 0L } ?: ev.durationMs.coerceAtLeast(0L)
            for (tag in tags) {
                counts[tag] = (counts[tag] ?: 0L) + 1L
                durations[tag] = (durations[tag] ?: 0L) + listenMs
                if (ev.artist.isNotBlank()) {
                    val map = artistCountsByGenre.getOrPut(tag) { mutableMapOf() }
                    map[ev.artist] = (map[ev.artist] ?: 0) + 1
                }
            }
        }

        if (resolver != null && untaggedArtists.isNotEmpty()) {
            scheduleBackgroundGenreBackfill(dao, resolver, untaggedArtists.entries.take(24).map { it.key to it.value })
        }

        val totalAll = counts.values.sum().coerceAtLeast(1L)
        val sorted = counts.entries.sortedByDescending { it.value }.take(20).map { it.key to it.value }
        val topArtists = artistCountsByGenre.mapValues { (_, artistMap) ->
            artistMap.entries.sortedByDescending { it.value }.take(3).map { it.key }
        }
        return normalizeStats(
            tags = sorted,
            totalOverride = totalAll,
            listeningMsMap = durations,
            topArtistsMap = topArtists,
        )
    }

    private fun scheduleBackgroundGenreBackfill(
        dao: com.lastwave.app.data.scrobble.ScrobbleEventDao,
        resolver: com.lastwave.app.data.genre.GenreResolver,
        targets: List<Pair<String, Pair<String, String>>>,
    ) {
        if (!backfillRunning.compareAndSet(false, true)) return
        backgroundScope.launch {
            try {
                for ((artistLower, sample) in targets) {
                    val resolved = runCatching { resolver.resolve(sample.first, sample.second) }
                        .getOrDefault("")
                        .trim()
                    if (resolved.isNotBlank()) {
                        runCatching { dao.updateMissingGenreByArtist(artistLower, resolved) }
                    }
                }
            } finally {
                backfillRunning.set(false)
            }
        }
    }

    private suspend fun fetchChartTags(): List<GenreStat> {
        return try {
            val d = call(mapOf("method" to "chart.gettoptags", "limit" to "18"))
            val tags = GenerateJson.asObjectList(d["tags"]?.jsonObject?.get("tag"))
                .mapNotNull { obj ->
                    val raw = (obj["name"] as? JsonPrimitive)?.content?.trim() ?: return@mapNotNull null
                    val canonical = GenreNormalizer.normalizeSingle(raw) ?: return@mapNotNull null
                    val count = (obj["count"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 100L
                    canonical to count
                }
            normalizeStats(tags)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeStats(
        tags: List<Pair<String, Long>>,
        totalOverride: Long? = null,
        listeningMsMap: Map<String, Long> = emptyMap(),
        topArtistsMap: Map<String, List<String>> = emptyMap(),
    ): List<GenreStat> {
        if (tags.isEmpty()) return emptyList()
        val top = tags.maxOf { it.second }.coerceAtLeast(1L)
        val total = (totalOverride ?: tags.sumOf { it.second }).coerceAtLeast(1L)
        return tags.map { (name, count) ->
            GenreStat(
                name = name,
                count = count,
                percentOfTop = count.toFloat() / top.toFloat(),
                sharePercent = count.toFloat() / total.toFloat(),
                listeningMs = listeningMsMap[name] ?: 0L,
                topArtists = topArtistsMap[name].orEmpty(),
            )
        }
    }

    /**
     * Personalized Genre Detail track list ("Your Tracks"):
     * Queries the user's local Room listening history FIRST (works even without
     * Last.fm), then blends with Last.fm / taste profile genre matches and top
     * tag tracks for deeper discovery.
     */
    suspend fun fetchGenreTracks(genre: String, page: Int): List<GeneratedTrack> {
        val targetTag = GenreNormalizer.primaryGenre(genre)
        if (targetTag.isBlank()) return emptyList()

        val pool = mutableListOf<GeneratedTrack>()

        // 1. Local Room scrobbles matching this canonical genre (works offline & without Last.fm!)
        if (page == 1 && scrobbleEventDao != null) {
            val localEvents = runCatching { scrobbleEventDao?.getMusicInRange(0L, System.currentTimeMillis()) }
                .getOrNull().orEmpty()
            val resolver = genreResolver
            val matchedLocal = localEvents.filter { ev ->
                val raw = ev.genre.ifBlank { resolver?.peekCached(ev.title, ev.artist).orEmpty() }
                if (raw.isBlank()) return@filter false
                val normalized = GenreNormalizer.splitAndNormalize(raw, maxTags = 3)
                normalized.any { it == targetTag || it.contains(targetTag) || targetTag.contains(it) }
            }
            // Rank local tracks by play count
            val localRanked = matchedLocal
                .groupBy { "${it.artist.trim().lowercase()}|${it.title.trim().lowercase()}" }
                .entries
                .sortedByDescending { it.value.size }
                .map { (_, plays) ->
                    val sample = plays.first()
                    GeneratedTrack(
                        name = sample.title,
                        artist = sample.artist,
                        album = sample.album,
                        artworkUrl = sample.artworkUrl,
                        playcount = plays.size.toLong(),
                        durationMs = sample.durationMs.takeIf { it > 0L },
                    )
                }
            pool += localRanked.take(35)
        }

        val profile = try { tasteProfileProvider.get() } catch (_: Exception) { null }
        val userTopArtists = profile?.topArtistNames?.toList() ?: emptyList()

        if (page == 1 && profile != null) {
            val userTracks = (profile.topTracksRaw + profile.recentTracksRaw).distinctBy { it.key }
            val candidateArtists = (profile.topArtistsRaw +
                profile.ytMusicFeedRaw.map { it.artist } + userTopArtists)
                .filter(String::isNotBlank)
                .distinctBy { it.trim().lowercase() }
                .take(14)
            val genreMatchedArtists = coroutineScope {
                candidateArtists.map { artistName ->
                    async(Dispatchers.IO) {
                        try {
                            val td = call(mapOf("method" to "artist.gettoptags", "artist" to artistName))
                            val tags = GenerateJson.namesOf(td["toptags"]?.jsonObject?.get("tag"))
                                .mapNotNull { GenreNormalizer.normalizeSingle(it) }
                            artistName to tags.any { it == targetTag || it.contains(targetTag) || targetTag.contains(it) }
                        } catch (_: Exception) {
                            artistName to false
                        }
                    }
                }.awaitAll()
                    .filter { it.second }
                    .map { it.first.lowercase() }
                    .toSet()
            }

            for (track in userTracks) {
                if (track.artist.trim().lowercase() in genreMatchedArtists) {
                    pool += track
                }
            }

            pool += coroutineScope {
                genreMatchedArtists.take(4).map { artist ->
                    async(Dispatchers.IO) {
                        try {
                            val d = call(mapOf("method" to "artist.gettoptracks", "artist" to artist, "limit" to "10"))
                            GenerateJson.normalise(d["toptracks"]?.jsonObject?.get("track")).toList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }
        }

        // 2. Fetch tag's top tracks for the requested page
        try {
            val d = call(mapOf("method" to "tag.gettoptracks", "tag" to targetTag, "limit" to "40", "page" to page.toString()))
            pool += GenerateJson.normalise(d["tracks"]?.jsonObject?.get("track"))
        } catch (_: Exception) {}

        if (pool.isEmpty()) return emptyList()

        val deduped = generateRepository.deduplicate(pool)
        val userTopArtistSet = profile?.topArtistNames ?: emptySet()
        val userRecentArtistSet = profile?.recentArtists ?: emptySet()
        val userHeardKeys = (profile?.topTrackKeys ?: emptySet()) + (profile?.recentTrackKeys ?: emptySet())
        val ytFeedKeys = profile?.ytMusicFeedRaw?.map { it.key }?.toSet().orEmpty()
        val ytLikedKeys = profile?.ytMusicLikedRaw?.map { it.key }?.toSet().orEmpty()
        val artistAffinity = profile?.artistAffinity.orEmpty()
        val normalizedBoostArtists = userTopArtistSet.map { it.trim().lowercase() }.toSet()

        val scored = deduped.map { track ->
            val aKey = track.artist.trim().lowercase()
            var score = ((artistAffinity[aKey] ?: 0.0) * 20.0).toInt()
            val pc = track.playcount ?: 0L
            if (pc > 0L) score += (pc.coerceAtMost(25L).toInt()) + 15
            if (track.key in userHeardKeys) score += 8
            if (track.key in ytFeedKeys) score += 10
            if (track.key in ytLikedKeys) score += 8
            if (aKey in userTopArtistSet) score += 7
            if (aKey in userRecentArtistSet) score += 5
            if (aKey in normalizedBoostArtists) score += 5
            track to score
        }

        val sorted = scored
            .groupBy { it.second }
            .entries
            .sortedByDescending { it.key }
            .flatMap { it.value.shuffled() }
            .map { it.first }
        val allowed = generateRepository.filterRecommendationExclusions(sorted)
        return generateRepository.filterPlayable(allowed).take(35)
    }

    /** Discover More: fresh tracks in [genre] outside the user's already-heard top history. */
    suspend fun discoverMore(genre: String): List<GeneratedTrack> = coroutineScope {
        val targetTag = GenreNormalizer.primaryGenre(genre)
        val pool = mutableListOf<GeneratedTrack>()
        val profile = try { tasteProfileProvider.get() } catch (_: Exception) { null }

        val page1Deferred = async(Dispatchers.IO) {
            try {
                val d = call(mapOf("method" to "tag.gettoptracks", "tag" to targetTag, "limit" to "50", "page" to "1"))
                GenerateJson.normalise(d["tracks"]?.jsonObject?.get("track"))
            } catch (e: Exception) {
                Log.d(TAG, "discoverMore page 1 tag.gettoptracks miss", e)
                emptyList()
            }
        }

        val page2Deferred = async(Dispatchers.IO) {
            try {
                val randomPage = (2..5).random().toString()
                val d = call(mapOf("method" to "tag.gettoptracks", "tag" to targetTag, "limit" to "50", "page" to randomPage))
                GenerateJson.normalise(d["tracks"]?.jsonObject?.get("track"))
            } catch (e: Exception) {
                Log.d(TAG, "discoverMore page 2 tag.gettoptracks miss", e)
                emptyList()
            }
        }

        val tagArtistsDeferred = async(Dispatchers.IO) {
            try {
                val d = call(mapOf("method" to "tag.gettopartists", "tag" to targetTag, "limit" to "25"))
                GenerateJson.namesOf(d["topartists"]?.jsonObject?.get("artist"))
            } catch (e: Exception) {
                Log.d(TAG, "discoverMore tag.gettopartists miss", e)
                emptyList()
            }
        }

        val page1Tracks = page1Deferred.await()
        val page2Tracks = page2Deferred.await()
        val tagArtists = tagArtistsDeferred.await()

        pool += page1Tracks
        pool += page2Tracks

        val profileArtists = (profile?.topArtistsRaw.orEmpty() +
            profile?.ytMusicFeedRaw.orEmpty().map { it.artist })
            .filter(String::isNotBlank)
            .distinctBy { it.trim().lowercase() }
        val tagArtistsLower = tagArtists.map { it.trim().lowercase() }.toSet()
        val matchingProfileArtists = profileArtists.filter { it.trim().lowercase() in tagArtistsLower }

        val artistSeeds = (matchingProfileArtists.shuffled().take(3) + tagArtists.shuffled().take(6))
            .distinct()
            .take(6)

        val artistTopTracks = artistSeeds.map { artistName ->
            async(Dispatchers.IO) {
                try {
                    val page = (1..3).random()
                    val d = call(mapOf("method" to "artist.gettoptracks", "artist" to artistName, "limit" to "6", "page" to page.toString()))
                    GenerateJson.normalise(d["toptracks"]?.jsonObject?.get("track"))
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten()
        pool += artistTopTracks

        val heardKeys = (profile?.topTracksRaw ?: emptyList()).map { it.key }.toSet()
        val deduped = generateRepository.deduplicate(pool)
        val filtered = deduped.filterNot { it.key in heardKeys }
        val finalPool = filtered.ifEmpty { deduped }

        val affinity = profile?.artistAffinity.orEmpty()
        val ytFeedKeys = profile?.ytMusicFeedRaw?.map { it.key }?.toSet().orEmpty()
        val personalized = finalPool
            .groupBy { track ->
                val artist = track.artist.trim().lowercase()
                ((affinity[artist] ?: 0.0) * 20.0).toInt() +
                    if (track.key in ytFeedKeys) 10 else 0
            }
            .entries
            .sortedByDescending { it.key }
            .flatMap { it.value.shuffled() }

        val allowed = generateRepository.filterRecommendationExclusions(personalized)
        generateRepository.filterPlayable(allowed).take(35)
    }

    /** Personalized single-genre playlist scored by taste-profile signals. */
    suspend fun explorePersonalizedGenre(genre: String, sourceBoostArtists: Set<String> = emptySet()): List<GeneratedTrack> = coroutineScope {
        val targetTag = GenreNormalizer.primaryGenre(genre)
        val profile = try { tasteProfileProvider.get() } catch (_: Exception) { null }
        val pool = mutableListOf<GeneratedTrack>()

        try {
            val page = (1..6).random()
            val d = call(mapOf("method" to "tag.gettoptracks", "tag" to targetTag, "limit" to "50", "page" to page.toString()))
            pool += GenerateJson.normalise(d["tracks"]?.jsonObject?.get("track"))
        } catch (e: Exception) {
            Log.d(TAG, "explorePersonalizedGenre tag.gettoptracks miss", e)
        }

        val knownArtists = (profile?.topArtistsRaw.orEmpty() +
            profile?.ytMusicFeedRaw.orEmpty().map { it.artist })
            .filter(String::isNotBlank)
            .distinctBy { it.trim().lowercase() }
            .shuffled()
            .take(5)
        val similarArtists = knownArtists.map { artistName ->
            async(Dispatchers.IO) {
                try {
                    val sim = call(mapOf("method" to "artist.getsimilar", "artist" to artistName, "limit" to "10"))
                    GenerateJson.namesOf(sim["similarartists"]?.jsonObject?.get("artist"))
                        .shuffled().take(2)
                } catch (e: Exception) {
                    Log.d(TAG, "explorePersonalizedGenre artist.getsimilar miss", e)
                    emptyList()
                }
            }
        }.awaitAll().flatten().distinctBy { it.trim().lowercase() }.take(10)
        pool += similarArtists.map { artistName ->
            async(Dispatchers.IO) {
                try {
                    val d = call(mapOf("method" to "artist.gettoptracks", "artist" to artistName, "limit" to "6"))
                    GenerateJson.normalise(d["toptracks"]?.jsonObject?.get("track"))
                } catch (e: Exception) {
                    Log.d(TAG, "explorePersonalizedGenre similar-artist toptracks miss", e)
                    emptyList()
                }
            }
        }.awaitAll().flatten()

        val deduped = generateRepository.deduplicate(pool)
        val normalizedSourceBoostArtists = sourceBoostArtists.map { it.trim().lowercase() }.toSet()

        val scored = deduped.map { track ->
            val artistKey = track.artist.trim().lowercase()
            var score = 0
            score += ((profile?.artistAffinity?.get(artistKey) ?: 0.0) * 18.0).toInt()
            if (profile?.topArtistNames?.contains(artistKey) == true) score += 4
            if (profile?.recentArtists?.contains(artistKey) == true) score += 3
            if (profile?.ytMusicFeedRaw?.any { it.key == track.key } == true) score += 8
            if (artistKey in normalizedSourceBoostArtists) score += 4
            track to score
        }

        val sorted = scored.sortedByDescending { it.second }
            .let { list ->
                list.groupBy { it.second }.entries.sortedByDescending { it.key }.flatMap { it.value.shuffled() }
            }
            .map { it.first }

        val allowed = generateRepository.filterRecommendationExclusions(sorted)
        generateRepository.filterPlayable(allowed).take(30)
    }
}
