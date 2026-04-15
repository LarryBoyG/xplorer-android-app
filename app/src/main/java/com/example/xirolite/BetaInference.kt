package com.example.xirolite

import com.example.xirolite.data.CommandResult

object BetaInference {
    private const val LOW_POWER_WARNING_THRESHOLD = 20
    private const val CRITICAL_POWER_WARNING_THRESHOLD = 10
    private const val STABLE_NO_GPS_SAMPLE_COUNT = 3

    fun buildUiState(
        host: String,
        networkInfo: DroneNetworkInfo,
        telemetryResults: List<TelemetryResult>,
        watch3014Summary: Telemetry3014Summary?,
        recentRemotePackets: List<RemoteTelemetryPacket>,
        relayProbeResults: List<CommandResult>,
        flightLogStatusText: String
    ): BetaUiState {
        val latestRemotePacket = recentRemotePackets.firstOrNull()
        val candidateFields = buildCandidates(latestRemotePacket, watch3014Summary)
        val droneState = buildDroneState(
            watch3014Summary = watch3014Summary,
            recentRemotePackets = recentRemotePackets,
            latestRemotePacket = latestRemotePacket,
            telemetryResults = telemetryResults,
            flightLogStatusText = flightLogStatusText
        )
        val relayState = buildRelayState(relayProbeResults)
        val preflight = buildPreflight(host, networkInfo, telemetryResults, latestRemotePacket, relayState, droneState)
        val warnings = buildFlightWarnings(recentRemotePackets)
        return BetaUiState(
            droneState = droneState,
            relayState = relayState,
            preflight = preflight,
            warnings = warnings,
            candidateFields = candidateFields,
            relayProbeResults = relayProbeResults
        )
    }

    fun buildFlightWarnings(recentRemotePackets: List<RemoteTelemetryPacket>): List<FlightWarning> {
        val snapshot = LegacyTelemetryDecoder.decode(recentRemotePackets)
        val warnings = mutableListOf<FlightWarning>()

        val recentSatelliteSamples = recentRemotePackets
            .take(STABLE_NO_GPS_SAMPLE_COUNT)
            .mapNotNull { it.rawUnsigned(23) }
        val stableNoGps = recentSatelliteSamples.size == STABLE_NO_GPS_SAMPLE_COUNT &&
            recentSatelliteSamples.all { it <= 0 }
        if (stableNoGps) {
            warnings += FlightWarning(
                title = "No GPS",
                detail = "Aircraft is in Attitude until satellites are available.",
                severity = FlightWarningSeverity.CAUTION
            )
        }

        snapshot?.droneBatteryPercent?.let { batteryPercent ->
            when {
                batteryPercent <= CRITICAL_POWER_WARNING_THRESHOLD -> warnings += FlightWarning(
                    title = "Aircraft Power Critical",
                    detail = "Aircraft power is $batteryPercent%. Land as soon as possible.",
                    severity = FlightWarningSeverity.CRITICAL
                )

                batteryPercent <= LOW_POWER_WARNING_THRESHOLD -> warnings += FlightWarning(
                    title = "Aircraft Power Low",
                    detail = "Aircraft power is $batteryPercent%. Plan to land soon.",
                    severity = FlightWarningSeverity.CAUTION
                )
            }
        }

        return warnings
    }

