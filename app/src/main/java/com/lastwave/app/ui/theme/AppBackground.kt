package com.lastwave.app.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.lastwave.app.data.local.AppBackgroundStyle
import com.lastwave.app.ui.common.LocalAmbientAnimationsActive

/**
 * The app's wallpaper: a barely-moving, slightly glossy colour wash behind
 * every screen (never inside the immersive player, which paints its own
 * artwork backdrop).
 *
 * - [AppBackgroundStyle.ARTWORK]: two soft accent pools that drift with the
 *   now-playing palette, at low alpha so text contrast is untouched.
 * - [AppBackgroundStyle.GLOSSY]: a diagonal sheen sweeping across the accent
 *   gradient — the "slightly glossy" look.
 * - [AppBackgroundStyle.PLAIN]: nothing; the Material background shows through.
 *
 * All motion is slow (30–60 s loops) and disabled with ambient animations.
 */
@Composable
internal fun AppBackgroundGloss(
    style: AppBackgroundStyle,
    accent: Color,
    accentSecondary: Color,
    modifier: Modifier = Modifier,
) {
    if (style == AppBackgroundStyle.PLAIN) return
    val animate = LocalAmbientAnimationsActive.current
    val drift = rememberInfiniteTransition(label = "appBackground")
    val phase by drift.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (animate) 42_000 else 1, easing = androidx.compose.animation.core.LinearEasing),
            RepeatMode.Reverse,
        ),
        label = "appBackgroundPhase",
    )
    val sheen by drift.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            tween(if (animate) 26_000 else 1, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "appBackgroundSheen",
    )
    val primary = accent.copy(alpha = if (style == AppBackgroundStyle.ARTWORK) 0.16f else 0.13f)
    val secondary = accentSecondary.copy(alpha = if (style == AppBackgroundStyle.ARTWORK) 0.13f else 0.11f)
    val sheenColor = lerp(Color.White, accent, 0.35f).copy(alpha = 0.05f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val w = size.width
                val h = size.height
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(primary, Color.Transparent),
                        center = Offset(w * (0.22f + 0.10f * phase), h * (0.16f + 0.08f * (1f - phase))),
                        radius = maxOf(w, h) * 0.72f,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(secondary, Color.Transparent),
                        center = Offset(w * (0.84f - 0.08f * phase), h * (0.72f + 0.10f * phase)),
                        radius = maxOf(w, h) * 0.66f,
                    )
                )
                if (style == AppBackgroundStyle.GLOSSY) {
                    // A wide, soft diagonal highlight that sweeps once a minute.
                    val band = w * 0.55f
                    val x = -band + (w + band * 2f) * sheen
                    drawRect(
                        Brush.linearGradient(
                            colors = listOf(Color.Transparent, sheenColor, Color.Transparent),
                            start = Offset(x, -h * 0.2f),
                            end = Offset(x + band, h * 1.2f),
                        )
                    )
                }
            },
    )
}

/** Small helper so screens that draw their own scrim can match the gloss. */
internal fun glossScrimColor(base: Color, amount: Float = 0.06f): Color =
    lerp(base, Color.White, amount)

internal val glossCornerRadius = 28.dp
