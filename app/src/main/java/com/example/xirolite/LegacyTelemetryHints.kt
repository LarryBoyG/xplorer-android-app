package com.example.xirolite

import kotlin.math.roundToInt

data class LegacyTelemetryTarget(
    val fieldName: String,
    val description: String,
    val confidence: String,
    val currentLeads: List<String>
)

object LegacyTelemetryHints {

    private val offsetLabels = mapOf(
        23 to "Satellites",
        28 to "State Family",
        29 to "State Subtype",
        41 to "Signal / Status",
        46 to "Control Step A",
        47 to "Control Step B",
        51 to "Drone Power (Lo)",
        52 to "Drone Power (Hi)",
        54 to "Gear Candidate",
        57 to "Navigation Window",
        59 to "Switch / Control State",
        33 to "Altitude (Lo)",
        34 to "Altitude (Hi)",
        69 to "Voltage (Lo)",
        70 to "Voltage (Hi)",
        77 to "Remote Control Alarm",
        79 to "Power Window (Legacy Guess)",
        86 to "Tail / Error A",
        87 to "Tail / Error B",
        88 to "Tail / Error C",
        97 to "Remote Battery False Lead"
    )

    private val offsetHints = mapOf(
        23 to "High-confidence legacy satellite count lead. It matched XIRO Assistant satellite values in the filled HJ checkpoints.",
        28 to "State family byte. Useful when comparing only flight mode or switch changes.",
        29 to "State subtype byte. Useful when comparing only flight mode or switch changes.",
        41 to "Signal/status window candidate. Not tied to a recovered legacy field yet.",
        46 to "Control-step candidate pair lead. Compare when only the physical switch changes.",
        47 to "Control-step candidate pair lead. Compare when only the physical switch changes.",
        51 to "Low byte of the validated drone power percent pair.",
        52 to "High byte of the validated drone power percent pair.",
        54 to "Best current dedicated flight-gear lead. XIRO Lite now prefers this byte for the 1/2/3 Gear HUD when it stays stable.",
        57 to "Navigation-state window candidate. Useful for GPS and return-home comparisons.",
        59 to "Raw control-state lead. Useful for debugging mode transitions, but noisy enough that it should not be shown directly as Gear.",
        33 to "Low byte of the altitude candidate (u16 @ 33 / 10.0).",
        34 to "High byte of the altitude candidate (u16 @ 33 / 10.0).",
        69 to "Low byte of the validated displayed voltage pair.",
        70 to "High byte of the validated displayed voltage pair.",
        77 to "Validated legacy remoteControlAlarm bitmask. Bit 0x02 matches the legacy MAGNETIC_ERROR compass calibration alert.",
        79 to "Older power-window guess kept for comparison with earlier probe logs.",
        86 to "Tail/error-state candidate. Useful for return-home and alert comparisons.",
        87 to "Tail/error-state candidate. Useful for return-home and alert comparisons.",
        88 to "Tail/error-state candidate. Useful for return-home and alert comparisons.",
        97 to "Do not trust as remote battery. Legacy remote battery uses a separate callback."
    )

    private val pairLabels = mapOf(
        19 to "Stick Pair",
        20 to "Stick Step Pair",
        28 to "State Pair",
        46 to "Control Step Pair",
        51 to "Drone Power %",
        57 to "Navigation Pair",
        33 to "Altitude",
        69 to "Drone Voltage",
        77 to "Remote Alarm",
        79 to "Power Pair (Legacy Guess)",
        86 to "Tail / Error Pair"
    )

    private val pairHints = mapOf(
        19 to "Manual-stick family pair. Useful when the remote is actively being moved.",
        20 to "Manual-stick family pair. Useful when the remote is actively being moved.",
        28 to "State family/subtype pair. Useful for currentControlState and flightGear comparisons.",
        46 to "Control-step pair candidate. Useful when only the remote switch changes.",
        51 to "High-confidence legacy drone power pair. XIRO Assistant power matched u16 / 256 exactly in the filled HJ checkpoints.",
        57 to "Navigation-state pair candidate. Useful for GPS and turn-back comparisons.",
        33 to "Altitude candidate from u16 @ 33 / 10.0 meters. Recovered from legacy HJ analysis.",
        69 to "High-confidence legacy displayed voltage pair. XIRO Assistant voltage matched u16 / 204.8 exactly in the filled HJ checkpoints.",
        77 to "Validated remoteControlAlarm byte. Bit 0x02 is the legacy compass/magnetic calibration failure flag.",
        79 to "Older power-pair guess kept for comparison with earlier probe logs.",
        86 to "Low-confidence turn-back / error-state pair candidate."
    )

    fun knownOffsetIndexes(): List<Int> = offsetLabels.keys.sorted()

