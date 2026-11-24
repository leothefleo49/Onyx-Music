package com.lastwave.app.ui.recognition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.lyrics.GeniusLyricsApi
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.recognition.AudioSignature
import com.lastwave.app.data.recognition.MicCapture
import com.lastwave.app.data.recognition.RecognitionClient
import com.lastwave.app.data.recognition.RecognitionFailure
import com.lastwave.app.data.recognition.RecognizedSong
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.navigation.AppRouteNavigator
import com.lastwave.app.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RecognitionError { PERMISSION, MICROPHONE, SILENCE, NO_MATCH, RATE_LIMITED, NETWORK }

enum class RecognitionMode { SONG, LYRICS, HUM }

sealed interface RecognitionState {
    data object Idle : RecognitionState
    data class Listening(val elapsedSec: Float = 0f, val mode: RecognitionMode = RecognitionMode.SONG) : RecognitionState
    data class Identifying(val hint: String = "") : RecognitionState
    data class Found(val song: RecognizedSong, val alternates: List<RecognizedSong> = emptyList()) : RecognitionState
    data class Failed(val error: RecognitionError, val mode: RecognitionMode = RecognitionMode.SONG) : RecognitionState
}

@HiltViewModel
class MusicRecognitionViewModel @Inject constructor(
    private val client: RecognitionClient,
    private val geniusLyricsApi: GeniusLyricsApi,
    private val innerTubeApi: InnerTubeMusicApi,
    private val musicPlayer: MusicPlayer,
    private val routeNavigator: AppRouteNavigator,
    private val settingsPreferences: SettingsPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow<RecognitionState>(RecognitionState.Idle)
    val state: StateFlow<RecognitionState> = _state.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    val defaults: StateFlow<Pair<Boolean, Int>> = settingsPreferences.settings
        .map { it.recognitionHumMode to it.recognitionSensitivity }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false to 50)

    private var activeJob: Job? = null

    /**
     * Progressive streaming song identification: opens a single native 16 kHz mic
     * stream and fires overlapping Shazam probes at 2.8s, 5.0s, 7.5s, and 10.5s
     * using the same session UUID, canceling immediately as soon as a match lands.
     */
    fun start(
        humMode: Boolean? = null,
        sensitivityOverride: Int? = null,
    ) {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _audioLevel.value = 0f
            _state.value = RecognitionState.Listening(0f, RecognitionMode.SONG)
            _state.value = try {
                val prefs = settingsPreferences.settings.first()
                val sensitivity = (sensitivityOverride ?: prefs.recognitionSensitivity).coerceIn(0, 100)
                if (humMode != null) settingsPreferences.setRecognitionHumMode(humMode)
                if (sensitivityOverride != null) settingsPreferences.setRecognitionSensitivity(sensitivity)

                runProgressiveSongCapture(sensitivity)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: MicCapture.CaptureException) {
                RecognitionState.Failed(RecognitionError.MICROPHONE, RecognitionMode.SONG)
            } catch (_: RecognitionFailure.NoMatch) {
                RecognitionState.Failed(RecognitionError.NO_MATCH, RecognitionMode.SONG)
            } catch (_: RecognitionFailure.RateLimited) {
                RecognitionState.Failed(RecognitionError.RATE_LIMITED, RecognitionMode.SONG)
            } catch (_: Exception) {
                RecognitionState.Failed(RecognitionError.NETWORK, RecognitionMode.SONG)
            } finally {
                _audioLevel.value = 0f
            }
        }
    }

    private suspend fun runProgressiveSongCapture(sensitivity: Int): RecognitionState = coroutineScope {
        // Peak gate adapts cleanly: even at low sensitivity (13%), loud or quiet
        // music still triggers once real signal arrives because AudioSignature
        // auto-gains the PCM stream before fingerprinting.
        val peakFloor = (360 - sensitivity * 3).coerceIn(60, 360)
        val sessionUuid = UUID.randomUUID().toString()
        val ringBuffer = ShortArray(MAX_CAPTURE_SAMPLES)
        var filled = 0
        var maxPeakSeen = 0
        var nextProbeIdx = 0
        var earliestRetryAtSamples = 0
        val matched = AtomicBoolean(false)
        val outcomeChannel = Channel<Result<RecognizedSong>>(capacity = Channel.UNLIMITED)
        val probeJobs = ArrayList<Job>(PROBE_CHECKPOINTS_MS.size)
        var lastError: Throwable? = null

        val micJob = launch {
            MicCapture.stream16k(maxDurationMs = MAX_CAPTURE_MS) { chunk, level, peak ->
                if (matched.get()) return@stream16k
                _audioLevel.value = level
                if (peak > maxPeakSeen) maxPeakSeen = peak

                val copyLen = minOf(chunk.size, MAX_CAPTURE_SAMPLES - filled)
                if (copyLen > 0) {
                    System.arraycopy(chunk, 0, ringBuffer, filled, copyLen)
                    filled += copyLen
                }
                val elapsedSec = filled.toFloat() / AudioSignature.SAMPLE_RATE
                if (!matched.get()) {
                    _state.value = RecognitionState.Listening(elapsedSec, RecognitionMode.SONG)
                }

                val targetMs = PROBE_CHECKPOINTS_MS.getOrNull(nextProbeIdx) ?: return@stream16k
                val targetSamples = (targetMs * AudioSignature.SAMPLE_RATE / 1000L).toInt()
                if (filled >= targetSamples && filled >= earliestRetryAtSamples && maxPeakSeen >= peakFloor) {
                    nextProbeIdx++
                    val snapshot = ringBuffer.copyOf(filled)
                    val sampleMs = snapshot.size * 1000L / AudioSignature.SAMPLE_RATE
                    probeJobs += launch(Dispatchers.Default) {
                        if (matched.get()) return@launch
                        try {
                            val sigUri = AudioSignature.toConditionedDataUri(snapshot)
                            val probe = client.recognizeProbe(sigUri, sampleMs, sessionUuid)
                            val song = probe.song
                            if (song != null && matched.compareAndSet(false, true)) {
                                outcomeChannel.trySend(Result.success(song))
                            } else if (probe.retryMs != null && probe.retryMs > 0L) {
                                val extraSamples = (probe.retryMs * AudioSignature.SAMPLE_RATE / 1000L).toInt()
                                earliestRetryAtSamples = maxOf(earliestRetryAtSamples, snapshot.size + extraSamples)
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (err: Throwable) {
                            lastError = err
                        }
                    }
                }
            }
        }

        // Wait until either a progressive probe matches or capture + all probes finish
        val watcher = launch {
            micJob.join()
            if (!matched.get() && filled >= AudioSignature.SAMPLE_RATE * 2 && maxPeakSeen >= peakFloor) {
                _state.value = RecognitionState.Identifying("Checking full clip...")
                val finalSnap = ringBuffer.copyOf(filled)
                val finalMs = finalSnap.size * 1000L / AudioSignature.SAMPLE_RATE
                val finalJob = launch(Dispatchers.Default) {
                    try {
                        val sigUri = AudioSignature.toConditionedDataUri(finalSnap)
                        val probe = client.recognizeProbe(sigUri, finalMs, sessionUuid)
                        val song = probe.song
                        if (song != null && matched.compareAndSet(false, true)) {
                            outcomeChannel.trySend(Result.success(song))
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (err: Throwable) {
                        lastError = err
                    }
                }
                probeJobs += finalJob
            }
            probeJobs.forEach { it.join() }
            if (!matched.get()) {
                outcomeChannel.trySend(
                    Result.failure(
                        when {
                            maxPeakSeen < peakFloor -> SilenceSentinel()
                            lastError is RecognitionFailure.RateLimited -> lastError!!
                            lastError is RecognitionFailure.Network -> lastError!!
                            else -> RecognitionFailure.NoMatch()
                        },
                    ),
                )
            }
        }

        val result = outcomeChannel.receive()
        micJob.cancel()
        watcher.cancel()
        probeJobs.forEach { it.cancel() }
        outcomeChannel.close()

        result.fold(
            onSuccess = { RecognitionState.Found(it) },
            onFailure = { err ->
                when (err) {
                    is SilenceSentinel -> RecognitionState.Failed(RecognitionError.SILENCE, RecognitionMode.SONG)
                    is RecognitionFailure.RateLimited -> RecognitionState.Failed(RecognitionError.RATE_LIMITED, RecognitionMode.SONG)
                    is RecognitionFailure.Network -> RecognitionState.Failed(RecognitionError.NETWORK, RecognitionMode.SONG)
                    else -> RecognitionState.Failed(RecognitionError.NO_MATCH, RecognitionMode.SONG)
                }
            },
        )
    }

    /**
     * Identifies a song from sung or spoken lyrics (via Genius lyric-snippet search
     * with YouTube Music catalog fallback). Works out-of-the-box with no API keys.
     */
    fun identifyFromLyrics(candidates: List<String>) {
        val cleaned = candidates.map { it.trim() }.filter { it.length >= 3 }.distinct()
        if (cleaned.isEmpty()) {
            _state.value = RecognitionState.Failed(RecognitionError.SILENCE, RecognitionMode.LYRICS)
            return
        }
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            val primaryPhrase = cleaned.first()
            _state.value = RecognitionState.Identifying("\"$primaryPhrase\"")
            _state.value = try {
                val geniusHits = withContext(Dispatchers.IO) {
                    cleaned.take(2).flatMap { phrase ->
                        runCatching { geniusLyricsApi.searchByLyricSnippet(phrase) }.getOrDefault(emptyList())
                    }.distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
                }
                if (geniusHits.isNotEmpty()) {
                    RecognitionState.Found(
                        song = geniusHits.first(),
                        alternates = geniusHits.drop(1).take(3),
                    )
                } else {
                    val ytHits = withContext(Dispatchers.IO) {
                        runCatching { innerTubeApi.searchSongs("$primaryPhrase lyrics", limit = 5) }
                            .getOrDefault(emptyList())
                            .map { track ->
                                RecognizedSong(
                                    title = track.title,
                                    artist = track.artist,
                                    album = track.album,
                                    coverUrl = track.artworkUrl,
                                    shazamUrl = null,
                                )
                            }
                    }
                    if (ytHits.isNotEmpty()) {
                        RecognitionState.Found(
                            song = ytHits.first(),
                            alternates = ytHits.drop(1).take(3),
                        )
                    } else {
                        RecognitionState.Failed(RecognitionError.NO_MATCH, RecognitionMode.LYRICS)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                RecognitionState.Failed(RecognitionError.NETWORK, RecognitionMode.LYRICS)
            }
        }
    }

    fun updateSensitivity(value: Int) {
        viewModelScope.launch {
            settingsPreferences.setRecognitionSensitivity(value.coerceIn(0, 100))
        }
    }

    fun onPermissionDenied() {
        _state.value = RecognitionState.Failed(RecognitionError.PERMISSION)
    }

    fun playSong(song: RecognizedSong) {
        dismiss()
        musicPlayer.play(
            track = PlayableTrack(
                title = song.title,
                artist = song.artist,
                album = song.album,
                artworkUrl = song.coverUrl,
            ),
            sourceLabel = "Recognized",
        )
    }

    fun searchFor(song: RecognizedSong) {
        dismiss()
        val query = listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" ")
        routeNavigator.navigateTo(Screen.Search.createRoute(query))
    }

    fun dismiss() {
        activeJob?.cancel()
        _audioLevel.value = 0f
        _state.value = RecognitionState.Idle
    }

    private class SilenceSentinel : Exception()

    private companion object {
        val PROBE_CHECKPOINTS_MS = longArrayOf(2_800L, 5_000L, 7_500L, 10_500L)
        const val MAX_CAPTURE_MS = 12_000L
        const val MAX_CAPTURE_SAMPLES = (AudioSignature.SAMPLE_RATE * 12)
    }
}
