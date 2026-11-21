package com.lastwave.app.playback.automix

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.automix.analysis.AcousticSignature
import com.lastwave.app.playback.automix.analysis.BeatGridEstimate
import com.lastwave.app.playback.automix.analysis.PhraseBoundary
import com.lastwave.app.playback.automix.analysis.VocalActivityMask
import com.lastwave.app.playback.automix.analysis.VocalSegment
import com.lastwave.app.playback.automix.api.AutoMixSettings
import com.lastwave.app.playback.automix.api.AutoMixTier
import com.lastwave.app.playback.automix.api.Bpm
import com.lastwave.app.playback.automix.api.CamelotKey
import com.lastwave.app.playback.automix.api.CrossfadeCurveEvaluator
import com.lastwave.app.playback.automix.api.CrossfadeCurveType
import com.lastwave.app.playback.automix.api.DecibelLufs
import com.lastwave.app.playback.automix.api.TransitionDurationMode
import com.lastwave.app.playback.automix.api.TransitionStyle
import com.lastwave.app.playback.automix.data.TransitionOverrideEntity
import com.lastwave.app.playback.automix.engine.SmartBeatTransitionPlanner
import com.lastwave.app.playback.automix.engine.SpectralCrossoverAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SmartBeatTransitionPlannerTest {

    private fun track(id: String, durationMs: Long = 200_000L) = PlayableTrack(
        videoId = id,
        title = "Track $id",
        artist = "Artist $id",
        durationMs = durationMs,
    )

    private fun signature(
        id: String,
        bpm: Float = 120f,
        camelotKey: String = "8A",
        durationMs: Long = 200_000L,
        firstDownbeatMs: Long = 2_000L,
        silenceTailMs: Long = 4_000L,
        lufs: Float = -14.0f,
        introVocals: List<VocalSegment> = emptyList(),
        outroVocals: List<VocalSegment> = emptyList(),
    ): AcousticSignature {
        val beatIntervalMs = ((60.0 / bpm) * 1000.0).toLong()
        val barMs = beatIntervalMs * 4L
        val anchors = mutableListOf<Long>()
        var t = firstDownbeatMs
        while (t < durationMs) {
            anchors.add(t)
            t += barMs
        }
        return AcousticSignature(
            trackId = id,
            durationMs = durationMs,
            sourceHash = "test",
            camelotKey = CamelotKey(camelotKey),
            beatGrid = BeatGridEstimate(
                bpm = Bpm(bpm),
                beatIntervalMs = beatIntervalMs,
                firstDownbeatMs = firstDownbeatMs,
                downbeatAnchorsMs = anchors,
                phraseBoundary = PhraseBoundary(
                    introDropMs = firstDownbeatMs + barMs * 4L,
                    outroBreakdownMs = durationMs - barMs * 8L,
                    silenceTailMs = silenceTailMs,
                ),
            ),
            introVocalMask = VocalActivityMask(0L, 45_000L, introVocals),
            outroVocalMask = VocalActivityMask(durationMs - 45_000L, 45_000L, outroVocals),
            integratedLufs = DecibelLufs(lufs),
        )
    }

    @Test
    fun tempoBridge_clampsWithinPlusMinusSixPercentAndHandlesHalfTime() {
        val withinRange = SmartBeatTransitionPlanner.computeTempoBridge(120f, 126f)
        assertTrue(withinRange.isTempoMatched)
        assertTrue(withinRange.outgoingRatio in 0.94f..1.06f)
        assertTrue(withinRange.incomingRatio in 0.94f..1.06f)
        assertEquals(123f, withinRange.syncedBpm!!, 0.2f)

        // Half-time equivalence (70 BPM -> 142 BPM: 70*2 = 140 vs 142, ~1.4% delta)
        val halfTime = SmartBeatTransitionPlanner.computeTempoBridge(140f, 71f)
        assertTrue(halfTime.isTempoMatched)
        assertTrue(halfTime.outgoingRatio in 0.94f..1.06f)
        assertTrue(halfTime.incomingRatio in 0.94f..1.06f)

        // Beyond +-6% (100 BPM vs 130 BPM) must NOT stretch (prevents chipmunk artifacts)
        val tooFar = SmartBeatTransitionPlanner.computeTempoBridge(100f, 130f)
        assertFalse(tooFar.isTempoMatched)
        assertEquals(1.0f, tooFar.outgoingRatio, 0.0001f)
        assertEquals(1.0f, tooFar.incomingRatio, 0.0001f)
    }

    @Test
    fun planTransition_trimsDeadSilenceOutroAndIntroLeadInWithoutCuttingVocals() {
        val outTrack = track("out", 210_000L)
        val inTrack = track("in", 200_000L)
        val outSig = signature("out", bpm = 124f, camelotKey = "8A", durationMs = 210_000L, silenceTailMs = 6_000L)
        val inSig = signature("in", bpm = 126f, camelotKey = "9A", durationMs = 200_000L, firstDownbeatMs = 3_500L)

        val settings = AutoMixSettings(
            autoMixEnabled = true,
            tier = AutoMixTier.IMPROVED_SMART,
            durationMode = TransitionDurationMode.MANUAL,
            targetDurationSeconds = 12,
        )

        val plan = SmartBeatTransitionPlanner.planTransition(
            outgoingTrack = outTrack,
            outgoingSignature = outSig,
            incomingTrack = inTrack,
            incomingSignature = inSig,
            settings = settings,
        )

        assertFalse(plan.isBypass)
        assertEquals(6_000L, plan.trimmedOutgoingTailMs)
        assertEquals(3_500L, plan.cueInStartMs)
        // Cue-out + duration should complete right around effective end (210s - 6s = 204s)
        assertTrue(plan.cueOutStartMs + plan.durationMs <= 206_000L)
        assertEquals(TransitionStyle.HARMONIC_BEATMIX, plan.transitionStyle)
        assertTrue(plan.tempoRatioOutgoing in 0.94f..1.06f)
        assertTrue(plan.tempoRatioIncoming in 0.94f..1.06f)

        // If incoming vocals start at 1_000ms (before firstDownbeatMs 3_500ms), cue-in must not skip vocals!
        val earlyVocalInSig = signature(
            id = "in2",
            bpm = 126f,
            firstDownbeatMs = 3_500L,
            introVocals = listOf(VocalSegment(1_000L, 12_000L)),
        )
        val earlyVocalPlan = SmartBeatTransitionPlanner.planTransition(
            outgoingTrack = outTrack,
            outgoingSignature = outSig,
            incomingTrack = inTrack,
            incomingSignature = earlyVocalInSig,
            settings = settings,
        )
        assertTrue(earlyVocalPlan.cueInStartMs <= 1_000L)
    }

    @Test
    fun planTransition_matchesLufsContourBetweenLoudAndQuietTracks() {
        val (outTrim, inTrim) = SmartBeatTransitionPlanner.computeLufsTrimGains(
            outgoingLufs = DecibelLufs(-9.0f),
            incomingLufs = DecibelLufs(-16.0f),
        )
        // Louder outgoing track is attenuated; quieter incoming track is boosted.
        assertTrue(outTrim < 1.0f)
        assertTrue(inTrim > 1.0f)

        val (flatOut, flatIn) = SmartBeatTransitionPlanner.computeLufsTrimGains(
            outgoingLufs = DecibelLufs(-14.0f),
            incomingLufs = DecibelLufs(-14.2f),
        )
        assertEquals(1.0f, flatOut, 0.001f)
        assertEquals(1.0f, flatIn, 0.001f)
    }

    @Test
    fun planTransition_mitigatesVocalCollision() {
        val duration = 200_000L
        val outSig = signature(
            id = "v1",
            durationMs = duration,
            silenceTailMs = 0L,
            outroVocals = listOf(VocalSegment(duration - 16_000L, duration - 1_000L)),
        )
        val inSig = signature(
            id = "v2",
            durationMs = duration,
            firstDownbeatMs = 500L,
            introVocals = listOf(VocalSegment(0L, 12_000L)),
        )
        val plan = SmartBeatTransitionPlanner.planTransition(
            outgoingTrack = track("v1", duration),
            outgoingSignature = outSig,
            incomingTrack = track("v2", duration),
            incomingSignature = inSig,
            settings = AutoMixSettings(autoMixEnabled = true, tier = AutoMixTier.IMPROVED_SMART),
        )

        assertTrue(plan.requiresPhraseLoop || plan.eqMidSwap > 0.5f || plan.durationMs <= 10_000L)
    }

    @Test
    fun planTransition_appliesManualOverrideParametersAccurately() {
        val override = TransitionOverrideEntity(
            fromTrackKey = "vid:a",
            toTrackKey = "vid:b",
            bpmMatchEnabled = true,
            targetTempoRatio = 1.04f,
            transitionDurationSec = 24,
            curveType = CrossfadeCurveType.S_CURVE.name,
            eqLowSwap = 0.80f,
            eqMidSwap = 0.40f,
            eqHighSwap = 0.65f,
            cueOutMs = 150_000L,
            cueInMs = 4_000L,
            filterSweepEnabled = true,
        )

        val plan = SmartBeatTransitionPlanner.planTransition(
            outgoingTrack = track("a", 210_000L),
            outgoingSignature = signature("a", bpm = 120f, camelotKey = "8A"),
            incomingTrack = track("b", 200_000L),
            incomingSignature = signature("b", bpm = 124f, camelotKey = "9A"),
            settings = AutoMixSettings(autoMixEnabled = true, tier = AutoMixTier.IMPROVED_SMART),
            manualOverride = override,
        )

        assertTrue(plan.isManualOverride)
        assertEquals(24_000L, plan.durationMs)
        assertEquals(CrossfadeCurveType.S_CURVE, plan.curveType)
        assertEquals(150_000L, plan.cueOutStartMs)
        assertEquals(4_000L, plan.cueInStartMs)
        assertEquals(1.04f, plan.tempoRatioIncoming, 0.001f)
        assertEquals(0.80f, plan.eqLowSwap, 0.001f)
        assertEquals(0.40f, plan.eqMidSwap, 0.001f)
        assertEquals(0.65f, plan.eqHighSwap, 0.001f)
        assertTrue(plan.filterSweepEnabled)
    }

    @Test
    fun crossfadeCurveEvaluator_allCurvesRespectEndpointsAndHeadroom() {
        CrossfadeCurveType.entries.forEach { curve ->
            val start = CrossfadeCurveEvaluator.evaluate(0f, curve)
            val mid = CrossfadeCurveEvaluator.evaluate(0.5f, curve)
            val end = CrossfadeCurveEvaluator.evaluate(1f, curve)

            assertEquals("Start outgoing for $curve", 1.0f, start.outgoingGain, 0.01f)
            assertEquals("Start incoming for $curve", 0.0f, start.incomingGain, 0.01f)
            assertEquals("End outgoing for $curve", 0.0f, end.outgoingGain, 0.01f)
            assertEquals("End incoming for $curve", 1.0f, end.incomingGain, 0.01f)
            assertTrue("Mid outgoing bounded for $curve", mid.outgoingGain in 0f..1f)
            assertTrue("Mid incoming bounded for $curve", mid.incomingGain in 0f..1f)
        }
    }

    @Test
    fun spectralCrossoverAudioProcessor_appliesManualThreeBandEqWithoutNativeLibrary() {
        val processor = SpectralCrossoverAudioProcessor()
        processor.isEnabled = true
        processor.isIncomingDeck = false
        val format = processor.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT))
        assertEquals(C.ENCODING_PCM_FLOAT, format.encoding)

        processor.applyManualDjParams(
            lowSwap = 0.85f,
            midSwap = 0.50f,
            highSwap = 0.20f,
            tempoRatio = 1.03f,
            filterSweepEnabled = true,
            lufsTrim = 0.92f,
        )
        processor.blendProgress = 0.5f

        val (low, mid, high) = processor.computeBandGains(0.5f)
        // High swap value swaps outgoing low-end out earlier than high-end.
        assertTrue(low < high)
        assertTrue(mid in 0f..1.25f)

        val frames = 64
        val input = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.nativeOrder())
        repeat(frames * 2) { input.putFloat(0.5f) }
        input.flip()

        processor.queueInput(input)
        val out = processor.getOutput()
        assertEquals(frames * 8, out.remaining())
    }
}
