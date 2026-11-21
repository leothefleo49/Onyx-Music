package com.lastwave.app.data.scrobble

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

@Dao
interface ScrobbleEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: ScrobbleEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<ScrobbleEventEntity>): List<Long>

    @Update
    suspend fun update(event: ScrobbleEventEntity)

    @Query("DELETE FROM scrobble_events WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM scrobble_events ORDER BY scrobbledAtMs DESC")
    fun observeAll(): Flow<List<ScrobbleEventEntity>>

    @Query("SELECT * FROM scrobble_events ORDER BY scrobbledAtMs DESC")
    suspend fun getAll(): List<ScrobbleEventEntity>

    @Query("SELECT * FROM scrobble_events WHERE scrobbledAtMs >= :startMs AND scrobbledAtMs <= :endMs ORDER BY scrobbledAtMs DESC")
    suspend fun getInRange(startMs: Long, endMs: Long): List<ScrobbleEventEntity>

    @Query("SELECT * FROM scrobble_events WHERE scrobbledAtMs >= :startMs AND scrobbledAtMs <= :endMs ORDER BY scrobbledAtMs DESC")
    fun observeInRange(startMs: Long, endMs: Long): Flow<List<ScrobbleEventEntity>>

    @Query("SELECT * FROM scrobble_events ORDER BY scrobbledAtMs DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ScrobbleEventEntity>

    @Query("SELECT COUNT(*) FROM scrobble_events")
    fun observeCount(): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM scrobble_events " +
            "WHERE lastFmSyncStatus = 'PENDING' OR listenBrainzSyncStatus = 'PENDING'",
    )
    fun observePendingSyncCount(): Flow<Int>

    @Query(
        "SELECT * FROM scrobble_events " +
            "WHERE lastFmSyncStatus = 'PENDING' AND retryCount < :maxRetries " +
            "ORDER BY scrobbledAtMs ASC LIMIT :limit",
    )
    suspend fun getPendingLastFm(maxRetries: Int = 8, limit: Int = 50): List<ScrobbleEventEntity>

    @Query(
        "SELECT * FROM scrobble_events " +
            "WHERE listenBrainzSyncStatus = 'PENDING' AND retryCount < :maxRetries " +
            "ORDER BY scrobbledAtMs ASC LIMIT :limit",
    )
    suspend fun getPendingListenBrainz(maxRetries: Int = 8, limit: Int = 50): List<ScrobbleEventEntity>

    @Query(
        "SELECT * FROM scrobble_events " +
            "WHERE trackKey = :trackKey AND scrobbledAtMs BETWEEN :minTimeMs AND :maxTimeMs " +
            "ORDER BY scrobbledAtMs DESC LIMIT 1",
    )
    suspend fun findRecentDuplicate(
        trackKey: String,
        minTimeMs: Long,
        maxTimeMs: Long,
    ): ScrobbleEventEntity?

    @Query("UPDATE scrobble_events SET lastFmSyncStatus = :status, retryCount = :retryCount WHERE id = :id")
    suspend fun updateLastFmStatus(id: Long, status: String, retryCount: Int)

    @Query("UPDATE scrobble_events SET listenBrainzSyncStatus = :status, retryCount = :retryCount WHERE id = :id")
    suspend fun updateListenBrainzStatus(id: Long, status: String, retryCount: Int)

    @Query(
        "UPDATE scrobble_events SET trackKey = :trackKey, title = :title, artist = :artist, album = :album " +
            "WHERE id = :id",
    )
    suspend fun updateMetadata(id: Long, trackKey: String, title: String, artist: String, album: String)

    @Query("UPDATE scrobble_events SET isLoved = :loved WHERE id = :id")
    suspend fun updateLovedById(id: Long, loved: Boolean)

    @Query("UPDATE scrobble_events SET isLoved = :loved WHERE trackKey = :trackKey")
    suspend fun updateLovedByTrackKey(trackKey: String, loved: Boolean)

    @Query("SELECT * FROM scrobble_events WHERE LOWER(TRIM(artist)) = :artistLower ORDER BY scrobbledAtMs DESC")
    suspend fun getByArtist(artistLower: String): List<ScrobbleEventEntity>

    @Query("SELECT * FROM scrobble_events WHERE trackKey = :trackKey ORDER BY scrobbledAtMs DESC")
    suspend fun getByTrackKey(trackKey: String): List<ScrobbleEventEntity>

    @Query("SELECT scrobbledAtMs FROM scrobble_events ORDER BY scrobbledAtMs ASC")
    suspend fun getAllTimestamps(): List<Long>

    @Query("DELETE FROM scrobble_events")
    suspend fun clearAll()
}

