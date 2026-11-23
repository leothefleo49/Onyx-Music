package com.lastwave.app.ui.player

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.lastwave.app.data.artwork.ArtworkNormalizer
import com.lastwave.app.data.local.EdgeGlowMode
import com.lastwave.app.data.local.EdgeGlowMotion
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.rhythm.FluidRhythmEngine
import com.lastwave.app.ui.common.LocalAmbientAnimationsActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val AOD_VISUALIZER_MODES = listOf("wave", "glow", "bars", "aurora", "off")

private fun aodModeLabel(mode: String): String = when (mode) {
    "wave" -> "Wave"
    "glow" -> "Glow"
    "bars" -> "Bars"
    "aurora" -> "Aurora"
    else -> "Off"
}

/**
 * Always-on Display ("Vibe Mode"): true OLED black canvas framed by live
 * beat-reactive luminous edge visualizers (multi-layer glowing Wave ribbons,
 * Ambient Glow, Spectrum Bars, or flowing Aurora), dynamic album-art palette,
 * subtle OLED burn-in drift, synced current + next lyrics, and live mode switcher.
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
    onChangeVisualizerMode: ((String) -> Unit)? = null,
    visualizerMode: String = "wave",
    showControls: Boolean = true,
    showArtwork: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Extract vibrant cover-art colors when EdgeGlowMode.ART is active so AOD
    // always glows with the playing song's actual palette instead of a static fallback.
    var artPrimary by remember(track?.videoId, track?.artworkUrl, track?.title) { mutableStateOf<Color?>(null) }
    var artSecondary by remember(track?.videoId, track?.artworkUrl, track?.title) { mutableStateOf<Color?>(null) }
    LaunchedEffect(track?.artworkUrl, glow.mode) {
        if (glow.mode != EdgeGlowMode.ART) return@LaunchedEffect
        val url = ArtworkNormalizer.upscaleYoutubeArtwork(track?.artworkUrl)?.takeIf { it.isNotBlank() }
            ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .size(128)
                    .build()
                val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                    ?: return@runCatching
                val palette = Palette.from(bitmap).clearFilters().generate()
                val primarySwatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch
                val secondarySwatch = palette.lightVibrantSwatch?.takeIf { it != primarySwatch }
                    ?: palette.darkVibrantSwatch?.takeIf { it != primarySwatch }
                    ?: palette.mutedSwatch
                    ?: palette.dominantSwatch
                artPrimary = primarySwatch?.let { Color(it.rgb) }
                artSecondary = secondarySwatch?.let { Color(it.rgb) }
            }
        }
    }

    val targetColor1 = when (glow.mode) {
        EdgeGlowMode.ART -> artPrimary ?: glow.color1
        else -> glow.color1
    }
    val targetColor2 = when (glow.mode) {
        EdgeGlowMode.ART -> artSecondary ?: glow.color2
        EdgeGlowMode.CUSTOM -> lerp(glow.color1, Color.White, 0.32f)
        else -> glow.color2
    }
    val resolvedColor1 by animateColorAsState(targetColor1, tween(900), label = "aodColor1")
    val resolvedColor2 by animateColorAsState(targetColor2, tween(900), label = "aodColor2")
    val resolvedGlow = remember(glow, resolvedColor1, resolvedColor2) {
        glow.copy(
            enabled = true,
            color1 = resolvedColor1,
            color2 = resolvedColor2,
        )
    }

    // Live beat / audio energy from FluidRhythmEngine for all AOD visualizers.
    val animActive = LocalAmbientAnimationsActive.current && rememberPlayerWindowActive()
    val wantsBeat = visualizerMode != "off" && animActive && resolvedGlow.motion == EdgeGlowMotion.BEAT
    DisposableEffect(wantsBeat) {
        if (wantsBeat) FluidRhythmEngine.acquire()
        onDispose { if (wantsBeat) FluidRhythmEngine.release() }
    }
    var beatEnergy by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(wantsBeat, isPlaying) {
        if (!wantsBeat) {
            beatEnergy = 0f
            return@LaunchedEffect
        }
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0.001f, 0.1f)
                last = now
                beatEnergy = FluidRhythmEngine.tick(dt, isPlaying)
            }
        }
    }
    val smoothBeat by animateFloatAsState(
        targetValue = if (isPlaying) beatEnergy.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(90),
        label = "aodSmoothBeat",
    )

    // Subtle OLED burn-in drift: gently shifts content by +-5px every 45 seconds.
    var shiftStep by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(45_000L)
            shiftStep = (shiftStep + 1) % 8
        }
    }
    val driftOffset = remember(shiftStep) {
        val angle = shiftStep * (PI / 4.0)
        IntOffset((cos(angle) * 5.0).roundToInt(), (sin(angle) * 5.0).roundToInt())
    }

    // Two taps: the first arms exit, the second leaves.
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

    // Current + preview next synced lyric line.
    val lyricPair: Pair<String?, String?> = remember(showLyrics, lines, isSynced, plainLyrics, positionMs, lyricsOffsetMs) {
        if (!showLyrics) return@remember null to null
        if (isSynced && lines.isNotEmpty()) {
            val t = positionMs + lyricsOffsetMs
            val currentIdx = lines.indexOfLast { it.timeMs <= t }
            if (currentIdx >= 0) {
                val curr = lines[currentIdx].text.takeIf { it.isNotBlank() }
                val next = lines.drop(currentIdx + 1).firstOrNull { it.text.isNotBlank() }?.text
                curr to next
            } else {
                val upcoming = lines.firstOrNull { it.text.isNotBlank() }?.text
                null to upcoming
            }
        } else {
            val nonBlank = plainLyrics?.lineSequence()?.filter { it.isNotBlank() }?.take(2)?.toList().orEmpty()
            nonBlank.getOrNull(0) to nonBlank.getOrNull(1)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (exitArmed) onExit() else exitArmed = true
            },
        contentAlignment = Alignment.Center,
    ) {
        // 1. Edge visualizers (Glow / Wave / Bars / Aurora)
        if (visualizerMode == "glow") {
            AmbientEdgeGlow(
                config = resolvedGlow.copy(
                    enabled = true,
                    intensity = (resolvedGlow.intensity * 1.15f).coerceIn(0.4f, 2.2f),
                ),
                isPlaying = isPlaying,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (visualizerMode != "off" && visualizerMode != "glow") {
            AodEdgeVisualizer(
                mode = visualizerMode,
                glow = resolvedGlow,
                isPlaying = isPlaying,
                beat = smoothBeat,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 2. Centered OLED-shifted content
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .offset { driftOffset }
                .padding(horizontal = 32.dp),
        ) {
            Text(
                text = timeText,
                fontSize = 92.sp,
                fontWeight = FontWeight.ExtraLight,
                color = Color.White.copy(alpha = 0.95f),
                letterSpacing = 2.sp,
            )
            Text(
                text = dateText.uppercase(Locale.getDefault()),
                fontSize = 12.sp,
                letterSpacing = 4.sp,
                color = Color.White.copy(alpha = 0.48f),
            )
            Spacer(Modifier.height(30.dp))

            if (showArtwork && track?.artworkUrl != null) {
                // Soft luminous ambient halo behind the album cover reacting gently to the music.
                Box(contentAlignment = Alignment.Center) {
                    val haloAlpha = if (isPlaying) (0.28f + smoothBeat * 0.24f).coerceIn(0.18f, 0.56f) else 0.16f
                    Box(
                        modifier = Modifier
                            .size(192.dp)
                            .background(
                                Brush.radialGradient(
                                    0f to resolvedColor1.copy(alpha = haloAlpha),
                                    0.55f to resolvedColor2.copy(alpha = haloAlpha * 0.45f),
                                    1f to Color.Transparent,
                                ),
                                CircleShape,
                            ),
                    )
                    AsyncImage(
                        model = track.artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(144.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .border(
                                width = 1.dp,
                                brush = Brush.linearGradient(
                                    listOf(
                                        resolvedColor1.copy(alpha = 0.55f),
                                        resolvedColor2.copy(alpha = 0.35f),
                                    ),
                                ),
                                shape = RoundedCornerShape(24.dp),
                            ),
                    )
                }
                Spacer(Modifier.height(14.dp))
            }

            Text(
                text = track?.title ?: "Nothing playing",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.94f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 520.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = track?.artist.orEmpty(),
                fontSize = 14.sp,
                color = lerp(Color.White, resolvedColor1, 0.28f).copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 520.dp),
            )

            Spacer(Modifier.height(22.dp))
            AnimatedContent(
                targetState = lyricPair,
                transitionSpec = { fadeIn(tween(380)) togetherWith fadeOut(tween(380)) },
                label = "aodLyric",
            ) { (currentLine, nextLine) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.widthIn(max = 600.dp).height(76.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (!currentLine.isNullOrBlank()) {
                        Text(
                            text = currentLine,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            color = Color.White.copy(alpha = 0.90f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!nextLine.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = nextLine,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            color = Color.White.copy(alpha = 0.36f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (showControls && onPlayPause != null) {
                Spacer(Modifier.height(14.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(26.dp),
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
                    Surface(
                        onClick = onPlayPause,
                        shape = CircleShape,
                        color = resolvedColor1.copy(alpha = 0.20f),
                        modifier = Modifier
                            .size(56.dp)
                            .border(1.dp, resolvedColor1.copy(alpha = 0.45f), CircleShape),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White.copy(alpha = 0.95f),
                                modifier = Modifier.size(32.dp),
                            )
                        }
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

        // 3. Bottom bar: live AOD Style Switcher Pill + Tap-twice-to-exit hint
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 26.dp),
        ) {
            if (onChangeVisualizerMode != null) {
                Surface(
                    onClick = {
                        val idx = AOD_VISUALIZER_MODES.indexOf(visualizerMode)
                        val next = AOD_VISUALIZER_MODES[(idx + 1) % AOD_VISUALIZER_MODES.size]
                        onChangeVisualizerMode(next)
                    },
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.08f),
                    modifier = Modifier.border(
                        1.dp,
                        resolvedColor1.copy(alpha = if (visualizerMode == "off") 0.16f else 0.36f),
                        CircleShape,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = resolvedColor1.copy(alpha = 0.88f),
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Style: ${aodModeLabel(visualizerMode)}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp,
                            color = Color.White.copy(alpha = 0.72f),
                        )
                    }
                }
            }
            Text(
                text = if (exitArmed) "TAP AGAIN TO EXIT" else "TAP TWICE TO EXIT",
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                color = Color.White.copy(alpha = if (exitArmed) 0.78f else 0.26f),
            )
        }
    }
}

/**
 * Multi-layered luminous OLED edge visualizers for Always-on Display:
 * - "wave": glowing dual-harmonic silk/neon ribbons with inward ambient wash,
 *   wide outer bloom, vibrant sweeping gradient halo, and bright core.
 * - "bars": smooth glowing rounded equalizer bars along all four edges.
 * - "aurora": flowing multi-color corner & edge silk curtains.
 */
