package com.lastwave.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.lastwave.app.data.local.EdgeGlowEdgeMask
import com.lastwave.app.data.local.EdgeGlowMode
import com.lastwave.app.data.local.EdgeGlowMotion
import com.lastwave.app.playback.rhythm.FluidRhythmEngine
import com.lastwave.app.ui.common.LocalAmbientAnimationsActive
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Everything the Lumn-style ambient edge glow & visualizers need, resolved from settings + artwork. */
internal data class EdgeGlowConfig(
    val enabled: Boolean = false,
    val mode: EdgeGlowMode = EdgeGlowMode.ART,
    val motion: EdgeGlowMotion = EdgeGlowMotion.BEAT,
    /** Visual style: "glow" (Lumn Glow), "wave" (Smooth Edge Curve), "bars" (Edge EQ Bars), "aurora" (Aurora Silk), "off". */
    val style: String = "glow",
    /** Bitmask of active edges ([EdgeGlowEdgeMask]: TOP=1, BOTTOM=2, LEFT=4, RIGHT=8). */
    val edgeMask: Int = EdgeGlowEdgeMask.ALL,
    /** 0.2 .. 2.0 */
    val intensity: Float = 1f,
    /** 0.05 .. 1.0 */
    val thickness: Float = 0.42f,
    val color1: Color = Color(0xFF8B5CF6),
    val color2: Color = Color(0xFF22D3EE),
    val color3: Color = Color(0xFFEC4899),
)

internal val VIBE_STYLE_MODES = listOf("glow", "wave", "bars", "aurora", "off")

internal fun vibeStyleLabel(style: String): String = when (style) {
    "glow" -> "Lumn Glow"
    "wave" -> "Smooth Curve"
    "bars" -> "Edge EQ"
    "aurora" -> "Aurora Silk"
    else -> "Off"
}

/**
 * Lumn-faithful ambient screen-border lighting & audio-reactive bezel visualizer.
 *
 * Wraps every selected physical screen edge (Top / Bottom / Left / Right) in
 * velvety exponential-falloff multi-color light that orbits organically around
 * the bezel and reacts in real time to the 8-band PCM spectrum from
 * [FluidRhythmEngine]. All frame reads happen strictly inside the [Canvas] draw
 * phase so the surrounding Compose tree never recomposes per frame.
 */
