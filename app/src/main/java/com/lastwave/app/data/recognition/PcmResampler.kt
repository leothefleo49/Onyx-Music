package com.lastwave.app.data.recognition

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * High-speed sample-rate conversion for mono 16-bit PCM using a precomputed
 * polyphase Hann-windowed sinc lookup table. Low-passes below the output Nyquist
 * so content above it cannot fold back into the fingerprint band, while avoiding
 * millions of runtime sin()/cos() evaluations on mobile CPUs.
 */
object PcmResampler {

    private const val HALF_WIDTH = 16 // input samples either side of the output position
    private const val TAPS = HALF_WIDTH * 2 // 32-tap FIR
    private const val PHASES = 512
    private const val CUTOFF_MARGIN = 0.92

    private val tableCache = ConcurrentHashMap<Long, FloatArray>()

    fun resample(input: ShortArray, inRate: Int, outRate: Int): ShortArray {
        require(inRate > 0 && outRate > 0) { "sample rates must be positive" }
        if (inRate == outRate || input.isEmpty()) return input

        // Fast integer decimation path (e.g. 48,000 Hz -> 16,000 Hz = exact 3:1)
        if (inRate > outRate && inRate % outRate == 0) {
            return decimateInteger(input, inRate / outRate)
        }

        val step = inRate.toDouble() / outRate
        val cutoff = 0.5 * minOf(1.0, outRate.toDouble() / inRate) * CUTOFF_MARGIN
        val table = getOrBuildTable(inRate, outRate, cutoff)
        val outCount = (input.size / step).toInt()
        val out = ShortArray(outCount)
        val lastIdx = input.size - 1

        for (n in 0 until outCount) {
            val centre = n * step
            val base = centre.toInt()
            val frac = centre - base
            val phase = (frac * PHASES).toInt().coerceIn(0, PHASES - 1)
            val rowOffset = phase * TAPS
            val startInput = base - HALF_WIDTH + 1
            var acc = 0f
            for (t in 0 until TAPS) {
                val idx = startInput + t
                if (idx in 0..lastIdx) {
                    acc += input[idx] * table[rowOffset + t]
                }
            }
            out[n] = acc.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun decimateInteger(input: ShortArray, factor: Int): ShortArray {
        val cutoff = 0.5 / factor * CUTOFF_MARGIN
        val table = getOrBuildTable(factor, 1, cutoff)
        val outCount = input.size / factor
        val out = ShortArray(outCount)
        val lastIdx = input.size - 1
        for (n in 0 until outCount) {
            val startInput = n * factor - HALF_WIDTH + 1
            var acc = 0f
            for (t in 0 until TAPS) {
                val idx = startInput + t
                if (idx in 0..lastIdx) {
                    acc += input[idx] * table[t]
                }
            }
            out[n] = acc.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun getOrBuildTable(inRate: Int, outRate: Int, cutoff: Double): FloatArray {
        val key = (inRate.toLong() shl 32) or (outRate.toLong() and 0xFFFF_FFFFL)
        return tableCache.computeIfAbsent(key) {
            val table = FloatArray(PHASES * TAPS)
            for (p in 0 until PHASES) {
                val frac = p.toDouble() / PHASES
                val rowOffset = p * TAPS
                var sum = 0.0
                for (t in 0 until TAPS) {
                    val d = (t - HALF_WIDTH + 1) - frac
                    val w = kernel(d, cutoff)
                    table[rowOffset + t] = w.toFloat()
                    sum += w
                }
                if (sum > 1e-6) {
                    val inv = (1.0 / sum).toFloat()
                    for (t in 0 until TAPS) table[rowOffset + t] *= inv
                }
            }
            table
        }
    }

    private fun kernel(d: Double, cutoff: Double): Double {
        if (abs(d) >= HALF_WIDTH) return 0.0
        val window = 0.5 * (1.0 + cos(PI * d / HALF_WIDTH))
        val x = 2.0 * cutoff * d
        val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
        return 2.0 * cutoff * sinc * window
    }
}
