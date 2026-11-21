package com.lastwave.app.playback.automix.data

import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.youtubeVideoIdOrNull
import com.lastwave.app.playback.PlayableTrack
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository facade over [RemixPresetDatabase] with canonical track key normalization
 * so Manual DJ overrides and Deep Curated Mix edge recipes resolve identically whether
 * a track came from a playlist, YouTube Music, or local storage.
 */
@Singleton
class RemixPresetRepository @Inject constructor(
    private val dao: TransitionOverrideDao,
) {
    suspend fun getOverride(fromTrack: PlayableTrack, toTrack: PlayableTrack): TransitionOverrideEntity? {
        val primaryFrom = canonicalTrackKey(fromTrack)
        val primaryTo = canonicalTrackKey(toTrack)
        dao.getOverride(primaryFrom, primaryTo)?.let { return it }

        val fallbackFrom = fallbackTitleArtistKey(fromTrack.title, fromTrack.artist)
        val fallbackTo = fallbackTitleArtistKey(toTrack.title, toTrack.artist)
        if (fallbackFrom != primaryFrom || fallbackTo != primaryTo) {
            dao.getOverride(fallbackFrom, fallbackTo)?.let { return it }
        }
        return null
    }

    suspend fun getOverrideByKeys(fromTrackKey: String, toTrackKey: String): TransitionOverrideEntity? =
        dao.getOverride(fromTrackKey.trim(), toTrackKey.trim())

    fun observeOverride(fromTrackKey: String, toTrackKey: String): Flow<TransitionOverrideEntity?> =
        dao.observeOverride(fromTrackKey.trim(), toTrackKey.trim())

    fun observeOverridesForPlaylist(playlistId: Long): Flow<List<TransitionOverrideEntity>> =
        dao.observeOverridesForPlaylist(playlistId)

    suspend fun getOverridesForPlaylist(playlistId: Long): List<TransitionOverrideEntity> =
        dao.getOverridesForPlaylist(playlistId)

    fun observeAll(): Flow<List<TransitionOverrideEntity>> = dao.observeAll()

    suspend fun saveOverride(entity: TransitionOverrideEntity) {
        dao.upsert(entity.normalized().copy(updatedAtMs = System.currentTimeMillis()))
    }

    suspend fun savePlaylistRecipes(playlistId: Long, recipes: List<TransitionOverrideEntity>) {
        if (recipes.isEmpty()) return
        val now = System.currentTimeMillis()
        val normalized = recipes.mapIndexed { index, recipe ->
            recipe.normalized().copy(
                playlistId = playlistId,
                updatedAtMs = now + index,
            )
        }
        dao.upsertAll(normalized)
    }

    suspend fun deleteOverride(fromTrackKey: String, toTrackKey: String) {
        dao.delete(fromTrackKey.trim(), toTrackKey.trim())
    }

    suspend fun deletePlaylistRecipes(playlistId: Long) {
        dao.deleteByPlaylist(playlistId)
    }

    companion object {
        fun canonicalTrackKey(track: PlayableTrack): String {
            val videoId = track.videoId?.trim()?.takeIf { it.isNotEmpty() }
            if (videoId != null) return "vid:$videoId"
            val internalId = track.internalTrackId?.trim()?.takeIf { it.isNotEmpty() }
            if (internalId != null) return "id:$internalId"
            return fallbackTitleArtistKey(track.title, track.artist)
        }

        fun canonicalTrackKey(track: GeneratedTrack): String {
            val videoId = track.youtubeVideoIdOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            if (videoId != null) return "vid:$videoId"
            return fallbackTitleArtistKey(track.name, track.artist)
        }

        fun fallbackTitleArtistKey(title: String, artist: String): String {
            val normTitle = title.trim().lowercase().replace(Regex("\\s+"), " ")
            val normArtist = artist.trim().lowercase().replace(Regex("\\s+"), " ")
            return "ta:$normArtist|$normTitle"
        }
    }
}
