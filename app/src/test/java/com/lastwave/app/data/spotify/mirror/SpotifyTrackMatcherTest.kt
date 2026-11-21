package com.lastwave.app.data.spotify.mirror

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.playback.MediaType
import com.lastwave.app.playback.PlayableTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SpotifyTrackMatcherTest {

    @Test
    fun normalizeQuery_stripsYouTubeNoiseAndChannelSuffixes() {
        val query = SpotifyTrackNormalizer.normalizeQuery(
            rawTitle = "Daft Punk - Get Lucky (Official Audio) [HD] (feat. Pharrell Williams)",
            rawArtist = "Daft Punk - Topic",
        )

        assertThat(query.cleanTitle).isEqualTo("Get Lucky")
        assertThat(query.normalizedTitle).isEqualTo("get lucky")
        assertThat(query.primaryArtist).isEqualTo("Daft Punk")
        assertThat(query.allArtists).containsAtLeast("Daft Punk", "Pharrell Williams")
    }

    @Test
    fun normalizeQuery_stripsVevoAndDiacriticsAndRemasterSuffix() {
        val query = SpotifyTrackNormalizer.normalizeQuery(
            rawTitle = "Déjà Vu - 2011 Remaster (Official Music Video) | 4K",
            rawArtist = "BeyoncéVEVO",
        )

        assertThat(query.normalizedTitle).isEqualTo("deja vu")
        assertThat(SpotifyTrackNormalizer.normalizeComparable(query.primaryArtist)).isEqualTo("beyonce")
    }

    @Test
    fun extractSpotifyUriAndIsrc_handlesMultipleFormats() {
        val validId = "4cOdK2wGLETKBW3PvgPWqT"
        assertThat(SpotifyTrackNormalizer.extractSpotifyUri("spotify:track:$validId"))
            .isEqualTo("spotify:track:$validId")
        assertThat(SpotifyTrackNormalizer.extractSpotifyUri("https://open.spotify.com/track/$validId?si=abc"))
            .isEqualTo("spotify:track:$validId")
        assertThat(SpotifyTrackNormalizer.extractSpotifyUri("https://open.spotify.com/intl-es/track/$validId"))
            .isEqualTo("spotify:track:$validId")
        assertThat(SpotifyTrackNormalizer.extractSpotifyUri("not-a-spotify-uri")).isNull()

        assertThat(SpotifyTrackNormalizer.extractIsrc("isrc:USUM71703861")).isEqualTo("USUM71703861")
        assertThat(SpotifyTrackNormalizer.extractIsrc("US-UM7-17-03861")).isEqualTo("USUM71703861")
    }

    @Test
    fun scoreCandidate_prefersMatchingDurationOverExtendedVersion() {
        val query = SpotifyTrackNormalizer.normalizeQuery(
            rawTitle = "Blinding Lights (Official Audio)",
            rawArtist = "The Weeknd",
        )
        val studio = SpotifyCatalogCandidate(
            id = "0VjIjW4GlUZAMYd2vXMi3b",
            uri = "spotify:track:0VjIjW4GlUZAMYd2vXMi3b",
            title = "Blinding Lights",
            artists = listOf("The Weeknd"),
            durationMs = 200_040L,
        )
        val extendedMix = SpotifyCatalogCandidate(
            id = "1VjIjW4GlUZAMYd2vXMi3c",
            uri = "spotify:track:1VjIjW4GlUZAMYd2vXMi3c",
            title = "Blinding Lights",
            artists = listOf("The Weeknd"),
            durationMs = 390_000L,
        )

        val studioScore = SpotifyTrackNormalizer.scoreCandidate(query, 202_000L, studio)
        val extendedScore = SpotifyTrackNormalizer.scoreCandidate(query, 202_000L, extendedMix)

        assertThat(studioScore).isGreaterThan(0.95f)
        assertThat(extendedScore).isLessThan(0.72f)
    }

    @Test
    fun scoreCandidate_penalizesLiveOrSpedUpMismatchAndWrongArtist() {
        val spedUpQuery = SpotifyTrackNormalizer.normalizeQuery(
            rawTitle = "Starboy (Sped Up)",
            rawArtist = "The Weeknd",
        )
        val studioCandidate = SpotifyCatalogCandidate(
            id = "7MXVkk9YMctZqd1Srtv4MB",
            uri = "spotify:track:7MXVkk9YMctZqd1Srtv4MB",
            title = "Starboy",
            artists = listOf("The Weeknd", "Daft Punk"),
            durationMs = 230_453L,
        )
        val spedUpScore = SpotifyTrackNormalizer.scoreCandidate(spedUpQuery, 185_000L, studioCandidate)
        assertThat(spedUpScore).isLessThan(SpotifyTrackMatcher.DEFAULT_MIN_CONFIDENCE)

        val wrongArtistQuery = SpotifyTrackNormalizer.normalizeQuery(
            rawTitle = "Hello",
            rawArtist = "Lionel Richie",
        )
        val wrongArtistCandidate = SpotifyCatalogCandidate(
            id = "4sPmO7WMQUAf45kwMOtONw",
            uri = "spotify:track:4sPmO7WMQUAf45kwMOtONw",
            title = "Hello",
            artists = listOf("Adele"),
            durationMs = 295_000L,
        )
        val wrongArtistScore = SpotifyTrackNormalizer.scoreCandidate(wrongArtistQuery, 248_000L, wrongArtistCandidate)
        assertThat(wrongArtistScore).isLessThan(0.60f)
    }

    @Test
    fun resolveTrack_cachesResultSoRepeatsRequireZeroApiCalls() = runTest {
        val dao = InMemoryTrackMatchDao()
        val client = FakeCatalogClient(
            searchResults = listOf(
                SpotifyCatalogCandidate(
                    id = "3n3Ppam7vgaVa1iaRUc9Lp",
                    uri = "spotify:track:3n3Ppam7vgaVa1iaRUc9Lp",
                    title = "Mr. Brightside",
                    artists = listOf("The Killers"),
                    durationMs = 222_973L,
                    isrc = "USIR20400274",
                ),
            ),
        )
        val matcher = SpotifyTrackMatcher(dao, client)
        val track = PlayableTrack(
            title = "Mr. Brightside (Official Music Video)",
            artist = "The Killers - Topic",
            durationMs = 224_000L,
        )

        val first = matcher.resolveTrack(track, nowMs = 1_000L)
        assertThat(first).isInstanceOf(SpotifyMatchResult.Matched::class.java)
        val firstMatched = first as SpotifyMatchResult.Matched
        assertThat(firstMatched.fromCache).isFalse()
        assertThat(firstMatched.entity.spotifyUri).isEqualTo("spotify:track:3n3Ppam7vgaVa1iaRUc9Lp")
        assertThat(client.searchCallCount).isEqualTo(1)

        // Playing the same song from a local FLAC file shares the canonical key and uses 0 API calls.
        val localFlacCopy = PlayableTrack(
            title = "Mr. Brightside",
            artist = "The Killers",
            playbackUrl = "file:///storage/emulated/0/Music/mr_brightside.flac",
            durationMs = 223_000L,
        )
        val second = matcher.resolveTrack(localFlacCopy, nowMs = 2_000L)
        assertThat(second).isInstanceOf(SpotifyMatchResult.Matched::class.java)
        assertThat((second as SpotifyMatchResult.Matched).fromCache).isTrue()
        assertThat(client.searchCallCount).isEqualTo(1)
    }

    @Test
    fun resolveTrack_resolvesViaDirectUriAndIsrcBeforeFuzzySearch() = runTest {
        val dao = InMemoryTrackMatchDao()
        val client = FakeCatalogClient(
            isrcResults = mapOf(
                "USUM71703861" to listOf(
                    SpotifyCatalogCandidate(
                        id = "0VjIjW4GlUZAMYd2vXMi3b",
                        uri = "spotify:track:0VjIjW4GlUZAMYd2vXMi3b",
                        title = "Blinding Lights",
                        artists = listOf("The Weeknd"),
                        durationMs = 200_040L,
                        isrc = "USUM71703861",
                    ),
                ),
            ),
        )
        val matcher = SpotifyTrackMatcher(dao, client)

        val uriTrack = PlayableTrack(
            title = "Some Track",
            artist = "Some Artist",
            internalTrackId = "spotify:track:4cOdK2wGLETKBW3PvgPWqT",
        )
        val uriResult = matcher.resolveTrack(uriTrack, nowMs = 1_000L)
        assertThat(uriResult).isInstanceOf(SpotifyMatchResult.Matched::class.java)
        assertThat(uriResult.entity.confidence).isEqualTo(1.0f)
        assertThat(client.searchCallCount).isEqualTo(0)
        assertThat(client.isrcCallCount).isEqualTo(0)

        val isrcTrack = PlayableTrack(
            title = "Blinding Lights (FLAC Rip)",
            artist = "The Weeknd",
            internalTrackId = "isrc:USUM71703861",
            durationMs = 200_500L,
        )
        val isrcResult = matcher.resolveTrack(isrcTrack, nowMs = 2_000L)
        assertThat(isrcResult).isInstanceOf(SpotifyMatchResult.Matched::class.java)
        assertThat(isrcResult.entity.spotifyUri).isEqualTo("spotify:track:0VjIjW4GlUZAMYd2vXMi3b")
        assertThat(client.isrcCallCount).isEqualTo(1)
        assertThat(client.searchCallCount).isEqualTo(0)
    }

    @Test
    fun resolveTrack_marksNonCatalogAndPodcastTracksAsLocalOnly() = runTest {
        val dao = InMemoryTrackMatchDao()
        val client = FakeCatalogClient(searchResults = emptyList())
        val matcher = SpotifyTrackMatcher(dao, client)

        val unreleasedTrack = PlayableTrack(
            title = "Unreleased SoundCloud Mashup 2026",
            artist = "Bedroom Producer",
            durationMs = 190_000L,
        )
        val result = matcher.resolveTrack(unreleasedTrack, nowMs = 1_000L)
        assertThat(result).isInstanceOf(SpotifyMatchResult.LocalOnly::class.java)
        assertThat(result.entity.isCatalogMatch).isFalse()
        assertThat(result.entity.spotifyUri).isNull()
        assertThat(client.searchCallCount).isEqualTo(1)

        // Repeat within negative-cache TTL stays LocalOnly without extra API calls.
        val cachedResult = matcher.resolveTrack(unreleasedTrack, nowMs = 60_000L)
        assertThat(cachedResult).isInstanceOf(SpotifyMatchResult.LocalOnly::class.java)
        assertThat((cachedResult as SpotifyMatchResult.LocalOnly).fromCache).isTrue()
        assertThat(client.searchCallCount).isEqualTo(1)

        val podcast = PlayableTrack(
            title = "Episode 42",
            artist = "Tech Podcast",
            mediaType = MediaType.PODCAST_EPISODE,
        )
        val podcastResult = matcher.resolveTrack(podcast, nowMs = 70_000L)
        assertThat(podcastResult).isInstanceOf(SpotifyMatchResult.LocalOnly::class.java)
        assertThat(client.searchCallCount).isEqualTo(1)
    }
}

