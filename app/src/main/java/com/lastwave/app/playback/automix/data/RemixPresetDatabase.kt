package com.lastwave.app.playback.automix.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.lastwave.app.playback.automix.api.CrossfadeCurveType
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

/**
 * Per-transition DJ remix override or Deep Curated Mix edge recipe.
 * Persisted in the isolated [RemixPresetDatabase] ("onyx_remix.db").
 */
@Entity(
    tableName = "transition_overrides",
    primaryKeys = ["fromTrackKey", "toTrackKey"],
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["updatedAtMs"]),
    ],
)
data class TransitionOverrideEntity(
    val fromTrackKey: String,
    val toTrackKey: String,
    val bpmMatchEnabled: Boolean = true,
    val targetTempoRatio: Float = 1.0f,
    val transitionDurationSec: Int = 12,
    val curveType: String = CrossfadeCurveType.EQUAL_POWER.name,
    val eqLowSwap: Float = 0.5f,
    val eqMidSwap: Float = 0.5f,
    val eqHighSwap: Float = 0.5f,
    val cueOutMs: Long = 0L,
    val cueInMs: Long = 0L,
    val filterSweepEnabled: Boolean = true,
    val playlistId: Long? = null,
    val compatibilityScore: Float? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    fun normalized(): TransitionOverrideEntity = copy(
        fromTrackKey = fromTrackKey.trim(),
        toTrackKey = toTrackKey.trim(),
        targetTempoRatio = targetTempoRatio.coerceIn(MIN_MANUAL_TEMPO_RATIO, MAX_MANUAL_TEMPO_RATIO),
        transitionDurationSec = transitionDurationSec.coerceIn(MIN_DURATION_SEC, MAX_DURATION_SEC),
        curveType = CrossfadeCurveType.fromNameOrDefault(curveType).name,
        eqLowSwap = eqLowSwap.coerceIn(0f, 1f),
        eqMidSwap = eqMidSwap.coerceIn(0f, 1f),
        eqHighSwap = eqHighSwap.coerceIn(0f, 1f),
        cueOutMs = cueOutMs.coerceAtLeast(0L),
        cueInMs = cueInMs.coerceAtLeast(0L),
        compatibilityScore = compatibilityScore?.coerceIn(0f, 1f),
    )

    companion object {
        const val MIN_DURATION_SEC = 4
        const val MAX_DURATION_SEC = 32
        const val MIN_SMART_TEMPO_RATIO = 0.94f
        const val MAX_SMART_TEMPO_RATIO = 1.06f
        const val MIN_MANUAL_TEMPO_RATIO = 0.85f
        const val MAX_MANUAL_TEMPO_RATIO = 1.15f
    }
}

@Dao
interface TransitionOverrideDao {
    @Query(
        "SELECT * FROM transition_overrides WHERE fromTrackKey = :fromTrackKey AND toTrackKey = :toTrackKey LIMIT 1",
    )
    suspend fun getOverride(fromTrackKey: String, toTrackKey: String): TransitionOverrideEntity?

    @Query(
        "SELECT * FROM transition_overrides WHERE fromTrackKey = :fromTrackKey AND toTrackKey = :toTrackKey LIMIT 1",
    )
    fun observeOverride(fromTrackKey: String, toTrackKey: String): Flow<TransitionOverrideEntity?>

    @Query("SELECT * FROM transition_overrides WHERE playlistId = :playlistId ORDER BY updatedAtMs ASC")
    suspend fun getOverridesForPlaylist(playlistId: Long): List<TransitionOverrideEntity>

    @Query("SELECT * FROM transition_overrides WHERE playlistId = :playlistId ORDER BY updatedAtMs ASC")
    fun observeOverridesForPlaylist(playlistId: Long): Flow<List<TransitionOverrideEntity>>

    @Query("SELECT * FROM transition_overrides ORDER BY updatedAtMs DESC")
    fun observeAll(): Flow<List<TransitionOverrideEntity>>

    @Upsert
    suspend fun upsert(entity: TransitionOverrideEntity)

    @Upsert
    suspend fun upsertAll(entities: List<TransitionOverrideEntity>)

    @Query("DELETE FROM transition_overrides WHERE fromTrackKey = :fromTrackKey AND toTrackKey = :toTrackKey")
    suspend fun delete(fromTrackKey: String, toTrackKey: String)

    @Query("DELETE FROM transition_overrides WHERE playlistId = :playlistId")
    suspend fun deleteByPlaylist(playlistId: Long)

    @Query("DELETE FROM transition_overrides")
    suspend fun clearAll()
}

@Database(
    entities = [TransitionOverrideEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class RemixPresetDatabase : RoomDatabase() {
    abstract fun transitionOverrideDao(): TransitionOverrideDao

    companion object {
        const val DB_NAME = "onyx_remix.db"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object RemixPresetDatabaseModule {

    @Provides
    @Singleton
    fun provideRemixPresetDatabase(
        @ApplicationContext context: Context,
    ): RemixPresetDatabase = Room.databaseBuilder(
        context,
        RemixPresetDatabase::class.java,
        RemixPresetDatabase.DB_NAME,
    )
        .fallbackToDestructiveMigration(dropAllTables = true)
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        .build()

    @Provides
    @Singleton
    fun provideTransitionOverrideDao(
        database: RemixPresetDatabase,
    ): TransitionOverrideDao = database.transitionOverrideDao()
}
