package com.lastwave.app.ui.stats

import androidx.compose.runtime.Immutable
import com.lastwave.app.data.scrobble.ActiveNowPlaying
import com.lastwave.app.data.scrobble.HistorySyncProgress
import com.lastwave.app.data.scrobble.ScrobbleEventEntity

enum class MediaCategoryTab(val label: String) {
    MUSIC("Music"),
    OTHER("Videos & Other"),
    ALL("All"),
}

enum class StatsTimeRange(val label: String) {
    TODAY("Today"),
    WEEK("7 Days"),
    MONTH_TO_DATE("30 Days"),
    YEAR_TO_DATE("This Year"),
    ALL_TIME("Lifetime"),
    CUSTOM("Custom"),
}

enum class TimelineMetric(val label: String) {
    LISTENING_TIME("Listening time"),
    PLAY_COUNT("Play count"),
    AVG_SESSION("Avg. session"),
}

enum class CategoryFilter(val label: String) {
    SONG("Tracks"),
    ARTIST("Artists"),
    ALBUM("Albums"),
    GENRE("Genres"),
}

@Immutable
data class CustomDateRange(
    val startMs: Long,
    val endMs: Long,
    val label: String,
)

@Immutable
data class HeroKpiSummary(
    val totalListeningLabel: String = "0m",
    val totalScrobbles: Int = 0,
    val uniqueTracks: Int = 0,
    val uniqueArtists: Int = 0,
    val uniqueAlbums: Int = 0,
    val currentStreakDays: Int = 0,
    val longestStreakDays: Int = 0,
    val activeSourcesCount: Int = 0,
)

@Immutable
data class SourceAppBreakdown(
    val packageName: String,
    val appName: String,
    val playCount: Int,
    val listeningLabel: String,
    val fraction: Float,
)

@Immutable
data class TimelineBar(
    val label: String,
    val value: Float,
    val valueLabel: String,
    val isPeak: Boolean = false,
)

@Immutable
data class RankedCategoryItem(
    val rank: Int,
    val title: String,
    val subtitle: String,
    val durationLabel: String,
    val progress: Float,
    val artworkUrl: String? = null,
    val artist: String = "",
    val playCount: Int = 0,
    val sourceSummary: String = "",
)

@Immutable
data class ListeningHabitsData(
    val totalSessions: Int,
    val avgSessionLabel: String,
    val longestSessionLabel: String,
    val sessionsPerDay: String,
    val mostActiveDay: String,
    val peakTimelineSlot: String,
)

@Immutable
data class TrackConcentrationData(
    val top3SharePercent: Int,
    val avgPlaysPerTrack: String,
    val uniqueTracksCount: Int,
    val top1Percent: Int,
    val top1DurationLabel: String,
    val top2To3Percent: Int,
    val top2To3DurationLabel: String,
    val othersPercent: Int,
    val othersDurationLabel: String,
)

@Immutable
data class RankedTrackItem(
    val rank: Int,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val playCount: Int,
    val durationLabel: String,
    val progress: Float,
    val album: String = "",
    val sourceSummary: String = "",
)

@Immutable
data class PersonalRecordsData(
    val bestDayPlays: Int,
    val bestDayDurationLabel: String,
    val bestDayDate: String,
    val bestWeekPlays: Int,
    val bestWeekDurationLabel: String,
    val bestMonthPlays: Int,
    val bestMonthDurationLabel: String,
    val longestStreakDays: Int,
    val currentStreakDays: Int = 0,
)

@Immutable
data class WeeklyRhythmData(
    val weekdayAvgLabel: String,
    val weekendAvgLabel: String,
)

@Immutable
data class MonthComparisonData(
    val thisMonthLabel: String,
    val lastMonthLabel: String,
    val progress: Float,
)

@Immutable
data class HourlyRhythmBar(
    val hour: Int,
    val label: String,
    val count: Int,
    val fraction: Float,
    val isPeak: Boolean = false,
    val durationLabel: String = "0m",
)

