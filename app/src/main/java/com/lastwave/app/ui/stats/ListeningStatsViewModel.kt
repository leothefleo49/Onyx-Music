package com.lastwave.app.ui.stats

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.generate.TasteProfileProvider
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.local.db.DownloadedTrackEntity
import com.lastwave.app.data.local.db.SongPlayStatsDao
import com.lastwave.app.data.local.db.SongPlayStatsEntity
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.repository.HomeRepository
import com.lastwave.app.data.repository.LocalStatsRepository
import com.lastwave.app.data.scrobble.ActiveNowPlaying
import com.lastwave.app.data.scrobble.ScrobbleEventDao
import com.lastwave.app.data.scrobble.ScrobbleEventEntity
import com.lastwave.app.data.scrobble.ScrobbleMediaCategory
import com.lastwave.app.data.scrobble.ScrobbleRulesEngine
import com.lastwave.app.data.scrobble.ScrobbleSyncEngine
import com.lastwave.app.data.scrobble.StreakSummary
import com.lastwave.app.data.scrobble.UniversalHistorySyncManager
import com.lastwave.app.data.scrobble.normalizeScrobbleTrackKey
import com.lastwave.app.playback.MediaType
import com.lastwave.app.playback.MusicPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.lastwave.app.data.genre.GenreNormalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class ListeningStatsViewModel @Inject constructor(
    private val scrobbleEventDao: ScrobbleEventDao,
    private val scrobbleSyncEngine: ScrobbleSyncEngine,
    private val historySyncManager: UniversalHistorySyncManager,
    private val musicPlayer: MusicPlayer,
    private val songPlayStatsDao: SongPlayStatsDao,
    private val localStatsRepository: LocalStatsRepository,
    private val playlistRepository: PlaylistRepository,
    private val downloadedTrackDao: DownloadedTrackDao,
    private val homeRepository: HomeRepository,
    private val sessionPreferences: SessionPreferences,
    private val tasteProfileProvider: TasteProfileProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ListeningStatsUiState())
    val uiState: StateFlow<ListeningStatsUiState> = _uiState.asStateFlow()

    private var allEvents: List<ScrobbleEventEntity> = emptyList()
    private var rawSongPlayStats: List<SongPlayStatsEntity> = emptyList()
    private var localStatsCache: LocalStatsRepository.LocalStats? = null
    private var tasteTags: List<String> = emptyList()
    private var downloadedFallbackList: List<DownloadedTrackEntity> = emptyList()
    private var autoGenreBackfillTriggered = false
    private var recalcJob: Job? = null

    init {
        viewModelScope.launch {
            scrobbleEventDao.observeAll().collect { events ->
                allEvents = events
                recalculate()
                maybeTriggerGenreBackfill(events)
            }
        }
        viewModelScope.launch {
            combine(
                musicPlayer.chromeState,
                scrobbleSyncEngine.nowPlaying,
            ) { chrome, externalNp ->
                val current = chrome.current
                when {
                    chrome.isPlaying && current != null && current.mediaType == MediaType.SONG && current.showId.isNullOrBlank() ->
                        ActiveNowPlaying(
                            title = current.title,
                            artist = current.artist,
                            album = current.album.orEmpty(),
                            sourcePackage = "onyx.internal",
                            sourceAppName = "Onyx Music",
                            mediaCategory = ScrobbleMediaCategory.MUSIC,
                        )
                    externalNp != null -> externalNp
                    else -> null
                }
            }.collect { np ->
                _uiState.update { it.copy(nowPlaying = np) }
            }
        }
        viewModelScope.launch {
            scrobbleSyncEngine.pendingSyncCount.collect { pending ->
                _uiState.update { it.copy(pendingSyncCount = pending) }
            }
        }
        viewModelScope.launch {
            historySyncManager.progress.collect { progress ->
                _uiState.update { it.copy(syncProgress = progress) }
            }
        }
        loadAuxiliaryData()
    }

    private fun maybeTriggerGenreBackfill(events: List<ScrobbleEventEntity>) {
        if (autoGenreBackfillTriggered) return
        val musicEvents = events.filter { ScrobbleMediaCategory.isMusic(it.mediaCategory) }
        if (musicEvents.isEmpty()) return
        val taggedCount = musicEvents.count { it.genre.isNotBlank() }
        if (taggedCount < (musicEvents.size / 4).coerceAtLeast(3)) {
            autoGenreBackfillTriggered = true
            backfillGenresNow(silent = true)
        }
    }

    fun setMediaTab(tab: MediaCategoryTab) {
        _uiState.update { it.copy(selectedMediaTab = tab, selectedSource = null) }
        recalculate()
    }

    fun backfillGenresNow(silent: Boolean = false) {
        if (_uiState.value.isBackfillingGenres) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isBackfillingGenres = true) }
            val count = runCatching { scrobbleSyncEngine.backfillMissingGenres(maxArtists = 35) }.getOrDefault(0)
            _uiState.update {
                it.copy(
                    isBackfillingGenres = false,
                    statusBanner = if (!silent && count > 0) "Detected genres for $count artists" else it.statusBanner,
                )
            }
        }
    }

    fun syncLastFmNow() = historySyncManager.syncLastFmFullHistory()
    fun syncListenBrainzNow() = historySyncManager.syncListenBrainzFullHistory()
    fun syncYouTubeMusicNow() = historySyncManager.syncYouTubeMusicHistory()
    fun syncSpotifyRecentNow() = historySyncManager.syncSpotifyRecentHistory()
    fun deduplicateHistoryNow() = historySyncManager.deduplicateHistory()
    fun importHistoryFromUri(uri: Uri, fileNameHint: String? = null) = historySyncManager.importFromUri(uri, fileNameHint)
    fun cancelHistorySync() = historySyncManager.cancelActiveSync()
    fun dismissSyncBanner() = historySyncManager.clearBanner()

    fun changeScrobbleCategory(event: ScrobbleEventEntity, newCategory: String) {
        viewModelScope.launch(Dispatchers.IO) {
            scrobbleSyncEngine.updateScrobbleCategory(event.id, newCategory)
            val label = if (ScrobbleMediaCategory.isMusic(newCategory)) "Music" else "Videos & Other"
            _uiState.update { it.copy(statusBanner = "Moved \"${event.title}\" to $label") }
        }
    }

    fun setTimeRange(range: StatsTimeRange) {
        _uiState.update { it.copy(selectedRange = range) }
        recalculate()
    }

    fun setCustomRange(startMs: Long, endMs: Long) {
        val normalizedStart = minOf(startMs, endMs)
        val normalizedEnd = maxOf(startMs, endMs)
        val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        val label = "${fmt.format(Date(normalizedStart))} - ${fmt.format(Date(normalizedEnd))}"
        _uiState.update {
            it.copy(
                selectedRange = StatsTimeRange.CUSTOM,
                customRange = CustomDateRange(normalizedStart, normalizedEnd, label),
            )
        }
        recalculate()
    }

    fun setTimelineMetric(metric: TimelineMetric) {
        _uiState.update { it.copy(selectedTimelineMetric = metric) }
        recalculate()
    }

    fun setCategory(category: CategoryFilter) {
        _uiState.update { it.copy(selectedCategory = category) }
        recalculate()
    }

    fun setSource(source: String?) {
        _uiState.update { it.copy(selectedSource = source) }
        recalculate()
    }

    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true) }
        loadAuxiliaryData()
    }

    fun openArtistDetail(artistName: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val clean = artistName.trim()
            if (clean.isBlank()) return@launch
            val matching = filteredSourcePool(_uiState.value)
                .filter { it.artist.trim().equals(clean, ignoreCase = true) }
            if (matching.isEmpty()) return@launch

            val totalMs = matching.sumOf { effectiveListenMs(it) }
            val uniqueTracks = matching.map { it.trackKey }.distinct().size
            val sortedAsc = matching.sortedBy { it.scrobbledAtMs }
            val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            val firstLabel = dateFmt.format(Date(sortedAsc.first().scrobbledAtMs))
            val lastLabel = dateFmt.format(Date(sortedAsc.last().scrobbledAtMs))
            val peakDayLabel = computePeakDayLabel(matching)
            val timeline = computeCompactTimeline(matching)
            val sources = computeSourceBreakdown(matching)

            val byTrack = matching.groupBy { it.trackKey }.values
                .sortedByDescending { list -> list.sumOf { effectiveListenMs(it) } }
                .take(15)
            val maxTrackMs = byTrack.firstOrNull()?.sumOf { effectiveListenMs(it) }?.coerceAtLeast(1L) ?: 1L
            val topTracks = byTrack.mapIndexed { idx, list ->
                val sample = list.first()
                val ms = list.sumOf { effectiveListenMs(it) }
                RankedTrackItem(
                    rank = idx + 1,
                    title = sample.title,
                    artist = sample.artist,
                    artworkUrl = list.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl,
                    playCount = list.size,
                    durationLabel = formatHoursMins(ms),
                    progress = (ms.toFloat() / maxTrackMs).coerceIn(0.06f, 1f),
                    album = sample.album,
                    sourceSummary = summarizeSources(list),
                )
            }

            _uiState.update {
                it.copy(
                    selectedArtistDetail = ArtistDetailStats(
                        artistName = sortedAsc.last().artist,
                        artworkUrl = matching.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl,
                        totalPlays = matching.size,
                        totalListeningLabel = formatHoursMins(totalMs),
                        uniqueTracks = uniqueTracks,
                        firstPlayedLabel = firstLabel,
                        lastPlayedLabel = lastLabel,
                        peakDayLabel = peakDayLabel,
                        timelineBars = timeline,
                        sourceBreakdown = sources,
                        topTracks = topTracks,
                    ),
                )
            }
        }
    }

    fun dismissArtistDetail() {
        _uiState.update { it.copy(selectedArtistDetail = null) }
    }

    fun openTrackDetail(title: String, artist: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val key = normalizeScrobbleTrackKey(artist, title)
            val matching = filteredSourcePool(_uiState.value).filter { it.trackKey == key }
            if (matching.isEmpty()) return@launch

            val totalMs = matching.sumOf { effectiveListenMs(it) }
            val sortedAsc = matching.sortedBy { it.scrobbledAtMs }
            val latest = sortedAsc.last()
            val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            val firstLabel = dateFmt.format(Date(sortedAsc.first().scrobbledAtMs))
            val lastLabel = dateFmt.format(Date(latest.scrobbledAtMs))
            val peakDayLabel = computePeakDayLabel(matching)

            _uiState.update {
                it.copy(
                    selectedTrackDetail = TrackDetailStats(
                        title = latest.title,
                        artist = latest.artist,
                        album = matching.firstOrNull { it.album.isNotBlank() }?.album.orEmpty(),
                        artworkUrl = matching.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl,
                        totalPlays = matching.size,
                        totalListeningLabel = formatHoursMins(totalMs),
                        isLoved = matching.any { it.isLoved },
                        firstPlayedLabel = firstLabel,
                        lastPlayedLabel = lastLabel,
                        peakDayLabel = peakDayLabel,
                        timelineBars = computeCompactTimeline(matching),
                        sourceBreakdown = computeSourceBreakdown(matching),
                        recentEvents = sortedAsc.reversed(),
                    ),
                )
            }
        }
    }

    fun dismissTrackDetail() {
        _uiState.update { it.copy(selectedTrackDetail = null) }
    }

    fun toggleLoveForEvent(event: ScrobbleEventEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            scrobbleSyncEngine.setTrackLoved(
                artist = event.artist,
                title = event.title,
                loved = !event.isLoved,
                eventId = event.id,
            )
            refreshOpenTrackDetailIfMatching(event.artist, event.title)
        }
    }

    fun toggleLoveForSelectedTrack() {
        val current = _uiState.value.selectedTrackDetail ?: return
        viewModelScope.launch(Dispatchers.IO) {
            scrobbleSyncEngine.setTrackLoved(
                artist = current.artist,
                title = current.title,
                loved = !current.isLoved,
            )
            _uiState.update { state ->
                state.selectedTrackDetail?.let { detail ->
                    state.copy(selectedTrackDetail = detail.copy(isLoved = !detail.isLoved))
                } ?: state
            }
        }
    }

    fun editScrobble(
        event: ScrobbleEventEntity,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        saveAsAutoRule: Boolean,
        applyRetroactively: Boolean,
        newGenre: String = event.genre,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = scrobbleSyncEngine.editScrobbleEvent(
                event = event,
                newTitle = newTitle,
                newArtist = newArtist,
                newAlbum = newAlbum,
                saveAsAutoRule = saveAsAutoRule,
                applyRetroactively = applyRetroactively,
            )
            if (newGenre.trim() != event.genre.trim()) {
                scrobbleSyncEngine.updateFullScrobbleMetadata(
                    id = event.id,
                    title = newTitle.trim().ifBlank { event.title },
                    artist = newArtist.trim().ifBlank { event.artist },
                    album = newAlbum.trim(),
                    genre = newGenre.trim(),
                )
            }
            _uiState.update {
                it.copy(
                    statusBanner = if (saveAsAutoRule) {
                        "Updated $updated play(s) and saved auto-fix rule"
                    } else {
                        "Play updated"
                    },
                )
            }
        }
    }

    fun deleteScrobble(event: ScrobbleEventEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            scrobbleSyncEngine.deleteScrobble(event.id)
        }
    }

    fun flushPendingSync() {
        viewModelScope.launch(Dispatchers.IO) {
            val report = scrobbleSyncEngine.flushPendingQueue()
            val total = report.lastFmSynced + report.listenBrainzSynced
            if (total > 0) {
                _uiState.update { it.copy(statusBanner = "Synced $total queued scrobbles") }
            }
        }
    }

    fun clearStatusBanner() {
        _uiState.update { it.copy(statusBanner = null) }
    }

    private fun refreshOpenTrackDetailIfMatching(artist: String, title: String) {
        val open = _uiState.value.selectedTrackDetail ?: return
        if (normalizeScrobbleTrackKey(open.artist, open.title) == normalizeScrobbleTrackKey(artist, title)) {
            openTrackDetail(open.title, open.artist)
        }
    }

    private fun loadAuxiliaryData() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = allEvents.isEmpty() && rawSongPlayStats.isEmpty()) }
            runCatching {
                rawSongPlayStats = songPlayStatsDao.getAll()
                localStatsCache = runCatching { localStatsRepository.load() }.getOrNull()
                tasteTags = runCatching { tasteProfileProvider.get().topTags.toList() }.getOrNull().orEmpty()
                downloadedFallbackList = runCatching { downloadedTrackDao.getAllList() }.getOrNull().orEmpty()
            }
            recalculate()
            _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
        }
    }

    /**
     * Primary source of truth is [allEvents] from [OnyxScrobbleDatabase].
     * When the user hasn't accumulated scrobble events yet, synthesizes
     * fallback events from [SongPlayStatsDao] / [LocalStatsRepository] / downloads
     * so the Stats Hub never looks empty on first run.
     */
    private fun effectiveEvents(): List<ScrobbleEventEntity> {
        if (allEvents.isNotEmpty()) return allEvents

        if (rawSongPlayStats.isNotEmpty()) {
            return rawSongPlayStats.flatMap { s ->
                val count = s.playCount.coerceIn(1, 20)
                val perPlayMs = (if (s.totalPlayTimeMs > 0L) s.totalPlayTimeMs / count else 210_000L)
                    .coerceAtLeast(30_000L)
                val baseTime = if (s.lastPlayedAtMillis > 0L) s.lastPlayedAtMillis else System.currentTimeMillis()
                (0 until count).map { idx ->
                    val ts = (baseTime - idx * 3_600_000L).coerceAtLeast(1L)
                    ScrobbleEventEntity(
                        id = -(s.trackKey.hashCode().toLong() * 31 + idx),
                        trackKey = normalizeScrobbleTrackKey(s.artist, s.title),
                        title = s.title.ifBlank { "Unknown Title" },
                        artist = s.artist.ifBlank { "Unknown Artist" },
                        artworkUrl = s.artworkUrl,
                        durationMs = perPlayMs,
                        listenedMs = perPlayMs,
                        startedAtMs = ts,
                        scrobbledAtMs = ts,
                        sourcePackage = "com.leothefleo49.onyxmusic",
                        sourceAppName = "Onyx Music",
                    )
                }
            }
        }

        val local = localStatsCache
        if (local != null && local.topTracks.isNotEmpty()) {
            val now = System.currentTimeMillis()
            return local.topTracks.mapIndexed { idx, ht ->
                val ts = ht.timestampMillis ?: (now - idx * 86_400_000L)
                ScrobbleEventEntity(
                    id = -(idx.toLong() + 1L),
                    trackKey = normalizeScrobbleTrackKey(ht.artist, ht.name),
                    title = ht.name,
                    artist = ht.artist,
                    artworkUrl = ht.artworkUrl,
                    durationMs = 210_000L,
                    listenedMs = ht.playCount.coerceAtLeast(1) * 210_000L,
                    startedAtMs = ts,
                    scrobbledAtMs = ts,
                    sourcePackage = "com.leothefleo49.onyxmusic",
                    sourceAppName = "Onyx Music",
                )
            }
        }

        if (downloadedFallbackList.isNotEmpty()) {
            return downloadedFallbackList.mapIndexed { idx, dt ->
                val dur = dt.durationMs.takeIf { it > 0L } ?: 210_000L
                ScrobbleEventEntity(
                    id = -(10_000L + idx),
                    trackKey = normalizeScrobbleTrackKey(dt.artist, dt.title),
                    title = dt.title,
                    artist = dt.artist,
                    album = dt.album,
                    artworkUrl = dt.artworkUrl,
                    durationMs = dur,
                    listenedMs = dur,
                    startedAtMs = dt.downloadedAtMillis,
                    scrobbledAtMs = dt.downloadedAtMillis,
                    sourcePackage = "com.leothefleo49.onyxmusic",
                    sourceAppName = "Onyx Music",
                )
            }
        }
        return emptyList()
    }

    private fun getWindowBounds(range: StatsTimeRange, custom: CustomDateRange?): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        return when (range) {
            StatsTimeRange.TODAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            StatsTimeRange.WEEK -> (now - 7L * 86_400_000L) to now
            StatsTimeRange.MONTH_TO_DATE -> (now - 30L * 86_400_000L) to now
            StatsTimeRange.YEAR_TO_DATE -> {
                cal.set(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            StatsTimeRange.ALL_TIME -> 0L to Long.MAX_VALUE
            StatsTimeRange.CUSTOM -> {
                if (custom != null) custom.startMs to custom.endMs else (now - 30L * 86_400_000L) to now
            }
        }
    }

    private fun filteredSourcePool(state: ListeningStatsUiState): List<ScrobbleEventEntity> {
        val unfilteredPool = effectiveEvents()
        val fullPool = when (state.selectedMediaTab) {
            MediaCategoryTab.MUSIC -> unfilteredPool.filter { ScrobbleMediaCategory.isMusic(it.mediaCategory) }
            MediaCategoryTab.OTHER -> unfilteredPool.filter { !ScrobbleMediaCategory.isMusic(it.mediaCategory) }
            MediaCategoryTab.ALL -> unfilteredPool
        }
        return state.selectedSource?.let { selected ->
            fullPool.filter { it.sourceAppName.ifBlank { "Onyx Music" } == selected }
        } ?: fullPool
    }

    private fun recalculate() {
        recalcJob?.cancel()
        val stateSnapshot = _uiState.value
        val eventsSnapshot = allEvents
        recalcJob = viewModelScope.launch(Dispatchers.Default) {
            val unfilteredPool = effectiveEvents()
            val musicCount = unfilteredPool.count { ScrobbleMediaCategory.isMusic(it.mediaCategory) }
            val otherCount = unfilteredPool.size - musicCount
            val fullPool = when (stateSnapshot.selectedMediaTab) {
                MediaCategoryTab.MUSIC -> unfilteredPool.filter { ScrobbleMediaCategory.isMusic(it.mediaCategory) }
                MediaCategoryTab.OTHER -> unfilteredPool.filter { !ScrobbleMediaCategory.isMusic(it.mediaCategory) }
                MediaCategoryTab.ALL -> unfilteredPool
            }
            val sourceNames = fullPool
                .map { it.sourceAppName.ifBlank { "Onyx Music" } }
                .distinct()
                .sorted()
            val sourcePool = stateSnapshot.selectedSource?.let { selected ->
                fullPool.filter { it.sourceAppName.ifBlank { "Onyx Music" } == selected }
            } ?: fullPool
            val (startMs, endMs) = getWindowBounds(stateSnapshot.selectedRange, stateSnapshot.customRange)

            val inWindow = if (startMs > 0L || endMs < Long.MAX_VALUE) {
                sourcePool.filter { it.scrobbledAtMs in startMs..endMs }
            } else {
                sourcePool
            }

            coroutineContext.ensureActive()

            val totalListeningMs = inWindow.sumOf { effectiveListenMs(it) }
            val totalPlays = inWindow.size
            val uniqueTracks = inWindow.map { it.trackKey }.distinct().size
            val uniqueArtists = inWindow.map { it.artist.trim().lowercase() }.filter { it.isNotBlank() }.distinct().size
            val uniqueAlbums = inWindow.map { it.album.trim().lowercase() }.filter { it.isNotBlank() }.distinct().size
            val sources = computeSourceBreakdown(inWindow)

            val streakSummary = ScrobbleRulesEngine.computeStreaks(sourcePool.map { it.scrobbledAtMs })
            val totalListeningLabel = formatHoursMins(totalListeningMs)

            val heroKpis = HeroKpiSummary(
                totalListeningLabel = totalListeningLabel,
                totalScrobbles = totalPlays,
                uniqueTracks = uniqueTracks,
                uniqueArtists = uniqueArtists,
                uniqueAlbums = uniqueAlbums,
                currentStreakDays = streakSummary.currentStreakDays,
                longestStreakDays = streakSummary.longestStreakDays,
                activeSourcesCount = sources.size,
            )

            val (bars, peakName, peakDuration) = computeTimelineBars(
                events = inWindow,
                range = stateSnapshot.selectedRange,
                metric = stateSnapshot.selectedTimelineMetric,
            )
            val categoryItems = computeCategoryItems(inWindow, stateSnapshot.selectedCategory)
            val habits = computeHabits(inWindow, totalListeningMs, totalPlays)
            val concentration = computeConcentration(inWindow, totalListeningMs)
            val records = computePersonalRecords(sourcePool, streakSummary)
            val weeklyRhythm = computeWeeklyRhythm(sourcePool)
            val monthComparison = computeMonthComparison(sourcePool)
            val hourlyRhythm = computeHourlyRhythm(inWindow.ifEmpty { sourcePool })
            val matrixHeatmap = computeMatrixHeatmap(inWindow.ifEmpty { sourcePool })
            val activityHeatmap = computeActivityHeatmap(sourcePool)

            coroutineContext.ensureActive()

            val avgSessionMins = habits.avgSessionMinutes
            // Honest 0..100 rhythm score based on active days this week (up to 70 pts) + current streak (up to 30 pts)
            val consistencyScore = if (sourcePool.isEmpty()) {
                0
            } else {
                ((streakSummary.activeDaysLast7.coerceIn(0, 7) * 10) +
                    (streakSummary.currentStreakDays.coerceAtMost(15) * 2)).coerceIn(0, 100)
            }
            val rating = when {
                consistencyScore >= 85 -> "Peak Flow"
                consistencyScore >= 65 -> "Steady Rhythm"
                consistencyScore >= 35 -> "Building Habit"
                consistencyScore > 0 -> "Light Rhythm"
                else -> "Just Starting"
            }
            val consistency = ConsistencyScoreData(
                score = consistencyScore,
                rating = rating,
                subtitle = if (streakSummary.currentStreakDays > 0) {
                    "${streakSummary.currentStreakDays}-day listening streak active (best ${streakSummary.longestStreakDays}d)"
                } else {
                    "Play a track today to start a new streak"
                },
                avgSessionMinutes = avgSessionMins,
                activeDaysThisWeek = streakSummary.activeDaysLast7.coerceIn(0, 7),
                streakDays = streakSummary.currentStreakDays,
            )

            val trackCounts = inWindow.groupingBy { it.trackKey }.eachCount()
            val deepMs = inWindow.filter { (trackCounts[it.trackKey] ?: 1) >= 3 }.sumOf { effectiveListenMs(it) }
            val discoveryMs = inWindow.filter { (trackCounts[it.trackKey] ?: 1) == 1 }.sumOf { effectiveListenMs(it) }
            val steadyMs = (totalListeningMs - deepMs - discoveryMs).coerceAtLeast(0L)
            val deepPct = if (totalListeningMs > 0L) ((deepMs.toDouble() / totalListeningMs) * 100).roundToInt().coerceIn(0, 100) else 0
            val discPct = if (totalListeningMs > 0L) ((discoveryMs.toDouble() / totalListeningMs) * 100).roundToInt().coerceIn(0, 100 - deepPct) else 0
            val flowPct = if (totalListeningMs > 0L) (100 - deepPct - discPct).coerceAtLeast(0) else 0

            val stages = ListeningStagesData(
                deepFocus = ListeningStage(
                    name = "Heavy Rotation",
                    percent = deepPct,
                    durationLabel = formatHoursMins(deepMs),
                    description = "Tracks played 3 or more times in this window",
                ),
                flowState = ListeningStage(
                    name = "Steady Rotation",
                    percent = flowPct,
                    durationLabel = formatHoursMins(steadyMs),
                    description = "Tracks revisited across sessions",
                ),
                discovery = ListeningStage(
                    name = "Discovery",
                    percent = discPct,
                    durationLabel = formatHoursMins(discoveryMs),
                    description = "First-time plays and one-off listens",
                ),
            )

            val groupedTracks = inWindow.groupBy { it.trackKey }.values
                .sortedByDescending { list -> list.size * 1_000_000L + list.sumOf { effectiveListenMs(it) } }
                .take(30)
            val maxTrackMs = groupedTracks.firstOrNull()?.sumOf { effectiveListenMs(it) }?.coerceAtLeast(1L) ?: 1L
            val tracksInRange = groupedTracks.mapIndexed { idx, list ->
                val latest = list.maxByOrNull { it.scrobbledAtMs } ?: list.first()
                val ms = list.sumOf { effectiveListenMs(it) }
                RankedTrackItem(
                    rank = idx + 1,
                    title = latest.title.ifBlank { "Unknown Title" },
                    artist = latest.artist.ifBlank { "Unknown Artist" },
                    artworkUrl = list.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl,
                    playCount = list.size,
                    durationLabel = formatHoursMins(ms),
                    progress = (ms.toFloat() / maxTrackMs).coerceIn(0.05f, 1f),
                    album = latest.album,
                    sourceSummary = summarizeSources(list),
                )
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    musicScrobbleCount = musicCount,
                    otherScrobbleCount = otherCount,
                    heroKpis = heroKpis,
                    totalListeningLabel = totalListeningLabel,
                    totalPlays = totalPlays,
                    sourceBreakdown = sources,
                    availableSources = sourceNames,
                    timelineBars = bars,
                    peakSegmentName = peakName,
                    peakSegmentDuration = peakDuration,
                    categoryItems = categoryItems,
                    habits = habits,
                    concentration = concentration,
                    tracksInRange = tracksInRange,
                    records = records,
                    weeklyRhythm = weeklyRhythm,
                    monthComparison = monthComparison,
                    hourlyRhythm = hourlyRhythm,
                    matrixHeatmap = matrixHeatmap,
                    activityHeatmap = activityHeatmap,
                    consistency = consistency,
                    stages = stages,
                    recentScrobbles = eventsSnapshot,
                )
            }
        }
    }

    private fun effectiveListenMs(event: ScrobbleEventEntity): Long = when {
        event.listenedMs > 0L -> event.listenedMs
        event.durationMs > 0L -> event.durationMs
        else -> 210_000L
    }

    private fun computeSourceBreakdown(events: List<ScrobbleEventEntity>): List<SourceAppBreakdown> {
        if (events.isEmpty()) return emptyList()
        val total = events.size.coerceAtLeast(1).toFloat()
        return events
            .groupBy { (it.sourcePackage.ifBlank { "com.leothefleo49.onyxmusic" }) to (it.sourceAppName.ifBlank { "Onyx Music" }) }
            .entries
            .sortedByDescending { it.value.size }
            .map { (key, list) ->
                val ms = list.sumOf { effectiveListenMs(it) }
                SourceAppBreakdown(
                    packageName = key.first,
                    appName = key.second,
                    playCount = list.size,
                    listeningLabel = formatHoursMins(ms),
                    fraction = (list.size / total).coerceIn(0.05f, 1f),
                )
            }
    }

    private fun summarizeSources(events: List<ScrobbleEventEntity>): String {
        val grouped = events.groupingBy { it.sourceAppName.ifBlank { "Onyx Music" } }.eachCount()
        if (grouped.isEmpty()) return ""
        val top = grouped.entries.sortedByDescending { it.value }
        return if (top.size == 1) {
            top.first().key
        } else {
            "${top.first().key} +${top.size - 1}"
        }
    }

    private fun computeTimelineBars(
        events: List<ScrobbleEventEntity>,
        range: StatsTimeRange,
        metric: TimelineMetric,
    ): Triple<List<TimelineBar>, String, String> {
        if (events.isEmpty()) {
            val fallbackLabel = Calendar.getInstance().get(Calendar.YEAR).toString()
            return Triple(listOf(TimelineBar(fallbackLabel, 0.08f, "0m", isPeak = false)), "", "0m")
        }

        data class Bucket(
            val label: String,
            var totalMs: Long = 0L,
            var plays: Int = 0,
            val timestamps: MutableList<Long> = mutableListOf(),
            var sessions: Int = 0,
        )
        val buckets = LinkedHashMap<String, Bucket>()
        val cal = Calendar.getInstance()

        when (range) {
            StatsTimeRange.TODAY -> {
                for (block in 0..20 step 4) {
                    val label = String.format(Locale.US, "%02d", block)
                    buckets[label] = Bucket(label)
                }
                events.forEach { ev ->
                    cal.timeInMillis = ev.scrobbledAtMs
                    val block = (cal.get(Calendar.HOUR_OF_DAY) / 4) * 4
                    val label = String.format(Locale.US, "%02d", block)
                    val b = buckets.getOrPut(label) { Bucket(label) }
                    b.totalMs += effectiveListenMs(ev)
                    b.plays++
                    b.timestamps += ev.scrobbledAtMs
                }
            }
            StatsTimeRange.WEEK -> {
                val keyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
                val cursor = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -6) }
                for (i in 0..6) {
                    val key = keyFmt.format(cursor.time)
                    val label = dayFmt.format(cursor.time)
                    buckets[key] = Bucket(label)
                    cursor.add(Calendar.DAY_OF_YEAR, 1)
                }
                events.forEach { ev ->
                    cal.timeInMillis = ev.scrobbledAtMs
                    val key = keyFmt.format(cal.time)
                    val b = buckets[key] ?: return@forEach
                    b.totalMs += effectiveListenMs(ev)
                    b.plays++
                    b.timestamps += ev.scrobbledAtMs
                }
            }
            StatsTimeRange.MONTH_TO_DATE, StatsTimeRange.CUSTOM -> {
                val dayKeyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val monthKeyFmt = SimpleDateFormat("yyyy-MM", Locale.US)
                val sorted = events.sortedBy { it.scrobbledAtMs }
                val uniqueDays = sorted.map {
                    cal.timeInMillis = it.scrobbledAtMs
                    dayKeyFmt.format(cal.time)
                }.distinct()
                val useMonthly = uniqueDays.size > 45
                val labelFmt = if (useMonthly) {
                    SimpleDateFormat("MMM yy", Locale.getDefault())
                } else {
                    SimpleDateFormat("MMM d", Locale.getDefault())
                }
                sorted.forEach { ev ->
                    cal.timeInMillis = ev.scrobbledAtMs
                    val key = if (useMonthly) monthKeyFmt.format(cal.time) else dayKeyFmt.format(cal.time)
                    val label = labelFmt.format(cal.time)
                    val b = buckets.getOrPut(key) { Bucket(label) }
                    b.totalMs += effectiveListenMs(ev)
                    b.plays++
                    b.timestamps += ev.scrobbledAtMs
                }
            }
            StatsTimeRange.YEAR_TO_DATE -> {
                val monthKeyFmt = SimpleDateFormat("yyyy-MM", Locale.US)
                val monthFmt = SimpleDateFormat("MMM", Locale.getDefault())
                val currentMonth = cal.get(Calendar.MONTH)
                val currentYear = cal.get(Calendar.YEAR)
                val cursor = Calendar.getInstance().apply {
                    set(Calendar.YEAR, currentYear)
                    set(Calendar.DAY_OF_MONTH, 1)
                }
                for (m in 0..currentMonth) {
                    cursor.set(Calendar.MONTH, m)
                    buckets[monthKeyFmt.format(cursor.time)] = Bucket(monthFmt.format(cursor.time))
                }
                events.forEach { ev ->
                    cal.timeInMillis = ev.scrobbledAtMs
                    val key = monthKeyFmt.format(cal.time)
                    val b = buckets.getOrPut(key) { Bucket(monthFmt.format(cal.time)) }
                    b.totalMs += effectiveListenMs(ev)
                    b.plays++
                    b.timestamps += ev.scrobbledAtMs
                }
            }
            StatsTimeRange.ALL_TIME -> {
                events.sortedBy { it.scrobbledAtMs }.forEach { ev ->
                    cal.timeInMillis = ev.scrobbledAtMs
                    val label = cal.get(Calendar.YEAR).toString()
                    val b = buckets.getOrPut(label) { Bucket(label) }
                    b.totalMs += effectiveListenMs(ev)
                    b.plays++
                    b.timestamps += ev.scrobbledAtMs
                }
            }
        }

        // Trim excessive buckets to keep bar chart readable
        val rawList = buckets.values.toList()
        val bucketList = if (rawList.size > 14) rawList.takeLast(14) else rawList
        bucketList.forEach { b ->
            if (b.timestamps.isEmpty()) {
                b.sessions = 0
            } else {
                val ts = b.timestamps.sorted()
                var count = 1
                for (i in 1 until ts.size) {
                    if (ts[i] - ts[i - 1] > 30 * 60_000L) count++
                }
                b.sessions = count
            }
        }

        fun metricVal(b: Bucket): Float = when (metric) {
            TimelineMetric.LISTENING_TIME -> b.totalMs / 60_000f
            TimelineMetric.PLAY_COUNT -> b.plays.toFloat()
            TimelineMetric.AVG_SESSION -> if (b.sessions > 0) (b.totalMs / (b.sessions * 60_000f)) else 0f
        }

        val peakBucket = bucketList.maxByOrNull { metricVal(it) }
        val maxVal = bucketList.maxOfOrNull { metricVal(it) }?.coerceAtLeast(1f) ?: 1f

        val bars = bucketList.map { b ->
            val v = metricVal(b)
            val labelText = when (metric) {
                TimelineMetric.LISTENING_TIME -> formatHoursMins(b.totalMs)
                TimelineMetric.PLAY_COUNT -> "${b.plays} plays"
                TimelineMetric.AVG_SESSION -> "${v.roundToInt()}m"
            }
            TimelineBar(
                label = b.label,
                value = (v / maxVal).coerceIn(0.05f, 1f),
                valueLabel = labelText,
                isPeak = b == peakBucket && v > 0f,
            )
        }
        return Triple(bars, peakBucket?.label.orEmpty(), formatHoursMins(peakBucket?.totalMs ?: 0L))
    }

    private fun computeCategoryItems(
        events: List<ScrobbleEventEntity>,
        category: CategoryFilter,
    ): List<RankedCategoryItem> {
        return when (category) {
            CategoryFilter.SONG -> {
                val grouped = events.groupBy { it.trackKey }.values
                    .sortedByDescending { list -> list.sumOf { effectiveListenMs(it) } }
                    .take(15)
                val maxMs = grouped.firstOrNull()?.sumOf { effectiveListenMs(it) }?.coerceAtLeast(1L) ?: 1L
                grouped.mapIndexed { idx, list ->
                    val sample = list.first()
                    val ms = list.sumOf { effectiveListenMs(it) }
                    val src = summarizeSources(list)
                    RankedCategoryItem(
                        rank = idx + 1,
                        title = sample.title.ifBlank { "Unknown Title" },
                        subtitle = "${list.size} plays \u00b7 ${sample.artist.ifBlank { "Unknown Artist" }}" +
                            if (src.isNotBlank()) " \u00b7 $src" else "",
                        durationLabel = formatHoursMins(ms),
                        progress = (ms.toFloat() / maxMs).coerceIn(0.05f, 1f),
                        artworkUrl = list.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl,
                        artist = sample.artist,
                        playCount = list.size,
                        sourceSummary = src,
                    )
                }
            }
            CategoryFilter.ARTIST -> {
                val grouped = events.groupBy { it.artist.trim().lowercase().ifBlank { "unknown" } }.values
                    .sortedByDescending { list -> list.sumOf { effectiveListenMs(it) } }
                    .take(15)
                val maxMs = grouped.firstOrNull()?.sumOf { effectiveListenMs(it) }?.coerceAtLeast(1L) ?: 1L
                grouped.mapIndexed { idx, list ->
                    val displayArtist = list.first().artist.trim().ifBlank { "Unknown Artist" }
                    val ms = list.sumOf { effectiveListenMs(it) }
                    val distinctTracks = list.map { it.trackKey }.distinct().size
                    val src = summarizeSources(list)
                    RankedCategoryItem(
                        rank = idx + 1,
                        title = displayArtist,
                        subtitle = "${list.size} plays \u00b7 $distinctTracks tracks" +
                            if (src.isNotBlank()) " \u00b7 $src" else "",
                        durationLabel = formatHoursMins(ms),
                        progress = (ms.toFloat() / maxMs).coerceIn(0.05f, 1f),
                        artworkUrl = list.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl,
                        artist = displayArtist,
                        playCount = list.size,
                        sourceSummary = src,
                    )
                }
            }
            CategoryFilter.ALBUM -> {
                val withAlbum = events.filter { it.album.isNotBlank() }
                if (withAlbum.isNotEmpty()) {
                    val grouped = withAlbum
                        .groupBy { "${it.artist.trim().lowercase()}|${it.album.trim().lowercase()}" }
                        .values
                        .sortedByDescending { list -> list.sumOf { effectiveListenMs(it) } }
                        .take(15)
                    val maxMs = grouped.firstOrNull()?.sumOf { effectiveListenMs(it) }?.coerceAtLeast(1L) ?: 1L
                    grouped.mapIndexed { idx, list ->
                        val sample = list.first()
                        val ms = list.sumOf { effectiveListenMs(it) }
                        RankedCategoryItem(
                            rank = idx + 1,
                            title = sample.album.trim(),
                            subtitle = "${list.size} plays \u00b7 ${sample.artist.trim()}",
                            durationLabel = formatHoursMins(ms),
                            progress = (ms.toFloat() / maxMs).coerceIn(0.05f, 1f),
                            artworkUrl = list.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl,
                            artist = sample.artist.trim(),
                            playCount = list.size,
                            sourceSummary = summarizeSources(list),
                        )
                    }
                } else {
                    val localAlbums = localStatsCache?.topAlbums.orEmpty()
                    val maxPlays = localAlbums.firstOrNull()?.playCount?.coerceAtLeast(1L) ?: 1L
                    localAlbums.take(15).mapIndexed { idx, alb ->
                        RankedCategoryItem(
                            rank = idx + 1,
                            title = alb.name,
                            subtitle = "${alb.playCount} plays \u00b7 ${alb.artist}",
                            durationLabel = formatHoursMins(alb.playCount * 210_000L),
                            progress = (alb.playCount.toFloat() / maxPlays).coerceIn(0.05f, 1f),
                            artworkUrl = alb.artworkUrl,
                            artist = alb.artist,
                            playCount = alb.playCount.toInt(),
                        )
                    }
                }
            }
            CategoryFilter.GENRE -> {
                val withGenre = events.filter { it.genre.isNotBlank() }
                if (withGenre.isNotEmpty()) {
                    val buckets = LinkedHashMap<String, MutableList<ScrobbleEventEntity>>()
                    for (ev in withGenre) {
                        val tags = GenreNormalizer.splitAndNormalize(ev.genre, maxTags = 2)
                        for (tag in tags) {
                            buckets.getOrPut(tag) { mutableListOf() }.add(ev)
                        }
                    }
                    val totalAllTagPlays = buckets.values.sumOf { it.size }.coerceAtLeast(1)
                    val sorted = buckets.entries
                        .sortedByDescending { it.value.size }
                        .take(15)
                    val topCount = sorted.firstOrNull()?.value?.size?.coerceAtLeast(1) ?: 1
                    sorted.mapIndexed { idx, (tag, list) ->
                        val ms = list.sumOf { effectiveListenMs(it) }
                        val share = list.size.toFloat() / totalAllTagPlays.toFloat()
                        val pct = (share * 100f).roundToInt().coerceIn(1, 100)
                        val display = GenreNormalizer.formatDisplayName(tag)
                        val src = summarizeSources(list)
                        RankedCategoryItem(
                            rank = idx + 1,
                            title = display,
                            subtitle = "$pct% of music · ${list.size} plays" +
                                if (src.isNotBlank()) " · $src" else "",
                            durationLabel = formatHoursMins(ms),
                            progress = (list.size.toFloat() / topCount.toFloat()).coerceIn(0.06f, 1f),
                            playCount = list.size,
                            artist = tag,
                        )
                    }
                } else {
                    emptyList()
                }
            }
        }
    }

    private fun computeHabits(
        events: List<ScrobbleEventEntity>,
        totalListeningMs: Long,
        totalPlays: Int,
    ): ListeningHabitsData {
        if (events.isEmpty()) {
            return ListeningHabitsData(
                totalSessions = 0,
                avgSessionLabel = "0m",
                longestSessionLabel = "0m",
                sessionsPerDay = "0.0",
                mostActiveDay = "None",
                peakTimelineSlot = "None",
            )
        }

        val sorted = events.sortedBy { it.scrobbledAtMs }
        var sessionsCount = 1
        var longestSessionMs = 0L
        var sessionMs = effectiveListenMs(sorted.first())
        var lastTime = sorted.first().scrobbledAtMs

        for (i in 1 until sorted.size) {
            val ev = sorted[i]
            if (ev.scrobbledAtMs - lastTime > 25 * 60_000L) {
                longestSessionMs = maxOf(longestSessionMs, sessionMs)
                sessionsCount++
                sessionMs = effectiveListenMs(ev)
            } else {
                sessionMs += effectiveListenMs(ev)
            }
            lastTime = ev.scrobbledAtMs
        }
        longestSessionMs = maxOf(longestSessionMs, sessionMs)

        val avgSessionMs = totalListeningMs / sessionsCount.coerceAtLeast(1)
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val activeDays = sorted.map { dayFmt.format(Date(it.scrobbledAtMs)) }.distinct().size.coerceAtLeast(1)
        val sessionsPerDay = String.format(Locale.US, "%.1f", sessionsCount.toFloat() / activeDays)

        val dowFmt = SimpleDateFormat("EEEE", Locale.getDefault())
        val dowBuckets = sorted.groupBy { dowFmt.format(Date(it.scrobbledAtMs)) }
        val topDow = dowBuckets.maxByOrNull { (_, list) -> list.sumOf { effectiveListenMs(it) } }
        val mostActiveDay = if (topDow != null) {
            "${topDow.key}, ${formatHoursMins(topDow.value.sumOf { effectiveListenMs(it) })}"
        } else {
            "None"
        }

        val peakSlot = computePeakDayLabel(events)
        return ListeningHabitsData(
            totalSessions = sessionsCount,
            avgSessionLabel = formatHoursMins(avgSessionMs),
            longestSessionLabel = formatHoursMins(longestSessionMs),
            sessionsPerDay = sessionsPerDay,
            mostActiveDay = mostActiveDay,
            peakTimelineSlot = peakSlot,
            avgSessionMinutes = (avgSessionMs / 60_000L).toInt().coerceAtLeast(0),
        )
    }

    private fun computeConcentration(
        events: List<ScrobbleEventEntity>,
        totalListeningMs: Long,
    ): TrackConcentrationData {
        if (events.isEmpty()) {
            return TrackConcentrationData(0, "0.0", 0, 0, "0m", 0, "0m", 0, "0m")
        }
        val grouped = events.groupBy { it.trackKey }.values
            .map { list -> list.sumOf { effectiveListenMs(it) } }
            .sortedDescending()
        val totalMs = totalListeningMs.coerceAtLeast(1L)
        val top1Ms = grouped.firstOrNull() ?: 0L
        val top2To3Ms = grouped.drop(1).take(2).sum()
        val othersMs = (totalMs - top1Ms - top2To3Ms).coerceAtLeast(0L)

        val top1Pct = ((top1Ms.toDouble() / totalMs) * 100).roundToInt().coerceIn(0, 100)
        val top2To3Pct = ((top2To3Ms.toDouble() / totalMs) * 100).roundToInt().coerceIn(0, 100 - top1Pct)
        val top3Share = (top1Pct + top2To3Pct).coerceIn(0, 100)
        val othersPct = (100 - top3Share).coerceAtLeast(0)
        val avgPlays = String.format(Locale.US, "%.1f", events.size.toFloat() / grouped.size.coerceAtLeast(1))

        return TrackConcentrationData(
            top3SharePercent = top3Share,
            avgPlaysPerTrack = avgPlays,
            uniqueTracksCount = grouped.size,
            top1Percent = top1Pct,
            top1DurationLabel = formatHoursMins(top1Ms),
            top2To3Percent = top2To3Pct,
            top2To3DurationLabel = formatHoursMins(top2To3Ms),
            othersPercent = othersPct,
            othersDurationLabel = formatHoursMins(othersMs),
        )
    }

    private fun computePersonalRecords(
        events: List<ScrobbleEventEntity>,
        streaks: StreakSummary,
    ): PersonalRecordsData {
        if (events.isEmpty()) {
            return PersonalRecordsData(0, "0m", "None", 0, "0m", 0, "0m", 0, 0)
        }
        val cal = Calendar.getInstance()
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val displayFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        val monthFmt = SimpleDateFormat("yyyy-MM", Locale.US)

        val dayMs = mutableMapOf<String, Long>()
        val dayPlays = mutableMapOf<String, Int>()
        val weekMs = mutableMapOf<String, Long>()
        val weekPlays = mutableMapOf<String, Int>()
        val monthMs = mutableMapOf<String, Long>()
        val monthPlays = mutableMapOf<String, Int>()

        events.forEach { ev ->
            cal.timeInMillis = ev.scrobbledAtMs
            val dk = dayFmt.format(cal.time)
            val wk = "${cal.get(Calendar.YEAR)}-W${cal.get(Calendar.WEEK_OF_YEAR)}"
            val mk = monthFmt.format(cal.time)
            val ms = effectiveListenMs(ev)
            dayMs[dk] = (dayMs[dk] ?: 0L) + ms
            dayPlays[dk] = (dayPlays[dk] ?: 0) + 1
            weekMs[wk] = (weekMs[wk] ?: 0L) + ms
            weekPlays[wk] = (weekPlays[wk] ?: 0) + 1
            monthMs[mk] = (monthMs[mk] ?: 0L) + ms
            monthPlays[mk] = (monthPlays[mk] ?: 0) + 1
        }

        val bestDay = dayMs.maxByOrNull { it.value }
        val bestDayDate = bestDay?.key?.let { key ->
            runCatching { displayFmt.format(dayFmt.parse(key)!!) }.getOrNull()
        } ?: "None"
        val bestWeek = weekMs.maxByOrNull { it.value }
        val bestMonth = monthMs.maxByOrNull { it.value }

        return PersonalRecordsData(
            bestDayPlays = bestDay?.key?.let { dayPlays[it] } ?: 0,
            bestDayDurationLabel = formatHoursMins(bestDay?.value ?: 0L),
            bestDayDate = bestDayDate,
            bestWeekPlays = bestWeek?.key?.let { weekPlays[it] } ?: 0,
            bestWeekDurationLabel = formatHoursMins(bestWeek?.value ?: 0L),
            bestMonthPlays = bestMonth?.key?.let { monthPlays[it] } ?: 0,
            bestMonthDurationLabel = formatHoursMins(bestMonth?.value ?: 0L),
            longestStreakDays = streaks.longestStreakDays,
            currentStreakDays = streaks.currentStreakDays,
        )
    }

    private fun computeWeeklyRhythm(events: List<ScrobbleEventEntity>): WeeklyRhythmData {
        if (events.isEmpty()) return WeeklyRhythmData("0m", "0m")
        val cal = Calendar.getInstance()
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val weekdayDays = mutableSetOf<String>()
        val weekendDays = mutableSetOf<String>()
        var weekdayMs = 0L
        var weekendMs = 0L

        events.forEach { ev ->
            cal.timeInMillis = ev.scrobbledAtMs
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            val key = dayFmt.format(cal.time)
            val ms = effectiveListenMs(ev)
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) {
                weekendMs += ms
                weekendDays += key
            } else {
                weekdayMs += ms
                weekdayDays += key
            }
        }
        val wdAvg = if (weekdayDays.isNotEmpty()) weekdayMs / weekdayDays.size else 0L
        val weAvg = if (weekendDays.isNotEmpty()) weekendMs / weekendDays.size else 0L
        return WeeklyRhythmData(formatHoursMins(wdAvg), formatHoursMins(weAvg))
    }

    private fun computeMonthComparison(events: List<ScrobbleEventEntity>): MonthComparisonData {
        val cal = Calendar.getInstance()
        val curY = cal.get(Calendar.YEAR)
        val curM = cal.get(Calendar.MONTH)
        cal.add(Calendar.MONTH, -1)
        val prevY = cal.get(Calendar.YEAR)
        val prevM = cal.get(Calendar.MONTH)

        var thisMs = 0L
        var lastMs = 0L
        val tCal = Calendar.getInstance()
        events.forEach { ev ->
            tCal.timeInMillis = ev.scrobbledAtMs
            val y = tCal.get(Calendar.YEAR)
            val m = tCal.get(Calendar.MONTH)
            val ms = effectiveListenMs(ev)
            if (y == curY && m == curM) thisMs += ms
            else if (y == prevY && m == prevM) lastMs += ms
        }
        val progress = when {
            lastMs > 0L -> (thisMs.toFloat() / lastMs.toFloat()).coerceIn(0.05f, 1f)
            thisMs > 0L -> 1f
            else -> 0f
        }
        return MonthComparisonData(formatHoursMins(thisMs), formatHoursMins(lastMs), progress)
    }

    private fun computeHourlyRhythm(events: List<ScrobbleEventEntity>): List<HourlyRhythmBar> {
        val counts = IntArray(24)
        val durations = LongArray(24)
        val cal = Calendar.getInstance()
        events.forEach { ev ->
            cal.timeInMillis = ev.scrobbledAtMs
            val h = cal.get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)
            counts[h]++
            durations[h] += effectiveListenMs(ev)
        }
        val maxVal = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
        return (0..23).map { h ->
            val c = counts[h]
            HourlyRhythmBar(
                hour = h,
                label = String.format(Locale.US, "%02d", h),
                count = c,
                fraction = if (c > 0) (c.toFloat() / maxVal).coerceIn(0.10f, 1f) else 0.04f,
                isPeak = c == maxVal && c > 0,
                durationLabel = formatHoursMins(durations[h]),
            )
        }
    }

    private fun computeMatrixHeatmap(events: List<ScrobbleEventEntity>): List<MatrixHeatmapCell> {
        val counts = Array(7) { IntArray(24) }
        val durations = Array(7) { LongArray(24) }
        val cal = Calendar.getInstance()
        events.forEach { ev ->
            cal.timeInMillis = ev.scrobbledAtMs
            // Map Calendar.MONDAY..SUNDAY to 0..6
            val dow = when (cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> 0
                Calendar.TUESDAY -> 1
                Calendar.WEDNESDAY -> 2
                Calendar.THURSDAY -> 3
                Calendar.FRIDAY -> 4
                Calendar.SATURDAY -> 5
                else -> 6
            }
            val hr = cal.get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)
            counts[dow][hr]++
            durations[dow][hr] += effectiveListenMs(ev)
        }
        val maxCount = counts.maxOf { row -> row.maxOrNull() ?: 0 }.coerceAtLeast(1)
        val cells = ArrayList<MatrixHeatmapCell>(7 * 24)
        for (d in 0 until 7) {
            for (h in 0 until 24) {
                val c = counts[d][h]
                val intensity = when {
                    c == 0 -> 0
                    c <= maxCount * 0.25f -> 1
                    c <= maxCount * 0.50f -> 2
                    c <= maxCount * 0.75f -> 3
                    else -> 4
                }
                cells += MatrixHeatmapCell(
                    dayOfWeek = d,
                    hour = h,
                    playCount = c,
                    intensity = intensity,
                    durationLabel = formatHoursMins(durations[d][h]),
                )
            }
        }
        return cells
    }

    private fun computeActivityHeatmap(events: List<ScrobbleEventEntity>): List<HeatmapDay> {
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        val dailyMs = mutableMapOf<String, Long>()
        val dailyCount = mutableMapOf<String, Int>()
        events.forEach { ev ->
            cal.timeInMillis = ev.scrobbledAtMs
            val key = dayFmt.format(cal.time)
            dailyMs[key] = (dailyMs[key] ?: 0L) + effectiveListenMs(ev)
            dailyCount[key] = (dailyCount[key] ?: 0) + 1
        }

        val endCal = Calendar.getInstance()
        while (endCal.get(Calendar.DAY_OF_WEEK) != Calendar.SATURDAY) {
            endCal.add(Calendar.DAY_OF_YEAR, 1)
        }
        val cur = Calendar.getInstance().apply {
            timeInMillis = endCal.timeInMillis
            add(Calendar.DAY_OF_YEAR, -83)
        }

        val result = ArrayList<HeatmapDay>(84)
        for (i in 0 until 84) {
            val key = dayFmt.format(cur.time)
            val dow = (cur.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)
            val weekIdx = i / 7
            val ms = dailyMs[key] ?: 0L
            val plays = dailyCount[key] ?: 0
            val intensity = when {
                ms <= 0L -> 0
                ms < 15 * 60_000L -> 1
                ms < 45 * 60_000L -> 2
                ms < 90 * 60_000L -> 3
                else -> 4
            }
            result += HeatmapDay(
                dateString = key,
                dayOfWeek = dow,
                weekIndex = weekIdx,
                intensity = intensity,
                durationLabel = formatHoursMins(ms),
                playCount = plays,
            )
            cur.add(Calendar.DAY_OF_YEAR, 1)
        }
        return result
    }

    private fun computePeakDayLabel(events: List<ScrobbleEventEntity>): String {
        if (events.isEmpty()) return "None"
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dispFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        val grouped = events.groupBy { dayFmt.format(Date(it.scrobbledAtMs)) }
        val peak = grouped.maxByOrNull { it.value.size } ?: return "None"
        val dateText = runCatching { dispFmt.format(dayFmt.parse(peak.key)!!) }.getOrDefault(peak.key)
        return "$dateText (${peak.value.size} plays)"
    }

    private fun computeCompactTimeline(events: List<ScrobbleEventEntity>): List<TimelineBar> {
        if (events.isEmpty()) return emptyList()
        val keyFmt = SimpleDateFormat("yyyy-MM", Locale.US)
        val labelFmt = SimpleDateFormat("MMM yy", Locale.getDefault())
        val grouped = events.sortedBy { it.scrobbledAtMs }
            .groupBy { keyFmt.format(Date(it.scrobbledAtMs)) }
            .entries
            .toList()
            .takeLast(8)
        val maxPlays = grouped.maxOfOrNull { it.value.size }?.coerceAtLeast(1) ?: 1
        return grouped.map { (_, list) ->
            val label = labelFmt.format(Date(list.first().scrobbledAtMs))
            TimelineBar(
                label = label,
                value = (list.size.toFloat() / maxPlays).coerceIn(0.08f, 1f),
                valueLabel = "${list.size}",
                isPeak = list.size == maxPlays,
            )
        }
    }

    private fun formatHoursMins(ms: Long): String {
        val totalMins = (ms / 60_000L).coerceAtLeast(0L)
        val hours = totalMins / 60
        val mins = totalMins % 60
        return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    }
}
