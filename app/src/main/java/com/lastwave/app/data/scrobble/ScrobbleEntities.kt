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
 * Canonical normalized track key shared across scrobble deduplication,
 * stats aggregation, and two-way Last.fm love synchronization.
 */
fun normalizeScrobbleTrackKey(artist: String, title: String): String =
    "${artist.trim().lowercase()}|${title.trim().lowercase()}"

@Entity(
    tableName = "scrobble_events",
    indices = [
        Index(value = ["scrobbledAtMs"]),
        Index(value = ["trackKey"]),
        Index(value = ["sourcePackage"]),
        Index(value = ["lastFmSyncStatus"]),
        Index(value = ["listenBrainzSyncStatus"]),
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