@Composable
internal fun AmbientEdgeGlow(
    config: EdgeGlowConfig,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!config.enabled || config.mode == EdgeGlowMode.OFF || config.style == "off") return
    val animActive = LocalAmbientAnimationsActive.current && rememberPlayerWindowActive()
    val needsLiveAudio = animActive && isPlaying && (
        config.motion == EdgeGlowMotion.BEAT ||
            config.style == "bars" ||
            config.style == "wave"
        )
    val needsFrameLoop = animActive && config.motion != EdgeGlowMotion.STATIC

    DisposableEffect(needsLiveAudio) {
        if (needsLiveAudio) FluidRhythmEngine.acquire()
        onDispose { if (needsLiveAudio) FluidRhythmEngine.release() }
    }

    // Read only inside Canvas draw lambda — zero per-frame composable recomposition.
    val frameClockNanos = remember { mutableLongStateOf(0L) }
    val phaseState = remember { FloatArray(2) } // [fastPhase, slowPhase]

    LaunchedEffect(needsFrameLoop, needsLiveAudio, isPlaying, config.motion) {
        if (!needsFrameLoop) return@LaunchedEffect
        var lastNanos = 0L
        while (isActive) {
            withFrameNanos { now ->
                val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0.001f, 0.1f)
                lastNanos = now
                val beat = if (needsLiveAudio) FluidRhythmEngine.tick(dt, isPlaying) else 0f
                val speedMult = when (config.motion) {
                    EdgeGlowMotion.BEAT -> if (isPlaying) 0.65f + beat * 1.15f else 0.22f
                    EdgeGlowMotion.FLOW -> if (isPlaying) 0.95f else 0.35f
                    EdgeGlowMotion.IDLE -> 0.38f
                    EdgeGlowMotion.STATIC -> 0f
                }
                phaseState[0] = (phaseState[0] + dt * 1.25f * speedMult) % (2f * PI.toFloat())
                phaseState[1] = (phaseState[1] + dt * 0.48f * max(0.35f, speedMult)) % (2f * PI.toFloat())
                frameClockNanos.longValue = now
            }
        }
    }

    // Reusable paths so drawing allocates zero objects per frame.
    val reusablePaths = remember { List(4) { Path() } }

    Canvas(modifier = modifier) {
        @Suppress("UNUSED_VARIABLE")
        val tick = frameClockNanos.longValue
        val beat = if (needsLiveAudio && isPlaying) FluidRhythmEngine.currentRhythmValue else 0f
        val spectrum = FluidRhythmEngine.currentSpectrum
        val phase = phaseState[0]
        val slowPhase = phaseState[1]

        val pulse = when (config.motion) {
            EdgeGlowMotion.BEAT -> if (isPlaying) 0.48f + beat * 0.62f else 0.34f
            EdgeGlowMotion.FLOW -> if (isPlaying) 0.72f + 0.14f * sin(slowPhase) + beat * 0.20f else 0.45f
            EdgeGlowMotion.IDLE -> 0.56f + 0.26f * (0.5f + 0.5f * sin(slowPhase * 1.4f))
            EdgeGlowMotion.STATIC -> 0.78f
        }

        val c1 = config.color1
        val c2 = if (config.mode == EdgeGlowMode.CUSTOM) lerp(c1, Color.White, 0.28f) else config.color2
        val c3 = if (config.mode == EdgeGlowMode.CUSTOM) lerp(c1, c2, 0.5f) else config.color3
        val edges = if (config.edgeMask and EdgeGlowEdgeMask.ALL == 0) EdgeGlowEdgeMask.ALL else config.edgeMask

        when (config.style) {
            "wave" -> drawLumnSmoothCurve(
                c1 = c1, c2 = c2, c3 = c3,
                edges = edges,
                intensity = config.intensity,
                thickness = config.thickness,
                pulse = pulse,
                beat = beat,
                spectrum = spectrum,
                isPlaying = isPlaying,
                phase = phase,
                slowPhase = slowPhase,
                paths = reusablePaths,
            )
            "bars" -> drawLumnEdgeBars(
                c1 = c1, c2 = c2, c3 = c3,
                edges = edges,
                intensity = config.intensity,
                thickness = config.thickness,
                pulse = pulse,
                spectrum = spectrum,
                isPlaying = isPlaying,
                phase = phase,
                slowPhase = slowPhase,
            )
            "aurora" -> drawLumnAuroraSilk(
                c1 = c1, c2 = c2, c3 = c3,
                edges = edges,
                intensity = config.intensity,
                thickness = config.thickness,
                pulse = pulse,
                beat = beat,
                phase = phase,
                slowPhase = slowPhase,
            )
            else -> drawLumnAmbientBorder(
                c1 = c1, c2 = c2, c3 = c3,
                edges = edges,
                intensity = config.intensity,
                thickness = config.thickness,
                pulse = pulse,
                beat = beat,
                spectrum = spectrum,
                phase = phase,
                slowPhase = slowPhase,
            )
        }
    }
}

/**
 * Signature Lumn (`trylumn.xyz`) ambient screen-border glow:
 *  1. Soft exponential-falloff multi-stop bezel bands along every enabled edge
 *     (covers tall 20:9 side bezels seamlessly, never pooling only in corners).
 *  2. Six orbiting additive light plumes hugging the physical screen perimeter
 *     that blend album-art colors organically and breathe with live bass/mids.
 *  3. Delicate luminous outer bezel rim following the display edges.
 */