@Composable
private fun AodEdgeVisualizer(
    mode: String,
    glow: EdgeGlowConfig,
    isPlaying: Boolean,
    beat: Float,
    modifier: Modifier = Modifier,
) {
    if (mode != "bars" && mode != "wave" && mode != "aurora") return
    val transition = rememberInfiniteTransition(label = "aodVisualizer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4_800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "aodVisualizerPhase",
    )
    val slowPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "aodVisualizerSlowPhase",
    )

    val motionGain = when (glow.motion) {
        EdgeGlowMotion.BEAT -> if (isPlaying) 0.52f + beat * 0.92f else 0.28f
        EdgeGlowMotion.IDLE -> if (isPlaying) 0.78f + 0.20f * sin(slowPhase) else 0.35f
        EdgeGlowMotion.STATIC -> 0.72f
    }
    val intensity = glow.intensity.coerceIn(0.35f, 2.2f)
    val thicknessScale = glow.thickness.coerceIn(0.15f, 1f)

    Canvas(modifier) {
        val colorA = glow.color1
        val colorB = glow.color2
        when (mode) {
            "wave" -> drawGlowingWaves(
                colorA = colorA,
                colorB = colorB,
                phase = phase,
                slowPhase = slowPhase,
                motionGain = motionGain,
                intensity = intensity,
                thicknessScale = thicknessScale,
            )
            "bars" -> drawGlowingBars(
                colorA = colorA,
                colorB = colorB,
                phase = phase,
                slowPhase = slowPhase,
                motionGain = motionGain,
                intensity = intensity,
                thicknessScale = thicknessScale,
            )
            "aurora" -> drawAuroraFrame(
                colorA = colorA,
                colorB = colorB,
                phase = phase,
                slowPhase = slowPhase,
                motionGain = motionGain,
                intensity = intensity,
                thicknessScale = thicknessScale,
            )
        }
    }
}

