package com.lastwave.app.playback.spatial

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.lastwave.app.data.local.SpatialAudioMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sin

/**
 * Head-tracked spatial audio for headphones.
 *
 * Reads the rotation-vector sensor and publishes the listener's yaw relative
 * to a recenter reference. [SpatialAudioStage] turns that angle into a small
 * interaural level shift so the stage stays put in the room when the head
 * turns — the same idea as Apple's head-tracked Spatial Audio, without HRTF
 * hardware. Everything is best-effort: no sensor, no tracking, audio is
 * untouched.
 */
@Singleton
class HeadTracker @Inject constructor(
    @ApplicationContext private val context: Context,
) : SensorEventListener {

    /** Yaw offset in radians, clamped to ±[MAX_YAW] by the stage. */
    @Volatile
    var yawRadians: Float = 0f
        private set

    @Volatile
    var isAvailable: Boolean = false
        private set

    private val sensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }
    private val rotationSensor by lazy {
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    }

    private var referenceAzimuth: Float? = null
    private var listening = false

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    fun start() {
        if (listening) return
        val manager = sensorManager ?: return
        val sensor = rotationSensor
        if (sensor == null) {
            isAvailable = false
            return
        }
        isAvailable = true
        runCatching {
            manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            listening = true
        }
    }

    fun stop() {
        if (!listening) return
        runCatching { sensorManager?.unregisterListener(this) }
        listening = false
        yawRadians = 0f
        referenceAzimuth = null
    }

    /** Treats the current head direction as "facing the music". */
    fun recenter() {
        referenceAzimuth = rawAzimuth
        yawRadians = 0f
    }

    private var rawAzimuth: Float = 0f

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR
        ) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val azimuth = orientation[0]
        rawAzimuth = azimuth
        val reference = referenceAzimuth ?: run {
            referenceAzimuth = azimuth
            azimuth
        }
        var delta = azimuth - reference
        // Shortest way around the circle.
        while (delta > PI) delta -= (2 * PI).toFloat()
        while (delta < -PI) delta += (2 * PI).toFloat()
        yawRadians = delta
        // The audio thread reads this every buffer; no allocation here.
        SpatialAudioStage.headYawRadians = delta
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** Past 60°, hold the stage: further rotation reads as a full turn. */
        const val MAX_YAW: Float = (PI / 3.0).toFloat()
    }
}

/**
 * Per-buffer stereo processing for [SpatialAudioMode]. Config is written by the
 * player from settings/sensors and read on the audio thread, so every field is
 * volatile and no allocation happens in [process].
 */
object SpatialAudioStage {

    @Volatile var mode: SpatialAudioMode = SpatialAudioMode.OFF
    @Volatile var crossfeed: Float = 0.25f
    @Volatile var width: Float = 1f
    @Volatile var bassMono: Boolean = false
    @Volatile var headYawRadians: Float = 0f

    // Persistent one-pole filter states across consecutive buffers to avoid boundary clicks.
    @Volatile private var lpL: Float = 0f
    @Volatile private var lpR: Float = 0f
    @Volatile private var bassL: Float = 0f
    @Volatile private var bassR: Float = 0f

    fun isActive(): Boolean = mode != SpatialAudioMode.OFF

    fun reset() {
        lpL = 0f
        lpR = 0f
        bassL = 0f
        bassR = 0f
    }

