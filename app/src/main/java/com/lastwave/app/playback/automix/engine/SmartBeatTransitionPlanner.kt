package com.lastwave.app.playback.automix.engine

import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.automix.analysis.AcousticSignature
import com.lastwave.app.playback.automix.analysis.AutoMixCamelotClassifier
import com.lastwave.app.playback.automix.analysis.DownbeatGridEstimator
import com.lastwave.app.playback.automix.analysis.HarmonicCompatibilityTier
import com.lastwave.app.playback.automix.analysis.VocalPresenceGateDetector
import com.lastwave.app.playback.automix.api.AutoMixSettings
import com.lastwave.app.playback.automix.api.CrossfadeCurveType
import com.lastwave.app.playback.automix.api.DecibelLufs
import com.lastwave.app.playback.automix.api.TransitionDurationMode
import com.lastwave.app.playback.automix.api.TransitionStyle
import com.lastwave.app.playback.automix.data.TransitionOverrideEntity
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Rich transition plan produced by [SmartBeatTransitionPlanner] (Tier 2 Improved Smart AutoMix,
 * Tier 3 Deep Curated Mix recipes, and Manual DJ Remix overrides).
 */
data class SmartTransitionPlan(
    val cueOutStartMs: Long,
    val cueInStartMs: Long,
    val durationMs: Long,
    val transitionStyle: TransitionStyle,
    val curveType: CrossfadeCurveType = CrossfadeCurveType.EQUAL_POWER,
    val isBypass: Boolean = false,
    val bypassReason: String? = null,
    val pitchShiftSemitones: Int = 0,
    val requiresPhraseLoop: Boolean = false,
    val loopBarsCount: Int = 0,
    val tempoRatioOutgoing: Float = 1.0f,
    val tempoRatioIncoming: Float = 1.0f,
    val outgoingLufsTrimGain: Float = 1.0f,
    val incomingLufsTrimGain: Float = 1.0f,
    val eqLowSwap: Float = 0.5f,
    val eqMidSwap: Float = 0.5f,
    val eqHighSwap: Float = 0.5f,
    val filterSweepEnabled: Boolean = true,
    val harmonicKeyPair: String? = null,
    val syncedBpm: Float? = null,
    val isVocalProtected: Boolean = true,
    val isManualOverride: Boolean = false,
    val trimmedOutgoingTailMs: Long = 0L,
    val trimmedIncomingLeadMs: Long = 0L,
) {
    fun toSplicePlan(): SplicePlan = SplicePlan(
        cueOutStartMs = cueOutStartMs,
        cueInStartMs = cueInStartMs,
        durationMs = durationMs,
        transitionStyle = transitionStyle,
        isBypass = isBypass,
        bypassReason = bypassReason,
        pitchShiftSemitones = pitchShiftSemitones,
        requiresPhraseLoop = requiresPhraseLoop,
        loopBarsCount = loopBarsCount,
    )
}

/**
 * Tier 2 ("Improved Smart") beat-aligned, loudness-matched, vocal-protected transition planner.
 *
 * Combines:
 * - Silence/low-energy outro tail & intro lead-in trimming from [DownbeatGridEstimator]
 * - Beat-aligned 4..16 bar splice windows snapped to downbeat anchors
 * - Tempo bridging clamped strictly to +-6% for [TransientPreservingStretcher]
 * - Loudness contour gain matching (+-0.5 LU target, clamped gain trim)
 * - Vocal collision avoidance via [VocalPresenceGateDetector] & [PhraseLoopSynthesizer]
 * - 3-band Linkwitz-Riley bass swap and manual [TransitionOverrideEntity] recipes
 */
object SmartBeatTransitionPlanner {

    const val MAX_SMART_TEMPO_STRETCH_FRACTION = 0.06f // +-6% clamp
    const val MIN_SMART_TEMPO_RATIO = 1.0f - MAX_SMART_TEMPO_STRETCH_FRACTION // 0.94f
    const val MAX_SMART_TEMPO_RATIO = 1.0f + MAX_SMART_TEMPO_STRETCH_FRACTION // 1.06f