private fun DrawScope.drawGlowingWaves(
    colorA: Color,
    colorB: Color,
    phase: Float,
    slowPhase: Float,
    motionGain: Float,
    intensity: Float,
    thicknessScale: Float,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    // Soft inward ambient curtain so the edges genuinely illuminate the OLED bezel.
    val washDepth = size.minDimension * (0.14f + thicknessScale * 0.14f)
    val washAlpha = (0.28f * intensity * motionGain).coerceIn(0.08f, 0.52f)
    drawRect(
        brush = Brush.verticalGradient(
            0f to colorA.copy(alpha = washAlpha),
            1f to Color.Transparent,
            startY = 0f,
            endY = washDepth,
        ),
    )
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Transparent,
            1f to colorB.copy(alpha = washAlpha),
            startY = h - washDepth,
            endY = h,
        ),
    )
    drawRect(
        brush = Brush.horizontalGradient(
            0f to lerp(colorA, colorB, 0.35f).copy(alpha = washAlpha * 0.85f),
            1f to Color.Transparent,
            startX = 0f,
            endX = washDepth * 0.85f,
        ),
    )
    drawRect(
        brush = Brush.horizontalGradient(
            0f to Color.Transparent,
            1f to lerp(colorB, colorA, 0.35f).copy(alpha = washAlpha * 0.85f),
            startX = w - washDepth * 0.85f,
            endX = w,
        ),
    )

    val inset = 14.dp.toPx() + thicknessScale * 14.dp.toPx()
    val maxAmp = (10.dp.toPx() + size.minDimension * 0.032f * thicknessScale) * motionGain * intensity.coerceAtMost(1.5f)
    val steps = 72

    fun buildEdgeWave(
        edge: Int, // 0=top, 1=bottom, 2=left, 3=right
        phaseShift: Float,
        freq1: Float,
        freq2: Float,
        ampScale: Float,
    ): Path {
        val path = Path()
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            // Taper amplitude gently at corners so the 4 luminous waves meet gracefully.
            val cornerEnvelope = sin(t * PI.toFloat()).coerceAtLeast(0.18f)
            val harmonic = sin(t * freq1 * (2f * PI).toFloat() + phase + phaseShift) * 0.65f +
                cos(t * freq2 * (2f * PI).toFloat() - slowPhase * 1.5f + phaseShift * 0.7f) * 0.35f
            val displacement = inset + harmonic * maxAmp * ampScale * cornerEnvelope
            val (x, y) = when (edge) {
                0 -> (t * w) to displacement.coerceIn(4f, h * 0.25f)
                1 -> (t * w) to (h - displacement).coerceIn(h * 0.75f, h - 4f)
                2 -> displacement.coerceIn(4f, w * 0.25f) to (t * h)
                else -> (w - displacement).coerceIn(w * 0.75f, w - 4f) to (t * h)
            }
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return path
    }

    val coreWhiteA = lerp(colorA, Color.White, 0.42f)
    val coreWhiteB = lerp(colorB, Color.White, 0.42f)

    val horizBrush1 = Brush.horizontalGradient(listOf(colorA, colorB, colorA))
    val horizBrush2 = Brush.horizontalGradient(listOf(colorB, colorA, colorB))
    val vertBrush1 = Brush.verticalGradient(listOf(colorA, colorB, colorA))
    val vertBrush2 = Brush.verticalGradient(listOf(colorB, colorA, colorB))

    val outerStroke = Stroke(width = (18.dp.toPx() * thicknessScale.coerceAtLeast(0.5f)), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val midStroke = Stroke(width = (8.dp.toPx() * thicknessScale.coerceAtLeast(0.55f)), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val coreStroke = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val secondaryCoreStroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

    val outerAlpha = (0.24f * intensity * motionGain).coerceIn(0.08f, 0.45f)
    val midAlpha = (0.48f * intensity * motionGain).coerceIn(0.18f, 0.78f)
    val coreAlpha = (0.92f * intensity.coerceAtMost(1.3f)).coerceIn(0.45f, 1f)

    for (edge in 0..3) {
        val isHorizontal = edge == 0 || edge == 1
        val primaryBrush = if (isHorizontal) horizBrush1 else vertBrush1
        val secondaryBrush = if (isHorizontal) horizBrush2 else vertBrush2

        // Ribbon 1 (primary luminous wave)
        val p1 = buildEdgeWave(edge = edge, phaseShift = edge * 0.9f, freq1 = 2f, freq2 = 3f, ampScale = 1f)
        drawPath(p1, brush = primaryBrush, alpha = outerAlpha, style = outerStroke, blendMode = BlendMode.Screen)
        drawPath(p1, brush = primaryBrush, alpha = midAlpha, style = midStroke, blendMode = BlendMode.Screen)
        drawPath(
            p1,
            brush = if (isHorizontal) {
                Brush.horizontalGradient(listOf(coreWhiteA, coreWhiteB, coreWhiteA))
            } else {
                Brush.verticalGradient(listOf(coreWhiteA, coreWhiteB, coreWhiteA))
            },
            alpha = coreAlpha,
            style = coreStroke,
        )

        // Ribbon 2 (intertwining harmonic wave)
        val p2 = buildEdgeWave(edge = edge, phaseShift = edge * 1.4f + 2.1f, freq1 = 3f, freq2 = 2f, ampScale = 0.72f)
        drawPath(p2, brush = secondaryBrush, alpha = outerAlpha * 0.75f, style = outerStroke, blendMode = BlendMode.Screen)
        drawPath(p2, brush = secondaryBrush, alpha = midAlpha * 0.65f, style = midStroke, blendMode = BlendMode.Screen)
        drawPath(
            p2,
            brush = if (isHorizontal) {
                Brush.horizontalGradient(listOf(coreWhiteB, coreWhiteA, coreWhiteB))
            } else {
                Brush.verticalGradient(listOf(coreWhiteB, coreWhiteA, coreWhiteB))
            },
            alpha = coreAlpha * 0.75f,
            style = secondaryCoreStroke,
        )
    }
}

private fun DrawScope.drawGlowingBars(
    colorA: Color,
    colorB: Color,
    phase: Float,
    slowPhase: Float,
    motionGain: Float,
    intensity: Float,
    thicknessScale: Float,
) {
    val maxLen = size.minDimension * (0.065f + 0.065f * thicknessScale) * intensity.coerceAtMost(1.6f)
    val horizSegments = 26
    val vertSegments = 36
    val coreWidth = 3.2.dp.toPx()
    val haloWidth = 10.dp.toPx()
    val edgePad = 3.dp.toPx()

    fun drawGlowingBar(start: Offset, end: Offset, c: Color, alpha: Float) {
        drawLine(c.copy(alpha = alpha * 0.28f), start, end, haloWidth, cap = StrokeCap.Round, blendMode = BlendMode.Screen)
        drawLine(lerp(c, Color.White, 0.30f).copy(alpha = alpha), start, end, coreWidth, cap = StrokeCap.Round)
    }

    for (i in 0 until horizSegments) {
        val frac = i / (horizSegments - 1f)
        val color = lerp(colorA, colorB, frac)
        val pulse = 0.20f + 0.80f * ((sin(phase * 2f + i * 0.58f) * 0.6f + cos(slowPhase * 2f - i * 0.35f) * 0.4f + 1f) / 2f)
        val len = (maxLen * pulse * motionGain).coerceAtLeast(4.dp.toPx())
        val alpha = (0.62f * intensity * (0.55f + 0.45f * pulse)).coerceIn(0.22f, 0.96f)
        val x = size.width * (i + 0.5f) / horizSegments
        drawGlowingBar(Offset(x, edgePad), Offset(x, edgePad + len), color, alpha)
        drawGlowingBar(Offset(x, size.height - edgePad), Offset(x, size.height - edgePad - len * 0.9f), lerp(colorB, colorA, frac), alpha)
    }

    for (i in 0 until vertSegments) {
        val frac = i / (vertSegments - 1f)
        val color = lerp(colorA, colorB, frac)
        val pulse = 0.18f + 0.82f * ((sin(phase * 1.8f + i * 0.46f + 1.2f) * 0.6f + cos(slowPhase * 2.2f + i * 0.28f) * 0.4f + 1f) / 2f)
        val len = (maxLen * 0.82f * pulse * motionGain).coerceAtLeast(4.dp.toPx())
        val alpha = (0.55f * intensity * (0.55f + 0.45f * pulse)).coerceIn(0.18f, 0.90f)
        val y = size.height * (i + 0.5f) / vertSegments
        drawGlowingBar(Offset(edgePad, y), Offset(edgePad + len, y), color, alpha)
        drawGlowingBar(Offset(size.width - edgePad, y), Offset(size.width - edgePad - len, y), lerp(colorB, colorA, frac), alpha)
    }
}

private fun DrawScope.drawAuroraFrame(
    colorA: Color,
    colorB: Color,
    phase: Float,
    slowPhase: Float,
    motionGain: Float,
    intensity: Float,
    thicknessScale: Float,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val radius = size.minDimension * (0.52f + thicknessScale * 0.30f)
    val a1 = (0.58f * intensity * motionGain).coerceIn(0.18f, 0.85f)
    val a2 = (0.50f * intensity * motionGain).coerceIn(0.16f, 0.78f)
    val colorC = lerp(colorA, colorB, 0.5f)

    // Four drifting corner aurora pools that sweep along the bezel perimeter.
    val p1 = Offset(w * (0.20f + 0.22f * sin(slowPhase)), h * 0.04f)
    val p2 = Offset(w * (0.80f - 0.22f * cos(slowPhase)), h * 0.96f)
    val p3 = Offset(w * 0.03f, h * (0.55f + 0.24f * cos(phase * 0.7f)))
    val p4 = Offset(w * 0.97f, h * (0.45f - 0.24f * sin(phase * 0.7f)))

    drawCircle(
        brush = Brush.radialGradient(listOf(colorA.copy(alpha = a1), Color.Transparent), center = p1, radius = radius),
        radius = radius,
        center = p1,
        blendMode = BlendMode.Screen,
    )
    drawCircle(
        brush = Brush.radialGradient(listOf(colorB.copy(alpha = a1), Color.Transparent), center = p2, radius = radius),
        radius = radius,
        center = p2,
        blendMode = BlendMode.Screen,
    )
    drawCircle(
        brush = Brush.radialGradient(listOf(colorC.copy(alpha = a2), Color.Transparent), center = p3, radius = radius * 0.85f),
        radius = radius * 0.85f,
        center = p3,
        blendMode = BlendMode.Screen,
    )
    drawCircle(
        brush = Brush.radialGradient(listOf(lerp(colorB, Color.White, 0.2f).copy(alpha = a2 * 0.85f), Color.Transparent), center = p4, radius = radius * 0.85f),
        radius = radius * 0.85f,
        center = p4,
        blendMode = BlendMode.Screen,
    )
}
