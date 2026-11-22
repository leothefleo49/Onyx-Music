package com.lastwave.app.ui.stats

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

/** A self-contained yearly recap from Onyx's own scrobble history. */
@Composable
fun OnyxWrappedScreen(
    onClose: () -> Unit,
    viewModel: OnyxWrappedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            primary.copy(alpha = 0.32f),
                            MaterialTheme.colorScheme.background,
                            secondary.copy(alpha = 0.20f),
                        ),
                    ),
                ),
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White.copy(alpha = 0.9f))
                    }
                    Spacer(Modifier.weight(1f))
                    if (state.hasData) {
                        IconButton(onClick = {
                            val topArtist = state.topArtists.firstOrNull()?.title.orEmpty()
                            val topTrack = state.topTracks.firstOrNull()?.title.orEmpty()
                            val text = buildString {
                                append("My Onyx Wrapped ${state.year}: ")
                                append("${state.totalMinutes} minutes across ${state.plays} plays")
                                if (topArtist.isNotBlank()) append(" • Top artist: $topArtist")
                                if (topTrack.isNotBlank()) append(" • Top song: $topTrack")
                                append("\n#OnyxWrapped")
                            }
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    },
                                    "Share Onyx Wrapped",
                                ),
                            )
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White.copy(alpha = 0.9f))
                        }
                    }
                }

                if (state.isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Building your Wrapped…", color = Color.White.copy(alpha = 0.8f))
                    }
                    return@Column
                }
                if (!state.hasData) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "No listening history for ${state.year} yet.\nPlay a few songs and come back.",
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center,
                        )
                    }
                    return@Column
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        bottom = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            androidx.compose.foundation.layout.Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            ) {
                                WrappedRange.entries.forEach { range ->
                                    androidx.compose.material3.FilterChip(
                                        selected = state.range == range,
                                        onClick = { viewModel.setRange(range) },
                                        label = {
                                            Text(range.label, style = MaterialTheme.typography.labelMedium)
                                        },
                                    )
                                }
                            }
                            Text(
                                "ONYX WRAPPED",
                                fontSize = 13.sp,
                                letterSpacing = 5.sp,
                                fontWeight = FontWeight.Bold,
                                color = primary,
                            )
                            Text(
                                if (state.range == WrappedRange.YEAR) state.year.toString() else state.range.label,
                                fontSize = 56.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "${state.totalMinutes}",
                                fontSize = 64.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                            )
                            Text(
                                "minutes of music \u2022 ${state.plays} plays",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White.copy(alpha = 0.75f),
                            )
                        }
                    }

                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            WrappedStatCard("${state.uniqueTracks}", "songs", Modifier.weight(1f))
                            WrappedStatCard("${state.uniqueArtists}", "artists", Modifier.weight(1f))
                            WrappedStatCard("${state.uniqueAlbums}", "albums", Modifier.weight(1f))
                        }
                    }

                    item {
                        WrappedPersonalityCard(
                            personality = state.personality,
                            peakHour = state.peakHour,
                            source = state.topSource,
                            streak = state.longestStreakDays,
                        )
                    }

                    if (state.topArtists.isNotEmpty()) {
                        item { WrappedSectionTitle("Top artists") }
                        itemsIndexed(state.topArtists) { index, item ->
                            WrappedRankRow(index + 1, item)
                        }
                    }
                    if (state.topTracks.isNotEmpty()) {
                        item { WrappedSectionTitle("Top songs") }
                        itemsIndexed(state.topTracks) { index, item ->
                            WrappedRankRow(index + 1, item, showArtwork = true)
                        }
                    }
                    if (state.topAlbums.isNotEmpty()) {
                        item { WrappedSectionTitle("Top albums") }
                        itemsIndexed(state.topAlbums) { index, item ->
                            WrappedRankRow(index + 1, item, showArtwork = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WrappedStatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.08f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color.White)
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.65f))
        }
    }
}

@Composable
private fun WrappedPersonalityCard(
    personality: String,
    peakHour: String,
    source: String,
    streak: Int,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color.White.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Your listening personality", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
            Text(personality, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (peakHour.isNotBlank()) {
                Text("Peak listening: $peakHour", color = Color.White.copy(alpha = 0.75f))
            }
            if (source.isNotBlank()) {
                Text("Most-used source: $source", color = Color.White.copy(alpha = 0.75f))
            }
            if (streak > 0) {
                Text("Longest daily streak: $streak days", color = Color.White.copy(alpha = 0.75f))
            }
        }
    }
}

@Composable
private fun WrappedSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = Color.White.copy(alpha = 0.9f),
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun WrappedRankRow(
    rank: Int,
    item: WrappedItem,
    showArtwork: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.07f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$rank",
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(26.dp),
            )
            if (showArtwork) {
                AsyncImage(
                    model = item.artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