    private const val MIN_TRANSITION_MS = 4_000L
    private const val MAX_TRANSITION_MS = 32_000L
    private const val MAX_OUTRO_SILENCE_TRIM_MS = 14_000L
    private const val MAX_INTRO_LEAD_TRIM_MS = 8_000L
    private const val MAX_LUFS_GAIN_DB = 6.0f

    fun planTransition(
        outgoingTrack: PlayableTrack,
        outgoingSignature: AcousticSignature?,
        incomingTrack: PlayableTrack,
        incomingSignature: AcousticSignature?,
        settings: AutoMixSettings,
        manualOverride: TransitionOverrideEntity? = null,
        isMusicVideoOrLive: Boolean = false,
        outgoingDurationMs: Long? = null,
        incomingDurationMs: Long? = null,
    ): SmartTransitionPlan {
        val outDur = outgoingDurationMs?.takeIf { it > 0L }
            ?: outgoingTrack.durationMs?.takeIf { it > 0L }
            ?: outgoingSignature?.durationMs?.takeIf { it > 0L }
            ?: 180_000L
        val inDur = incomingDurationMs?.takeIf { it > 0L }
            ?: incomingTrack.durationMs?.takeIf { it > 0L }
            ?: incomingSignature?.durationMs?.takeIf { it > 0L }
            ?: 180_000L

        if (manualOverride != null) {
            return applyManualOverride(
                override = manualOverride.normalized(),
                outgoingSignature = outgoingSignature,
                incomingSignature = incomingSignature,
                outDur = outDur,
                inDur = inDur,
            )
        }

        if (outDur < 15_000L || inDur < 15_000L) {
            return SmartTransitionPlan(
                cueOutStartMs = maxOf(0L, outDur - 2_000L),
                cueInStartMs = 0L,
                durationMs = 2_000L,
                transitionStyle = TransitionStyle.PERCEPTUAL_FADE,
                isBypass = true,
                bypassReason = "Short-track duration guard (<15s)",
            )
        }

        if (isMusicVideoOrLive) {
            return SmartTransitionPlan(
                cueOutStartMs = maxOf(0L, outDur - 3_000L),
                cueInStartMs = 0L,
                durationMs = 3_000L,
                transitionStyle = TransitionStyle.PERCEPTUAL_FADE,
                isBypass = true,
                bypassReason = "Live broadcast or video stream guard",
            )
        }

        // 1. Tempo bridging clamped strictly to +-6% (including 1:2 / 2:1 half/double-time).
        val tempoBridge = computeTempoBridge(
            outgoingBpm = outgoingSignature?.beatGrid?.bpm?.value,
            incomingBpm = incomingSignature?.beatGrid?.bpm?.value,
        )

        // 2. Harmonic evaluation via Camelot wheel.
        val harmonicMatch = if (outgoingSignature != null && incomingSignature != null) {
            AutoMixCamelotClassifier.evaluateCompatibility(
                keyOut = outgoingSignature.camelotKey,
                keyIn = incomingSignature.camelotKey,
                tempoDeltaPercent = tempoBridge.rawDeltaPercent,
            )
        } else null

        // 3. Trim dead silence / low-energy outro tail and intro lead-in.
        val trimmedOutroTailMs = computeSafeOutroTailTrim(outgoingSignature, outDur)
        val effectiveOutEndMs = (outDur - trimmedOutroTailMs).coerceAtLeast(outDur / 2L)
        val trimmedIntroLeadMs = computeSafeIntroCueIn(incomingSignature, inDur)

        // 4. Beat-aligned splice duration (4..16 bars) around user target.
        val baseDurationMs = when (settings.durationMode) {
            TransitionDurationMode.AUTO -> 12_000L
            TransitionDurationMode.MANUAL -> settings.targetDurationSeconds.coerceIn(4, 32) * 1_000L
        }
        val harmonicScaledMs = (baseDurationMs * (harmonicMatch?.allowedDurationMultiplier ?: 1.0f)).toLong()
        var plannedDurationMs = quantizeToMusicalPhraseMs(
            targetMs = harmonicScaledMs,
            beatIntervalMs = outgoingSignature?.beatGrid?.beatIntervalMs
                ?: incomingSignature?.beatGrid?.beatIntervalMs,
            minBars = 4,
            maxBars = 16,
        )
        plannedDurationMs = minOf(plannedDurationMs, (effectiveOutEndMs * 0.35f).toLong())
            .coerceIn(MIN_TRANSITION_MS, MAX_TRANSITION_MS)

        // 5. Snap cueOutStartMs to the nearest downbeat anchor before effectiveOutEndMs.
        val rawCueOutMs = (effectiveOutEndMs - plannedDurationMs).coerceAtLeast(0L)
        var cueOutStartMs = snapToDownbeatAnchor(
            targetMs = rawCueOutMs,
            anchors = outgoingSignature?.beatGrid?.downbeatAnchorsMs.orEmpty(),
            maxAllowedMs = maxOf(0L, effectiveOutEndMs - MIN_TRANSITION_MS),
        )
        plannedDurationMs = (effectiveOutEndMs - cueOutStartMs)
            .coerceIn(MIN_TRANSITION_MS, MAX_TRANSITION_MS)

        val cueInStartMs = trimmedIntroLeadMs

        // 6. Vocal-clash detection and automatic mitigation.
        var requiresLoop = false
        var loopBars = 0
        var eqMidSwap = 0.5f
        var vocalProtected = true

        if (outgoingSignature != null && incomingSignature != null) {
            val collision = VocalPresenceGateDetector.hasVocalCollision(
                outgoingMask = outgoingSignature.outroVocalMask,
                incomingMask = incomingSignature.introVocalMask,
                spliceStartOutgoingMs = cueOutStartMs,
                spliceStartIncomingMs = cueInStartMs,
                spliceDurationMs = plannedDurationMs,
            )
            if (collision) {
                val incomingStartsWithVocals = incomingSignature.introVocalMask.isVocalActiveAt(cueInStartMs)
                if (incomingStartsWithVocals) {
                    val loop = PhraseLoopSynthesizer.createVocalSafeLoop(
                        grid = outgoingSignature.beatGrid,
                        vocalMask = outgoingSignature.outroVocalMask,
                        targetBars = 8,
                        isMusicVideoOrLive = false,
                    )
                    if (loop.isValid) {
                        requiresLoop = true
                        loopBars = loop.barCount
                    } else {
                        // Trim duration & shift cue-out later to exit outgoing vocals swiftly.
                        plannedDurationMs = (plannedDurationMs * 0.60f).toLong().coerceIn(MIN_TRANSITION_MS, 10_000L)
                        cueOutStartMs = maxOf(0L, effectiveOutEndMs - plannedDurationMs)
                        eqMidSwap = 0.72f
                    }
                } else {
                    // Incoming has instrumental runway before vocals; tighten overlap to avoid outgoing tail clash.
                    val barMs = (outgoingSignature.beatGrid.beatIntervalMs * 4L).coerceAtLeast(1_500L)
                    val reducedMs = maxOf(barMs * 4L, (plannedDurationMs * 0.65f).toLong())
                        .coerceIn(MIN_TRANSITION_MS, plannedDurationMs)
                    plannedDurationMs = reducedMs
                    cueOutStartMs = snapToDownbeatAnchor(
                        targetMs = maxOf(0L, effectiveOutEndMs - plannedDurationMs),
                        anchors = outgoingSignature.beatGrid.downbeatAnchorsMs,
                        maxAllowedMs = maxOf(0L, effectiveOutEndMs - MIN_TRANSITION_MS),
                    )
                    eqMidSwap = 0.65f
                }

                // Re-verify residual collision for telemetry accuracy.
                vocalProtected = !VocalPresenceGateDetector.hasVocalCollision(
                    outgoingMask = outgoingSignature.outroVocalMask,
                    incomingMask = incomingSignature.introVocalMask,
                    spliceStartOutgoingMs = cueOutStartMs,
                    spliceStartIncomingMs = cueInStartMs,
                    spliceDurationMs = plannedDurationMs,
                ) || requiresLoop
            }
        }

        // 7. Loudness matching via LUFS contour (matches outgoing & incoming within +-0.5 LU target).
        val (outGainTrim, inGainTrim) = computeLufsTrimGains(
            outgoingLufs = outgoingSignature?.integratedLufs ?: DecibelLufs.TARGET,
            incomingLufs = incomingSignature?.integratedLufs ?: DecibelLufs.TARGET,
        )

        // 8. Select curve & 3-band bass swap parameters.
        val curveType = when {
            requiresLoop -> CrossfadeCurveType.CUT_IN
            harmonicMatch?.tier == HarmonicCompatibilityTier.PERFECT_LOCK && tempoBridge.isTempoMatched ->
                CrossfadeCurveType.EQUAL_POWER
            harmonicMatch?.tier == HarmonicCompatibilityTier.DISSONANT -> CrossfadeCurveType.S_CURVE
            else -> CrossfadeCurveType.EQUAL_POWER
        }

        val style = when {
            requiresLoop -> TransitionStyle.LOOP_DROP
            harmonicMatch?.tier == HarmonicCompatibilityTier.PERFECT_LOCK && tempoBridge.isTempoMatched ->
                TransitionStyle.HARMONIC_BEATMIX
            settings.spectralFilterSweepsEnabled -> TransitionStyle.SPECTRAL_SWEEP
            else -> TransitionStyle.PERCEPTUAL_FADE
        }

        // For dissonant transitions, swap bass earlier to avoid low-end rumble clash.
        val eqLowSwap = when (harmonicMatch?.tier) {
            HarmonicCompatibilityTier.DISSONANT -> 0.68f
            HarmonicCompatibilityTier.PERFECT_LOCK -> 0.50f
            else -> 0.55f
        }

        return SmartTransitionPlan(
            cueOutStartMs = cueOutStartMs,
            cueInStartMs = cueInStartMs,
            durationMs = plannedDurationMs,
            transitionStyle = style,
            curveType = curveType,
            isBypass = false,
            pitchShiftSemitones = harmonicMatch?.suggestedPitchShiftSemitones ?: 0,
            requiresPhraseLoop = requiresLoop,
            loopBarsCount = loopBars,
            tempoRatioOutgoing = tempoBridge.outgoingRatio,
            tempoRatioIncoming = tempoBridge.incomingRatio,
            outgoingLufsTrimGain = outGainTrim,
            incomingLufsTrimGain = inGainTrim,
            eqLowSwap = eqLowSwap,
            eqMidSwap = eqMidSwap,
            eqHighSwap = 0.50f,
            filterSweepEnabled = settings.spectralFilterSweepsEnabled,
            harmonicKeyPair = harmonicMatch?.keyPairLabel
                ?: incomingSignature?.camelotKey?.takeIf { it.isValid }?.toString(),
            syncedBpm = tempoBridge.syncedBpm ?: incomingSignature?.beatGrid?.bpm?.value?.takeIf { !it.isNaN() },
            isVocalProtected = vocalProtected,
            isManualOverride = false,
            trimmedOutgoingTailMs = trimmedOutroTailMs,
            trimmedIncomingLeadMs = trimmedIntroLeadMs,
        )
    }

