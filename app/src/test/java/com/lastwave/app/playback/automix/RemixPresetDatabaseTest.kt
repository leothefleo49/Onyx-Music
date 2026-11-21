package com.lastwave.app.playback.automix

import androidx.room.Room
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.automix.api.CrossfadeCurveType
import com.lastwave.app.playback.automix.data.RemixPresetDatabase
import com.lastwave.app.playback.automix.data.RemixPresetRepository
import com.lastwave.app.playback.automix.data.TransitionOverrideEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemixPresetDatabaseTest {

    private lateinit var database: RemixPresetDatabase
    private lateinit var repository: RemixPresetRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, RemixPresetDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RemixPresetRepository(database.transitionOverrideDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun saveAndReadOverride_normalizesBoundsAndResolvesCanonicalPlayableTrack() = runTest {
        val from = PlayableTrack(title = "Midnight City", artist = "M83", videoId = "dX3k_QDnzHE")
        val to = PlayableTrack(title = "Nightcall", artist = "Kavinsky", videoId = "MV_3Dpw-BRY")

        val raw = TransitionOverrideEntity(
            fromTrackKey = RemixPresetRepository.canonicalTrackKey(from),
            toTrackKey = RemixPresetRepository.canonicalTrackKey(to),
            bpmMatchEnabled = true,
            targetTempoRatio = 1.99f, // should clamp to MAX_MANUAL_TEMPO_RATIO (1.15f)
            transitionDurationSec = 99, // should clamp to MAX_DURATION_SEC (32)
            curveType = CrossfadeCurveType.EXPONENTIAL.name,
            eqLowSwap = 1.5f, // should clamp to 1.0f
            eqMidSwap = -0.3f, // should clamp to 0.0f
            eqHighSwap = 0.7f,
            cueOutMs = 145_000L,
            cueInMs = 3_200L,
            filterSweepEnabled = false,
        )

        repository.saveOverride(raw)

        val fetched = repository.getOverride(from, to)
        assertNotNull(fetched)
        assertEquals(TransitionOverrideEntity.MAX_MANUAL_TEMPO_RATIO, fetched!!.targetTempoRatio, 0.0001f)
        assertEquals(TransitionOverrideEntity.MAX_DURATION_SEC, fetched.transitionDurationSec)
        assertEquals(CrossfadeCurveType.EXPONENTIAL.name, fetched.curveType)
        assertEquals(1.0f, fetched.eqLowSwap, 0.0001f)
        assertEquals(0.0f, fetched.eqMidSwap, 0.0001f)
        assertEquals(0.7f, fetched.eqHighSwap, 0.0001f)
        assertEquals(145_000L, fetched.cueOutMs)
        assertEquals(3_200L, fetched.cueInMs)
        assertEquals(false, fetched.filterSweepEnabled)
    }

    @Test
    fun savePlaylistRecipes_persistsAndDeletesByPlaylistId() = runTest {
        val playlistId = 42_001L
        val recipes = listOf(
            TransitionOverrideEntity(
                fromTrackKey = "vid:track000001",
                toTrackKey = "vid:track000002",
                transitionDurationSec = 16,
                curveType = CrossfadeCurveType.EQUAL_POWER.name,
                compatibilityScore = 0.94f,
            ),
            TransitionOverrideEntity(
                fromTrackKey = "vid:track000002",
                toTrackKey = "vid:track000003",
                transitionDurationSec = 12,
                curveType = CrossfadeCurveType.CUT_IN.name,
                compatibilityScore = 0.89f,
            ),
        )

        repository.savePlaylistRecipes(playlistId, recipes)

        val stored = repository.getOverridesForPlaylist(playlistId)
        assertEquals(2, stored.size)
        assertEquals(playlistId, stored[0].playlistId)
        assertEquals(0.94f, stored[0].compatibilityScore!!, 0.001f)
        assertEquals(CrossfadeCurveType.CUT_IN.name, stored[1].curveType)

        val observed = repository.observeOverridesForPlaylist(playlistId).first()
        assertEquals(2, observed.size)

        repository.deletePlaylistRecipes(playlistId)
        assertEquals(0, repository.getOverridesForPlaylist(playlistId).size)
        assertNull(repository.getOverrideByKeys("vid:track000001", "vid:track000002"))
    }
}
