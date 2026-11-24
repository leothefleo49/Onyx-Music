package com.lastwave.app.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Immutable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class AudioRouteKind(val label: String) {
    SPEAKER("Phone Speakers"),
    BLUETOOTH("Bluetooth Audio"),
    WIRED("Wired Headphones"),
    USB_DAC("USB DAC"),
}

@Immutable
data class ActiveAudioOutput(
    val routeKind: AudioRouteKind = AudioRouteKind.SPEAKER,
    val deviceKey: String = "speaker:built_in",
    val deviceName: String = "Phone Speakers",
    val maxSampleRateHz: Int = 48_000,
    val maxBitDepth: Int = 16,
    val supportsHiRes: Boolean = false,
    val recommendedQualityTier: Int = 5,
    val recommendedClarityPreset: Int = 1,
    val summaryLine: String = "Phone Speakers · 48 kHz",
)

/**
 * Monitors the active hardware audio output route (Bluetooth A2DP/BLE, USB DAC,
 * Wired 3.5mm/USB-C analog headphones, or Built-in Speakers) and computes
 * automatic optimal playback recommendations for Smart Auto-Best Output Mode.
 */
@Singleton
class AudioOutputRouteMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _activeOutput = MutableStateFlow(detectCurrentOutput())
    val activeOutput: StateFlow<ActiveAudioOutput> = _activeOutput.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refresh()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refresh()
        }
    }

    init {
        runCatching {
            audioManager?.registerAudioDeviceCallback(deviceCallback, mainHandler)
        }
    }

    fun refresh() {
        val next = detectCurrentOutput()
        if (_activeOutput.value != next) {
            _activeOutput.value = next
        }
    }

    private fun detectCurrentOutput(): ActiveAudioOutput {
        val am = audioManager ?: return ActiveAudioOutput()
        val devices = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.getOrDefault(emptyList())

        // Priority: USB DAC > Wired Headphones > Bluetooth A2DP/BLE > Built-in Speaker
        val usbDevice = devices.firstOrNull { info ->
            info.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                info.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                info.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
        if (usbDevice != null) {
            return buildOutputFor(usbDevice, AudioRouteKind.USB_DAC)
        }

        val wiredDevice = devices.firstOrNull { info ->
            info.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                info.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                info.type == AudioDeviceInfo.TYPE_LINE_ANALOG ||
                info.type == AudioDeviceInfo.TYPE_AUX_LINE
        }
        if (wiredDevice != null) {
            return buildOutputFor(wiredDevice, AudioRouteKind.WIRED)
        }

        val btDevice = devices.firstOrNull { info ->
            info.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (
                    info.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        info.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                        info.type == AudioDeviceInfo.TYPE_BLE_BROADCAST
                    ))
        }
        if (btDevice != null) {
            return buildOutputFor(btDevice, AudioRouteKind.BLUETOOTH)
        }

        val speakerDevice = devices.firstOrNull { info ->
            info.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        return if (speakerDevice != null) {
            buildOutputFor(speakerDevice, AudioRouteKind.SPEAKER)
        } else {
            ActiveAudioOutput()
        }
    }

    private fun buildOutputFor(info: AudioDeviceInfo, kind: AudioRouteKind): ActiveAudioOutput {
        val rawName = runCatching { info.productName?.toString()?.trim().orEmpty() }.getOrDefault("")
        val cleanName = if (rawName.isBlank() || rawName.equals(Build.MODEL, ignoreCase = true) && kind == AudioRouteKind.SPEAKER) {
            kind.label
        } else {
            rawName
        }
        val sampleRates = runCatching { info.sampleRates }.getOrNull()
        val maxRate = sampleRates?.maxOrNull()?.takeIf { it > 0 } ?: when (kind) {
            AudioRouteKind.USB_DAC -> 192_000
            AudioRouteKind.WIRED -> 96_000
            AudioRouteKind.BLUETOOTH -> 48_000
            AudioRouteKind.SPEAKER -> 48_000
        }
        val encodings = runCatching { info.encodings }.getOrNull() ?: IntArray(0)
        val maxBitDepth = when {
            encodings.contains(android.media.AudioFormat.ENCODING_PCM_FLOAT) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && encodings.contains(android.media.AudioFormat.ENCODING_PCM_32BIT)) -> 32
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && encodings.contains(android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED) -> 24
            kind == AudioRouteKind.USB_DAC || kind == AudioRouteKind.WIRED -> 24
            else -> 16
        }
        val supportsHiRes = maxRate >= 88_200 || kind == AudioRouteKind.USB_DAC || kind == AudioRouteKind.WIRED
        val recQuality = when {
            kind == AudioRouteKind.USB_DAC || kind == AudioRouteKind.WIRED -> 27 // Hi-Res 24/192
            kind == AudioRouteKind.BLUETOOTH && maxRate >= 88_200 -> 27 // LDAC / aptX HD / LHDC Hi-Res
            kind == AudioRouteKind.BLUETOOTH -> 6 // CD Lossless 16/44.1
            else -> 6 // Built-in speakers: keep CD Lossless (never cap down to 320k mid-song)
        }
        val recClarityPreset = when (kind) {
            AudioRouteKind.SPEAKER -> 1 // Speaker curve
            AudioRouteKind.BLUETOOTH, AudioRouteKind.WIRED -> 2 // Headphone curve
            AudioRouteKind.USB_DAC -> 3 // DAC Reference curve
        }
        val rateKhz = if (maxRate % 1000 == 0) "${maxRate / 1000} kHz" else "%.1f kHz".format(maxRate / 1000.0)
        val tierLabel = when (recQuality) {
            27 -> "Hi-Res"
            6 -> "CD Lossless"
            else -> "320 kbps"
        }
        return ActiveAudioOutput(
            routeKind = kind,
            deviceKey = "${kind.name.lowercase()}:${cleanName.lowercase()}",
            deviceName = cleanName,
            maxSampleRateHz = maxRate,
            maxBitDepth = maxBitDepth,
            supportsHiRes = supportsHiRes,
            recommendedQualityTier = recQuality,
            recommendedClarityPreset = recClarityPreset,
            summaryLine = "$cleanName · ${maxBitDepth}-bit / $rateKhz · Best: $tierLabel",
        )
    }
}
