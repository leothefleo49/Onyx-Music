package com.lastwave.app.data.queue

import android.content.Context
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class PreviewQueue(
    val id: String,
    val title: String,
    val timestamp: Long,
    val tracks: List<PlayableTrack>,
    val artworkUrl: String? = null,
    /** Queue index the listener was on when this snapshot was taken. */
    val startIndex: Int = 0,
)

@Singleton
class PreviewQueueRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val applicationScope: CoroutineScope,
    private val settingsPreferences: com.lastwave.app.data.local.SettingsPreferences,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val file by lazy { File(context.filesDir, "preview_queues.json") }

    private val _previewQueues = MutableStateFlow<List<PreviewQueue>>(emptyList())
    val previewQueues: StateFlow<List<PreviewQueue>> = _previewQueues.asStateFlow()

    init {
        applicationScope.launch(Dispatchers.IO) {
            loadFromDisk()
        }
    }

    private suspend fun loadFromDisk() = mutex.withLock {
        runCatching {
            if (file.exists()) {
                val text = file.readText()
                val list = json.decodeFromString<List<PreviewQueue>>(text)
                _previewQueues.value = trimToPolicy(list)
            }
        }.onFailure {
            android.util.Log.w("PreviewQueueRepo", "Failed to load preview queues", it)
        }
    }

    /** Applies the user's retention count and age limits. */
    private suspend fun trimToPolicy(list: List<PreviewQueue>): List<PreviewQueue> {
        val settings = runCatching { settingsPreferences.settings.first() }.getOrNull()
        val maxCount = settings?.queueHistoryLimit ?: 30
        val maxAgeMs = (settings?.queueHistoryDays ?: 30).toLong() * 24L * 60L * 60L * 1000L
        val cutoff = System.currentTimeMillis() - maxAgeMs
        return list.filter { it.timestamp >= cutoff }.take(maxCount)
    }

    /** Keeps the most recent snapshot's resume position current as playback
     *  moves through it, without creating a second history entry. */
    fun updateActiveQueuePosition(tracks: List<PlayableTrack>, index: Int) {
        if (tracks.size < 2) return
        applicationScope.launch(Dispatchers.IO) {
            mutex.withLock {
                val current = _previewQueues.value.toMutableList()
                val top = current.firstOrNull() ?: return@withLock
                val sameQueue = top.tracks.size == tracks.size &&
                    top.tracks.firstOrNull()?.let { it.videoId ?: (it.title + it.artist) } ==
                    tracks.firstOrNull()?.let { it.videoId ?: (it.title + it.artist) }
                if (!sameQueue) return@withLock
                val safeIndex = index.coerceIn(0, top.tracks.lastIndex)
                if (top.startIndex == safeIndex) return@withLock
                current[0] = top.copy(startIndex = safeIndex)
                _previewQueues.value = current
                runCatching { file.writeText(json.encodeToString(current)) }
            }
        }
    }

    fun recordQueue(tracks: List<PlayableTrack>, sourceLabel: String = "Queue", startIndex: Int = 0) {
        if (tracks.size < 2) return
        val snapshotTracks = tracks.take(1500)
        val firstTrack = snapshotTracks.firstOrNull() ?: return
        val title = if (sourceLabel.isNotBlank() && sourceLabel != "Onyx" && sourceLabel != "Home") {
            sourceLabel
        } else {
            "${firstTrack.title} & more"
        }
        val artwork = snapshotTracks.firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) }
        val newEntry = PreviewQueue(
            id = System.currentTimeMillis().toString(),
            title = title,
            timestamp = System.currentTimeMillis(),
            tracks = snapshotTracks,
            artworkUrl = artwork,
            startIndex = startIndex.coerceIn(0, snapshotTracks.lastIndex.coerceAtLeast(0)),
        )

        applicationScope.launch(Dispatchers.IO) {
            mutex.withLock {
                val current = _previewQueues.value.toMutableList()
                val topFirstVideoId = current.firstOrNull()?.tracks?.firstOrNull()?.let { it.videoId ?: (it.title + it.artist) }
                val newFirstVideoId = firstTrack.videoId ?: (firstTrack.title + firstTrack.artist)
                if (topFirstVideoId == newFirstVideoId) {
                    current.removeAt(0)
                }
                current.add(0, newEntry)
                val trimmed = trimToPolicy(current)
                _previewQueues.value = trimmed
                runCatching {
                    file.writeText(json.encodeToString(trimmed))
                }
            }
        }
    }
}
