package com.lastwave.app.data.scrobble

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Pure Kotlin serializer & importer for Onyx scrobble history.
 *
 * Supported export formats:
 *  - Onyx JSON (`onyx-scrobbles-v1`)
 *  - RFC-4180 CSV
 *
 * Supported import formats (auto-detected):
 *  - Onyx JSON (`onyx-scrobbles-v1`)
 *  - Spotify Extended Streaming History JSON (`ts`, `ms_played`, `master_metadata_track_name`, ...)
 *  - Spotify Account Data Streaming History JSON (`endTime`, `artistName`, `trackName`, `msPlayed`)
 *  - Last.fm JSON export (either `recenttracks.track` envelope or flat array of track objects)
 *  - CSV (Onyx export header or generic `artist,title,album,timestamp`)
 */
object ScrobbleImportExport {
    private val prettyJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val rawJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    data class ImportResult(
        val events: List<ScrobbleEventEntity>,
        val formatDetected: String,
        val skippedCount: Int,
    )

    fun exportToJson(events: List<ScrobbleEventEntity>): String {
        val root = buildJsonObject {
            put("format", "onyx-scrobbles-v1")
            put("exportedAtMs", System.currentTimeMillis())
            put("count", events.size)
            put(
                "events",
                buildJsonArray {
                    events.forEach { e ->
                        add(
                            buildJsonObject {
                                put("title", e.title)
                                put("artist", e.artist)
                                put("album", e.album)
                                put("genre", e.genre)
                                if (!e.artworkUrl.isNullOrBlank()) put("artworkUrl", e.artworkUrl)
                                put("durationMs", e.durationMs)
                                put("listenedMs", e.listenedMs)
                                put("startedAtMs", e.startedAtMs)
                                put("scrobbledAtMs", e.scrobbledAtMs)
                                put("sourcePackage", e.sourcePackage)
                                put("sourceAppName", e.sourceAppName)
                                put("isLoved", e.isLoved)
                            },
                        )
                    }
                },
            )
        }
        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }

    fun exportToCsv(events: List<ScrobbleEventEntity>): String {
        val sb = StringBuilder()
        sb.append("scrobbledAtMs,startedAtMs,artist,title,album,genre,durationMs,listenedMs,sourceAppName,sourcePackage,isLoved\n")
        events.forEach { e ->
            sb.append(e.scrobbledAtMs).append(',')
                .append(e.startedAtMs).append(',')
                .append(csvEscape(e.artist)).append(',')
                .append(csvEscape(e.title)).append(',')
                .append(csvEscape(e.album)).append(',')
                .append(csvEscape(e.genre)).append(',')
                .append(e.durationMs).append(',')
                .append(e.listenedMs).append(',')
                .append(csvEscape(e.sourceAppName)).append(',')
                .append(csvEscape(e.sourcePackage)).append(',')
                .append(e.isLoved).append('\n')
        }
        return sb.toString()
    }

    fun importFromText(raw: String): ImportResult {
        val trimmed = raw.trim().removePrefix("\uFEFF")
        if (trimmed.isBlank()) {
            return ImportResult(emptyList(), "Empty", 0)
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val parsed = runCatching { rawJson.parseToJsonElement(trimmed) }.getOrNull()
            if (parsed != null) {
                return parseJsonElement(parsed)
            }
        }
        return parseCsv(trimmed)
    }