internal fun DrawScope.drawLumnAmbientBorder(
    c1: Color,
    c2: Color,
    c3: Color,
    edges: Int,
    intensity: Float,
    thickness: Float,
    pulse: Float,
    beat: Float,
    spectrum: FloatArray,
    phase: Float,
    slowPhase: Float,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    val safeInt = intensity.coerceIn(0.2f, 2.2f)
    val safeThick = thickness.coerceIn(0.05f, 1f)
    val minDim = size.minDimension

    val baseDepth = minDim * (0.09f + safeThick * 0.24f) * (0.90f + beat * 0.26f)
    val washPeakAlpha = (0.48f * safeInt * pulse).coerceIn(0.08f, 0.86f)

    // Smoothly shift color pairings over time so the border flows like liquid light.
    val mixA = 0.5f + 0.5f * sin(slowPhase)
    val mixB = 0.5f + 0.5f * cos(slowPhase * 0.85f + 1.2f)
    val topColor = lerp(c1, c2, mixA)
    val bottomColor = lerp(c2, c3, mixB)
    val leftColor = lerp(c3, c1, 1f - mixA)
    val rightColor = lerp(c1, c3, mixB)

    // 1. Exponential-falloff ambient bands per enabled edge (zero hard inner line).
    if ((edges and EdgeGlowEdgeMask.TOP) != 0) {
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.00f to topColor.copy(alpha = washPeakAlpha),
                    0.20f to topColor.copy(alpha = washPeakAlpha * 0.56f),
                    0.50f to topColor.copy(alpha = washPeakAlpha * 0.19f),
                    0.82f to topColor.copy(alpha = washPeakAlpha * 0.04f),
                    1.00f to Color.Transparent,
                ),
                startY = 0f,
                endY = baseDepth,
            ),
            topLeft = Offset.Zero,
            size = Size(w, baseDepth),
            blendMode = BlendMode.Screen,
        )
    }
    if ((edges and EdgeGlowEdgeMask.BOTTOM) != 0) {
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.00f to Color.Transparent,
                    0.18f to bottomColor.copy(alpha = washPeakAlpha * 0.04f),
                    0.50f to bottomColor.copy(alpha = washPeakAlpha * 0.19f),
                    0.80f to bottomColor.copy(alpha = washPeakAlpha * 0.56f),
                    1.00f to bottomColor.copy(alpha = washPeakAlpha),
                ),
                startY = h - baseDepth,
                endY = h,
            ),
            topLeft = Offset(0f, h - baseDepth),
            size = Size(w, baseDepth),
            blendMode = BlendMode.Screen,
        )
    }
    if ((edges and EdgeGlowEdgeMask.LEFT) != 0) {
        val sideDepth = baseDepth * 0.88f
        drawRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.00f to leftColor.copy(alpha = washPeakAlpha * 0.94f),
                    0.22f to leftColor.copy(alpha = washPeakAlpha * 0.52f),
                    0.52f to leftColor.copy(alpha = washPeakAlpha * 0.17f),
                    0.82f to leftColor.copy(alpha = washPeakAlpha * 0.03f),
                    1.00f to Color.Transparent,
                ),
                startX = 0f,
                endX = sideDepth,
            ),
            topLeft = Offset.Zero,
            size = Size(sideDepth, h),
            blendMode = BlendMode.Screen,
        )
    }
    if ((edges and EdgeGlowEdgeMask.RIGHT) != 0) {
        val sideDepth = baseDepth * 0.88f
        drawRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.00f to Color.Transparent,
                    0.18f to rightColor.copy(alpha = washPeakAlpha * 0.03f),
                    0.48f to rightColor.copy(alpha = washPeakAlpha * 0.17f),
                    0.78f to rightColor.copy(alpha = washPeakAlpha * 0.52f),
                    1.00f to rightColor.copy(alpha = washPeakAlpha * 0.94f),
                ),
                startX = w - sideDepth,
                endX = w,
            ),
            topLeft = Offset(w - sideDepth, 0f),
            size = Size(sideDepth, h),
            blendMode = BlendMode.Screen,
        )
    }

    // 2. Orbiting luminous bezel plumes that glide along the enabled edges (Lumn's living glow).
    val orbColors = arrayOf(c1, c2, c3, lerp(c1, c2, 0.5f), lerp(c2, c3, 0.5f), lerp(c3, c1, 0.5f))
    val orbRadiusBase = baseDepth * 1.45f
    val orbAlphaBase = (0.42f * safeInt * pulse).coerceIn(0.08f, 0.78f)
    val perimeter = 2f * (w + h)

    for (i in 0 until 6) {
        val bandEnergy = if (spectrum.size >= 8) spectrum[(i * 7) % 8] else beat
        val progress = ((slowPhase / (2f * PI.toFloat())) + i / 6f) % 1f
        val d = progress * perimeter
        val (ox, oy, edgeFlag) = when {
            d < w -> Triple(d, 0f, EdgeGlowEdgeMask.TOP)
            d < w + h -> Triple(w, d - w, EdgeGlowEdgeMask.RIGHT)
            d < 2f * w + h -> Triple(w - (d - w - h), h, EdgeGlowEdgeMask.BOTTOM)
            else -> Triple(0f, h - (d - 2f * w - h), EdgeGlowEdgeMask.LEFT)
        }
        if ((edges and edgeFlag) == 0) continue
        val radius = orbRadiusBase * (0.85f + 0.45f * bandEnergy + 0.15f * sin(phase + i * 1.1f))
        val orbColor = orbColors[i]
        val alpha = (orbAlphaBase * (0.72f + 0.42f * bandEnergy)).coerceIn(0.06f, 0.82f)
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to orbColor.copy(alpha = alpha),
                    0.35f to orbColor.copy(alpha = alpha * 0.44f),
                    0.70f to orbColor.copy(alpha = alpha * 0.11f),
                    1.00f to Color.Transparent,
                ),
                center = Offset(ox, oy),
                radius = radius.coerceAtLeast(16f),
            ),
            radius = radius.coerceAtLeast(16f),
            center = Offset(ox, oy),
            blendMode = BlendMode.Screen,
        )
    }

    // 3. Soft bezel edge rim light (subtle crisp halo right at the glass edge).
    val rimAlpha = (0.36f * safeInt * pulse).coerceIn(0.08f, 0.75f)
    val rimWidth = (1.6f + safeThick * 3.0f).dp.toPx()
    if (edges == EdgeGlowEdgeMask.ALL) {
        val cornerPx = 28.dp.toPx()
        drawRoundRect(
            brush = Brush.sweepGradient(
                colorStops = arrayOf(
                    0.00f to topColor.copy(alpha = rimAlpha),
                    0.25f to rightColor.copy(alpha = rimAlpha),
                    0.50f to bottomColor.copy(alpha = rimAlpha),
                    0.75f to leftColor.copy(alpha = rimAlpha),
                    1.00f to topColor.copy(alpha = rimAlpha),
                ),
                center = Offset(w / 2f, h / 2f),
            ),
            topLeft = Offset(rimWidth * 0.5f, rimWidth * 0.5f),
            size = Size((w - rimWidth).coerceAtLeast(1f), (h - rimWidth).coerceAtLeast(1f)),
            cornerRadius = CornerRadius(cornerPx, cornerPx),
            style = Stroke(width = rimWidth),
            blendMode = BlendMode.Screen,
        )
    } else {
        val rimBrushH = Brush.horizontalGradient(listOf(c1.copy(alpha = rimAlpha), c2.copy(alpha = rimAlpha), c3.copy(alpha = rimAlpha)))
        val rimBrushV = Brush.verticalGradient(listOf(c1.copy(alpha = rimAlpha), c2.copy(alpha = rimAlpha), c3.copy(alpha = rimAlpha)))
        if ((edges and EdgeGlowEdgeMask.TOP) != 0) drawLine(rimBrushH, Offset(0f, rimWidth * 0.5f), Offset(w, rimWidth * 0.5f), rimWidth, blendMode = BlendMode.Screen)
        if ((edges and EdgeGlowEdgeMask.BOTTOM) != 0) drawLine(rimBrushH, Offset(0f, h - rimWidth * 0.5f), Offset(w, h - rimWidth * 0.5f), rimWidth, blendMode = BlendMode.Screen)
        if ((edges and EdgeGlowEdgeMask.LEFT) != 0) drawLine(rimBrushV, Offset(rimWidth * 0.5f, 0f), Offset(rimWidth * 0.5f, h), rimWidth, blendMode = BlendMode.Screen)
        if ((edges and EdgeGlowEdgeMask.RIGHT) != 0) drawLine(rimBrushV, Offset(w - rimWidth * 0.5f, 0f), Offset(w - rimWidth * 0.5f, h), rimWidth, blendMode = BlendMode.Screen)
    }
}

