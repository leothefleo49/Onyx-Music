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

/**
 * Preferred presentation mode applied automatically when a track or album starts
 * playing (can be configured per-album or per-track).
 */
@Serializable
enum class DefaultPlayerMode(val label: String) {
    FOLLOW_GLOBAL("App default"),
    FULLSCREEN_ARTWORK("Fullscreen"),
    LYRICS("Lyrics visible"),
    VIDEO("Music video"),
    LIVE_BACKGROUND("Live background"),
    NONE("Minimal / none"),
}

/** Per-song overrides that sit outside the global defaults. */
@Serializable
data class TrackOverride(
    val id: String,
    val title: String,
    val artist: String,
    val videoDisabled: Boolean = false,
    val canvasDisabled: Boolean = false,
    val animationsDisabled: Boolean = false,
    val defaultPlayMode: DefaultPlayerMode = DefaultPlayerMode.FOLLOW_GLOBAL,
) {
    val isActive: Boolean
        get() = videoDisabled ||
            canvasDisabled ||
            animationsDisabled ||
            defaultPlayMode != DefaultPlayerMode.FOLLOW_GLOBAL
}

/** Whole-album overrides applied to every track belonging to the album. */
@Serializable
data class AlbumOverride(
    val id: String,
    val album: String,
    val artist: String,
    val videoDisabled: Boolean = false,
    val canvasDisabled: Boolean = false,
    val animationsDisabled: Boolean = false,
    val defaultPlayMode: DefaultPlayerMode = DefaultPlayerMode.FOLLOW_GLOBAL,
) {
    val isActive: Boolean
        get() = videoDisabled ||
            canvasDisabled ||
            animationsDisabled ||
            defaultPlayMode != DefaultPlayerMode.FOLLOW_GLOBAL
}

fun trackOverrideKey(title: String, artist: String, videoId: String? = null): String =
    videoId?.takeIf { it.isNotBlank() }
        ?: "${title.trim()}|${artist.trim()}".lowercase(Locale.ROOT)

fun albumOverrideKey(album: String, artist: String = ""): String {
    val normAlbum = album.trim().lowercase(Locale.ROOT)
    val normArtist = artist.trim().lowercase(Locale.ROOT)
    return if (normArtist.isNotBlank()) "album:$normAlbum|$normArtist" else "album:$normAlbum"
}

@Singleton
class TrackOverridesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val OVERRIDES = stringPreferencesKey("lw_track_overrides")
        val ALBUM_OVERRIDES = stringPreferencesKey("lw_album_overrides")
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

    val albumOverrides: Flow<Map<String, AlbumOverride>> = dataStore.data
        .recoverPreferences("TrackOverridesRepository")
        .map { prefs ->
            val raw = prefs.readSafely(Keys.ALBUM_OVERRIDES) ?: return@map emptyMap()
            runCatching {
                json.decodeFromString<List<AlbumOverride>>(raw)
                    .associateBy { it.id }
            }.getOrDefault(emptyMap())
        }

    suspend fun setOverride(override: TrackOverride) {
        dataStore.edit { prefs ->
            val current = decodeTracks(prefs.readSafely(Keys.OVERRIDES)).toMutableList()
            current.removeAll { it.id == override.id }
            if (override.isActive) current.add(0, override)
            prefs[Keys.OVERRIDES] = json.encodeToString(current)
        }
    }

    suspend fun removeOverride(id: String) {
        dataStore.edit { prefs ->
            val current = decodeTracks(prefs.readSafely(Keys.OVERRIDES)).filterNot { it.id == id }
            prefs[Keys.OVERRIDES] = json.encodeToString(current)
        }
    }

    suspend fun setAlbumOverride(override: AlbumOverride) {
        dataStore.edit { prefs ->
            val current = decodeAlbums(prefs.readSafely(Keys.ALBUM_OVERRIDES)).toMutableList()
            current.removeAll { it.id == override.id }
            if (override.isActive) current.add(0, override)
            prefs[Keys.ALBUM_OVERRIDES] = json.encodeToString(current)
        }
    }

    suspend fun removeAlbumOverride(id: String) {
        dataStore.edit { prefs ->
            val current = decodeAlbums(prefs.readSafely(Keys.ALBUM_OVERRIDES)).filterNot { it.id == id }
            prefs[Keys.ALBUM_OVERRIDES] = json.encodeToString(current)
        }
    }

    private fun decodeTracks(raw: String?): List<TrackOverride> =
        raw?.let { runCatching { json.decodeFromString<List<TrackOverride>>(it) }.getOrNull() }
            .orEmpty()

    private fun decodeAlbums(raw: String?): List<AlbumOverride> =
        raw?.let { runCatching { json.decodeFromString<List<AlbumOverride>>(it) }.getOrNull() }
            .orEmpty()
}