    /**
     * Converts a persisted [TransitionOverrideEntity] (from Manual DJ Remix Studio or Deep Curated Mix)
     * into an executable [SmartTransitionPlan].
     */
    fun applyManualOverride(
        override: TransitionOverrideEntity,
        outgoingSignature: AcousticSignature?,
        incomingSignature: AcousticSignature?,
        outDur: Long,
        inDur: Long,
    ): SmartTransitionPlan {
        val clean = override.normalized()
        val durationMs = (clean.transitionDurationSec * 1_000L)
            .coerceIn(MIN_TRANSITION_MS, minOf(MAX_TRANSITION_MS, (outDur * 0.65f).toLong().coerceAtLeast(MIN_TRANSITION_MS)))

        val cueOutStartMs = if (clean.cueOutMs > 0L) {
            clean.cueOutMs.coerceIn(0L, maxOf(0L, outDur - MIN_TRANSITION_MS))
        } else {
            val tailTrim = computeSafeOutroTailTrim(outgoingSignature, outDur)
            maxOf(0L, (outDur - tailTrim) - durationMs)
        }

        val cueInStartMs = clean.cueInMs.coerceIn(0L, maxOf(0L, inDur - MIN_TRANSITION_MS))

        val (tempoOut, tempoIn, syncedBpm) = if (clean.bpmMatchEnabled) {
            if (abs(clean.targetTempoRatio - 1.0f) > 0.002f) {
                val ratio = clean.targetTempoRatio.coerceIn(
                    TransitionOverrideEntity.MIN_MANUAL_TEMPO_RATIO,
                    TransitionOverrideEntity.MAX_MANUAL_TEMPO_RATIO,
                )
                val outBpm = outgoingSignature?.beatGrid?.bpm?.value?.takeIf { !it.isNaN() && it > 0f }
                Triple(1.0f, ratio, outBpm?.let { it * ratio })
            } else {
                val bridge = computeTempoBridge(
                    outgoingBpm = outgoingSignature?.beatGrid?.bpm?.value,
                    incomingBpm = incomingSignature?.beatGrid?.bpm?.value,
                )
                Triple(bridge.outgoingRatio, bridge.incomingRatio, bridge.syncedBpm)
            }
        } else {
            Triple(1.0f, 1.0f, incomingSignature?.beatGrid?.bpm?.value?.takeIf { !it.isNaN() })
        }

        val (outLufsTrim, inLufsTrim) = computeLufsTrimGains(
            outgoingLufs = outgoingSignature?.integratedLufs ?: DecibelLufs.TARGET,
            incomingLufs = incomingSignature?.integratedLufs ?: DecibelLufs.TARGET,
        )

        val curveType = CrossfadeCurveType.fromNameOrDefault(clean.curveType)
        val style = when {
            clean.filterSweepEnabled -> TransitionStyle.SPECTRAL_SWEEP
            clean.bpmMatchEnabled -> TransitionStyle.HARMONIC_BEATMIX
            else -> TransitionStyle.PERCEPTUAL_FADE
        }

        val keyPair = if (outgoingSignature != null && incomingSignature != null &&
            outgoingSignature.camelotKey.isValid && incomingSignature.camelotKey.isValid
        ) {
            "${outgoingSignature.camelotKey} -> ${incomingSignature.camelotKey}"
        } else {
            incomingSignature?.camelotKey?.takeIf { it.isValid }?.toString()
        }

        return SmartTransitionPlan(
            cueOutStartMs = cueOutStartMs,
            cueInStartMs = cueInStartMs,
            durationMs = durationMs,
            transitionStyle = style,
            curveType = curveType,
            isBypass = false,
            tempoRatioOutgoing = tempoOut,
            tempoRatioIncoming = tempoIn,
            outgoingLufsTrimGain = outLufsTrim,
            incomingLufsTrimGain = inLufsTrim,
            eqLowSwap = clean.eqLowSwap,
            eqMidSwap = clean.eqMidSwap,
            eqHighSwap = clean.eqHighSwap,
            filterSweepEnabled = clean.filterSweepEnabled,
            harmonicKeyPair = keyPair,
            syncedBpm = syncedBpm,
            isVocalProtected = true,
            isManualOverride = true,
        )
    }

