package com.lastwave.app.data.spotify.mirror

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

/**
 * Cached Spotify catalog match (or explicit Local-Only verdict) for a track played in Onyx Music.
 * Keyed by [trackKey] so the same recording played from YouTube Music, FLAC, or local files reuses
 * one cached resolution without additional Spotify API calls.
 */
@Entity(
    tableName = "spotify_track_matches",
    indices = [
        Index(value = ["isrc"]),
        Index(value = ["resolvedAtMs"]),
    ],
)
data class SpotifyTrackMatchEntity(
    @PrimaryKey val trackKey: String,
    val sourceTitle: String,
    val sourceArtist: String,
    val spotifyUri: String?,
    val isrc: String?,
    val matchedTitle: String?,
    val matchedArtist: String?,
    val matchedDurationMs: Long? = null,
    val confidence: Float,
    val isCatalogMatch: Boolean,
    val isManualOverride: Boolean = false,
    val resolvedAtMs: Long,
)

/**
 * Append-only mirror history recording catalog-mirrored plays vs. local-only / yielded plays.
 */
@Entity(
    tableName = "spotify_mirror_logs",
    indices = [
        Index(value = ["playedAtMs"]),
        Index(value = ["isCatalogMatch"]),
    ],
)
data class SpotifyMirrorLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val trackKey: String,
    val sourceTitle: String,
    val sourceArtist: String,
    val spotifyUri: String?,
    val matchedTitle: String?,
    val matchedArtist: String?,
    val confidence: Float,
    val isCatalogMatch: Boolean,
    val mirrorOutcome: String,
    val playedAtMs: Long,
)

object MirrorOutcome {
    const val MIRRORED = "MIRRORED"
    const val LOCAL_ONLY = "LOCAL_ONLY"
    const val EXTERNAL_DEVICE_YIELD = "EXTERNAL_DEVICE_YIELD"
    const val ERROR = "ERROR"
}

@Dao
interface SpotifyTrackMatchDao {
    @Query("SELECT * FROM spotify_track_matches WHERE trackKey = :trackKey LIMIT 1")
    suspend fun findByTrackKey(trackKey: String): SpotifyTrackMatchEntity?

    @Query("SELECT * FROM spotify_track_matches WHERE isrc = :isrc AND isCatalogMatch = 1 LIMIT 1")
    suspend fun findByIsrc(isrc: String): SpotifyTrackMatchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SpotifyTrackMatchEntity)

    @Query("DELETE FROM spotify_track_matches WHERE trackKey = :trackKey")
    suspend fun deleteByTrackKey(trackKey: String)

    @Query("DELETE FROM spotify_track_matches")
    suspend fun clearAll()

    @Query("SELECT * FROM spotify_track_matches ORDER BY resolvedAtMs DESC LIMIT :limit")
    fun observeRecentMatches(limit: Int = 25): Flow<List<SpotifyTrackMatchEntity>>
}

@Dao
interface SpotifyMirrorLogDao {
    @Insert
    suspend fun insert(entity: SpotifyMirrorLogEntity): Long

    @Query("SELECT COUNT(*) FROM spotify_mirror_logs WHERE isCatalogMatch = 1 AND mirrorOutcome = 'MIRRORED'")
    fun observeCatalogPlayCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM spotify_mirror_logs WHERE isCatalogMatch = 0 OR mirrorOutcome = 'LOCAL_ONLY'")
    fun observeLocalOnlyPlayCount(): Flow<Int>

    @Query("SELECT * FROM spotify_mirror_logs ORDER BY playedAtMs DESC LIMIT :limit")
    fun observeRecentLogs(limit: Int = 25): Flow<List<SpotifyMirrorLogEntity>>

    @Query("DELETE FROM spotify_mirror_logs")
    suspend fun clearAll()
}

@Database(
    entities = [
        SpotifyTrackMatchEntity::class,
        SpotifyMirrorLogEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class SpotifyMirrorDatabase : RoomDatabase() {
    abstract fun trackMatchDao(): SpotifyTrackMatchDao
    abstract fun mirrorLogDao(): SpotifyMirrorLogDao

    companion object {
        const val DB_NAME = "onyx_spotify_mirror.db"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object SpotifyMirrorDatabaseModule {
    @Provides
    @Singleton
    fun provideSpotifyMirrorDatabase(
        @ApplicationContext context: Context,
    ): SpotifyMirrorDatabase = Room.databaseBuilder(
        context,
        SpotifyMirrorDatabase::class.java,
        SpotifyMirrorDatabase.DB_NAME,
    )
        .fallbackToDestructiveMigration()
        .fallbackToDestructiveMigrationOnDowngrade()
        .build()

    @Provides
    @Singleton
    fun provideSpotifyTrackMatchDao(database: SpotifyMirrorDatabase): SpotifyTrackMatchDao =
        database.trackMatchDao()

    @Provides
    @Singleton
    fun provideSpotifyMirrorLogDao(database: SpotifyMirrorDatabase): SpotifyMirrorLogDao =
        database.mirrorLogDao()
}
