package com.lastwave.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Per-song overrides that sit outside the global defaults. */
@Serializable
data class TrackOverride(
    val id: String,
    val title: String,
    val artist: String,
    val videoDisabled: Boolean = false,
    val canvasDisabled: Boolean = false,
    val animationsDisabled: Boolean = false,
) {
    val isActive: Boolean
        get() = videoDisabled || canvasDisabled || animationsDisabled
}

fun trackOverrideKey(title: String, artist: String, videoId: String? = null): String =
    videoId?.takeIf { it.isNotBlank() }
        ?: "${title.trim()}|${artist.trim()}".lowercase(Locale.ROOT)

@Singleton
class TrackOverridesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val OVERRIDES = stringPreferencesKey("lw_track_overrides")
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val overrides: Flow<Map<String, TrackOverride>> = dataStore.data
        .recoverPreferences("TrackOverridesRepository")
        .map { prefs ->
            val raw = prefs.readSafely(Keys.OVERRIDES) ?: return@map emptyMap()
            runCatching {
                json.decodeFromString<List<TrackOverride>>(raw)
                    .associateBy { it.id }
            }.getOrDefault(emptyMap())
        }

    suspend fun setOverride(override: TrackOverride) {
        dataStore.edit { prefs ->
            val current = decode(prefs.readSafely(Keys.OVERRIDES)).toMutableList()
            current.removeAll { it.id == override.id }
            if (override.isActive) current.add(0, override)
            prefs[Keys.OVERRIDES] = json.encodeToString(current)
        }
    }

    suspend fun removeOverride(id: String) {
        dataStore.edit { prefs ->
            val current = decode(prefs.readSafely(Keys.OVERRIDES)).filterNot { it.id == id }
            prefs[Keys.OVERRIDES] = json.encodeToString(current)
        }
    }

    private fun decode(raw: String?): List<TrackOverride> =
        raw?.let { runCatching { json.decodeFromString<List<TrackOverride>>(it) }.getOrNull() }
            .orEmpty()
}
