package com.lastwave.app.data.scrobble

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

data class ScrobbleMetadata(
    val title: String,
    val artist: String,
    val album: String = "",
)

data class StreakSummary(
    val currentStreakDays: Int,
    val longestStreakDays: Int,
    val activeDaysTotal: Int,
    val activeDaysLast7: Int,
)

/**
 * Pure, unit-testable engine for Pano-style scrobble threshold calculation,
 * metadata auto-edit rules (exact and regex), duplicate suppression, and
 * calendar-day listening streak calculations.
 */
object ScrobbleRulesEngine {
    const val MIN_TRACK_DURATION_MS = 30_000L
    const val MAX_THRESHOLD_MS = 4 * 60_000L
    const val DEFAULT_UNKNOWN_DURATION_MS = 180_000L
    const val MIN_SCROBBLE_PERCENT = 25
    const val MAX_SCROBBLE_PERCENT = 90
    const val DEFAULT_SCROBBLE_PERCENT = 50
    /** Same-track events closer than this are treated as one play. Kept
     *  short so genuinely repeated short songs still count. */
    const val DEFAULT_DEDUP_WINDOW_MS = 45_000L

    /** Well-known external music players for the Pano-style app filter list. */
    val KNOWN_MUSIC_PLAYERS: LinkedHashMap<String, String> = linkedMapOf(
        "com.spotify.music" to "Spotify",
        "com.apple.android.music" to "Apple Music",
        "com.google.android.apps.youtube.music" to "YouTube Music",
        "com.soundcloud.android" to "SoundCloud",
        "com.aspiro.tidal" to "TIDAL",
        "com.qobuz.music" to "Qobuz",
        "tv.plex.labs.plexamp" to "Plexamp",
        "com.maxmpz.audioplayer" to "Poweramp",
        "com.amazon.mp3" to "Amazon Music",
        "deezer.android.app" to "Deezer",
        "com.bandcamp.android" to "Bandcamp",
        "org.videolan.vlc" to "VLC",
        "in.krosbits.musicolet" to "Musicolet",
        "com.neutroncode.mp" to "Neutron Player",
        "com.hiby.music" to "HiBy Music",
        "com.foobar2000.foobar2000" to "foobar2000",
        "app.symfonik.music.player" to "Symfonium",
        "com.pandora.android" to "Pandora",
        "com.google.android.youtube" to "YouTube",
    )

