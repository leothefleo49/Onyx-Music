package com.lastwave.app.data.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ScrobbleRulesEngineTest {

    // ── Threshold calculation & eligibility ──────────────────────────────

    @Test
    fun `tracks shorter than 30s are never eligible`() {
        assertEquals(Long.MAX_VALUE, ScrobbleRulesEngine.calculateThresholdMs(29_999L, 50))
        assertEquals(Long.MAX_VALUE, ScrobbleRulesEngine.calculateThresholdMs(10_000L, 25))
        assertFalse(ScrobbleRulesEngine.isEligibleForScrobble(25_000L, 25_000L, 50))
    }

    @Test
    fun `threshold scales linearly between 25 and 90 percent with 30s floor and 4m cap`() {
        // 3-minute track at 50% -> 90s
        assertEquals(90_000L, ScrobbleRulesEngine.calculateThresholdMs(180_000L, 50))
        // 3-minute track at 75% -> 135s
        assertEquals(135_000L, ScrobbleRulesEngine.calculateThresholdMs(180_000L, 75))
        // 40s track at 25% would be 10s, floored to 30s
        assertEquals(30_000L, ScrobbleRulesEngine.calculateThresholdMs(40_000L, 25))
        // 12-minute track at 50% would be 6m, capped at 4m (240s)
        assertEquals(240_000L, ScrobbleRulesEngine.calculateThresholdMs(720_000L, 50))
        // Out-of-bounds percentages clamp to [25, 90]
        assertEquals(45_000L, ScrobbleRulesEngine.calculateThresholdMs(180_000L, 5))
        assertEquals(162_000L, ScrobbleRulesEngine.calculateThresholdMs(180_000L, 100))
    }

    @Test
    fun `unknown duration falls back to 3 minutes`() {
        assertEquals(90_000L, ScrobbleRulesEngine.calculateThresholdMs(0L, 50))
        assertEquals(90_000L, ScrobbleRulesEngine.calculateThresholdMs(-1L, 50))
        assertTrue(ScrobbleRulesEngine.isEligibleForScrobble(0L, 90_000L, 50))
        assertFalse(ScrobbleRulesEngine.isEligibleForScrobble(0L, 89_999L, 50))
    }

    // ── Metadata cleanup ────────────────────────────────────────────────

    @Test
    fun `cleanMetadata strips YouTube Topic, VEVO, and official video suffixes`() {
        val cleaned = ScrobbleRulesEngine.cleanMetadata(
            ScrobbleMetadata(
                title = "Midnight City (Official Music Video)",
                artist = "M83 - Topic",
                album = "  Hurry Up, We're Dreaming  ",
            ),
        )
        assertEquals("Midnight City", cleaned.title)
        assertEquals("M83", cleaned.artist)
        assertEquals("Hurry Up, We're Dreaming", cleaned.album)

        val vevo = ScrobbleRulesEngine.cleanMetadata(
            ScrobbleMetadata(title = "Starboy [Official Audio]", artist = "TheWeekndVEVO"),
        )
        assertEquals("Starboy", vevo.title)
        assertEquals("TheWeeknd", vevo.artist)
    }

    // ── Pano exact & regex edit rules ───────────────────────────────────

    @Test
    fun `applyRules applies exact case-insensitive replacements sequentially`() {
        val rules = listOf(
            ScrobbleEditRuleEntity(
                id = 1,
                matchArtist = "daft punk",
                matchTitle = "one more time (radio edit)",
                replaceTitle = "One More Time",
                replaceAlbum = "Discovery",
                isRegex = false,
                enabled = true,
            ),
            ScrobbleEditRuleEntity(
                id = 2,
                matchArtist = "Daft Punk",
                replaceArtist = "Daft Punk (FR)",
                isRegex = false,
                enabled = false, // disabled rule must be skipped
            ),
        )

        val result = ScrobbleRulesEngine.applyRules(
            ScrobbleMetadata(title = "One More Time (Radio Edit)", artist = "Daft Punk", album = ""),
            rules,
        )
        assertEquals("One More Time", result.title)
        assertEquals("Daft Punk", result.artist)
        assertEquals("Discovery", result.album)
    }

    @Test
    fun `applyRules supports regex capture groups and safely skips invalid regex or blank patterns`() {
        val rules = listOf(
            ScrobbleEditRuleEntity(
                id = 1,
                matchTitle = "[invalid(regex",
                replaceTitle = "ShouldNotCrash",
                isRegex = true,
                enabled = true,
            ),
            ScrobbleEditRuleEntity(
                id = 2,
                matchArtist = "",
                matchTitle = "",
                matchAlbum = "",
                replaceArtist = "NeverOverwriteEverything",
                isRegex = false,
                enabled = true,
            ),
            ScrobbleEditRuleEntity(
                id = 3,
                matchTitle = """^(.+?)\s*-\s*\d{4}\s+Remaster$""",
                replaceTitle = "$1",
                isRegex = true,
                enabled = true,
            ),
        )

        val result = ScrobbleRulesEngine.applyRules(
            ScrobbleMetadata(
                title = "Time - 2011 Remaster",
                artist = "Pink Floyd",
                album = "The Dark Side of the Moon",
            ),
            rules,
        )
        assertEquals("Time", result.title)
        assertEquals("Pink Floyd", result.artist)
        assertEquals("The Dark Side of the Moon", result.album)
    }

    // ── Deduplication ───────────────────────────────────────────────────

    @Test
    fun `isDuplicateScrobble detects same track within 120s window and allows beyond`() {
        val keyA = normalizeScrobbleTrackKey("Radiohead", "Nude")
        val keyB = normalizeScrobbleTrackKey("  RADIOHEAD ", "nude")
        val otherKey = normalizeScrobbleTrackKey("Radiohead", "Reckoner")

        assertTrue(ScrobbleRulesEngine.isDuplicateScrobble(keyA, 1_000_000L, keyB, 1_120_000L))
        assertTrue(ScrobbleRulesEngine.isDuplicateScrobble(keyA, 1_000_000L, keyB, 900_000L))
        assertFalse(ScrobbleRulesEngine.isDuplicateScrobble(keyA, 1_000_000L, keyB, 1_120_001L))
        assertFalse(ScrobbleRulesEngine.isDuplicateScrobble(keyA, 1_000_000L, otherKey, 1_010_000L))
    }

    // ── Listening streaks ───────────────────────────────────────────────

    @Test
    fun `computeStreaks tracks current, longest, and active days across calendar days`() {
        val utc = TimeZone.getTimeZone("UTC")
        fun dayMs(year: Int, month: Int, day: Int, hour: Int = 14): Long =
            Calendar.getInstance(utc).apply {
                set(year, month - 1, day, hour, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

        val now = dayMs(2026, 11, 21, 18)
        val timestamps = listOf(
            // Older 4-day streak (Nov 10..13)
            dayMs(2026, 11, 10, 9),
            dayMs(2026, 11, 11, 10),
            dayMs(2026, 11, 12, 11),
            dayMs(2026, 11, 13, 12),
            // Gap on Nov 14..18
            // Current 3-day streak ending today (Nov 19, 20, 21), with duplicate plays on Nov 21
            dayMs(2026, 11, 19, 8),
            dayMs(2026, 11, 20, 22),
            dayMs(2026, 11, 21, 7),
            dayMs(2026, 11, 21, 17),
        )

        val summary = ScrobbleRulesEngine.computeStreaks(timestamps, nowMs = now, timeZone = utc)
        assertEquals(3, summary.currentStreakDays)
        assertEquals(4, summary.longestStreakDays)
        assertEquals(7, summary.activeDaysTotal)
        assertEquals(3, summary.activeDaysLast7)
    }

    @Test
    fun `computeStreaks keeps streak alive through yesterday and resets after a missed day`() {
        val utc = TimeZone.getTimeZone("UTC")
        fun dayMs(day: Int): Long =
            Calendar.getInstance(utc).apply {
                set(2026, Calendar.NOVEMBER, day, 15, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

        val now = dayMs(21)
        val yesterdayActive = ScrobbleRulesEngine.computeStreaks(
            listOf(dayMs(19), dayMs(20)),
            nowMs = now,
            timeZone = utc,
        )
        assertEquals(2, yesterdayActive.currentStreakDays)

        val broken = ScrobbleRulesEngine.computeStreaks(
            listOf(dayMs(18), dayMs(19)),
            nowMs = now,
            timeZone = utc,
        )
        assertEquals(0, broken.currentStreakDays)
        assertEquals(2, broken.longestStreakDays)

        val empty = ScrobbleRulesEngine.computeStreaks(emptyList(), nowMs = now, timeZone = utc)
        assertEquals(StreakSummary(0, 0, 0, 0), empty)
    }

    @Test
    fun `epochDayOf remains strictly monotonic across DST transitions in Pacific Auckland`() {
        val nz = TimeZone.getTimeZone("Pacific/Auckland")
        // NZ DST spring-forward occurred on Sept 27, 2026 (UTC+12 -> UTC+13)
        val sep26 = Calendar.getInstance(nz).apply { set(2026, Calendar.SEPTEMBER, 26, 12, 0, 0) }.timeInMillis
        val sep27 = Calendar.getInstance(nz).apply { set(2026, Calendar.SEPTEMBER, 27, 12, 0, 0) }.timeInMillis
        val sep28 = Calendar.getInstance(nz).apply { set(2026, Calendar.SEPTEMBER, 28, 12, 0, 0) }.timeInMillis

        val d26 = ScrobbleRulesEngine.epochDayOf(sep26, nz)
        val d27 = ScrobbleRulesEngine.epochDayOf(sep27, nz)
        val d28 = ScrobbleRulesEngine.epochDayOf(sep28, nz)
        assertEquals(d26 + 1L, d27)
        assertEquals(d27 + 1L, d28)
    }
}
