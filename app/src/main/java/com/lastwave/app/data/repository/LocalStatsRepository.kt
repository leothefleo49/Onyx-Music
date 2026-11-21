package com.lastwave.app.data.repository

import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.scrobble.ScrobbleEventDao
import com.lastwave.app.data.scrobble.ScrobbleEventEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local Stats Mode for the Stats tab (HomeScreen).
 *
 * When Last.fm is NOT connected (or temporarily offline), the Stats page draws
 * first from real timestamped plays in [OnyxScrobbleDatabase] (both Onyx Music
 * and external apps via the built-in Pano-style scrobbler), falling back to
 * saved playlists and downloaded tracks when no scrobble events exist yet.
 */
@Singleton
class LocalStatsRepository @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val downloadedTrackDao: DownloadedTrackDao,
    private val scrobbleEventDao: ScrobbleEventDao,
) {
    data class LocalStats(
        val stats: HomeStats,
        val topTracks: List<HomeTrack>,
        val topArtists: List<HomeArtistItem>,
        val topAlbums: List<HomeAlbum>,
        val recentTracks: List<HomeTrack> = emptyList(),
        val topTracks7Days: List<HomeTrack> = emptyList(),
        val topTracks30Days: List<HomeTrack> = emptyList(),
    )

    suspend fun load(): LocalStats {
        val scrobbleEvents = runCatching { scrobbleEventDao.getAll() }.getOrDefault(emptyList())
        if (scrobbleEvents.isNotEmpty()) {
            return buildFromScrobbleEvents(scrobbleEvents)
        }
        return buildFromLibraryFallback()
    }

    private fun buildFromScrobbleEvents(events: List<ScrobbleEventEntity>): LocalStats {
        val valid = events.filter {
            it.title.isNotBlank() && it.artist.isNotBlank() &&
                !com.lastwave.app.util.ArtistHelper.isPlayCountOrStat(it.artist)
        }
        if (valid.isEmpty()) return buildEmpty()

        val now = System.currentTimeMillis()
        val cutoff7 = now - 7L * 86_400_000L
        val cutoff30 = now - 30L * 86_400_000L

        val byTrack = valid.groupBy { it.trackKey }
        val byArtist = valid.groupBy { it.artist.trim().lowercase() }
        val byAlbum = valid.filter { it.album.isNotBlank() }
            .groupBy { "${it.artist.trim().lowercase()}|${it.album.trim().lowercase()}" }

        val topTracks = aggregateTopTracks(valid, limit = 50)
        val topTracks7 = aggregateTopTracks(valid.filter { it.scrobbledAtMs >= cutoff7 }, limit = 50)
        val topTracks30 = aggregateTopTracks(valid.filter { it.scrobbledAtMs >= cutoff30 }, limit = 50)

        val countByKey = byTrack.mapValues { it.value.size }
        val recentTracks = valid
            .sortedByDescending { it.scrobbledAtMs }
            .take(150)
            .map { ev ->
                HomeTrack(
                    name = ev.title,
                    artist = ev.artist,
                    artworkUrl = ev.artworkUrl,
                    timestampMillis = ev.scrobbledAtMs,
                    playCount = countByKey[ev.trackKey] ?: 1,
                )
            }

        val topArtists = byArtist.entries
            .sortedByDescending { it.value.size }
            .take(30)
            .map { (_, list) ->
                val sample = list.first()
                HomeArtistItem(
                    name = sample.artist.trim(),
                    playCount = list.size.toLong(),
                    artworkUrl = list.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl,
                )
            }

        val topAlbums = byAlbum.entries
            .sortedByDescending { it.value.size }
            .take(20)
            .map { (_, list) ->
                val sample = list.first()
                HomeAlbum(
                    name = sample.album.trim(),
                    artist = sample.artist.trim(),
                    artworkUrl = list.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl,
                    playCount = list.size.toLong(),
                )
            }

        return LocalStats(
            stats = HomeStats(
                scrobbles = valid.size.toLong(),
                trackCount = byTrack.size.toLong(),
                artistCount = byArtist.size.toLong(),
                albumCount = byAlbum.size.toLong(),
                avatarUrl = null,
            ),
            topTracks = topTracks,
            topArtists = topArtists,
            topAlbums = topAlbums,
            recentTracks = recentTracks,
            topTracks7Days = topTracks7,
            topTracks30Days = topTracks30,
        )
    }

    private fun aggregateTopTracks(events: List<ScrobbleEventEntity>, limit: Int): List<HomeTrack> {
        return events.groupBy { it.trackKey }.entries
            .sortedByDescending { it.value.size }
            .take(limit)
            .map { (_, list) ->
                val latest = list.maxByOrNull { it.scrobbledAtMs } ?: list.first()
                HomeTrack(
                    name = latest.title,
                    artist = latest.artist,
                    artworkUrl = list.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl,
                    timestampMillis = latest.scrobbledAtMs,
                    playCount = list.size,
                )
            }
    }

    private suspend fun buildFromLibraryFallback(): LocalStats {
        val playlists = runCatching { playlistRepository.getAll() }.getOrDefault(emptyList())
        val liked = runCatching { playlistRepository.getLikedSongs()?.tracks.orEmpty() }.getOrDefault(emptyList())
        val downloaded = runCatching { downloadedTrackDao.getAllList() }.getOrDefault(emptyList())

        val allTracks = (playlists.flatMap { it.tracks } + liked)
            .filter { it.name.isNotBlank() && it.artist.isNotBlank() && !com.lastwave.app.util.ArtistHelper.isPlayCountOrStat(it.artist) }

        val playCounts = allTracks.groupingBy { it.key }.eachCount()
        val totalPlays = playCounts.values.sum().toLong()
        val distinctTracks = playCounts.size.toLong()

        val artistPlays = allTracks.groupingBy { it.artist.trim().lowercase() }.eachCount()
        val artistDisplay = allTracks.groupBy { it.artist.trim().lowercase() }
            .mapValues { (_, tracks) -> tracks.firstOrNull()?.artist?.trim().orEmpty() }
        val distinctArtists = artistPlays.size.toLong()

        val albumKeys = allTracks.mapNotNull { it.album?.trim()?.takeIf(String::isNotBlank)?.lowercase() }
            .toSet().size.toLong()
        val downloadAlbums = downloaded.mapNotNull { it.album.trim().takeIf(String::isNotBlank)?.lowercase() }
            .toSet().size.toLong()
        val distinctAlbums = maxOf(albumKeys, downloadAlbums)
        val scrobbles = totalPlays + downloaded.size.toLong()

        val topTracks = playCounts.entries
            .sortedByDescending { it.value }
            .take(50)
            .mapNotNull { (key, count) ->
                val track = allTracks.firstOrNull { it.key == key } ?: return@mapNotNull null
                HomeTrack(
                    name = track.name,
                    artist = track.artist,
                    artworkUrl = track.artworkUrl,
                    timestampMillis = null,
                    playCount = count,
                )
            }

        val topArtists = artistPlays.entries
            .sortedByDescending { it.value }
            .take(30)
            .map { (key, count) ->
                val display = artistDisplay[key].orEmpty().ifBlank { key }
                val artwork = allTracks.firstOrNull {
                    it.artist.trim().lowercase() == key && !it.artworkUrl.isNullOrBlank()
                }?.artworkUrl
                HomeArtistItem(name = display, playCount = count.toLong(), artworkUrl = artwork)
            }

        data class AlbumSeed(val artist: String, val title: String, val artworkUrl: String?)
        val albumSeeds = allTracks.mapNotNull { t ->
            val album = t.album?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            AlbumSeed(t.artist.trim(), album, t.artworkUrl)
        } + downloaded.mapNotNull { d ->
            val album = d.album.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val cleanArtist = d.artist.trim().takeIf { !com.lastwave.app.util.ArtistHelper.isPlayCountOrStat(it) && it.isNotBlank() } ?: "Unknown artist"
            AlbumSeed(cleanArtist, album, d.artworkUrl)
        }
        val albumGroups = albumSeeds.groupBy { "${it.artist.lowercase()}|${it.title.lowercase()}" }
        val topAlbums = albumGroups.entries
            .sortedByDescending { it.value.size }
            .take(10)
            .map { (_, seeds) ->
                val first = seeds.first()
                HomeAlbum(
                    name = first.title,
                    artist = first.artist,
                    artworkUrl = seeds.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl,
                    playCount = seeds.size.toLong(),
                )
            }

        return LocalStats(
            stats = HomeStats(
                scrobbles = scrobbles,
                trackCount = distinctTracks,
                artistCount = distinctArtists,
                albumCount = distinctAlbums,
                avatarUrl = null,
            ),
            topTracks = topTracks,
            topArtists = topArtists,
            topAlbums = topAlbums,
        )
    }

    private fun buildEmpty(): LocalStats = LocalStats(
        stats = HomeStats(0L, 0L, 0L, 0L, null),
        topTracks = emptyList(),
        topArtists = emptyList(),
        topAlbums = emptyList(),
    )
}