    private fun parseJsonElement(element: JsonElement): ImportResult {
        return when (element) {
            is JsonObject -> {
                when {
                    element["format"]?.jsonPrimitive?.contentOrNull == "onyx-scrobbles-v1" &&
                        element["events"] is JsonArray -> {
                        parseOnyxEvents(element["events"]!!.jsonArray)
                    }
                    element["recenttracks"] is JsonObject -> {
                        val tracks = element["recenttracks"]!!.jsonObject["track"]
                        if (tracks is JsonArray) {
                            parseLastFmArray(tracks)
                        } else {
                            ImportResult(emptyList(), "Last.fm JSON", 0)
                        }
                    }
                    element["events"] is JsonArray -> parseOnyxEvents(element["events"]!!.jsonArray)
                    element["tracks"] is JsonArray -> parseLastFmArray(element["tracks"]!!.jsonArray)
                    else -> ImportResult(emptyList(), "Unknown JSON", 0)
                }
            }
            is JsonArray -> {
                val sample = element.firstOrNull() as? JsonObject
                    ?: return ImportResult(emptyList(), "Empty JSON Array", 0)
                when {
                    sample.containsKey("master_metadata_track_name") || sample.containsKey("ms_played") ->
                        parseSpotifyExtendedHistory(element)
                    sample.containsKey("endTime") && sample.containsKey("trackName") ->
                        parseSpotifyAccountHistory(element)
                    sample.containsKey("scrobbledAtMs") ->
                        parseOnyxEvents(element)
                    else -> parseLastFmArray(element)
                }
            }
            else -> ImportResult(emptyList(), "Unsupported", 0)
        }
    }

    private fun parseOnyxEvents(array: JsonArray): ImportResult {
        val results = mutableListOf<ScrobbleEventEntity>()
        var skipped = 0
        for (item in array) {
            val obj = item as? JsonObject ?: run { skipped++; continue }
            val title = obj.str("title").trim()
            val artist = obj.str("artist").trim()
            if (title.isBlank() || artist.isBlank()) {
                skipped++
                continue
            }
            val scrobbledAt = obj.longVal("scrobbledAtMs")
                ?: obj.longVal("startedAtMs")
                ?: System.currentTimeMillis()
            val startedAt = obj.longVal("startedAtMs") ?: scrobbledAt
            val durationMs = obj.longVal("durationMs") ?: 210_000L
            val listenedMs = obj.longVal("listenedMs") ?: durationMs
            results += ScrobbleEventEntity(
                trackKey = normalizeScrobbleTrackKey(artist, title),
                title = title,
                artist = artist,
                album = obj.str("album").trim(),
                genre = obj.str("genre").trim(),
                artworkUrl = obj.str("artworkUrl").ifBlank { null },
                durationMs = durationMs,
                listenedMs = listenedMs,
                startedAtMs = startedAt,
                scrobbledAtMs = scrobbledAt,
                sourcePackage = obj.str("sourcePackage").ifBlank { "import.onyx" },
                sourceAppName = obj.str("sourceAppName").ifBlank { "Onyx Import" },
                isLoved = obj["isLoved"]?.jsonPrimitive?.booleanOrNull == true,
                lastFmSyncStatus = ScrobbleSyncStatus.SYNCED,
                listenBrainzSyncStatus = ScrobbleSyncStatus.SKIPPED,
            )
        }
        return ImportResult(results, "Onyx JSON", skipped)
    }

    private fun parseSpotifyExtendedHistory(array: JsonArray): ImportResult {
        val results = mutableListOf<ScrobbleEventEntity>()
        var skipped = 0
        for (item in array) {
            val obj = item as? JsonObject ?: run { skipped++; continue }
            val title = obj.str("master_metadata_track_name").trim()
            val artist = obj.str("master_metadata_album_artist_name").trim()
            val msPlayed = obj.longVal("ms_played") ?: 0L
            if (title.isBlank() || artist.isBlank() || msPlayed < ScrobbleRulesEngine.MIN_TRACK_DURATION_MS) {
                skipped++
                continue
            }
            val tsMs = parseIsoTimestampMs(obj.str("ts")) ?: run { skipped++; continue }
            val album = obj.str("master_metadata_album_album_name").trim()
            results += ScrobbleEventEntity(
                trackKey = normalizeScrobbleTrackKey(artist, title),
                title = title,
                artist = artist,
                album = album,
                durationMs = msPlayed,
                listenedMs = msPlayed,
                startedAtMs = (tsMs - msPlayed).coerceAtLeast(1L),
                scrobbledAtMs = tsMs,
                sourcePackage = "com.spotify.music",
                sourceAppName = "Spotify",
                lastFmSyncStatus = ScrobbleSyncStatus.SYNCED,
                listenBrainzSyncStatus = ScrobbleSyncStatus.SKIPPED,
            )
        }
        return ImportResult(results, "Spotify Extended History", skipped)
    }

