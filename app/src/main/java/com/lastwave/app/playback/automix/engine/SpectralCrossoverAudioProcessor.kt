package com.lastwave.app.playback.automix.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

enum class FilterSweepMode(val label: String) {
    OFF("Off"),
    HPF_OUT("HPF Out"),
    LPF_IN("LPF In"),
    DUAL_SWEEP("Dual Sweep"),
}

/**
 * Media3 AudioProcessor bridging real-time PCM audio buffers into the C++20 AutoMix DSP engine,
 * extended with 3-band Linkwitz-Riley-aligned manual DJ EQ swap faders (Low 120 Hz / Mid / High 3.8 kHz),
 * 3-band EQ Kill toggles, real-time HPF/LPF filter sweeps, and deck/LUFS gain trim.
 */
class SpectralCrossoverAudioProcessor : AudioProcessor {

    companion object {
        @Volatile
        private var nativeLoaded: Boolean = false

        init {
            nativeLoaded = runCatching {
                System.loadLibrary("lastwave_audio")
                true
            }.getOrDefault(false)
        }

        private const val LOW_CROSSOVER_HZ = 120.0f
        private const val HIGH_CROSSOVER_HZ = 3800.0f
    }

    private var inputAudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat = AudioFormat.NOT_SET

    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    @Volatile var isEnabled: Boolean = false
    @Volatile var blendProgress: Float = 0.0f
    @Volatile var applySpectralSweeps: Boolean = true
    @Volatile var tempoRatioAlpha: Float = 1.0f
    @Volatile var tempoRatioBeta: Float = 1.0f

    // 3-band DJ EQ swap faders (0.0 = outgoing-favored / cut, 0.5 = neutral/balanced, 1.0 = incoming-favored / boost)
    @Volatile var eqLowSwap: Float = 0.5f
    @Volatile var eqMidSwap: Float = 0.5f
    @Volatile var eqHighSwap: Float = 0.5f
    @Volatile var killLow: Boolean = false
    @Volatile var killMid: Boolean = false
    @Volatile var killHigh: Boolean = false
    @Volatile var filterSweepMode: FilterSweepMode = FilterSweepMode.DUAL_SWEEP
    @Volatile var manualGainTrimDb: Float = 0.0f
    @Volatile var lufsGainTrim: Float = 1.0f
    @Volatile var isIncomingDeck: Boolean = false

    // Lightweight 1st-order / biquad state per stereo channel for Kotlin 3-band EQ fader shaping
    private var configuredRateHz: Int = 0
    private var lowCoeff = 0.017f
    private var highCoeff = 0.45f
    private var lowStateL = 0f
    private var lowStateR = 0f
    private var highStateL = 0f
    private var highStateR = 0f
    private var sweepStateL = 0f
    private var sweepStateR = 0f

    fun isAudiblyEngaged(): Boolean {
        if (killLow || killMid || killHigh) return true
        if (kotlin.math.abs(manualGainTrimDb) > 0.05f) return true
        if (!isEnabled) return false
        return blendProgress > 0.001f ||
            kotlin.math.abs(eqLowSwap - 0.5f) > 0.01f ||
            kotlin.math.abs(eqMidSwap - 0.5f) > 0.01f ||
            kotlin.math.abs(eqHighSwap - 0.5f) > 0.01f ||
            kotlin.math.abs(lufsGainTrim - 1.0f) > 0.005f
    }

