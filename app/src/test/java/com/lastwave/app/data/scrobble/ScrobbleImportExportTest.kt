package com.lastwave.app.data.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrobbleImportExportTest {

    @Test
    fun `Onyx JSON export and import round-trips fields cleanly`() {
        val original = listOf(
            ScrobbleEventEntity(
                trackKey = normalizeScrobbleTrackKey("Radiohead", "Weird Fishes/Arpeggi"),
                title = "Weird Fishes/Arpeggi",
                artist = "Radiohead",
                album = "In Rainbows",
                genre = "Alternative Rock",
                artworkUrl = "https://example.com/in-rainbows.jpg",
                durationMs = 318_000L,
                listenedMs = 318_000L,
                startedAtMs = 1_700_000_000_000L,
                scrobbledAtMs = 1_700_000_318_000L,
                sourcePackage = "com.lastwave.app",
                sourceAppName = "Onyx",
                isLoved = true,
            ),
        )

        val json = ScrobbleImportExport.exportToJson(original)
        val imported = ScrobbleImportExport.importFromText(json)

        assertEquals("Onyx JSON", imported.formatDetected)
        assertEquals(0, imported.skippedCount)
        assertEquals(1, imported.events.size)
        val item = imported.events.first()
        assertEquals("Weird Fishes/Arpeggi", item.title)
        assertEquals("Radiohead", item.artist)
        assertEquals("In Rainbows", item.album)
        assertEquals(318_000L, item.durationMs)
        assertEquals(1_700_000_318_000L, item.scrobbledAtMs)
        assertTrue(item.isLoved)
    }

    @Test
    fun `CSV export and import handles quoted commas and quotes`() {
        val original = listOf(
            ScrobbleEventEntity(
                trackKey = normalizeScrobbleTrackKey("Earth, Wind & Fire", "Hello, \"World\""),
                title = "Hello, \"World\"",
                artist = "Earth, Wind & Fire",
                album = "Greatest Hits",
                durationMs = 215_000L,
                listenedMs = 200_000L,
                startedAtMs = 1_700_100_000_000L,
                scrobbledAtMs = 1_700_100_200_000L,
                sourcePackage = "com.spotify.music",
                sourceAppName = "Spotify",
                isLoved = false,
            ),
        )

        val csv = ScrobbleImportExport.exportToCsv(original)
        val imported = ScrobbleImportExport.importFromText(csv)

        assertEquals("CSV", imported.formatDetected)
        assertEquals(1, imported.events.size)
        val item = imported.events.first()
        assertEquals("Hello, \"World\"", item.title)
        assertEquals("Earth, Wind & Fire", item.artist)
        assertEquals("Greatest Hits", item.album)
        assertEquals("Spotify", item.sourceAppName)
        assertEquals(1_700_100_200_000L, item.scrobbledAtMs)
    }

    @Test
    fun `imports Spotify Extended Streaming History and filters short skips and podcasts`() {
        val spotifyExtendedJson = """
            [
              {
                "ts": "2025-10-12T18:30:00Z",
                "ms_played": 214000,
                "master_metadata_track_name": "Instant Crush",
                "master_metadata_album_artist_name": "Daft Punk",
                "master_metadata_album_album_name": "Random Access Memories"
              },
              {
                "ts": "2025-10-12T18:35:00Z",
                "ms_played": 12000,
                "master_metadata_track_name": "Skipped Track",
                "master_metadata_album_artist_name": "Daft Punk",
                "master_metadata_album_album_name": "Random Access Memories"
              },
              {
                "ts": "2025-10-12T19:00:00Z",
                "ms_played": 1800000,
                "master_metadata_track_name": null,
                "master_metadata_album_artist_name": null,
                "episode_name": "Some Podcast Episode"
              }
            ]
        """.trimIndent()

        val result = ScrobbleImportExport.importFromText(spotifyExtendedJson)
        assertEquals("Spotify Extended History", result.formatDetected)
        assertEquals(1, result.events.size)
        assertEquals(2, result.skippedCount)
        val track = result.events.first()
        assertEquals("Instant Crush", track.title)
        assertEquals("Daft Punk", track.artist)
        assertEquals("Random Access Memories", track.album)
        assertEquals(214_000L, track.listenedMs)
        assertEquals("Spotify", track.sourceAppName)
    }

    @Test
    fun `imports Spotify Account Data Streaming History`() {
        val spotifyAccountJson = """
            [
              {
                "endTime": "2025-11-01 21:15",
                "artistName": "Tame Impala",
                "trackName": "Let It Happen",
                "msPlayed": 466000
              }
            ]
        """.trimIndent()

        val result = ScrobbleImportExport.importFromText(spotifyAccountJson)
        assertEquals("Spotify Streaming History", result.formatDetected)
        assertEquals(1, result.events.size)
        val track = result.events.first()
        assertEquals("Let It Happen", track.title)
        assertEquals("Tame Impala", track.artist)
        assertEquals(466_000L, track.listenedMs)
    }

    @Test
    fun `imports Lastfm JSON envelope and skips nowplaying entry`() {
        val lastFmJson = """
            {
              "recenttracks": {
                "track": [
                  {
                    "name": "Currently Playing",
                    "artist": { "#text": "Bonobo" },
                    "album": { "#text": "Migration" },
                    "@attr": { "nowplaying": "true" }
                  },
                  {
                    "name": "Kerala",
                    "artist": { "#text": "Bonobo" },
                    "album": { "#text": "Migration" },
                    "date": { "uts": "1730000000", "#text": "27 Oct 2024, 03:33" }
                  }
                ]
              }
            }
        """.trimIndent()

        val result = ScrobbleImportExport.importFromText(lastFmJson)
        assertEquals("Last.fm JSON", result.formatDetected)
        assertEquals(1, result.events.size)
        assertEquals(1, result.skippedCount)
        val track = result.events.first()
        assertEquals("Kerala", track.title)
        assertEquals("Bonobo", track.artist)
        assertEquals("Migration", track.album)
        assertEquals(1_730_000_000_000L, track.scrobbledAtMs)
        assertEquals(ScrobbleSyncStatus.SYNCED, track.lastFmSyncStatus)
    }

    @Test
    fun `full Spotify ZIP skips Technical Log Information error logs and avoids Account overlap`() {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            fun addEntry(path: String, body: String) {
                zos.putNextEntry(java.util.zip.ZipEntry(path))
                zos.write(body.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            addEntry(
                "Spotify Technical Log Information/PlaybackError.json",
                """[{"name":"Error 500","artist":"PlaybackEngine","timestamp":1730000000}]""",
            )
            addEntry(
                "Spotify Technical Log Information/RawCoreStream.json",
                """[{"name":"raw_chunk","artist":"cdn","timestamp":1730000005}]""",
            )
            addEntry(
                "Spotify Extended Streaming History/Streaming_History_Audio_2025.json",
                """[{"ts":"2025-11-01T21:15:00Z","ms_played":240000,"master_metadata_track_name":"Let It Happen","master_metadata_album_artist_name":"Tame Impala","master_metadata_album_album_name":"Currents"}]""",
            )
            addEntry(
                "Spotify Account Data/StreamingHistory_music_0.json",
                """[{"endTime":"2025-11-01 21:15","artistName":"Tame Impala","trackName":"Let It Happen","msPlayed":240000}]""",
            )
        }
        val result = ScrobbleImportExport.importFromZipBytes(baos.toByteArray())
        assertEquals(1, result.events.size)
        assertEquals("Let It Happen", result.events.first().title)
        assertEquals("Tame Impala", result.events.first().artist)
        assertEquals("Currents", result.events.first().album)
    }

    @Test
    fun `full Google Takeout ZIP handles ALL YouTube plus YT Music and skips non-YouTube and Ads`() {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            fun addEntry(path: String, body: String) {
                zos.putNextEntry(java.util.zip.ZipEntry(path))
                zos.write(body.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            addEntry(
                "Takeout/Chrome/BrowserHistory.json",
                """[{"name":"Random Page","artist":"Chrome","timestamp":1730000000}]""",
            )
            addEntry(
                "Takeout/YouTube and YouTube Music/playlists/Liked videos.csv",
                "Video Id,Time Added\nabcdef12345,2025-01-01T00:00:00Z",
            )
            addEntry(
                "Takeout/YouTube and YouTube Music/history/watch-history.json",
                """[
                  {
                    "header": "YouTube Music",
                    "title": "Watched Midnight City - Radio Edit",
                    "titleUrl": "https://music.youtube.com/watch?v=dX3k_QDnzHE",
                    "subtitles": [{"name": "M83 - Topic"}],
                    "time": "2025-11-02T10:00:00.123456Z"
                  },
                  {
                    "header": "YouTube",
                    "title": "Watched Sponsored Car Ad",
                    "titleUrl": "https://www.youtube.com/watch?v=aaaaaaaaaaa",
                    "subtitles": [{"name": "Ad Brand Official"}],
                    "details": [{"name": "From Google Ads"}],
                    "time": "2025-11-02T10:05:00Z"
                  },
                  {
                    "header": "YouTube",
                    "title": "Watched Best Smartphones 2026 - Full Review",
                    "titleUrl": "https://www.youtube.com/watch?v=bbbbbbbbbbb",
                    "subtitles": [{"name": "Tech Reviewer Official"}],
                    "time": "2025-11-02T10:10:00Z"
                  }
                ]""",
            )
        }
        val result = ScrobbleImportExport.importFromZipBytes(baos.toByteArray())
        assertEquals(2, result.events.size)
        val ytMusicSong = result.events.first { it.sourceAppName == "YouTube Music" }
        assertEquals("M83", ytMusicSong.artist)
        assertTrue(ytMusicSong.title.startsWith("Midnight City"))
        assertEquals(ScrobbleMediaCategory.MUSIC, ytMusicSong.mediaCategory)

        val ytVideo = result.events.first { it.sourceAppName == "YouTube" }
        assertEquals(ScrobbleMediaCategory.VIDEO, ytVideo.mediaCategory)
    }
}