    fun knownPairStartIndexes(): List<Int> = pairLabels.keys.sorted()

    fun offsetLabel(index: Int): String = offsetLabels[index] ?: "UDP[$index]"

    fun pairLabel(startIndex: Int): String = pairLabels[startIndex] ?: "UDP[$startIndex..${startIndex + 1}]"

    fun offsetHint(index: Int): String? = offsetHints[index]

    fun pairHint(startIndex: Int): String? = pairHints[startIndex]

    fun droneBatteryHudText(packet: RemoteTelemetryPacket?): String {
        val snapshot = LegacyTelemetryDecoder.decode(packet)
        return snapshot?.droneBatteryPercent?.let { "$it%" } ?: "--"
    }

    fun remoteBatteryHudText(packet: RemoteTelemetryPacket?): String = "--"

    fun hudSummaryText(
        packet: RemoteTelemetryPacket?,
        hjStatusText: String,
        remoteBatteryReading: RemoteBatteryReading? = null
    ): String {
        val snapshot = LegacyTelemetryDecoder.decode(packet)
        val details = mutableListOf(
            "Legacy-calibrated UDP decode: power from u16@51/256, voltage from u16@69/204.8, satellites from b23."
        )
        snapshot?.gearSelection?.let {
            details += "Gear HUD decode: $it (stabilized 1..3 from UDP[54] with UDP[59] fallback)."
        }
        snapshot?.switchControlState?.let { details += "Raw control-state lead: UDP[59]=$it." }
        if (hjStatusText.isNotBlank()) {
            details += "HJ log: $hjStatusText."
        }
        if (remoteBatteryReading != null) {
            details += "Remote battery: ${remoteBatteryReading.percent}% from TCP 6666 raw ${remoteBatteryReading.raw}."
        } else {
            details += "Remote battery waits for the TCP 6666 legacy callback."
        }
        return details.joinToString(" ")
    }

    fun buildCandidateFields(
        watch3014Summary: Telemetry3014Summary?,
        latestRemotePacket: RemoteTelemetryPacket?,
        remoteBatteryReading: RemoteBatteryReading? = null
    ): List<TelemetryFieldCandidate> {
        val packet = latestRemotePacket
        val snapshot = LegacyTelemetryDecoder.decode(packet)

        return listOf(
            TelemetryFieldCandidate(
                label = "ZDDroneState.searchStarNum / Satellites",
                value = snapshot?.satellites?.toString()
                    ?: watch3014Summary?.value(2033)?.toString()
                    ?: "Waiting for UDP 6800",
                confidence = if (snapshot?.satellites != null) "high" else if (watch3014Summary?.value(2033) != null) "medium" else "tracking",
                source = if (snapshot?.satellites != null) {
                    "Validated against XIRO Assistant and HJ checkpoints using raw UDP byte 23."
                } else {
                    "Fallback guess from 3014/2033 until a live UDP packet is available."
                }
            ),
            TelemetryFieldCandidate(
                label = "ZDDroneState.height / Baro HGT",
                value = snapshot?.baroHeightMeters?.let { "${it.format(1)} m  (raw ${packet?.rawU16le(47)})" }
                    ?: "Waiting for UDP 6800",
                confidence = if (snapshot?.baroHeightMeters != null) "high" else "tracking",
                source = "Baro HGT recovered from u16@47 / 10.0 based on HJ-compatible log analysis."
            ),
            TelemetryFieldCandidate(
                label = "ZDDroneState.targetHeight / Target HGT",
                value = snapshot?.targetHeightMeters?.let { "${it.format(1)} m  (raw ${packet?.rawU16le(83)})" }
                    ?: "Waiting for UDP 6800",
                confidence = if (snapshot?.targetHeightMeters != null) "high" else "tracking",
                source = "Target HGT recovered from u16@83 / 10.0 based on HJ-compatible log analysis."
            ),
            TelemetryFieldCandidate(
                label = "ZDDroneState.power",
                value = snapshot?.droneBatteryPercentExact?.let { "${it.format(2)}%  (raw ${packet?.rawU16le(51)})" }
                    ?: "Waiting for UDP 6800",
                confidence = if (snapshot?.droneBatteryPercentExact != null) "high" else "tracking",
                source = "Validated against XIRO Assistant and HJ checkpoints using u16@51 / 256."
            ),
            TelemetryFieldCandidate(
                label = "ZDDroneState.voltage",
                value = snapshot?.droneVoltageVolts?.let { "${it.format(2)} V  (raw ${packet?.rawU16le(69)})" }
                    ?: "Waiting for UDP 6800",
                confidence = if (snapshot?.droneVoltageVolts != null) "high" else "tracking",
                source = "Validated against XIRO Assistant and HJ checkpoints using u16@69 / 204.8."
            ),
            TelemetryFieldCandidate(
                label = "ZDPositioningMode",
                value = snapshot?.flightModeText
                    ?: watch3014Summary?.value(2034)?.let { "3014/2034 = $it" }
                    ?: "Waiting for UDP 6800",
                confidence = if (snapshot?.flightModeText != null) "medium" else if (watch3014Summary?.value(2034) != null) "low" else "tracking",
                source = if (snapshot?.flightModeText != null) {
                    "Current XIRO Lite mapping follows the field-observed threshold: 0-6 sats => Attitude, 7+ sats => GPS Mode."
                } else {
                    "Fallback to older 3014 flight-mode guess until live UDP is available."
                }
            ),
            TelemetryFieldCandidate(
                label = "ZDDroneState.flightGear",
                value = snapshot?.gearSelection?.let { "Gear = $it  (prefers UDP[54], falls back to stabilized UDP[59])" }
                    ?: "Waiting for stable 1..3 packets",
                confidence = if (snapshot?.gearSelection != null) "medium" else "tracking",
                source = "User-facing Gear now uses a stabilized 1..3 decode so raw control-state spikes do not leak into the HUD."
            ),
            TelemetryFieldCandidate(
                label = "ZDDroneState.currentControlState",
                value = snapshot?.switchControlState?.let { "UDP[59] = $it" } ?: "Waiting for UDP 6800",
                confidence = if (snapshot?.switchControlState != null) "medium" else "tracking",
                source = "Raw control-state lead kept for debug comparisons when testing switch and mode transitions."
            ),
            TelemetryFieldCandidate(
                label = "ZDDroneState.remoteControlAlarm",
                value = snapshot?.remoteControlAlarm?.let { "UDP[77] = 0x%02X".format(it) }
                    ?: "Waiting for UDP 6800",
                confidence = if (snapshot?.remoteControlAlarm != null) "high" else "tracking",
                source = "Validated against the compass-failure pcap: bit 0x02 matches the legacy MAGNETIC_ERROR alert."
            ),
            TelemetryFieldCandidate(
                label = "Remote battery",
                value = remoteBatteryReading?.let {
                    "${it.percent}%  (TCP 6666 raw ${it.raw}, ${it.responseHex})"
                } ?: packet?.rawUnsigned(97)?.let { "Waiting for TCP 6666 callback. Ignore UDP[97]=$it as percent." }
                    ?: "Waiting for TCP 6666 callback",
                confidence = if (remoteBatteryReading != null) "experimental high" else "tracking",
                source = "Recovered from the legacy SDK getRemoteElectricity callback and validated against 40%, 60%, 80%, and 100% captures."
            )
        )
    }

