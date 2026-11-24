package com.lastwave.app.ui.player.automix

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lastwave.app.R
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.automix.api.AutoMixPreferences
import com.lastwave.app.playback.automix.api.AutoMixSettings
import com.lastwave.app.playback.automix.api.AutoMixTier
import com.lastwave.app.playback.automix.api.CrossfadeCurveEvaluator
import com.lastwave.app.playback.automix.api.CrossfadeCurveType
import com.lastwave.app.playback.automix.api.DeckBlendTelemetry
import com.lastwave.app.playback.automix.data.RemixPresetRepository
import com.lastwave.app.playback.automix.data.TransitionOverrideEntity
import com.lastwave.app.playback.automix.engine.DualDeckTransportCoordinator
import com.lastwave.app.playback.automix.engine.FilterSweepMode
import com.lastwave.app.playback.previewManualDjTransition
import com.lastwave.app.ui.theme.LiquidGlassPreset
import com.lastwave.app.ui.theme.liquidGlassChrome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

enum class DjStylePreset(
    val label: String,
    val subtitle: String,
    val durationSec: Int,
    val curveType: CrossfadeCurveType,
    val eqLowSwap: Float,
    val eqMidSwap: Float,
    val eqHighSwap: Float,
    val filterSweepMode: FilterSweepMode,
    val bpmMatch: Boolean,
) {
    CLUB_BASS_SWAP(
        label = "Club Bass Swap",
        subtitle = "16s · Punchy bass switch",
        durationSec = 16,
        curveType = CrossfadeCurveType.EQUAL_POWER,
        eqLowSwap = 0.85f,
        eqMidSwap = 0.50f,
        eqHighSwap = 0.35f,
        filterSweepMode = FilterSweepMode.HPF_OUT,
        bpmMatch = true,
    ),
    FILTER_WASH(
        label = "Filter Wash",
        subtitle = "14s · Dual HPF/LPF wash",
        durationSec = 14,
        curveType = CrossfadeCurveType.S_CURVE,
        eqLowSwap = 0.50f,
        eqMidSwap = 0.50f,
        eqHighSwap = 0.50f,
        filterSweepMode = FilterSweepMode.DUAL_SWEEP,
        bpmMatch = true,
    ),
    RADIO_QUICK_CUT(
        label = "Radio Quick Cut",
        subtitle = "4s · Tight drop-in cut",
        durationSec = 4,
        curveType = CrossfadeCurveType.CUT_IN,
        eqLowSwap = 0.50f,
        eqMidSwap = 0.50f,
        eqHighSwap = 0.50f,
        filterSweepMode = FilterSweepMode.OFF,
        bpmMatch = false,
    ),
    LONG_HARMONIC(
        label = "Long Harmonic",
        subtitle = "28s · Extended smooth blend",
        durationSec = 28,
        curveType = CrossfadeCurveType.S_CURVE,
        eqLowSwap = 0.55f,
        eqMidSwap = 0.50f,
        eqHighSwap = 0.45f,
        filterSweepMode = FilterSweepMode.DUAL_SWEEP,
        bpmMatch = true,
    ),
    VOCAL_SAFE(
        label = "Vocal-Safe",
        subtitle = "16s · Clears outgoing mids",
        durationSec = 16,
        curveType = CrossfadeCurveType.EQUAL_POWER,
        eqLowSwap = 0.55f,
        eqMidSwap = 0.25f,
        eqHighSwap = 0.50f,
        filterSweepMode = FilterSweepMode.HPF_OUT,
        bpmMatch = true,
    ),
}

@Immutable
data class ManualDjUiState(
    val fromTrack: PlayableTrack? = null,
    val toTrack: PlayableTrack? = null,
    val fromTrackKey: String = "",
    val toTrackKey: String = "",
    val bpmMatchEnabled: Boolean = true,
    val targetTempoRatio: Float = 1.0f,
    val transitionDurationSec: Int = 12,
    val curveType: CrossfadeCurveType = CrossfadeCurveType.EQUAL_POWER,
    val eqLowSwap: Float = 0.5f,
    val eqMidSwap: Float = 0.5f,
    val eqHighSwap: Float = 0.5f,
    val killLow: Boolean = false,
    val killMid: Boolean = false,
    val killHigh: Boolean = false,
    val filterSweepMode: FilterSweepMode = FilterSweepMode.DUAL_SWEEP,
    val outgoingGainTrimDb: Float = 0.0f,
    val incomingGainTrimDb: Float = 0.0f,
    val cueOutMs: Long = 0L,
    val cueInMs: Long = 0L,
    val filterSweepEnabled: Boolean = true,
    val activePreset: DjStylePreset? = null,
    val hasSavedRecipe: Boolean = false,
    val statusBanner: String? = null,
) {
    fun toEntity(): TransitionOverrideEntity = TransitionOverrideEntity(
        fromTrackKey = fromTrackKey,
        toTrackKey = toTrackKey,
        bpmMatchEnabled = bpmMatchEnabled,
        targetTempoRatio = targetTempoRatio,
        transitionDurationSec = transitionDurationSec,
        curveType = curveType.name,
        eqLowSwap = eqLowSwap,
        eqMidSwap = eqMidSwap,
        eqHighSwap = eqHighSwap,
        cueOutMs = cueOutMs,
        cueInMs = cueInMs,
        filterSweepEnabled = filterSweepMode != FilterSweepMode.OFF,
    ).normalized()
}

