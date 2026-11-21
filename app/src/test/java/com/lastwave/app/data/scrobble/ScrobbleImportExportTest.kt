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
}