    data class TempoBridgeResult(
        val outgoingRatio: Float,
        val incomingRatio: Float,
        val syncedBpm: Float?,
        val rawDeltaPercent: Float,
        val isTempoMatched: Boolean,
    )

    /**
     * Computes a symmetric or incoming-biased tempo bridge between [outgoingBpm] and [incomingBpm],
     * accounting for half-time (0.5x) and double-time (2x) relationships, strictly clamped to +-6%.
     */
    fun computeTempoBridge(outgoingBpm: Float?, incomingBpm: Float?): TempoBridgeResult {
        if (outgoingBpm == null || incomingBpm == null ||
            outgoingBpm.isNaN() || incomingBpm.isNaN() ||
            outgoingBpm < 45f || incomingBpm < 45f
        ) {
            return TempoBridgeResult(
                outgoingRatio = 1.0f,
                incomingRatio = 1.0f,
                syncedBpm = incomingBpm?.takeIf { !it.isNaN() && it > 0f }
                    ?: outgoingBpm?.takeIf { !it.isNaN() && it > 0f },
                rawDeltaPercent = 0f,
                isTempoMatched = false,
            )
        }

        // Find the closest harmonic multiple of incomingBpm (1x, 2x, 0.5x) to outgoingBpm.
        val candidates = listOf(incomingBpm, incomingBpm * 2.0f, incomingBpm * 0.5f)
        val effectiveIncomingBpm = candidates.minBy { abs(it - outgoingBpm) }
        val rawRatio = outgoingBpm / effectiveIncomingBpm
        val rawDeltaFraction = abs(rawRatio - 1.0f)
        val rawDeltaPercent = ((effectiveIncomingBpm - outgoingBpm) / outgoingBpm) * 100f

        if (rawDeltaFraction > MAX_SMART_TEMPO_STRETCH_FRACTION) {
            // Beyond +-6%: avoid chipmunk/sludge artifacts by leaving tempo unstretched.
            return TempoBridgeResult(
                outgoingRatio = 1.0f,
                incomingRatio = 1.0f,
                syncedBpm = incomingBpm,
                rawDeltaPercent = rawDeltaPercent,
                isTempoMatched = false,
            )
        }

        // Split the stretch symmetrically toward the midpoint BPM so neither song drifts far,
        // and clamp both ratios strictly within [0.94, 1.06].
        val midBpm = (outgoingBpm + effectiveIncomingBpm) * 0.5f
        val outRatio = (midBpm / outgoingBpm).coerceIn(MIN_SMART_TEMPO_RATIO, MAX_SMART_TEMPO_RATIO)
        val inRatio = (midBpm / effectiveIncomingBpm).coerceIn(MIN_SMART_TEMPO_RATIO, MAX_SMART_TEMPO_RATIO)
        val matched = DownbeatGridEstimator.isHarmonicallyEquivalent(
            bpmA = outgoingBpm,
            bpmB = incomingBpm,
            tolerancePercent = MAX_SMART_TEMPO_STRETCH_FRACTION * 100f,
        )

        return TempoBridgeResult(
            outgoingRatio = outRatio,
            incomingRatio = inRatio,
            syncedBpm = midBpm,
            rawDeltaPercent = rawDeltaPercent,
            isTempoMatched = matched,
        )
    }

