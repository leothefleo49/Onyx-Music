package com.lastwave.app.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
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
import kotlin.math.PI
import kotlin.math.sin

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
    onPlayPause: (() -> Unit)? = null,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    visualizerMode: String = "glow",
    showControls: Boolean = true,
    showArtwork: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Two taps: the first arms exit, the second leaves. One stray tap no
    // longer dismisses the mode.
    var exitArmed by remember { mutableStateOf(false) }
    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            delay(3_000L)
            exitArmed = false
        }
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
            .clickable {
                if (exitArmed) onExit() else exitArmed = true
            },
        contentAlignment = Alignment.Center,
    ) {
        if (visualizerMode == "glow") {
            AmbientEdgeGlow(
                config = glow.copy(enabled = glow.enabled, intensity = glow.intensity * 0.8f),
                isPlaying = isPlaying,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AodEdgeVisualizer(
            mode = visualizerMode,
            glow = glow,
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
            if (showArtwork && track?.artworkUrl != null) {
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
            if (showControls && onPlayPause != null) {
                Spacer(Modifier.height(18.dp))
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onPrevious != null) {
                        IconButton(onClick = onPrevious) {
                            Icon(
                                Icons.Filled.SkipPrevious,
                                contentDescription = "Previous",
                                tint = Color.White.copy(alpha = 0.80f),
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                    IconButton(onClick = onPlayPause) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White.copy(alpha = 0.94f),
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    if (onNext != null) {
                        IconButton(onClick = onNext) {
                            Icon(
                                Icons.Filled.SkipNext,
                                contentDescription = "Next",
                                tint = Color.White.copy(alpha = 0.80f),
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = if (exitArmed) "Tap again to exit" else "Tap twice to exit",
            fontSize = 10.sp,
            letterSpacing = 2.sp,
            color = Color.White.copy(alpha = if (exitArmed) 0.70f else 0.22f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        )
    }
}

/** Lumn-style edge visualisers layered under the AOD content. */
@Composable
private fun AodEdgeVisualizer(
    mode: String,
    glow: EdgeGlowConfig,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    if (mode != "bars" && mode != "wave") return
    val transition = rememberInfiniteTransition(label = "aodVisualizer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "aodVisualizerPhase",
    )
    val strength = if (isPlaying) 1f else 0.3f
    val intensity = glow.intensity.coerceIn(0.2f, 2f)
    Canvas(modifier) {
        val colorA = glow.color1
        val colorB = glow.color2
        if (mode == "bars") {
            val maxLen = size.minDimension * 0.09f * glow.thickness.coerceIn(0.1f, 1f) * intensity
            val segments = 28
            val barWidth = 3.5.dp.toPx()
            for (i in 0 until segments) {
                val fraction = i / (segments - 1f)
                val color = lerp(colorA, colorB, fraction)
                    .copy(alpha = (0.55f * intensity).coerceIn(0.15f, 0.9f))
                val pulse = 0.22f + 0.78f * ((sin(phase * 2f + i * 0.52f) + 1f) / 2f)
                val length = maxLen * pulse * strength

                val x = size.width * (i + 0.5f) / segments
                drawLine(color, Offset(x, 0f), Offset(x, length), barWidth, cap = StrokeCap.Round)
                drawLine(
                    color,
                    Offset(x, size.height),
                    Offset(x, size.height - length * 0.8f),
                    barWidth,
                    cap = StrokeCap.Round,
                )

                val y = size.height * (i + 0.5f) / segments
                drawLine(color, Offset(0f, y), Offset(length * 0.8f, y), barWidth, cap = StrokeCap.Round)
                drawLine(
                    color,
                    Offset(size.width, y),
                    Offset(size.width - length * 0.8f, y),
                    barWidth,
                    cap = StrokeCap.Round,
                )
            }
        } else {
            val amplitude = size.minDimension * 0.035f * intensity
            val stroke = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
            val steps = 64

            fun edgePath(vertical: Boolean, reverse: Boolean): Path {
                val path = Path()
                for (step in 0..steps) {
                    val t = step / steps.toFloat()
                    val wave = sin(phase * 2f + t * (2f * PI).toFloat()) * amplitude * strength
                    val (x, y) = when {
                        !vertical && !reverse -> t * size.width to wave
                        !vertical && reverse -> t * size.width to size.height - wave
                        vertical && !reverse -> wave to t * size.height
                        else -> size.width - wave to t * size.height
                    }
                    if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                return path
            }

            drawPath(edgePath(vertical = false, reverse = false), colorA.copy(alpha = 0.75f), style = stroke)
            drawPath(edgePath(vertical = false, reverse = true), colorB.copy(alpha = 0.65f), style = stroke)
            drawPath(edgePath(vertical = true, reverse = false), colorB.copy(alpha = 0.55f), style = stroke)
            drawPath(edgePath(vertical = true, reverse = true), colorA.copy(alpha = 0.55f), style = stroke)
        }
    }
}
