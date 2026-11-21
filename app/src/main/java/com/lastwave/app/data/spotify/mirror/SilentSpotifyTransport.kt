package com.lastwave.app.data.spotify.mirror

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

enum class TransportState {
    DISCONNECTED,
    CONNECTING,
    READY,
    UNAVAILABLE,
    ERROR,
}

data class TransportStatus(
    val state: TransportState = TransportState.DISCONNECTED,
    val deviceId: String? = null,
    val deviceName: String = SilentSpotifyTransport.DEFAULT_DEVICE_NAME,
    val detail: String? = null,
    val premiumRequired: Boolean = false,
)

data class SpotifyPlaybackContext(
    val isPlaying: Boolean,
    val activeDeviceId: String?,
    val activeDeviceName: String?,
    val activeTrackUri: String?,
    val progressMs: Long,
)

sealed class TransportCommandResult {
    data object Success : TransportCommandResult()
    data object Unauthorized : TransportCommandResult()
    data object PremiumRequired : TransportCommandResult()
    data object NoActiveDevice : TransportCommandResult()
    data class Failed(val code: Int, val message: String) : TransportCommandResult()
}

/**
 * Pluggable transport abstraction for a silent Spotify Connect receiver.
 *
 * [MutedWebPlaybackReceiver] is the primary Android WebView + Widevine EME implementation.
 * Alternative null-sink backends (such as an embedded librespot transport) can implement
 * this interface without touching the track matcher, state machine, or UI.
 */
interface SilentSpotifyTransport {
    val status: StateFlow<TransportStatus>
    val deviceName: String

    /** Optional host context hook for transports backed by a service-managed WebView. */
    fun bindHostContext(context: Context?) = Unit

    suspend fun connect(tokenProvider: suspend () -> String?)
    suspend fun disconnect()

    suspend fun playTrack(spotifyUri: String, positionMs: Long): TransportCommandResult
    suspend fun pause(): TransportCommandResult
    suspend fun seek(positionMs: Long): TransportCommandResult
    suspend fun setVolumeZero(): TransportCommandResult
    suspend fun queryCurrentPlayback(): SpotifyPlaybackContext?

    companion object {
        const val DEFAULT_DEVICE_NAME = "Onyx Music"
    }
}
