package com.lastwave.app.data.spotify

import android.content.Context
import android.util.Log
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.playlist.SpotifyPlaylistImporter
import com.lastwave.app.data.playlist.SpotifyUserPlaylist
import com.lastwave.app.data.spotify.mirror.SpotifyTrackNormalizer
import com.lastwave.app.util.AppVisibility
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SpotifyLiveSync"
private const val PREFS_NAME = "onyx_spotify_liked_sync_v1"
private const val KEY_ENTRIES = "liked_entries_json"
private const val KEY_LAST_SYNC = "last_sync_ms"
private const val FOREGROUND_POLL_MS = 60_000L

@Serializable
private data class CachedSpotifyLike(
    val spotifyId: String? = null,
    val title: String,
    val artist: String,
)

/**
 * Live Spotify library & Liked Songs sync manager.
 *
 * Keeps Spotify Liked Songs markers in its own persisted store (separate from
 * Room `Liked Songs` so [com.lastwave.app.data.ytmusic.YtLikedSongsSync] never
 * deletes Spotify likes during YouTube Music reconciliation). Every track liked
 * on Spotify automatically appears hearted across the entire Onyx app, and
 * playlists stay live via a 60-second foreground heartbeat + instant refresh on
 * connect / resume / pull-to-refresh.
 */
