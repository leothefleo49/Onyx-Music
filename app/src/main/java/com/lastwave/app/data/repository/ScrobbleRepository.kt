package com.lastwave.app.data.repository

import android.content.Context
import com.lastwave.app.data.scrobble.ActiveNowPlaying
import com.lastwave.app.data.scrobble.ScrobbleSyncEngine
import com.lastwave.app.data.scrobble.SyncFlushReport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Facade used by [com.lastwave.app.playback.MusicPlaybackService] and UI ViewModels
 * for scrobbling, Now Playing updates, two-way Last.fm Love sync, and persistent
 * offline queue flushing.
 *
 * Backed by [ScrobbleSyncEngine] and Room (`onyx_scrobble.db`) so no scrobble is
 * ever lost on process death and every internal Onyx Music play is recorded alongside
 * external system-wide scrobbles.
 */
@Singleton
class ScrobbleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncEngine: ScrobbleSyncEngine,
) {
    val scrobbleEvents: SharedFlow<Unit> = syncEngine.scrobbleEvents
    val nowPlaying: StateFlow<ActiveNowPlaying?> = syncEngine.nowPlaying
    val pendingSyncCount: Flow<Int> = syncEngine.pendingSyncCount

    sealed interface Result {
        data object Success : Result
        data object NoSessionKey : Result
        data class Failed(
            val message: String,
            /** True when the failure is transient (rate limit / network) and the
             *  caller should retry or queue rather than treat it as permanent. */
            val retryable: Boolean = false,
        ) : Result
    }

    suspend fun updateNowPlaying(artist: String, track: String, album: String?): Result =
        syncEngine.updateNowPlaying(
            artist = artist,
            track = track,
            album = album,
            sourcePackage = context.packageName,
            sourceAppName = "Onyx Music",
        ).toLegacyResult()

    suspend fun scrobble(artist: String, track: String, album: String?, timestampSec: Long): Result =
        syncEngine.recordAndSyncScrobble(
            artist = artist,
            track = track,
            album = album,
            startedAtSec = timestampSec,
            sourcePackage = context.packageName,
            sourceAppName = "Onyx Music",
        ).toLegacyResult()

    suspend fun loveTrack(artist: String, track: String, eventId: Long? = null): Result =
        syncEngine.setTrackLoved(artist, track, loved = true, eventId = eventId).toLegacyResult()

    suspend fun unloveTrack(artist: String, track: String, eventId: Long? = null): Result =
        syncEngine.setTrackLoved(artist, track, loved = false, eventId = eventId).toLegacyResult()

    suspend fun syncLovedTracksFromLastFm(): kotlin.Result<Int> =
        syncEngine.pullLovedTracksFromLastFm()

    suspend fun flushPendingScrobbles(): SyncFlushReport =
        syncEngine.flushPendingQueue()

    private fun ScrobbleSyncEngine.RemoteResult.toLegacyResult(): Result = when (this) {
        ScrobbleSyncEngine.RemoteResult.Success -> Result.Success
        ScrobbleSyncEngine.RemoteResult.NoSessionKey -> Result.NoSessionKey
        is ScrobbleSyncEngine.RemoteResult.Failed -> Result.Failed(message = message, retryable = retryable)
    }
}
