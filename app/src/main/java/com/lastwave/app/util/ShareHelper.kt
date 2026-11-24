package com.lastwave.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.lastwave.app.playback.PlayableTrack
import java.net.URLEncoder

/**
 * Central sharing helper that validates real YouTube Music / Spotify URLs
 * (never fabricating fake IDs) and launches Android's direct share sheet
 * (`Intent.ACTION_SEND`) so songs, albums, and playlists can be sent straight
 * to messaging apps without manual copying.
 */
object ShareHelper {

    private val YT_VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
    private val SPOTIFY_ID_REGEX = Regex("^[A-Za-z0-9]{22}$")
    private val YT_URL_PATTERNS = listOf(
        Regex("""(?:youtube\.com|music\.youtube\.com)/watch\?(?:[^#]*&)?v=([A-Za-z0-9_-]{11})"""),
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""youtube\.com/(?:shorts|embed|live)/([A-Za-z0-9_-]{11})"""),
    )
    private val SPOTIFY_TRACK_URL_REGEX = Regex("""open\.spotify\.com/track/([A-Za-z0-9]{22})""")

    fun validYouTubeVideoId(candidate: String?): String? {
        val clean = candidate?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (YT_VIDEO_ID_REGEX.matches(clean)) return clean
        return extractYouTubeVideoIdFromUrl(clean)
    }

    fun extractYouTubeVideoIdFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        for (pattern in YT_URL_PATTERNS) {
            val id = pattern.find(url)?.groupValues?.getOrNull(1)
            if (id != null && YT_VIDEO_ID_REGEX.matches(id)) return id
        }
        return null
    }

    fun extractSpotifyTrackId(candidate: String?): String? {
        if (candidate.isNullOrBlank()) return null
        val trimmed = candidate.trim()
        if (trimmed.startsWith("spotify:track:")) {
            val id = trimmed.removePrefix("spotify:track:")
            if (SPOTIFY_ID_REGEX.matches(id)) return id
        }
        SPOTIFY_TRACK_URL_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    /**
     * Resolves a verified, working share URL synchronously when a valid YouTube
     * video ID or Spotify track URL is already known; returns null when an async
     * catalog lookup is needed so we never produce a broken fake URL.
     */
    fun verifiedTrackUrlOrNull(
        existingVideoId: String?,
        rawUrl: String?,
    ): String? {
        val ytId = validYouTubeVideoId(existingVideoId) ?: extractYouTubeVideoIdFromUrl(rawUrl)
        if (ytId != null) return "https://music.youtube.com/watch?v=$ytId"

        val spId = extractSpotifyTrackId(rawUrl) ?: extractSpotifyTrackId(existingVideoId)
        if (spId != null) return "https://open.spotify.com/track/$spId"

        val cleanRaw = rawUrl?.trim().orEmpty()
        if ((cleanRaw.startsWith("https://music.youtube.com/") ||
                cleanRaw.startsWith("https://www.youtube.com/") ||
                cleanRaw.startsWith("https://open.spotify.com/")) &&
            !cleanRaw.contains("local:") && !cleanRaw.contains("query:")
        ) {
            return cleanRaw
        }
        return null
    }

    fun fallbackSearchUrl(title: String, artist: String): String {
        val query = listOf(title.trim(), artist.trim()).filter { it.isNotBlank() }.joinToString(" ")
        val encoded = runCatching { URLEncoder.encode(query, "UTF-8") }.getOrDefault("")
        return "https://music.youtube.com/search?q=$encoded"
    }

    fun buildTrackShareText(title: String, artist: String, url: String): String {
        val label = if (artist.isNotBlank()) "$title — $artist" else title
        return "$label\n$url"
    }

    fun shareText(
        context: Context,
        subject: String,
        body: String,
        chooserTitle: String = "Share via",
    ) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        val chooser = Intent.createChooser(sendIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }.onFailure {
            copyToClipboard(context, subject, body, "Copied to clipboard")
        }
    }

    fun shareTrackSync(context: Context, track: PlayableTrack) {
        val url = verifiedTrackUrlOrNull(track.videoId, track.playbackUrl)
            ?: fallbackSearchUrl(track.title, track.artist)
        val body = buildTrackShareText(track.title, track.artist, url)
        shareText(context, "${track.title} — ${track.artist}", body, "Share song")
    }

    fun shareAlbum(
        context: Context,
        albumTitle: String,
        artistName: String,
        browseId: String? = null,
    ) {
        val url = when {
            !browseId.isNullOrBlank() && (browseId.startsWith("MPRE") || browseId.startsWith("OLAK")) ->
                "https://music.youtube.com/browse/$browseId"
            else -> fallbackSearchUrl(albumTitle, artistName)
        }
        val label = if (artistName.isNotBlank()) "$albumTitle — $artistName" else albumTitle
        shareText(context, label, "$label\n$url", "Share album")
    }

    fun sharePlaylist(
        context: Context,
        playlistTitle: String,
        playlistId: String? = null,
        tracksPreview: List<PlayableTrack> = emptyList(),
    ) {
        val cleanId = playlistId?.trim().orEmpty()
        val url = when {
            cleanId.startsWith("sp_") && cleanId.length > 10 && cleanId != "sp_liked" ->
                "https://open.spotify.com/playlist/${cleanId.removePrefix("sp_")}"
            cleanId.startsWith("VLPL") || cleanId.startsWith("PL") || cleanId.startsWith("RDCLAK") ->
                "https://music.youtube.com/playlist?list=${cleanId.removePrefix("VL")}"
            else -> null
        }
        val body = buildString {
            append(playlistTitle)
            if (url != null) {
                append('\n').append(url)
            } else if (tracksPreview.isNotEmpty()) {
                append("\n\n")
                tracksPreview.take(8).forEachIndexed { idx, t ->
                    append("${idx + 1}. ${t.title}")
                    if (t.artist.isNotBlank()) append(" — ${t.artist}")
                    append('\n')
                }
                if (tracksPreview.size > 8) append("…and ${tracksPreview.size - 8} more tracks")
            }
        }.trim()
        shareText(context, playlistTitle, body, "Share playlist")
    }

    fun copyToClipboard(context: Context, label: String, text: String, toastMessage: String? = null) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
        if (!toastMessage.isNullOrBlank()) {
            Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
        }
    }
}
