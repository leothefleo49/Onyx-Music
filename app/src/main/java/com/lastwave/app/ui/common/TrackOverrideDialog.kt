package com.lastwave.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lastwave.app.data.local.TrackOverride
import com.lastwave.app.ui.settings.TrackOverridesViewModel

/** Per-song escape hatches: this one track can differ from global video,
 *  canvas and motion defaults. */
@Composable
fun TrackOverrideDialog(
    track: TrackOverride,
    onDismiss: () -> Unit,
    viewModel: TrackOverridesViewModel = hiltViewModel(),
) {
    var videoDisabled by remember(track.id) { mutableStateOf(track.videoDisabled) }
    var canvasDisabled by remember(track.id) { mutableStateOf(track.canvasDisabled) }
    var animationsDisabled by remember(track.id) { mutableStateOf(track.animationsDisabled) }

    @Composable
    fun row(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Per-song settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                row("Disable video", "No synced video for this song", videoDisabled) { videoDisabled = it }
                row("Disable Canvas", "No looping Canvas artwork", canvasDisabled) { canvasDisabled = it }
                row("Reduce motion", "Static backgrounds and no beat sync", animationsDisabled) { animationsDisabled = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.save(
                    track.copy(
                        videoDisabled = videoDisabled,
                        canvasDisabled = canvasDisabled,
                        animationsDisabled = animationsDisabled,
                    ),
                )
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