    /**
     * Applies width, crossfeed and head-tracked balance to interleaved stereo
     * float PCM, in place. Mid/side keeps the centre stable while the width
     * changes; crossfeed adds a low-passed opposite channel so hard-panned
     * content stops sounding stuck inside one ear.
     */
    fun process(buffer: java.nio.ByteBuffer, sampleRate: Int) {
        if (!isActive()) return
        val floats = buffer.asFloatBuffer()
        val frames = floats.remaining() / 2
        if (frames <= 0) return

        val safeWidth = width.coerceIn(0.3f, 2f)
        val safeCrossfeed = crossfeed.coerceIn(0f, 1f)
        val cf = safeCrossfeed * 0.45f
        // ~700 Hz one-pole for the crossfeed path: removes the comb-filter
        // harshness of an unfiltered opposite-channel add.
        val lpCoeff = (1f - Math.exp(-2.0 * PI * 700.0 / sampleRate.coerceAtLeast(8_000))).toFloat()
        val bassCoeff = (1f - Math.exp(-2.0 * PI * 120.0 / sampleRate.coerceAtLeast(8_000))).toFloat()

        val yaw = if (mode == SpatialAudioMode.HEAD_TRACKED) {
            headYawRadians.coerceIn(-HeadTracker.MAX_YAW, HeadTracker.MAX_YAW)
        } else 0f
        // ±60° maps to about ±3.5 dB, which reads as a real head turn without
        // collapsing one side.
        val pan = sin(yaw) * 0.42f
        val gainL = 1f - pan
        val gainR = 1f + pan

        var curLpL = lpL
        var curLpR = lpR
        var curBassL = bassL
        var curBassR = bassR
        for (i in 0 until frames) {
            val idx = i * 2
            var l = floats.get(idx)
            var r = floats.get(idx + 1)

            if (bassMono) {
                curBassL += bassCoeff * (l - curBassL)
                curBassR += bassCoeff * (r - curBassR)
                val lowMono = (curBassL + curBassR) * 0.5f
                l = (l - curBassL) + lowMono
                r = (r - curBassR) + lowMono
            }

            // Mid/side width.
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f * safeWidth
            l = mid + side
            r = mid - side

            // Crossfeed: a little of the opposite ear's low end.
            curLpL += lpCoeff * (l - curLpL)
            curLpR += lpCoeff * (r - curLpR)
            val outL = l + curLpR * cf
            val outR = r + curLpL * cf

            floats.put(idx, outL * gainL)
            floats.put(idx + 1, outR * gainR)
        }
        lpL = curLpL
        lpR = curLpR
        bassL = curBassL
        bassR = curBassR
    }

    /**
     * Applies width, crossfeed and head-tracked balance to interleaved stereo
     * 16-bit signed integer PCM in place, so standard 16-bit FLAC / AAC decoders
     * also receive full spatial rendering.
     */
    fun processPcm16(buffer: java.nio.ByteBuffer, sampleRate: Int) {
        if (!isActive()) return
        val shorts = buffer.asShortBuffer()
        val frames = shorts.remaining() / 2
        if (frames <= 0) return

        val safeWidth = width.coerceIn(0.3f, 2f)
        val safeCrossfeed = crossfeed.coerceIn(0f, 1f)
        val cf = safeCrossfeed * 0.45f
        val lpCoeff = (1f - Math.exp(-2.0 * PI * 700.0 / sampleRate.coerceAtLeast(8_000))).toFloat()
        val bassCoeff = (1f - Math.exp(-2.0 * PI * 120.0 / sampleRate.coerceAtLeast(8_000))).toFloat()

        val yaw = if (mode == SpatialAudioMode.HEAD_TRACKED) {
            headYawRadians.coerceIn(-HeadTracker.MAX_YAW, HeadTracker.MAX_YAW)
        } else 0f
        val pan = sin(yaw) * 0.42f
        val gainL = 1f - pan
        val gainR = 1f + pan

        var curLpL = lpL
        var curLpR = lpR
        var curBassL = bassL
        var curBassR = bassR
        val invScale = 1f / 32768f
        for (i in 0 until frames) {
            val idx = i * 2
            var l = shorts.get(idx) * invScale
            var r = shorts.get(idx + 1) * invScale

            if (bassMono) {
                curBassL += bassCoeff * (l - curBassL)
                curBassR += bassCoeff * (r - curBassR)
                val lowMono = (curBassL + curBassR) * 0.5f
                l = (l - curBassL) + lowMono
                r = (r - curBassR) + lowMono
            }

            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f * safeWidth
            l = mid + side
            r = mid - side

            curLpL += lpCoeff * (l - curLpL)
            curLpR += lpCoeff * (r - curLpR)
            val outL = (l + curLpR * cf) * gainL
            val outR = (r + curLpL * cf) * gainR

            val sL = (outL * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            val sR = (outR * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            shorts.put(idx, sL)
            shorts.put(idx + 1, sR)
        }
        lpL = curLpL
        lpR = curLpR
        bassL = curBassL
        bassR = curBassR
    }
}
