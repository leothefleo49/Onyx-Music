package com.lastwave.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lastwave.app.R
import com.lastwave.app.data.local.AppBackgroundStyle
import com.lastwave.app.data.local.EdgeGlowMode
import com.lastwave.app.data.local.EdgeGlowMotion
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.SpatialAudioMode
import com.lastwave.app.ui.player.parseHexColor

/**
 * One sheet for the immersive-playback pack: the Lumn-style ambient edge
 * glow, progress-bar colours, the always-on player, headphone spatial audio
 * and the app background. Grouped by what the user is trying to change
 * rather than by which subsystem owns the preference.
 */
@Composable
internal fun AmbienceSettingsDialog(
    settings: MiscSettings,
    onEdgeGlowEnabled: (Boolean) -> Unit,
    onEdgeGlowMode: (EdgeGlowMode) -> Unit,
    onEdgeGlowMotion: (EdgeGlowMotion) -> Unit,
    onEdgeGlowIntensity: (Float) -> Unit,
    onEdgeGlowThickness: (Float) -> Unit,
    onEdgeGlowColor: (Int, String) -> Unit,
    onAodLyrics: (Boolean) -> Unit,
    onAodVisualizerMode: (String) -> Unit,
    onAodShowControls: (Boolean) -> Unit,
    onAodShowArtwork: (Boolean) -> Unit,
    onSeekbarMatch: (Boolean) -> Unit,
    onSeekbarColor: (Int, String) -> Unit,
    onBackground: (AppBackgroundStyle) -> Unit,
    glassSurfaces: Set<String>,
    onGlassSurface: (com.lastwave.app.data.local.GlassSurface, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
        title = {
            Text(
                "Visuals, AOD & glass",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // ── Ambient edge glow ──
                SectionHeader(stringResource(R.string.settings_edge_glow))
                ToggleRow(
                    label = stringResource(R.string.settings_edge_glow_subtitle),
                    checked = settings.edgeGlowEnabled,
                    onChange = onEdgeGlowEnabled,
                )
                val showGlowControls = settings.edgeGlowEnabled || settings.aodVisualizerMode != "off"
                if (showGlowControls) {
                    OptionRow(
                        label = stringResource(R.string.settings_edge_glow_color_mode),
                        options = listOf(
                            EdgeGlowMode.ART to "Artwork",
                            EdgeGlowMode.CUSTOM to "Custom",
                            EdgeGlowMode.GRADIENT to "Gradient",
                            EdgeGlowMode.OFF to "Off",
                        ),
                        selected = settings.edgeGlowMode,
                        onSelect = onEdgeGlowMode,
                    )
                    if (settings.edgeGlowMode == EdgeGlowMode.CUSTOM ||
                        settings.edgeGlowMode == EdgeGlowMode.GRADIENT
                    ) {
                        ColorPickerRow(
                            label = stringResource(R.string.settings_edge_glow_custom_color_1),
                            current = settings.edgeGlowColor1,
                            onPick = { onEdgeGlowColor(1, it) },
                        )
                    }
                    if (settings.edgeGlowMode == EdgeGlowMode.GRADIENT) {
                        ColorPickerRow(
                            label = stringResource(R.string.settings_edge_glow_custom_color_2),
                            current = settings.edgeGlowColor2,
                            onPick = { onEdgeGlowColor(2, it) },
                        )
                    }
                    OptionRow(
                        label = stringResource(R.string.settings_edge_glow_motion),
                        options = listOf(
                            EdgeGlowMotion.BEAT to "Beat",
                            EdgeGlowMotion.IDLE to "Breathing",
                            EdgeGlowMotion.STATIC to "Static",
                        ),
                        selected = settings.edgeGlowMotion,
                        onSelect = onEdgeGlowMotion,
                    )
                    SliderRow(
                        label = stringResource(R.string.settings_edge_glow_intensity),
                        value = settings.edgeGlowIntensity,
                        range = 0.2f..2f,
                        valueLabel = "${(settings.edgeGlowIntensity * 100).toInt()}%",
                        onChange = onEdgeGlowIntensity,
                    )
                    SliderRow(
                        label = stringResource(R.string.settings_edge_glow_thickness),
                        value = settings.edgeGlowThickness,
                        range = 0.05f..1f,
                        valueLabel = "${(settings.edgeGlowThickness * 100).toInt()}%",
                        onChange = onEdgeGlowThickness,
                    )
                }

                Divider()
                // ── Progress bar colours ──
                SectionHeader(stringResource(R.string.settings_seekbar_colors))
                ToggleRow(
                    label = stringResource(R.string.settings_seekbar_colors_match),
                    checked = settings.seekbarMatchTrackColors,
                    onChange = {
                        onSeekbarMatch(it)
                    },
                )
                if (!settings.seekbarMatchTrackColors) {
                    ColorPickerRow(
                        label = stringResource(R.string.settings_edge_glow_custom_color_1),
                        current = settings.seekbarCustomColor1,
                        onPick = { onSeekbarColor(1, it) },
                    )
                    ColorPickerRow(
                        label = stringResource(R.string.settings_edge_glow_custom_color_2),
                        current = settings.seekbarCustomColor2,
                        onPick = { onSeekbarColor(2, it) },
                    )
                }

                Divider()
                // ── Always-on player ──
                SectionHeader(stringResource(R.string.settings_aod))
                Text(
                    stringResource(R.string.settings_aod_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                ToggleRow(
                    label = stringResource(R.string.settings_aod_lyrics),
                    checked = settings.aodShowLyrics,
                    onChange = onAodLyrics,
                )
                OptionRow(
                    label = "Edge animation",
                    options = listOf(
                        "wave" to "Wave",
                        "glow" to "Glow",
                        "bars" to "Bars",
                        "aurora" to "Aurora",
                        "off" to "Off",
                    ),
                    selected = settings.aodVisualizerMode,
                    onSelect = onAodVisualizerMode,
                )
                ToggleRow(
                    label = "Show playback controls",
                    checked = settings.aodShowControls,
                    onChange = onAodShowControls,
                )
                ToggleRow(
                    label = "Show artwork",
                    checked = settings.aodShowArtwork,
                    onChange = onAodShowArtwork,
                )

                Divider()
                // ── App background ──
                SectionHeader(stringResource(R.string.settings_app_background))
                OptionRow(
                    label = "",
                    options = listOf(
                        AppBackgroundStyle.ARTWORK to stringResource(R.string.settings_app_background_artwork),
                        AppBackgroundStyle.GLOSSY to stringResource(R.string.settings_app_background_glossy),
                        AppBackgroundStyle.PLAIN to stringResource(R.string.settings_app_background_plain),
                    ),
                    selected = settings.appBackgroundStyle,
                    onSelect = onBackground,
                )

                Divider()
                // ── Where Liquid Glass is allowed ──
                SectionHeader(stringResource(R.string.settings_glass_surfaces))
                com.lastwave.app.data.local.GlassSurface.entries.forEach { surface ->
                    val label = when (surface) {
                        com.lastwave.app.data.local.GlassSurface.NAVIGATION -> stringResource(R.string.settings_glass_surface_nav)
                        com.lastwave.app.data.local.GlassSurface.MINI_PLAYER -> stringResource(R.string.settings_glass_surface_mini)
                        com.lastwave.app.data.local.GlassSurface.PLAYER_CONTROLS -> stringResource(R.string.settings_glass_surface_player)
                        com.lastwave.app.data.local.GlassSurface.FLOATING -> stringResource(R.string.settings_glass_surface_floating)
                        com.lastwave.app.data.local.GlassSurface.HEADER -> stringResource(R.string.settings_glass_surface_header)
                    }
                    ToggleRow(
                        label = label,
                        checked = surface.id in glassSurfaces,
                        onChange = { onGlassSurface(surface, it) },
                    )
                }
            }
        },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
    )
}

@Composable
private fun Divider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

@Composable
internal fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun <T> OptionRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (label.isNotBlank()) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, text) ->
                val isSelected = value == selected
                Box(
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .border(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape,
                        )
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            else Color.Transparent,
                            CircleShape,
                        )
                        .clickable { onSelect(value) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Fixed swatches keep the picker thumb-friendly; no free-text hex entry. */
private val COLOR_SWATCHES = listOf(
    "#8B5CF6", "#6366F1", "#22D3EE", "#06B6D4",
    "#10B981", "#84CC16", "#F59E0B", "#F97316",
    "#EF4444", "#EC4899", "#F4F4F5", "#0F172A",
)

@Composable
private fun ColorPickerRow(label: String, current: String, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            COLOR_SWATCHES.forEach { hex ->
                val color = parseHexColor(hex, MaterialTheme.colorScheme.primary)
                val isSelected = hex.equals(current, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .size(30.dp)
                        .background(color, CircleShape)
                        .border(
                            width = if (isSelected) 2.5.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.White.copy(alpha = 0.22f),
                            shape = CircleShape,
                        )
                        .clickable { onPick(hex) },
                )
            }
        }
    }
}
