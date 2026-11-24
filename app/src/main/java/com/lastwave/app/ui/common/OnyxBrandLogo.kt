package com.lastwave.app.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lastwave.app.R
import com.lastwave.app.data.local.AppIcon
import com.lastwave.app.ui.theme.LocalAppIcon

/**
 * Shared Onyx Music app-icon badge that always respects BOTH the user's chosen
 * [AppIcon] variant (Black, White, Desktop, Default) AND their active Material
 * theme accent color so no popup, splash, or card ever flashes the wrong icon color.
 */
@Composable
fun OnyxBrandLogo(
    size: Dp = 40.dp,
    shape: Shape = CircleShape,
    appIcon: AppIcon = LocalAppIcon.current,
    modifier: Modifier = Modifier,
) {
    val useDynamicAccent = appIcon == AppIcon.DEFAULT
    val backgroundColor = if (useDynamicAccent) {
        MaterialTheme.colorScheme.primary
    } else {
        colorResource(appIcon.backgroundRes)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        if (useDynamicAccent) {
            Icon(
                painter = painterResource(R.drawable.ic_lastwave_logo_mono),
                contentDescription = "Onyx Music",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(size * 0.56f),
            )
        } else {
            Icon(
                painter = painterResource(appIcon.logoRes),
                contentDescription = "Onyx Music",
                tint = Color.Unspecified,
                modifier = Modifier.size(size),
            )
        }
    }
}

/**
 * Renders the user's currently selected Onyx Music logo (honoring BOTH [AppIcon]
 * and dynamic theme accent for [AppIcon.DEFAULT]) to a [Bitmap] for Android
 * system surfaces: Recents (`TaskDescription`), PackageInstaller update prompts
 * (`SessionParams.setAppIcon`), and playback notification fallback art.
 */
fun renderOnyxLogoBitmap(
    context: Context,
    appIcon: AppIcon,
    primaryArgb: Int,
    onPrimaryArgb: Int,
    sizePx: Int = 192,
): Bitmap {
    val safeSize = sizePx.coerceIn(48, 512)
    val bmp = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val useDynamic = appIcon == AppIcon.DEFAULT
    val bgArgb = if (useDynamic) primaryArgb else ContextCompat.getColor(context, appIcon.backgroundRes)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bgArgb
        style = Paint.Style.FILL
    }
    val radius = safeSize * 0.24f
    canvas.drawRoundRect(RectF(0f, 0f, safeSize.toFloat(), safeSize.toFloat()), radius, radius, paint)
    runCatching {
        if (useDynamic) {
            val drawable = ContextCompat.getDrawable(context, R.drawable.ic_lastwave_logo_mono)?.mutate()
            if (drawable != null) {
                drawable.setTint(onPrimaryArgb)
                val pad = (safeSize * 0.22f).toInt()
                drawable.setBounds(pad, pad, safeSize - pad, safeSize - pad)
                drawable.draw(canvas)
            }
        } else {
            val drawable = ContextCompat.getDrawable(context, appIcon.logoRes)?.mutate()
            if (drawable != null) {
                drawable.setBounds(0, 0, safeSize, safeSize)
                drawable.draw(canvas)
            }
        }
    }
    return bmp
}
