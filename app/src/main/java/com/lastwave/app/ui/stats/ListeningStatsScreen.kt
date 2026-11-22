package com.lastwave.app.ui.stats

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.R
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.player.LocalMusicPlayer
import com.lastwave.app.ui.settings.ScrobblerSettingsScreen
import com.lastwave.app.ui.shell.FloatingNavDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningStatsScreen(
    onBack: () -> Unit,
    viewModel: ListeningStatsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val musicPlayer = LocalMusicPlayer.current

    var showHistorySheet by remember { mutableStateOf(false) }
    var showScrobblerControls by remember { mutableStateOf(false) }
    var showCustomDatePicker by remember { mutableStateOf(false) }
    var showWrapped by remember { mutableStateOf(false) }

    if (showScrobblerControls) {
        ScrobblerSettingsScreen(
            onBack = {
                showScrobblerControls = false
                viewModel.refresh()
            },
        )
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .adaptiveContentWidth(maxWidth = 860.dp)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.stats_hub_title),
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 22.sp),
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showWrapped = true }) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = "Onyx Wrapped",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = { showHistorySheet = true }) {
                        Icon(
                            imageVector = Icons.Filled.History,
                            contentDescription = stringResource(R.string.stats_open_history_cd),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = { showScrobblerControls = true }) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = stringResource(R.string.stats_open_scrobbler_cd),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = viewModel::refresh) {
                        if (uiState.isRefreshing) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.stats_refresh_cd),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        },
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .adaptiveContentWidth(maxWidth = 860.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = FloatingNavDefaults.contentBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // ── LIVE NOW PLAYING / SYNC BANNER ──
            uiState.nowPlaying?.let { np ->
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Now scrobbling via ${np.sourceAppName.ifBlank { "Onyx Music" }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "${np.title} \u00b7 ${np.artist}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.pendingSyncCount > 0) {
                item {
                    Surface(
                        onClick = viewModel::flushPendingSync,
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CloudQueue,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "${uiState.pendingSyncCount} scrobbles queued offline \u00b7 Tap to sync",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }

            uiState.statusBanner?.let { banner ->
                item {
                    Surface(
                        onClick = viewModel::clearStatusBanner,
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = banner,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            // ── TIME WINDOW CHIPS ──
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(StatsTimeRange.entries) { range ->
                            val chipLabel = if (range == StatsTimeRange.CUSTOM && uiState.customRange != null) {
                                uiState.customRange!!.label
                            } else {
                                range.label
                            }
                            StatsFilterChip(
                                label = chipLabel,
                                selected = uiState.selectedRange == range,
                                onClick = {
                                    if (range == StatsTimeRange.CUSTOM) {
                                        showCustomDatePicker = true
                                    } else {
                                        viewModel.setTimeRange(range)
                                    }
                                },
                            )
                        }
                    }

                    if (uiState.availableSources.size > 1) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            item {
                                StatsFilterChip(
                                    label = "All sources",
                                    selected = uiState.selectedSource == null,
                                    onClick = { viewModel.setSource(null) },
                                )
                            }
                            items(uiState.availableSources) { source ->
                                StatsFilterChip(
                                    label = source,
                                    selected = uiState.selectedSource == source,
                                    onClick = { viewModel.setSource(source) },
                                )
                            }
                        }
                    }

                    // Quick Hub Action Bar (Scrobble Feed + Scrobbler Controls)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            onClick = { showHistorySheet = true },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Play Feed (${uiState.recentScrobbles.size})",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        Surface(
                            onClick = { showScrobblerControls = true },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Tune,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Scrobbler & Rules",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }

            // ── HERO KPI GRID ──
            item {
                HeroKpiGrid(kpis = uiState.heroKpis)
            }

            // ── SOURCE APP BREAKDOWN ──
            if (uiState.sourceBreakdown.isNotEmpty()) {
                item {
                    SourceAppBreakdownCard(
                        title = "Listening by source app",
                        sources = uiState.sourceBreakdown,
                    )
                }
            }

            // ── TOP RANKINGS (TRACKS / ARTISTS / ALBUMS / GENRES) ──
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Top charts",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(CategoryFilter.entries) { cat ->
                            StatsFilterChip(
                                label = cat.label,
                                selected = uiState.selectedCategory == cat,
                                onClick = { viewModel.setCategory(cat) },
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Top ${uiState.selectedCategory.label.lowercase()}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(12.dp))
                            if (uiState.categoryItems.isEmpty()) {
                                Text(
                                    text = "No ${uiState.selectedCategory.label.lowercase()} captured in this window yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 10.dp),
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    uiState.categoryItems.forEach { item ->
                                        val clickAction: (() -> Unit)? = when (uiState.selectedCategory) {
                                            CategoryFilter.SONG -> ({
                                                viewModel.openTrackDetail(item.title, item.artist)
                                            })
                                            CategoryFilter.ARTIST -> ({
                                                viewModel.openArtistDetail(item.title)
                                            })
                                            CategoryFilter.ALBUM -> if (item.artist.isNotBlank()) {
                                                { viewModel.openArtistDetail(item.artist) }
                                            } else null
                                            CategoryFilter.GENRE -> null
                                        }
                                        RankedCategoryRow(item = item, onClick = clickAction)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── 24-HOUR RADIAL LISTENING CLOCK ──
            if (uiState.hourlyRhythm.isNotEmpty()) {
                item {
                    ListeningClockCard(bars = uiState.hourlyRhythm)
                }
            }

            // ── 7x24 MATRIX & 12-WEEK CALENDAR HEATMAP ──
            if (uiState.matrixHeatmap.isNotEmpty() || uiState.activityHeatmap.isNotEmpty()) {
                item {
                    ListeningActivityHeatmapCard(
                        matrixCells = uiState.matrixHeatmap,
                        calendarDays = uiState.activityHeatmap,
                    )
                }
            }

            // ── LISTENING TIMELINE ──
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Listening timeline",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(TimelineMetric.entries) { metric ->
                            StatsFilterChip(
                                label = metric.label,
                                selected = uiState.selectedTimelineMetric == metric,
                                onClick = { viewModel.setTimelineMetric(metric) },
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${uiState.selectedRange.label} progression",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Text(
                                        text = uiState.selectedTimelineMetric.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            ListeningStatsBarChart(bars = uiState.timelineBars)
                        }
                    }

                    if (uiState.peakSegmentName.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.TrendingUp,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                Spacer(Modifier.width(14.dp))
                                Column {
                                    Text(
                                        text = "Peak segment",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = "${uiState.peakSegmentName} \u00b7 ${uiState.peakSegmentDuration}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── RHYTHM, STAGES & PERSONAL RECORDS ──
            uiState.consistency?.let { consistency ->
                item { ConsistencyScoreCard(data = consistency) }
            }
            uiState.stages?.let { stages ->
                item { ListeningStagesCard(stages = stages) }
            }
            uiState.records?.let { records ->
                item { PersonalRecordsCard(records = records) }
            }
            uiState.weeklyRhythm?.let { rhythm ->
                item { WeeklyRhythmCard(rhythm = rhythm) }
            }
            uiState.monthComparison?.let { comparison ->
                item { MonthComparisonCard(comparison = comparison) }
            }

            // ── LISTENING HABITS ──
            uiState.habits?.let { habits ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Listening habits",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                        )
                        HabitItemRow("Total sessions", habits.totalSessions.toString(), Icons.Filled.History)
                        HabitItemRow("Average session", habits.avgSessionLabel, Icons.Filled.Timer)
                        HabitItemRow("Longest session", habits.longestSessionLabel, Icons.Filled.Bolt)
                        HabitItemRow("Sessions per day", habits.sessionsPerDay, Icons.Filled.AutoAwesome)
                        HabitItemRow("Most active weekday", habits.mostActiveDay, Icons.Filled.CalendarMonth)
                        HabitItemRow("Peak listening day", habits.peakTimelineSlot, Icons.Filled.TrendingUp)
                    }
                }
            }

            // ── TRACK CONCENTRATION ──
            uiState.concentration?.let { conc ->
                item {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            DonutConcentrationChart(percentage = conc.top3SharePercent)
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = "Listening concentration",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Top 3 tracks represent ${conc.top3SharePercent}% of listening time (${conc.avgPlaysPerTrack} avg plays/track).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(14.dp))
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                ConcentrationRow("Top 1", conc.top1Percent, conc.top1DurationLabel, MaterialTheme.colorScheme.primary)
                                ConcentrationRow("Top 2-3", conc.top2To3Percent, conc.top2To3DurationLabel, MaterialTheme.colorScheme.tertiary)
                                ConcentrationRow("Others", conc.othersPercent, conc.othersDurationLabel, MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }

            // ── TOP TRACKS IN RANGE (TAPPABLE FOR DEEP DIVE) ──
            if (uiState.tracksInRange.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Most played tracks",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                        )
                        uiState.tracksInRange.forEach { track ->
                            RankedTrackCard(
                                track = track,
                                onClick = { viewModel.openTrackDetail(track.title, track.artist) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showHistorySheet) {
        ScrobbleHistorySheet(
            events = uiState.recentScrobbles,
            onDismiss = { showHistorySheet = false },
            onToggleLove = viewModel::toggleLoveForEvent,
            onDelete = viewModel::deleteScrobble,
            onEdit = viewModel::editScrobble,
            onPlayTrack = { ev ->
                musicPlayer.playQueue(
                    tracks = listOf(
                        PlayableTrack(
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
        )
    }

    uiState.selectedArtistDetail?.let { artistDetail ->
        ArtistDetailStatsSheet(
            detail = artistDetail,
            onDismiss = viewModel::dismissArtistDetail,
            onSelectTrack = { title, artist ->
                viewModel.dismissArtistDetail()
                viewModel.openTrackDetail(title, artist)
            },
        )
    }

    uiState.selectedTrackDetail?.let { trackDetail ->
        TrackDetailStatsSheet(
            detail = trackDetail,
            onDismiss = viewModel::dismissTrackDetail,
            onPlay = {
                musicPlayer.playQueue(
                    tracks = listOf(
                        PlayableTrack(
                            title = trackDetail.title,
                            artist = trackDetail.artist,
                            album = trackDetail.album.ifBlank { null },
                            artworkUrl = trackDetail.artworkUrl,
                        ),
                    ),
                    startIndex = 0,
                    sourceLabel = "Listening Stats",
                )
            },
            onToggleLove = viewModel::toggleLoveForSelectedTrack,
        )
    }

    if (showCustomDatePicker) {
        val pickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showCustomDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val s = pickerState.selectedStartDateMillis
                        val e = pickerState.selectedEndDateMillis ?: s
                        if (s != null && e != null) {
                            // Extend end date to 23:59:59.999
                            viewModel.setCustomRange(s, e + 86_399_999L)
                        }
                        showCustomDatePicker = false
                    },
                    enabled = pickerState.selectedStartDateMillis != null,
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDatePicker = false }) {
                    Text("Cancel")
                }
            },
        ) {
            DateRangePicker(
                state = pickerState,
                modifier = Modifier.height(420.dp),
            )
        }
    }

    if (showWrapped) {
        OnyxWrappedScreen(onClose = { showWrapped = false })
    }
}

@Composable
private fun ConcentrationRow(
    label: String,
    percentage: Int,
    duration: String,
    dotColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${percentage}%",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = duration,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