    private fun buildDroneState(
        watch3014Summary: Telemetry3014Summary?,
        recentRemotePackets: List<RemoteTelemetryPacket>,
        latestRemotePacket: RemoteTelemetryPacket?,
        telemetryResults: List<TelemetryResult>,
        flightLogStatusText: String
    ): DroneStateUi {
        val packet = latestRemotePacket
        val summary = watch3014Summary
        val legacySnapshot = LegacyTelemetryDecoder.decode(recentRemotePackets)

        val satCount = when {
            legacySnapshot?.satellites != null -> legacySnapshot.satellites.toString()
            summary?.value(2033) != null -> summary.value(2033).toString()
            else -> "--"
        }

        val gpsText = when {
            legacySnapshot?.gpsText != null -> legacySnapshot.gpsText
            summary?.value(2033) != null -> when (summary.value(2033)) {
                0 -> "No lock"
                1 -> "Acquiring"
                2 -> "Likely locked"
                else -> "${summary.value(2033)} sats?"
            }
            packet == null -> "Unknown"
            else -> "Waiting for calibrated packet"
        }

        val flightModeText = when {
            legacySnapshot?.flightModeText != null -> legacySnapshot.flightModeText
            summary?.value(2034) != null -> "Mode ${summary.value(2034)}"
            else -> packet?.stateGuess?.label ?: "Unknown"
        }

        val heightText = "--"
        val speedText = "--"
        val distanceText = "--"

        val cameraReady = telemetryResults.any { it.label == "CMD 3009" && it.status.startsWith("HTTP 200") }
        val sdCardText = if (cameraReady) "Camera reachable" else "Unknown"

        return DroneStateUi(
            heightText = heightText,
            speedText = speedText,
            distanceText = distanceText,
            voltageText = legacySnapshot?.droneVoltageVolts?.let { "${"%.2f".format(it)} V" } ?: "--",
            satelliteText = satCount,
            gpsText = gpsText,
            flightModeText = flightModeText,
            droneBatteryText = LegacyTelemetryHints.droneBatteryHudText(packet),
            relaySignalText = packet?.watchedOffsets?.firstOrNull { it.index == 41 }?.unsigned?.toString() ?: "--",
            sdCardText = sdCardText,
            remoteBatteryText = LegacyTelemetryHints.remoteBatteryHudText(packet),
            gearText = legacySnapshot?.gearSelection?.toString() ?: "--",
            flightLogText = flightLogStatusText,
            fovText = "RTSP",
            summary = LegacyTelemetryHints.hudSummaryText(packet, flightLogStatusText)
        )
    }

    private fun buildPreflight(
        host: String,
        networkInfo: DroneNetworkInfo,
        telemetryResults: List<TelemetryResult>,
        latestRemotePacket: RemoteTelemetryPacket?,
        relayState: RelayStateUi,
        droneState: DroneStateUi
    ): List<PreflightItem> {
        val onXiroWifi = networkInfo.localIp?.startsWith("192.168.2.") == true ||
            networkInfo.localIp?.startsWith("192.168.1.") == true
        val cameraConnected = latestRemotePacket != null ||
            telemetryResults.any { it.status.startsWith("HTTP 200") } ||
            onXiroWifi

        return listOf(
            PreflightItem(
                label = "GPS Sat",
                value = droneState.satelliteText,
                ok = droneState.satelliteText.toIntOrNull()?.let { it > 0 } == true
            ),
            PreflightItem(
                label = "Camera",
                value = if (cameraConnected) "Connected" else "Not connected",
                ok = cameraConnected
            ),
            PreflightItem(
                label = "Aircraft Power",
                value = droneState.droneBatteryText,
                ok = droneState.droneBatteryText != "--"
            ),
            PreflightItem(
                label = "Wi-Fi Signal",
                value = networkInfo.wifiSignalText,
                ok = networkInfo.wifiSignalText != "Unknown"
            ),
            PreflightItem(
                label = "Flight Mode",
                value = droneState.flightModeText,
                ok = droneState.flightModeText != "Unknown"
            ),
            PreflightItem(
                label = "Gear",
                value = droneState.gearText,
                ok = droneState.gearText != "--"
            )
        )
    }