/**
 * Smooth Edge Curve: continuous soft liquid-glow wave anchored flush to every
 * enabled bezel edge, sculpted in real time by the 8-band audio spectrum.
 * Uses filled translucent layers + subtle luminous crest instead of hard inset wireframes.
 */
private fun DrawScope.drawLumnSmoothCurve(
    c1: Color,
    c2: Color,
    c3: Color,
    edges: Int,
    intensity: Float,
    thickness: Float,
    pulse: Float,
    beat: Float,
    spectrum: FloatArray,
    isPlaying: Boolean,
    phase: Float,
    slowPhase: Float,
    paths: List<Path>,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    // Subtle Lumn base wash first so the bezel feels softly backlit.
    drawLumnAmbientBorder(
        c1 = c1, c2 = c2, c3 = c3,
        edges = edges,
        intensity = intensity * 0.55f,
        thickness = thickness * 0.8f,
        pulse = pulse,
        beat = beat,
        spectrum = spectrum,
        phase = phase,
        slowPhase = slowPhase,
    )

    val safeInt = intensity.coerceIn(0.2f, 2.2f)
    val safeThick = thickness.coerceIn(0.05f, 1f)
    val maxReach = size.minDimension * (0.055f + safeThick * 0.135f) * (0.75f + pulse * 0.55f)
    val baseReach = 6.dp.toPx() + maxReach * 0.22f
    val steps = 40

    fun sampleEnergy(t: Float, harmonicOffset: Float): Float {
        // Mirror spectrum symmetrically from center outward (bass in center, treble toward ends).
        val sym = kotlin.math.abs(t - 0.5f) * 2f
        val live = if (isPlaying && spectrum.size >= 8) {
            FluidRhythmEngine.sampleBand(sym)
        } else {
            0.22f
        }
        val organic = 0.5f + 0.5f * sin(t * 2f * PI.toFloat() * 1.5f + phase + harmonicOffset) *
            cos(t * PI.toFloat() * 2f - slowPhase)
        val envelope = sin(t * PI.toFloat()).coerceIn(0.08f, 1f)
        return ((live * 0.72f + organic * 0.28f) * envelope).coerceIn(0f, 1.15f)
    }

    fun drawEdgeCurve(edgeIndex: Int, edgeFlag: Int, colorA: Color, colorB: Color) {
        if ((edges and edgeFlag) == 0) return
        val fillAlpha = (0.34f * safeInt * pulse).coerceIn(0.10f, 0.68f)
        val crestAlpha = (0.68f * safeInt * pulse).coerceIn(0.22f, 0.92f)

        // Two overlapping soft filled silk waves per edge (back layer wider, front layer crisper).
        for (layer in 0..1) {
            val path = paths[layer]
            val crest = paths[layer + 2]
            path.reset()
            crest.reset()
            val reachScale = if (layer == 0) 1.0f else 0.66f
            val hShift = edgeIndex * 1.15f + layer * 2.3f

            when (edgeIndex) {
                0 -> path.moveTo(0f, 0f)
                1 -> path.moveTo(0f, h)
                2 -> path.moveTo(0f, 0f)
                else -> path.moveTo(w, 0f)
            }

            var prevX = 0f
            var prevY = 0f
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val e = sampleEnergy(t, hShift)
                val dist = (baseReach * reachScale + e * maxReach * reachScale)
                val x: Float
                val y: Float
                when (edgeIndex) {
                    0 -> { x = t * w; y = dist.coerceAtMost(h * 0.28f) }
                    1 -> { x = t * w; y = (h - dist).coerceAtLeast(h * 0.72f) }
                    2 -> { x = dist.coerceAtMost(w * 0.30f); y = t * h }
                    else -> { x = (w - dist).coerceAtLeast(w * 0.70f); y = t * h }
                }
                if (i == 0) {
                    path.lineTo(x, y)
                    crest.moveTo(x, y)
                } else {
                    val midX = (prevX + x) * 0.5f
                    val midY = (prevY + y) * 0.5f
                    path.quadraticTo(prevX, prevY, midX, midY)
                    crest.quadraticTo(prevX, prevY, midX, midY)
                }
                prevX = x
                prevY = y
            }
            path.lineTo(prevX, prevY)
            crest.lineTo(prevX, prevY)
            when (edgeIndex) {
                0 -> path.lineTo(w, 0f)
                1 -> path.lineTo(w, h)
                2 -> path.lineTo(0f, h)
                else -> path.lineTo(w, h)
            }
            path.close()

            val layerColor = if (layer == 0) colorA else colorB
            val maxDist = baseReach + maxReach
            val fillBrush = when (edgeIndex) {
                0 -> Brush.verticalGradient(
                    0f to layerColor.copy(alpha = fillAlpha),
                    0.7f to layerColor.copy(alpha = fillAlpha * 0.42f),
                    1f to Color.Transparent,
                    startY = 0f, endY = maxDist,
                )
                1 -> Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.3f to layerColor.copy(alpha = fillAlpha * 0.42f),
                    1f to layerColor.copy(alpha = fillAlpha),
                    startY = h - maxDist, endY = h,
                )
                2 -> Brush.horizontalGradient(
                    0f to layerColor.copy(alpha = fillAlpha),
                    0.7f to layerColor.copy(alpha = fillAlpha * 0.42f),
                    1f to Color.Transparent,
                    startX = 0f, endX = maxDist,
                )
                else -> Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.3f to layerColor.copy(alpha = fillAlpha * 0.42f),
                    1f to layerColor.copy(alpha = fillAlpha),
                    startX = w - maxDist, endX = w,
                )
            }
            drawPath(path, brush = fillBrush, blendMode = BlendMode.Screen)
            if (layer == 1) {
                val crestColor = lerp(colorA, Color.White, 0.36f)
                val crestBrush = if (edgeIndex == 0 || edgeIndex == 1) {
                    Brush.horizontalGradient(listOf(colorA.copy(alpha = crestAlpha), crestColor.copy(alpha = crestAlpha), colorB.copy(alpha = crestAlpha)))
                } else {
                    Brush.verticalGradient(listOf(colorA.copy(alpha = crestAlpha), crestColor.copy(alpha = crestAlpha), colorB.copy(alpha = crestAlpha)))
                }
                drawPath(crest, brush = crestBrush, style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round), alpha = 0.32f, blendMode = BlendMode.Screen)
                drawPath(crest, brush = crestBrush, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round), alpha = 0.85f, blendMode = BlendMode.Screen)
            }
        }
    }

    drawEdgeCurve(0, EdgeGlowEdgeMask.TOP, c1, c2)
    drawEdgeCurve(1, EdgeGlowEdgeMask.BOTTOM, c2, c3)
    drawEdgeCurve(2, EdgeGlowEdgeMask.LEFT, c3, c1)
    drawEdgeCurve(3, EdgeGlowEdgeMask.RIGHT, c1, c3)
}

