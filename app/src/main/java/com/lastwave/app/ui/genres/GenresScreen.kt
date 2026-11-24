package com.lastwave.app.ui.genres

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.R
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.GenreStat
import com.lastwave.app.data.genre.GenreNormalizer
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.GlassHeaderLayout
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance
import kotlin.math.roundToInt

/**
 * Your Genres screen: canonical genre breakdown with true share %,
 * listening duration, top artists per genre, and an interactive Genre Detail
 * sheet with sort chips (Most Played / Most Recent / A–Z), Start Mix, and
 * Discover More.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenresScreen(
    onBack: () -> Unit = {},
    onNavigateToPlaylist: () -> Unit = {},
    viewModel: GenresViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.navigateToPlaylist) {
        if (state.navigateToPlaylist) {
            onNavigateToPlaylist()
            viewModel.consumeNavigateToPlaylist()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        GlassHeaderLayout(
            Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 860.dp),
            header = { headerModifier, headerBackdrop ->
                ExpressiveHeader(
                    modifier = headerModifier,
                    backdrop = headerBackdrop,
                    title = stringResource(R.string.ge_title),
                    subtitle = stringResource(R.string.ge_sub),
                    onBack = onBack,
                    actions = { PeriodDropdown(state.period, viewModel::setPeriod) },
                )
            },
        ) { headerInset ->
            Column(Modifier.fillMaxSize()) {
                when {
                    state.isLoading -> Box(
                        Modifier.fillMaxSize().safeHorizontalContentPadding(),
                        contentAlignment = Alignment.Center,
                    ) { com.lastwave.app.ui.common.ExpressiveLoadingIndicator() }

                    state.error != null && state.stats.isEmpty() -> Box(
                        Modifier.fillMaxSize().safeHorizontalContentPadding().padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = state.error ?: "Couldn't load genres",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = viewModel::retry) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Retry")
                            }
                        }
                    }

                    state.stats.isEmpty() -> Box(
                        Modifier.fillMaxSize().safeHorizontalContentPadding().padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(R.string.ge_empty),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Play music or sync your Spotify, YouTube Music, or Last.fm history to build your genre profile.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    else -> LazyColumn(
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp + headerInset,
                            bottom = 24.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize().safeHorizontalContentPadding(),
                    ) {
                        if (state.error != null) {
                            item(key = "genres_error") {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = state.error.orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        androidx.compose.material3.TextButton(onClick = viewModel::dismissError) {
                                            Text("Dismiss")
                                        }
                                    }
                                }
                            }
                        }

                        item(key = "genres_summary") {
                            GenreOverviewCard(stats = state.stats)
                        }

                        item(key = "genres_caption") {
                            Text(
                                text = "Tap any genre to browse your tracks, start a mix, or discover fresh artists",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }

                        itemsIndexed(state.stats, key = { _, stat -> stat.name }) { index, stat ->
                            GenreBarRow(
                                rank = index + 1,
                                stat = stat,
                                onClick = { viewModel.openDetail(stat.name) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }

    state.detailGenre?.let { genre ->
        GenreDetailSheet(
            genre = genre,
            state = state,
            onDismiss = viewModel::closeDetail,
            onLoadMore = { viewModel.loadDetailPage() },
            onStartMix = { viewModel.startMix(genre) },
            onDiscoverMore = { viewModel.discoverMore(genre) },
            onShowYourTracks = viewModel::showYourTracks,
            onSelectSort = viewModel::setDetailSort,
            onExploreGenre = { viewModel.exploreGenre(it) },
        )
    }
}

@Composable
private fun GenreOverviewCard(stats: List<GenreStat>) {
    val lead = stats.firstOrNull() ?: return
    val totalPlays = stats.sumOf { it.count }
    val totalMs = stats.sumOf { it.listeningMs }
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Leading genre",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = GenreNormalizer.formatDisplayName(lead.name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${(lead.sharePercent * 100f).roundToInt().coerceIn(1, 100)}% share",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GenreStatChip(
                    label = "Genres",
                    value = stats.size.toString(),
                    modifier = Modifier.weight(1f),
                )
                GenreStatChip(
                    label = "Plays",
                    value = totalPlays.toString(),
                    modifier = Modifier.weight(1f),
                )
                if (totalMs > 60_000L) {
                    GenreStatChip(
                        label = "Time",
                        value = formatGenreDuration(totalMs),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun GenreStatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PeriodDropdown(period: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = GENRE_PERIODS.firstOrNull { it.first == period }?.second ?: "Overall"
    Box {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.clickable { expanded = true },
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GENRE_PERIODS.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onChange(value); expanded = false })
            }
        }
    }
}

@Composable
private fun GenreBarRow(
    rank: Int,
    stat: GenreStat,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pctInt = (stat.sharePercent * 100f).roundToInt().coerceIn(1, 100)
    val animated by animateFloatAsState(
        targetValue = stat.percentOfTop.coerceIn(0.06f, 1f),
        animationSpec = tween(550),
        label = "genreBar",
    )
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "#$rank",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (rank <= 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(28.dp),
                )
                Text(
                    text = GenreNormalizer.formatDisplayName(stat.name),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                val metaParts = buildList {
                    add("$pctInt%")
                    if (stat.count > 0L) add("${stat.count} plays")
                    if (stat.listeningMs >= 60_000L) add(formatGenreDuration(stat.listeningMs))
                }
                Text(
                    text = metaParts.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (stat.topArtists.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = stat.topArtists.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 28.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(9.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(animated)
                        .height(9.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (rank == 1) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary.copy(alpha = (0.85f - (rank - 1) * 0.04f).coerceAtLeast(0.45f)),
                        ),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenreDetailSheet(
    genre: String,
    state: GenresUiState,
    onDismiss: () -> Unit,
    onLoadMore: () -> Unit,
    onStartMix: () -> Unit,
    onDiscoverMore: () -> Unit,
    onShowYourTracks: () -> Unit,
    onSelectSort: (GenreDetailSort) -> Unit,
    onExploreGenre: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var menuTrack by remember { mutableStateOf<GeneratedTrack?>(null) }
    val listState = rememberLazyListState()
    val displayGenre = remember(genre) { GenreNormalizer.formatDisplayName(GenreNormalizer.primaryGenre(genre)) }

    val shouldLoadMore by remember(listState, state.detailTracks.size) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.detailTracks.size - 5
        }
    }
    LaunchedEffect(shouldLoadMore, state.detailTracks.size, state.detailLoading, state.detailHasMore, state.isDiscoverMode) {
        if (shouldLoadMore && !state.detailLoading && state.detailHasMore && !state.isDiscoverMode) onLoadMore()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 640.dp)
                .align(Alignment.CenterHorizontally),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text(displayGenre, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (state.isDiscoverMode) "Discoveries \u2022 Fresh recommendations" else "Your Tracks \u2022 History & personalized picks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = onStartMix, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.fr_start_mix), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (state.isDiscoverMode) {
                    OutlinedButton(onClick = onShowYourTracks, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.ge_tracks), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    OutlinedButton(onClick = onDiscoverMore, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.ge_discover), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // Sort chips
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                item {
                    FilterChip(
                        selected = state.detailSort == GenreDetailSort.POPULAR,
                        onClick = { onSelectSort(GenreDetailSort.POPULAR) },
                        label = { Text("Most Played") },
                    )
                }
                item {
                    FilterChip(
                        selected = state.detailSort == GenreDetailSort.NEWEST,
                        onClick = { onSelectSort(GenreDetailSort.NEWEST) },
                        label = { Text("Recommended") },
                    )
                }
                item {
                    FilterChip(
                        selected = state.detailSort == GenreDetailSort.AZ,
                        onClick = { onSelectSort(GenreDetailSort.AZ) },
                        label = { Text("A\u2013Z") },
                    )
                }
            }

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(bottom = 12.dp)) {
                itemsIndexed(state.detailTracks, key = { index, track -> "${track.key}_$index" }) { _, track ->
                    GenreTrackRow(
                        track = track,
                        allTracks = state.detailTracks,
                        genreTitle = displayGenre,
                        onMenu = { menuTrack = track },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (state.detailLoading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator()
                        }
                    }
                }
                if (!state.detailLoading && state.detailTracks.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.ge_no_tracks), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    menuTrack?.let { track ->
        TrackContextMenuSheet(
            target = TrackMenuTarget.Track(track.name, track.artist, track.url),
            capabilities = TrackMenuCapabilities(showDeleteScrobble = true),
            playbackSourceLabel = "Genres",
            onDismiss = { menuTrack = null },
            onExploreGenre = onExploreGenre,
        )
    }
}

@Composable
private fun GenreTrackRow(
    track: GeneratedTrack,
    allTracks: List<GeneratedTrack>,
    genreTitle: String,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val musicPlayer = com.lastwave.app.ui.player.LocalMusicPlayer.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                val queue = allTracks.map {
                    com.lastwave.app.playback.PlayableTrack(it.name, it.artist, album = it.album, artworkUrl = it.artworkUrl)
                }
                val index = allTracks.indexOf(track).coerceAtLeast(0)
                musicPlayer.playQueue(queue, startIndex = index, sourceLabel = genreTitle)
            }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(
            name = track.name,
            artist = track.artist,
            embeddedUrl = track.artworkUrl,
            fallbackIcon = Icons.Filled.MusicNote,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val pc = track.playcount ?: 0L
            val subtitle = if (pc > 0L) "${track.artist} · $pc plays" else track.artist
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        com.lastwave.app.ui.common.OverflowMenuButton(onClick = onMenu)
    }
}

private fun formatGenreDuration(ms: Long): String {
    val totalMin = (ms / 60_000L).coerceAtLeast(1L)
    val hours = totalMin / 60L
    val mins = totalMin % 60L
    return when {
        hours >= 1L && mins > 0L -> "${hours}h ${mins}m"
        hours >= 1L -> "${hours}h"
        else -> "${mins}m"
    }
}