@Dao
interface ScrobbleEditRuleDao {
    @Query("SELECT * FROM scrobble_edit_rules ORDER BY createdAtMs ASC, id ASC")
    fun observeAll(): Flow<List<ScrobbleEditRuleEntity>>

    @Query("SELECT * FROM scrobble_edit_rules WHERE enabled = 1 ORDER BY createdAtMs ASC, id ASC")
    suspend fun getEnabledRules(): List<ScrobbleEditRuleEntity>

    @Query("SELECT * FROM scrobble_edit_rules ORDER BY createdAtMs ASC, id ASC")
    suspend fun getAll(): List<ScrobbleEditRuleEntity>

    @Upsert
    suspend fun upsert(rule: ScrobbleEditRuleEntity): Long

    @Query("DELETE FROM scrobble_edit_rules WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ScrobbleAppFilterDao {
    @Query("SELECT * FROM scrobble_app_filters ORDER BY appName ASC")
    fun observeAll(): Flow<List<ScrobbleAppFilterEntity>>

    @Query("SELECT * FROM scrobble_app_filters ORDER BY appName ASC")
    suspend fun getAll(): List<ScrobbleAppFilterEntity>

    @Query("SELECT * FROM scrobble_app_filters WHERE packageName = :packageName LIMIT 1")
    suspend fun find(packageName: String): ScrobbleAppFilterEntity?

    @Upsert
    suspend fun upsert(filter: ScrobbleAppFilterEntity)

    @Upsert
    suspend fun upsertAll(filters: List<ScrobbleAppFilterEntity>)

    @Query("DELETE FROM scrobble_app_filters WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

@Database(
    entities = [
        ScrobbleEventEntity::class,
        ScrobbleEditRuleEntity::class,
        ScrobbleAppFilterEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class OnyxScrobbleDatabase : RoomDatabase() {
    abstract fun scrobbleEventDao(): ScrobbleEventDao
    abstract fun scrobbleEditRuleDao(): ScrobbleEditRuleDao
    abstract fun scrobbleAppFilterDao(): ScrobbleAppFilterDao
}

@Module
@InstallIn(SingletonComponent::class)
object OnyxScrobbleDatabaseModule {

    @Provides
    @Singleton
    fun provideOnyxScrobbleDatabase(
        @ApplicationContext context: Context,
    ): OnyxScrobbleDatabase = Room.databaseBuilder(
        context,
        OnyxScrobbleDatabase::class.java,
        "onyx_scrobble.db",
    )
        .fallbackToDestructiveMigration()
        .fallbackToDestructiveMigrationOnDowngrade()
        .build()

    @Provides
    @Singleton
    fun provideScrobbleEventDao(db: OnyxScrobbleDatabase): ScrobbleEventDao = db.scrobbleEventDao()

    @Provides
    @Singleton
    fun provideScrobbleEditRuleDao(db: OnyxScrobbleDatabase): ScrobbleEditRuleDao = db.scrobbleEditRuleDao()

    @Provides
    @Singleton
    fun provideScrobbleAppFilterDao(db: OnyxScrobbleDatabase): ScrobbleAppFilterDao = db.scrobbleAppFilterDao()
}