    /**
     * In-place PCM processing hook invoked directly from [com.lastwave.app.playback.NativeProcessingAudioSink]
     * so live DJ EQ swaps, 3-band kills, filter sweeps, and deck gain trims execute on BOTH float and
     * PCM16 audio paths (Media3's DefaultAudioSink drops custom AudioProcessors on float output).
     */
    fun processPcmBufferInPlace(
        pcmBuffer: ByteBuffer,
        sampleRateHz: Int,
        channelCount: Int,
        pcmEncoding: Int,
    ) {
        if (channelCount != 2 || !isAudiblyEngaged()) return
        val sr = sampleRateHz.coerceAtLeast(8_000)
        if (sr != configuredRateHz) {
            configuredRateHz = sr
            lowCoeff = computeOnePoleAlpha(LOW_CROSSOVER_HZ, sr.toFloat())
            highCoeff = computeOnePoleAlpha(HIGH_CROSSOVER_HZ, sr.toFloat())
            resetFilterStates()
        }

        val rawGains = computeBandGains(blendProgress)
        val lowGain = if (killLow) 0f else rawGains.first
        val midGain = if (killMid) 0f else rawGains.second
        val highGain = if (killHigh) 0f else rawGains.third
        val manualLinear = 10.0.pow(manualGainTrimDb.coerceIn(-12f, 6f) / 20.0).toFloat()
        val trim = (lufsGainTrim * manualLinear).coerceIn(0.25f, 2.0f)

        val sweepActive = applySpectralSweeps && filterSweepMode != FilterSweepMode.OFF && blendProgress > 0.01f
        val sweepIsHpf = when (filterSweepMode) {
            FilterSweepMode.HPF_OUT -> !isIncomingDeck
            FilterSweepMode.LPF_IN -> false
            FilterSweepMode.DUAL_SWEEP -> !isIncomingDeck
            FilterSweepMode.OFF -> false
        }
        val sweepAppliesToThisDeck = sweepActive && when (filterSweepMode) {
            FilterSweepMode.HPF_OUT -> !isIncomingDeck
            FilterSweepMode.LPF_IN -> isIncomingDeck
            FilterSweepMode.DUAL_SWEEP -> true
            FilterSweepMode.OFF -> false
        }
        val sweepAlpha = if (sweepAppliesToThisDeck) {
            val t = blendProgress.coerceIn(0f, 1f)
            val cutoffHz = if (sweepIsHpf) {
                // Sweep HPF from 35 Hz up to 2400 Hz on outgoing deck
                35f * (2400f / 35f).toDouble().pow(t.toDouble()).toFloat()
            } else {
                // Sweep LPF from 450 Hz up to 18000 Hz on incoming deck
                450f * (18000f / 450f).toDouble().pow(t.toDouble()).toFloat()
            }
            computeOnePoleAlpha(cutoffHz, sr.toFloat())
        } else 0f

        val alphaLow = lowCoeff
        val alphaHigh = highCoeff
        val startPos = pcmBuffer.position()
        val remaining = pcmBuffer.remaining()

        if (pcmEncoding == C.ENCODING_PCM_FLOAT) {
            val numFrames = remaining / 8
            if (numFrames <= 0) return
            val dup = pcmBuffer.duplicate().order(pcmBuffer.order())
            val floats = dup.asFloatBuffer()
            for (i in 0 until numFrames) {
                val idx = i * 2
                val inL = floats.get(idx)
                val inR = floats.get(idx + 1)

                lowStateL += alphaLow * (inL - lowStateL)
                lowStateR += alphaLow * (inR - lowStateR)
                highStateL += alphaHigh * (inL - highStateL)
                highStateR += alphaHigh * (inR - highStateR)

                var outL = ((lowStateL * lowGain) + ((highStateL - lowStateL) * midGain) + ((inL - highStateL) * highGain)) * trim
                var outR = ((lowStateR * lowGain) + ((highStateR - lowStateR) * midGain) + ((inR - highStateR) * highGain)) * trim

                if (sweepAppliesToThisDeck) {
                    sweepStateL += sweepAlpha * (outL - sweepStateL)
                    sweepStateR += sweepAlpha * (outR - sweepStateR)
                    if (sweepIsHpf) {
                        outL -= sweepStateL
                        outR -= sweepStateR
                    } else {
                        outL = sweepStateL
                        outR = sweepStateR
                    }
                }
                floats.put(idx, outL.coerceIn(-1.0f, 1.0f))
                floats.put(idx + 1, outR.coerceIn(-1.0f, 1.0f))
            }
        } else if (pcmEncoding == C.ENCODING_PCM_16BIT) {
            val numFrames = remaining / 4
            if (numFrames <= 0) return
            val dup = pcmBuffer.duplicate().order(pcmBuffer.order())
            val shorts = dup.asShortBuffer()
            for (i in 0 until numFrames) {
                val idx = i * 2
                val inL = shorts.get(idx) / 32768f
                val inR = shorts.get(idx + 1) / 32768f

                lowStateL += alphaLow * (inL - lowStateL)
                lowStateR += alphaLow * (inR - lowStateR)
                highStateL += alphaHigh * (inL - highStateL)
                highStateR += alphaHigh * (inR - highStateR)

                var outL = ((lowStateL * lowGain) + ((highStateL - lowStateL) * midGain) + ((inL - highStateL) * highGain)) * trim
                var outR = ((lowStateR * lowGain) + ((highStateR - lowStateR) * midGain) + ((inR - highStateR) * highGain)) * trim

                if (sweepAppliesToThisDeck) {
                    sweepStateL += sweepAlpha * (outL - sweepStateL)
                    sweepStateR += sweepAlpha * (outR - sweepStateR)
                    if (sweepIsHpf) {
                        outL -= sweepStateL
                        outR -= sweepStateR
                    } else {
                        outL = sweepStateL
                        outR = sweepStateR
                    }
                }
                shorts.put(idx, (outL.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                shorts.put(idx + 1, (outR.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
        }
        pcmBuffer.position(startPos)
    }

    fun applyManualDjParams(
        lowSwap: Float,
        midSwap: Float,
        highSwap: Float,
        tempoRatio: Float,
        filterSweepEnabled: Boolean,
        lufsTrim: Float = 1.0f,
    ) {
        eqLowSwap = lowSwap.coerceIn(0f, 1f)
        eqMidSwap = midSwap.coerceIn(0f, 1f)
        eqHighSwap = highSwap.coerceIn(0f, 1f)
        val clampedTempo = tempoRatio.coerceIn(0.85f, 1.15f)
        if (isIncomingDeck) {
            tempoRatioBeta = clampedTempo
        } else {
            tempoRatioAlpha = clampedTempo
        }
        applySpectralSweeps = filterSweepEnabled
        lufsGainTrim = lufsTrim.coerceIn(0.5f, 1.5f)
    }

    fun resetManualDjParams() {
        eqLowSwap = 0.5f
        eqMidSwap = 0.5f
        eqHighSwap = 0.5f
        tempoRatioAlpha = 1.0f
        tempoRatioBeta = 1.0f
        applySpectralSweeps = true
        lufsGainTrim = 1.0f
        blendProgress = 0.0f
    }

    /**
     * Computes effective 3-band linear gains for this deck given blend [progress] and the DJ swap faders.
     * Exposed internally for deterministic unit testing.
     */
    internal fun computeBandGains(progress: Float = blendProgress): Triple<Float, Float, Float> {
        val t = progress.coerceIn(0f, 1f)
        fun bandGain(swapFader: Float): Float {
            // swapFader in [0..1] shifts the transition crossover midpoint earlier (high value) or later (low value).
            val midpoint = (1.0f - swapFader).coerceIn(0.10f, 0.90f)
            val delta = if (isIncomingDeck) {
                ((t - (midpoint - 0.15f)) / 0.30f).coerceIn(0f, 1f)
            } else {
                (1.0f - ((t - (midpoint - 0.15f)) / 0.30f)).coerceIn(0f, 1f)
            }
            // When not actively blending (progress == 0), let manual faders audition direct +/-6dB trim.
            if (t <= 0.001f) {
                val trim = (0.5f + swapFader).coerceIn(0.25f, 1.5f)
                return trim
            }
            return (0.15f + 0.85f * delta).coerceIn(0f, 1.25f)
        }

        val low = bandGain(eqLowSwap)
        val mid = bandGain(eqMidSwap)
        val high = bandGain(eqHighSwap)
        return Triple(low, mid, high)
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT
        ) {
            return AudioFormat.NOT_SET
        }

        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = AudioFormat(
            inputAudioFormat.sampleRate,
            2,
            C.ENCODING_PCM_FLOAT,
        )

        val sr = inputAudioFormat.sampleRate.toFloat().coerceAtLeast(8000f)
        lowCoeff = computeOnePoleAlpha(LOW_CROSSOVER_HZ, sr)
        highCoeff = computeOnePoleAlpha(HIGH_CROSSOVER_HZ, sr)
        resetFilterStates()

        if (nativeLoaded) {
            runCatching { nativeInit(sr) }
        }
        return if (isEnabled) outputAudioFormat else AudioFormat.NOT_SET
    }

    override fun isActive(): Boolean = isEnabled && inputAudioFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val numFrames = if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            remaining / (inputAudioFormat.channelCount * 2)
        } else {
            remaining / (inputAudioFormat.channelCount * 4)
        }
        if (numFrames <= 0) return

        val requiredBytes = numFrames * 2 * 4 // Stereo float (8 bytes per frame)
        if (buffer.capacity() < requiredBytes) {
            buffer = ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }

        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            val shorts = inputBuffer.asShortBuffer()
            val floats = buffer.asFloatBuffer()
            val channels = inputAudioFormat.channelCount
            for (i in 0 until numFrames) {
                val sL = shorts.get() / 32768.0f
                val sR = if (channels > 1) shorts.get() / 32768.0f else sL
                floats.put(sL)
                floats.put(sR)
            }
            inputBuffer.position(inputBuffer.position() + remaining)
        } else {
            buffer.put(inputBuffer)
        }
        buffer.flip()

        if (outputBuffer.capacity() < requiredBytes) {
            outputBuffer = ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }

        val nativeSucceeded = if (nativeLoaded) {
            runCatching {
                nativeSetTempoRatio(tempoRatioAlpha, tempoRatioBeta)
                nativeProcessStereo(
                    alphaBuf = buffer,
                    betaBuf = null,
                    outBuf = outputBuffer,
                    numFrames = numFrames,
                    progress = blendProgress,
                    applySpectralSweeps = applySpectralSweeps,
                )
                true
            }.getOrDefault(false)
        } else {
            false
        }

        if (!nativeSucceeded) {
            outputBuffer.put(buffer)
            outputBuffer.flip()
        } else {
            outputBuffer.position(0)
            outputBuffer.limit(requiredBytes)
        }

        // Apply 3-band DJ EQ swap shaping and LUFS contour trim in-place when active.
        val needsEqShaping = kotlin.math.abs(eqLowSwap - 0.5f) > 0.01f ||
            kotlin.math.abs(eqMidSwap - 0.5f) > 0.01f ||
            kotlin.math.abs(eqHighSwap - 0.5f) > 0.01f ||
            kotlin.math.abs(lufsGainTrim - 1.0f) > 0.005f

        if (needsEqShaping) {
            applyThreeBandShapingInPlace(outputBuffer, numFrames)
        }

        outputBuffer.position(0)
        outputBuffer.limit(requiredBytes)
    }

    private fun applyThreeBandShapingInPlace(stereoFloatBuf: ByteBuffer, numFrames: Int) {
        val (lowGain, midGain, highGain) = computeBandGains(blendProgress)
        val trim = lufsGainTrim.coerceIn(0.5f, 1.5f)
        val floats = stereoFloatBuf.asFloatBuffer()
        val alphaLow = lowCoeff
        val alphaHigh = highCoeff

        for (i in 0 until numFrames) {
            val idx = i * 2
            val inL = floats.get(idx)
            val inR = floats.get(idx + 1)

            // 3-band split: Low (<120Hz), High (>3.8kHz), Mid (remainder)
            lowStateL += alphaLow * (inL - lowStateL)
            lowStateR += alphaLow * (inR - lowStateR)

            highStateL += alphaHigh * (inL - highStateL)
            highStateR += alphaHigh * (inR - highStateR)

            val lowL = lowStateL
            val lowR = lowStateR
            val highL = inL - highStateL
            val highR = inR - highStateR
            val midL = highStateL - lowStateL
            val midR = highStateR - lowStateR

            val outL = ((lowL * lowGain) + (midL * midGain) + (highL * highGain)) * trim
            val outR = ((lowR * lowGain) + (midR * midGain) + (highR * highGain)) * trim

            floats.put(idx, outL.coerceIn(-1.0f, 1.0f))
            floats.put(idx + 1, outR.coerceIn(-1.0f, 1.0f))
        }
    }

    private fun computeOnePoleAlpha(cutoffHz: Float, sampleRate: Float): Float {
        val omega = 2.0 * PI * cutoffHz.toDouble() / sampleRate.toDouble()
        val k = tan(omega.coerceIn(0.0001, PI * 0.45) * 0.5)
        val alpha = (2.0 * k / (1.0 + k)).toFloat()
        return alpha.coerceIn(0.001f, 0.99f)
    }

    private fun resetFilterStates() {
        lowStateL = 0f
        lowStateR = 0f
        highStateL = 0f
        highStateR = 0f
        sweepStateL = 0f
        sweepStateR = 0f
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer == AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        resetFilterStates()
        if (nativeLoaded) {
            runCatching { nativeReset() }
        }
    }

    override fun reset() {
        flush()
        buffer = AudioProcessor.EMPTY_BUFFER
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
    }

    // Native JNI functions
    private external fun nativeInit(sampleRate: Float)
    private external fun nativeReset()
    private external fun nativeSetTempoRatio(ratioAlpha: Float, ratioBeta: Float)
    private external fun nativeProcessStereo(
        alphaBuf: ByteBuffer?,
        betaBuf: ByteBuffer?,
        outBuf: ByteBuffer,
        numFrames: Int,
        progress: Float,
        applySpectralSweeps: Boolean,
    )
    private external fun nativeEstimateLufs(pcmBuf: ByteBuffer, numFrames: Int): Float
}