/**
 * Edge EQ Bars: smooth glowing frequency-spectrum bars hugging every enabled
 * bezel edge, driven by the real 8-band PCM analyser in [FluidRhythmEngine].
 */
private fun DrawScope.drawLumnEdgeBars(
    c1: Color,
    c2: Color,
    c3: Color,
    edges: Int,
    intensity: Float,
    thickness: Float,
    pulse: Float,
    spectrum: FloatArray,
    isPlaying: Boolean,
    phase: Float,
    slowPhase: Float,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    // Subtle backlit bezel glow behind the EQ bars.
    drawLumnAmbientBorder(
        c1 = c1, c2 = c2, c3 = c3,
        edges = edges,
        intensity = intensity * 0.42f,
        thickness = thickness * 0.65f,
        pulse = pulse,
        beat = if (spectrum.isNotEmpty()) spectrum[0] else 0f,
        spectrum = spectrum,
        phase = phase,
        slowPhase = slowPhase,
    )

    val safeInt = intensity.coerceIn(0.2f, 2.2f)
    val safeThick = thickness.coerceIn(0.05f, 1f)
    val maxBarLen = size.minDimension * (0.055f + safeThick * 0.11f) * safeInt.coerceAtMost(1.55f)
    val minBarLen = 3.5.dp.toPx()
    val coreWidth = 3.4.dp.toPx()
    val haloWidth = 10.dp.toPx()
    val margin = 4.dp.toPx()

    fun barLevel(frac: Float, edgeOffset: Float): Float {
        // Symmetric spectrum: rich bass in center of edge, crisp highs toward corners.
        val bandPos = kotlin.math.abs(frac - 0.5f) * 2f
        val live = if (isPlaying && spectrum.size >= 8) {
            FluidRhythmEngine.sampleBand(bandPos)
        } else {
            0.14f + 0.10f * (0.5f + 0.5f * sin(slowPhase + frac * 5f + edgeOffset))
        }
        // Subtle shimmer so adjacent bars feel fluid without losing real spectrum shape.
        val shimmer = 0.88f + 0.12f * sin(phase * 1.4f + frac * 9f + edgeOffset)
        val cornerTaper = sin(frac * PI.toFloat()).coerceIn(0.25f, 1f)
        return (live * shimmer * cornerTaper).coerceIn(0.06f, 1.1f)
    }

    fun barColor(frac: Float): Color =
        if (frac < 0.5f) lerp(c1, c2, frac * 2f) else lerp(c2, c3, (frac - 0.5f) * 2f)

    fun drawBar(start: Offset, end: Offset, color: Color, alpha: Float) {
        drawLine(color.copy(alpha = alpha * 0.30f), start, end, haloWidth, cap = StrokeCap.Round, blendMode = BlendMode.Screen)
        drawLine(lerp(color, Color.White, 0.25f).copy(alpha = alpha), start, end, coreWidth, cap = StrokeCap.Round, blendMode = BlendMode.Screen)
    }

    val hCount = 24
    val vCount = 34
    if ((edges and (EdgeGlowEdgeMask.TOP or EdgeGlowEdgeMask.BOTTOM)) != 0) {
        for (i in 0 until hCount) {
            val frac = (i + 0.5f) / hCount
            val x = w * frac
            val color = barColor(frac)
            if ((edges and EdgeGlowEdgeMask.TOP) != 0) {
                val lvl = barLevel(frac, 0f)
                val len = (minBarLen + maxBarLen * lvl).coerceAtLeast(minBarLen)
                val alpha = (0.45f + 0.50f * lvl * safeInt.coerceAtMost(1.2f)).coerceIn(0.22f, 0.95f)
                drawBar(Offset(x, margin), Offset(x, margin + len), color, alpha)
            }
            if ((edges and EdgeGlowEdgeMask.BOTTOM) != 0) {
                val lvl = barLevel(frac, 1.6f)
                val len = (minBarLen + maxBarLen * lvl).coerceAtLeast(minBarLen)
                val alpha = (0.45f + 0.50f * lvl * safeInt.coerceAtMost(1.2f)).coerceIn(0.22f, 0.95f)
                drawBar(Offset(x, h - margin), Offset(x, h - margin - len), barColor(1f - frac), alpha)
            }
        }
    }
    if ((edges and (EdgeGlowEdgeMask.LEFT or EdgeGlowEdgeMask.RIGHT)) != 0) {
        for (i in 0 until vCount) {
            val frac = (i + 0.5f) / vCount
            val y = h * frac
            val color = barColor(frac)
            if ((edges and EdgeGlowEdgeMask.LEFT) != 0) {
                val lvl = barLevel(frac, 2.7f)
                val len = (minBarLen + maxBarLen * 0.85f * lvl).coerceAtLeast(minBarLen)
                val alpha = (0.42f + 0.48f * lvl * safeInt.coerceAtMost(1.2f)).coerceIn(0.20f, 0.92f)
                drawBar(Offset(margin, y), Offset(margin + len, y), color, alpha)
            }
            if ((edges and EdgeGlowEdgeMask.RIGHT) != 0) {
                val lvl = barLevel(frac, 4.1f)
                val len = (minBarLen + maxBarLen * 0.85f * lvl).coerceAtLeast(minBarLen)
                val alpha = (0.42f + 0.48f * lvl * safeInt.coerceAtMost(1.2f)).coerceIn(0.20f, 0.92f)
                drawBar(Offset(w - margin, y), Offset(w - margin - len, y), barColor(1f - frac), alpha)
            }
        }
    }
}

