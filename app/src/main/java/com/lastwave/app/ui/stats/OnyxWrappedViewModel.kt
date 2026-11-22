package com.lastwave.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.scrobble.ScrobbleEventDao
import com.lastwave.app.data.scrobble.ScrobbleEventEntity
import com.lastwave.app.data.scrobble.ScrobbleRulesEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

enum class WrappedRange(val label: String) {
    WEEK("7 days"),
    MONTH("30 days"),
    YEAR("This year"),
    ALL("All time"),
}

data class WrappedItem(
    val title: String,
    val subtitle: String,
    val artworkUrl: String? = null,
    val plays: Int = 0,
)

data class OnyxWrappedUiState(
    val year: Int = Calendar.getInstance().get(Calendar.YEAR),
    val range: WrappedRange = WrappedRange.YEAR,
    val isLoading: Boolean = true,
    val hasData: Boolean = false,
    val totalMinutes: Long = 0L,
    val plays: Int = 0,
    val uniqueTracks: Int = 0,
    val uniqueArtists: Int = 0,
    val uniqueAlbums: Int = 0,
    val topArtists: List<WrappedItem> = emptyList(),
    val topTracks: List<WrappedItem> = emptyList(),
    val topAlbums: List<WrappedItem> = emptyList(),
    val topSource: String = "",
    val peakHour: String = "",
    val longestStreakDays: Int = 0,
    val personality: String = "",
)

@HiltViewModel
class OnyxWrappedViewModel @Inject constructor(
    private val scrobbleEventDao: ScrobbleEventDao,
) : ViewModel() {

    private val _state = MutableStateFlow(OnyxWrappedUiState())
    val state: StateFlow<OnyxWrappedUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load(range: WrappedRange = _state.value.range) {
        viewModelScope.launch {
            val events = runCatching { scrobbleEventDao.observeAll().first() }.getOrDefault(emptyList())
            _state.value = buildWrapped(events, range)
        }
    }

    fun setRange(range: WrappedRange) {
        _state.value = _state.value.copy(range = range, isLoading = true)
        load(range)
    }

    private fun buildWrapped(events: List<ScrobbleEventEntity>, range: WrappedRange): OnyxWrappedUiState {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val now = System.currentTimeMillis()
        val (start, end) = when (range) {
            WrappedRange.WEEK -> now - 7L * 24L * 60L * 60L * 1000L to now + 1L
            WrappedRange.MONTH -> now - 30L * 24L * 60L * 60L * 1000L to now + 1L
            WrappedRange.ALL -> Long.MIN_VALUE to Long.MAX_VALUE
            WrappedRange.YEAR -> Calendar.getInstance().apply {
                clear()
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, Calendar.JANUARY)
                set(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis to Calendar.getInstance().apply {
                clear()
                set(Calendar.YEAR, year + 1)
                set(Calendar.MONTH, Calendar.JANUARY)
                set(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
        }
        val inYear = events.filter { it.scrobbledAtMs in start until end }
        if (inYear.isEmpty()) {
            return OnyxWrappedUiState(year = year, range = range, isLoading = false, hasData = false)
        }

        fun listenMs(event: ScrobbleEventEntity): Long = when {
            event.listenedMs > 0L -> event.listenedMs
            event.durationMs > 0L -> event.durationMs
            else -> 210_000L
        }

        val totalMs = inYear.sumOf { listenMs(it) }
        val artists = inYear
            .groupBy { it.artist.trim().ifBlank { "Unknown Artist" } }
            .entries
            .sortedByDescending { entry -> entry.value.sumOf { listenMs(it) } }
            .take(5)
            .map { (artist, list) ->
                WrappedItem(
                    title = artist,
                    subtitle = "${list.size} plays \u2022 ${formatMinutes(list.sumOf { listenMs(it) })}",
                    plays = list.size,
                )
            }
        val tracks = inYear
            .groupBy { it.trackKey }
            .entries
            .sortedByDescending { entry -> entry.value.size }
            .take(5)
            .map { (_, list) ->
                val latest = list.maxByOrNull { it.scrobbledAtMs } ?: list.first()
                WrappedItem(
                    title = latest.title.ifBlank { "Unknown Title" },
                    subtitle = "${latest.artist.ifBlank { "Unknown Artist" }} \u2022 ${list.size} plays",
                    artworkUrl = list.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl,
                    plays = list.size,
                )
            }
        val albums = inYear
            .filter { it.album.isNotBlank() }
            .groupBy { it.album.trim() }
            .entries
            .sortedByDescending { entry -> entry.value.size }
            .take(5)
            .map { (album, list) ->
                val latest = list.maxByOrNull { it.scrobbledAtMs } ?: list.first()
                WrappedItem(
                    title = album,
                    subtitle = "${latest.artist.ifBlank { "Unknown Artist" }} \u2022 ${list.size} plays",
                    artworkUrl = list.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl,
                    plays = list.size,
                )
            }
        val source = inYear
            .groupingBy { it.sourceAppName.ifBlank { "Onyx Music" } }
            .eachCount()
            .maxByOrNull { it.value }?.key.orEmpty()

        val hourCounts = IntArray(24)
        inYear.forEach { event ->
            val hour = Calendar.getInstance().apply { timeInMillis = event.scrobbledAtMs }
                .get(Calendar.HOUR_OF_DAY)
            hourCounts[hour]++
        }
        val peakHourIndex = hourCounts.indices.maxByOrNull { hourCounts[it] } ?: 0
        val peakHour = when (peakHourIndex) {
            in 5..11 -> "Morning (${formatHour(peakHourIndex)})"
            in 12..16 -> "Afternoon (${formatHour(peakHourIndex)})"
            in 17..21 -> "Evening (${formatHour(peakHourIndex)})"
            else -> "Late night (${formatHour(peakHourIndex)})"
        }
        val personality = when (peakHourIndex) {
            in 5..8 -> "Early riser"
            in 9..11 -> "Morning commuter"
            in 12..16 -> "Afternoon listener"
            in 17..21 -> "Evening wind-down"
            else -> "Night owl"
        }
        val streaks = ScrobbleRulesEngine.computeStreaks(inYear.map { it.scrobbledAtMs })

        return OnyxWrappedUiState(
            year = year,
            range = range,
            isLoading = false,
            hasData = true,
            totalMinutes = totalMs / 60_000L,
            plays = inYear.size,
            uniqueTracks = inYear.map { it.trackKey }.distinct().size,
            uniqueArtists = inYear.map { it.artist.trim().lowercase() }.filter { it.isNotBlank() }.distinct().size,
            uniqueAlbums = inYear.map { it.album.trim().lowercase() }.filter { it.isNotBlank() }.distinct().size,
            topArtists = artists,
            topTracks = tracks,
            topAlbums = albums,
            topSource = source,
            peakHour = peakHour,
            longestStreakDays = streaks.longestStreakDays,
            personality = personality,
        )
    }

    private fun formatMinutes(ms: Long): String {
        val minutes = ms / 60_000L
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }

    private fun formatHour(hour: Int): String {
        val normalized = if (hour % 12 == 0) 12 else hour % 12
        val suffix = if (hour < 12) "AM" else "PM"
        return "$normalized $suffix"
    }
}
