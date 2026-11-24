package com.lastwave.app.data.playlist

import com.lastwave.app.data.generate.LocalAudioFormats
import com.lastwave.app.data.generate.youtubeVideoIdOrNull
import com.lastwave.app.data.localmusic.LocalOfflineRepository
import com.lastwave.app.data.spotify.SpotifyLikedSongsSync
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.isLocalTrack
import com.lastwave.app.playback.toGeneratedTrack
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Coordinates local heart state, YouTube Music Liked Songs, and Spotify Liked Songs:
 *
 * - Device-local files (no YouTube id) persist into [LocalOfflineRepository],
 *   isolated from online Liked Songs, Spotify, Last.fm, and YouTube sync.
 * - Online/streaming tracks persist into the local Liked Songs playlist via
 *   [LikedSongsManager] and merge with live-synced [SpotifyLikedSongsSync] markers
 *   so every track liked on YouTube Music OR Spotify appears hearted immediately.
 */
@Singleton
class TrackLikeController @Inject constructor(
    private val likedSongsManager: LikedSongsManager,
    private val localOfflineRepository: LocalOfflineRepository,
    private val ytAuth: YtMusicAuthManager,
    private val spotifyLikedSongsSync: SpotifyLikedSongsSync,
    applicationScope: CoroutineScope,
) {
    val youTubeConnection = ytAuth.connection

    val likedTrackKeys: StateFlow<Set<String>> = combine(
        likedSongsManager.likedTrackKeys,
        spotifyLikedSongsSync.likedTrackMarkers,
        localOfflineRepository.likedUrls,
    ) { onlineKeys, spotifyKeys, offlineUrls ->
        val merged = HashSet<String>(onlineKeys.size + spotifyKeys.size + offlineUrls.size)
        merged.addAll(onlineKeys)
        merged.addAll(spotifyKeys)
        for (u in offlineUrls) merged.add("local:$u")
        merged
    }.stateIn(
        scope = applicationScope,
        started = SharingStarted.Eagerly,
        initialValue = likedSongsManager.likedTrackKeys.value +
            spotifyLikedSongsSync.likedTrackMarkers.value +
            localOfflineRepository.likedUrls.value.map { "local:$it" },
    )

    fun isLiked(track: PlayableTrack, likedKeys: Set<String> = likedTrackKeys.value): Boolean =
        if (isDeviceLocalTrack(track)) {
            val norm = LocalAudioFormats.importedPathOrNull(track.playbackUrl) ?: return false
            localOfflineRepository.isLiked(norm) || "local:$norm" in likedKeys
        } else {
            track.toGeneratedTrack().isLikedIn(likedKeys)
        }

    /** Flips the heart across Onyx, YouTube Music, and Spotify. */
    suspend fun toggle(track: PlayableTrack): Boolean {
        val localUrl = deviceLocalUrl(track)
        if (localUrl != null) {
            return localOfflineRepository.toggleLike(localUrl)
        }
        val currentlyLiked = isLiked(track)
        val desired = !currentlyLiked
        val result = likedSongsManager.setLiked(track.toGeneratedTrack(), desired)
        spotifyLikedSongsSync.onLocalLikeChanged(track.title, track.artist, result)
        return result
    }

    /** Idempotent local like for artwork double-tap: it never unlikes. */
    suspend fun like(track: PlayableTrack): Boolean {
        val localUrl = deviceLocalUrl(track)
        if (localUrl != null) {
            localOfflineRepository.setLiked(localUrl, true)
            return true
        }
        val result = likedSongsManager.like(track.toGeneratedTrack())
        spotifyLikedSongsSync.onLocalLikeChanged(track.title, track.artist, true)
        return result
    }

    /** Idempotent media rating / explicit like command. */
    suspend fun setLiked(track: PlayableTrack, liked: Boolean): Boolean {
        val localUrl = deviceLocalUrl(track)
        if (localUrl != null) {
            localOfflineRepository.setLiked(localUrl, liked)
            return liked
        }
        val result = likedSongsManager.setLiked(track.toGeneratedTrack(), liked)
        spotifyLikedSongsSync.onLocalLikeChanged(track.title, track.artist, result)
        return result
    }

    fun canUseYouTubeLike(track: PlayableTrack?): Boolean =
        track != null && ytAuth.connection.value.isConnected && youTubeIdOf(track) != null

    private fun isDeviceLocalTrack(track: PlayableTrack): Boolean = deviceLocalUrl(track) != null

    private fun deviceLocalUrl(track: PlayableTrack): String? {
        if (!track.videoId.isNullOrBlank() || !track.isLocalTrack()) return null
        return LocalAudioFormats.importedPathOrNull(track.playbackUrl)
    }

    private fun youTubeIdOf(track: PlayableTrack): String? =
        track.videoId?.takeIf { it.isNotBlank() } ?: track.toGeneratedTrack().youtubeVideoIdOrNull()

}
