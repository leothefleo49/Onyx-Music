package com.lastwave.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.MusicOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lastwave.app.R
import com.lastwave.app.data.spotify.SpotifyPreferences
import com.lastwave.app.data.spotify.mirror.MirrorPhase
import com.lastwave.app.data.spotify.mirror.SpotifyMirrorLogDao
import com.lastwave.app.data.spotify.mirror.SpotifyMirrorPreferences
import com.lastwave.app.data.spotify.mirror.SpotifyMirrorSettings
import com.lastwave.app.data.spotify.mirror.SpotifyMirrorState
import com.lastwave.app.data.spotify.mirror.SpotifySilentMirrorManager
import com.lastwave.app.ui.theme.ExpressivePillShape
import com.lastwave.app.ui.theme.LocalLiquidGlass
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class SpotifyMirrorViewModel @Inject constructor(
    private val spotifyPreferences: SpotifyPreferences,
    private val mirrorPreferences: SpotifyMirrorPreferences,
    private val mirrorManager: SpotifySilentMirrorManager,
    mirrorLogDao: SpotifyMirrorLogDao,
) : ViewModel() {

    private val sharing = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L)

    val isSpotifyConnected: StateFlow<Boolean> = spotifyPreferences.isConnected
        .catch { emit(false) }
        .stateIn(viewModelScope, sharing, false)

    val settings: StateFlow<SpotifyMirrorSettings> = mirrorPreferences.settings
        .catch { emit(SpotifyMirrorSettings()) }
        .stateIn(viewModelScope, sharing, SpotifyMirrorSettings())

    val mirrorState: StateFlow<SpotifyMirrorState> = mirrorManager.state

    val catalogPlayCount: StateFlow<Int> = mirrorLogDao.observeCatalogPlayCount()
        .catch { emit(0) }
        .stateIn(viewModelScope, sharing, 0)

    val localOnlyPlayCount: StateFlow<Int> = mirrorLogDao.observeLocalOnlyPlayCount()
        .catch { emit(0) }
        .stateIn(viewModelScope, sharing, 0)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()

    init {
        mirrorManager.ensureObserving()
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && !isSpotifyConnected.value) return@launch
            mirrorManager.ensureObserving()
            mirrorPreferences.setEnabled(enabled)
        }
    }

    fun setPauseWhenExternalDeviceActive(enabled: Boolean) {
        viewModelScope.launch {
            mirrorPreferences.setPauseWhenExternalDeviceActive(enabled)
        }
    }

    fun setMinMatchConfidence(threshold: Float) {
        viewModelScope.launch {
            mirrorPreferences.setMinMatchConfidence(threshold)
        }
    }

    fun rematchCurrentTrack() {
        viewModelScope.launch {
            _busy.value = true
            _feedback.value = null
            try {
                mirrorManager.rematchCurrentTrack()
            } finally {
                _busy.value = false
            }
        }
    }

    fun applyManualUriOverride(
        rawUriOrUrl: String,
        successMessage: String,
        invalidMessage: String,
        onApplied: () -> Unit,
    ) {
        viewModelScope.launch {
            _busy.value = true
            _feedback.value = null
            try {
                val ok = mirrorManager.applyManualUriOverride(rawUriOrUrl)
                if (ok) {
                    _feedback.value = successMessage
                    onApplied()
                } else {
                    _feedback.value = invalidMessage
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun markCurrentLocalOnly() {
        viewModelScope.launch {
            _busy.value = true
            _feedback.value = null
            try {
                mirrorManager.markCurrentTrackLocalOnly()
            } finally {
                _busy.value = false
            }
        }
    }

    fun reconnectDevice() {
        viewModelScope.launch {
            _busy.value = true
            _feedback.value = null
            try {
                mirrorManager.reconnect()
            } finally {
                _busy.value = false
            }
        }
    }

    fun clearMatchCache(clearedMessage: String) {
        viewModelScope.launch {
            _busy.value = true
            try {
                mirrorManager.clearMatchCache()
                _feedback.value = clearedMessage
            } finally {
                _busy.value = false
            }
        }
    }

    fun clearFeedback() {
        _feedback.value = null
    }
}

@Composable
internal fun mirrorStatusHeadline(state: SpotifyMirrorState): String = when (state.phase) {
    MirrorPhase.DISABLED -> stringResource(R.string.spotify_mirror_status_off)
    MirrorPhase.NOT_SIGNED_IN -> stringResource(R.string.spotify_mirror_status_not_signed_in)
    MirrorPhase.CONNECTING -> stringResource(R.string.spotify_mirror_status_connecting, state.deviceName)
    MirrorPhase.CONNECTED_IDLE -> stringResource(R.string.spotify_mirror_status_connected, state.deviceName)
    MirrorPhase.MIRRORING -> stringResource(R.string.spotify_mirror_status_mirroring, state.deviceName)
    MirrorPhase.PAUSED_LOCAL_ONLY -> stringResource(R.string.spotify_mirror_status_local_only)
    MirrorPhase.PAUSED_EXTERNAL_DEVICE -> if (!state.externalDeviceName.isNullOrBlank()) {
        stringResource(R.string.spotify_mirror_status_external_device_named, state.externalDeviceName)
    } else {
        stringResource(R.string.spotify_mirror_status_external_device)
    }
    MirrorPhase.PREMIUM_REQUIRED -> stringResource(R.string.spotify_mirror_status_premium_required)
    MirrorPhase.ERROR -> state.lastError?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.spotify_mirror_status_error)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyMirrorSettingsSheet(
    onDismiss: () -> Unit,
    viewModel: SpotifyMirrorViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isConnected by viewModel.isSpotifyConnected.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val mirrorState by viewModel.mirrorState.collectAsStateWithLifecycle()
    val catalogCount by viewModel.catalogPlayCount.collectAsStateWithLifecycle()
    val localOnlyCount by viewModel.localOnlyPlayCount.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    val useGlass = LocalLiquidGlass.current

    var showOverrideEditor by remember { mutableStateOf(false) }
    var overrideInput by remember { mutableStateOf("") }
    var sliderDraft by remember(settings.minMatchConfidence) {
        mutableFloatStateOf(settings.minMatchConfidence)
    }

    val containerColor = if (useGlass) {
        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = containerColor,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.spotify_mirror_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.spotify_mirror_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.spotify_mirror_close_cd),
                    )
                }
            }

            // Live Connect Status Pill
            MirrorLiveStatusBanner(
                state = mirrorState,
                busy = busy,
                onReconnect = if (settings.enabled && isConnected &&
                    (mirrorState.phase == MirrorPhase.ERROR || mirrorState.phase == MirrorPhase.CONNECTING)
                ) {
                    viewModel::reconnectDevice
                } else {
                    null
                },
            )

            // Primary Toggles Surface
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = if (useGlass) {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (settings.enabled) Icons.Filled.Devices else Icons.Outlined.Devices,
                            contentDescription = null,
                            tint = if (settings.enabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.spotify_mirror_enable_title),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = if (isConnected) {
                                    stringResource(
                                        R.string.spotify_mirror_enable_sub,
                                        mirrorState.deviceName,
                                    )
                                } else {
                                    stringResource(R.string.spotify_mirror_enable_need_signin)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = settings.enabled,
                            onCheckedChange = viewModel::setEnabled,
                            enabled = isConnected || settings.enabled,
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PauseCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.spotify_mirror_external_title),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = stringResource(R.string.spotify_mirror_external_sub),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = settings.pauseWhenExternalDeviceActive,
                            onCheckedChange = viewModel::setPauseWhenExternalDeviceActive,
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.spotify_mirror_confidence_title),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${(sliderDraft * 100f).roundToInt()}%",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            text = stringResource(R.string.spotify_mirror_confidence_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Slider(
                            value = sliderDraft,
                            onValueChange = { sliderDraft = it },
                            onValueChangeFinished = { viewModel.setMinMatchConfidence(sliderDraft) },
                            valueRange = SpotifyMirrorPreferences.MIN_CONFIDENCE_LOWER_BOUND..SpotifyMirrorPreferences.MIN_CONFIDENCE_UPPER_BOUND,
                            steps = 5,
                        )
                    }
                }
            }

            // Last Matched Track + Manual Override / Re-match
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.spotify_mirror_last_match_header),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(),
                ) {
                    val lastMatch = mirrorState.lastMatch
                    val sourceTrack = mirrorState.currentSourceTrack
                    if (lastMatch == null && sourceTrack == null) {
                        Text(
                            text = stringResource(R.string.spotify_mirror_no_match_yet),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (lastMatch?.isCatalogMatch == true) {
                                        Icons.Filled.MusicNote
                                    } else {
                                        Icons.Outlined.MusicOff
                                    },
                                    contentDescription = null,
                                    tint = if (lastMatch?.isCatalogMatch == true) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = lastMatch?.matchedTitle
                                            ?: sourceTrack?.title
                                            ?: lastMatch?.sourceTitle.orEmpty(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = lastMatch?.matchedArtist
                                            ?: sourceTrack?.artist
                                            ?: lastMatch?.sourceArtist.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (lastMatch != null) {
                                    val badgeBg = if (lastMatch.isCatalogMatch) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    }
                                    val badgeFg = if (lastMatch.isCatalogMatch) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Surface(
                                        shape = CircleShape,
                                        color = badgeBg,
                                    ) {
                                        Text(
                                            text = when {
                                                lastMatch.isManualOverride && lastMatch.isCatalogMatch ->
                                                    stringResource(R.string.spotify_mirror_manual_badge)
                                                lastMatch.isCatalogMatch ->
                                                    stringResource(R.string.spotify_mirror_catalog_badge)
                                                else ->
                                                    stringResource(R.string.spotify_mirror_local_only_badge)
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = badgeFg,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }

                            if (lastMatch != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.spotify_mirror_confidence_value,
                                            (lastMatch.confidence * 100f).roundToInt(),
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (!lastMatch.spotifyUri.isNullOrBlank()) {
                                        Text(
                                            text = lastMatch.spotifyUri,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilledTonalButton(
                                    onClick = viewModel::rematchCurrentTrack,
                                    enabled = !busy,
                                    shape = ExpressivePillShape,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.spotify_mirror_rematch),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        showOverrideEditor = !showOverrideEditor
                                        viewModel.clearFeedback()
                                    },
                                    enabled = !busy,
                                    shape = ExpressivePillShape,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.spotify_mirror_override_uri),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            AnimatedVisibility(
                                visible = showOverrideEditor,
                                enter = expandVertically(),
                                exit = shrinkVertically(),
                            ) {
                                val savedMsg = stringResource(R.string.spotify_mirror_override_saved)
                                val invalidMsg = stringResource(R.string.spotify_mirror_override_invalid)
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = overrideInput,
                                        onValueChange = { overrideInput = it },
                                        placeholder = {
                                            Text(stringResource(R.string.spotify_mirror_override_hint))
                                        },
                                        singleLine = true,
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Button(
                                            onClick = {
                                                viewModel.applyManualUriOverride(
                                                    rawUriOrUrl = overrideInput,
                                                    successMessage = savedMsg,
                                                    invalidMessage = invalidMsg,
                                                ) {
                                                    overrideInput = ""
                                                    showOverrideEditor = false
                                                }
                                            },
                                            enabled = overrideInput.isNotBlank() && !busy,
                                            shape = ExpressivePillShape,
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text(stringResource(R.string.spotify_mirror_override_apply))
                                        }
                                        TextButton(
                                            onClick = {
                                                viewModel.markCurrentLocalOnly()
                                                showOverrideEditor = false
                                            },
                                            enabled = !busy,
                                        ) {
                                            Text(stringResource(R.string.spotify_mirror_mark_local))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!feedback.isNullOrBlank()) {
                Text(
                    text = feedback.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            // Catalog vs Local-Only Play Counters
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.spotify_mirror_stats_header),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MirrorStatTile(
                        label = stringResource(R.string.spotify_mirror_stats_catalog),
                        value = catalogCount.toString(),
                        sublabel = mirrorState.deviceName,
                        modifier = Modifier.weight(1f),
                    )
                    MirrorStatTile(
                        label = stringResource(R.string.spotify_mirror_stats_local_only),
                        value = localOnlyCount.toString(),
                        sublabel = stringResource(R.string.spotify_mirror_stats_local_hint),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Honest Requirements & Catalog-Only Wrapped Explanation
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.spotify_mirror_requirements_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.spotify_mirror_requirements_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Cache Maintenance Footer
            val cacheClearedMsg = stringResource(R.string.spotify_mirror_cache_cleared)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { viewModel.clearMatchCache(cacheClearedMsg) },
                    enabled = !busy,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.spotify_mirror_clear_cache))
                }
            }
        }
    }
}

@Composable
private fun MirrorLiveStatusBanner(
    state: SpotifyMirrorState,
    busy: Boolean,
    onReconnect: (() -> Unit)?,
) {
    val (bg, fg, icon) = when (state.phase) {
        MirrorPhase.MIRRORING, MirrorPhase.CONNECTED_IDLE -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Filled.CheckCircle,
        )
        MirrorPhase.PAUSED_EXTERNAL_DEVICE, MirrorPhase.PAUSED_LOCAL_ONLY -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Filled.PauseCircle,
        )
        MirrorPhase.PREMIUM_REQUIRED, MirrorPhase.ERROR -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Filled.ErrorOutline,
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Outlined.Devices,
        )
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = bg,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy || state.phase == MirrorPhase.CONNECTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = fg,
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = mirrorStatusHeadline(state),
                style = MaterialTheme.typography.labelLarge,
                color = fg,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (onReconnect != null) {
                TextButton(onClick = onReconnect) {
                    Text(
                        text = stringResource(R.string.spotify_mirror_reconnect),
                        color = fg,
                    )
                }
            }
        }
    }
}

@Composable
private fun MirrorStatTile(
    label: String,
    value: String,
    sublabel: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sublabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