internal class InMemoryTrackMatchDao : SpotifyTrackMatchDao {
    private val store = LinkedHashMap<String, SpotifyTrackMatchEntity>()
    private val flow = MutableStateFlow<List<SpotifyTrackMatchEntity>>(emptyList())

    override suspend fun findByTrackKey(trackKey: String): SpotifyTrackMatchEntity? = store[trackKey]

    override suspend fun findByIsrc(isrc: String): SpotifyTrackMatchEntity? =
        store.values.firstOrNull { it.isrc == isrc && it.isCatalogMatch }

    override suspend fun upsert(entity: SpotifyTrackMatchEntity) {
        store[entity.trackKey] = entity
        flow.value = store.values.sortedByDescending { it.resolvedAtMs }
    }

    override suspend fun deleteByTrackKey(trackKey: String) {
        store.remove(trackKey)
        flow.value = store.values.sortedByDescending { it.resolvedAtMs }
    }

    override suspend fun clearAll() {
        store.clear()
        flow.value = emptyList()
    }

    override fun observeRecentMatches(limit: Int): Flow<List<SpotifyTrackMatchEntity>> =
        flow.map { it.take(limit) }
}

internal class FakeCatalogClient(
    var searchResults: List<SpotifyCatalogCandidate> = emptyList(),
    var isrcResults: Map<String, List<SpotifyCatalogCandidate>> = emptyMap(),
    var tracksById: Map<String, SpotifyCatalogCandidate> = emptyMap(),
) : SpotifyCatalogClient {
    var searchCallCount = 0
    var isrcCallCount = 0

    override suspend fun searchByIsrc(isrc: String): List<SpotifyCatalogCandidate> {
        isrcCallCount++
        return isrcResults[isrc].orEmpty()
    }

    override suspend fun searchTracks(cleanTitle: String, cleanArtist: String): List<SpotifyCatalogCandidate> {
        searchCallCount++
        return searchResults
    }

    override suspend fun fetchTrackById(trackId: String): SpotifyCatalogCandidate? = tracksById[trackId]
}