    /**
     * Computes LUFS-matched linear gain trims for outgoing and incoming tracks so perceived
     * loudness remains flat within +-0.5 LU across the transition.
     */
    fun computeLufsTrimGains(
        outgoingLufs: DecibelLufs,
        incomingLufs: DecibelLufs,
    ): Pair<Float, Float> {
        val outDb = outgoingLufs.value.takeIf { it.isFinite() && it > -65f } ?: DecibelLufs.TARGET.value
        val inDb = incomingLufs.value.takeIf { it.isFinite() && it > -65f } ?: DecibelLufs.TARGET.value
        val deltaDb = outDb - inDb
        if (abs(deltaDb) <= 0.5f) return 1.0f to 1.0f

        val targetDb = ((outDb + inDb) * 0.5f).coerceIn(-18.0f, -9.0f)
        val outAdjDb = (targetDb - outDb).coerceIn(-MAX_LUFS_GAIN_DB, MAX_LUFS_GAIN_DB)
        val inAdjDb = (targetDb - inDb).coerceIn(-MAX_LUFS_GAIN_DB, MAX_LUFS_GAIN_DB)

        val outLinear = 10.0.pow(outAdjDb / 20.0).toFloat().coerceIn(0.55f, 1.45f)
        val inLinear = 10.0.pow(inAdjDb / 20.0).toFloat().coerceIn(0.55f, 1.45f)
        return outLinear to inLinear
    }

