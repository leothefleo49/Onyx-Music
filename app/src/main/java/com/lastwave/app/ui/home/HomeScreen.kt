package com.lastwave.app.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.lastwave.app.R
import com.lastwave.app.data.repository.HomeSortMode
import com.lastwave.app.data.repository.HomeTrack
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.HeaderActionIcon
import com.lastwave.app.ui.common.TrackMiniTrayData
import com.lastwave.app.ui.common.TrackMiniTraySheet
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.shell.FloatingNavDefaults

internal val ListContainerShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
internal val TrackRowShape = RoundedCornerShape(18.dp)
internal val NowPlayingCardShape = RoundedCornerShape(22.dp)
internal val ArtworkShape = RoundedCornerShape(14.dp)
internal val BadgePillShape = RoundedCornerShape(50)
internal val StatPillShape = RoundedCornerShape(20.dp)
internal val HeroInnerShape = RoundedCornerShape(24.dp)

/**
 * Unified, single-scroll Stats tab:
 * All summary cards, quick action chips (Insights Hub, Onyx Wrapped, Genres,
 * Play Feed, Scrobbler), and the Recent / Top listening list live inside ONE
 * smooth [LazyColumn] inside [PullToRefreshBox] so the whole viewport scrolls
 * cleanly with zero nested-scroll conflict.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenGenres: () -> Unit = {},
    onOpenListeningStats: () -> Unit = {},
    onOpenFriends: () -> Unit,
    onOpenDownloads: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    artistAlbumNavigator: com.lastwave.app.ui.navigation.ArtistAlbumNavigator = hiltViewModel<ArtistAlbumNavBridgeHome>().navigator,
    genreExplorer: com.lastwave.app.ui.genres.GenreExplorer = hiltViewModel<GenreExplorerBridgeHome>().explorer,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val jamViewModel: com.lastwave.app.ui.jam.LastWaveJamViewModel = hiltViewModel()
    var showJamSheet by remember { mutableStateOf(false) }
    var showScrobblerControls by remember { mutableStateOf(false) }
    var showScrobbleHistory by remember { mutableStateOf(false) }
    var showWrapped by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    BackHandler(enabled = showScrobblerControls) {
        showScrobblerControls = false
        viewModel.refreshSilently()
    }

    if (showScrobblerControls) {
        com.lastwave.app.ui.settings.ScrobblerSettingsScreen(
            onBack = {
                showScrobblerControls = false
                viewModel.refreshSilently()
            },
        )
        return
    }

    LaunchedEffect(viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.pollWhileActive()
        }
    }

    var menuTrack by remember { mutableStateOf<HomeTrack?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ExpressiveHeader(
                    title = "Stats",
                    modifier = Modifier.adaptiveContentWidth(maxWidth = 860.dp),
                    actions = {
                        HeaderActionIcon(
                            Icons.Filled.BarChart,
                            "Insights Hub",
                            onOpenListeningStats,
                        )
                        HeaderActionIcon(Icons.Filled.Search, "Search", onOpenSearch)
                        HeaderActionIcon(Icons.Filled.Settings, "Settings", onOpenSettings)
                    },
                )
            }
        },
    ) { scaffoldPadding ->
        if (uiState.isLoading) {
            Box(
                Modifier.fillMaxSize().padding(scaffoldPadding).safeHorizontalContentPadding(),
                contentAlignment = Alignment.Center,
            ) {
                com.lastwave.app.ui.common.ExpressiveLoadingIndicator(message = "Loading your listening history")
            }
            return@Scaffold
        }

        val listState = rememberLazyListState()
        val rows by viewModel.rows.collectAsStateWithLifecycle()
        val isRecentChronological = uiState.sortMode == HomeSortMode.RECENT
        val playbackQueue = remember(rows, isRecentChronological) {
            val raw = rows.mapNotNull { row ->
                (row as? HomeRow.Track)?.track?.let { track ->
                    com.lastwave.app.playback.PlayableTrack(
                        title = track.name,
                        artist = track.artist,
                        artworkUrl = track.artworkUrl,
                        videoId = track.videoId,
                    )
                }
            }
            if (isRecentChronological) raw.asReversed() else raw
        }
        val playbackIndexByRow = remember(rows, isRecentChronological) {
            val totalTracks = rows.count { it is HomeRow.Track }
            var nextPlaybackIndex = 0
            IntArray(rows.size) { rowIndex ->
                if (rows[rowIndex] is HomeRow.Track) {
                    val idx = nextPlaybackIndex++
                    if (isRecentChronological) (totalTracks - 1 - idx).coerceAtLeast(0) else idx
                } else {
                    -1
                }
            }
        }
        val rowKeys = remember(rows) {
            val seen = HashMap<String, Int>(rows.size)
            Array(rows.size) { i ->
                val base = when (val row = rows[i]) {
                    is HomeRow.DateHeader -> "date_${row.label}"
                    is HomeRow.Track -> if (row.track.isNowPlaying) {
                        "nowplaying_${row.track.key}"
                    } else {
                        "track_${row.track.key}_${row.track.timestampMillis}"
                    }
                    is HomeRow.Album -> "album_${row.album.artist}_${row.album.name}"
                    is HomeRow.Artist -> "artist_${row.artist.name}"
                }
                val n = seen[base] ?: 0
                seen[base] = n + 1
                if (n == 0) base else "${base}#$n"
            }
        }
        val musicPlayer = com.lastwave.app.ui.player.LocalMusicPlayer.current
        var miniTrayRowIndex by remember { mutableStateOf<Int?>(null) }

        LaunchedEffect(listState, uiState.allTracks.size) {
            snapshotFlowNearEnd(listState) { viewModel.loadNextPage() }
        }

        val showLocalBanner = uiState.isLocalStatsMode && !uiState.isViewingFriend
        val stats = uiState.stats
        val headlineLabel = if (uiState.isLocalStatsMode && !uiState.isViewingFriend) "Plays" else "Scrobbles"

        Box(
            modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .fillMaxSize()
                    .adaptiveContentWidth(maxWidth = 860.dp)
                    .safeHorizontalContentPadding(),
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        top = 4.dp,
                        bottom = FloatingNavDefaults.contentBottomPadding() + 16.dp,
                    ),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "stats_header_row", contentType = "header_row") {
                        HeaderRow(
                            displayUsername = when {
                                uiState.isViewingFriend -> uiState.viewingUsername
                                uiState.username.isNotBlank() -> uiState.username
                                uiState.isLocalStatsMode -> "Guest"
                                else -> uiState.username
                            },
                            isViewingFriend = uiState.isViewingFriend,
                            onClick = onOpenFriends,
                            viewModel = viewModel,
                        )
                        Spacer(Modifier.height(6.dp))
                    }

                    if (showLocalBanner) {
                        item(key = "local_stats_banner", contentType = "banner") {
                            LocalStatsBanner(
                                isOfflineFallback = uiState.isLastFmConnected,
                                onClick = if (uiState.isLastFmConnected) viewModel::refresh else onOpenSettings,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }

                    if (stats != null) {
                        item(key = "stats_hero_card", contentType = "stats_card") {
                            StatsCard(
                                scrobbles = stats.scrobbles,
                                trackCount = stats.trackCount,
                                artistCount = stats.artistCount,
                                albumCount = stats.albumCount,
                                headlineLabel = headlineLabel,
                                topGenres = uiState.topTags,
                                onOpenGenres = onOpenGenres,
                                onSelectGenre = { genre ->
                                    genreExplorer.explore(genre)
                                    onOpenGenres()
                                },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    if (!uiState.isViewingFriend) {
                        item(key = "stats_quick_strip", contentType = "quick_strip") {
                            StatsHubQuickStrip(
                                streakDays = uiState.currentStreakDays,
                                pendingSyncCount = uiState.pendingSyncCount,
                                onOpenStatsHub = onOpenListeningStats,
                                onOpenWrapped = { showWrapped = true },
                                onOpenGenres = onOpenGenres,
                                onOpenHistory = { showScrobbleHistory = true },
                                onOpenScrobbler = { showScrobblerControls = true },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    }

                    item(key = "stats_list_header", contentType = "list_header") {
                        Surface(
                            shape = ListContainerShape,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)) {
                                MixHeader(sortMode = uiState.sortMode, onSortModeChange = viewModel::setSortMode)
                            }
                        }
                    }

                    itemsIndexed(
                        rows,
                        key = { index, _ -> rowKeys.getOrNull(index) ?: index.toString() },
                        contentType = { _, row ->
                            when (row) {
                                is HomeRow.DateHeader -> "date"
                                is HomeRow.Track -> "track"
                                is HomeRow.Album -> "album"
                                is HomeRow.Artist -> "artist"
                            }
                        },
                    ) { rowIndex, row ->
                        val isLast = rowIndex == rows.lastIndex
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .then(
                                    if (isLast) Modifier.clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                                    else Modifier,
                                )
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .padding(horizontal = 8.dp)
                                .then(if (isLast) Modifier.padding(bottom = 10.dp) else Modifier),
                        ) {
                            when (row) {
                                is HomeRow.DateHeader -> DateHeaderRow(row.label)
                                is HomeRow.Track -> TrackRow(
                                    track = row.track,
                                    badge = row.badge,
                                    onClick = {
                                        musicPlayer.playQueue(
                                            tracks = playbackQueue,
                                            startIndex = playbackIndexByRow.getOrElse(rowIndex) { 0 },
                                            sourceLabel = "Stats",
                                        )
                                    },
                                    onLongClick = { miniTrayRowIndex = rowIndex },
                                    onMenuClick = { menuTrack = row.track },
                                )
                                is HomeRow.Album -> AlbumRow(
                                    album = row.album,
                                    badge = row.badge,
                                    onClick = {
                                        artistAlbumNavigator.openAlbum(
                                            title = row.album.name,
                                            artist = row.album.artist,
                                            browseId = "",
                                        )
                                    },
                                )
                                is HomeRow.Artist -> ArtistRow(
                                    artist = row.artist,
                                    badge = row.badge,
                                    onClick = {
                                        artistAlbumNavigator.openArtist(
                                            name = row.artist.name,
                                            browseId = "",
                                        )
                                    },
                                )
                            }
                        }
                    }

                    if (rows.isEmpty()) {
                        item(key = "empty", contentType = "empty") {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .padding(vertical = 40.dp, horizontal = 20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(R.string.home_no_tracks),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                miniTrayRowIndex?.let { trayIndex ->
                    (rows.getOrNull(trayIndex) as? HomeRow.Track)?.let { trayRow ->
                        TrackMiniTraySheet(
                            data = TrackMiniTrayData(
                                title = trayRow.track.name,
                                artist = trayRow.track.artist,
                                artworkUrl = trayRow.track.artworkUrl,
                                sourceLabel = "Stats",
                                onPlay = {
                                    musicPlayer.playQueue(
                                        tracks = playbackQueue,
                                        startIndex = playbackIndexByRow.getOrElse(trayIndex) { 0 },
                                        sourceLabel = "Stats",
                                    )
                                },
                            ),
                            onDismiss = { miniTrayRowIndex = null },
                        )
                    }
                }
            }
        }
    }

    menuTrack?.let { track ->
        com.lastwave.app.ui.common.TrackContextMenuSheet(
            target = com.lastwave.app.ui.common.TrackMenuTarget.Track(track.name, track.artist, track.artworkUrl.orEmpty()),
            capabilities = com.lastwave.app.ui.common.TrackMenuCapabilities(showDeleteScrobble = true),
            playbackSourceLabel = "Stats",
            onDismiss = { menuTrack = null },
            onExploreGenre = { genre ->
                menuTrack = null
                genreExplorer.explore(genre)
                onOpenGenres()
            },
        )
    }

    if (showJamSheet) {
        com.lastwave.app.ui.jam.LastWaveJamSheet(
            viewModel = jamViewModel,
            onDismiss = { showJamSheet = false },
        )
    }

    if (showWrapped) {
        com.lastwave.app.ui.stats.OnyxWrappedScreen(
            onClose = { showWrapped = false },
        )
    }

    if (showScrobbleHistory) {
        val musicPlayer = com.lastwave.app.ui.player.LocalMusicPlayer.current
        com.lastwave.app.ui.stats.ScrobbleHistorySheet(
            events = uiState.recentScrobbleEvents,
            onDismiss = { showScrobbleHistory = false },
            onToggleLove = viewModel::toggleScrobbleLove,
            onDelete = viewModel::deleteScrobbleEvent,
            onEdit = { ev, title, artist, album, saveRule, retro ->
                viewModel.editScrobbleEvent(ev, title, artist, album, saveRule, retro)
            },
            onPlayTrack = { ev ->
                musicPlayer.playQueue(
                    tracks = listOf(
                        com.lastwave.app.playback.PlayableTrack(
                            title = ev.title,
                            artist = ev.artist,
                            album = ev.album.ifBlank { null },
                            artworkUrl = ev.artworkUrl,
                        ),
                    ),
                    startIndex = 0,
                    sourceLabel = "Scrobble History",
                )
            },
            onChangeCategory = viewModel::changeScrobbleCategory,
        )
    }
}

@Composable
private fun StatsHubQuickStrip(
    streakDays: Int,
    pendingSyncCount: Int,
    onOpenStatsHub: () -> Unit,
    onOpenWrapped: () -> Unit,
    onOpenGenres: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenScrobbler: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Surface(
                onClick = onOpenStatsHub,
                shape = BadgePillShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.BarChart,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (streakDays > 0) "Insights \u00b7 ${streakDays}d" else "Insights Hub",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                    )
                }
            }
        }
        item {
            Surface(
                onClick = onOpenWrapped,
                shape = BadgePillShape,
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Wrapped",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 1,
                    )
                }
            }
        }
        item {
            Surface(
                onClick = onOpenGenres,
                shape = BadgePillShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Genres",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
        item {
            Surface(
                onClick = onOpenHistory,
                shape = BadgePillShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Play Feed",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
        item {
            Surface(
                onClick = onOpenScrobbler,
                shape = BadgePillShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (pendingSyncCount > 0) "Scrobbler ($pendingSyncCount)" else "Scrobbler",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

internal suspend fun snapshotFlowNearEnd(listState: LazyListState, onNearEnd: () -> Unit) {
    snapshotFlow {
        val info = listState.layoutInfo
        val total = info.totalItemsCount
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        total > 0 && lastVisible >= total - 5
    }.collect { isNear -> if (isNear) onNearEnd() }
}

@dagger.hilt.android.lifecycle.HiltViewModel
class ArtistAlbumNavBridgeHome @javax.inject.Inject constructor(
    val navigator: com.lastwave.app.ui.navigation.ArtistAlbumNavigator,
) : androidx.lifecycle.ViewModel()

@dagger.hilt.android.lifecycle.HiltViewModel
class GenreExplorerBridgeHome @javax.inject.Inject constructor(
    val explorer: com.lastwave.app.ui.genres.GenreExplorer,
) : androidx.lifecycle.ViewModel()
