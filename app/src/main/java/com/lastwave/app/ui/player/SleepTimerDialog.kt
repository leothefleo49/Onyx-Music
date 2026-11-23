package com.lastwave.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.playback.MusicPlayer
import kotlin.math.ceil

private val SLEEP_PRESET_MINUTES = listOf(10, 15, 30, 45, 60, 90, 120)

/**
 * Unified Sleep Timer dialog shared across Quick Actions, Track 3-dot menus,
 * and the floating Sleep Timer pill.
 *
 * Includes:
 * - Quick presets (10m, 15m, 30m, 45m, 60m, 90m, 2h)
 * - End of current song (calculated from remaining duration of the playing track)
 * - End of queue (stops cleanly after the final track in queue finishes)
 * - Inline Custom Time picker (Hours + Minutes steppers AND direct minute entry)
 * - Active timer status + 1-tap Turn Off
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepTimerDialog(
    musicPlayer: MusicPlayer,
    onDismiss: () -> Unit,
) {
    val playerState by musicPlayer.state.collectAsStateWithLifecycle()
    val remainingMs = playerState.sleepTimerRemainingMs
    val endOfQueue = playerState.sleepTimerEndOfQueue
    val isTimerActive = remainingMs != null || endOfQueue

    var customHours by remember { mutableIntStateOf(0) }
    var customMinutes by remember { mutableIntStateOf(25) }
    var directMinutesText by remember { mutableStateOf("25") }

    val endOfTrackMinutes = remember(playerState.durationMs, playerState.positionMs) {
        val remMs = (playerState.durationMs - playerState.positionMs).coerceAtLeast(0L)
        if (remMs >= 30_000L) ceil(remMs / 60_000.0).toInt().coerceAtLeast(1) else null
    }

    val endOfQueueStats = remember(playerState.queue, playerState.currentIndex, playerState.durationMs, playerState.positionMs) {
        val upcoming = playerState.queue.drop((playerState.currentIndex + 1).coerceAtLeast(0))
        QueueStatsFormatter.formatQueueStats(upcoming)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Bedtime,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Sleep Timer",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = when {
                                endOfQueue -> "Active · Stops at end of queue"
                                remainingMs != null -> {
                                    val minsLeft = ceil(remainingMs / 60_000.0).toInt().coerceAtLeast(1)
                                    "Active · $minsLeft min remaining"
                                }
                                else -> "Stop playback automatically"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isTimerActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = if (isTimerActive) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                    if (isTimerActive) {
                        OutlinedButton(
                            onClick = {
                                musicPlayer.setSleepTimerMinutes(0)
                                onDismiss()
                            },
                        ) {
                            Text("Turn off")
                        }
                    }
                }

                // Quick Preset Chips
                Text(
                    text = "Quick presets",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SLEEP_PRESET_MINUTES.forEach { mins ->
                        val label = when {
                            mins >= 120 && mins % 60 == 0 -> "${mins / 60}h"
                            mins > 60 -> "${mins / 60}h ${mins % 60}m"
                            else -> "${mins}m"
                        }
                        Surface(
                            onClick = {
                                musicPlayer.setSleepTimerMinutes(mins)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                    }
                }

                // End of current song / End of queue buttons
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (endOfTrackMinutes != null && playerState.current != null) {
                        FilledTonalButton(
                            onClick = {
                                musicPlayer.setSleepTimerMinutes(endOfTrackMinutes)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text("End of current song (~$endOfTrackMinutes min)")
                        }
                    }
                    FilledTonalButton(
                        onClick = {
                            musicPlayer.setSleepTimerEndOfQueue()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("End of queue · $endOfQueueStats")
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Inline Custom Duration Picker (Hours + Minutes steppers + direct input)
                Text(
                    text = "Custom time",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Hours stepper
                    TimeStepperBox(
                        label = "Hours",
                        value = customHours,
                        onDecrement = {
                            customHours = (customHours - 1).coerceAtLeast(0)
                            directMinutesText = (customHours * 60 + customMinutes).toString()
                        },
                        onIncrement = {
                            customHours = (customHours + 1).coerceAtMost(12)
                            directMinutesText = (customHours * 60 + customMinutes).toString()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    // Minutes stepper
                    TimeStepperBox(
                        label = "Min",
                        value = customMinutes,
                        onDecrement = {
                            customMinutes = if (customMinutes >= 5) customMinutes - 5 else 0
                            directMinutesText = (customHours * 60 + customMinutes).toString()
                        },
                        onIncrement = {
                            val next = customMinutes + 5
                            if (next >= 60) {
                                customHours = (customHours + 1).coerceAtMost(12)
                                customMinutes = next % 60
                            } else {
                                customMinutes = next
                            }
                            directMinutesText = (customHours * 60 + customMinutes).toString()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = directMinutesText,
                        onValueChange = { raw ->
                            val clean = raw.filter(Char::isDigit).take(4)
                            directMinutesText = clean
                            val total = clean.toIntOrNull() ?: 0
                            customHours = (total / 60).coerceIn(0, 24)
                            customMinutes = total % 60
                        },
                        label = { Text("Exact minutes") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                    )
                    val totalCustomMinutes = (directMinutesText.toIntOrNull() ?: (customHours * 60 + customMinutes))
                        .coerceIn(0, 1440)
                    Button(
                        onClick = {
                            if (totalCustomMinutes > 0) {
                                musicPlayer.setSleepTimerMinutes(totalCustomMinutes)
                                onDismiss()
                            }
                        },
                        enabled = totalCustomMinutes > 0,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(54.dp),
                    ) {
                        Text(
                            text = if (totalCustomMinutes >= 60) {
                                val h = totalCustomMinutes / 60
                                val m = totalCustomMinutes % 60
                                if (m > 0) "Start ${h}h ${m}m" else "Start ${h}h"
                            } else if (totalCustomMinutes > 0) {
                                "Start ${totalCustomMinutes}m"
                            } else {
                                "Start"
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeStepperBox(
    label: String,
    value: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.border(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            RoundedCornerShape(16.dp),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(
                onClick = onDecrement,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease $label", modifier = Modifier.size(16.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onIncrement,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Increase $label", modifier = Modifier.size(16.dp))
            }
        }
    }
}
