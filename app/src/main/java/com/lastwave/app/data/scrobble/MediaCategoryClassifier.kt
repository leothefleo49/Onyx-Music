package com.lastwave.app.data.scrobble

/**
 * Pure-Kotlin media classifier that separates Music from Videos, Podcasts, and
 * Other media so external scrobblers and multi-platform imports (e.g. YouTube
 * Takeout, Spotify podcasts, Twitch, browsers) can keep everything recorded
 * without polluting Music listening stats.
 */
object MediaCategoryClassifier {

    private val PURE_MUSIC_PACKAGES = setOf(
        "com.leothefleo49.onyxmusic",
        "com.lastwave.app",
        "com.google.android.apps.youtube.music",
        "com.apple.android.music",
        "com.aspiro.tidal",
        "com.qobuz.music",
        "tv.plex.labs.plexamp",
        "com.maxmpz.audioplayer",
        "com.amazon.mp3",
        "deezer.android.app",
        "com.bandcamp.android",
        "in.krosbits.musicolet",
        "com.neutroncode.mp",
        "com.hiby.music",
        "com.foobar2000.foobar2000",
        "app.symfonik.music.player",
        "com.pandora.android",
        "com.soundcloud.android",
    )

    private val VIDEO_PACKAGES = setOf(
        "com.google.android.youtube",
        "app.revanced.android.youtube",
        "app.rvx.android.youtube",
        "org.schabi.newpipe",
        "com.netflix.mediaclient",
        "tv.twitch.android.app",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.disney.disneyplus",
        "com.hulu.plus",
        "com.amazon.avod.thirdpartyclient",
        "com.vimeo.android.videoapp",
        "org.videolan.vlc",
        "com.mxtech.videoplayer.ad",
        "com.mxtech.videoplayer.pro",
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.brave.browser",
        "com.opera.browser",
    )

    private val PODCAST_PACKAGES = setOf(
        "au.com.shiftyjelly.pocketcasts",
        "de.danoeh.antennapod",
        "com.google.android.apps.podcasts",
        "com.bambuna.podcastaddict",
        "fm.castbox.audiobook.radio.podcast",
        "com.audible.application",
        "com.overdrive.mobile.android.libby",
    )

