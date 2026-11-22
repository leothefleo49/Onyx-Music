package com.lastwave.app.ui.player

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.lastwave.app.data.local.EdgeGlowMode
import com.lastwave.app.data.local.EdgeGlowMotion
import com.lastwave.app.playback.rhythm.FluidRhythmEngine
import com.lastwave.app.ui.common.LocalAmbientAnimationsActive
import kotlinx.coroutines.isActive
import kotlin.math.hypot
import kotlin.math.max

/** Everything the ambient edge glow needs, resolved from settings + artwork. */
internal data class EdgeGlowConfig(
    val enabled: Boolean = false,
    val mode: EdgeGlowMode = EdgeGlowMode.ART,
    val motion: EdgeGlowMotion = EdgeGlowMotion.BEAT,
    /** 0.2 .. 2.0 */
    val intensity: Float = 1f,
    /** 0 .. 1 */
    val thickness: Float = 0.38f,
    val color1: Color = Color(0xFF8B5CF6),
    val color2: Color = Color(0xFF22D3EE),
)

/**
 * Lumn-style ambient screen-border glow. Two layered radial gradients leave
 * the centre of the screen untouched and pool light in the corners and along
 * the edges; the bass envelope from the playback sink pulses it in time with
 * the music, or a slow breath when motion is Idle.
 *
 * Drawn as a sibling above the artwork but below controls, so it never
 * intercepts touches (Canvas has no pointer input).
 */
@Composable
internal fun AmbientEdgeGlow(
    config: EdgeGlowConfig,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!config.enabled || config.mode == EdgeGlowMode.OFF) return
    val animActive = LocalAmbientAnimationsActive.current && rememberPlayerWindowActive()
    val beatsWanted = config.motion == EdgeGlowMotion.BEAT && animActive
    // Only hold the analyser while the glow is on screen and needs beats.
    androidx.compose.runtime.DisposableEffect(beatsWanted) {
        if (beatsWanted) FluidRhythmEngine.acquire()
        onDispose { if (beatsWanted) FluidRhythmEngine.release() }
    }
    var beat by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(beatsWanted, isPlaying) {
        if (!beatsWanted) {
            beat = 0f
            return@LaunchedEffect
        }
        var last = 0L
        while (isActive) {
            androidx.compose.runtime.withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0.001f, 0.1f)
                last = now
                beat = FluidRhythmEngine.tick(dt, isPlaying)
            }
        }
    }
    val breath by rememberInfiniteTransition(label = "edgeGlowBreath").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4_200), RepeatMode.Reverse),
        label = "edgeGlowBreathValue",
    )
    val pulse = when (config.motion) {
        EdgeGlowMotion.BEAT -> if (isPlaying) 0.35f + beat * 0.65f else 0.22f
        EdgeGlowMotion.IDLE -> if (animActive) breath else 0.8f
        EdgeGlowMotion.STATIC -> 0.75f
    }
    Canvas(modifier = modifier) {
        drawEdgeGlow(
            color1 = config.color1,
            color2 = config.color2,
            intensity = config.intensity,
            thickness = config.thickness,
            pulse = pulse,
        )
    }
}

/**
 * Draws the two glow layers. Split out so the AOD and the player can share
 * exactly the same look and so the gradient maths stays testable by eye.
 */
internal fun DrawScope.drawEdgeGlow(
    color1: Color,
    color2: Color,
    intensity: Float,
    thickness: Float,
    pulse: Float,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val center = androidx.compose.ui.geometry.Offset(w / 2f, h / 2f)
    val cornerRadius = hypot(w, h) / 2f
    val safeIntensity = intensity.coerceIn(0.1f, 2.5f)
    val safeThickness = thickness.coerceIn(0.05f, 1f)

    // How far the glow reaches inward from the edge.
    val inner = (1f - (0.10f + safeThickness * 0.55f)).coerceIn(0.05f, 0.9f)

    // Wide, soft primary wash that pools in the corners.
    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                inner * 0.55f to Color.Transparent,
                inner to Color.Transparent,
                (1f - (1f - inner) * 0.45f) to color1.copy(alpha = 0.30f * safeIntensity * pulse),
                1f to color1.copy(alpha = 0.85f * safeIntensity * pulse),
            ),
            center = center,
            radius = cornerRadius,
        ),
    )
    // Tighter secondary rim so two colours blend where they meet.
    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                (inner + (1f - inner) * 0.35f) to Color.Transparent,
                (inner + (1f - inner) * 0.75f) to color2.copy(alpha = 0.24f * safeIntensity * pulse),
                1f to color2.copy(alpha = 0.65f * safeIntensity * pulse),
            ),
            center = center,
            radius = cornerRadius * 0.98f,
        ),
    )
    // A hairline of pure colour exactly on the bezel edge reads as the
    // "light spilling from the frame" part of the effect.
    val edgeAlpha = (0.18f * safeIntensity * pulse).coerceIn(0f, 0.6f)
    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.985f to Color.Transparent,
                1f to color1.copy(alpha = edgeAlpha),
            ),
            center = center,
            radius = cornerRadius,
        ),
    )
}

/** Utility so callers can keep gradients from washing out black content. */
internal fun glowAlphaFor(beat: Float, base: Float = 0.28f): Float =
    (base + beat.coerceIn(0f, 1f) * 0.42f) * max(0.2f, 1f)

/** Parses a stored "#RRGGBB" colour, falling back when the string is invalid. */
internal fun parseHexColor(hex: String, fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex.trim())) }.getOrDefault(fallback)
