package com.lastwave.app.playback.automix.api

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DJ crossfade volume curve shapes supported by Smart AutoMix and Manual DJ Remixing.
 */
enum class CrossfadeCurveType {
    LINEAR,
    EQUAL_POWER,
    EXPONENTIAL,
    S_CURVE,
    CUT_IN;

    companion object {
        fun fromNameOrDefault(
            name: String?,
            default: CrossfadeCurveType = EQUAL_POWER,
        ): CrossfadeCurveType = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: default
    }
}

data class DeckGainPair(
    val outgoingGain: Float,
    val incomingGain: Float,
)

/**
 * Pure crossfade gain evaluator with headroom limiting so combined deck energy never clips.
 */
object CrossfadeCurveEvaluator {

    fun evaluate(
        progress: Float,
        curveType: CrossfadeCurveType,
        outgoingLufsTrim: Float = 1.0f,
        incomingLufsTrim: Float = 1.0f,
    ): DeckGainPair {
        val t = progress.coerceIn(0f, 1f)
        val (rawOut, rawIn) = when (curveType) {
            CrossfadeCurveType.LINEAR -> (1f - t) to t

            CrossfadeCurveType.EQUAL_POWER -> {
                val outG = cos(t * PI * 0.5).toFloat().coerceIn(0f, 1f)
                val inG = sin(t * PI * 0.5).toFloat().coerceIn(0f, 1f)
                outG to inG
            }

            CrossfadeCurveType.EXPONENTIAL -> {
                val outG = (1f - t).pow(2.0f).coerceIn(0f, 1f)
                val inG = t.pow(1.6f).coerceIn(0f, 1f)
                outG to inG
            }

            CrossfadeCurveType.S_CURVE -> {
                val smooth = (t * t * (3f - 2f * t)).coerceIn(0f, 1f)
                (1f - smooth) to smooth
            }

            CrossfadeCurveType.CUT_IN -> {
                // Incoming enters swiftly at full energy over first 20%, outgoing drops cleanly after 65%.
                val inG = (t / 0.20f).coerceIn(0f, 1f)
                val outG = when {
                    t < 0.65f -> 1f
                    else -> (1f - ((t - 0.65f) / 0.35f)).coerceIn(0f, 1f)
                }
                outG to inG
            }
        }

        val scaledOut = (rawOut * outgoingLufsTrim.coerceIn(0.5f, 1.5f)).coerceIn(0f, 1.5f)
        val scaledIn = (rawIn * incomingLufsTrim.coerceIn(0.5f, 1.5f)).coerceIn(0f, 1.5f)
        val sum = scaledOut + scaledIn
        val headroom = if (sum <= 1f) 1f else 1f / sqrt(sum)

        return DeckGainPair(
            outgoingGain = (scaledOut * headroom).coerceIn(0f, 1f),
            incomingGain = (scaledIn * headroom).coerceIn(0f, 1f),
        )
    }
}
