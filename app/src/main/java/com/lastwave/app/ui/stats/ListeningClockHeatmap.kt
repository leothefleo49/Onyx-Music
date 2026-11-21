package com.lastwave.app.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * stats.fm-style 24-hour radial listening clock rendered via Compose Canvas.
 * Tapping any spoke highlights that hour and shows exact play count and duration.
 */
@Composable
fun ListeningClockCard(
    bars: List<HourlyRhythmBar>,
    modifier: Modifier = Modifier,
) {
    if (bars.isEmpty()) return

    val anim = remember { Animatable(0f) }
    LaunchedEffect(bars) {
        anim.snapTo(0f)
        anim.animateTo(1f, tween(durationMillis = 700, easing = FastOutSlowInEasing))
    }

    var selectedHour by remember(bars) {
        mutableStateOf(bars.firstOrNull { it.isPeak }?.hour)
    }

    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val trackRing = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceText = MaterialTheme.colorScheme.onSurface
    val textMeasurer = rememberTextMeasurer()
    val cardinalStyle = MaterialTheme.typography.labelSmall.copy(
        color = labelColor,
        fontWeight = FontWeight.Bold,
    )

    val activeSlot = bars.firstOrNull { it.hour == selectedHour }
        ?: bars.firstOrNull { it.isPeak }
        ?: bars.first()

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "24-hour listening clock",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = surfaceText,
                    )
                    Text(
                        text = "Tap any hour to inspect plays and duration",
                        style = MaterialTheme.typography.bodySmall,
                        color = labelColor,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = String.format(Locale.US, "%02d:00", activeSlot.hour),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(232.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(
                    modifier = Modifier
                        .size(224.dp)
                        .pointerInput(bars) {
                            detectTapGestures { offset ->
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val dx = offset.x - cx
                                val dy = offset.y - cy
                                val angleRad = atan2(dy.toDouble(), dx.toDouble())
                                var deg = Math.toDegrees(angleRad) + 90.0
                                if (deg < 0.0) deg += 360.0
                                val hourIdx = ((deg / 15.0).roundToInt()) % 24
                                selectedHour = hourIdx
                            }
                        },
                ) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val outerLabelRadius = size.minDimension / 2f - 10.dp.toPx()
                    val innerRadius = size.minDimension * 0.22f
                    val maxSpokeLen = outerLabelRadius - innerRadius - 18.dp.toPx()

                    // Subtle concentric guide rings
                    drawCircle(
                        color = trackRing.copy(alpha = 0.45f),
                        radius = innerRadius,
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                    drawCircle(
                        color = trackRing.copy(alpha = 0.25f),
                        radius = innerRadius + maxSpokeLen * 0.5f,
                        center = center,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                    drawCircle(
                        color = trackRing.copy(alpha = 0.35f),
                        radius = innerRadius + maxSpokeLen,
                        center = center,
                        style = Stroke(width = 1.dp.toPx()),
                    )

                    // 24 radial spokes
                    for (bar in bars) {
                        val angle = ((bar.hour * 15.0) - 90.0) * (PI / 180.0)
                        val cosA = cos(angle).toFloat()
                        val sinA = sin(angle).toFloat()
                        val isSelected = bar.hour == selectedHour
                        val spokeLength = (maxSpokeLen * bar.fraction * anim.value)
                            .coerceAtLeast(4.dp.toPx())
                        val start = Offset(
                            center.x + cosA * (innerRadius + 3.dp.toPx()),
                            center.y + sinA * (innerRadius + 3.dp.toPx()),
                        )
                        val end = Offset(
                            center.x + cosA * (innerRadius + 3.dp.toPx() + spokeLength),
                            center.y + sinA * (innerRadius + 3.dp.toPx() + spokeLength),
                        )
                        val spokeColor = when {
                            isSelected -> primary
                            bar.isPeak -> secondary
                            bar.count > 0 -> primary.copy(alpha = 0.42f)
                            else -> trackRing.copy(alpha = 0.4f)
                        }
                        drawLine(
                            color = spokeColor,
                            start = start,
                            end = end,
                            strokeWidth = if (isSelected) 7.dp.toPx() else 4.5.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }

                    // Cardinal clock hour markers (00, 06, 12, 18)
                    val cardinals = listOf(0 to "00", 6 to "06", 12 to "12", 18 to "18")
                    for ((hour, label) in cardinals) {
                        val angle = ((hour * 15.0) - 90.0) * (PI / 180.0)
                        val layout = textMeasurer.measure(label, cardinalStyle)
                        val x = center.x + cos(angle).toFloat() * outerLabelRadius - layout.size.width / 2f
                        val y = center.y + sin(angle).toFloat() * outerLabelRadius - layout.size.height / 2f
                        drawText(
                            textMeasurer = textMeasurer,
                            text = label,
                            topLeft = Offset(x, y),
                            style = cardinalStyle,
                        )
                    }
                }

                // Center callout inside the inner ring
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = activeSlot.count.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = surfaceText,
                    )
                    Text(
                        text = if (activeSlot.count == 1) "play" else "plays",
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = String.format(
                        Locale.US,
                        "Hour %02d:00 - %02d:59",
                        activeSlot.hour,
                        activeSlot.hour,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = surfaceText,
                )
                Text(
                    text = "${activeSlot.count} plays \u00b7 ${activeSlot.durationLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = labelColor,
                )
            }
        }
    }
}

private enum class HeatmapViewMode(val label: String) {
    WEEK_HOUR_MATRIX("7x24 Matrix"),
    CALENDAR_12W("12 Weeks"),
}

/**
 * Combined Activity Heatmap supporting both a 7x24 Day-of-Week x Hour Canvas
 * matrix and a 12-week calendar activity grid with tap callouts.
 */
