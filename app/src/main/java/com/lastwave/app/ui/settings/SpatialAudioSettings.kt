package com.lastwave.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.SpatialAudioMode

/** Spatial audio lives in its own sheet, separate from visual ambience. */
@Composable
internal fun SpatialAudioSettingsDialog(
    settings: MiscSettings,
    onMode: (SpatialAudioMode) -> Unit,
    onCrossfeed: (Float) -> Unit,
    onWidth: (Float) -> Unit,
    onBassMono: (Boolean) -> Unit,
    onRecenter: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = {
            Text(
                "Spatial audio",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Headphone soundstage processing. Head tracking follows how you turn your head; fixed keeps a stable stage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                OptionRow(
                    label = "Mode",
                    options = listOf(
                        SpatialAudioMode.OFF to "Off",
                        SpatialAudioMode.STATIC to "Fixed stage",
                        SpatialAudioMode.HEAD_TRACKED to "Head tracked",
                    ),
                    selected = settings.spatialAudioMode,
                    onSelect = onMode,
                )
                if (settings.spatialAudioMode != SpatialAudioMode.OFF) {
                    OptionRow(
                        label = "Preset",
                        options = listOf(
                            "Narrow" to "Narrow",
                            "Balanced" to "Balanced",
                            "Wide" to "Wide",
                            "Concert" to "Concert",
                        ),
                        selected = when {
                            settings.spatialWidth <= 0.8f -> "Narrow"
                            settings.spatialWidth >= 1.6f -> "Concert"
                            settings.spatialWidth >= 1.2f -> "Wide"
                            else -> "Balanced"
                        },
                        onSelect = { preset ->
                            when (preset) {
                                "Narrow" -> {
                                    onWidth(0.7f); onCrossfeed(0.5f); onBassMono(true)
                                }
                                "Wide" -> {
                                    onWidth(1.35f); onCrossfeed(0.18f); onBassMono(false)
                                }
                                "Concert" -> {
                                    onWidth(1.75f); onCrossfeed(0.08f); onBassMono(false)
                                }
                                else -> {
                                    onWidth(1f); onCrossfeed(0.25f); onBassMono(false)
                                }
                            }
                        },
                    )
                    Text("Crossfeed", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = settings.spatialCrossfeed,
                        onValueChange = onCrossfeed,
                        valueRange = 0f..1f,
                    )
                    Text("Stereo width ${String.format("%.2f\u00d7", settings.spatialWidth)}", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = settings.spatialWidth,
                        onValueChange = onWidth,
                        valueRange = 0.3f..2f,
                    )
                    ToggleRow(
                        label = "Centre the bass",
                        checked = settings.spatialBassMono,
                        onChange = onBassMono,
                    )
                    if (settings.spatialAudioMode == SpatialAudioMode.HEAD_TRACKED) {
                        TextButton(onClick = onRecenter) { Text("Recentre head tracking") }
                    }
                }
            }
        },
    )
}
