package com.lastwave.app.data.recognition

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt

/** Records or streams mono 16-bit PCM from the microphone. */
object MicCapture {

    /** Legacy fixed capture rate kept for compatibility. */
    const val CAPTURE_RATE = 44_100
    const val PREFERRED_RATE = AudioSignature.SAMPLE_RATE // 16 kHz native

    class Clip(val samples: ShortArray, val sampleRate: Int)

    class CaptureException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Streams live 16 kHz mono PCM chunks (~100ms each) with normalized RMS level (0f..1f).
     * Prefers UNPROCESSED / VOICE_RECOGNITION audio sources so OEM voice AGC/NS
     * does not distort music playing from a car stereo or room speaker, and opens
     * native 16 kHz directly whenever hardware supports it (zero resampling lag).
     */
    @SuppressLint("MissingPermission")
    suspend fun stream16k(
        maxDurationMs: Long = 12_500L,
        onChunk: suspend (pcm16k: ShortArray, rmsLevel: Float, peakAbs: Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val (recorder, rate, minBuffer) = openBestRecorder()
        val chunkSamples = (rate / 10).coerceAtLeast(minBuffer / 2) // ~100ms chunks
        val maxSamples = (rate.toLong() * maxDurationMs / 1000L).toInt()
        var totalRead = 0
        try {
            recorder.startRecording()
            val buf = ShortArray(chunkSamples)
            while (totalRead < maxSamples) {
                currentCoroutineContext().ensureActive()
                val toRead = minOf(buf.size, maxSamples - totalRead)
                val read = recorder.read(buf, 0, toRead)
                if (read < 0) throw CaptureException("Microphone read failed ($read)")
                if (read == 0) continue
                totalRead += read

                val rawChunk = if (read == buf.size) buf.copyOf() else buf.copyOf(read)
                var sumSq = 0.0
                var peak = 0
                for (s in rawChunk) {
                    val v = s.toInt()
                    val a = abs(v)
                    if (a > peak) peak = a
                    sumSq += v.toDouble() * v.toDouble()
                }
                val rms = sqrt(sumSq / rawChunk.size.coerceAtLeast(1)) / 32768.0
                // Scale visually so normal car/room music fills 0.25..0.95 smoothly
                val level = (rms * 5.5).toFloat().coerceIn(0f, 1f)
                val pcm16k = if (rate == PREFERRED_RATE) {
                    rawChunk
                } else {
                    PcmResampler.resample(rawChunk, rate, PREFERRED_RATE)
                }
                onChunk(pcm16k, level, peak)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CaptureException) {
            throw e
        } catch (e: Exception) {
            throw CaptureException("Could not record audio", e)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openBestRecorder(): Triple<AudioRecord, Int, Int> {
        val sources = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                add(MediaRecorder.AudioSource.UNPROCESSED)
            }
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.MIC)
        }
        val rates = intArrayOf(PREFERRED_RATE, 48_000, CAPTURE_RATE)
        var lastError: Throwable? = null
        for (rate in rates) {
            val minBuffer = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) continue
            for (source in sources) {
                val rec = try {
                    AudioRecord(
                        source,
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        (minBuffer * 3).coerceAtLeast(rate / 2),
                    )
                } catch (e: Exception) {
                    lastError = e
                    null
                }
                if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                    return Triple(rec, rate, minBuffer)
                }
                rec?.release()
            }
        }
        throw CaptureException("Microphone is busy or unavailable", lastError)
    }

    /** Caller must already hold RECORD_AUDIO. */
    @SuppressLint("MissingPermission")
    suspend fun record(durationMs: Long): Clip = withContext(Dispatchers.IO) {
        val (recorder, rate, minBuffer) = openBestRecorder()
        val target = (rate * durationMs / 1000).toInt()
        val pcm = ShortArray(target)
        var filled = 0
        try {
            recorder.startRecording()
            val chunk = ShortArray(minBuffer / 2)
            while (filled < target) {
                currentCoroutineContext().ensureActive()
                val read = recorder.read(chunk, 0, minOf(chunk.size, target - filled))
                if (read < 0) throw CaptureException("Microphone read failed ($read)")
                System.arraycopy(chunk, 0, pcm, filled, read)
                filled += read
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CaptureException) {
            throw e
        } catch (e: Exception) {
            throw CaptureException("Could not record audio", e)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        Clip(pcm, rate)
    }

    /** True when the clip never rises above background noise. */
    fun isSilent(samples: ShortArray, threshold: Int = 300): Boolean =
        samples.none { abs(it.toInt()) >= threshold }
}