/**
 * Aurora Silk: lush overlapping northern-lights curtains confined cleanly to the
 * enabled screen perimeter so center clock/artwork/lyrics stay crisp and dark.
 */
private fun DrawScope.drawLumnAuroraSilk(
    c1: Color,
    c2: Color,
    c3: Color,
    edges: Int,
    intensity: Float,
    thickness: Float,
    pulse: Float,
    beat: Float,
    phase: Float,
    slowPhase: Float,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val safeInt = intensity.coerceIn(0.2f, 2.2f)
    val safeThick = thickness.coerceIn(0.05f, 1f)
    // Constrained radius so curtains hug the edges instead of washing over center content.
    val radius = size.minDimension * (0.20f + safeThick * 0.16f) * (0.92f + beat * 0.24f)
    val alpha = (0.50f * safeInt * pulse).coerceIn(0.12f, 0.82f)

    fun drawPlume(cx: Float, cy: Float, color: Color, rScale: Float = 1f) {
        val r = (radius * rScale).coerceAtLeast(24f)
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to color.copy(alpha = alpha),
                    0.42f to color.copy(alpha = alpha * 0.42f),
                    0.78f to color.copy(alpha = alpha * 0.09f),
                    1.00f to Color.Transparent,
                ),
                center = Offset(cx, cy),
                radius = r,
            ),
            radius = r,
            center = Offset(cx, cy),
            blendMode = BlendMode.Screen,
        )
    }

    if ((edges and EdgeGlowEdgeMask.TOP) != 0) {
        drawPlume(w * (0.25f + 0.18f * sin(slowPhase)), -radius * 0.15f, c1, 1.15f)
        drawPlume(w * (0.75f + 0.18f * cos(slowPhase * 1.1f)), -radius * 0.10f, c2, 1.05f)
    }
    if ((edges and EdgeGlowEdgeMask.BOTTOM) != 0) {
        drawPlume(w * (0.30f + 0.20f * cos(slowPhase * 0.9f)), h + radius * 0.12f, c2, 1.12f)
        drawPlume(w * (0.72f + 0.18f * sin(slowPhase + 1.5f)), h + radius * 0.14f, c3, 1.15f)
    }
    if ((edges and EdgeGlowEdgeMask.LEFT) != 0) {
        drawPlume(-radius * 0.15f, h * (0.32f + 0.18f * sin(phase * 0.5f)), c3, 1.0f)
        drawPlume(-radius * 0.15f, h * (0.70f + 0.16f * cos(slowPhase + 0.8f)), c1, 0.95f)
    }
    if ((edges and EdgeGlowEdgeMask.RIGHT) != 0) {
        drawPlume(w + radius * 0.15f, h * (0.30f + 0.16f * cos(phase * 0.5f + 1f)), c2, 0.95f)
        drawPlume(w + radius * 0.15f, h * (0.68f + 0.18f * sin(slowPhase + 2.1f)), c1, 1.0f)
    }
}

/** Legacy compatibility wrapper kept in case any external preview calls drawEdgeGlow. */
internal fun DrawScope.drawEdgeGlow(
    color1: Color,
    color2: Color,
    intensity: Float,
    thickness: Float,
    pulse: Float,
) {
    drawLumnAmbientBorder(
        c1 = color1,
        c2 = color2,
        c3 = lerp(color1, color2, 0.5f),
        edges = EdgeGlowEdgeMask.ALL,
        intensity = intensity,
        thickness = thickness,
        pulse = pulse,
        beat = (pulse - 0.45f).coerceIn(0f, 1f),
        spectrum = FluidRhythmEngine.currentSpectrum,
        phase = 0f,
        slowPhase = 0f,
    )
}

/** Utility so callers can keep gradients from washing out black content. */
internal fun glowAlphaFor(beat: Float, base: Float = 0.28f): Float =
    (base + beat.coerceIn(0f, 1f) * 0.42f) * max(0.2f, 1f)

/** Parses a stored "#RRGGBB" colour, falling back when the string is invalid. */
internal fun parseHexColor(hex: String, fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex.trim())) }.getOrDefault(fallback)
