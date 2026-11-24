package com.lastwave.app.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.lastwave.app.ui.common.LocalAmbientAnimationsActive
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Aurora seekbar: overlapping translucent waves of different heights and
 * colours rising from a flat bottom baseline, over the played part only.
 * Unlike [WavyProgressTrack] the waves are filled (not stroked), so several
 * colours blend where they cross — the layered look the Android-style
 * progress bars have today. [fraction] and [rhythm] are read in the draw
 * phase, so progress ticks and beat pulses redraw without recomposing.
 */
@Composable
internal fun AuroraProgressTrack(
    fraction: () -> Float,
    rhythm: () -> Float,
    isPlaying: Boolean,
    dragging: Boolean,
    isTranslucent: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    tertiaryColor: Color,
    modifier: Modifier = Modifier,
) {
    val primary = remember(primaryColor) { primaryColor.ensureLuminousForDarkBackground(0.40f) }
    val secondary = remember(secondaryColor) { secondaryColor.ensureLuminousForDarkBackground(0.34f) }
    val tertiary = remember(tertiaryColor) { tertiaryColor.ensureLuminousForDarkBackground(0.30f) }

    // Four layers, deliberately different hues and alphas so crossings blend.
    val layers = remember(primary, secondary, tertiary) {
        listOf(
            tertiary.auroraShift(+0.22f, 0.92f).copy(alpha = 0.34f),
            secondary.auroraShift(-0.10f, 1.12f).copy(alpha = 0.44f),
            primary.auroraShift(+0.12f, 0.95f).copy(alpha = 0.62f),
            primary.auroraShift(-0.08f, 1.20f).copy(alpha = 0.88f),
        )
    }
    val inactiveColor = Color.White.copy(alpha = if (isTranslucent) 0.20f else 0.16f)
    val thumbColor = remember(primary) { primary.auroraShift(+0.06f, 1.06f) }

    val clock = remember { mutableFloatStateOf(0f) }
    val wavesActive = isPlaying && !dragging &&
        LocalAmbientAnimationsActive.current && rememberPlayerWindowActive()
    androidx.compose.runtime.DisposableEffect(wavesActive) {
        if (wavesActive) com.lastwave.app.playback.rhythm.FluidRhythmEngine.acquire()
        onDispose { if (wavesActive) com.lastwave.app.playback.rhythm.FluidRhythmEngine.release() }
    }
    LaunchedEffect(wavesActive) {
        clock.floatValue = 0f
        if (!wavesActive) return@LaunchedEffect
        var startNanos = -1L
        var lastNanos = -1L
        while (isActive) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (startNanos < 0L) startNanos = now
                val dt = if (lastNanos < 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0.001f, 0.1f)
                lastNanos = now
                com.lastwave.app.playback.rhythm.FluidRhythmEngine.tick(dt, true)
                clock.floatValue = ((now - startNanos) % 100_000_000_000L) / 1_000_000_000f
            }
            delay(24)
        }
    }

    // Scrubbing flattens the waves so the thumb reads clearly.
    val flatness by animateFloatAsState(if (dragging) 0.12f else 1f, tween(200), label = "auroraFlat")
    val paths = remember { List(4) { Path() } }
    val specs = remember {
        // wavelength dp, amplitude dp, phase speed, phase offset
        listOf(
            AuroraWave(150f, 13f, 2.1f, 2.6f),
            AuroraWave(110f, 19f, 1.55f, 1.15f),
            AuroraWave(84f, 10f, 1.25f, 0.4f),
            AuroraWave(62f, 7f, 0.95f, 0f),
        )
    }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val baselineY = height - 2.dp.toPx()
        val thumbX = (fraction().coerceIn(0f, 1f) * width).coerceIn(0f, width)
        val beat = rhythm().coerceIn(0f, 1f)
        val beatGain = 1f + beat * 0.55f

        // Unplayed rail: a quiet flat line with an empty trough above it.
        drawLine(
            inactiveColor,
            Offset(0f, baselineY - 3.dp.toPx()),
            Offset(width, baselineY - 3.dp.toPx()),
            3.dp.toPx(),
            StrokeCap.Round,
        )

        if (thumbX > 0f) {
            val t = clock.floatValue
            val step = 1.6.dp.toPx()
            clipRect(left = 0f, right = thumbX + 1.dp.toPx(), top = 0f, bottom = height) {
                specs.forEachIndexed { index, spec ->
                    val amp = spec.amplitudeDp.dp.toPx() * flatness * beatGain
                    val k = (2 * PI / spec.wavelengthDp.dp.toPx()).toFloat()
                    val phase = t * spec.phaseSpeed + spec.phaseOffset
                    val path = paths[index]
                    path.reset()
                    path.moveTo(0f, baselineY)
                    var x = 0f
                    while (x < thumbX) {
                        // Slight left-to-right swell so the waves look like a
                        // rolled surface instead of a repeating stamp.
                        val swell = 0.55f + 0.45f * sin(x / width * PI).toFloat().coerceAtLeast(0f)
                        val y = baselineY - (0.18f + 0.82f * (0.5f + 0.5f * sin(x * k - phase))) * amp * swell
                        path.lineTo(x, y)
                        x += step
                    }
                    path.lineTo(thumbX, baselineY)
                    path.close()
                    drawPath(
                        path,
                        Brush.horizontalGradient(
                            listOf(
                                layers[index].copy(alpha = layers[index].alpha * 0.55f),
                                layers[index],
                                layers[index].copy(alpha = layers[index].alpha * 0.8f),
                            ),
                            0f,
                            thumbX.coerceAtLeast(1f),
                        ),
                    )
                }
                // Bright leading edge on the played rail.
                drawLine(
                    Brush.horizontalGradient(listOf(primary, secondary), 0f, thumbX.coerceAtLeast(1f)),
                    Offset(0f, baselineY),
                    Offset(thumbX, baselineY),
                    2.5.dp.toPx(),
                    StrokeCap.Round,
                )
            }

            val thumbY = baselineY
            val thumbRadius = 7.dp.toPx()
            drawCircle(thumbColor.copy(alpha = 0.30f + beat * 0.28f), thumbRadius + 4.5.dp.toPx(), Offset(thumbX, thumbY))
            drawCircle(thumbColor, thumbRadius, Offset(thumbX, thumbY))
            drawCircle(Color.White.copy(alpha = 0.6f), thumbRadius, Offset(thumbX, thumbY), style = Stroke(1.2.dp.toPx()))
        }
    }
}

/** Local tonal shift (the Wavy/Waveform helpers are private to those files). */
private fun Color.auroraShift(lightnessDelta: Float, saturationScale: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (red * 255).toInt().coerceIn(0, 255),
        (green * 255).toInt().coerceIn(0, 255),
        (blue * 255).toInt().coerceIn(0, 255),
        hsv,
    )
    val originalSaturation = hsv[1]
    hsv[1] = (hsv[1] * saturationScale).coerceIn(if (originalSaturation > 0.05f) 0.15f else 0f, 1f)
    hsv[2] = (hsv[2] + lightnessDelta).coerceIn(0.25f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private data class AuroraWave(
    val wavelengthDp: Float,
    val amplitudeDp: Float,
    val phaseSpeed: Float,
    val phaseOffset: Float,
)
