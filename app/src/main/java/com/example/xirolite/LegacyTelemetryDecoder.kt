package com.example.xirolite

import kotlin.math.roundToInt

data class LegacyTelemetrySnapshot(
    val satellites: Int?,
    val droneBatteryPercent: Int?,
    val droneBatteryPercentExact: Double?,
    val droneVoltageVolts: Double?,
    val flightModeText: String?,
    val gpsText: String?,
    val gearSelection: Int?,
    val switchControlState: Int?,
    val hjCompatible: Boolean
)

object LegacyTelemetryDecoder {
    private const val RAW_SATELLITE_OFFSET = 23
    private const val RAW_POWER_U16_OFFSET = 51
    private const val RAW_VOLTAGE_U16_OFFSET = 69
    private const val RAW_GEAR_CANDIDATE_OFFSET = 54
    private const val RAW_SWITCH_CONTROL_OFFSET = 59
    private const val GEAR_WINDOW_SIZE = 12

    fun decode(packet: RemoteTelemetryPacket?): LegacyTelemetrySnapshot? =
        packet?.let { decode(listOf(it)) }

    fun decode(packets: List<RemoteTelemetryPacket>): LegacyTelemetrySnapshot? {
        val latestPacket = packets.firstOrNull() ?: return null

        val satellites = latestPacket.rawUnsigned(RAW_SATELLITE_OFFSET)
        val droneBatteryExact = latestPacket.rawU16le(RAW_POWER_U16_OFFSET)?.div(256.0)
        val droneVoltage = latestPacket.rawU16le(RAW_VOLTAGE_U16_OFFSET)?.div(204.8)
        val switchControlState = latestPacket.rawUnsigned(RAW_SWITCH_CONTROL_OFFSET)
        val gearSelection = deriveGearSelection(packets)

        val flightModeText = when {
            satellites == null -> null
            satellites <= 0 -> "Attitude"
            else -> "GPS Mode"
        }

        val gpsText = when {
            satellites == null -> null
            satellites <= 0 -> "No lock"
            else -> "$satellites sats"
        }

        return LegacyTelemetrySnapshot(
            satellites = satellites,
            droneBatteryPercent = droneBatteryExact?.roundToInt(),
            droneBatteryPercentExact = droneBatteryExact,
            droneVoltageVolts = droneVoltage,
            flightModeText = flightModeText,
            gpsText = gpsText,
            gearSelection = gearSelection,
            switchControlState = switchControlState,
            hjCompatible = latestPacket.rawData.size >= 98
        )
    }

    private fun deriveGearSelection(packets: List<RemoteTelemetryPacket>): Int? {
        val recentPackets = packets.take(GEAR_WINDOW_SIZE)
        stableGearCandidate(recentPackets, RAW_GEAR_CANDIDATE_OFFSET)?.let { return it }
        return stableGearCandidate(recentPackets, RAW_SWITCH_CONTROL_OFFSET)
    }

    private fun stableGearCandidate(
        packets: List<RemoteTelemetryPacket>,
        offset: Int
    ): Int? {
        val validValues = packets
            .mapNotNull { packet -> packet.rawUnsigned(offset)?.takeIf { it in 1..3 } }
            .take(GEAR_WINDOW_SIZE)

        if (validValues.isEmpty()) return null
        if (validValues.size == 1) return validValues.first()

        val newestValue = validValues.first()
        val newestRunLength = validValues.takeWhile { it == newestValue }.size
        if (newestRunLength >= 2) return newestValue

        val counts = validValues.groupingBy { it }.eachCount()
        val bestCount = counts.values.maxOrNull() ?: return null
        if (bestCount < 2) return null

        val winners = counts
            .filterValues { it == bestCount }
            .keys

        return validValues.firstOrNull { it in winners }
    }
}
