package com.lastwave.app.playback.automix.engine

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.automix.analysis.AcousticSignature
import com.lastwave.app.playback.automix.analysis.AcousticSignatureExtractor
import com.lastwave.app.playback.automix.analysis.AcousticSignatureStore
import com.lastwave.app.playback.automix.api.AutoMixController
import com.lastwave.app.playback.automix.api.AutoMixPreferences
import com.lastwave.app.playback.automix.api.AutoMixSettings
import com.lastwave.app.playback.automix.api.AutoMixTier
import com.lastwave.app.playback.automix.api.BlendPhase
import com.lastwave.app.playback.automix.api.CrossfadeCurveEvaluator
import com.lastwave.app.playback.automix.api.CrossfadeCurveType
import com.lastwave.app.playback.automix.api.DeckBlendTelemetry
import com.lastwave.app.playback.automix.api.LiveDjControlState
import com.lastwave.app.playback.automix.api.TrackChromeSummary
import com.lastwave.app.playback.automix.api.TransitionStyle
import com.lastwave.app.playback.automix.data.RemixPresetRepository
import com.lastwave.app.playback.automix.data.TransitionOverrideEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DualDeckTransportCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AutoMixPreferences,
    private val signatureExtractor: AcousticSignatureExtractor,
    private val signatureStore: AcousticSignatureStore,
    private val remixPresetRepository: RemixPresetRepository,
) : AutoMixController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var baseVolume: Float = 1.0f
        private set

    fun setBaseVolume(vol: Float) {
        baseVolume = vol.coerceIn(0f, 1f)
        if (activeSpliceJob == null || activeSpliceJob?.isActive != true) {
            currentPrimaryPlayer?.volume = baseVolume
        }
    }

    private val _telemetry = MutableStateFlow(DeckBlendTelemetry.IDLE)
    override val telemetry: StateFlow<DeckBlendTelemetry> = _telemetry.asStateFlow()

    private val _activeTier = MutableStateFlow(AutoMixTier.CLASSIC)
    override val activeTier: StateFlow<AutoMixTier> = _activeTier.asStateFlow()

    private val _liveDjState = MutableStateFlow(LiveDjControlState())
    override val liveDjState: StateFlow<LiveDjControlState> = _liveDjState.asStateFlow()

    private var currentSettings = AutoMixSettings()
    val isEnabled: Boolean get() = currentSettings.autoMixEnabled
    private var isHardwareBypassEngaged = false
    private var isJamRoomActive = false

    private var activeTrackKey: String? = null
    private var activeSpliceJob: Job? = null
    private var currentSplicePlan: SplicePlan? = null
    private var currentSmartPlan: SmartTransitionPlan? = null
    private var currentOutgoingSignature: AcousticSignature? = null
    private var currentIncomingSignature: AcousticSignature? = null
    private var currentOutgoingTrack: PlayableTrack? = null
    private var currentIncomingTrack: PlayableTrack? = null

    /** Outgoing duration the current plan was built for. */
    private var planDurationMs: Long = 0L

    /** An explicit "mix now" or DJ preview request: fires regardless of time left. */
    private var immediatePlan = false
    private var currentPrimaryPlayer: ExoPlayer? = null
    private var currentSecondaryPlayer: ExoPlayer? = null

    /** User pause / audio-focus pause. A blend must hold still while it is set. */
    private var transportPaused = false

    /** Optional DSP processors attached by playback pipeline for live 3-band EQ swap. */
    @Volatile
    private var outgoingCrossoverProcessor: SpectralCrossoverAudioProcessor? = null

    @Volatile
    private var incomingCrossoverProcessor: SpectralCrossoverAudioProcessor? = null

    /** True while a blend (both decks audible) is in progress. */
    val isBlending: Boolean get() = activeSpliceJob?.isActive == true

    fun attachCrossoverProcessors(
        outgoing: SpectralCrossoverAudioProcessor?,
        incoming: SpectralCrossoverAudioProcessor?,
    ) {
        outgoingCrossoverProcessor = outgoing?.also { it.isIncomingDeck = false }
        incomingCrossoverProcessor = incoming?.also { it.isIncomingDeck = true }
        syncLiveDjToProcessors(_liveDjState.value)
    }

    /** Swaps outgoing/incoming crossover processor roles when decks hand over mid-blend. */
    fun swapCrossoverDecks() {
        val oldOut = outgoingCrossoverProcessor
        val oldIn = incomingCrossoverProcessor
        outgoingCrossoverProcessor = oldIn?.also { it.isIncomingDeck = false }
        incomingCrossoverProcessor = oldOut?.also { it.isIncomingDeck = true }
    }

    fun setEqKills(killLow: Boolean, killMid: Boolean, killHigh: Boolean) {
        outgoingCrossoverProcessor?.apply {
            this.killLow = killLow
            this.killMid = killMid
            this.killHigh = killHigh
            if (killLow || killMid || killHigh) isEnabled = true
        }
    }

    fun setFilterSweepMode(mode: FilterSweepMode) {
        val sweepOn = mode != FilterSweepMode.OFF
        outgoingCrossoverProcessor?.apply {
            filterSweepMode = mode
            applySpectralSweeps = sweepOn
        }
        incomingCrossoverProcessor?.apply {
            filterSweepMode = mode
            applySpectralSweeps = sweepOn
        }
    }

    fun setDeckGainTrims(outgoingDb: Float, incomingDb: Float) {
        outgoingCrossoverProcessor?.apply {
            manualGainTrimDb = outgoingDb.coerceIn(-12f, 6f)
            if (kotlin.math.abs(manualGainTrimDb) > 0.05f) isEnabled = true
        }
        incomingCrossoverProcessor?.apply {
            manualGainTrimDb = incomingDb.coerceIn(-12f, 6f)
            if (kotlin.math.abs(manualGainTrimDb) > 0.05f) isEnabled = true
        }
    }

    /**
     * Pause / resume coming from the player transport. During a blend both
     * decks are paused and the blend clock stops, so the old song does not
     * keep fading out and the next song is not handed over while paused;
     * resume picks the blend up exactly where it stopped.
     */
    fun setTransportPaused(paused: Boolean) {
        if (transportPaused == paused) return
        transportPaused = paused
        if (activeSpliceJob?.isActive != true) return
        runCatching {
            if (paused) {
                currentPrimaryPlayer?.pause()
                currentSecondaryPlayer?.pause()
            } else {
                currentPrimaryPlayer?.play()
                currentSecondaryPlayer?.play()
            }
        }
    }

    // Track reference callbacks into MusicPlayer
    var onPerformDeckSwap: ((outgoing: ExoPlayer, incoming: ExoPlayer) -> Unit)? = null
    var onTransitionComplete: (() -> Unit)? = null

    init {
        scope.launch {
            preferences.settings.collectLatest { settings ->
                val tierChanged = currentSettings.tier != settings.tier
                currentSettings = settings
                _activeTier.value = settings.tier
                if (!settings.autoMixEnabled) {
                    cancelTransition(revertToPrimary = true)
                } else if (tierChanged && activeSpliceJob?.isActive != true) {
                    currentSplicePlan = null
                    currentSmartPlan = null
                    if (_telemetry.value.phase == BlendPhase.ARMED) {
                        _telemetry.update {
                            it.copy(phase = BlendPhase.IDLE, activeTier = settings.tier)
                        }
                    }
                }
            }
        }
    }

    override fun setActiveTier(tier: AutoMixTier) {
        _activeTier.value = tier
        currentSettings = currentSettings.copy(tier = tier)
        scope.launch(Dispatchers.IO) {
            preferences.setAutoMixTier(tier)
        }
    }

    override fun applyLiveDjParameters(
        eqLowSwap: Float,
        eqMidSwap: Float,
        eqHighSwap: Float,
        tempoRatio: Float,
        filterSweepEnabled: Boolean,
        curveType: CrossfadeCurveType,
        transitionDurationSec: Int,
        bpmMatchEnabled: Boolean,
    ) {
        val updated = LiveDjControlState(
            eqLowSwap = eqLowSwap.coerceIn(0f, 1f),
            eqMidSwap = eqMidSwap.coerceIn(0f, 1f),
            eqHighSwap = eqHighSwap.coerceIn(0f, 1f),
            tempoRatio = tempoRatio.coerceIn(
                TransitionOverrideEntity.MIN_MANUAL_TEMPO_RATIO,
                TransitionOverrideEntity.MAX_MANUAL_TEMPO_RATIO,
            ),
            filterSweepEnabled = filterSweepEnabled,
            curveType = curveType,
            transitionDurationSec = transitionDurationSec.coerceIn(
                TransitionOverrideEntity.MIN_DURATION_SEC,
                TransitionOverrideEntity.MAX_DURATION_SEC,
            ),
            bpmMatchEnabled = bpmMatchEnabled,
        )
        _liveDjState.value = updated
        syncLiveDjToProcessors(updated)

        // Update active smart plan live if currently blending or armed.
        currentSmartPlan = currentSmartPlan?.copy(
            eqLowSwap = updated.eqLowSwap,
            eqMidSwap = updated.eqMidSwap,
            eqHighSwap = updated.eqHighSwap,
            filterSweepEnabled = updated.filterSweepEnabled,
            curveType = updated.curveType,
            tempoRatioIncoming = if (updated.bpmMatchEnabled) updated.tempoRatio else 1.0f,
        )
    }

    /**
     * Triggers an immediate live preview of a Manual DJ Remix Studio transition override.
     */
    fun triggerPreviewTransition(
        override: TransitionOverrideEntity,
        outgoingDurationMs: Long? = null,
        incomingDurationMs: Long? = null,
    ) {
        val outDur = outgoingDurationMs?.takeIf { it > 0L }
            ?: runCatching { currentPrimaryPlayer?.duration?.takeIf { it > 0L } }.getOrNull()
            ?: currentOutgoingTrack?.durationMs
            ?: 180_000L
        val inDur = incomingDurationMs?.takeIf { it > 0L }
            ?: runCatching { currentSecondaryPlayer?.duration?.takeIf { it > 0L } }.getOrNull()
            ?: currentIncomingTrack?.durationMs
            ?: 180_000L

        val smartPlan = SmartBeatTransitionPlanner.applyManualOverride(
            override = override,
            outgoingSignature = currentOutgoingSignature,
            incomingSignature = currentIncomingSignature,
            outDur = outDur,
            inDur = inDur,
        )

        applyLiveDjParameters(
            eqLowSwap = smartPlan.eqLowSwap,
            eqMidSwap = smartPlan.eqMidSwap,
            eqHighSwap = smartPlan.eqHighSwap,
            tempoRatio = smartPlan.tempoRatioIncoming,
            filterSweepEnabled = smartPlan.filterSweepEnabled,
            curveType = smartPlan.curveType,
            transitionDurationSec = (smartPlan.durationMs / 1_000L).toInt(),
            bpmMatchEnabled = override.bpmMatchEnabled,
        )

        currentSmartPlan = smartPlan
        currentSplicePlan = smartPlan.toSplicePlan()
        planDurationMs = outDur
        immediatePlan = true

        _telemetry.update {
            it.copy(
                phase = BlendPhase.ARMED,
                spliceStartPositionMs = smartPlan.cueOutStartMs,
                spliceDurationMs = smartPlan.durationMs,
                activeTransitionStyle = smartPlan.transitionStyle,
                harmonicKeyPair = smartPlan.harmonicKeyPair,
                syncedBpm = smartPlan.syncedBpm,
                isVocalProtected = smartPlan.isVocalProtected,
                activeTier = currentSettings.tier,
                activeCurveType = smartPlan.curveType,
                activeTempoRatio = smartPlan.tempoRatioIncoming,
                isManualOverrideActive = true,
            )
        }

        val primary = currentPrimaryPlayer
        val secondary = currentSecondaryPlayer
        if (!transportPaused &&
            primary != null &&
            secondary != null &&
            primary.playWhenReady &&
            secondary.playbackState == Player.STATE_READY
        ) {
            startBlendExecution(primary, secondary, smartPlan.toSplicePlan(), smartPlan)
        }
    }

    fun onTrackProgressTick(
        primaryPlayer: ExoPlayer,
        secondaryPlayer: ExoPlayer?,
        currentTrack: PlayableTrack?,
        nextTrack: PlayableTrack?,
        positionMs: Long,
        durationMs: Long,
        nextTrackStreamUrl: String? = null,
    ) {
        val previousIncomingKey = currentIncomingTrack?.let { it.videoId ?: "${it.artist}|${it.title}" }
        currentPrimaryPlayer = primaryPlayer
        currentSecondaryPlayer = secondaryPlayer
        currentOutgoingTrack = currentTrack
        currentIncomingTrack = nextTrack

        if ((!currentSettings.autoMixEnabled && !immediatePlan) || isHardwareBypassEngaged || isJamRoomActive) {
            if (_telemetry.value.phase != BlendPhase.IDLE) {
                _telemetry.value = DeckBlendTelemetry.IDLE.copy(activeTier = currentSettings.tier)
            }
            return
        }

        if (currentTrack == null || durationMs <= 0L) return

        // If a blend is actively running, do not interfere with the transition in progress.
        if (activeSpliceJob?.isActive == true) {
            return
        }

        val incomingKey = nextTrack?.let { it.videoId ?: "${it.artist}|${it.title}" }
        val trackKey = currentTrack.videoId ?: "${currentTrack.artist}|${currentTrack.title}"
        if (activeTrackKey != trackKey) {
            activeTrackKey = trackKey
            currentSplicePlan = null
            currentSmartPlan = null
            currentOutgoingSignature = lookupCachedSignature(currentTrack, durationMs)
            currentIncomingSignature = null
            immediatePlan = false
            // Only reset telemetry if no blend is active or completing —
            // the deck swap changes the current track mid-blend, which must
            // not flicker the badge back to the quality label.
            val currentPhase = _telemetry.value.phase
            if (currentPhase != BlendPhase.BLENDING &&
                currentPhase != BlendPhase.LOOP_LOCKED &&
                currentPhase != BlendPhase.MIX_COMPLETED
            ) {
                _telemetry.value = DeckBlendTelemetry.IDLE.copy(activeTier = currentSettings.tier)
            }

            // Pre-analyze outgoing track signature for Tier 2 & Tier 3 so beat grid + LUFS are available.
            if (currentSettings.tier != AutoMixTier.CLASSIC && currentOutgoingSignature == null) {
                signatureExtractor.preAnalyzeTrack(currentTrack, currentTrack.playbackUrl) { outSig ->
                    if (activeTrackKey == trackKey) {
                        currentOutgoingSignature = outSig
                    }
                }
            }
        } else if (incomingKey != previousIncomingKey && activeSpliceJob?.isActive != true) {
            // Next song changed (reorder, play-next, shuffle toggle) — re-plan for the new upcoming track.
            currentSplicePlan = null
            currentSmartPlan = null
            currentIncomingSignature = null
            if (_telemetry.value.phase == BlendPhase.ARMED || _telemetry.value.phase == BlendPhase.PRE_ANALYZING) {
                _telemetry.value = DeckBlendTelemetry.IDLE.copy(activeTier = currentSettings.tier)
            }
        }

        // A plan built for a different length (the stream's real duration
        // arrived after the plan) would open the mix at the wrong moment.
        if (currentSplicePlan != null && !immediatePlan && kotlin.math.abs(planDurationMs - durationMs) > 1_500L) {
            currentSplicePlan = null
            currentSmartPlan = null
            if (_telemetry.value.phase == BlendPhase.ARMED) {
                // Reset to IDLE (not PRE_ANALYZING) so the look-ahead planner actually re-runs.
                _telemetry.update { it.copy(phase = BlendPhase.IDLE) }
            }
        }

        val remainingMs = durationMs - positionMs

        // 1. Look-ahead pre-analysis trigger: 25% progress or 90s remaining
        if ((positionMs > durationMs * 0.25f || remainingMs < 90_000L) && nextTrack != null) {
            if (_telemetry.value.phase == BlendPhase.IDLE) {
                _telemetry.update {
                    it.copy(
                        phase = BlendPhase.PRE_ANALYZING,
                        activeTier = currentSettings.tier,
                        incomingTrackPreview = TrackChromeSummary(
                            title = nextTrack.title,
                            artist = nextTrack.artist,
                            artworkUrl = nextTrack.artworkUrl,
                            durationMs = nextTrack.durationMs ?: 0L,
                        ),
                    )
                }

                signatureExtractor.preAnalyzeTrack(nextTrack, nextTrackStreamUrl ?: nextTrack.playbackUrl) { sig ->
                    val phaseNow = _telemetry.value.phase
                    if (activeTrackKey != trackKey ||
                        activeSpliceJob?.isActive == true ||
                        (phaseNow != BlendPhase.PRE_ANALYZING && phaseNow != BlendPhase.ARMED)
                    ) {
                        return@preAnalyzeTrack
                    }
                    currentIncomingSignature = sig

                    scope.launch {
                        val manualOverride = withContext(Dispatchers.IO) {
                            runCatching { remixPresetRepository.getOverride(currentTrack, nextTrack) }.getOrNull()
                        }
                        if (activeTrackKey != trackKey || activeSpliceJob?.isActive == true) {
                            return@launch
                        }

                        val useClassicOnly = currentSettings.tier == AutoMixTier.CLASSIC && manualOverride == null
                        if (useClassicOnly) {
                            // Tier 1 (CLASSIC): Preserve exact upstream SpliceTrajectorySynthesizer behavior.
                            val plan = SpliceTrajectorySynthesizer.synthesizePlan(
                                outgoingTrack = currentTrack,
                                outgoingSignature = null,
                                incomingTrack = nextTrack,
                                incomingSignature = sig,
                                settings = currentSettings,
                                outgoingDurationMs = durationMs,
                                incomingDurationMs = nextTrack.durationMs,
                            )
                            currentSmartPlan = null
                            currentSplicePlan = plan
                            planDurationMs = durationMs

                            if (!plan.isBypass) {
                                _telemetry.update {
                                    it.copy(
                                        phase = BlendPhase.ARMED,
                                        spliceStartPositionMs = plan.cueOutStartMs,
                                        spliceDurationMs = plan.durationMs,
                                        activeTransitionStyle = plan.transitionStyle,
                                        harmonicKeyPair = sig.camelotKey.toString(),
                                        syncedBpm = sig.beatGrid.bpm.value,
                                        activeTier = AutoMixTier.CLASSIC,
                                        activeCurveType = CrossfadeCurveType.EQUAL_POWER,
                                        activeTempoRatio = 1.0f,
                                        isManualOverrideActive = false,
                                    )
                                }
                            } else {
                                _telemetry.update { it.copy(phase = BlendPhase.ARMED, activeTier = AutoMixTier.CLASSIC) }
                            }
                        } else {
                            // Tier 2 (IMPROVED_SMART), Tier 3 (DEEP_CURATED_MIX), or explicit Manual DJ override.
                            val smartPlan = SmartBeatTransitionPlanner.planTransition(
                                outgoingTrack = currentTrack,
                                outgoingSignature = currentOutgoingSignature,
                                incomingTrack = nextTrack,
                                incomingSignature = sig,
                                settings = currentSettings,
                                manualOverride = manualOverride,
                                outgoingDurationMs = durationMs,
                                incomingDurationMs = nextTrack.durationMs,
                            )
                            currentSmartPlan = smartPlan
                            currentSplicePlan = smartPlan.toSplicePlan()
                            planDurationMs = durationMs

                            if (!smartPlan.isBypass) {
                                _telemetry.update {
                                    it.copy(
                                        phase = BlendPhase.ARMED,
                                        spliceStartPositionMs = smartPlan.cueOutStartMs,
                                        spliceDurationMs = smartPlan.durationMs,
                                        activeTransitionStyle = smartPlan.transitionStyle,
                                        harmonicKeyPair = smartPlan.harmonicKeyPair ?: sig.camelotKey.toString(),
                                        syncedBpm = smartPlan.syncedBpm ?: sig.beatGrid.bpm.value,
                                        isVocalProtected = smartPlan.isVocalProtected,
                                        activeTier = currentSettings.tier,
                                        activeCurveType = smartPlan.curveType,
                                        activeTempoRatio = smartPlan.tempoRatioIncoming,
                                        isManualOverrideActive = smartPlan.isManualOverride,
                                    )
                                }
                            } else {
                                _telemetry.update { it.copy(phase = BlendPhase.ARMED, activeTier = currentSettings.tier) }
                            }
                        }
                    }
                }
            }
        }

        // 2. Automatic Fallback Plan (Never miss a transition window)
        val fallbackDurationMs = (currentSettings.targetDurationSeconds * 1000L).coerceIn(4_000L, 25_000L)
        if (remainingMs <= fallbackDurationMs + 1000L && nextTrack != null) {
            val plan = currentSplicePlan
            if (plan == null || plan.isBypass) {
                val safePlan = SplicePlan(
                    cueOutStartMs = maxOf(0L, durationMs - fallbackDurationMs),
                    cueInStartMs = 0L,
                    durationMs = fallbackDurationMs,
                    transitionStyle = TransitionStyle.PERCEPTUAL_FADE,
                    isBypass = false,
                )
                currentSplicePlan = safePlan
                currentSmartPlan = null
                planDurationMs = durationMs
                _telemetry.update {
                    it.copy(
                        phase = BlendPhase.ARMED,
                        spliceStartPositionMs = safePlan.cueOutStartMs,
                        spliceDurationMs = safePlan.durationMs,
                        activeTransitionStyle = safePlan.transitionStyle,
                        activeTier = currentSettings.tier,
                    )
                }
            }
        }

        // 3. Trigger Splice at Splice Horizon (never while paused: starting
        // the incoming deck would play the next song behind the pause)
        val plan = currentSplicePlan
        if (!transportPaused && primaryPlayer.playWhenReady &&
            plan != null && !plan.isBypass &&
            (_telemetry.value.phase == BlendPhase.ARMED || _telemetry.value.phase == BlendPhase.PRE_ANALYZING)
        ) {
            val smart = currentSmartPlan
            val effectiveTailMarginMs = (smart?.trimmedOutgoingTailMs ?: 0L) + 1_000L
            val shouldTrigger = immediatePlan || (
                remainingMs <= plan.durationMs + effectiveTailMarginMs &&
                    (positionMs >= plan.cueOutStartMs || remainingMs <= plan.durationMs)
                )
            if (shouldTrigger && secondaryPlayer != null) {
                val isSecondaryReady = secondaryPlayer.playbackState == Player.STATE_READY
                if (isSecondaryReady) {
                    startBlendExecution(primaryPlayer, secondaryPlayer, plan, smart)
                }
            }
        }
    }

    private fun startBlendExecution(
        primaryPlayer: ExoPlayer,
        secondaryPlayer: ExoPlayer,
        plan: SplicePlan,
        smartPlan: SmartTransitionPlan?,
    ) {
        activeSpliceJob?.cancel()
        immediatePlan = false
        currentPrimaryPlayer = primaryPlayer
        currentSecondaryPlayer = secondaryPlayer
        val durationMs = plan.durationMs.coerceAtLeast(1000L)

        primaryPlayer.repeatMode = Player.REPEAT_MODE_OFF

        val useSmartEngine = smartPlan != null
        val curveType = smartPlan?.curveType ?: CrossfadeCurveType.EQUAL_POWER
        val initialTempoOut = smartPlan?.tempoRatioOutgoing ?: 1.0f
        val initialTempoIn = smartPlan?.tempoRatioIncoming ?: 1.0f
        val outLufsTrim = smartPlan?.outgoingLufsTrimGain ?: 1.0f
        val inLufsTrim = smartPlan?.incomingLufsTrimGain ?: 1.0f

        _telemetry.update {
            it.copy(
                phase = if (plan.requiresPhraseLoop) BlendPhase.LOOP_LOCKED else BlendPhase.BLENDING,
                blendProgress = 0.0f,
                activeTransitionStyle = plan.transitionStyle,
                loopBarsActive = if (plan.requiresPhraseLoop) plan.loopBarsCount else null,
                activeTier = currentSettings.tier,
                activeCurveType = curveType,
                activeTempoRatio = initialTempoIn,
                isManualOverrideActive = smartPlan?.isManualOverride == true,
            )
        }

        if (smartPlan != null) {
            outgoingCrossoverProcessor?.apply {
                isEnabled = true
                applyManualDjParams(
                    lowSwap = smartPlan.eqLowSwap,
                    midSwap = smartPlan.eqMidSwap,
                    highSwap = smartPlan.eqHighSwap,
                    tempoRatio = initialTempoOut,
                    filterSweepEnabled = smartPlan.filterSweepEnabled,
                    lufsTrim = outLufsTrim,
                )
            }
            incomingCrossoverProcessor?.apply {
                isEnabled = true
                applyManualDjParams(
                    lowSwap = smartPlan.eqLowSwap,
                    midSwap = smartPlan.eqMidSwap,
                    highSwap = smartPlan.eqHighSwap,
                    tempoRatio = initialTempoIn,
                    filterSweepEnabled = smartPlan.filterSweepEnabled,
                    lufsTrim = inLufsTrim,
                )
            }
            primaryPlayer.setPlaybackSpeed(initialTempoOut)
            secondaryPlayer.setPlaybackSpeed(initialTempoIn)
        } else {
            // Tier 1 CLASSIC: exact upstream unity speed.
            primaryPlayer.setPlaybackSpeed(1.0f)
            secondaryPlayer.setPlaybackSpeed(1.0f)
        }

        secondaryPlayer.volume = 0.0f
        // Avoid forcing a network re-buffer when the standby is already cued at 0:00.
        if (plan.cueInStartMs > 500L && kotlin.math.abs(secondaryPlayer.currentPosition - plan.cueInStartMs) > 500L) {
            secondaryPlayer.seekTo(plan.cueInStartMs)
        }
        secondaryPlayer.play()

        activeSpliceJob = scope.launch {
            var elapsed = 0L
            var lastTick = android.os.SystemClock.elapsedRealtime()
            var swapped = false

            while (isActive) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (!transportPaused) elapsed += now - lastTick
                lastTick = now
                if (transportPaused) {
                    delay(20L)
                    continue
                }
                val progress = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0.0f, 1.0f)
                val activeSmart = currentSmartPlan

                _telemetry.update { it.copy(blendProgress = progress) }

                outgoingCrossoverProcessor?.blendProgress = progress
                incomingCrossoverProcessor?.blendProgress = progress

                if (useSmartEngine && activeSmart != null) {
                    // Gently glide incoming tempo back to 1.0x over the final 40% of the blend.
                    if (progress > 0.60f && kotlin.math.abs(initialTempoIn - 1.0f) > 0.002f) {
                        val glide = ((progress - 0.60f) / 0.40f).coerceIn(0f, 1f)
                        val liveTempoIn = initialTempoIn + (1.0f - initialTempoIn) * glide
                        runCatching { secondaryPlayer.setPlaybackSpeed(liveTempoIn) }
                        _telemetry.update { it.copy(activeTempoRatio = liveTempoIn) }
                    }

                    val gains = CrossfadeCurveEvaluator.evaluate(
                        progress = progress,
                        curveType = activeSmart.curveType,
                        outgoingLufsTrim = activeSmart.outgoingLufsTrimGain,
                        incomingLufsTrim = activeSmart.incomingLufsTrimGain,
                    )
                    primaryPlayer.volume = (gains.outgoingGain * baseVolume).coerceIn(0f, 1f)
                    secondaryPlayer.volume = (gains.incomingGain * baseVolume).coerceIn(0f, 1f)
                } else {
                    // Tier 1 CLASSIC: exact upstream equal-power volume curve with headroom trim.
                    val gainOut = kotlin.math.cos(progress * Math.PI * 0.5).toFloat().coerceIn(0.0f, 1.0f)
                    val gainIn = kotlin.math.sin(progress * Math.PI * 0.5).toFloat().coerceIn(0.0f, 1.0f)
                    val sum = gainIn + gainOut
                    val trim = if (sum <= 1f) 1f else 1f / kotlin.math.sqrt(sum)
                    primaryPlayer.volume = (gainOut * trim * baseVolume).coerceIn(0f, 1f)
                    secondaryPlayer.volume = (gainIn * trim * baseVolume).coerceIn(0f, 1f)
                }

                // Dominant energy handoff point (65% through transition window):
                if (progress >= 0.65f && !swapped) {
                    swapped = true
                    runCatching {
                        onPerformDeckSwap?.invoke(primaryPlayer, secondaryPlayer)
                    }.onFailure { e ->
                        android.util.Log.e("DualDeckCoordinator", "Error during deck swap", e)
                    }
                }

                if (progress >= 1.0f) break
                delay(20L)
            }

            primaryPlayer.volume = 0.0f
            secondaryPlayer.volume = baseVolume
            runCatching { primaryPlayer.setPlaybackSpeed(1.0f) }
            runCatching { secondaryPlayer.setPlaybackSpeed(1.0f) }
            runCatching { primaryPlayer.pause() }
            resetProcessorsAfterBlend()

            _telemetry.update {
                it.copy(
                    phase = BlendPhase.MIX_COMPLETED,
                    blendProgress = 1.0f,
                    activeTempoRatio = 1.0f,
                )
            }

            runCatching { onTransitionComplete?.invoke() }

            // Display MIXED for 1,500 ms before settling to IDLE
            delay(1_500L)
            _telemetry.update { DeckBlendTelemetry.IDLE.copy(activeTier = currentSettings.tier) }
            currentSplicePlan = null
            currentSmartPlan = null
            currentPrimaryPlayer = null
            currentSecondaryPlayer = null
        }
    }

    override fun cancelTransition(revertToPrimary: Boolean) {
        activeSpliceJob?.cancel()
        activeSpliceJob = null
        currentSplicePlan = null
        currentSmartPlan = null
        immediatePlan = false
        resetProcessorsAfterBlend()
        _telemetry.update { DeckBlendTelemetry.IDLE.copy(activeTier = currentSettings.tier) }
        if (revertToPrimary) {
            runCatching {
                currentPrimaryPlayer?.setPlaybackSpeed(1.0f)
                currentSecondaryPlayer?.setPlaybackSpeed(1.0f)
                currentPrimaryPlayer?.volume = baseVolume
                currentSecondaryPlayer?.volume = 0.0f
                currentSecondaryPlayer?.pause()
            }
        }
        currentPrimaryPlayer = null
        currentSecondaryPlayer = null
    }

    override fun forceCommitTransition() {
        activeSpliceJob?.cancel()
        activeSpliceJob = null
        resetProcessorsAfterBlend()
        val primary = currentPrimaryPlayer
        val secondary = currentSecondaryPlayer
        if (primary != null && secondary != null) {
            runCatching {
                onPerformDeckSwap?.invoke(primary, secondary)
                primary.setPlaybackSpeed(1.0f)
                secondary.setPlaybackSpeed(1.0f)
                primary.volume = 0.0f
                secondary.volume = baseVolume
                primary.pause()
                onTransitionComplete?.invoke()
            }
        }
        _telemetry.update {
            it.copy(
                phase = BlendPhase.MIX_COMPLETED,
                blendProgress = 1.0f,
                activeTempoRatio = 1.0f,
            )
        }
        scope.launch {
            delay(1_500L)
            _telemetry.update { DeckBlendTelemetry.IDLE.copy(activeTier = currentSettings.tier) }
            currentSplicePlan = null
            currentSmartPlan = null
            currentPrimaryPlayer = null
            currentSecondaryPlayer = null
        }
    }

    override fun triggerImmediateTransition(customDurationMs: Long?) {
        val duration = customDurationMs ?: (currentSettings.targetDurationSeconds * 1000L)
        val plan = SplicePlan(
            cueOutStartMs = 0L,
            cueInStartMs = 0L,
            durationMs = duration,
            transitionStyle = TransitionStyle.HARMONIC_BEATMIX,
        )
        currentSplicePlan = plan
        immediatePlan = true
    }

    override fun handleSeekEvent(targetPositionMs: Long) {
        val plan = currentSplicePlan ?: return
        if (_telemetry.value.phase == BlendPhase.BLENDING || _telemetry.value.phase == BlendPhase.LOOP_LOCKED) {
            if (targetPositionMs < plan.cueOutStartMs) {
                // Scrubbing before splice start: cancel incoming blend and restore primary to unity
                cancelTransition(revertToPrimary = true)
            } else if (targetPositionMs >= plan.cueOutStartMs + (plan.durationMs * 0.65f)) {
                // Scrubbing past dominant handoff: immediately commit transition
                forceCommitTransition()
            }
        }
    }

    override fun updateHardwareBypassState(isHardwareBypassActive: Boolean) {
        isHardwareBypassEngaged = isHardwareBypassActive
        if (isHardwareBypassActive) {
            cancelTransition(revertToPrimary = true)
        }
    }

    fun setJamRoomActive(active: Boolean) {
        isJamRoomActive = active
        if (active) {
            cancelTransition(revertToPrimary = true)
        }
    }

    private fun lookupCachedSignature(track: PlayableTrack, durationMs: Long): AcousticSignature? {
        val trackId = track.internalTrackId?.takeIf { it.isNotBlank() }
            ?: track.videoId
            ?: "${track.artist}:${track.title}"
        val sourceHash = (track.playbackUrl?.hashCode() ?: 0).toString()
        return signatureStore.getSignature(trackId, durationMs, sourceHash)
    }

    private fun syncLiveDjToProcessors(state: LiveDjControlState) {
        outgoingCrossoverProcessor?.applyManualDjParams(
            lowSwap = state.eqLowSwap,
            midSwap = state.eqMidSwap,
            highSwap = state.eqHighSwap,
            tempoRatio = 1.0f,
            filterSweepEnabled = state.filterSweepEnabled,
        )
        incomingCrossoverProcessor?.applyManualDjParams(
            lowSwap = state.eqLowSwap,
            midSwap = state.eqMidSwap,
            highSwap = state.eqHighSwap,
            tempoRatio = if (state.bpmMatchEnabled) state.tempoRatio else 1.0f,
            filterSweepEnabled = state.filterSweepEnabled,
        )
    }

    private fun resetProcessorsAfterBlend() {
        outgoingCrossoverProcessor?.apply {
            isEnabled = false
            resetManualDjParams()
        }
        incomingCrossoverProcessor?.apply {
            isEnabled = false
            resetManualDjParams()
        }
    }
}