    private fun parseSpotifyAccountHistory(array: JsonArray): ImportResult {
        val results = mutableListOf<ScrobbleEventEntity>()
        var skipped = 0
        for (item in array) {
            val obj = item as? JsonObject ?: run { skipped++; continue }
            val title = obj.str("trackName").trim()
            val artist = obj.str("artistName").trim()
            val msPlayed = obj.longVal("msPlayed") ?: 0L
            if (title.isBlank() || artist.isBlank() || msPlayed < ScrobbleRulesEngine.MIN_TRACK_DURATION_MS) {
                skipped++
                continue
            }
            val tsMs = parseSpotifyShortTimestampMs(obj.str("endTime")) ?: run { skipped++; continue }
            results += ScrobbleEventEntity(
                trackKey = normalizeScrobbleTrackKey(artist, title),
                title = title,
                artist = artist,
                durationMs = msPlayed,
                listenedMs = msPlayed,
                startedAtMs = (tsMs - msPlayed).coerceAtLeast(1L),
                scrobbledAtMs = tsMs,
                sourcePackage = "com.spotify.music",
                sourceAppName = "Spotify",
                lastFmSyncStatus = ScrobbleSyncStatus.SYNCED,
                listenBrainzSyncStatus = ScrobbleSyncStatus.SKIPPED,
            )
        }
        return ImportResult(results, "Spotify Streaming History", skipped)
    }

    private fun parseLastFmArray(array: JsonArray): ImportResult {
        val results = mutableListOf<ScrobbleEventEntity>()
        var skipped = 0
        for (item in array) {
            val obj = item as? JsonObject ?: run { skipped++; continue }
            val title = obj.str("name").ifBlank { obj.str("track") }.trim()
            val artist = extractTextField(obj["artist"]).trim()
            val album = extractTextField(obj["album"]).trim()
            if (title.isBlank() || artist.isBlank()) {
                skipped++
                continue
            }
            val uts = extractTimestampMs(obj) ?: run { skipped++; continue }
            val artwork = (obj["image"] as? JsonArray)
                ?.lastOrNull()
                ?.let { (it as? JsonObject)?.str("#text") }
                ?.ifBlank { null }
            val loved = obj["loved"]?.jsonPrimitive?.contentOrNull == "1" ||
                obj["isLoved"]?.jsonPrimitive?.booleanOrNull == true
            results += ScrobbleEventEntity(
                trackKey = normalizeScrobbleTrackKey(artist, title),
                title = title,
                artist = artist,
                album = album,
                artworkUrl = artwork,
                durationMs = 210_000L,
                listenedMs = 210_000L,
                startedAtMs = uts,
                scrobbledAtMs = uts,
                sourcePackage = "fm.last.android",
                sourceAppName = "Last.fm Import",
                isLoved = loved,
                lastFmSyncStatus = ScrobbleSyncStatus.SYNCED,
                listenBrainzSyncStatus = ScrobbleSyncStatus.SKIPPED,
            )
        }
        return ImportResult(results, "Last.fm JSON", skipped)
    }

    private fun parseCsv(raw: String): ImportResult {
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < 2) return ImportResult(emptyList(), "CSV", 0)
        val header = parseCsvLine(lines.first()).map { it.trim().lowercase() }
        val idxArtist = header.indexOfFirst { it == "artist" }
        val idxTitle = header.indexOfFirst { it == "title" || it == "track" || it == "name" }
        if (idxArtist < 0 || idxTitle < 0) return ImportResult(emptyList(), "CSV", lines.size)

        val idxAlbum = header.indexOfFirst { it == "album" }
        val idxGenre = header.indexOfFirst { it == "genre" }
        val idxScrobbled = header.indexOfFirst { it == "scrobbledatms" || it == "timestamp" || it == "uts" }
        val idxStarted = header.indexOfFirst { it == "startedatms" }
        val idxDuration = header.indexOfFirst { it == "durationms" }
        val idxListened = header.indexOfFirst { it == "listenedms" }
        val idxSourceApp = header.indexOfFirst { it == "sourceappname" || it == "source" }
        val idxSourcePkg = header.indexOfFirst { it == "sourcepackage" }
        val idxLoved = header.indexOfFirst { it == "isloved" || it == "loved" }

