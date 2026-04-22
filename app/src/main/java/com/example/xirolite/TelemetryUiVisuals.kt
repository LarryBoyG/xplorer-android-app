package com.example.xirolite

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

const val LIVE_VIEW_HUD_PREF_KEY = "live_view_hud_items"
val DEFAULT_LIVE_VIEW_HUD_ITEMS = setOf("GPS Sat", "Aircraft Power", "Elevation", "SD Card")

fun normalizeHudSelection(items: Set<String>): Set<String> =
    items.map { label ->
        when (label) {
            "Altitude", "Baro HGT" -> "Elevation"
            "Target HGT" -> "Target Elevation"
            else -> label
        }
    }.toSet()

data class SignalStrengthVisual(
    val level: Int,
    val caption: String
)

enum class StatusIndicatorLevel {
    GOOD,
    CAUTION,
    CRITICAL,
    NEUTRAL
}

fun parseSignalStrengthVisual(value: String): SignalStrengthVisual? {
    val trimmed = value.trim()
    if (trimmed.isBlank() || trimmed == "--") return null

    val numericMatch = Regex("(\\d{1,3})").find(trimmed)
    if (numericMatch != null) {
        val signal = numericMatch.groupValues[1].toIntOrNull() ?: return null
        val level = when {
            signal >= 75 -> 4
            signal >= 55 -> 3
            signal >= 35 -> 2
            signal >= 15 -> 1
            else -> 0
        }
        return SignalStrengthVisual(level = level, caption = trimmed)
    }

    val lowered = trimmed.lowercase(Locale.US)
    val level = when {
        "excellent" in lowered -> 4
        "strong" in lowered -> 3
        "good" in lowered -> 3
        "fair" in lowered -> 2
        "weak" in lowered -> 1
        "waiting" in lowered -> 0
        else -> return null
    }
    return SignalStrengthVisual(level = level, caption = trimmed)
}

fun resolveStatusIndicatorLevel(label: String, value: String, ok: Boolean): StatusIndicatorLevel {
    return when (label) {
        "GPS Sat" -> {
            val satCount = parseLeadingInt(value)
            when {
                satCount == null -> if (ok) StatusIndicatorLevel.GOOD else StatusIndicatorLevel.NEUTRAL
                satCount <= 3 -> StatusIndicatorLevel.CRITICAL
                satCount <= 7 -> StatusIndicatorLevel.CAUTION
                else -> StatusIndicatorLevel.GOOD
            }
        }

        "Aircraft Power", "Remote Power" -> {
            val percent = parseLeadingInt(value)
            when {
                percent == null -> if (ok) StatusIndicatorLevel.GOOD else StatusIndicatorLevel.NEUTRAL
                percent <= 15 -> StatusIndicatorLevel.CRITICAL
                percent <= 30 -> StatusIndicatorLevel.CAUTION
                else -> StatusIndicatorLevel.GOOD
            }
        }

        "SD Card" -> {
            val mbValue = parseLeadingInt(value)
            when {
                mbValue == null -> StatusIndicatorLevel.NEUTRAL
                mbValue <= 512 -> StatusIndicatorLevel.CRITICAL
                mbValue <= 2048 -> StatusIndicatorLevel.CAUTION
                else -> StatusIndicatorLevel.GOOD
            }
        }

        "Altitude", "Baro HGT", "Elevation", "Target HGT", "Target Elevation" -> {
            val alt = parseLeadingInt(value)
            when {
                alt == null -> StatusIndicatorLevel.NEUTRAL
                alt >= 115 -> StatusIndicatorLevel.CRITICAL
                alt >= 100 -> StatusIndicatorLevel.CAUTION
                else -> StatusIndicatorLevel.GOOD
            }
        }

        "Flight Mode" -> {
            val lowered = value.lowercase(Locale.US)
            when {
                "gps" in lowered -> StatusIndicatorLevel.GOOD
                "attitude" in lowered -> StatusIndicatorLevel.CAUTION
                else -> if (ok) StatusIndicatorLevel.GOOD else StatusIndicatorLevel.NEUTRAL
            }
        }

        else -> if (ok) StatusIndicatorLevel.GOOD else StatusIndicatorLevel.CAUTION
    }
}

fun indicatorColor(level: StatusIndicatorLevel): Color =
    when (level) {
        StatusIndicatorLevel.GOOD -> Color(0xFF4CAF50)
        StatusIndicatorLevel.CAUTION -> Color(0xFFFFC107)
        StatusIndicatorLevel.CRITICAL -> Color(0xFFFF5252)
        StatusIndicatorLevel.NEUTRAL -> Color(0xFF7A828E)
    }

@Composable
fun StatusDot(level: StatusIndicatorLevel, size: Dp = 10.dp) {
    Box(
        modifier = Modifier
            .width(size)
            .height(size)
            .clip(CircleShape)
            .background(indicatorColor(level))
    )
}

@Composable
fun SignalStrengthValue(value: String) {
    val visual = parseSignalStrengthVisual(value)
    if (visual == null) {
        Text(value, style = MaterialTheme.typography.bodyMedium)
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SignalStrengthBars(level = visual.level)
        Text(visual.caption, style = MaterialTheme.typography.bodySmall, color = Color(0xFFD7DCE3))
    }
}

@Composable
fun SignalStrengthBars(level: Int) {
    val activeColor = Color(0xFF6BD224)
    val inactiveColor = Color(0xFF5A606B)
    val heights = listOf(8.dp, 12.dp, 16.dp, 20.dp)

    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        heights.forEachIndexed { index, height ->
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .height(height)
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(if (index < level) activeColor else inactiveColor)
            )
        }
    }
}

private fun parseLeadingInt(value: String): Int? =
    Regex("(\\d+)").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
