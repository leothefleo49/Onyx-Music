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
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream

/**
 * Pure Kotlin serializer & universal multi-platform importer for Onyx scrobble history.
 *
 * Supported export formats:
 *  - Onyx JSON (`onyx-scrobbles-v1`)
 *  - RFC-4180 CSV
 *
 * Supported import formats (auto-detected, including `.zip` archives):
 *  - Onyx JSON (`onyx-scrobbles-v1`)
 *  - Spotify Extended Streaming History JSON / ZIP (`ts`, `ms_played`, `master_metadata_track_name`, podcasts)
 *  - Spotify Account Data Streaming History JSON / ZIP (`endTime`, `artistName`, `trackName`, `msPlayed`)
 *  - YouTube & YouTube Music Google Takeout (`watch-history.json`, `music-history.json`, ZIP)
 *  - SoundCloud listening history JSON / CSV
 *  - stats.fm export JSON / CSV
 *  - Pano Scrobbler backup JSON / JSONL / CSV
 *  - ListenBrainz export JSON / JSONL
 *  - Last.fm JSON & CSV (headered or 4-column headerless `artist,album,track,date`)
 *  - Apple Music Play Activity CSV
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
                                put("mediaCategory", e.mediaCategory)
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
        sb.append("scrobbledAtMs,startedAtMs,artist,title,album,genre,durationMs,listenedMs,sourceAppName,sourcePackage,isLoved,mediaCategory\n")
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
                .append(e.isLoved).append(',')
                .append(csvEscape(e.mediaCategory)).append('\n')
        }
        return sb.toString()
    }

    /**
     * Reads either a raw `.zip` archive (Spotify / Google Takeout / Pano / stats.fm exports)
     * or a single `.json` / `.jsonl` / `.html` / `.csv` stream and auto-detects the source format.
     * Streams ZIP entries directly without loading the entire archive into RAM (prevents OOM on
     * multi-year Google Takeout or Spotify Extended History archives).
     */
    fun importFromStream(input: InputStream, fileNameHint: String? = null): ImportResult {
        val buffered = java.io.BufferedInputStream(input, 64 * 1024)
        buffered.mark(8)
        val header = ByteArray(4)
        val read = buffered.read(header)
        buffered.reset()
        if (read <= 0) return ImportResult(emptyList(), "Empty", 0)
        val isZipHeader = read >= 4 &&
            header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            (header[2] == 0x03.toByte() || header[2] == 0x05.toByte())
        val isZip = isZipHeader || fileNameHint?.endsWith(".zip", ignoreCase = true) == true
        if (isZip) {
            return importFromZipStream(buffered)
        }
        val text = buffered.bufferedReader(Charsets.UTF_8).readText()
        if (fileNameHint?.endsWith(".html", ignoreCase = true) == true ||
            fileNameHint?.endsWith(".htm", ignoreCase = true) == true ||
            text.trimStart().startsWith("<!DOCTYPE html", ignoreCase = true) ||
            text.trimStart().startsWith("<html", ignoreCase = true)
        ) {
            return parseYouTubeTakeoutHtml(text)
        }
        return importFromText(text)
    }

    fun importFromZipBytes(bytes: ByteArray): ImportResult =
        importFromZipStream(ByteArrayInputStream(bytes))

    fun importFromZipStream(stream: InputStream): ImportResult {
        val extendedSpotifyEvents = mutableListOf<ScrobbleEventEntity>()
        val accountSpotifyEvents = mutableListOf<ScrobbleEventEntity>()
        val otherEvents = mutableListOf<ScrobbleEventEntity>()
        val formats = linkedSetOf<String>()
        var totalSkipped = 0
        var hasExtendedSpotify = false

        ZipInputStream(stream).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                val lower = name.lowercase(Locale.ROOT)
                val baseName = lower.substringAfterLast('/')
                val entryKind = classifyZipEntry(entry.isDirectory, lower, baseName)

                if (entryKind != ZipEntryKind.SKIP) {
                    val parsed = when (entryKind) {
                        ZipEntryKind.YOUTUBE_HTML -> parseYouTubeTakeoutHtmlStream(zis)
                        else -> {
                            val entryBytes = readBoundedEntry(zis, MAX_ZIP_ENTRY_BYTES)
                            if (entryBytes != null && entryBytes.isNotEmpty()) {
                                importFromText(entryBytes.toString(Charsets.UTF_8))
                            } else null
                        }
                    }
                    if (parsed != null && parsed.events.isNotEmpty()) {
                        totalSkipped += parsed.skippedCount
                        when (entryKind) {
                            ZipEntryKind.SPOTIFY_EXTENDED -> {
                                hasExtendedSpotify = true
                                extendedSpotifyEvents += parsed.events
                                formats += parsed.formatDetected
                            }
                            ZipEntryKind.SPOTIFY_ACCOUNT -> {
                                accountSpotifyEvents += parsed.events
                                if (!hasExtendedSpotify) formats += parsed.formatDetected
                            }
                            else -> {
                                otherEvents += parsed.events
                                formats += parsed.formatDetected
                            }
                        }
                    }
                }
                zis.closeEntry()
            }
        }

        // Full Spotify exports often include BOTH Extended Streaming History AND Account Data
        // StreamingHistory_music_*.json (which overlaps the last 12 months). Prefer Extended
        // whenever present so the user's past year is never double-imported!
        val allEvents = ArrayList<ScrobbleEventEntity>(
            extendedSpotifyEvents.size + otherEvents.size + if (hasExtendedSpotify) 0 else accountSpotifyEvents.size,
        )
        allEvents += extendedSpotifyEvents
        if (!hasExtendedSpotify) {
            allEvents += accountSpotifyEvents
        } else if (accountSpotifyEvents.isNotEmpty()) {
            // Keep any podcast rows from Account History if Extended had none, drop music overlap
            allEvents += accountSpotifyEvents.filter { it.mediaCategory == ScrobbleMediaCategory.PODCAST }
            formats.remove("Spotify Streaming History")
        }
        allEvents += otherEvents

        val label = when {
            formats.isEmpty() -> "ZIP Archive (no history found)"
            formats.size == 1 -> "${formats.first()} (ZIP)"
            else -> "${formats.take(2).joinToString(" + ")} (ZIP)"
        }
        return ImportResult(allEvents, label, totalSkipped)
    }

    private enum class ZipEntryKind {
        SKIP,
        SPOTIFY_EXTENDED,
        SPOTIFY_ACCOUNT,
        YOUTUBE_JSON,
        YOUTUBE_HTML,
        GENERIC_HISTORY,
    }

    private fun classifyZipEntry(isDirectory: Boolean, lowerPath: String, baseName: String): ZipEntryKind {
        if (isDirectory || lowerPath.contains("__macosx") || baseName.startsWith(".")) {
            return ZipEntryKind.SKIP
        }
        // Always ignore Spotify Technical Log Information / error logs / telemetry / metadata
        if (lowerPath.contains("technical log") ||
            lowerPath.contains("technical_log") ||
            baseName in NON_HISTORY_SPOTIFY_FILES ||
            baseName.contains("error") ||
            baseName.contains("rawcorestream") ||
            baseName.contains("stutter") ||
            baseName.contains("clientevent") ||
            baseName.contains("diagnostic") ||
            baseName.contains("crash") ||
            baseName.startsWith("playlist") ||
            baseName.contains("search-history") ||
            baseName.contains("search_history") ||
            baseName.contains("subscriptions") ||
            baseName.contains("comments") ||
            baseName.contains("live-chat") ||
            baseName.contains("my-comments") ||
            baseName.contains("library-songs")
        ) {
            return ZipEntryKind.SKIP
        }

        // Spotify Extended Streaming History (exact allowlist)
        if ((baseName.startsWith("streaming_history_audio") ||
                baseName.startsWith("streaming_history_video") ||
                baseName.startsWith("endsong")) &&
            baseName.endsWith(".json")
        ) {
            return ZipEntryKind.SPOTIFY_EXTENDED
        }

        // Spotify Account Data Streaming History (exact allowlist)
        if ((baseName.startsWith("streaminghistory_music") ||
                baseName.startsWith("streaminghistory_podcast") ||
                baseName.startsWith("streaminghistory")) &&
            baseName.endsWith(".json")
        ) {
            return ZipEntryKind.SPOTIFY_ACCOUNT
        }

        // If inside a Spotify export folder and NOT one of the streaming history files above, skip it!
        if (lowerPath.contains("spotify") &&
            (lowerPath.contains("my_spotify_data") ||
                lowerPath.contains("spotify account data") ||
                lowerPath.contains("spotify extended streaming history"))
        ) {
            return ZipEntryKind.SKIP
        }

        // Google Takeout full archive: allowlist ONLY YouTube / YouTube Music watch & music history!
        if (lowerPath.startsWith("takeout/") || lowerPath.contains("/takeout/")) {
            val isYouTubeFolder = lowerPath.contains("youtube")
            if (!isYouTubeFolder) return ZipEntryKind.SKIP
            val isHistoryFile = baseName == "watch-history.json" ||
                baseName == "music-history.json" ||
                baseName == "myactivity.json" ||
                baseName == "watch-history.html" ||
                baseName == "music-history.html" ||
                baseName == "myactivity.html" ||
                (lowerPath.contains("/history/") && (baseName.endsWith(".json") || baseName.endsWith(".html")))
            if (!isHistoryFile) return ZipEntryKind.SKIP
            return if (baseName.endsWith(".html") || baseName.endsWith(".htm")) {
                ZipEntryKind.YOUTUBE_HTML
            } else {
                ZipEntryKind.YOUTUBE_JSON
            }
        }

        // Standalone YouTube Takeout HTML/JSON files inside a custom ZIP
        if (baseName == "watch-history.html" || baseName == "music-history.html" || baseName == "myactivity.html") {
            return ZipEntryKind.YOUTUBE_HTML
        }
        if (baseName == "watch-history.json" || baseName == "music-history.json") {
            return ZipEntryKind.YOUTUBE_JSON
        }

        // Generic exports (Onyx, Last.fm, Pano, ListenBrainz, stats.fm, SoundCloud, Apple Music)
        val supportedExt = baseName.endsWith(".json") ||
            baseName.endsWith(".jsonl") ||
            baseName.endsWith(".csv")
        if (supportedExt) return ZipEntryKind.GENERIC_HISTORY
        return ZipEntryKind.SKIP
    }

    private fun readBoundedEntry(input: InputStream, maxBytes: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > maxBytes) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun importFromText(raw: String): ImportResult {
        val trimmed = raw.trim().removePrefix("\uFEFF")
        if (trimmed.isBlank()) {
            return ImportResult(emptyList(), "Empty", 0)
        }
        if (trimmed.startsWith("<!DOCTYPE html", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true)
        ) {
            return parseYouTubeTakeoutHtml(trimmed)
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val parsed = runCatching { rawJson.parseToJsonElement(trimmed) }.getOrNull()
            if (parsed != null) {
                return parseJsonElement(parsed)
            }
            // Support JSONL (newline-delimited JSON used by ListenBrainz & Pano Scrobbler)
            val jsonLines = parseJsonLines(trimmed)
            if (jsonLines != null) return jsonLines
        }
        return parseCsv(trimmed)
    }

    private fun parseJsonLines(trimmed: String): ImportResult? {
        val objs = mutableListOf<JsonObject>()
        var skipped = 0
        for (line in trimmed.lineSequence()) {
            val clean = line.trim()
            if (clean.isEmpty()) continue
            val obj = runCatching { rawJson.parseToJsonElement(clean) as? JsonObject }.getOrNull()
            if (obj != null) objs += obj else skipped++
        }
        if (objs.isEmpty()) return null
        val res = parseJsonElement(JsonArray(objs))
        return res.copy(skippedCount = res.skippedCount + skipped)
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
                    element["payload"] is JsonObject && element["payload"]!!.jsonObject["listens"] is JsonArray -> {
                        parseListenBrainzArray(element["payload"]!!.jsonObject["listens"]!!.jsonArray)
                    }
                    element["listens"] is JsonArray -> parseListenBrainzArray(element["listens"]!!.jsonArray)
                    element["streams"] is JsonArray -> parseStatsFmArray(element["streams"]!!.jsonArray)
                    element["items"] is JsonArray -> parseJsonElement(element["items"]!!.jsonArray)
                    element["scrobbles"] is JsonArray -> parsePanoArray(element["scrobbles"]!!.jsonArray)
                    element["collection"] is JsonArray -> parseSoundCloudArray(element["collection"]!!.jsonArray)
                    element["events"] is JsonArray -> parseOnyxEvents(element["events"]!!.jsonArray)
                    element["tracks"] is JsonArray -> parseLastFmArray(element["tracks"]!!.jsonArray)
                    else -> ImportResult(emptyList(), "Unknown JSON", 0)
                }
            }
            is JsonArray -> {
                val sample = element.firstOrNull { it is JsonObject } as? JsonObject
                    ?: return ImportResult(emptyList(), "Empty JSON Array", 0)
                when {
                    sample.containsKey("master_metadata_track_name") || sample.containsKey("ms_played") ->
                        parseSpotifyExtendedHistory(element)
                    sample.containsKey("endTime") && (sample.containsKey("trackName") || sample.containsKey("episodeName")) ->
                        parseSpotifyAccountHistory(element)
                    sample.containsKey("header") && sample.containsKey("title") && sample.containsKey("time") ->
                        parseYouTubeTakeoutHistory(element)
                    sample.containsKey("track_metadata") && sample.containsKey("listened_at") ->
                        parseListenBrainzArray(element)
                    sample.containsKey("track") && sample["track"] is JsonObject &&
                        (sample.containsKey("played_at") || sample.containsKey("playedAt") || sample.containsKey("created_at") || sample.containsKey("endTime")) -> {
                        val inner = sample["track"]!!.jsonObject
                        if (inner.containsKey("user") || inner.containsKey("permalink_url") || sample.containsKey("created_at")) {
                            parseSoundCloudArray(element)
                        } else {
                            parseStatsFmArray(element)
                        }
                    }
                    sample.containsKey("played_at") || sample.containsKey("permalink_url") ||
                        (sample.containsKey("user") && sample["user"] is JsonObject) ->
                        parseSoundCloudArray(element)
                    sample.containsKey("playedAt") || (sample.containsKey("msPlayed") && sample.containsKey("trackId")) ->
                        parseStatsFmArray(element)
                    sample.containsKey("scrobbledAtMs") ->
                        parseOnyxEvents(element)
                    sample.containsKey("time") && (sample.containsKey("artist") || sample.containsKey("track")) && !sample.containsKey("header") ->
                        parsePanoArray(element)
                    sample.containsKey("artist") &&
                        (sample.containsKey("name") || sample.containsKey("track")) &&
                        (sample.containsKey("date") || sample.containsKey("uts") || sample.containsKey("timestamp")) ->
                        parseLastFmArray(element)
                    else -> ImportResult(emptyList(), "Unsupported JSON Array", 0)
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
            val srcPkg = obj.str("sourcePackage").ifBlank { "import.onyx" }
            val srcApp = obj.str("sourceAppName").ifBlank { "Onyx Import" }
            val rawCat = obj.str("mediaCategory").trim().uppercase(Locale.ROOT)
            val category = if (rawCat in setOf(
                    ScrobbleMediaCategory.MUSIC,
                    ScrobbleMediaCategory.VIDEO,
                    ScrobbleMediaCategory.PODCAST,
                    ScrobbleMediaCategory.OTHER,
                )
            ) {
                rawCat
            } else {
                MediaCategoryClassifier.classify(
                    title = title,
                    artist = artist,
                    album = obj.str("album").trim(),
                    genre = obj.str("genre").trim(),
                    sourcePackage = srcPkg,
                    sourceAppName = srcApp,
                    durationMs = durationMs,
                )
            }
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
                sourcePackage = srcPkg,
                sourceAppName = srcApp,
                isLoved = obj["isLoved"]?.jsonPrimitive?.booleanOrNull == true,
                lastFmSyncStatus = ScrobbleSyncStatus.SYNCED,
                listenBrainzSyncStatus = ScrobbleSyncStatus.SKIPPED,
                mediaCategory = category,
            )
        }
        return ImportResult(results, "Onyx JSON", skipped)
    }

    private fun parseSpotifyExtendedHistory(array: JsonArray): ImportResult {
        val results = mutableListOf<ScrobbleEventEntity>()
        var skipped = 0
        for (item in array) {
            val obj = item as? JsonObject ?: run { skipped++; continue }
            val msPlayed = obj.longVal("ms_played") ?: 0L
            if (msPlayed < ScrobbleRulesEngine.MIN_TRACK_DURATION_MS) {
                skipped++
                continue
            }
            val trackTitle = obj.str("master_metadata_track_name").trim()
            val trackArtist = obj.str("master_metadata_album_artist_name").trim()
            val episodeTitle = obj.str("episode_name").trim()
            val episodeShow = obj.str("episode_show_name").trim()
            val bookTitle = obj.str("audiobook_title").trim()
            val bookChapter = obj.str("audiobook_chapter_title").trim()

            val isPodcast = trackTitle.isBlank() && episodeTitle.isNotBlank() && episodeShow.isNotBlank()
            val isAudiobook = trackTitle.isBlank() && !isPodcast && bookTitle.isNotBlank()
            val title = when {
                isPodcast -> episodeTitle
                isAudiobook -> bookChapter.ifBlank { bookTitle }
                else -> trackTitle
            }
            val artist = when {
                isPodcast -> episodeShow
                isAudiobook -> bookTitle
                else -> trackArtist
            }
            if (title.isBlank() || artist.isBlank()) {
                skipped++
                continue
            }
            val tsMs = parseIsoTimestampMs(obj.str("ts")) ?: run { skipped++; continue }
            val album = when {
                isPodcast -> episodeShow
                isAudiobook -> bookTitle
                else -> obj.str("master_metadata_album_album_name").trim()
            }
            val wasSkipped = obj["skipped"]?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true) == true ||
                obj.str("reason_end").equals("fwdbtn", ignoreCase = true)
            val platform = obj.str("platform").trim().take(48)
            val startedMs = (tsMs - msPlayed).coerceAtLeast(1L)
            results += ScrobbleEventEntity(
                trackKey = normalizeScrobbleTrackKey(artist, title),
                title = title,
                artist = artist,
                album = album,
                durationMs = msPlayed,
                listenedMs = msPlayed,
                startedAtMs = startedMs,
                scrobbledAtMs = startedMs,
                sourcePackage = "com.spotify.music",
                sourceAppName = "Spotify",
                lastFmSyncStatus = ScrobbleSyncStatus.SYNCED,
                listenBrainzSyncStatus = ScrobbleSyncStatus.SKIPPED,
                mediaCategory = when {
                    isPodcast -> ScrobbleMediaCategory.PODCAST
                    isAudiobook -> ScrobbleMediaCategory.OTHER
                    else -> ScrobbleMediaCategory.MUSIC
                },
                outputDevice = platform,
                wasSkipped = wasSkipped,
                completed = !wasSkipped,
            )
        }
        return ImportResult(results, "Spotify Extended History", skipped)
    }

    private fun parseSpotifyAccountHistory(array: JsonArray): ImportResult {
        val results = mutableListOf<ScrobbleEventEntity>()
        var skipped = 0
        for (item in array) {
            val obj = item as? JsonObject ?: run { skipped++; continue }
            val msPlayed = obj.longVal("msPlayed") ?: 0L
            if (msPlayed < ScrobbleRulesEngine.MIN_TRACK_DURATION_MS) {
                skipped++
                continue
            }
            val trackTitle = obj.str("trackName").trim()
            val trackArtist = obj.str("artistName").trim()
            val episodeTitle = obj.str("episodeName").trim()
            val podcastName = obj.str("podcastName").trim()
            val isPodcast = trackTitle.isBlank() && episodeTitle.isNotBlank() && podcastName.isNotBlank()
            val title = if (isPodcast) episodeTitle else trackTitle
            val artist = if (isPodcast) podcastName else trackArtist
            if (title.isBlank() || artist.isBlank()) {
                skipped++
                continue
            }
            val tsMs = parseFlexibleTimestamp(obj.str("endTime")) ?: run { skipped++; continue }
            val startedMs = (tsMs - msPlayed).coerceAtLeast(1L)
            results += ScrobbleEventEntity(
                trackKey = normalizeScrobbleTrackKey(artist, title),
                title = title,
                artist = artist,
                durationMs = msPlayed,
                listenedMs = msPlayed,
                startedAtMs = startedMs,
                scrobbledAtMs = startedMs,
                sourcePackage = "com.spotify.music",
                sourceAppName = "Spotify",
                lastFmSyncStatus = ScrobbleSyncStatus.SYNCED,
                listenBrainzSyncStatus = ScrobbleSyncStatus.SKIPPED,
                mediaCategory = if (isPodcast) ScrobbleMediaCategory.PODCAST else ScrobbleMediaCategory.MUSIC,
            )
        }
        return ImportResult(results, "Spotify Streaming History", skipped)
    }

    private fun parseYouTubeTakeoutHistory(array: JsonArray): ImportResult {
        val results = mutableListOf<ScrobbleEventEntity>()
        var skipped = 0
        for (item in array) {
            val obj = item as? JsonObject ?: run { skipped++; continue }
            val header = obj.str("header").trim()
            val rawTitle = obj.str("title").trim()
            val isAd = (obj["details"] as? JsonArray)?.any { detail ->
                (detail as? JsonObject)?.str("name")?.contains("Google Ads", ignoreCase = true) == true
            } == true
            if (isAd || rawTitle.isBlank() || rawTitle.startsWith("Watched a video that has been removed", ignoreCase = true)) {
                skipped++
                continue
            }
            val strippedTitle = stripTakeoutPrefix(rawTitle)
            if (strippedTitle.isBlank() || strippedTitle.startsWith("https://")) {
                skipped++
                continue
            }
            val subtitleChannel = (obj["subtitles"] as? JsonArray)
                ?.firstOrNull()
                ?.let { (it as? JsonObject)?.str("name")?.trim() }
                .orEmpty()
            val isYtMusic = header.equals("YouTube Music", ignoreCase = true) ||
                obj.str("titleUrl").contains("music.youtube.com", ignoreCase = true)

            val (parsedArtist, parsedTitle) = splitYouTubeTitleAndArtist(
                rawTitle = strippedTitle,
                channelName = subtitleChannel,
                isYouTubeMusic = isYtMusic,
            )
            if (parsedTitle.isBlank() || parsedArtist.isBlank()) {
                skipped++
                continue
            }
            val tsMs = parseIsoTimestampMs(obj.str("time")) ?: run { skipped++; continue }
            val sourcePkg = if (isYtMusic) "com.google.android.apps.youtube.music" else "com.google.android.youtube"
            val sourceApp = if (isYtMusic) "YouTube Music" else "YouTube"
            val category = if (isYtMusic) {
                ScrobbleMediaCategory.MUSIC
            } else {
                MediaCategoryClassifier.classify(
                    title = strippedTitle,
                    artist = subtitleChannel.ifBlank { parsedArtist },
                    sourcePackage = sourcePkg,
                    sourceAppName = sourceApp,
                )
            }
            val cleaned = ScrobbleRulesEngine.cleanMetadata(ScrobbleMetadata(parsedTitle, parsedArtist))
            results += ScrobbleEventEntity(
                trackKey = normalizeScrobbleTrackKey(cleaned.artist, cleaned.title),
                title = cleaned.title,
                artist = cleaned.artist,
                durationMs = 210_000L,
                listenedMs = 210_000L,
                startedAtMs = tsMs,
                scrobbledAtMs = tsMs,
                sourcePackage = sourcePkg,
                sourceAppName = sourceApp,
                lastFmSyncStatus = ScrobbleSyncStatus.SYNCED,
                listenBrainzSyncStatus = ScrobbleSyncStatus.SKIPPED,
                mediaCategory = category,
            )
        }
        return ImportResult(results, "YouTube / YT Music Takeout", skipped)
    }

    private fun splitYouTubeTitleAndArtist(
        rawTitle: String,
        channelName: String,
        isYouTubeMusic: Boolean = false,
    ): Pair<String, String> {
        val isTopicChannel = channelName.trim().endsWith("- Topic", ignoreCase = true)
        val cleanChannel = channelName.replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "").trim()
        // For YouTube Music or auto-generated "- Topic" channels, the channel name IS
        // the true artist and the title IS the true track name — splitting on " - "
        // corrupts song titles like "Song - Radio Edit" or "Part 1 - Intro".
        if ((isYouTubeMusic || isTopicChannel) && cleanChannel.isNotBlank()) {
            return cleanChannel to rawTitle
        }
        val separators = listOf(" - ", " — ", " – ")
        for (sep in separators) {
            val idx = rawTitle.indexOf(sep)
            if (idx in 2 until rawTitle.length - sep.length - 1) {
                val left = rawTitle.substring(0, idx).trim()
                val right = rawTitle.substring(idx + sep.length).trim()
                if (left.length <= 60 && right.isNotBlank()) {
                    return left to right
                }
            }
        }
        return (cleanChannel.ifBlank { "YouTube" }) to rawTitle
    }

    private fun parseSoundCloudArray(array: JsonArray): ImportResult {
        val results = mutableListOf<ScrobbleEventEntity>()
        var skipped = 0
        for (item in array) {
            val obj = item as? JsonObject ?: run { skipped++; continue }
            val trackObj = obj["track"] as? JsonObject ?: obj
            val title = trackObj.str("title").ifBlank { trackObj.str("name") }.trim()
            val userObj = trackObj["user"] as? JsonObject
            val artist = userObj?.str("username")?.ifBlank { userObj.str("full_name") }?.trim()
                ?: extractTextField(trackObj["artist"]).trim()
            if (title.isBlank() || artist.isBlank()) {
                skipped++
                continue
            }
            val tsMs = obj.longVal("played_at")
                ?: parseFlexibleTimestamp(obj.str("played_at"))
                ?: parseFlexibleTimestamp(obj.str("created_at"))
                ?: parseFlexibleTimestamp(trackObj.str("created_at"))
                ?: extractTimestampMs(obj)
                ?: System.currentTimeMillis()
            val durMs = trackObj.longVal("duration") ?: trackObj.longVal("full_duration") ?: 210_000L
            val genre = trackObj.str("genre").trim()
            val artwork = trackObj.str("artwork_url").ifBlank { null }
            val category = MediaCategoryClassifier.classify(
                title = title,
                artist = artist,
                genre = genre,
                sourcePackage = "com.soundcloud.android",
                sourceAppName = "SoundCloud",
                durationMs = durMs,
            )
            results += ScrobbleEventEntity(
                trackKey = normalizeScrobbleTrackKey(artist, title),
                title = title,
                artist = artist,
                genre = genre,
                artworkUrl = artwork,
                durationMs = durMs,
                listenedMs = durMs,
                startedAtMs = tsMs,
                scrobbledAtMs = tsMs,
                sourcePackage = "com.soundcloud.android",
                sourceAppName = "SoundCloud",
                lastFmSyncStatus = ScrobbleSyncStatus.SYNCED,
                listenBrainzSyncStatus = ScrobbleSyncStatus.SKIPPED,
                mediaCategory = category,
            )
        }
        return ImportResult(results, "SoundCloud JSON", skipped)
    }

    private fun parseStatsFmArray(array: JsonArray): ImportResult {
        val results = mutableListOf<ScrobbleEventEntity>()
        var skipped = 0
        for (item in array) {
            val obj = item as? JsonObject ?: run { skipped++; continue }
            val trackObj = obj["track"] as? JsonObject
            val title = trackObj?.str("name")?.trim()
                ?: obj.str("trackName").ifBlank { obj.str("name") }.trim()
            val artistsArr = (trackObj?.get("artists") ?: obj["artists"]) as? JsonArray
            val artist = artistsArr?.firstOrNull()?.let { elem ->
                when (elem) {
                    is JsonObject -> elem.str("name")
                    is JsonPrimitive -> elem.contentOrNull.orEmpty()
                    else -> ""
                }
            }?.trim()?.ifBlank { null }
                ?: obj.str("artistName").ifBlank { extractTextField(obj["artist"]) }.trim()
            if (title.isBlank() || artist.isBlank()) {
                skipped++
                continue
            }
            val album = (trackObj?.get("albums") as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.str("name") }?.trim()
                ?: obj.str("albumName").trim()
            val tsMs = parseFlexibleTimestamp(obj.str("endTime"))
                ?: parseFlexibleTimestamp(obj.str("playedAt"))
                ?: obj.longVal("playedAt")
                ?: extractTimestampMs(obj)
                ?: run { skipped++; continue }
            val msPlayed = obj.longVal("msPlayed") ?: obj.longVal("ms_played") ?: 210_000L
            if (msPlayed in 1L until ScrobbleRulesEngine.MIN_TRACK_DURATION_MS) {
                skipped++
                continue
            }
            results += ScrobbleEventEntity(
                trackKey = normalizeScrobbleTrackKey(artist, title),
                title = title,
                artist = artist,
                album = album,
                durationMs = msPlayed,
                listenedMs = msPlayed,
                startedAtMs = (tsMs - msPlayed).coerceAtLeast(1L),
                scrobbledAtMs = tsMs,
                sourcePackage = "dev.netlob.spotistats",
                sourceAppName = "stats.fm",
                lastFmSyncStatus = ScrobbleSyncStatus.SYNCED,
                listenBrainzSyncStatus = ScrobbleSyncStatus.SKIPPED,
                mediaCategory = ScrobbleMediaCategory.MUSIC,
            )
        }
        return ImportResult(results, "stats.fm JSON", skipped)
    }

    private fun parseListenBrainzArray(array: JsonArray): ImportResult {
        val results = mutableListOf<ScrobbleEventEntity>()
        var skipped = 0
        for (item in array) {
            val obj = item as? JsonObject ?: run { skipped++; continue }
            val meta = obj["track_metadata"] as? JsonObject ?: obj
            val title = meta.str("track_name").ifBlank { meta.str("title") }.trim()
            val artist = meta.str("artist_name").ifBlank { meta.str("artist") }.trim()
            val album = meta.str("release_name").ifBlank { meta.str("album") }.trim()
            if (title.isBlank() || artist.isBlank()) {
                skipped++
                continue
            }
            val listenedAt = obj.longVal("listened_at") ?: extractTimestampMs(obj) ?: run { skipped++; continue }
            val tsMs = normalizeEpochToMs(listenedAt)
            val addInfo = meta["additional_info"] as? JsonObject
            val durMs = addInfo?.longVal("duration_ms")
                ?: addInfo?.longVal("duration")?.times(1000L)
                ?: 210_000L
            results += ScrobbleEventEntity(
                trackKey = normalizeScrobbleTrackKey(artist, title),
                title = title,
                artist = artist,
                album = album,
                durationMs = durMs,
                listenedMs = durMs,
                startedAtMs = tsMs,
                scrobbledAtMs = tsMs,
                sourcePackage = "org.listenbrainz.android",
                sourceAppName = "ListenBrainz",
                lastFmSyncStatus = ScrobbleSyncStatus.SYNCED,
                listenBrainzSyncStatus = ScrobbleSyncStatus.SYNCED,
                mediaCategory = ScrobbleMediaCategory.MUSIC,
            )
        }
        return ImportResult(results, "ListenBrainz JSON", skipped)
    }

    private fun parsePanoArray(array: JsonArray): ImportResult {
        val results = mutableListOf<ScrobbleEventEntity>()
        var skipped = 0
        for (item in array) {
            val obj = item as? JsonObject ?: run { skipped++; continue }
            val title = obj.str("track").ifBlank { obj.str("title").ifBlank { obj.str("name") } }.trim()
            val artist = extractTextField(obj["artist"]).trim()
            val album = extractTextField(obj["album"]).trim()
            if (title.isBlank() || artist.isBlank()) {
                skipped++
                continue
            }
            val tsMs = obj.longVal("time")?.let(::normalizeEpochToMs)
                ?: parseFlexibleTimestamp(obj.str("time"))
                ?: extractTimestampMs(obj)
                ?: System.currentTimeMillis()
            val durMs = obj.longVal("duration")?.let { if (it in 1L..3600L) it * 1000L else it } ?: 210_000L
            val pkg = obj.str("packageName").ifBlank { obj.str("app").ifBlank { "com.arn.scrobble" } }
            val appName = obj.str("appName").ifBlank { "Pano Scrobbler" }
            val category = MediaCategoryClassifier.classify(
                title = title,
                artist = artist,
                album = album,
                sourcePackage = pkg,
                sourceAppName = appName,
                durationMs = durMs,
            )
            results += ScrobbleEventEntity(
                trackKey = normalizeScrobbleTrackKey(artist, title),
                title = title,
                artist = artist,
                album = album,
                durationMs = durMs,
                listenedMs = durMs,
                startedAtMs = tsMs,
                scrobbledAtMs = tsMs,
                sourcePackage = pkg,
                sourceAppName = appName,
                lastFmSyncStatus = ScrobbleSyncStatus.SYNCED,
                listenBrainzSyncStatus = ScrobbleSyncStatus.SKIPPED,
                mediaCategory = category,
            )
        }
        return ImportResult(results, "Pano Scrobbler JSON", skipped)
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
                mediaCategory = ScrobbleMediaCategory.MUSIC,
            )
        }
        return ImportResult(results, "Last.fm JSON", skipped)
    }

    private fun parseCsv(raw: String): ImportResult {
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return ImportResult(emptyList(), "CSV", 0)
        val firstCols = parseCsvLine(lines.first())
        val header = firstCols.map { it.trim().lowercase(Locale.ROOT).removePrefix("\uFEFF") }

        val idxArtist = header.indexOfFirst {
            it == "artist" || it == "artist name" || it == "artist_name" || it == "artistname" || it == "channel" || it == "creator"
        }
        val idxTitle = header.indexOfFirst {
            it == "title" || it == "track" || it == "name" || it == "song name" || it == "track name" || it == "track_name" || it == "trackname" || it == "song"
        }

        // Handle headerless 4-column Last.fm CSV exports: artist,album,track,date
        if (idxArtist < 0 || idxTitle < 0) {
            if (firstCols.size == 4 && parseFlexibleTimestamp(firstCols[3].trim()) != null) {
                val events = mutableListOf<ScrobbleEventEntity>()
                var skipped = 0
                for (line in lines) {
                    val cols = parseCsvLine(line)
                    val artist = cols.getOrNull(0)?.trim().orEmpty()
                    val album = cols.getOrNull(1)?.trim().orEmpty()
                    val title = cols.getOrNull(2)?.trim().orEmpty()
                    val tsMs = parseFlexibleTimestamp(cols.getOrNull(3)?.trim().orEmpty())
                    if (artist.isBlank() || title.isBlank() || tsMs == null) {
                        skipped++
                        continue
                    }
                    events += ScrobbleEventEntity(
                        trackKey = normalizeScrobbleTrackKey(artist, title),
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = 210_000L,
                        listenedMs = 210_000L,
                        startedAtMs = tsMs,
                        scrobbledAtMs = tsMs,
                        sourcePackage = "fm.last.android",
                        sourceAppName = "Last.fm CSV",
                        lastFmSyncStatus = ScrobbleSyncStatus.SYNCED,
                        listenBrainzSyncStatus = ScrobbleSyncStatus.SKIPPED,
                        mediaCategory = ScrobbleMediaCategory.MUSIC,
                    )
                }
                return ImportResult(events, "Last.fm CSV", skipped)
            }
            return ImportResult(emptyList(), "CSV", lines.size)
        }
        if (lines.size < 2) return ImportResult(emptyList(), "CSV", 0)

        val idxAlbum = header.indexOfFirst { it == "album" || it == "album name" || it == "album_name" || it == "release" }
        val idxGenre = header.indexOfFirst { it == "genre" || it == "genres" }
        val idxScrobbled = header.indexOfFirst {
            it == "scrobbledatms" || it == "timestamp" || it == "uts" || it == "date" ||
                it == "time" || it == "played_at" || it == "endtime" || it == "event end timestamp" || it == "date_uts"
        }
        val idxStarted = header.indexOfFirst { it == "startedatms" || it == "event start timestamp" }
        val idxDuration = header.indexOfFirst {
            it == "durationms" || it == "duration" || it == "media duration in milliseconds" || it == "ms_played" || it == "msplayed"
        }
        val idxListened = header.indexOfFirst {
            it == "listenedms" || it == "play duration milliseconds" || it == "ms_played" || it == "msplayed"
        }
        val idxSourceApp = header.indexOfFirst { it == "sourceappname" || it == "source" || it == "app" || it == "player" }
        val idxSourcePkg = header.indexOfFirst { it == "sourcepackage" || it == "package" }
        val idxLoved = header.indexOfFirst { it == "isloved" || it == "loved" }
        val idxCategory = header.indexOfFirst { it == "mediacategory" || it == "category" || it == "type" }
        val isAppleMusic = header.contains("event end timestamp") || header.contains("play duration milliseconds")

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
            val startedMs = cols.getOrNull(idxStarted)?.trim()?.let { it.toLongOrNull() ?: parseFlexibleTimestamp(it) } ?: tsMs
            val durationMs = cols.getOrNull(idxDuration)?.trim()?.toLongOrNull() ?: 210_000L
            val listenedMs = cols.getOrNull(idxListened)?.trim()?.toLongOrNull() ?: durationMs
            val lovedStr = cols.getOrNull(idxLoved)?.trim().orEmpty()
            val srcPkg = cols.getOrNull(idxSourcePkg)?.trim().orEmpty().ifBlank {
                if (isAppleMusic) "com.apple.android.music" else "import.csv"
            }
            val srcApp = cols.getOrNull(idxSourceApp)?.trim().orEmpty().ifBlank {
                if (isAppleMusic) "Apple Music" else "CSV Import"
            }
            val rawCat = cols.getOrNull(idxCategory)?.trim()?.uppercase(Locale.ROOT).orEmpty()
            val album = cols.getOrNull(idxAlbum)?.trim().orEmpty()
            val genre = cols.getOrNull(idxGenre)?.trim().orEmpty()
            val category = if (rawCat in setOf(
                    ScrobbleMediaCategory.MUSIC,
                    ScrobbleMediaCategory.VIDEO,
                    ScrobbleMediaCategory.PODCAST,
                    ScrobbleMediaCategory.OTHER,
                )
            ) {
                rawCat
            } else {
                MediaCategoryClassifier.classify(
                    title = title,
                    artist = artist,
                    album = album,
                    genre = genre,
                    sourcePackage = srcPkg,
                    sourceAppName = srcApp,
                    durationMs = durationMs,
                )
            }
            events += ScrobbleEventEntity(
                trackKey = normalizeScrobbleTrackKey(artist, title),
                title = title,
                artist = artist,
                album = album,
                genre = genre,
                durationMs = durationMs,
                listenedMs = listenedMs,
                startedAtMs = startedMs,
                scrobbledAtMs = tsMs,
                sourcePackage = srcPkg,
                sourceAppName = srcApp,
                isLoved = lovedStr.equals("true", ignoreCase = true) || lovedStr == "1",
                lastFmSyncStatus = ScrobbleSyncStatus.SYNCED,
                listenBrainzSyncStatus = ScrobbleSyncStatus.SKIPPED,
                mediaCategory = category,
            )
        }
        return ImportResult(events, if (isAppleMusic) "Apple Music CSV" else "CSV", skipped)
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
        if (iso.isNotBlank()) return parseFlexibleTimestamp(iso)
        return null
    }

    private fun parseFlexibleTimestamp(raw: String): Long? {
        if (raw.isBlank()) return null
        raw.toLongOrNull()?.let { return normalizeEpochToMs(it) }
        return parseIsoTimestampMs(raw) ?: parseSpotifyShortTimestampMs(raw) ?: parseLastFmHumanDateMs(raw)
    }

    private fun parseLastFmHumanDateMs(raw: String): Long? {
        val clean = raw.trim()
        if (clean.isBlank()) return null
        val patterns = listOf(
            "dd MMM yyyy, HH:mm",
            "dd MMM yyyy HH:mm",
            "d MMM yyyy, HH:mm",
            "d MMM yyyy HH:mm",
            "yyyy/MM/dd HH:mm:ss",
            "MM/dd/yyyy HH:mm:ss",
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

    private const val MAX_ZIP_ENTRY_BYTES = 128 * 1024 * 1024

    private val NON_HISTORY_SPOTIFY_FILES = setOf(
        "yourlibrary.json", "inferences.json", "identity.json", "userdata.json",
        "payments.json", "marquee.json", "searchqueries.json", "follow.json",
        "identifiers.json", "readme.txt", "read_me_first.pdf",
    )

    private val TAKEOUT_PREFIXES = listOf(
        "Watched ", "Listened to ", "Regardé ", "Écouté ", "Visto ", "Escuchado ",
        "Angesehen: ", "Angehört: ", "Assistiu a ", "Ouviu ", "Guardato ", "Ascoltato ",
    )

    private fun stripTakeoutPrefix(raw: String): String {
        var s = raw.trim()
        for (p in TAKEOUT_PREFIXES) {
            if (s.startsWith(p, ignoreCase = true)) {
                s = s.substring(p.length).trim()
                break
            }
        }
        return s
    }

    /**
     * Streams a Google Takeout `watch-history.html` entry without loading an entire
     * 100MB+ multi-year DOM tree into RAM (splits on `outer-cell` boundaries cleanly).
     */
    private fun parseYouTubeTakeoutHtmlStream(input: InputStream): ImportResult {
        val reader = java.io.BufferedReader(java.io.InputStreamReader(input, Charsets.UTF_8), 64 * 1024)
        val results = mutableListOf<ScrobbleEventEntity>()
        var totalSkipped = 0
        val chunkBuilder = StringBuilder(64 * 1024)
        val buf = CharArray(32 * 1024)
        var totalChars = 0L

        fun flushChunk(text: String) {
            if (text.isBlank()) return
            val parsed = parseYouTubeTakeoutHtml(text)
            results += parsed.events
            totalSkipped += parsed.skippedCount
        }

        while (true) {
            val n = reader.read(buf)
            if (n <= 0) break
            totalChars += n
            if (totalChars > MAX_ZIP_ENTRY_BYTES.toLong() * 2L) break
            chunkBuilder.append(buf, 0, n)
            if (chunkBuilder.length >= 256 * 1024) {
                val splitIdx = chunkBuilder.lastIndexOf("<div class=\"outer-cell")
                if (splitIdx > 0) {
                    val ready = chunkBuilder.substring(0, splitIdx)
                    chunkBuilder.delete(0, splitIdx)
                    flushChunk(ready)
                }
            }
        }
        if (chunkBuilder.isNotEmpty()) {
            flushChunk(chunkBuilder.toString())
        }
        return ImportResult(results, "Google Takeout (YouTube HTML)", totalSkipped)
    }

    /**
     * Parses Google Takeout's default `watch-history.html` export (YouTube & YouTube Music).
     * Automatically separates Music vs. Videos/Other via [MediaCategoryClassifier].
     */
    private fun parseYouTubeTakeoutHtml(html: String): ImportResult {
        val doc = runCatching { org.jsoup.Jsoup.parse(html) }.getOrNull()
            ?: return ImportResult(emptyList(), "Google Takeout HTML", 0)
        val cells = doc.select("div.content-cell.mdl-cell--6-col, div.content-cell")
        if (cells.isEmpty()) return ImportResult(emptyList(), "Google Takeout HTML", 0)

        val results = mutableListOf<ScrobbleEventEntity>()
        var skipped = 0
        for (cell in cells) {
            val cellFullText = cell.text()
            if (cellFullText.contains("From Google Ads", ignoreCase = true) ||
                cellFullText.startsWith("Searched for", ignoreCase = true)
            ) {
                skipped++
                continue
            }
            val links = cell.select("a")
            if (links.isEmpty()) continue
            val titleLink = links[0]
            val href = titleLink.attr("href")
            if (!href.contains("youtube.com", ignoreCase = true) && !href.contains("youtu.be", ignoreCase = true)) {
                continue
            }
            val rawTitle = stripTakeoutPrefix(titleLink.text())
            if (rawTitle.isBlank() || rawTitle.startsWith("https://")) {
                skipped++
                continue
            }
            val channel = links.getOrNull(1)?.text()?.trim().orEmpty()
            val isYtMusic = href.contains("music.youtube.com", ignoreCase = true) ||
                cell.parent()?.text()?.contains("YouTube Music", ignoreCase = true) == true
            // Extract date line (last text node inside content-cell)
            val textLines = cell.ownText().split("\n").map { it.trim() }.filter { it.isNotBlank() }
            val dateCandidate = cell.textNodes().lastOrNull()?.text()?.trim()
                ?: textLines.lastOrNull().orEmpty()
            val tsMs = parseTakeoutHtmlDate(dateCandidate) ?: run { skipped++; continue }

            val (parsedArtist, parsedTitle) = splitYouTubeTitleAndArtist(
                rawTitle = rawTitle,
                channelName = channel,
                isYouTubeMusic = isYtMusic,
            )
            if (parsedTitle.isBlank() || parsedArtist.isBlank()) {
                skipped++
                continue
            }
            val sourcePkg = if (isYtMusic) "com.google.android.apps.youtube.music" else "com.google.android.youtube"
            val sourceApp = if (isYtMusic) "YouTube Music" else "YouTube"
            val category = if (isYtMusic) {
                ScrobbleMediaCategory.MUSIC
            } else {
                MediaCategoryClassifier.classify(
                    title = rawTitle,
                    artist = channel.ifBlank { parsedArtist },
                    sourcePackage = sourcePkg,
                    sourceAppName = sourceApp,
                )
            }
            val cleaned = ScrobbleRulesEngine.cleanMetadata(ScrobbleMetadata(parsedTitle, parsedArtist))
            results += ScrobbleEventEntity(
                trackKey = normalizeScrobbleTrackKey(cleaned.artist, cleaned.title),
                title = cleaned.title,
                artist = cleaned.artist,
                durationMs = 210_000L,
                listenedMs = 210_000L,
                startedAtMs = tsMs,
                scrobbledAtMs = tsMs,
                sourcePackage = sourcePkg,
                sourceAppName = sourceApp,
                lastFmSyncStatus = ScrobbleSyncStatus.SYNCED,
                listenBrainzSyncStatus = ScrobbleSyncStatus.SKIPPED,
                mediaCategory = category,
            )
        }
        return ImportResult(results, "Google Takeout (YouTube HTML)", skipped)
    }

    private fun parseTakeoutHtmlDate(raw: String): Long? {
        val clean = raw.trim()
            .replace("\u202F", " ")
            .replace("\u00A0", " ")
        if (clean.isBlank()) return null
        val patterns = listOf(
            "MMM d, yyyy, h:mm:ss a z",
            "MMM d, yyyy, h:mm:ss a",
            "MMM d, yyyy, HH:mm:ss z",
            "d MMM yyyy, HH:mm:ss z",
            "d MMM yyyy, HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
        )
        for (p in patterns) {
            val ts = runCatching { SimpleDateFormat(p, Locale.US).parse(clean)?.time }.getOrNull()
            if (ts != null) return ts
        }
        // Strip trailing timezone token like "MST"/"PDT"/"GMT+02:00" as fallback
        val withoutTz = clean.replace(Regex("""\s+[A-Z]{2,5}([+-]\d{1,2}:?\d{2})?$"""), "").trim()
        for (p in listOf("MMM d, yyyy, h:mm:ss a", "MMM d, yyyy, HH:mm:ss", "d MMM yyyy, HH:mm:ss")) {
            val ts = runCatching { SimpleDateFormat(p, Locale.US).parse(withoutTz)?.time }.getOrNull()
            if (ts != null) return ts
        }
        return parseIsoTimestampMs(clean)
    }

    private fun normalizeEpochToMs(value: Long): Long =
        if (value in 1L..9_999_999_999L) value * 1000L else value

    internal fun parseIsoTimestampMs(raw: String): Long? {
        val clean = raw.trim()
        if (clean.isBlank()) return null
        // java.time.Instant handles arbitrary fractional second precision (e.g. Google Takeout's
        // 6-digit microseconds like 2024-06-15T14:23:45.123456Z) accurately without millisecond shift.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            runCatching { return java.time.Instant.parse(clean).toEpochMilli() }
            runCatching { return java.time.OffsetDateTime.parse(clean).toInstant().toEpochMilli() }
        }
        // Normalize >3 fractional digits to 3 digits for SimpleDateFormat fallback on API 24-25.
        val normalized = clean.replace(Regex("""\.(\d{3})\d+"""), ".$1")
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
                }.parse(normalized)?.time
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
