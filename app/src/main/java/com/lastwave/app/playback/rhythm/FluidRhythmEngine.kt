package com.lastwave.app.playback.rhythm

import android.os.SystemClock
import com.lastwave.app.playback.haptics.HapticPulseListener
import kotlin.math.exp
import kotlin.math.max

/**
 * Beat envelope for the beat-synced fluid background: follows the bass level
 * from the sink's [com.lastwave.app.playback.haptics.MusicHapticsAnalyzer],
 * so the artwork swells with every kick and bass hit and settles between them.
 * Discrete kick triggers missed hits and re-fired on 808 rolls.
 */
object FluidRhythmEngine : HapticPulseListener {

    // Lyra Player's rhythm.attack_ms / rhythm.release_ms: soft swell, slow settle.
    private const val ATTACK_MS = 35f
    private const val RELEASE_MS = 180f
    private const val BAND_ATTACK_MS = 26f
    private const val BAND_RELEASE_MS = 145f
    const val SPECTRUM_BAND_COUNT = 8

    @Volatile
    var currentRhythmValue: Float = 0f
        private set

    /** Live smoothed 8-band frequency spectrum (65 Hz .. 8.8 kHz, each 0..1). */
    val currentSpectrum: FloatArray = FloatArray(SPECTRUM_BAND_COUNT)

    /** Smoothly samples the 8-band spectrum at any normalised position `t` in 0..1. */
    fun sampleBand(fraction: Float): Float {
        val f = fraction.coerceIn(0f, 1f) * (SPECTRUM_BAND_COUNT - 1)
        val i0 = f.toInt().coerceIn(0, SPECTRUM_BAND_COUNT - 1)
        val i1 = (i0 + 1).coerceAtMost(SPECTRUM_BAND_COUNT - 1)
        val frac = f - i0
        return (currentSpectrum[i0] + (currentSpectrum[i1] - currentSpectrum[i0]) * frac).coerceIn(0f, 1f)
    }

    /**
     * Reference count of active beat consumers (fluid background, edge glow,
     * aurora seekbar). The analyser only runs while at least one wants beats.
     */
    private var consumers = 0

    @Volatile
    var isEnabled: Boolean = false
        private set

    fun acquire() {
        synchronized(this) {
            consumers++
            isEnabled = true
        }
    }

    fun release() {
        synchronized(this) {
            consumers = (consumers - 1).coerceAtLeast(0)
            if (consumers == 0) {
                isEnabled = false
                onAnalysisFlushed()
                target = 0f
                currentRhythmValue = 0f
                targetBands.fill(0f)
                currentSpectrum.fill(0f)
            }
        }
    }

    /** Legacy setter kept for the fluid background's on/off preference. */
    fun setPreferenceEnabled(enabled: Boolean) {
        synchronized(this) {
            if (enabled) consumers++ else consumers = (consumers - 1).coerceAtLeast(0)
            isEnabled = consumers > 0
        }
    }

    private var lastTickUptimeMs = 0L

    // Only the playing deck's sink may feed levels: the crossfade standby sent
    // zeros 8x a second that cut swells short mid-beat.
    @Volatile
    var liveSink: com.lastwave.app.playback.NativeProcessingAudioSink? = null

    // PCM is analysed when it enters the sink, a whole AudioTrack buffer (2.7 s
    // on a tablet) before it is heard. Levels wait here as (due uptime ms,
    // level) until they are audible.
    private val pendingLevels = ArrayDeque<Pair<Long, Float>>()
    private val pendingBands = ArrayDeque<Pair<Long, FloatArray>>()
    private var target = 0f
    private val targetBands = FloatArray(SPECTRUM_BAND_COUNT)

    override fun onHapticPulse(
        bassEnergy: Float,
        vocalEnergy: Float,
        isKick: Boolean,
        isSharpTransient: Boolean,
        leadTimeMs: Long,
    ) = Unit

    override fun onBassLevel(level: Float, leadTimeMs: Long) {
        synchronized(pendingLevels) {
            if (pendingLevels.size > 600) pendingLevels.removeFirst()
            pendingLevels.addLast(SystemClock.uptimeMillis() + leadTimeMs to level)
        }
    }

    override fun onSpectrumBands(bands: FloatArray, leadTimeMs: Long) {
        if (bands.size < SPECTRUM_BAND_COUNT) return
        synchronized(pendingBands) {
            if (pendingBands.size > 600) pendingBands.removeFirst()
            pendingBands.addLast(SystemClock.uptimeMillis() + leadTimeMs to bands)
        }
    }

    override fun onAnalysisFlushed() {
        synchronized(pendingLevels) { pendingLevels.clear() }
        synchronized(pendingBands) { pendingBands.clear() }
    }

    /**
     * Advanced every animation frame by the UI. Several consumers can tick
     * concurrently (fluid background + glow + seekbar); calls closer than
     * 8 ms apart are dropped so the envelope never advances twice a frame.
     */
    fun tick(dtSeconds: Float, isPlaying: Boolean): Float {
        val nowMs = SystemClock.uptimeMillis()
        if (nowMs - lastTickUptimeMs < 8L) return currentRhythmValue
        lastTickUptimeMs = nowMs
        val dtMs = (dtSeconds * 1000f).coerceIn(1f, 100f)
        if (!isEnabled || !isPlaying) {
            onAnalysisFlushed()
            target = 0f
            targetBands.fill(0f)
        } else {
            val now = SystemClock.uptimeMillis()
            synchronized(pendingLevels) {
                // Several 10 ms windows come due per frame: keep the loudest so no hit is skipped.
                var due = -1f
                while (pendingLevels.isNotEmpty() && pendingLevels.first().first <= now) {
                    due = max(due, pendingLevels.removeFirst().second)
                }
                if (due >= 0f) target = due
            }
            synchronized(pendingBands) {
                var hadDue = false
                for (i in 0 until SPECTRUM_BAND_COUNT) {
                    if (pendingBands.isNotEmpty() && pendingBands.first().first <= now) {
                        break
                    }
                }
                while (pendingBands.isNotEmpty() && pendingBands.first().first <= now) {
                    val b = pendingBands.removeFirst().second
                    if (!hadDue) {
                        for (i in 0 until SPECTRUM_BAND_COUNT) targetBands[i] = b[i]
                        hadDue = true
                    } else {
                        for (i in 0 until SPECTRUM_BAND_COUNT) targetBands[i] = max(targetBands[i], b[i])
                    }
                }
            }
        }
        val tau = if (target > currentRhythmValue) ATTACK_MS else RELEASE_MS
        currentRhythmValue = (currentRhythmValue + (target - currentRhythmValue) * (1f - exp(-dtMs / tau)))
            .coerceIn(0f, 1f)
        for (i in 0 until SPECTRUM_BAND_COUNT) {
            val cur = currentSpectrum[i]
            val tgt = targetBands[i]
            val bTau = if (tgt > cur) BAND_ATTACK_MS else BAND_RELEASE_MS
            currentSpectrum[i] = (cur + (tgt - cur) * (1f - exp(-dtMs / bTau))).coerceIn(0f, 1f)
        }
        return currentRhythmValue
    }
}