    private fun buildRelayState(relayProbeResults: List<CommandResult>): RelayStateUi {
        val statusResult = relayProbeResults.firstOrNull { it.label.contains("Repeater Status") }
        val versionResult = relayProbeResults.firstOrNull { it.label.contains("Repeater Version") }
        val wifiResult = relayProbeResults.firstOrNull { it.label.contains("Wi-Fi List") }
        val currentAir = relayProbeResults.firstOrNull { it.label.contains("Current Air Wi-Fi") }
        val parsedStatus = RelayPayloadParser.parseRepeaterStatus(statusResult?.preview.orEmpty())
        val parsedCurrentAir = RelayPayloadParser.parseCurrentAirWifi(currentAir?.preview.orEmpty())
        val boundFromStatus = RelayPayloadParser.extractLikelyBoundSsid(statusResult?.preview.orEmpty())
        val boundFromCurrentAir = RelayPayloadParser.extractLikelyBoundSsid(currentAir?.preview.orEmpty())

        val statusText = when {
            statusResult == null -> "Unknown"
            parsedStatus?.repeaterStatus?.contains("connected", true) == true -> "Connected"
            parsedStatus?.repeaterStatus?.contains("connecting", true) == true -> "Connecting"
            parsedStatus?.repeaterStatus?.contains("disconnect", true) == true -> "Disconnected"
            statusResult.preview.contains("connected", true) -> "Connected"
            statusResult.preview.contains("connecting", true) -> "Connecting"
            statusResult.preview.contains("disconnect", true) -> "Disconnected"
            !boundFromStatus.isNullOrBlank() -> "Connected"
            !parsedCurrentAir?.ssid.isNullOrBlank() -> "Connected"
            relayFieldLooksUnknown(statusResult.preview) -> "Waiting for repeater response"
            else -> statusResult.status
        }

        return RelayStateUi(
            status = statusText,
            version = versionResult?.preview?.lineSequence()?.firstOrNull()?.take(40) ?: "--",
            currentAirWifi = parsedCurrentAir
                ?.ssid
                ?.takeIf { !relayFieldLooksUnknown(it) }
                ?.take(40)
                ?: boundFromCurrentAir
                    ?.takeIf { !relayFieldLooksUnknown(it) }
                    ?.take(40)
                ?: boundFromStatus
                    ?.takeIf { !relayFieldLooksUnknown(it) }
                    ?.take(40)
                ?: currentAir
                    ?.preview
                    ?.lineSequence()
                    ?.map { it.trim() }
                    ?.firstOrNull { !relayFieldLooksUnknown(it) }
                    ?.take(40)
                ?: "--",
            availableNetworks = RelayPayloadParser.parseWifiCandidates(wifiResult?.preview.orEmpty()),
            lastProbeText = relayProbeResults.firstOrNull()?.status ?: "No relay probe yet"
        )
    }

    private fun isSelectableWifiRow(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return false
        if (trimmed == "<empty body>") return false
        if (trimmed.startsWith("<") || trimmed.startsWith("{") || trimmed.startsWith("[")) return false
        if (trimmed.contains("socket://", ignoreCase = true)) return false
        if (trimmed.contains("HTTP/", ignoreCase = true)) return false
        if (trimmed.contains("Connection refused", ignoreCase = true)) return false
        if (trimmed.contains("Exception", ignoreCase = true)) return false
        if (trimmed.contains("SocketTimeout", ignoreCase = true)) return false
        if (trimmed.contains("SOCKET OK", ignoreCase = true)) return false
        if (relayFieldLooksUnknown(trimmed)) return false
        if (!trimmed.any { ch -> ch.isLetterOrDigit() }) return false
        if (!trimmed.any { ch -> ch.isLetter() }) return false
        return trimmed.length in 2..64
    }

    private fun relayFieldLooksUnknown(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank() || trimmed == "--" || trimmed == "<empty body>") return true
        if (trimmed.equals("ff", ignoreCase = true)) return true
        if (trimmed.length <= 4 && trimmed.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return true
        if (trimmed.matches(Regex("(?:[0-9A-Fa-f]{2}\\s*){1,4}"))) return true
        return false
    }

    private fun buildCandidates(
        latestRemotePacket: RemoteTelemetryPacket?,
        watch3014Summary: Telemetry3014Summary?
    ): List<TelemetryFieldCandidate> =
        LegacyTelemetryHints.buildCandidateFields(
            watch3014Summary = watch3014Summary,
            latestRemotePacket = latestRemotePacket
        )

    private fun candidateValue(packet: RemoteTelemetryPacket, index: Int): Int {
        return packet.watchedOffsets.firstOrNull { it.index == index }?.unsigned ?: -1
    }
}
