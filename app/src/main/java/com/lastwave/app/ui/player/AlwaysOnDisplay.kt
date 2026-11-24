package com.lastwave.app.ui.player

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.lastwave.app.data.artwork.ArtworkNormalizer
import com.lastwave.app.data.local.EdgeGlowMode
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.common.ArtworkImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Full Always-On Display ("Full AOD / Vibe Mode"): true OLED black canvas
 * framed by the Lumn-style bezel visualizer ([AmbientEdgeGlow]), crisp upscaled
 * album art via [ArtworkImage], live synced lyrics, OLED burn-in pixel shift,
 * optional transport controls, live style switcher, and a 1-tap button to drop
 * back into Half AOD (keep-awake player without locking navigation).
 *
 * Note: `keepScreenOn` is managed centrally by [PlayerHost] so entering/exiting
 * Full AOD never clobbers Half AOD or Keep Lyrics Awake.
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
    onSwitchToHalfAod: (() -> Unit)? = null,
    onPlayPause: (() -> Unit)? = null,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    onChangeVisualizerMode: ((String) -> Unit)? = null,
    visualizerMode: String = "glow",
    showControls: Boolean = true,
    showArtwork: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Extract 3 vibrant cover-art colors when EdgeGlowMode.ART is active.
    // Uses a dedicated memoryCacheKey so the 128px software palette bitmap NEVER
    // overwrites the full-res artwork entry in Coil's shared cache.
    var artPrimary by remember(track?.videoId, track?.artworkUrl, track?.title) { mutableStateOf<Color?>(null) }
    var artSecondary by remember(track?.videoId, track?.artworkUrl, track?.title) { mutableStateOf<Color?>(null) }
    var artTertiary by remember(track?.videoId, track?.artworkUrl, track?.title) { mutableStateOf<Color?>(null) }
    LaunchedEffect(track?.artworkUrl, glow.mode) {
        if (glow.mode != EdgeGlowMode.ART) return@LaunchedEffect
        val url = ArtworkNormalizer.upscaleYoutubeArtwork(track?.artworkUrl)?.takeIf { it.isNotBlank() }
            ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .memoryCacheKey("$url#aod_palette_128")
                    .allowHardware(false)
                    .size(128)
                    .build()
                val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                    ?: return@runCatching
                val palette = Palette.from(bitmap).clearFilters().generate()
                val p = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch
                val s = palette.lightVibrantSwatch?.takeIf { it != p }
                    ?: palette.darkVibrantSwatch?.takeIf { it != p }
                    ?: palette.mutedSwatch
                    ?: palette.dominantSwatch
                val t = palette.darkVibrantSwatch?.takeIf { it != p && it != s }
                    ?: palette.mutedSwatch?.takeIf { it != p && it != s }
                    ?: palette.lightMutedSwatch
                    ?: s
                artPrimary = p?.let { Color(it.rgb) }
                artSecondary = s?.let { Color(it.rgb) }
                artTertiary = t?.let { Color(it.rgb) }
            }
        }
    }

    val targetColor1 = when (glow.mode) {
        EdgeGlowMode.ART -> artPrimary ?: glow.color1
        else -> glow.color1
    }
    val targetColor2 = when (glow.mode) {
        EdgeGlowMode.ART -> artSecondary ?: glow.color2
        EdgeGlowMode.CUSTOM -> lerp(glow.color1, Color.White, 0.30f)
        else -> glow.color2
    }
    val targetColor3 = when (glow.mode) {
        EdgeGlowMode.ART -> artTertiary ?: lerp(targetColor1, targetColor2, 0.5f)
        EdgeGlowMode.CUSTOM -> lerp(glow.color1, targetColor2, 0.5f)
        else -> lerp(glow.color1, glow.color2, 0.5f)
    }
    val resolvedColor1 by animateColorAsState(targetColor1, tween(800), label = "aodColor1")
    val resolvedColor2 by animateColorAsState(targetColor2, tween(800), label = "aodColor2")
    val resolvedColor3 by animateColorAsState(targetColor3, tween(800), label = "aodColor3")

    // Effective glow config — never short-circuit when user opens Full AOD with visualizer != "off".
    val effectiveMode = if (glow.mode == EdgeGlowMode.OFF) EdgeGlowMode.ART else glow.mode
    val resolvedGlow = remember(glow, resolvedColor1, resolvedColor2, resolvedColor3, visualizerMode, effectiveMode) {
        glow.copy(
            enabled = visualizerMode != "off",
            mode = effectiveMode,
            style = visualizerMode,
            intensity = (glow.intensity * 1.10f).coerceIn(0.35f, 2.2f),
            color1 = resolvedColor1,
            color2 = resolvedColor2,
            color3 = resolvedColor3,
        )
    }

    // Subtle OLED burn-in drift: shifts content gently by +-5px every 45 seconds.
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

    // Live synced current + upcoming lyric line (driven by live positionMs from PlayerHost).
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
        // 1. Lumn bezel ambient glow / spectrum visualizers (zero root recomposition).
        AmbientEdgeGlow(
            config = resolvedGlow,
            isPlaying = isPlaying,
            modifier = Modifier.fillMaxSize(),
        )

        // 2. Centered OLED-shifted content
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .offset { driftOffset }
                .padding(horizontal = 32.dp, vertical = 72.dp),
        ) {
            Text(
                text = timeText,
                fontSize = 86.sp,
                fontWeight = FontWeight.ExtraLight,
                color = Color.White.copy(alpha = 0.96f),
                letterSpacing = 2.sp,
                maxLines = 1,
            )
            Text(
                text = dateText.uppercase(Locale.getDefault()),
                fontSize = 12.sp,
                letterSpacing = 3.5.sp,
                color = Color.White.copy(alpha = 0.50f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(26.dp))

            if (showArtwork && track != null) {
                // Uses shared ArtworkImage (upscaled YouTube/high-res resolved artwork + ContentScale.Crop)
                // inside a fixed 156dp frame so no track ever shrinks or renders tiny.
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(204.dp)
                            .background(
                                Brush.radialGradient(
                                    0f to resolvedColor1.copy(alpha = if (isPlaying) 0.36f else 0.16f),
                                    0.55f to resolvedColor2.copy(alpha = if (isPlaying) 0.16f else 0.06f),
                                    1f to Color.Transparent,
                                ),
                                CircleShape,
                            ),
                    )
                    ArtworkImage(
                        name = track.title,
                        artist = track.artist,
                        embeddedUrl = ArtworkNormalizer.upscaleYoutubeArtwork(track.artworkUrl),
                        fallbackIcon = Icons.Filled.MusicNote,
                        decodeSizePx = 512,
                        modifier = Modifier
                            .size(156.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .border(
                                width = 1.dp,
                                brush = Brush.linearGradient(
                                    listOf(
                                        resolvedColor1.copy(alpha = 0.55f),
                                        resolvedColor2.copy(alpha = 0.35f),
                                    ),
                                ),
                                shape = RoundedCornerShape(26.dp),
                            ),
                    )
                }
                Spacer(Modifier.height(14.dp))
            }

            Text(
                text = track?.title ?: "Nothing playing",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.95f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 520.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = track?.artist.orEmpty(),
                fontSize = 14.sp,
                color = lerp(Color.White, resolvedColor1, 0.28f).copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 520.dp),
            )

            if (showLyrics) {
                Spacer(Modifier.height(20.dp))
                AnimatedContent(
                    targetState = lyricPair,
                    transitionSpec = { fadeIn(tween(340)) togetherWith fadeOut(tween(340)) },
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
                                color = Color.White.copy(alpha = 0.92f),
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
                                color = Color.White.copy(alpha = 0.38f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (showControls && onPlayPause != null) {
                Spacer(Modifier.height(14.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onPrevious != null) {
                        IconButton(onClick = onPrevious) {
                            Icon(
                                Icons.Filled.SkipPrevious,
                                contentDescription = "Previous",
                                tint = Color.White.copy(alpha = 0.82f),
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                    Surface(
                        onClick = onPlayPause,
                        shape = CircleShape,
                        color = resolvedColor1.copy(alpha = 0.22f),
                        modifier = Modifier
                            .size(56.dp)
                            .border(1.dp, resolvedColor1.copy(alpha = 0.50f), CircleShape),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White.copy(alpha = 0.96f),
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                    if (onNext != null) {
                        IconButton(onClick = onNext) {
                            Icon(
                                Icons.Filled.SkipNext,
                                contentDescription = "Next",
                                tint = Color.White.copy(alpha = 0.82f),
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                }
            }
        }

        // 3. Bottom controls: Style cycler + "Half AOD" toggle + Tap-twice-to-exit hint
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = 18.dp, start = 16.dp, end = 16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onChangeVisualizerMode != null) {
                    Surface(
                        onClick = {
                            val idx = VIBE_STYLE_MODES.indexOf(visualizerMode)
                            val next = VIBE_STYLE_MODES[(idx + 1) % VIBE_STYLE_MODES.size]
                            onChangeVisualizerMode(next)
                        },
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.border(
                            1.dp,
                            resolvedColor1.copy(alpha = if (visualizerMode == "off") 0.16f else 0.42f),
                            CircleShape,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                tint = resolvedColor1.copy(alpha = 0.92f),
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = vibeStyleLabel(visualizerMode),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.82f),
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (onSwitchToHalfAod != null) {
                    Surface(
                        onClick = onSwitchToHalfAod,
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FullscreenExit,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.80f),
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Half AOD",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.82f),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Text(
                text = if (exitArmed) "TAP AGAIN TO EXIT" else "TAP TWICE TO EXIT",
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                color = Color.White.copy(alpha = if (exitArmed) 0.82f else 0.30f),
            )
        }
    }
}

/**
 * Compact floating pill shown when **Half AOD** (Keep-Awake Vibe Mode) is active:
 * the phone won't auto-sleep, you can still manually press the power button or
 * switch tabs/apps freely, and you can jump straight into **Full AOD** in one tap.
 */
@Composable
internal fun HalfAodStatusPill(
    accentColor: Color,
    onEnterFullAod: () -> Unit,
    onTurnOff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = Color(0xCC121216),
        contentColor = Color.White,
        shadowElevation = 8.dp,
        modifier = modifier.border(1.dp, accentColor.copy(alpha = 0.55f), CircleShape),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = "Half AOD",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.92f),
                maxLines = 1,
            )
            Surface(
                onClick = onEnterFullAod,
                shape = CircleShape,
                color = accentColor.copy(alpha = 0.24f),
                modifier = Modifier.border(1.dp, accentColor.copy(alpha = 0.50f), CircleShape),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Fullscreen,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = "Full AOD",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onTurnOff),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Turn off Half AOD",
                    tint = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}
