package com.lastwave.app.ui.recognition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.recognition.AudioSignature
import com.lastwave.app.data.recognition.MicCapture
import com.lastwave.app.data.recognition.PcmResampler
import com.lastwave.app.data.recognition.RecognitionClient
import com.lastwave.app.data.recognition.RecognitionFailure
import com.lastwave.app.data.recognition.RecognizedSong
import com.lastwave.app.ui.navigation.AppRouteNavigator
import com.lastwave.app.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RecognitionError { PERMISSION, MICROPHONE, SILENCE, NO_MATCH, RATE_LIMITED, NETWORK }

sealed interface RecognitionState {
    data object Idle : RecognitionState
    data object Listening : RecognitionState
    data object Identifying : RecognitionState
    data class Found(val song: RecognizedSong) : RecognitionState
    data class Failed(val error: RecognitionError) : RecognitionState
}

@HiltViewModel
class MusicRecognitionViewModel @Inject constructor(
    private val client: RecognitionClient,
    private val routeNavigator: AppRouteNavigator,
    private val settingsPreferences: com.lastwave.app.data.local.SettingsPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow<RecognitionState>(RecognitionState.Idle)
    val state: StateFlow<RecognitionState> = _state.asStateFlow()

    /** Humming-mode default and sensitivity from Settings. */
    val defaults: StateFlow<Pair<Boolean, Int>> = settingsPreferences.settings
        .map { it.recognitionHumMode to it.recognitionSensitivity }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), false to 50)

    private var job: Job? = null

    /** Caller must already hold RECORD_AUDIO. */
    fun start(
        humMode: Boolean? = null,
        sensitivityOverride: Int? = null,
    ) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = RecognitionState.Listening
            _state.value = try {
                val settings = settingsPreferences.settings.first()
                val hum = humMode ?: settings.recognitionHumMode
                val sensitivity = (sensitivityOverride ?: settings.recognitionSensitivity).coerceIn(0, 100)
                if (humMode != null) settingsPreferences.setRecognitionHumMode(hum)
                if (sensitivityOverride != null) settingsPreferences.setRecognitionSensitivity(sensitivity)

                suspend fun captureOnce(listenMs: Long, threshold: Int): RecognizedSong? {
                    val clip = MicCapture.record(listenMs)
                    if (MicCapture.isSilent(clip.samples, threshold)) return null
                    _state.value = RecognitionState.Identifying
                    val (signature, sampleMs) = withContext(Dispatchers.Default) {
                        val pcm = PcmResampler.resample(clip.samples, clip.sampleRate, AudioSignature.SAMPLE_RATE)
                        AudioSignature.toDataUri(pcm) to pcm.size * 1000L / AudioSignature.SAMPLE_RATE
                    }
                    return client.recognize(signature, sampleMs)
                }

                // 700 at "strict" down to 100 at "very lenient". Humming and
                // solo instruments are quiet, so the hum mode floor is lower.
                val silenceThreshold = if (hum) {
                    (350 - sensitivity * 2).coerceIn(80, 350)
                } else {
                    (700 - sensitivity * 6).coerceIn(100, 700)
                }
                val listenMs = if (hum) HUM_LISTEN_MS else LISTEN_MS
                val first = try {
                    captureOnce(listenMs, silenceThreshold)
                } catch (noMatch: RecognitionFailure.NoMatch) {
                    // Auto-adapt: one more, longer and more lenient pass.
                    _state.value = RecognitionState.Listening
                    captureOnce(
                        if (hum) HUM_LISTEN_MS else RETRY_LISTEN_MS,
                        (silenceThreshold - 120).coerceAtLeast(80),
                    )
                }
                if (first == null) {
                    RecognitionState.Failed(RecognitionError.SILENCE)
                } else {
                    RecognitionState.Found(first)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MicCapture.CaptureException) {
                RecognitionState.Failed(RecognitionError.MICROPHONE)
            } catch (e: RecognitionFailure.NoMatch) {
                RecognitionState.Failed(RecognitionError.NO_MATCH)
            } catch (e: RecognitionFailure.RateLimited) {
                RecognitionState.Failed(RecognitionError.RATE_LIMITED)
            } catch (e: Exception) {
                RecognitionState.Failed(RecognitionError.NETWORK)
            }
        }
    }

    fun onPermissionDenied() {
        _state.value = RecognitionState.Failed(RecognitionError.PERMISSION)
    }

    fun searchFor(song: RecognizedSong) {
        dismiss()
        routeNavigator.navigateTo(Screen.Search.createRoute(listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" ")))
    }

    /** Stops listening or identifying and closes the sheet. */
    fun dismiss() {
        job?.cancel()
        _state.value = RecognitionState.Idle
    }

    private companion object {
        const val LISTEN_MS = 10_000L
        const val RETRY_LISTEN_MS = 14_000L
        const val HUM_LISTEN_MS = 15_000L
    }
}
