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
    val remoteControlAlarm: Int?,
    val position: FlightCoordinate?,
    val altitudeMeters: Double?,
    val baroHeightMeters: Double?,
    val targetHeightMeters: Double?,
    val hjCompatible: Boolean
)

object LegacyTelemetryDecoder {
    private const val RAW_LATITUDE_F32_OFFSET = 3
    private const val RAW_LONGITUDE_F32_OFFSET = 7
    private const val RAW_SATELLITE_OFFSET = 23
    private const val RAW_POWER_U16_OFFSET = 51
    private const val RAW_VOLTAGE_U16_OFFSET = 69
    private const val RAW_GEAR_CANDIDATE_OFFSET = 54
    private const val RAW_SWITCH_CONTROL_OFFSET = 59
    private const val RAW_REMOTE_CONTROL_ALARM_OFFSET = 77
    private const val RAW_BARO_HGT_U16_OFFSET = 47
    private const val RAW_TARGET_HGT_U16_OFFSET = 83
    private const val GEAR_WINDOW_SIZE = 12
    private const val MIN_VALID_VOLTAGE = 5.0
    private const val MAX_VALID_VOLTAGE = 20.0
    private const val MIN_VALID_BATTERY = 0.0
    private const val MAX_VALID_BATTERY = 100.0
    private const val MAX_VALID_SATELLITES = 40
    private const val MIN_VALID_RELATIVE_HEIGHT_METERS = -200.0
    private const val MAX_VALID_BARO_HEIGHT_METERS = 2000.0
    private const val MAX_VALID_TARGET_HEIGHT_METERS = 300.0
    private const val MAX_VALID_TARGET_TO_BARO_DELTA_METERS = 250.0

    fun decode(packet: RemoteTelemetryPacket?): LegacyTelemetrySnapshot? =
        packet?.let { decode(listOf(it)) }

    fun decode(packets: List<RemoteTelemetryPacket>): LegacyTelemetrySnapshot? {
        val latestPacket = selectTelemetryPacket(packets) ?: return null

        val satellites = latestPacket.rawUnsigned(RAW_SATELLITE_OFFSET)
        val droneBatteryExact = latestPacket.rawU16le(RAW_POWER_U16_OFFSET)?.div(256.0)
        val droneVoltage = latestPacket.rawU16le(RAW_VOLTAGE_U16_OFFSET)?.div(204.8)
        val switchControlState = latestPacket.rawUnsigned(RAW_SWITCH_CONTROL_OFFSET)
        val remoteControlAlarm = latestPacket.rawUnsigned(RAW_REMOTE_CONTROL_ALARM_OFFSET)
        val gearSelection = deriveGearSelection(packets)
        val position = decodePosition(latestPacket)
        val baroHeight = decodeRelativeHeightMeters(
            rawValue = latestPacket.rawS16le(RAW_BARO_HGT_U16_OFFSET),
            maxMeters = MAX_VALID_BARO_HEIGHT_METERS
        )
        val targetHeight = sanitizeTargetHeightMeters(
            decodedTargetMeters = decodeRelativeHeightMeters(
                rawValue = latestPacket.rawS16le(RAW_TARGET_HGT_U16_OFFSET),
                maxMeters = MAX_VALID_TARGET_HEIGHT_METERS
            ),
            decodedBaroMeters = baroHeight
        )

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
            remoteControlAlarm = remoteControlAlarm,
            position = position,
            altitudeMeters = baroHeight,
            baroHeightMeters = baroHeight,
            targetHeightMeters = targetHeight,
            hjCompatible = latestPacket.rawData.size >= 98
        )
    }

    private fun selectTelemetryPacket(packets: List<RemoteTelemetryPacket>): RemoteTelemetryPacket? {
        val recentPackets = packets.take(GEAR_WINDOW_SIZE)

        recentPackets.firstOrNull(::looksLikeTelemetryPacket)?.let { return it }
        return recentPackets.firstOrNull { candidate ->
            candidate.rawData.size > RAW_VOLTAGE_U16_OFFSET + 1
        }
    }

    private fun looksLikeTelemetryPacket(packet: RemoteTelemetryPacket): Boolean {
        if (packet.rawData.size <= RAW_VOLTAGE_U16_OFFSET + 1) return false

        val satellites = packet.rawUnsigned(RAW_SATELLITE_OFFSET) ?: return false
        if (satellites !in 0..MAX_VALID_SATELLITES) return false

        val droneBatteryExact = packet.rawU16le(RAW_POWER_U16_OFFSET)?.div(256.0) ?: return false
        if (droneBatteryExact !in MIN_VALID_BATTERY..MAX_VALID_BATTERY) return false

        val droneVoltage = packet.rawU16le(RAW_VOLTAGE_U16_OFFSET)?.div(204.8) ?: return false
        if (droneVoltage !in MIN_VALID_VOLTAGE..MAX_VALID_VOLTAGE) return false

        return true
    }

    fun decodePosition(packet: RemoteTelemetryPacket?): FlightCoordinate? {
        packet ?: return null

        val latitude = packet.rawF32le(RAW_LATITUDE_F32_OFFSET)?.toDouble() ?: return null
        val longitude = packet.rawF32le(RAW_LONGITUDE_F32_OFFSET)?.toDouble() ?: return null

        if (!latitude.isFinite() || !longitude.isFinite()) return null
        if (latitude !in -90.0..90.0) return null
        if (longitude !in -180.0..180.0) return null
        if (latitude == 0.0 && longitude == 0.0) return null

        return FlightCoordinate(latitude = latitude, longitude = longitude)
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

    private fun decodeRelativeHeightMeters(
        rawValue: Int?,
        maxMeters: Double
    ): Double? {
        val meters = rawValue?.div(10.0) ?: return null
        if (meters !in MIN_VALID_RELATIVE_HEIGHT_METERS..maxMeters) return null
        return meters
    }

    private fun sanitizeTargetHeightMeters(
        decodedTargetMeters: Double?,
        decodedBaroMeters: Double?
    ): Double? {
        decodedTargetMeters ?: return null
        decodedBaroMeters ?: return decodedTargetMeters

        if (kotlin.math.abs(decodedTargetMeters - decodedBaroMeters) > MAX_VALID_TARGET_TO_BARO_DELTA_METERS) {
            return null
        }

        return decodedTargetMeters
    }
}
