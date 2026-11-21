package com.lastwave.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lastwave.app.R
import com.lastwave.app.data.scrobble.ScrobbleEventEntity
import com.lastwave.app.data.scrobble.ScrobbleSyncStatus
import com.lastwave.app.ui.common.ArtworkImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full timestamped scrobble feed sheet with source app badges, sync status,
 * search + source filters, inline Pano-style metadata editor ("Save as auto-fix rule"),
 * Love/Unlove toggle, Delete, and Play in Onyx Music.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrobbleHistorySheet(
    events: List<ScrobbleEventEntity>,
    onDismiss: () -> Unit,
    onToggleLove: (ScrobbleEventEntity) -> Unit,
    onDelete: (ScrobbleEventEntity) -> Unit,
    onEdit: (
        event: ScrobbleEventEntity,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        saveAsAutoRule: Boolean,
        applyRetroactively: Boolean,
    ) -> Unit,
    onPlayTrack: (ScrobbleEventEntity) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf<String?>(null) }
    var editingEvent by remember { mutableStateOf<ScrobbleEventEntity?>(null) }
    var confirmingDelete by remember { mutableStateOf<ScrobbleEventEntity?>(null) }

    val availableSources = remember(events) {
        events.map { it.sourceAppName.ifBlank { "Onyx Music" } }.distinct().sorted()
    }

    val filtered = remember(events, query, selectedSource) {
        val q = query.trim().lowercase()
        events.filter { ev ->
            val appName = ev.sourceAppName.ifBlank { "Onyx Music" }
            val matchesSource = selectedSource == null || appName == selectedSource
            val matchesQuery = q.isEmpty() ||
                ev.title.lowercase().contains(q) ||
                ev.artist.lowercase().contains(q) ||
                ev.album.lowercase().contains(q)
            matchesSource && matchesQuery
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Scrobble History",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${filtered.size} recorded plays",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close history",
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search tracks, artists, or albums") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            if (availableSources.size > 1) {
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    item {
                        StatsFilterChip(
                            label = "All Apps",
                            selected = selectedSource == null,
                            onClick = { selectedSource = null },
                        )
                    }
                    items(availableSources) { src ->
                        StatsFilterChip(
                            label = src,
                            selected = selectedSource == src,
                            onClick = { selectedSource = if (selectedSource == src) null else src },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (events.isEmpty()) {
                            "No scrobbles recorded yet. Play music in Onyx or enable external app scrobbling."
                        } else {
                            "No plays match your current filter."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.id }) { event ->
                        ScrobbleHistoryRow(
                            event = event,
                            onPlay = { onPlayTrack(event) },
                            onToggleLove = { onToggleLove(event) },
                            onEdit = { editingEvent = event },
                            onDelete = { confirmingDelete = event },
                        )
                    }
                }
            }
        }
    }

    editingEvent?.let { target ->
        EditScrobbleDialog(
            event = target,
            onDismiss = { editingEvent = null },
            onConfirm = { title, artist, album, saveRule, retroactive ->
                onEdit(target, title, artist, album, saveRule, retroactive)
                editingEvent = null
            },
        )
    }

    confirmingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text("Delete scrobble") },
            text = {
                Text("Remove \"${target.title}\" by ${target.artist} from local scrobble history?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target)
                        confirmingDelete = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ScrobbleHistoryRow(
    event: ScrobbleEventEntity,
    onPlay: () -> Unit,
    onToggleLove: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val timeText = remember(event.scrobbledAtMs) {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(event.scrobbledAtMs))
    }
    val sourceLabel = event.sourceAppName.ifBlank { "Onyx Music" }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    ArtworkImage(
                        name = event.title,
                        artist = event.artist,
                        embeddedUrl = event.artworkUrl,
                        fallbackIcon = Icons.Filled.MusicNote,
                        modifier = Modifier.size(46.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(event.artist)
                            if (event.album.isNotBlank()) {
                                append(" \u00b7 ")
                                append(event.album)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = sourceLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Text(
                            text = timeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SyncStatusIcon(event.lastFmSyncStatus)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPlay, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play track",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onToggleLove, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = if (event.isLoved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (event.isLoved) "Unlove track" else "Love track",
                        tint = if (event.isLoved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit scrobble metadata",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete scrobble",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncStatusIcon(status: String) {
    val (icon, tint, label) = when (status) {
        ScrobbleSyncStatus.SYNCED -> Triple(
            Icons.Filled.CloudDone,
            MaterialTheme.colorScheme.primary,
            "Synced",
        )
        ScrobbleSyncStatus.PENDING -> Triple(
            Icons.Filled.CloudQueue,
            MaterialTheme.colorScheme.tertiary,
            "Queued",
        )
        ScrobbleSyncStatus.FAILED -> Triple(
            Icons.Filled.ErrorOutline,
            MaterialTheme.colorScheme.error,
            "Sync failed",
        )
        else -> return
    }
    Icon(
        imageVector = icon,
        contentDescription = label,
        tint = tint,
        modifier = Modifier.size(14.dp),
    )
}

@Composable
private fun EditScrobbleDialog(
    event: ScrobbleEventEntity,
    onDismiss: () -> Unit,
    onConfirm: (
        title: String,
        artist: String,
        album: String,
        saveAsAutoRule: Boolean,
        applyRetroactively: Boolean,
    ) -> Unit,
) {
    var title by remember(event) { mutableStateOf(event.title) }
    var artist by remember(event) { mutableStateOf(event.artist) }
    var album by remember(event) { mutableStateOf(event.album) }
    var saveAsRule by remember { mutableStateOf(true) }
    var applyRetro by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit scrobble") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("Album") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Checkbox(checked = saveAsRule, onCheckedChange = { saveAsRule = it })
                    Text(
                        text = stringResource(R.string.scrobbler_save_auto_rule),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (saveAsRule) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(checked = applyRetro, onCheckedChange = { applyRetro = it })
                        Text(
                            text = stringResource(R.string.scrobbler_apply_retroactive),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, artist, album, saveAsRule, saveAsRule && applyRetro) },
                enabled = title.isNotBlank() && artist.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