    fun buildRecoveredTargets(
        watch3014Summary: Telemetry3014Summary?,
        latestRemotePacket: RemoteTelemetryPacket?
    ): List<LegacyTelemetryTarget> {
        val snapshot = LegacyTelemetryDecoder.decode(latestRemotePacket)
        return listOf(
            LegacyTelemetryTarget(
                fieldName = "ZDDroneState.height / Baro HGT",
                description = "Barometric height field recovered from HJ analysis. Units are in meters.",
                confidence = if (snapshot?.baroHeightMeters != null) "high" else "tracking",
                currentLeads = listOfNotNull(
                    latestRemotePacket?.rawU16le(47)?.let { "u16@47 = $it" },
                    snapshot?.baroHeightMeters?.let { "u16@47 / 10.0 = ${it.format(1)} m" }
                ).ifEmpty { listOf("No live UDP packet available.") }
            ),
            LegacyTelemetryTarget(
                fieldName = "ZDDroneState.targetHeight / Target HGT",
                description = "Target height field recovered from HJ analysis. Units are in meters.",
                confidence = if (snapshot?.targetHeightMeters != null) "high" else "tracking",
                currentLeads = listOfNotNull(
                    latestRemotePacket?.rawU16le(83)?.let { "u16@83 = $it" },
                    snapshot?.targetHeightMeters?.let { "u16@83 / 10.0 = ${it.format(1)} m" }
                ).ifEmpty { listOf("No live UDP packet available.") }
            ),
            LegacyTelemetryTarget(
                fieldName = "ZDDroneState.power",
                description = "Legacy drone battery field recovered from HJ and validated against XIRO Assistant.",
                confidence = if (snapshot?.droneBatteryPercentExact != null) "high" else "tracking",
                currentLeads = listOfNotNull(
                    latestRemotePacket?.rawU16le(51)?.let { "u16@51 = $it" },
                    snapshot?.droneBatteryPercentExact?.let { "u16@51 / 256 = ${it.format(2)}%" }
                ).ifEmpty { listOf("No live UDP packet available.") }
            ),
            LegacyTelemetryTarget(
                fieldName = "ZDDroneState.voltage",
                description = "Legacy displayed voltage field recovered from HJ and validated against XIRO Assistant.",
                confidence = if (snapshot?.droneVoltageVolts != null) "high" else "tracking",
                currentLeads = listOfNotNull(
                    latestRemotePacket?.rawU16le(69)?.let { "u16@69 = $it" },
                    snapshot?.droneVoltageVolts?.let { "u16@69 / 204.8 = ${it.format(2)} V" }
                ).ifEmpty { listOf("No live UDP packet available.") }
            ),
            LegacyTelemetryTarget(
                fieldName = "ZDDroneState.searchStarNum",
                description = "Legacy satellite count field recovered from HJ and validated against XIRO Assistant.",
                confidence = if (snapshot?.satellites != null) "high" else if (watch3014Summary?.value(2033) != null) "medium" else "tracking",
                currentLeads = listOfNotNull(
                    latestRemotePacket?.rawUnsigned(23)?.let { "b23 = $it satellites" },
                    watch3014Summary?.value(2033)?.let { "3014/2033 = $it" }
                ).ifEmpty { listOf("No live UDP packet or 3014 summary available.") }
            ),
            LegacyTelemetryTarget(
                fieldName = "ZDDroneState.flightGear",
                description = "Legacy Gear HUD field should stay within 1..3. XIRO Lite now prefers a dedicated gear byte and only falls back to stabilized control-state values.",
                confidence = if (snapshot?.gearSelection != null) "medium" else "tracking",
                currentLeads = listOfNotNull(
                    snapshot?.gearSelection?.let { "Stabilized HUD Gear = $it" },
                    latestRemotePacket?.rawUnsigned(54)?.let { "UDP[54] gear lead = $it" },
                    latestRemotePacket?.rawUnsigned(59)?.let { "UDP[59] raw control state = $it" }
                ).ifEmpty { listOf("No live UDP packet or 3014 summary available.") }
            ),
            LegacyTelemetryTarget(
                fieldName = "ZDPositioningMode / currentControlState",
                description = "Legacy HUD mode label appears to be derived from satellite lock strength plus control state, not from a single raw switch byte.",
                confidence = if (snapshot?.flightModeText != null) "medium" else "tracking",
                currentLeads = listOfNotNull(
                    snapshot?.flightModeText?.let { "Derived HUD mode = $it" },
                    latestRemotePacket?.rawUnsigned(59)?.let { "UDP[59] raw control-state lead = $it" },
                    watch3014Summary?.value(2034)?.let { "3014/2034 = $it" }
                ).ifEmpty { listOf("No live UDP packet or 3014 summary available.") }
            ),
            LegacyTelemetryTarget(
                fieldName = "ZDDroneState.remoteControlAlarm",
                description = "Legacy alarm bitmask recovered from the 98-byte UDP state packet. Bit 0x02 is the compass/magnetic calibration failure flag.",
                confidence = if (snapshot?.remoteControlAlarm != null) "high" else "tracking",
                currentLeads = listOfNotNull(
                    latestRemotePacket?.rawUnsigned(77)?.let { "UDP[77] = 0x%02X".format(it) },
                    snapshot?.remoteControlAlarm?.takeIf { it and 0x02 != 0 }?.let { "MAGNETIC_ERROR bit set." }
                ).ifEmpty { listOf("No live UDP packet available.") }
            ),
            LegacyTelemetryTarget(
                fieldName = "Remote battery",
                description = "Legacy remote battery comes from the TCP 6666 getRemoteElectricity callback, not the main drone-state blob.",
                confidence = "experimental high",
                currentLeads = listOf(
                    latestRemotePacket?.rawUnsigned(97)?.let { "Ignore UDP[97] = $it as a battery percent." }
                        ?: "No live UDP packet available."
                )
            )
        )
    }

    fun calibrationChecklist(): List<String> = listOf(
        "Use UDP 6800 for live HUD values: drone power, displayed voltage, satellites, and stabilized Gear.",
        "Remote battery uses the TCP 6666 legacy getRemoteElectricity callback: request 1A 06 AC 06 D2, response 1A 06 AC 03 XX YY.",
        "The current flight-mode label in XIRO Lite follows the field-observed threshold: 0-6 satellites => Attitude, 7+ satellites => GPS Mode.",
        "Compass/magnetic calibration warnings are decoded from remoteControlAlarm bit 0x02 at UDP[77].",
        "Use HJ checkpoints when validating any new candidate against XIRO Assistant.",
        "Keep raw UDP windows visible in Debug Mode when testing switch or satellite transitions, especially UDP[54] and UDP[59]."
    )
}

private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
