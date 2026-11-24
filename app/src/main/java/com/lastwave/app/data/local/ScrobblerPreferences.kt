package com.lastwave.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class ScrobblerSettings(
    /** Master switch — the listener service only actually submits anything
     *  when this is on AND Android's Notification Listener access has been
     *  granted (a separate OS-level permission the service can't request
     *  for itself, only be sent to Settings to grant). */
    val enabled: Boolean = false,
    /** Mirrors Last.fm's own scrobble.updateNowPlaying call — shows "now
     *  playing" on your profile immediately, separate from the actual
     *  scrobble which only submits once the track has played long enough. */
    val submitNowPlaying: Boolean = true,
    /** Percent of the track that must play before it's scrobbled, same
     *  idea as Pano Scrobbler's "Scrobble delay" percent slider. Last.fm's
     *  own rule (whichever comes first) is respected on top of this: a
     *  track must also be longer than 30s, and is capped at needing at
     *  most 4 minutes of playback regardless of this percent. */
    val scrobblePercent: Int = 50,
    /** Package names explicitly enabled for external app scrobbling. */
    val selectedPackages: Set<String> = emptySet(),
    /** Package names explicitly blocked from external app scrobbling. */
    val blockedPackages: Set<String> = emptySet(),
    /** When true, known external music players (Spotify, Apple Music, etc.)
     *  are scrobbled automatically unless explicitly listed in [blockedPackages]. */
    val autoScrobbleKnownPlayers: Boolean = true,
    /** Two-way Last.fm love sync (push love/unlove and pull user.getLovedTracks). */
    val twoWayLoveSync: Boolean = true,
    /** Push Onyx plays outbound to Last.fm API. Turn off to track locally only. */
    val pushToLastFm: Boolean = true,
    /** Automatically pause outbound Last.fm API pushes for Onyx plays when an external
     *  scrobbler (official Last.fm app, Pano Scrobbler, etc.) is active on this device
     *  so tracks are never double-scrobbled on Last.fm or double-counted in Onyx Stats. */
    val avoidExternalScrobblerDuplicates: Boolean = true,
    /** Automatically sync YT Music & Spotify recent listening history on launch/resume. */
    val autoSyncHistoryEnabled: Boolean = true,
    /** Optional ListenBrainz submission toggle + user token. */
    val listenBrainzEnabled: Boolean = false,
    val listenBrainzToken: String = "",
    val listenBrainzUsername: String = "",
    val lastLovedSyncAtMs: Long = 0L,
    /** Blocked artist names (lowercase) excluded from scrobbling (e.g., rain/sleep sounds). */
    val blockedArtists: Set<String> = emptySet(),
)

