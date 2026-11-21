package com.lastwave.app.playback.automix

import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.playback.automix.analysis.AcousticSignature
import com.lastwave.app.playback.automix.analysis.BeatGridEstimate
import com.lastwave.app.playback.automix.analysis.PhraseBoundary
import com.lastwave.app.playback.automix.analysis.VocalActivityMask
import com.lastwave.app.playback.automix.analysis.VocalSegment
import com.lastwave.app.playback.automix.api.Bpm
import com.lastwave.app.playback.automix.api.CamelotKey
import com.lastwave.app.playback.automix.api.DecibelLufs
import com.lastwave.app.playback.automix.curator.CandidateOrigin
import com.lastwave.app.playback.automix.curator.CuratedCandidate
import com.lastwave.app.playback.automix.curator.SourcePolicy
import com.lastwave.app.playback.automix.curator.TransitionCompatibilityScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionCompatibilityScorerTest {

    private fun candidate(
        id: String,
        bpm: Float,
        key: String,
        lufs: Float = -14.0f,
        origin: CandidateOrigin = CandidateOrigin.LIKED,
        artist: String = "Artist $id",
        introVocals: List<VocalSegment> = emptyList(),
        outroVocals: List<VocalSegment> = emptyList(),
    ): CuratedCandidate {
        val durationMs = 200_000L
        val beatMs = ((60.0 / bpm) * 1000.0).toLong()
        val sig = AcousticSignature(
            trackId = id,
            durationMs = durationMs,
            sourceHash = "h",
            camelotKey = CamelotKey(key),
            beatGrid = BeatGridEstimate(
                bpm = Bpm(bpm),
                beatIntervalMs = beatMs,
                firstDownbeatMs = 500L,
                downbeatAnchorsMs = listOf(500L, 2500L, 4500L),
                phraseBoundary = PhraseBoundary(8_000L, durationMs - 16_000L, 2_000L),
            ),
            introVocalMask = VocalActivityMask(0L, 45_000L, introVocals),
            outroVocalMask = VocalActivityMask(durationMs - 45_000L, 45_000L, outroVocals),
            integratedLufs = DecibelLufs(lufs),
        )
        return CuratedCandidate(
            track = GeneratedTrack(
                name = "Song $id",
                artist = artist,
                url = "https://music.youtube.com/watch?v=${id.padEnd(11, '0').take(11)}",
                durationMs = durationMs,
            ),
            trackKey = "vid:$id",
            signature = sig,
            origin = origin,
            playCount = if (origin == CandidateOrigin.MOST_LISTENED) 15 else 2,
        )
    }

    @Test
    fun scoreTransition_rewardsHarmonicAndBpmAlignmentOverClash() {
        val base = candidate("A", bpm = 124f, key = "8A", lufs = -13.0f)
        val perfectMatch = candidate("B", bpm = 125f, key = "9A", lufs = -13.5f)
        val halfTimeMatch = candidate("C", bpm = 62f, key = "8B", lufs = -13.0f)
        val clash = candidate(
            id = "D",
            bpm = 158f,
            key = "2B",
            lufs = -22.0f,
            introVocals = listOf(VocalSegment(0L, 15_000L)),
        )
        val baseWithOutroVocals = candidate(
            id = "A2",
            bpm = 124f,
            key = "8A",
            lufs = -13.0f,
            outroVocals = listOf(VocalSegment(185_000L, 200_000L)),
        )

        val goodEdge = TransitionCompatibilityScorer.scoreTransition(base, perfectMatch)
        val halfTimeEdge = TransitionCompatibilityScorer.scoreTransition(base, halfTimeMatch)
        val badEdge = TransitionCompatibilityScorer.scoreTransition(baseWithOutroVocals, clash)

        assertTrue("Perfect match should exceed 0.85, was ${goodEdge.totalScore}", goodEdge.totalScore >= 0.85f)
        assertTrue("Half-time harmonic match should exceed 0.78, was ${halfTimeEdge.totalScore}", halfTimeEdge.totalScore >= 0.78f)
        assertTrue("Clash should score much lower than good match", badEdge.totalScore < goodEdge.totalScore - 0.35f)
    }

    @Test
    fun scoreTransition_respectsSourcePolicies() {
        val from = candidate("A", bpm = 120f, key = "8A", origin = CandidateOrigin.LIKED)
        val likedTo = candidate("B", bpm = 120f, key = "8A", origin = CandidateOrigin.LIKED)
        val discoverTo = candidate("C", bpm = 120f, key = "8A", origin = CandidateOrigin.DISCOVER)

        val leanLiked = TransitionCompatibilityScorer.scoreTransition(from, likedTo, SourcePolicy.LEAN_LIKED_LISTENED)
        val leanDiscover = TransitionCompatibilityScorer.scoreTransition(from, discoverTo, SourcePolicy.LEAN_LIKED_LISTENED)
        assertTrue(leanLiked.totalScore > leanDiscover.totalScore)

        val neutralLiked = TransitionCompatibilityScorer.scoreTransition(from, likedTo, SourcePolicy.NEUTRAL_DISCOVER)
        val neutralDiscover = TransitionCompatibilityScorer.scoreTransition(from, discoverTo, SourcePolicy.NEUTRAL_DISCOVER)
        assertTrue(neutralDiscover.totalScore > neutralLiked.totalScore)
    }

    @Test
    fun improveWithTwoOpt_strictlyImprovesScrambledHamiltonianPath() {
        // Construct a 6-node chain where ideal order is 0 -> 1 -> 2 -> 3 -> 4 -> 5 (adjacent edges = 0.98, others = 0.20)
        val n = 6
        val matrix = Array(n) { i ->
            FloatArray(n) { j ->
                when {
                    i == j -> -1f
                    kotlin.math.abs(i - j) == 1 -> 0.98f
                    else -> 0.20f
                }
            }
        }

        val scrambled = intArrayOf(0, 3, 2, 1, 4, 5)
        val initialScore = TransitionCompatibilityScorer.pathScore(scrambled, matrix)
        val improved = TransitionCompatibilityScorer.improveWithTwoOpt(scrambled, matrix)
        val finalScore = TransitionCompatibilityScorer.pathScore(improved, matrix)

        assertTrue("2-opt should improve score ($initialScore -> $finalScore)", finalScore > initialScore + 1.0f)
        assertEquals(5 * 0.98f, finalScore, 0.01f)
    }

    @Test
    fun sequenceOptimalPath_groupsCompatibleTracksAndGeneratesEdgeRecipes() {
        // Pool contains two incompatible BPM/key clusters:
        // Cluster 1 (124 BPM, Camelot 8A/9A/10A/8B) and Cluster 2 (86 BPM, Camelot 2A/3A)
        val pool = listOf(
            candidate("c1_1", bpm = 124f, key = "8A", lufs = -13f),
            candidate("c2_1", bpm = 86f, key = "2A", lufs = -17f),
            candidate("c1_2", bpm = 125f, key = "9A", lufs = -13f),
            candidate("c2_2", bpm = 87f, key = "3A", lufs = -17f),
            candidate("c1_3", bpm = 126f, key = "10A", lufs = -13f),
            candidate("c1_4", bpm = 124f, key = "8B", lufs = -13f),
        )

        val result = TransitionCompatibilityScorer.sequenceOptimalPath(
            candidates = pool,
            targetCount = 4,
            sourcePolicy = SourcePolicy.LEAN_LIKED_LISTENED,
        )

        assertEquals(4, result.orderedCandidates.size)
        assertEquals(3, result.edges.size)
        assertEquals(3, result.edgeRecipes.size)
        // Choosing the 4-track harmonious 124-126 BPM cluster yields very high average transition score
        assertTrue("Expected high average transition score, got ${result.averageScore}", result.averageScore >= 0.85f)
        assertTrue(result.orderedCandidates.all { it.trackKey.startsWith("vid:c1_") })

        // Verify every generated recipe aligns to the exact sequenced edge keys
        for (i in result.edgeRecipes.indices) {
            assertEquals(result.orderedCandidates[i].trackKey, result.edgeRecipes[i].fromTrackKey)
            assertEquals(result.orderedCandidates[i + 1].trackKey, result.edgeRecipes[i].toTrackKey)
            assertTrue(result.edgeRecipes[i].transitionDurationSec in 4..32)
        }
    }
}