    internal fun computeSafeOutroTailTrim(signature: AcousticSignature?, outDur: Long): Long {
        if (signature == null || outDur <= 20_000L) return 0L
        val rawTail = signature.beatGrid.phraseBoundary.silenceTailMs
        // Guard against mis-interpreted absolute timestamps vs silence duration:
        // PhraseBoundary.silenceTailMs is a tail duration (ms from end), but some mocks pass end timestamp.
        val tailDurationMs = if (rawTail > outDur / 2L) {
            maxOf(0L, outDur - rawTail)
        } else {
            rawTail.coerceAtLeast(0L)
        }
        return tailDurationMs.coerceIn(0L, minOf(MAX_OUTRO_SILENCE_TRIM_MS, outDur / 5L))
    }

    internal fun computeSafeIntroCueIn(signature: AcousticSignature?, inDur: Long): Long {
        if (signature == null || inDur <= 20_000L) return 0L
        val firstDownbeat = signature.beatGrid.firstDownbeatMs.coerceAtLeast(0L)
        // Never skip past an early vocal entry.
        val earliestVocalMs = signature.introVocalMask.vocalSegments.minOfOrNull { it.startMs }
        val maxAllowed = minOf(MAX_INTRO_LEAD_TRIM_MS, inDur / 8L)
        val safeCue = if (earliestVocalMs != null && earliestVocalMs < firstDownbeat) {
            minOf(earliestVocalMs, maxAllowed).coerceAtLeast(0L)
        } else {
            firstDownbeat.coerceIn(0L, maxAllowed)
        }
        return safeCue
    }

