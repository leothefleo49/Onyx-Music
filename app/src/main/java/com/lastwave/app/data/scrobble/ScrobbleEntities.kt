package com.lastwave.app.data.scrobble

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object ScrobbleSyncStatus {
    const val PENDING = "PENDING"
    const val SYNCED = "SYNCED"
    const val FAILED = "FAILED"
    const val SKIPPED = "SKIPPED"
}

/**
 * Distinguishes pure music listens from non-music videos, podcasts, or other media
 * so Stats can keep Music pure by default while still scrobbling and showing videos
 * and podcasts under the "Videos & Other" tab.
 */
object ScrobbleMediaCategory {
    const val MUSIC = "MUSIC"
    const val VIDEO = "VIDEO"
    const val PODCAST = "PODCAST"
    const val OTHER = "OTHER"

    fun isMusic(category: String?): Boolean =
        category.isNullOrBlank() || category.equals(MUSIC, ignoreCase = true)
}

/**
 * Canonical normalized track key shared across scrobble deduplication,
 * stats aggregation, and two-way Last.fm love synchronization.
 */
fun normalizeScrobbleTrackKey(artist: String, title: String): String =
    "${artist.trim().lowercase()}|${title.trim().lowercase()}"

/**
 * Aggressive cross-platform canonical key (strips diacritics, remaster/deluxe
 * suffixes, featuring tags, "- Topic" / "VEVO", and punctuation) so the same
 * play logged by Onyx, Last.fm app, Spotify, and YouTube Music always merges cleanly.
 */
fun canonicalScrobbleKey(artist: String, title: String): String =
    com.lastwave.app.data.spotify.mirror.SpotifyTrackNormalizer.canonicalTrackKey(title, artist)

@Entity(
    tableName = "scrobble_events",
    indices = [
        Index(value = ["scrobbledAtMs"]),
        Index(value = ["trackKey"]),
        Index(value = ["sourcePackage"]),
        Index(value = ["lastFmSyncStatus"]),
        Index(value = ["listenBrainzSyncStatus"]),
        Index(value = ["mediaCategory"]),
    ],
)
data class ScrobbleEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val trackKey: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val genre: String = "",
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
    val listenedMs: Long = 0L,
    val startedAtMs: Long = 0L,
    val scrobbledAtMs: Long = 0L,
    val sourcePackage: String = "",
    val sourceAppName: String = "",
    val isLoved: Boolean = false,
    val lastFmSyncStatus: String = ScrobbleSyncStatus.PENDING,
    val listenBrainzSyncStatus: String = ScrobbleSyncStatus.SKIPPED,
    val retryCount: Int = 0,
    val mediaCategory: String = ScrobbleMediaCategory.MUSIC,
    /** Active audio output route when played (e.g. "Bluetooth · Space One Pro", "USB DAC", "Speaker"). */
    val outputDevice: String = "",
    /** True if the user skipped before reaching 85% completion. */
    val wasSkipped: Boolean = false,
    /** True if the track played through naturally (or from external history where completion is assumed). */
    val completed: Boolean = true,
)

@Entity(tableName = "scrobble_edit_rules")
data class ScrobbleEditRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val matchArtist: String = "",
    val matchTitle: String = "",
    val matchAlbum: String = "",
    val replaceArtist: String = "",
    val replaceTitle: String = "",
    val replaceAlbum: String = "",
    val isRegex: Boolean = false,
    val enabled: Boolean = true,
    val createdAtMs: Long = System.currentTimeMillis(),
)

@Entity(tableName = "scrobble_app_filters")
data class ScrobbleAppFilterEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val isAllowed: Boolean = true,
    val updatedAtMs: Long = System.currentTimeMillis(),
)
