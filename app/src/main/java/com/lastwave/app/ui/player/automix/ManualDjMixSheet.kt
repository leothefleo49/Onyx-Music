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
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
    val cueOutMs: Long = 0L,
    val cueInMs: Long = 0L,
    val filterSweepEnabled: Boolean = true,
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
        filterSweepEnabled = filterSweepEnabled,
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
                            hasSavedRecipe = true,
                        )
                    }
                    pushLiveParameters()
                }
            }
        }
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
        _uiState.update { it.copy(bpmMatchEnabled = enabled, statusBanner = null) }
        pushLiveParameters()
    }

    fun setTempoRatio(ratio: Float) {
        val clamped = ratio.coerceIn(
            TransitionOverrideEntity.MIN_MANUAL_TEMPO_RATIO,
            TransitionOverrideEntity.MAX_MANUAL_TEMPO_RATIO,
        )
        _uiState.update { it.copy(targetTempoRatio = clamped, statusBanner = null) }
        pushLiveParameters()
    }

    fun setDurationSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(
            TransitionOverrideEntity.MIN_DURATION_SEC,
            TransitionOverrideEntity.MAX_DURATION_SEC,
        )
        _uiState.update { it.copy(transitionDurationSec = clamped, statusBanner = null) }
        pushLiveParameters()
    }

    fun setCurveType(curve: CrossfadeCurveType) {
        _uiState.update { it.copy(curveType = curve, statusBanner = null) }
        pushLiveParameters()
    }

    fun setEqSwap(low: Float? = null, mid: Float? = null, high: Float? = null) {
        _uiState.update {
            it.copy(
                eqLowSwap = (low ?: it.eqLowSwap).coerceIn(0f, 1f),
                eqMidSwap = (mid ?: it.eqMidSwap).coerceIn(0f, 1f),
                eqHighSwap = (high ?: it.eqHighSwap).coerceIn(0f, 1f),
                statusBanner = null,
            )
        }
        pushLiveParameters()
    }

    fun setFilterSweepEnabled(enabled: Boolean) {
        _uiState.update { it.copy(filterSweepEnabled = enabled, statusBanner = null) }
        pushLiveParameters()
    }

    fun setCueOutMs(cueOutMs: Long) {
        _uiState.update { it.copy(cueOutMs = cueOutMs.coerceAtLeast(0L), statusBanner = null) }
    }

    fun setCueInMs(cueInMs: Long) {
        _uiState.update { it.copy(cueInMs = cueInMs.coerceAtLeast(0L), statusBanner = null) }
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
                cueOutMs = 0L,
                cueInMs = 0L,
                filterSweepEnabled = true,
                hasSavedRecipe = false,
                statusBanner = null,
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

    private fun pushLiveParameters() {
        val s = _uiState.value
        coordinator.applyLiveDjParameters(
            eqLowSwap = s.eqLowSwap,
            eqMidSwap = s.eqMidSwap,
            eqHighSwap = s.eqHighSwap,
            tempoRatio = s.targetTempoRatio,
            filterSweepEnabled = s.filterSweepEnabled,
            curveType = s.curveType,
            transitionDurationSec = s.transitionDurationSec,
            bpmMatchEnabled = s.bpmMatchEnabled,
        )
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
                )
                Text(
                    text = stringResource(R.string.dj_studio_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

        // 2. Current Transition Pair Header
        TransitionPairSummaryRow(
            fromTrack = ui.fromTrack,
            toTrack = ui.toTrack,
            telemetry = telemetry,
        )

        // 3. Tempo & BPM Match
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
                            text = stringResource(R.string.dj_studio_tempo_ratio),
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
                        valueRange = TransitionOverrideEntity.MIN_SMART_TEMPO_RATIO..TransitionOverrideEntity.MAX_SMART_TEMPO_RATIO,
                    )
                }
            }
        }

        // 4. Transition Duration (4s - 32s)
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(8, 12, 16, 24).forEach { preset ->
                        val selected = ui.transitionDurationSec == preset
                        Surface(
                            onClick = { viewModel.setDurationSeconds(preset) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            modifier = Modifier.weight(1f).height(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "${preset}s",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
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

        // 6. 3-Band EQ Swap Faders (Low / Mid / High) + Filter Sweep
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
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.dj_studio_filter_sweep),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Switch(
                            checked = ui.filterSweepEnabled,
                            onCheckedChange = viewModel::setFilterSweepEnabled,
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

        // 7. Cue-Out / Cue-In Trimmers
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
                    )
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