    private val TOPIC_SUFFIX_REGEX = Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE)
    private val VEVO_SUFFIX_REGEX = Regex("VEVO$", RegexOption.IGNORE_CASE)
    private val NOISE_TITLE_REGEX = Regex(
        """\s*[\(\[]\s*(?:official\s+(?:music\s+)?video|official\s+audio|lyrics?\s+video|audio|hd|hq|4k)\s*[\)\]]\s*$""",
        RegexOption.IGNORE_CASE,
    )

    fun cleanMetadata(raw: ScrobbleMetadata): ScrobbleMetadata {
        val cleanArtist = raw.artist.trim()
            .replace(TOPIC_SUFFIX_REGEX, "")
            .replace(VEVO_SUFFIX_REGEX, "")
            .trim()
            .ifBlank { raw.artist.trim() }
        val cleanTitle = raw.title.trim()
            .replace(NOISE_TITLE_REGEX, "")
            .trim()
            .ifBlank { raw.title.trim() }
        return ScrobbleMetadata(
            title = cleanTitle,
            artist = cleanArtist,
            album = raw.album.trim(),
        )
    }

    /**
     * Pano / Last.fm scrobble threshold:
     * - Tracks with a known duration < 30s are never eligible (returns [Long.MAX_VALUE]).
     * - Unknown duration (<= 0) falls back to [DEFAULT_UNKNOWN_DURATION_MS] (3 min).
     * - Threshold is min(duration * percent / 100, 4 minutes), clamped to >= 30s.
     */
    fun calculateThresholdMs(durationMs: Long, scrobblePercent: Int): Long {
        if (durationMs in 1L until MIN_TRACK_DURATION_MS) return Long.MAX_VALUE
        val effectiveDuration = if (durationMs > 0L) durationMs else DEFAULT_UNKNOWN_DURATION_MS
        val clampedPercent = scrobblePercent.coerceIn(MIN_SCROBBLE_PERCENT, MAX_SCROBBLE_PERCENT)
        val byPercent = (effectiveDuration * clampedPercent) / 100L
        return minOf(byPercent, MAX_THRESHOLD_MS).coerceAtLeast(MIN_TRACK_DURATION_MS)
    }

    fun isEligibleForScrobble(durationMs: Long, listenedMs: Long, scrobblePercent: Int): Boolean {
        val threshold = calculateThresholdMs(durationMs, scrobblePercent)
        return threshold != Long.MAX_VALUE && listenedMs >= threshold
    }

    /**
     * Applies enabled Pano-style edit rules sequentially.
     * Rules with all-blank match fields are skipped so a malformed rule never
     * blindly overwrites every track. Invalid regexes are safely ignored.
     */
    fun applyRules(
        metadata: ScrobbleMetadata,
        rules: List<ScrobbleEditRuleEntity>,
    ): ScrobbleMetadata {
        var current = metadata
        for (rule in rules) {
            if (!rule.enabled) continue
            val hasArtistPattern = rule.matchArtist.isNotBlank()
            val hasTitlePattern = rule.matchTitle.isNotBlank()
            val hasAlbumPattern = rule.matchAlbum.isNotBlank()
            if (!hasArtistPattern && !hasTitlePattern && !hasAlbumPattern) continue

            if (rule.isRegex) {
                val artistRegex = if (hasArtistPattern) compileRegexSafely(rule.matchArtist) ?: continue else null
                val titleRegex = if (hasTitlePattern) compileRegexSafely(rule.matchTitle) ?: continue else null
                val albumRegex = if (hasAlbumPattern) compileRegexSafely(rule.matchAlbum) ?: continue else null

                val artistMatched = artistRegex == null || artistRegex.containsMatchIn(current.artist)
                val titleMatched = titleRegex == null || titleRegex.containsMatchIn(current.title)
                val albumMatched = albumRegex == null || albumRegex.containsMatchIn(current.album)
                if (!artistMatched || !titleMatched || !albumMatched) continue

                val newArtist = applyRegexReplacement(current.artist, artistRegex, rule.replaceArtist)
                val newTitle = applyRegexReplacement(current.title, titleRegex, rule.replaceTitle)
                val newAlbum = applyRegexReplacement(current.album, albumRegex, rule.replaceAlbum)
                current = ScrobbleMetadata(
                    title = newTitle.trim().ifBlank { current.title },
                    artist = newArtist.trim().ifBlank { current.artist },
                    album = newAlbum.trim(),
                )
            } else {
                val artistMatched = !hasArtistPattern ||
                    current.artist.trim().equals(rule.matchArtist.trim(), ignoreCase = true)
                val titleMatched = !hasTitlePattern ||
                    current.title.trim().equals(rule.matchTitle.trim(), ignoreCase = true)
                val albumMatched = !hasAlbumPattern ||
                    current.album.trim().equals(rule.matchAlbum.trim(), ignoreCase = true)
                if (!artistMatched || !titleMatched || !albumMatched) continue

                current = ScrobbleMetadata(
                    title = if (rule.replaceTitle.isNotBlank()) rule.replaceTitle.trim() else current.title,
                    artist = if (rule.replaceArtist.isNotBlank()) rule.replaceArtist.trim() else current.artist,
                    album = if (rule.replaceAlbum.isNotBlank()) rule.replaceAlbum.trim() else current.album,
                )
            }
        }
        return current
    }

    private fun compileRegexSafely(pattern: String): Regex? =
        runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()

    private fun applyRegexReplacement(value: String, regex: Regex?, replacement: String): String {
        if (replacement.isEmpty() && regex == null) return value
        return if (regex != null) {
            if (replacement.isEmpty()) value else runCatching { regex.replace(value, replacement) }.getOrDefault(value)
        } else {
            replacement
        }
    }

    fun isDuplicateScrobble(
        existingTrackKey: String,
        existingTimestampMs: Long,
        candidateTrackKey: String,
        candidateTimestampMs: Long,
        windowMs: Long = DEFAULT_DEDUP_WINDOW_MS,
    ): Boolean {
        if (!existingTrackKey.equals(candidateTrackKey, ignoreCase = true)) return false
        return abs(candidateTimestampMs - existingTimestampMs) <= windowMs
    }

    /**
     * Computes consecutive daily listening streaks across calendar days in [timeZone].
     * Current streak stays alive if the user listened today OR yesterday.
     */
    fun computeStreaks(
        timestampsMs: List<Long>,
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): StreakSummary {
        val valid = timestampsMs.filter { it > 0L }
        if (valid.isEmpty()) {
            return StreakSummary(0, 0, 0, 0)
        }
        val dayIndices = valid.map { epochDayOf(it, timeZone) }.distinct().sorted()
        val todayDay = epochDayOf(nowMs, timeZone)

        var longest = 1
        var run = 1
        for (i in 1 until dayIndices.size) {
            if (dayIndices[i] == dayIndices[i - 1] + 1L) {
                run++
                if (run > longest) longest = run
            } else {
                run = 1
            }
        }

        val daySet = dayIndices.toHashSet()
        var current = 0
        var cursor = when {
            todayDay in daySet -> todayDay
            (todayDay - 1L) in daySet -> todayDay - 1L
            else -> Long.MIN_VALUE
        }
        while (cursor != Long.MIN_VALUE && cursor in daySet) {
            current++
            cursor--
        }

        val last7Start = todayDay - 6L
        val activeLast7 = dayIndices.count { it in last7Start..todayDay }

        return StreakSummary(
            currentStreakDays = current,
            longestStreakDays = longest,
            activeDaysTotal = dayIndices.size,
            activeDaysLast7 = activeLast7,
        )
    }

    /** Returns a monotonically increasing local calendar day index safe across all time zones and DST boundaries. */
    internal fun epochDayOf(timestampMs: Long, timeZone: TimeZone): Long {
        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = timestampMs
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val localEpochMs = cal.timeInMillis + timeZone.getOffset(cal.timeInMillis)
        return Math.floorDiv(localEpochMs, 86_400_000L)
    }
}