        val events = mutableListOf<ScrobbleEventEntity>()
        var skipped = 0
        for (i in 1 until lines.size) {
            val cols = parseCsvLine(lines[i])
            val artist = cols.getOrNull(idxArtist)?.trim().orEmpty()
            val title = cols.getOrNull(idxTitle)?.trim().orEmpty()
            if (artist.isBlank() || title.isBlank()) {
                skipped++
                continue
            }
            val rawTs = cols.getOrNull(idxScrobbled)?.trim().orEmpty()
            val tsMs = parseFlexibleTimestamp(rawTs) ?: System.currentTimeMillis()
            val startedMs = cols.getOrNull(idxStarted)?.trim()?.toLongOrNull() ?: tsMs
            val durationMs = cols.getOrNull(idxDuration)?.trim()?.toLongOrNull() ?: 210_000L
            val listenedMs = cols.getOrNull(idxListened)?.trim()?.toLongOrNull() ?: durationMs
            val lovedStr = cols.getOrNull(idxLoved)?.trim().orEmpty()
            events += ScrobbleEventEntity(
                trackKey = normalizeScrobbleTrackKey(artist, title),
                title = title,
                artist = artist,
                album = cols.getOrNull(idxAlbum)?.trim().orEmpty(),
                genre = cols.getOrNull(idxGenre)?.trim().orEmpty(),
                durationMs = durationMs,
                listenedMs = listenedMs,
                startedAtMs = startedMs,
                scrobbledAtMs = tsMs,
                sourcePackage = cols.getOrNull(idxSourcePkg)?.trim().orEmpty().ifBlank { "import.csv" },
                sourceAppName = cols.getOrNull(idxSourceApp)?.trim().orEmpty().ifBlank { "CSV Import" },
                isLoved = lovedStr.equals("true", ignoreCase = true) || lovedStr == "1",
                lastFmSyncStatus = ScrobbleSyncStatus.SYNCED,
                listenBrainzSyncStatus = ScrobbleSyncStatus.SKIPPED,
            )
        }
        return ImportResult(events, "CSV", skipped)
    }

    private fun extractTextField(element: JsonElement?): String = when (element) {
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        is JsonObject -> element["#text"]?.jsonPrimitive?.contentOrNull
            ?: element["name"]?.jsonPrimitive?.contentOrNull
            ?: ""
        else -> ""
    }

    private fun extractTimestampMs(obj: JsonObject): Long? {
        val dateObj = obj["date"] as? JsonObject
        val utsFromDate = dateObj?.get("uts")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        if (utsFromDate != null) return normalizeEpochToMs(utsFromDate)

        val directUts = obj.longVal("uts") ?: obj.longVal("timestamp") ?: obj.longVal("scrobbledAtMs")
        if (directUts != null) return normalizeEpochToMs(directUts)

        val iso = obj.str("ts").ifBlank { obj.str("date") }
        if (iso.isNotBlank()) return parseIsoTimestampMs(iso)
        return null
    }

    private fun parseFlexibleTimestamp(raw: String): Long? {
        if (raw.isBlank()) return null
        raw.toLongOrNull()?.let { return normalizeEpochToMs(it) }
        return parseIsoTimestampMs(raw) ?: parseSpotifyShortTimestampMs(raw)
    }

    private fun normalizeEpochToMs(value: Long): Long =
        if (value in 1L..9_999_999_999L) value * 1000L else value

    internal fun parseIsoTimestampMs(raw: String): Long? {
        val clean = raw.trim()
        if (clean.isBlank()) return null
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd HH:mm:ss",
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(clean)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun parseSpotifyShortTimestampMs(raw: String): Long? {
        val clean = raw.trim()
        if (clean.isBlank()) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(clean)?.time
        }.getOrNull()
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.longVal(key: String): Long? {
        val prim = this[key] as? JsonPrimitive ?: return null
        return prim.longOrNull ?: prim.contentOrNull?.toLongOrNull()
    }

    private fun csvEscape(value: String): String {
        val needsQuoting = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuoting) "\"$escaped\"" else escaped
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQuotes && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    result += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        result += current.toString()
        return result
    }
}