    private val PODCAST_TITLE_REGEX = Regex(
        """\b(podcast|episode\s*#?\d+|ep\.?\s*#?\d+|audiobook|chapter\s+\d+|full\s+interview|q&a\s+session|commentary\s+track)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val STRONG_MUSIC_CUE_REGEX = Regex(
        """(\bofficial\s+(music\s+)?video\b|\bofficial\s+audio\b|\bofficial\s+lyric\b|\blyrics?\s*video\b|\blyrics\b|\baudio\b|\bmusic\s+video\b|\bvisualizer\b|\bremix\b|\bacoustic\b|\bcover\b|\bfeat\.?\b|\bft\.?\b|\bprod\.?\b|\bost\b|\bsoundtrack\b|\btiny\s+desk\b|\bcolors\s+show\b|\bvevo\b|\btopic\b|\blofi\b|\blo-fi\b|\bfull\s+album\b|\bmixtape\b|\btrack\s+\d+\b|\blive\s+session\b|\bdj\s+set\b)""",
        RegexOption.IGNORE_CASE,
    )

    private val MUSIC_CHANNEL_REGEX = Regex(
        """(-\s*topic$|vevo$|\brecords\b|\bmusic\b|\brecordings\b|\bsounds\b|\baudio\b)""",
        RegexOption.IGNORE_CASE,
    )

    private val VIDEO_NON_MUSIC_REGEX = Regex(
        """(\bvlog\b|\bgameplay\b|\bwalkthrough\b|\bplaythrough\b|\blet'?s\s+play\b|\btutorial\b|\bhow\s+to\b|\breview\b|\bunboxing\b|\breaction\b|\breacts?\s+to\b|\btrailer\b|\bteaser\b|\bshorts\b|\blivestream\b|\bvod\b|\bhighlights?\b|\bcompilation\b|\btier\s+list\b|\bexplained\b|\bdocumentary\b|\bvideo\s+essay\b|\bnews\b|\bpatch\s+notes\b|\bspeedrun\b|\bchallenge\b|\bprank\b|\brecipe\b|\bcooking\b|\bworkout\b|\basmr\b|\bpart\s+\d+\b|\bpt\.?\s*\d+\b|\bday\s+in\s+the\s+life\b|\branked\b|\btop\s+\d+\b|\bvs\.?\b|\bminecraft\b|\bfortnite\b|\bvalorant\b|\broblox\b|\bsetup\s+tour\b|\btech\b|\bbenchmark\b)""",
        RegexOption.IGNORE_CASE,
    )

    private val NON_MUSIC_GENRES = setOf(
        "podcast",
        "podcasts",
        "audiobook",
        "audiobooks",
        "spoken word",
        "comedy",
        "news",
        "sports",
        "gaming",
        "education",
        "science",
        "technology",
        "film",
        "animation",
        "howto",
        "people & blogs",
        "entertainment",
    )

    /**
     * Classifies a playback or import item into [ScrobbleMediaCategory].
     * Conservative towards keeping genuine music as [ScrobbleMediaCategory.MUSIC]
     * on music players, while accurately routing YouTube/video-app vlogs, tutorials,
     * streams, and podcasts to [ScrobbleMediaCategory.VIDEO] / [ScrobbleMediaCategory.PODCAST].
     */
    fun classify(
        title: String,
        artist: String,
        album: String = "",
        genre: String = "",
        sourcePackage: String = "",
        sourceAppName: String = "",
        durationMs: Long = 0L,
        isExplicitPodcast: Boolean = false,
    ): String {
        if (isExplicitPodcast) return ScrobbleMediaCategory.PODCAST

        val pkg = sourcePackage.trim().lowercase()
        val app = sourceAppName.trim().lowercase()
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        val cleanGenre = genre.trim().lowercase()

        if (pkg in PODCAST_PACKAGES || app.contains("podcast") || app.contains("audible")) {
            return ScrobbleMediaCategory.PODCAST
        }
        if (cleanGenre in NON_MUSIC_GENRES) {
            return if (cleanGenre.contains("podcast") || cleanGenre.contains("audiobook") || cleanGenre.contains("spoken")) {
                ScrobbleMediaCategory.PODCAST
            } else {
                ScrobbleMediaCategory.VIDEO
            }
        }
        if (cleanArtist.equals("Podcast", ignoreCase = true) || cleanTitle.equals("Podcast", ignoreCase = true)) {
            return ScrobbleMediaCategory.PODCAST
        }

        val combined = "$cleanTitle $cleanArtist $album"
        val hasMusicCue = STRONG_MUSIC_CUE_REGEX.containsMatchIn(cleanTitle) ||
            MUSIC_CHANNEL_REGEX.containsMatchIn(cleanArtist)
        val hasPodcastCue = PODCAST_TITLE_REGEX.containsMatchIn(combined)
        val hasVideoCue = VIDEO_NON_MUSIC_REGEX.containsMatchIn(cleanTitle)

        // Spotify & mixed audio apps can play both music and podcasts/audiobooks.
        val isSpotify = pkg.startsWith("com.spotify") || app == "spotify"
        if (isSpotify) {
            if (hasPodcastCue && !hasMusicCue) return ScrobbleMediaCategory.PODCAST
            if (durationMs >= 25L * 60_000L && !hasMusicCue) return ScrobbleMediaCategory.PODCAST
            return ScrobbleMediaCategory.MUSIC
        }

        // Dedicated music apps default to MUSIC unless explicitly a podcast episode.
        if (pkg in PURE_MUSIC_PACKAGES || app in setOf(
                "onyx music", "youtube music", "apple music", "tidal", "qobuz",
                "soundcloud", "deezer", "bandcamp", "poweramp", "plexamp", "amazon music",
                "last.fm", "last.fm import", "listenbrainz", "stats.fm", "pano scrobbler",
            )
        ) {
            if (hasPodcastCue && !hasMusicCue && durationMs >= 15L * 60_000L) {
                return ScrobbleMediaCategory.PODCAST
            }
            return ScrobbleMediaCategory.MUSIC
        }

        // YouTube & video/browser apps: distinguish music videos/songs from general videos.
        val isVideoApp = pkg in VIDEO_PACKAGES ||
            app == "youtube" ||
            app.contains("twitch") ||
            app.contains("netflix") ||
            app.contains("tiktok") ||
            app.contains("chrome") ||
            app.contains("firefox") ||
            app.contains("vlc")

        if (isVideoApp) {
            if (hasPodcastCue && !hasMusicCue) return ScrobbleMediaCategory.PODCAST
            if (hasVideoCue && !hasMusicCue) return ScrobbleMediaCategory.VIDEO
            if (hasMusicCue) return ScrobbleMediaCategory.MUSIC
            // Standard YouTube music uploads often format titles as "Artist - Track" and have known song duration (1-10 min).
            // Never auto-classify unknown-duration (0ms) Takeout videos as MUSIC purely because of a dash in the title.
            val hasDashSplit = cleanTitle.contains(" - ") || cleanTitle.contains(" — ") || cleanTitle.contains(" – ")
            if (hasDashSplit && durationMs in 60_000L..600_000L && !hasVideoCue) {
                return ScrobbleMediaCategory.MUSIC
            }
            if (durationMs >= 20L * 60_000L) {
                return if (hasPodcastCue) ScrobbleMediaCategory.PODCAST else ScrobbleMediaCategory.VIDEO
            }
            return ScrobbleMediaCategory.VIDEO
        }

        if (hasPodcastCue && !hasMusicCue) return ScrobbleMediaCategory.PODCAST
        if (hasVideoCue && !hasMusicCue) return ScrobbleMediaCategory.VIDEO
        return ScrobbleMediaCategory.MUSIC
    }
}