@Singleton
class SpotifyLikedSongsSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val spotifyPreferences: SpotifyPreferences,
    private val spotifyPlaylistImporter: SpotifyPlaylistImporter,
    private val applicationScope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val syncMutex = Mutex()
    @Volatile private var started = false
    @Volatile private var isConnectedCached = false

    private val _likedTrackMarkers = MutableStateFlow<Set<String>>(emptySet())
    val likedTrackMarkers: StateFlow<Set<String>> = _likedTrackMarkers.asStateFlow()

    private val _likedCount = MutableStateFlow(0)
    val likedCount: StateFlow<Int> = _likedCount.asStateFlow()

    private val _livePlaylists = MutableStateFlow<List<SpotifyUserPlaylist>>(emptyList())
    val livePlaylists: StateFlow<List<SpotifyUserPlaylist>> = _livePlaylists.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSyncedAtMs = MutableStateFlow(0L)
    val lastSyncedAtMs: StateFlow<Long> = _lastSyncedAtMs.asStateFlow()

    private val entriesLock = Any()
    private var cachedEntries: MutableList<CachedSpotifyLike> = mutableListOf()
    private var spotifyIdByNormKey: MutableMap<String, String> = mutableMapOf()

    @Synchronized
    fun start() {
        if (started) return
        started = true
        loadPersistedCache()

        // Sync immediately when Spotify connects (and clear on disconnect)
        applicationScope.launch(Dispatchers.IO) {
            spotifyPreferences.isConnected.distinctUntilChanged().collect { connected ->
                isConnectedCached = connected
                if (connected) {
                    syncNow(force = true)
                } else {
                    clearAll()
                }
            }
        }

        // Foreground live sync heartbeat (every 60s while app is visible)
        applicationScope.launch(Dispatchers.IO) {
            var wasVisible = AppVisibility.isVisible
            while (true) {
                delay(15_000L)
                val visibleNow = AppVisibility.isVisible
                val becameVisible = visibleNow && !wasVisible
                wasVisible = visibleNow
                if (!visibleNow) continue
                val elapsed = System.currentTimeMillis() - _lastSyncedAtMs.value
                if (becameVisible && elapsed > 20_000L) {
                    runCatching { syncNow(force = true) }
                } else if (elapsed >= FOREGROUND_POLL_MS) {
                    runCatching { syncNow(force = false) }
                }
            }
        }
    }

    /** Forces a fresh sync of Spotify Liked Songs and user playlists. */
    suspend fun syncNow(force: Boolean = true) {
        if (!spotifyPreferences.isConnected.first()) return
        syncMutex.withLock {
            _isSyncing.value = true
            try {
                if (force) spotifyPlaylistImporter.invalidateCache()

                // 1. Sync Spotify Liked Songs -> heart markers
                runCatching { spotifyPlaylistImporter.fetchLikedSongsWithIds(forceRefresh = force) }
                    .onSuccess { entries ->
                        if (entries.isNotEmpty()) {
                            val persisted = entries.map {
                                CachedSpotifyLike(spotifyId = it.spotifyId, title = it.title, artist = it.artist)
                            }
                            synchronized(entriesLock) {
                                cachedEntries = persisted.toMutableList()
                            }
                            rebuildMarkersAndPersist()
                            Log.i(TAG, "Live-synced ${entries.size} Spotify Liked Songs")
                        }
                    }
                    .onFailure { err ->
                        if (err is CancellationException) throw err
                        Log.w(TAG, "Spotify Liked Songs live sync skipped: ${err.message}")
                    }

                // 2. Sync Spotify User Playlists -> live Library list
                runCatching { spotifyPlaylistImporter.fetchUserPlaylists(forceRefresh = force) }
                    .onSuccess { playlists ->
                        if (playlists.isNotEmpty() || _livePlaylists.value.isEmpty()) {
                            _livePlaylists.value = playlists
                        }
                    }
                    .onFailure { err ->
                        if (err is CancellationException) throw err
                        Log.w(TAG, "Spotify playlists live sync skipped: ${err.message}")
                    }

                val now = System.currentTimeMillis()
                _lastSyncedAtMs.value = now
                prefs.edit().putLong(KEY_LAST_SYNC, now).apply()
            } finally {
                _isSyncing.value = false
            }
        }
    }

    /**
     * Called when the user hearts or unhearts a track in Onyx.
     * Updates the local Spotify liked cache immediately and pushes two-way
     * PUT/DELETE to Spotify when a Spotify track ID is known.
     */
    fun onLocalLikeChanged(title: String, artist: String, liked: Boolean) {
        if (!isConnectedCached) return
        val normKey = SpotifyTrackNormalizer.canonicalTrackKey(title, artist)
        if (normKey.isBlank()) return

        var knownSpotifyId: String? = null
        synchronized(entriesLock) {
            knownSpotifyId = spotifyIdByNormKey[normKey]
            if (liked) {
                if (cachedEntries.none { SpotifyTrackNormalizer.canonicalTrackKey(it.title, it.artist) == normKey }) {
                    cachedEntries.add(0, CachedSpotifyLike(spotifyId = knownSpotifyId, title = title, artist = artist))
                }
            } else {
                cachedEntries.removeAll { SpotifyTrackNormalizer.canonicalTrackKey(it.title, it.artist) == normKey }
            }
        }
        rebuildMarkersAndPersist()

        val idToPush = knownSpotifyId
        if (!idToPush.isNullOrBlank()) {
            applicationScope.launch(Dispatchers.IO) {
                runCatching { spotifyPlaylistImporter.setSpotifyTrackLiked(idToPush, liked) }
            }
        }
    }

    private fun loadPersistedCache() {
        runCatching {
            val raw = prefs.getString(KEY_ENTRIES, null).orEmpty()
            _lastSyncedAtMs.value = prefs.getLong(KEY_LAST_SYNC, 0L)
            if (raw.isNotBlank()) {
                val decoded = json.decodeFromString<List<CachedSpotifyLike>>(raw)
                synchronized(entriesLock) {
                    cachedEntries = decoded.toMutableList()
                }
                rebuildMarkers()
            }
        }
    }

    private fun rebuildMarkersAndPersist() {
        rebuildMarkers()
        runCatching {
            val snapshot = synchronized(entriesLock) { cachedEntries.toList() }
            prefs.edit().putString(KEY_ENTRIES, json.encodeToString(snapshot)).apply()
        }
    }

    private fun rebuildMarkers() {
        val snapshot = synchronized(entriesLock) { cachedEntries.toList() }
        val markers = HashSet<String>(snapshot.size * 2)
        val idMap = HashMap<String, String>(snapshot.size)
        for (entry in snapshot) {
            val rawKey = GeneratedTrack(name = entry.title, artist = entry.artist).key
            if (rawKey.isNotBlank()) markers += rawKey
            val norm = SpotifyTrackNormalizer.canonicalTrackKey(entry.title, entry.artist)
            if (norm.isNotBlank()) {
                markers += "norm:$norm"
                if (!entry.spotifyId.isNullOrBlank()) {
                    idMap[norm] = entry.spotifyId
                }
            }
        }
        synchronized(entriesLock) {
            spotifyIdByNormKey = idMap
        }
        _likedTrackMarkers.value = markers
        _likedCount.value = snapshot.size
    }

    private fun clearAll() {
        synchronized(entriesLock) {
            cachedEntries.clear()
            spotifyIdByNormKey.clear()
        }
        _likedTrackMarkers.value = emptySet()
        _likedCount.value = 0
        _livePlaylists.value = emptyList()
        _lastSyncedAtMs.value = 0L
        prefs.edit().clear().apply()
    }
}