    internal fun quantizeToMusicalPhraseMs(
        targetMs: Long,
        beatIntervalMs: Long?,
        minBars: Int = 4,
        maxBars: Int = 16,
    ): Long {
        if (beatIntervalMs == null || beatIntervalMs !in 250L..1_500L) {
            return targetMs.coerceIn(MIN_TRANSITION_MS, MAX_TRANSITION_MS)
        }
        val barMs = beatIntervalMs * 4L
        val rawBars = (targetMs.toDouble() / barMs.toDouble()).roundToInt()
        // Snap to musical 4-bar phrase multiples (4, 8, 12, 16 bars).
        val quantizedBars = (((rawBars + 2) / 4) * 4).coerceIn(minBars, maxBars)
        return (quantizedBars * barMs).coerceIn(MIN_TRANSITION_MS, MAX_TRANSITION_MS)
    }

    internal fun snapToDownbeatAnchor(
        targetMs: Long,
        anchors: List<Long>,
        maxAllowedMs: Long,
    ): Long {
        if (anchors.isEmpty()) return targetMs.coerceIn(0L, maxAllowedMs)
        val eligible = anchors.filter { it in 0L..maxAllowedMs }
        if (eligible.isEmpty()) return targetMs.coerceIn(0L, maxAllowedMs)
        return eligible.minBy { abs(it - targetMs) }
    }
}
