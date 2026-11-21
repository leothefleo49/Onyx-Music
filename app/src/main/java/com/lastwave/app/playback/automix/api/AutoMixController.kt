package com.lastwave.app.playback.automix.api

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.StateFlow

/**
 * Live 3-band EQ swap and filter state for Manual DJ Remixing Studio.
 */
@Immutable
data class LiveDjControlState(
    val eqLowSwap: Float = 0.5f,
    val eqMidSwap: Float = 0.5f,
    val eqHighSwap: Float = 0.5f,
    val tempoRatio: Float = 1.0f,
    val filterSweepEnabled: Boolean = true,
    val curveType: CrossfadeCurveType = CrossfadeCurveType.EQUAL_POWER,
    val transitionDurationSec: Int = 12,
    val bpmMatchEnabled: Boolean = true,
)

/**
 * Public reactive contract exposing AutoMix state to the playback service and UI layers.
 */
interface AutoMixController {
    /** Live immutable snapshot of the dual-deck transition state. */
    val telemetry: StateFlow<DeckBlendTelemetry>

    /** Active 3-tier AutoMix mode (Classic, Improved Smart, Deep Curated Mix). */
    val activeTier: StateFlow<AutoMixTier>

    /** Live DJ EQ / filter / tempo controls mirrored to audio processors and UI. */
    val liveDjState: StateFlow<LiveDjControlState>

    /** Switches the active AutoMix tier and persists the selection. */
    fun setActiveTier(tier: AutoMixTier)

    /** Cancels an ongoing or armed blend, restoring unity gain to primary deck. */
    fun cancelTransition(revertToPrimary: Boolean = true)

    /** Immediately commits the secondary deck as primary and finishes the blend. */
    fun forceCommitTransition()

    /** Triggers an immediate DJ-style phrase-locked transition. */
    fun triggerImmediateTransition(customDurationMs: Long? = null)

    /** Updates live 3-band EQ swap faders, tempo ratio, and filter sweep on active crossover processors. */
    fun applyLiveDjParameters(
        eqLowSwap: Float,
        eqMidSwap: Float,
        eqHighSwap: Float,
        tempoRatio: Float,
        filterSweepEnabled: Boolean,
        curveType: CrossfadeCurveType = CrossfadeCurveType.EQUAL_POWER,
        transitionDurationSec: Int = 12,
        bpmMatchEnabled: Boolean = true,
    )

    /** Notifies the controller of a user seek event during a transition. */
    fun handleSeekEvent(targetPositionMs: Long)

    /** Suspends transition execution if bit-perfect, exclusive USB, or synchronized listening is active. */
    fun updateHardwareBypassState(isHardwareBypassActive: Boolean)
}
