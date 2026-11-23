package com.lastwave.app.ui.common

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
