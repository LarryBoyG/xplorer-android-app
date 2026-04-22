package com.example.xirolite

import com.example.xirolite.data.CommandResult

object BetaInference {
    private const val LOW_POWER_WARNING_THRESHOLD = 20
    private const val CRITICAL_POWER_WARNING_THRESHOLD = 10
    private const val STABLE_NO_GPS_SAMPLE_COUNT = 3
    private const val LEGAL_HEIGHT_LIMIT_METERS = 120.0
    private const val LEGACY_ALARM_MAGNETIC_ERROR = 0x02
    private const val SD_CARD_PENDING_TEXT = "Pending camera decode"
    private const val FOV_PENDING_TEXT = "Pending camera callback"

    fun buildUiState(
        networkInfo: DroneNetworkInfo,
        telemetryResults: List<TelemetryResult>,
        watch3014Summary: Telemetry3014Summary?,
        recentRemotePackets: List<RemoteTelemetryPacket>,
        derivedFlightTelemetry: DerivedFlightTelemetry,
        remoteBatteryReading: RemoteBatteryReading? = null,
        relayProbeResults: List<CommandResult>,
        flightLogStatusText: String,
        measurementUnit: MeasurementUnit
    ): BetaUiState {
        val latestRemotePacket = recentRemotePackets.firstOrNull()
        val candidateFields = buildCandidates(latestRemotePacket, watch3014Summary, remoteBatteryReading)
        val relayState = buildRelayState(relayProbeResults)
        val droneState = buildDroneState(
            watch3014Summary = watch3014Summary,
            recentRemotePackets = recentRemotePackets,
            latestRemotePacket = latestRemotePacket,
            telemetryResults = telemetryResults,
            networkInfo = networkInfo,
            relayState = relayState,
            derivedFlightTelemetry = derivedFlightTelemetry,
            remoteBatteryReading = remoteBatteryReading,
            flightLogStatusText = flightLogStatusText,
            measurementUnit = measurementUnit
        )
        val preflight = buildPreflight(networkInfo, telemetryResults, latestRemotePacket, droneState)
        val warnings = buildFlightWarnings(
            recentRemotePackets = recentRemotePackets,
            measurementUnit = measurementUnit
        )
        return BetaUiState(
            droneState = droneState,
            relayState = relayState,
            preflight = preflight,
            warnings = warnings,
            candidateFields = candidateFields,
            relayProbeResults = relayProbeResults
        )
    }

