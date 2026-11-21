package com.lastwave.app.data.spotify.mirror

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lastwave.app.data.local.readSafely
import com.lastwave.app.data.local.recoverPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.spotifyMirrorDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "onyx_spotify_mirror_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { error ->
        Log.e("SpotifyMirrorPrefs", "onyx_spotify_mirror_prefs corrupt; reset to defaults", error)
        emptyPreferences()
    },
)

data class SpotifyMirrorSettings(
    /** Disabled by default until the user connects Spotify Premium and opts in. */
    val enabled: Boolean = false,
    /** Yield silently when the user is actively playing on another Spotify Connect device. */
    val pauseWhenExternalDeviceActive: Boolean = true,
    /** Minimum candidate confidence (0.60..0.90) required to treat a track as a catalog match. */
    val minMatchConfidence: Float = SpotifyTrackMatcher.DEFAULT_MIN_CONFIDENCE,
)

data class SpotifyMirrorDiagnostics(
    val transportState: TransportState = TransportState.DISCONNECTED,
    val deviceName: String = SilentSpotifyTransport.DEFAULT_DEVICE_NAME,
    val deviceId: String? = null,
    val activeExternalDeviceName: String? = null,
    val lastSourceTitle: String? = null,
    val lastSourceArtist: String? = null,
    val lastMatchedUri: String? = null,
    val lastConfidence: Float = 0f,
    val lastIsCatalogMatch: Boolean? = null,
    val lastNote: String = "Off",
    val lastError: String? = null,
    val updatedAtMs: Long = 0L,
)

/**
 * Isolated preferences store and live diagnostics holder for Spotify silent device mirroring.
 */
@Singleton
class SpotifyMirrorPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val dataStore: DataStore<Preferences> = context.spotifyMirrorDataStore

    val settings: Flow<SpotifyMirrorSettings> = dataStore.data
        .recoverPreferences("SpotifyMirrorPreferences")
        .map { prefs ->
            SpotifyMirrorSettings(
                enabled = prefs.readSafely(ENABLED_KEY) ?: false,
                pauseWhenExternalDeviceActive = prefs.readSafely(RESPECT_EXTERNAL_DEVICE_KEY) ?: true,
                minMatchConfidence = (prefs.readSafely(MIN_CONFIDENCE_KEY)
                    ?: SpotifyTrackMatcher.DEFAULT_MIN_CONFIDENCE)
                    .coerceIn(MIN_CONFIDENCE_LOWER_BOUND, MIN_CONFIDENCE_UPPER_BOUND),
            )
        }

    private val _diagnostics = MutableStateFlow(SpotifyMirrorDiagnostics())
    val diagnostics: StateFlow<SpotifyMirrorDiagnostics> = _diagnostics.asStateFlow()

    suspend fun currentSettings(): SpotifyMirrorSettings = settings.first()

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[ENABLED_KEY] = enabled
        }
    }

    suspend fun setPauseWhenExternalDeviceActive(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[RESPECT_EXTERNAL_DEVICE_KEY] = enabled
        }
    }

    suspend fun setMinMatchConfidence(threshold: Float) {
        val clamped = threshold.coerceIn(MIN_CONFIDENCE_LOWER_BOUND, MIN_CONFIDENCE_UPPER_BOUND)
        dataStore.edit { prefs ->
            prefs[MIN_CONFIDENCE_KEY] = clamped
        }
    }

    fun updateDiagnostics(transform: (SpotifyMirrorDiagnostics) -> SpotifyMirrorDiagnostics) {
        _diagnostics.value = transform(_diagnostics.value).copy(
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    companion object {
        const val MIN_CONFIDENCE_LOWER_BOUND = 0.60f
        const val MIN_CONFIDENCE_UPPER_BOUND = 0.90f

        private val ENABLED_KEY = booleanPreferencesKey("spotify_mirror_enabled")
        private val RESPECT_EXTERNAL_DEVICE_KEY =
            booleanPreferencesKey("spotify_mirror_pause_external_device")
        private val MIN_CONFIDENCE_KEY = floatPreferencesKey("spotify_mirror_min_confidence")
    }
}