@Composable
fun ListeningActivityHeatmapCard(
    matrixCells: List<MatrixHeatmapCell>,
    calendarDays: List<HeatmapDay>,
    modifier: Modifier = Modifier,
) {
    var mode by remember { mutableStateOf(HeatmapViewMode.WEEK_HOUR_MATRIX) }
    var selectedMatrixCell by remember(matrixCells) { mutableStateOf<MatrixHeatmapCell?>(null) }
    var selectedCalendarDay by remember(calendarDays) { mutableStateOf<HeatmapDay?>(null) }

    val primary = MaterialTheme.colorScheme.primary
    val emptyCell = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Activity heatmap",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (mode == HeatmapViewMode.WEEK_HOUR_MATRIX) {
                            "Listening intensity by weekday and hour"
                        } else {
                            "Daily activity over the past 12 weeks"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = labelColor,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HeatmapViewMode.entries.forEach { entry ->
                        val active = mode == entry
                        Surface(
                            onClick = { mode = entry },
                            shape = RoundedCornerShape(12.dp),
                            color = if (active) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            },
                        ) {
                            Text(
                                text = entry.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = if (active) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    labelColor
                                },
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (mode == HeatmapViewMode.WEEK_HOUR_MATRIX) {
                val cellLookup = remember(matrixCells) {
                    matrixCells.associateBy { (it.dayOfWeek to it.hour) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.width(32.dp),
                    ) {
                        dayNames.forEach { d ->
                            Box(
                                modifier = Modifier.height(14.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    text = d,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = labelColor,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Canvas(
                        modifier = Modifier
                            .weight(1f)
                            .height(122.dp)
                            .pointerInput(cellLookup) {
                                detectTapGestures { offset ->
                                    val col = ((offset.x / size.width) * 24f).toInt().coerceIn(0, 23)
                                    val row = ((offset.y / size.height) * 7f).toInt().coerceIn(0, 6)
                                    selectedMatrixCell = cellLookup[row to col]
                                }
                            },
                    ) {
                        val cols = 24
                        val rows = 7
                        val gapX = 2.dp.toPx()
                        val gapY = 4.dp.toPx()
                        val cellW = (size.width - gapX * (cols - 1)) / cols
                        val cellH = (size.height - gapY * (rows - 1)) / rows
                        val radius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx())

                        for (r in 0 until rows) {
                            for (c in 0 until cols) {
                                val cell = cellLookup[r to c]
                                val intensity = cell?.intensity ?: 0
                                val color = when (intensity) {
                                    0 -> emptyCell
                                    1 -> primary.copy(alpha = 0.30f)
                                    2 -> primary.copy(alpha = 0.55f)
                                    3 -> primary.copy(alpha = 0.80f)
                                    else -> primary
                                }
                                drawRoundRect(
                                    color = color,
                                    topLeft = Offset(c * (cellW + gapX), r * (cellH + gapY)),
                                    size = Size(cellW, cellH),
                                    cornerRadius = radius,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 36.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    listOf("00", "06", "12", "18", "23").forEach { hr ->
                        Text(
                            text = hr,
                            style = MaterialTheme.typography.labelSmall,
                            color = labelColor,
                        )
                    }
                }
                selectedMatrixCell?.let { cell ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = String.format(
                            Locale.US,
                            "%s %02d:00 \u00b7 %d plays (%s)",
                            dayNames.getOrElse(cell.dayOfWeek) { "" },
                            cell.hour,
                            cell.playCount,
                            cell.durationLabel,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                val byWeekDay = remember(calendarDays) {
                    calendarDays.associateBy { it.weekIndex to it.dayOfWeek }
                }
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(126.dp)
                        .pointerInput(byWeekDay) {
                            detectTapGestures { offset ->
                                val week = ((offset.x / size.width) * 12f).toInt().coerceIn(0, 11)
                                val dow = ((offset.y / size.height) * 7f).toInt().coerceIn(0, 6)
                                selectedCalendarDay = byWeekDay[week to dow]
                            }
                        },
                ) {
                    val weeks = 12
                    val days = 7
                    val gap = 4.dp.toPx()
                    val cellW = (size.width - gap * (weeks - 1)) / weeks
                    val cellH = (size.height - gap * (days - 1)) / days
                    val radius = CornerRadius(4.dp.toPx(), 4.dp.toPx())

                    for (w in 0 until weeks) {
                        for (d in 0 until days) {
                            val day = byWeekDay[w to d]
                            val intensity = day?.intensity ?: 0
                            val color = when (intensity) {
                                0 -> emptyCell
                                1 -> primary.copy(alpha = 0.30f)
                                2 -> primary.copy(alpha = 0.55f)
                                3 -> primary.copy(alpha = 0.80f)
                                else -> primary
                            }
                            drawRoundRect(
                                color = color,
                                topLeft = Offset(w * (cellW + gap), d * (cellH + gap)),
                                size = Size(cellW, cellH),
                                cornerRadius = radius,
                            )
                        }
                    }
                }
                selectedCalendarDay?.let { day ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "${day.dateString} \u00b7 ${day.playCount} plays (${day.durationLabel})",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Less",
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
                Spacer(Modifier.width(6.dp))
                listOf(0, 1, 2, 3, 4).forEach { level ->
                    val swatch = when (level) {
                        0 -> emptyCell
                        1 -> primary.copy(alpha = 0.30f)
                        2 -> primary.copy(alpha = 0.55f)
                        3 -> primary.copy(alpha = 0.80f)
                        else -> primary
                    }
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .size(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(swatch),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "More",
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
            }
        }
    }
}
