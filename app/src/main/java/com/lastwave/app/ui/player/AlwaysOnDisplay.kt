package com.lastwave.app.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.playback.PlayableTrack
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Always-on player: a black screen with a large clock, artwork, what is
 * playing and one line of synced lyrics, lit by the ambient edge glow.
 * Holds the screen awake while visible; a single tap returns to the player.
 */
@Composable
internal fun AlwaysOnDisplayOverlay(
    track: PlayableTrack?,
    isPlaying: Boolean,
    showLyrics: Boolean,
    lines: List<LyricLine>,
    isSynced: Boolean,
    plainLyrics: String?,
    positionMs: Long,
    lyricsOffsetMs: Long,
    glow: EdgeGlowConfig,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val timeText = remember(now) { SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(now)) }
    val dateText = remember(now) {
        SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(now))
    }

    // Current lyric: newest line whose timestamp passed, minus the user offset.
    val lyric: String? = remember(showLyrics, lines, isSynced, plainLyrics, positionMs, lyricsOffsetMs) {
        if (!showLyrics) return@remember null
        if (isSynced && lines.isNotEmpty()) {
            val t = positionMs + lyricsOffsetMs
            lines.lastOrNull { it.timeMs <= t }?.text?.takeIf { it.isNotBlank() }
        } else {
            plainLyrics?.lineSequence()?.firstOrNull { it.isNotBlank() }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(onClick = onExit),
        contentAlignment = Alignment.Center,
    ) {
        AmbientEdgeGlow(
            config = glow.copy(enabled = glow.enabled, intensity = glow.intensity * 0.8f),
            isPlaying = isPlaying,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        ) {
            Text(
                text = timeText,
                fontSize = 96.sp,
                fontWeight = FontWeight.ExtraLight,
                color = Color.White.copy(alpha = 0.94f),
                letterSpacing = 2.sp,
            )
            Text(
                text = dateText.uppercase(Locale.getDefault()),
                fontSize = 12.sp,
                letterSpacing = 4.sp,
                color = Color.White.copy(alpha = 0.45f),
            )
            Spacer(Modifier.height(36.dp))
            if (track?.artworkUrl != null) {
                AsyncImage(
                    model = track.artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(132.dp)
                        .clip(RoundedCornerShape(22.dp)),
                )
                Spacer(Modifier.height(20.dp))
            }
            Text(
                text = track?.title ?: "Nothing playing",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 520.dp),
            )
            Text(
                text = track?.artist.orEmpty(),
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.50f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 520.dp),
            )
            Spacer(Modifier.height(26.dp))
            AnimatedContent(
                targetState = lyric,
                transitionSpec = { fadeIn(tween(420)) togetherWith fadeOut(tween(420)) },
                label = "aodLyric",
            ) { line ->
                Text(
                    text = line.orEmpty(),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.86f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 620.dp).height(64.dp),
                )
            }
        }
        Text(
            text = "Tap to exit",
            fontSize = 10.sp,
            letterSpacing = 2.sp,
            color = Color.White.copy(alpha = 0.22f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        )
    }
}
