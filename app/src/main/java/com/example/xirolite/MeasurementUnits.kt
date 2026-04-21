package com.example.xirolite

import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

const val UI_PREFS_NAME = "xiro_lite_ui"
const val MEASUREMENT_UNIT_PREF_KEY = "measurement_unit"

enum class MeasurementUnit(
    val storedValue: String,
    val settingsValueLabel: String,
    val pageTitle: String
) {
    METRIC(
        storedValue = "metric",
        settingsValueLabel = "Meters",
        pageTitle = "Metric (meters)"
    ),
    IMPERIAL(
        storedValue = "imperial",
        settingsValueLabel = "Feet",
        pageTitle = "Imperial (feet)"
    );

    companion object {
        fun fromStored(value: String?): MeasurementUnit =
            entries.firstOrNull { it.storedValue.equals(value, ignoreCase = true) } ?: METRIC
    }
}

private const val METERS_TO_FEET = 3.28084
private const val METERS_PER_SECOND_TO_MPH = 2.236936
private const val METERS_PER_KILOMETER = 1000.0
private const val FEET_PER_MILE = 5280.0

fun formatAltitudeForUnit(altitudeMeters: Double, unit: MeasurementUnit): String {
    return when (unit) {
        MeasurementUnit.METRIC -> formatLinearValue(
            value = altitudeMeters,
            suffix = "m",
            integerThreshold = 0.05
        )

        MeasurementUnit.IMPERIAL -> formatLinearValue(
            value = altitudeMeters * METERS_TO_FEET,
            suffix = "ft",
            integerThreshold = 0.05
        )
    }
}

fun formatDistanceForUnit(distanceMeters: Double, unit: MeasurementUnit): String {
    return when (unit) {
        MeasurementUnit.METRIC -> {
            if (distanceMeters >= METERS_PER_KILOMETER) {
                String.format(Locale.US, "%.2f km", distanceMeters / METERS_PER_KILOMETER)
            } else {
                String.format(Locale.US, "%.0f m", distanceMeters)
            }
        }

        MeasurementUnit.IMPERIAL -> {
            val feet = distanceMeters * METERS_TO_FEET
            if (feet >= FEET_PER_MILE) {
                String.format(Locale.US, "%.2f mi", feet / FEET_PER_MILE)
            } else {
                String.format(Locale.US, "%.0f ft", feet)
            }
        }
    }
}

fun formatSpeedForUnit(speedMetersPerSecond: Double, unit: MeasurementUnit): String {
    return when (unit) {
        MeasurementUnit.METRIC -> String.format(Locale.US, "%.1f m/s", speedMetersPerSecond)
        MeasurementUnit.IMPERIAL -> String.format(
            Locale.US,
            "%.1f mph",
            speedMetersPerSecond * METERS_PER_SECOND_TO_MPH
        )
    }
}

private fun formatLinearValue(value: Double, suffix: String, integerThreshold: Double): String {
    val rounded = value.roundToInt().toDouble()
    return if ((value - rounded).absoluteValue <= integerThreshold) {
        String.format(Locale.US, "%.0f %s", value, suffix)
    } else {
        String.format(Locale.US, "%.1f %s", value, suffix)
    }
}
