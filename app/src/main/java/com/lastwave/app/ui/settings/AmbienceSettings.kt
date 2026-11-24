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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lastwave.app.R
import com.lastwave.app.data.local.AppBackgroundStyle
import com.lastwave.app.data.local.EdgeGlowEdgeMask
import com.lastwave.app.data.local.EdgeGlowMode
import com.lastwave.app.data.local.EdgeGlowMotion
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.ui.player.AmbientEdgeGlow
import com.lastwave.app.ui.player.EdgeGlowConfig
import com.lastwave.app.ui.player.parseHexColor

/**
 * Unified customization dialog for **Vibe Mode, Always-On Display, Lumn Border
 * Glow, Seekbar Colours, App Background, and Per-Surface Liquid Glass**.
 * Includes a live OLED preview box so every change is visible immediately.
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
    onEdgeGlowEdges: (Int) -> Unit = {},
    onEdgeGlowStyle: (String) -> Unit = {},
    onAodLyrics: (Boolean) -> Unit,
    onAodVisualizerMode: (String) -> Unit,
    onAodShowControls: (Boolean) -> Unit,
    onAodShowArtwork: (Boolean) -> Unit,
    onAodDefaultFull: (Boolean) -> Unit = {},
    onSeekbarMatch: (Boolean) -> Unit,
    onSeekbarColor: (Int, String) -> Unit,
    onBackground: (AppBackgroundStyle) -> Unit,
    glassSurfaces: Set<String>,
    onGlassSurface: (com.lastwave.app.data.local.GlassSurface, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val previewColor1 = parseHexColor(settings.edgeGlowColor1, MaterialTheme.colorScheme.primary)
    val previewColor2 = parseHexColor(settings.edgeGlowColor2, MaterialTheme.colorScheme.secondary)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
        title = {
            Text(
                "Vibe Mode, AOD & Glass",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 580.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // Live Lumn OLED bezel preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(112.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black)
                        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val activePreviewStyle = if (settings.aodVisualizerMode != "off") {
                        settings.aodVisualizerMode
                    } else {
                        settings.edgeGlowStyle
                    }
                    AmbientEdgeGlow(
                        config = EdgeGlowConfig(
                            enabled = activePreviewStyle != "off",
                            mode = if (settings.edgeGlowMode == EdgeGlowMode.OFF) EdgeGlowMode.GRADIENT else settings.edgeGlowMode,
                            motion = settings.edgeGlowMotion,
                            style = activePreviewStyle,
                            edgeMask = settings.edgeGlowEdges,
                            intensity = settings.edgeGlowIntensity,
                            thickness = settings.edgeGlowThickness,
                            color1 = previewColor1,
                            color2 = previewColor2,
                            color3 = lerp(previewColor1, previewColor2, 0.5f),
                        ),
                        isPlaying = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "9:41",
                            color = Color.White.copy(alpha = 0.94f),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.ExtraLight,
                        )
                        Text(
                            text = if (settings.aodDefaultFull) "Full AOD Preview" else "Half AOD • Screen Awake",
                            color = Color.White.copy(alpha = 0.60f),
                            fontSize = 11.sp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                // ── Lumn Ambient Border Glow ──
                SectionHeader("Lumn Border Glow")
                ToggleRow(
                    label = "Show border glow in player",
                    checked = settings.edgeGlowEnabled,
                    onChange = onEdgeGlowEnabled,
                )
                OptionRow(
                    label = "Glow & Visualizer Style",
                    options = listOf(
                        "glow" to "Lumn Glow",
                        "wave" to "Smooth Curve",
                        "bars" to "Edge EQ",
                        "aurora" to "Aurora Silk",
                        "off" to "Off",
                    ),
                    selected = settings.edgeGlowStyle,
                    onSelect = { style ->
                        onEdgeGlowStyle(style)
                        onAodVisualizerMode(style)
                    },
                )
                // Per-edge selection (Top / Bottom / Left / Right — choose any combination)
                EdgeSelectorRow(
                    edgeMask = settings.edgeGlowEdges,
                    onChange = onEdgeGlowEdges,
                )
                OptionRow(
                    label = stringResource(R.string.settings_edge_glow_color_mode),
                    options = listOf(
                        EdgeGlowMode.ART to "Album Art",
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
                        EdgeGlowMotion.BEAT to "Music Sync",
                        EdgeGlowMotion.FLOW to "Flow",
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

                Divider()
                // ── Always-On Display (Half AOD + Full AOD) ──
                SectionHeader("Always-On Display (AOD)")
                Text(
                    "Half AOD keeps your screen awake so it won't auto-sleep while letting you manually lock your phone or switch tabs, with a 1-tap button to open Full AOD.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                OptionRow(
                    label = "Sparkle button launches",
                    options = listOf(
                        false to "Half AOD (Default)",
                        true to "Full AOD",
                    ),
                    selected = settings.aodDefaultFull,
                    onSelect = onAodDefaultFull,
                )
                ToggleRow(
                    label = stringResource(R.string.settings_aod_lyrics),
                    checked = settings.aodShowLyrics,
                    onChange = onAodLyrics,
                )
                ToggleRow(
                    label = "Show playback controls in Full AOD",
                    checked = settings.aodShowControls,
                    onChange = onAodShowControls,
                )
                ToggleRow(
                    label = "Show album artwork in Full AOD",
                    checked = settings.aodShowArtwork,
                    onChange = onAodShowArtwork,
                )

                Divider()
                // ── Progress bar colours ──
                SectionHeader(stringResource(R.string.settings_seekbar_colors))
                ToggleRow(
                    label = stringResource(R.string.settings_seekbar_colors_match),
                    checked = settings.seekbarMatchTrackColors,
                    onChange = onSeekbarMatch,
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
private fun EdgeSelectorRow(
    edgeMask: Int,
    onChange: (Int) -> Unit,
) {
    val items = listOf(
        EdgeGlowEdgeMask.TOP to "Top",
        EdgeGlowEdgeMask.BOTTOM to "Bottom",
        EdgeGlowEdgeMask.LEFT to "Left",
        EdgeGlowEdgeMask.RIGHT to "Right",
    )
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "Active Edges (pick one or more)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 5.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { (flag, label) ->
                val isSelected = (edgeMask and flag) != 0
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
                        .clickable {
                            val next = if (isSelected) edgeMask and flag.inv() else edgeMask or flag
                            if (next != 0) onChange(next)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
            val allSelected = (edgeMask and EdgeGlowEdgeMask.ALL) == EdgeGlowEdgeMask.ALL
            Box(
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .border(
                        width = if (allSelected) 1.5.dp else 1.dp,
                        color = if (allSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    )
                    .background(
                        if (allSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else Color.Transparent,
                        CircleShape,
                    )
                    .clickable { onChange(EdgeGlowEdgeMask.ALL) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    "All 4",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (allSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
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
            modifier = Modifier.weight(1f).padding(end = 8.dp),
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
                        maxLines = 1,
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

/** Rich palette covering vibrant neon, pastel, and monochrome glow colours. */
private val COLOR_SWATCHES = listOf(
    "#8B5CF6", "#6366F1", "#3B82F6", "#22D3EE", "#06B6D4", "#14B8A6",
    "#10B981", "#84CC16", "#EAB308", "#F97316", "#EF4444", "#F43F5E",
    "#EC4899", "#D946EF", "#F8FAFC", "#0F172A",
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