@HiltViewModel
class ManualDjMixViewModel @Inject constructor(
    private val musicPlayer: MusicPlayer,
    private val autoMixPreferences: AutoMixPreferences,
    private val coordinator: DualDeckTransportCoordinator,
    private val remixPresetRepository: RemixPresetRepository,
) : ViewModel() {

    val autoMixSettings: StateFlow<AutoMixSettings> = autoMixPreferences.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AutoMixSettings(),
    )

    val telemetry: StateFlow<DeckBlendTelemetry> = coordinator.telemetry

    val savedRecipes: StateFlow<List<TransitionOverrideEntity>> = remixPresetRepository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    private val _uiState = MutableStateFlow(ManualDjUiState())
    val uiState: StateFlow<ManualDjUiState> = _uiState.asStateFlow()

    fun bindPair(fromTrack: PlayableTrack? = null, toTrack: PlayableTrack? = null) {
        val playerState = musicPlayer.state.value
        val resolvedFrom = fromTrack ?: playerState.current
        val resolvedTo = toTrack ?: playerState.queue.getOrNull(playerState.currentIndex + 1)

        val fromKey = resolvedFrom?.let(RemixPresetRepository::canonicalTrackKey).orEmpty()
        val toKey = resolvedTo?.let(RemixPresetRepository::canonicalTrackKey).orEmpty()

        _uiState.update {
            it.copy(
                fromTrack = resolvedFrom,
                toTrack = resolvedTo,
                fromTrackKey = fromKey,
                toTrackKey = toKey,
                statusBanner = null,
            )
        }

        if (fromKey.isNotEmpty() && toKey.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                val saved = remixPresetRepository.getOverrideByKeys(fromKey, toKey)
                if (saved != null) {
                    _uiState.update {
                        it.copy(
                            bpmMatchEnabled = saved.bpmMatchEnabled,
                            targetTempoRatio = saved.targetTempoRatio,
                            transitionDurationSec = saved.transitionDurationSec,
                            curveType = CrossfadeCurveType.fromNameOrDefault(saved.curveType),
                            eqLowSwap = saved.eqLowSwap,
                            eqMidSwap = saved.eqMidSwap,
                            eqHighSwap = saved.eqHighSwap,
                            cueOutMs = saved.cueOutMs,
                            cueInMs = saved.cueInMs,
                            filterSweepEnabled = saved.filterSweepEnabled,
                            filterSweepMode = if (saved.filterSweepEnabled) FilterSweepMode.DUAL_SWEEP else FilterSweepMode.OFF,
                            activePreset = null,
                            hasSavedRecipe = true,
                        )
                    }
                    pushLiveParameters()
                }
            }
        }
    }

    fun applyPreset(preset: DjStylePreset) {
        _uiState.update {
            it.copy(
                bpmMatchEnabled = preset.bpmMatch,
                transitionDurationSec = preset.durationSec,
                curveType = preset.curveType,
                eqLowSwap = preset.eqLowSwap,
                eqMidSwap = preset.eqMidSwap,
                eqHighSwap = preset.eqHighSwap,
                filterSweepMode = preset.filterSweepMode,
                filterSweepEnabled = preset.filterSweepMode != FilterSweepMode.OFF,
                activePreset = preset,
                statusBanner = "${preset.label} preset loaded",
            )
        }
        pushLiveParameters()
    }

    fun setAutoMixEnabled(enabled: Boolean) {
        viewModelScope.launch(NonCancellable + Dispatchers.IO) {
            autoMixPreferences.setAutoMixEnabled(enabled)
        }
    }

    fun selectTier(tier: AutoMixTier) {
        coordinator.setActiveTier(tier)
        viewModelScope.launch(NonCancellable + Dispatchers.IO) {
            autoMixPreferences.setAutoMixEnabled(true)
            autoMixPreferences.setAutoMixTier(tier)
        }
    }

    fun setBpmMatchEnabled(enabled: Boolean) {
        _uiState.update { it.copy(bpmMatchEnabled = enabled, activePreset = null, statusBanner = null) }
        pushLiveParameters()
    }

    fun setTempoRatio(ratio: Float) {
        val clamped = ratio.coerceIn(
            TransitionOverrideEntity.MIN_MANUAL_TEMPO_RATIO,
            TransitionOverrideEntity.MAX_MANUAL_TEMPO_RATIO,
        )
        _uiState.update { it.copy(targetTempoRatio = clamped, activePreset = null, statusBanner = null) }
        pushLiveParameters()
    }

    fun nudgeTempoRatio(delta: Float) {
        setTempoRatio(_uiState.value.targetTempoRatio + delta)
    }

    fun setDurationSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(
            TransitionOverrideEntity.MIN_DURATION_SEC,
            TransitionOverrideEntity.MAX_DURATION_SEC,
        )
        _uiState.update { it.copy(transitionDurationSec = clamped, activePreset = null, statusBanner = null) }
        pushLiveParameters()
    }

    fun setDurationBars(bars: Int) {
        val bpm = (telemetry.value.syncedBpm ?: 124f).coerceIn(60f, 200f)
        val seconds = ((bars * 4f * 60f) / bpm).roundToInt()
            .coerceIn(TransitionOverrideEntity.MIN_DURATION_SEC, TransitionOverrideEntity.MAX_DURATION_SEC)
        setDurationSeconds(seconds)
        _uiState.update { it.copy(statusBanner = "$bars bars @ ${bpm.roundToInt()} BPM = ${seconds}s") }
    }

    fun setCurveType(curve: CrossfadeCurveType) {
        _uiState.update { it.copy(curveType = curve, activePreset = null, statusBanner = null) }
        pushLiveParameters()
    }

    fun setEqSwap(low: Float? = null, mid: Float? = null, high: Float? = null) {
        _uiState.update {
            it.copy(
                eqLowSwap = (low ?: it.eqLowSwap).coerceIn(0f, 1f),
                eqMidSwap = (mid ?: it.eqMidSwap).coerceIn(0f, 1f),
                eqHighSwap = (high ?: it.eqHighSwap).coerceIn(0f, 1f),
                activePreset = null,
                statusBanner = null,
            )
        }
        pushLiveParameters()
    }

    fun toggleEqKill(low: Boolean? = null, mid: Boolean? = null, high: Boolean? = null) {
        _uiState.update {
            it.copy(
                killLow = low ?: it.killLow,
                killMid = mid ?: it.killMid,
                killHigh = high ?: it.killHigh,
                statusBanner = null,
            )
        }
        val s = _uiState.value
        coordinator.setEqKills(s.killLow, s.killMid, s.killHigh)
    }

    fun toggleBassSwapPunch() {
        val nextKillLow = !_uiState.value.killLow
        _uiState.update {
            it.copy(
                killLow = nextKillLow,
                eqLowSwap = if (nextKillLow) 0.90f else 0.50f,
                statusBanner = if (nextKillLow) "Bass Swap punch active (Outgoing low cut)" else "Bass Swap restored",
            )
        }
        pushLiveParameters()
    }

    fun setFilterSweepMode(mode: FilterSweepMode) {
        _uiState.update {
            it.copy(
                filterSweepMode = mode,
                filterSweepEnabled = mode != FilterSweepMode.OFF,
                activePreset = null,
                statusBanner = null,
            )
        }
        pushLiveParameters()
    }

    fun setFilterSweepEnabled(enabled: Boolean) {
        setFilterSweepMode(if (enabled) FilterSweepMode.DUAL_SWEEP else FilterSweepMode.OFF)
    }

    fun setDeckGainTrims(outgoingDb: Float? = null, incomingDb: Float? = null) {
        _uiState.update {
            it.copy(
                outgoingGainTrimDb = (outgoingDb ?: it.outgoingGainTrimDb).coerceIn(-6f, 6f),
                incomingGainTrimDb = (incomingDb ?: it.incomingGainTrimDb).coerceIn(-6f, 6f),
                statusBanner = null,
            )
        }
        val s = _uiState.value
        coordinator.setDeckGainTrims(s.outgoingGainTrimDb, s.incomingGainTrimDb)
    }

    fun setCueOutMs(cueOutMs: Long) {
        _uiState.update { it.copy(cueOutMs = cueOutMs.coerceAtLeast(0L), statusBanner = null) }
    }

    fun setCueOutAtCurrentPosition() {
        val posMs = musicPlayer.progressState.value.positionMs.coerceAtLeast(0L)
        _uiState.update {
            it.copy(
                cueOutMs = posMs,
                statusBanner = "Cue-Out set to current position (${formatMillis(posMs)})",
            )
        }
    }

    fun nudgeCueOutMs(deltaMs: Long) {
        val maxOutMs = (_uiState.value.fromTrack?.durationMs ?: 210_000L).coerceAtLeast(30_000L)
        setCueOutMs((_uiState.value.cueOutMs + deltaMs).coerceIn(0L, maxOutMs))
    }

    fun setCueInMs(cueInMs: Long) {
        _uiState.update { it.copy(cueInMs = cueInMs.coerceAtLeast(0L), statusBanner = null) }
    }

    fun nudgeCueInMs(deltaMs: Long) {
        val maxInMs = minOf(60_000L, (_uiState.value.toTrack?.durationMs ?: 180_000L) / 2L).coerceAtLeast(10_000L)
        setCueInMs((_uiState.value.cueInMs + deltaMs).coerceIn(0L, maxInMs))
    }

    fun saveRecipe(savedMessage: String) {
        val current = _uiState.value
        if (current.fromTrackKey.isBlank() || current.toTrackKey.isBlank()) return
        val entity = current.toEntity()
        viewModelScope.launch(NonCancellable + Dispatchers.IO) {
            remixPresetRepository.saveOverride(entity)
            _uiState.update { it.copy(hasSavedRecipe = true, statusBanner = savedMessage) }
        }
    }

    fun deleteSavedRecipe(recipe: TransitionOverrideEntity) {
        viewModelScope.launch(NonCancellable + Dispatchers.IO) {
            remixPresetRepository.deleteOverride(recipe.fromTrackKey, recipe.toTrackKey)
            _uiState.update { s ->
                if (s.fromTrackKey == recipe.fromTrackKey && s.toTrackKey == recipe.toTrackKey) {
                    s.copy(hasSavedRecipe = false, statusBanner = "Saved DJ recipe removed")
                } else {
                    s.copy(statusBanner = "Saved DJ recipe removed")
                }
            }
        }
    }

    fun resetRecipe() {
        val current = _uiState.value
        _uiState.update {
            it.copy(
                bpmMatchEnabled = true,
                targetTempoRatio = 1.0f,
                transitionDurationSec = 12,
                curveType = CrossfadeCurveType.EQUAL_POWER,
                eqLowSwap = 0.5f,
                eqMidSwap = 0.5f,
                eqHighSwap = 0.5f,
                killLow = false,
                killMid = false,
                killHigh = false,
                filterSweepMode = FilterSweepMode.DUAL_SWEEP,
                outgoingGainTrimDb = 0.0f,
                incomingGainTrimDb = 0.0f,
                cueOutMs = 0L,
                cueInMs = 0L,
                filterSweepEnabled = true,
                activePreset = null,
                hasSavedRecipe = false,
                statusBanner = "DJ mixer reset to defaults",
            )
        }
        pushLiveParameters()
        if (current.fromTrackKey.isNotBlank() && current.toTrackKey.isNotBlank()) {
            viewModelScope.launch(NonCancellable + Dispatchers.IO) {
                remixPresetRepository.deleteOverride(current.fromTrackKey, current.toTrackKey)
            }
        }
    }

    fun triggerLivePreview(startedMessage: String, unavailableMessage: String) {
        val current = _uiState.value
        val entity = current.toEntity()
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val started = musicPlayer.previewManualDjTransition(entity)
            _uiState.update {
                it.copy(statusBanner = if (started) startedMessage else unavailableMessage)
            }
        }
    }

    fun triggerDropCutNow(unavailableMessage: String) {
        val entity = _uiState.value.toEntity().copy(
            transitionDurationSec = TransitionOverrideEntity.MIN_DURATION_SEC,
            curveType = CrossfadeCurveType.CUT_IN.name,
            filterSweepEnabled = false,
        )
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val started = musicPlayer.previewManualDjTransition(entity)
            _uiState.update {
                it.copy(statusBanner = if (started) "Instant 4s Drop Cut triggered" else unavailableMessage)
            }
        }
    }

    fun abortLiveBlend() {
        coordinator.cancelTransition(revertToPrimary = true)
        _uiState.update {
            it.copy(
                killLow = false,
                killMid = false,
                killHigh = false,
                outgoingGainTrimDb = 0f,
                incomingGainTrimDb = 0f,
                statusBanner = "Live blend aborted — restored Deck A",
            )
        }
    }

    private fun pushLiveParameters() {
        val s = _uiState.value
        coordinator.applyLiveDjParameters(
            eqLowSwap = s.eqLowSwap,
            eqMidSwap = s.eqMidSwap,
            eqHighSwap = s.eqHighSwap,
            tempoRatio = s.targetTempoRatio,
            filterSweepEnabled = s.filterSweepMode != FilterSweepMode.OFF,
            curveType = s.curveType,
            transitionDurationSec = s.transitionDurationSec,
            bpmMatchEnabled = s.bpmMatchEnabled,
        )
        coordinator.setFilterSweepMode(s.filterSweepMode)
        coordinator.setEqKills(s.killLow, s.killMid, s.killHigh)
        coordinator.setDeckGainTrims(s.outgoingGainTrimDb, s.incomingGainTrimDb)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualDjMixSheet(
    onDismiss: () -> Unit,
    onOpenDeepMixBuilder: () -> Unit,
    fromTrack: PlayableTrack? = null,
    toTrack: PlayableTrack? = null,
    viewModel: ManualDjMixViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(fromTrack, toTrack) {
        viewModel.bindPair(fromTrack, toTrack)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        ManualDjMixSheetContent(
            viewModel = viewModel,
            onDismiss = onDismiss,
            onOpenDeepMixBuilder = {
                onDismiss()
                onOpenDeepMixBuilder()
            },
        )
    }
}

@Composable
fun ManualDjMixSheetContent(
    viewModel: ManualDjMixViewModel,
    onDismiss: () -> Unit,
    onOpenDeepMixBuilder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.autoMixSettings.collectAsStateWithLifecycle()
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val savedRecipes by viewModel.savedRecipes.collectAsStateWithLifecycle()

    val savedFeedback = stringResource(R.string.dj_studio_saved_feedback)
    val previewStarted = stringResource(R.string.dj_studio_preview_started)
    val previewUnavailable = stringResource(R.string.dj_studio_preview_unavailable)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.dj_studio_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.dj_studio_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Close DJ Remix Studio",
                )
            }
        }

        // 1. 3-Tier AutoMix Selector
        AutoMixTierSelectorCard(
            enabled = settings.autoMixEnabled,
            selectedTier = settings.tier,
            onToggleEnabled = viewModel::setAutoMixEnabled,
            onSelectTier = viewModel::selectTier,
            onOpenDeepMixBuilder = onOpenDeepMixBuilder,
        )

        // 2. Current Transition Pair Header + Live Deck Transport
        TransitionPairSummaryRow(
            fromTrack = ui.fromTrack,
            toTrack = ui.toTrack,
            telemetry = telemetry,
        )

        // 2B. Live Deck Actions (Mix Now / Drop Cut / Abort) + Blend Progress
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Live Deck Controls",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            telemetry.isBlendingActive -> "Blending ${(telemetry.clampedProgress * 100f).roundToInt()}%"
                            telemetry.isArmedOrBlending -> "Armed"
                            else -> "Ready"
                        },
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (telemetry.isArmedOrBlending) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                if (telemetry.isArmedOrBlending) {
                    LinearProgressIndicator(
                        progress = { telemetry.clampedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { viewModel.triggerLivePreview(previewStarted, previewUnavailable) },
                        modifier = Modifier.weight(1f).height(42.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Mix Now",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    FilledTonalButton(
                        onClick = { viewModel.triggerDropCutNow(previewUnavailable) },
                        modifier = Modifier.weight(1f).height(42.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FlashOn,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Drop Cut",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (telemetry.isArmedOrBlending) {
                        OutlinedButton(
                            onClick = viewModel::abortLiveBlend,
                            modifier = Modifier.height(42.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Stop,
                                contentDescription = "Abort blend",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }

        // 2C. One-Tap DJ Style Presets
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "DJ Style Presets",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DjStylePreset.entries.forEach { preset ->
                    val selected = ui.activePreset == preset
                    Surface(
                        onClick = { viewModel.applyPreset(preset) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        modifier = Modifier
                            .border(
                                width = 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(14.dp),
                            ),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = preset.label,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                            )
                            Text(
                                text = preset.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        // 3. Tempo & BPM Match (full +/-15% manual range + fine nudges)
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(R.string.dj_studio_tempo_match),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = ui.bpmMatchEnabled,
                        onCheckedChange = viewModel::setBpmMatchEnabled,
                    )
                }

                if (ui.bpmMatchEnabled) {
                    val deltaPct = ((ui.targetTempoRatio - 1.0f) * 100f)
                    val deltaText = String.format(Locale.US, "%+.1f%% (%.2fx)", deltaPct, ui.targetTempoRatio)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${stringResource(R.string.dj_studio_tempo_ratio)} (±15%)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = deltaText,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = ui.targetTempoRatio,
                        onValueChange = viewModel::setTempoRatio,
                        valueRange = TransitionOverrideEntity.MIN_MANUAL_TEMPO_RATIO..TransitionOverrideEntity.MAX_MANUAL_TEMPO_RATIO,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DjQuickChip(
                            label = "-1%",
                            selected = false,
                            onClick = { viewModel.nudgeTempoRatio(-0.01f) },
                            modifier = Modifier.weight(1f),
                        )
                        DjQuickChip(
                            label = "1.00x Reset",
                            selected = kotlin.math.abs(ui.targetTempoRatio - 1.0f) < 0.005f,
                            onClick = { viewModel.setTempoRatio(1.0f) },
                            modifier = Modifier.weight(1.3f),
                        )
                        DjQuickChip(
                            label = "+1%",
                            selected = false,
                            onClick = { viewModel.nudgeTempoRatio(0.01f) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // 4. Transition Duration (4s - 32s + Musical Bar Lengths)
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.dj_studio_transition_duration),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${ui.transitionDurationSec}s",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = ui.transitionDurationSec.toFloat(),
                    onValueChange = { viewModel.setDurationSeconds(it.roundToInt()) },
                    valueRange = TransitionOverrideEntity.MIN_DURATION_SEC.toFloat()..TransitionOverrideEntity.MAX_DURATION_SEC.toFloat(),
                    steps = 27,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(4, 8, 12, 16, 24).forEach { preset ->
                        DjQuickChip(
                            label = "${preset}s",
                            selected = ui.transitionDurationSec == preset,
                            onClick = { viewModel.setDurationSeconds(preset) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text(
                    text = "Musical Bar Length (${(telemetry.syncedBpm ?: 124f).roundToInt()} BPM)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(4, 8, 16, 32).forEach { bars ->
                        DjQuickChip(
                            label = "$bars Bars",
                            selected = false,
                            onClick = { viewModel.setDurationBars(bars) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // 5. Crossfade Curve Selector with mini Canvas preview
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.dj_studio_crossfade_curve),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CrossfadeCurveType.entries.forEach { curve ->
                    CrossfadeCurveChip(
                        curveType = curve,
                        selected = ui.curveType == curve,
                        onClick = { viewModel.setCurveType(curve) },
                    )
                }
            }
        }

        // 6. 3-Band EQ Swap Faders, Instant Kill Switches & Filter Sweep Mode
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.dj_studio_eq_swap_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Surface(
                        onClick = viewModel::toggleBassSwapPunch,
                        shape = RoundedCornerShape(10.dp),
                        color = if (ui.killLow) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    ) {
                        Text(
                            text = if (ui.killLow) "Bass Swapped" else "Bass Swap",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (ui.killLow) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            maxLines = 1,
                        )
                    }
                }

                // Instant 3-Band EQ Kill Switches (Live DSP)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DjQuickChip(
                        label = if (ui.killLow) "LOW KILLED" else "Kill Low",
                        selected = ui.killLow,
                        onClick = { viewModel.toggleEqKill(low = !ui.killLow) },
                        modifier = Modifier.weight(1f),
                    )
                    DjQuickChip(
                        label = if (ui.killMid) "MID KILLED" else "Kill Mid",
                        selected = ui.killMid,
                        onClick = { viewModel.toggleEqKill(mid = !ui.killMid) },
                        modifier = Modifier.weight(1f),
                    )
                    DjQuickChip(
                        label = if (ui.killHigh) "HIGH KILLED" else "Kill High",
                        selected = ui.killHigh,
                        onClick = { viewModel.toggleEqKill(high = !ui.killHigh) },
                        modifier = Modifier.weight(1f),
                    )
                }

                // Filter Sweep Mode Selector
                Text(
                    text = stringResource(R.string.dj_studio_filter_sweep),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterSweepMode.entries.forEach { mode ->
                        DjQuickChip(
                            label = mode.label,
                            selected = ui.filterSweepMode == mode,
                            onClick = { viewModel.setFilterSweepMode(mode) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                EqSwapSliderRow(
                    label = stringResource(R.string.dj_studio_eq_low),
                    value = ui.eqLowSwap,
                    onValueChange = { viewModel.setEqSwap(low = it) },
                )
                EqSwapSliderRow(
                    label = stringResource(R.string.dj_studio_eq_mid),
                    value = ui.eqMidSwap,
                    onValueChange = { viewModel.setEqSwap(mid = it) },
                )
                EqSwapSliderRow(
                    label = stringResource(R.string.dj_studio_eq_high),
                    value = ui.eqHighSwap,
                    onValueChange = { viewModel.setEqSwap(high = it) },
                )
            }
        }

        // 6B. Live Deck Gain Trim (-6 dB to +6 dB)
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Deck Gain Trim",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (kotlin.math.abs(ui.outgoingGainTrimDb) > 0.05f || kotlin.math.abs(ui.incomingGainTrimDb) > 0.05f) {
                        TextButton(onClick = { viewModel.setDeckGainTrims(0f, 0f) }) {
                            Text("Reset 0 dB")
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Deck A (Outgoing)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = String.format(Locale.US, "%+.1f dB", ui.outgoingGainTrimDb),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = ui.outgoingGainTrimDb,
                    onValueChange = { viewModel.setDeckGainTrims(outgoingDb = it) },
                    valueRange = -6f..6f,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Deck B (Incoming)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = String.format(Locale.US, "%+.1f dB", ui.incomingGainTrimDb),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = ui.incomingGainTrimDb,
                    onValueChange = { viewModel.setDeckGainTrims(incomingDb = it) },
                    valueRange = -6f..6f,
                )
            }
        }

        // 7. Cue-Out / Cue-In Trimmers + Set Here & ±1s Nudges
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(R.string.dj_studio_cue_points),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Surface(
                        onClick = viewModel::setCueOutAtCurrentPosition,
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MyLocation,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "Set Cue-Out Here",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                            )
                        }
                    }
                }

                val maxOutMs = (ui.fromTrack?.durationMs ?: 210_000L).coerceAtLeast(30_000L)
                val maxInMs = minOf(60_000L, (ui.toTrack?.durationMs ?: 180_000L) / 2L).coerceAtLeast(10_000L)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.dj_studio_cue_out),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (ui.cueOutMs == 0L) "Auto" else formatMillis(ui.cueOutMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = ui.cueOutMs.coerceAtMost(maxOutMs).toFloat(),
                    onValueChange = { viewModel.setCueOutMs(it.toLong()) },
                    valueRange = 0f..maxOutMs.toFloat(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DjQuickChip(
                        label = "-1s",
                        selected = false,
                        onClick = { viewModel.nudgeCueOutMs(-1_000L) },
                        modifier = Modifier.weight(1f),
                    )
                    DjQuickChip(
                        label = "Auto",
                        selected = ui.cueOutMs == 0L,
                        onClick = { viewModel.setCueOutMs(0L) },
                        modifier = Modifier.weight(1f),
                    )
                    DjQuickChip(
                        label = "+1s",
                        selected = false,
                        onClick = { viewModel.nudgeCueOutMs(1_000L) },
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.dj_studio_cue_in),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (ui.cueInMs == 0L) "Auto" else formatMillis(ui.cueInMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = ui.cueInMs.coerceAtMost(maxInMs).toFloat(),
                    onValueChange = { viewModel.setCueInMs(it.toLong()) },
                    valueRange = 0f..maxInMs.toFloat(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DjQuickChip(
                        label = "-1s",
                        selected = false,
                        onClick = { viewModel.nudgeCueInMs(-1_000L) },
                        modifier = Modifier.weight(1f),
                    )
                    DjQuickChip(
                        label = "Auto",
                        selected = ui.cueInMs == 0L,
                        onClick = { viewModel.setCueInMs(0L) },
                        modifier = Modifier.weight(1f),
                    )
                    DjQuickChip(
                        label = "+1s",
                        selected = false,
                        onClick = { viewModel.nudgeCueInMs(1_000L) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Status feedback banner
        ui.statusBanner?.let { banner ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = banner,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        // 8. Action Row: Preview, Save, Reset
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { viewModel.triggerLivePreview(previewStarted, previewUnavailable) },
                modifier = Modifier
                    .weight(1.3f)
                    .height(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.dj_studio_preview_button),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            FilledTonalButton(
                onClick = { viewModel.saveRecipe(savedFeedback) },
                enabled = ui.fromTrackKey.isNotBlank() && ui.toTrackKey.isNotBlank(),
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Save,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.dj_studio_save_button),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(
                onClick = viewModel::resetRecipe,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.dj_studio_reset_button),
                )
            }
        }

        // 9. Saved Custom DJ Recipes Manager
        if (savedRecipes.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Saved Transition Recipes (${savedRecipes.size})",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                    savedRecipes.take(8).forEach { recipe ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${formatTrackKeyLabel(recipe.fromTrackKey)} → ${formatTrackKeyLabel(recipe.toTrackKey)}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${recipe.transitionDurationSec}s · ${CrossfadeCurveType.fromNameOrDefault(recipe.curveType).name.lowercase().replace('_', ' ')} · ${String.format(Locale.US, "%.2fx", recipe.targetTempoRatio)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = { viewModel.deleteSavedRecipe(recipe) },
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete saved recipe",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DjQuickChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = modifier.height(34.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatTrackKeyLabel(key: String): String {
    return when {
        key.startsWith("ta:") -> key.removePrefix("ta:").replace("|", " – ")
        key.startsWith("vid:") -> "Track ${key.removePrefix("vid:").take(8)}"
        key.startsWith("id:") -> "Track ${key.removePrefix("id:").take(8)}"
        else -> key
    }
}

@Composable
private fun AutoMixTierSelectorCard(
    enabled: Boolean,
    selectedTier: AutoMixTier,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectTier: (AutoMixTier) -> Unit,
    onOpenDeepMixBuilder: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.automix_tier_section_title),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Switch(checked = enabled, onCheckedChange = onToggleEnabled)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AutoMixTier.entries.forEach { tier ->
                    val isSelected = selectedTier == tier
                    val label = when (tier) {
                        AutoMixTier.CLASSIC -> stringResource(R.string.automix_tier_classic_title)
                        AutoMixTier.IMPROVED_SMART -> stringResource(R.string.automix_tier_smart_title)
                        AutoMixTier.DEEP_CURATED_MIX -> stringResource(R.string.automix_tier_deep_title)
                    }
                    Surface(
                        onClick = { onSelectTier(tier) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                ),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            val tierDesc = when (selectedTier) {
                AutoMixTier.CLASSIC -> stringResource(R.string.automix_tier_classic_desc)
                AutoMixTier.IMPROVED_SMART -> stringResource(R.string.automix_tier_smart_desc)
                AutoMixTier.DEEP_CURATED_MIX -> stringResource(R.string.automix_tier_deep_desc)
            }
            Text(
                text = tierDesc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = onOpenDeepMixBuilder,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = stringResource(R.string.dj_studio_open_deep_builder))
            }
        }
    }
}

@Composable
private fun TransitionPairSummaryRow(
    fromTrack: PlayableTrack?,
    toTrack: PlayableTrack?,
    telemetry: DeckBlendTelemetry,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (fromTrack == null || toTrack == null) {
            Text(
                text = stringResource(R.string.dj_studio_no_next_track),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp),
            )
        } else {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "${fromTrack.title}  ->  ${toTrack.title}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val metaParts = buildList {
                    add("${fromTrack.artist} -> ${toTrack.artist}")
                    telemetry.syncedBpm?.let { add("${it.roundToInt()} BPM") }
                    telemetry.harmonicKeyPair?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
                Text(
                    text = metaParts.joinToString(" \u00B7 "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CrossfadeCurveChip(
    curveType: CrossfadeCurveType,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val label = when (curveType) {
        CrossfadeCurveType.LINEAR -> stringResource(R.string.dj_studio_curve_linear)
        CrossfadeCurveType.EQUAL_POWER -> stringResource(R.string.dj_studio_curve_equal_power)
        CrossfadeCurveType.EXPONENTIAL -> stringResource(R.string.dj_studio_curve_exponential)
        CrossfadeCurveType.S_CURVE -> stringResource(R.string.dj_studio_curve_s_curve)
        CrossfadeCurveType.CUT_IN -> stringResource(R.string.dj_studio_curve_cut_in)
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.tertiary
    val borderColor = if (selected) primaryColor else MaterialTheme.colorScheme.outlineVariant

    Column(
        modifier = Modifier
            .width(96.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                else MaterialTheme.colorScheme.surfaceContainer,
            )
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
        ) {
            val steps = 20
            val outPath = Path()
            val inPath = Path()
            for (i in 0..steps) {
                val t = i.toFloat() / steps.toFloat()
                val pair = CrossfadeCurveEvaluator.evaluate(t, curveType)
                val x = t * size.width
                val yOut = size.height * (1f - pair.outgoingGain.coerceIn(0f, 1f))
                val yIn = size.height * (1f - pair.incomingGain.coerceIn(0f, 1f))
                if (i == 0) {
                    outPath.moveTo(x, yOut)
                    inPath.moveTo(x, yIn)
                } else {
                    outPath.lineTo(x, yOut)
                    inPath.lineTo(x, yIn)
                }
            }
            drawPath(
                path = outPath,
                color = secondaryColor.copy(alpha = 0.85f),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
            drawPath(
                path = inPath,
                color = primaryColor,
                style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EqSwapSliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    val pct = ((value - 0.5f) * 200f).roundToInt()
    val readout = when {
        pct == 0 -> "Balanced"
        pct > 0 -> "Early +$pct%"
        else -> "Late $pct%"
    }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = readout,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..1f,
        )
    }
}

private fun formatMillis(ms: Long): String {
    val totalSec = (ms / 1_000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%d:%02d", mins, secs)
}