    fun buildFlightWarnings(
        recentRemotePackets: List<RemoteTelemetryPacket>,
        measurementUnit: MeasurementUnit = MeasurementUnit.METRIC
    ): List<FlightWarning> {
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

        if (snapshot?.remoteControlAlarm?.let { it and LEGACY_ALARM_MAGNETIC_ERROR != 0 } == true) {
            warnings += FlightWarning(
                title = "Compass Calibration",
                detail = "Compass calibration failure. Recalibrate before flying.",
                severity = FlightWarningSeverity.CRITICAL
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

        snapshot?.baroHeightMeters
            ?.takeIf { it > LEGAL_HEIGHT_LIMIT_METERS }
            ?.let { heightMeters ->
                val currentHeight = formatAltitudeForUnit(heightMeters, measurementUnit)
                val legalLimit = formatAltitudeForUnit(LEGAL_HEIGHT_LIMIT_METERS, measurementUnit)
                warnings += FlightWarning(
                    title = "Over Legal Height Limit",
                    detail = "Aircraft is at $currentHeight. Lower below $legalLimit.",
                    severity = FlightWarningSeverity.CAUTION
                )
            }

        return warnings
    }

    private fun buildDroneState(
        watch3014Summary: Telemetry3014Summary?,
        recentRemotePackets: List<RemoteTelemetryPacket>,
        latestRemotePacket: RemoteTelemetryPacket?,
        telemetryResults: List<TelemetryResult>,
        networkInfo: DroneNetworkInfo,
        relayState: RelayStateUi,
        derivedFlightTelemetry: DerivedFlightTelemetry,
        remoteBatteryReading: RemoteBatteryReading?,
        flightLogStatusText: String,
        measurementUnit: MeasurementUnit
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

        val baroHeightText = derivedFlightTelemetry.altitudeMeters
            ?.let { formatAltitudeForUnit(it, measurementUnit) }
            ?: legacySnapshot?.baroHeightMeters
            ?.let { formatAltitudeForUnit(it, measurementUnit) }
            ?: legacySnapshot?.altitudeMeters
            ?.let { formatAltitudeForUnit(it, measurementUnit) }
            ?: "--"
        val targetHeightText = legacySnapshot?.targetHeightMeters
            ?.let { formatAltitudeForUnit(it, measurementUnit) }
            ?: "--"
        val speedText = derivedFlightTelemetry.speedMetersPerSecond
            ?.let { formatSpeedForUnit(it, measurementUnit) }
            ?: "--"
        val distanceText = derivedFlightTelemetry.distanceFromHomeMeters
            ?.let { formatDistanceForUnit(it, measurementUnit) }
            ?: "--"

        val cameraReady = telemetryResults.any { it.label == "CMD 3009" && it.status.startsWith("HTTP 200") }
        val sdCardSummary = telemetryResults.firstOrNull { it.label == "CMD 3014" }?.preview?.let { 
            TelemetryProbe().parse3014Summary(it)
        }
        val sdCardText = when {
            sdCardSummary?.value(3015) != null -> "${sdCardSummary.value(3015)} MB"
            cameraReady -> SD_CARD_PENDING_TEXT
            else -> "Unknown"
        }
        val fovText = if (cameraReady) FOV_PENDING_TEXT else "--"

        return DroneStateUi(
            baroHeightText = baroHeightText,
            targetHeightText = targetHeightText,
            speedText = speedText,
            distanceText = distanceText,
            voltageText = legacySnapshot?.droneVoltageVolts?.let { "${"%.2f".format(it)} V" } ?: "--",
            satelliteText = satCount,
            gpsText = gpsText,
            flightModeText = flightModeText,
            droneBatteryText = legacySnapshot?.droneBatteryPercent?.let { "$it%" }
                ?: LegacyTelemetryHints.droneBatteryHudText(packet),
            relaySignalText = buildWifiTelemetryText(networkInfo, relayState),
            sdCardText = sdCardText,
            remoteBatteryText = remoteBatteryReading?.let { "${it.percent}%" }
                ?: LegacyTelemetryHints.remoteBatteryHudText(packet),
            gearText = legacySnapshot?.gearSelection?.toString() ?: "--",
            flightLogText = flightLogStatusText,
            fovText = fovText,
            summary = LegacyTelemetryHints.hudSummaryText(packet, flightLogStatusText, remoteBatteryReading)
        )
    }

    private fun buildPreflight(
        networkInfo: DroneNetworkInfo,
        telemetryResults: List<TelemetryResult>,
        latestRemotePacket: RemoteTelemetryPacket?,
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
                label = "Remote Power",
                value = droneState.remoteBatteryText,
                ok = droneState.remoteBatteryText != "--"
            ),
            PreflightItem(
                label = "Wi-Fi Signal",
                value = droneState.relaySignalText,
                ok = droneState.relaySignalText != "--" && droneState.relaySignalText != "Unknown"
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
            ),
            PreflightItem(
                label = "Elevation",
                value = droneState.baroHeightText,
                ok = true
            ),
            PreflightItem(
                label = "Target Elevation",
                value = droneState.targetHeightText,
                ok = true
            ),
            PreflightItem(
                label = "SD Card",
                value = droneState.sdCardText,
                ok = droneState.sdCardText != "Unknown" && droneState.sdCardText != "Reading..."
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
            currentAirSignal = parsedCurrentAir
                ?.signal
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.take(16)
                ?: "--",
            availableNetworks = RelayPayloadParser.parseWifiCandidates(wifiResult?.preview.orEmpty()),
            lastProbeText = relayProbeResults.firstOrNull()?.status ?: "No relay probe yet"
        )
    }

    private fun buildWifiTelemetryText(
        networkInfo: DroneNetworkInfo,
        relayState: RelayStateUi
    ): String {
        val onExtender = networkInfo.localIp?.startsWith("192.168.2.") == true
        val onCameraDirect = networkInfo.localIp?.startsWith("192.168.1.") == true
        val relaySignal = relayState.currentAirSignal.takeIf { it != "--" }

        return when {
            onExtender && relaySignal != null -> "Link $relaySignal"
            onExtender -> "Waiting for relay link"
            onCameraDirect -> "N/A direct camera"
            else -> "--"
        }
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
        watch3014Summary: Telemetry3014Summary?,
        remoteBatteryReading: RemoteBatteryReading?
    ): List<TelemetryFieldCandidate> =
        LegacyTelemetryHints.buildCandidateFields(
            watch3014Summary = watch3014Summary,
            latestRemotePacket = latestRemotePacket,
            remoteBatteryReading = remoteBatteryReading
        )

    private fun candidateValue(packet: RemoteTelemetryPacket, index: Int): Int {
        return packet.watchedOffsets.firstOrNull { it.index == index }?.unsigned ?: -1
    }
}