@Singleton
class ScrobblerPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("lw_scrobbler_enabled")
        val SUBMIT_NOW_PLAYING = booleanPreferencesKey("lw_scrobbler_now_playing")
        val PERCENT = intPreferencesKey("lw_scrobbler_percent")
        val PACKAGES = stringSetPreferencesKey("lw_scrobbler_packages")
        val BLOCKED_PACKAGES = stringSetPreferencesKey("lw_scrobbler_blocked_packages")
        val AUTO_KNOWN_PLAYERS = booleanPreferencesKey("lw_scrobbler_auto_known_players")
        val TWO_WAY_LOVE_SYNC = booleanPreferencesKey("lw_scrobbler_two_way_love")
        val PUSH_TO_LASTFM = booleanPreferencesKey("lw_scrobbler_push_lastfm")
        val AVOID_EXTERNAL_DUPES = booleanPreferencesKey("lw_scrobbler_avoid_ext_dupes")
        val AUTO_SYNC_HISTORY = booleanPreferencesKey("lw_scrobbler_auto_sync_history")
        val BLOCKED_ARTISTS = stringSetPreferencesKey("lw_scrobbler_blocked_artists")
        val LB_ENABLED = booleanPreferencesKey("lw_scrobbler_lb_enabled")
        val LB_TOKEN = stringPreferencesKey("lw_scrobbler_lb_token")
        val LB_USERNAME = stringPreferencesKey("lw_scrobbler_lb_username")
        val LAST_LOVED_SYNC = longPreferencesKey("lw_scrobbler_last_loved_sync")
    }

    val settings: Flow<ScrobblerSettings> = dataStore.data
        .recoverPreferences("ScrobblerPreferences")
        .map { p ->
            ScrobblerSettings(
                enabled = p.readSafely(Keys.ENABLED) ?: false,
                submitNowPlaying = p.readSafely(Keys.SUBMIT_NOW_PLAYING) ?: true,
                // Old/user-edited backups can contain values outside the Slider's
                // range. Never pass an invalid persisted value into Compose.
                scrobblePercent = (p.readSafely(Keys.PERCENT) ?: 50).coerceIn(25, 90),
                selectedPackages = p.readSafely(Keys.PACKAGES) ?: emptySet(),
                blockedPackages = p.readSafely(Keys.BLOCKED_PACKAGES) ?: emptySet(),
                autoScrobbleKnownPlayers = p.readSafely(Keys.AUTO_KNOWN_PLAYERS) ?: true,
                twoWayLoveSync = p.readSafely(Keys.TWO_WAY_LOVE_SYNC) ?: true,
                pushToLastFm = p.readSafely(Keys.PUSH_TO_LASTFM) ?: true,
                avoidExternalScrobblerDuplicates = p.readSafely(Keys.AVOID_EXTERNAL_DUPES) ?: true,
                autoSyncHistoryEnabled = p.readSafely(Keys.AUTO_SYNC_HISTORY) ?: true,
                listenBrainzEnabled = p.readSafely(Keys.LB_ENABLED) ?: false,
                listenBrainzToken = p.readSafely(Keys.LB_TOKEN) ?: "",
                listenBrainzUsername = p.readSafely(Keys.LB_USERNAME) ?: "",
                lastLovedSyncAtMs = p.readSafely(Keys.LAST_LOVED_SYNC) ?: 0L,
                blockedArtists = p.readSafely(Keys.BLOCKED_ARTISTS) ?: emptySet(),
            )
        }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ENABLED] = enabled }
    }

    suspend fun setSubmitNowPlaying(enabled: Boolean) {
        dataStore.edit { it[Keys.SUBMIT_NOW_PLAYING] = enabled }
    }

    suspend fun setScrobblePercent(percent: Int) {
        dataStore.edit { it[Keys.PERCENT] = percent.coerceIn(25, 90) }
    }

    suspend fun setSelectedPackages(packages: Set<String>) {
        dataStore.edit { it[Keys.PACKAGES] = packages }
    }

    suspend fun togglePackage(packageName: String) {
        dataStore.edit { prefs ->
            val current = prefs.readSafely(Keys.PACKAGES) ?: emptySet()
            prefs[Keys.PACKAGES] = if (packageName in current) current - packageName else current + packageName
        }
    }

    suspend fun setPackageAllowed(packageName: String, allowed: Boolean) {
        dataStore.edit { prefs ->
            val selected = prefs.readSafely(Keys.PACKAGES) ?: emptySet()
            val blocked = prefs.readSafely(Keys.BLOCKED_PACKAGES) ?: emptySet()
            if (allowed) {
                prefs[Keys.PACKAGES] = selected + packageName
                prefs[Keys.BLOCKED_PACKAGES] = blocked - packageName
            } else {
                prefs[Keys.PACKAGES] = selected - packageName
                prefs[Keys.BLOCKED_PACKAGES] = blocked + packageName
            }
        }
    }

    suspend fun setAutoScrobbleKnownPlayers(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_KNOWN_PLAYERS] = enabled }
    }

    suspend fun setTwoWayLoveSync(enabled: Boolean) {
        dataStore.edit { it[Keys.TWO_WAY_LOVE_SYNC] = enabled }
    }

    suspend fun setListenBrainzConfig(enabled: Boolean, token: String, username: String = "") {
        dataStore.edit {
            it[Keys.LB_ENABLED] = enabled
            it[Keys.LB_TOKEN] = token.trim()
            it[Keys.LB_USERNAME] = username.trim()
        }
    }

    suspend fun setListenBrainzEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.LB_ENABLED] = enabled }
    }

    suspend fun setLastLovedSyncAt(timestampMs: Long) {
        dataStore.edit { it[Keys.LAST_LOVED_SYNC] = timestampMs }
    }

    suspend fun setPushToLastFm(enabled: Boolean) {
        dataStore.edit { it[Keys.PUSH_TO_LASTFM] = enabled }
    }

    suspend fun setAvoidExternalScrobblerDuplicates(enabled: Boolean) {
        dataStore.edit { it[Keys.AVOID_EXTERNAL_DUPES] = enabled }
    }

    suspend fun setAutoSyncHistoryEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_SYNC_HISTORY] = enabled }
    }

    suspend fun addBlockedArtist(artist: String) {
        val norm = artist.trim().lowercase()
        if (norm.isBlank()) return
        dataStore.edit { prefs ->
            val current = prefs.readSafely(Keys.BLOCKED_ARTISTS) ?: emptySet()
            prefs[Keys.BLOCKED_ARTISTS] = current + norm
        }
    }

    suspend fun removeBlockedArtist(artist: String) {
        val norm = artist.trim().lowercase()
        dataStore.edit { prefs ->
            val current = prefs.readSafely(Keys.BLOCKED_ARTISTS) ?: emptySet()
            prefs[Keys.BLOCKED_ARTISTS] = current - norm
        }
    }
}