@Immutable
data class HeatmapDay(
    val dateString: String,
    val dayOfWeek: Int,
    val weekIndex: Int,
    val intensity: Int,
    val durationLabel: String,
    val playCount: Int = 0,
)

@Immutable
data class MatrixHeatmapCell(
    val dayOfWeek: Int,
    val hour: Int,
    val playCount: Int,
    val intensity: Int,
    val durationLabel: String,
)

@Immutable
data class ConsistencyScoreData(
    val score: Int,
    val rating: String,
    val subtitle: String,
    val avgSessionMinutes: Int,
    val activeDaysThisWeek: Int,
    val streakDays: Int,
)

@Immutable
data class ListeningStage(
    val name: String,
    val percent: Int,
    val durationLabel: String,
    val description: String,
)

@Immutable
data class ListeningStagesData(
    val deepFocus: ListeningStage,
    val flowState: ListeningStage,
    val discovery: ListeningStage,
)

@Immutable
data class ArtistDetailStats(
    val artistName: String,
    val artworkUrl: String?,
    val totalPlays: Int,
    val totalListeningLabel: String,
    val uniqueTracks: Int,
    val firstPlayedLabel: String,
    val lastPlayedLabel: String,
    val peakDayLabel: String,
    val timelineBars: List<TimelineBar>,
    val sourceBreakdown: List<SourceAppBreakdown>,
    val topTracks: List<RankedTrackItem>,
)

@Immutable
data class TrackDetailStats(
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String?,
    val totalPlays: Int,
    val totalListeningLabel: String,
    val isLoved: Boolean,
    val firstPlayedLabel: String,
    val lastPlayedLabel: String,
    val peakDayLabel: String,
    val timelineBars: List<TimelineBar>,
    val sourceBreakdown: List<SourceAppBreakdown>,
    val recentEvents: List<ScrobbleEventEntity>,
)

@Immutable
data class ListeningStatsUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val selectedRange: StatsTimeRange = StatsTimeRange.ALL_TIME,
    val customRange: CustomDateRange? = null,
    val selectedTimelineMetric: TimelineMetric = TimelineMetric.LISTENING_TIME,
    val selectedCategory: CategoryFilter = CategoryFilter.SONG,
    val selectedMediaTab: MediaCategoryTab = MediaCategoryTab.MUSIC,
    val musicScrobbleCount: Int = 0,
    val otherScrobbleCount: Int = 0,
    val isBackfillingGenres: Boolean = false,
    val syncProgress: HistorySyncProgress = HistorySyncProgress(),
    /** null = every source app (the default). */
    val selectedSource: String? = null,
    val availableSources: List<String> = emptyList(),
    val heroKpis: HeroKpiSummary = HeroKpiSummary(),
    val totalListeningLabel: String = "0m",
    val totalPlays: Int = 0,
    val sourceBreakdown: List<SourceAppBreakdown> = emptyList(),
    val timelineBars: List<TimelineBar> = emptyList(),
    val peakSegmentName: String = "",
    val peakSegmentDuration: String = "",
    val categoryItems: List<RankedCategoryItem> = emptyList(),
    val habits: ListeningHabitsData? = null,
    val concentration: TrackConcentrationData? = null,
    val tracksInRange: List<RankedTrackItem> = emptyList(),
    val records: PersonalRecordsData? = null,
    val weeklyRhythm: WeeklyRhythmData? = null,
    val monthComparison: MonthComparisonData? = null,
    val hourlyRhythm: List<HourlyRhythmBar> = emptyList(),
    val matrixHeatmap: List<MatrixHeatmapCell> = emptyList(),
    val activityHeatmap: List<HeatmapDay> = emptyList(),
    val consistency: ConsistencyScoreData? = null,
    val stages: ListeningStagesData? = null,
    val recentScrobbles: List<ScrobbleEventEntity> = emptyList(),
    val nowPlaying: ActiveNowPlaying? = null,
    val pendingSyncCount: Int = 0,
    val selectedArtistDetail: ArtistDetailStats? = null,
    val selectedTrackDetail: TrackDetailStats? = null,
    val statusBanner: String? = null,
)
