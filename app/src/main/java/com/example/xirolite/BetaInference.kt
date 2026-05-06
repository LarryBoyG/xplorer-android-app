package com.example.xirolite

import com.example.xirolite.data.CommandResult

object BetaInference {
    private const val LOW_POWER_WARNING_THRESHOLD = 20
    private const val CRITICAL_POWER_WARNING_THRESHOLD = 10
    private const val TELEMETRY_STALE_AFTER_MS = 5_000L
    private const val TELEMETRY_LOST_AFTER_MS = 15_000L
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
        measurementUnit: MeasurementUnit,
        nowMs: Long = System.currentTimeMillis()
    ): BetaUiState {
        val latestRemotePacket = recentRemotePackets.firstOrNull()
        val remoteTelemetryFresh = isTelemetryFresh(latestRemotePacket, nowMs)
        val candidateFields = buildCandidates(latestRemotePacket, watch3014Summary, remoteBatteryReading)
        val relayState = buildRelayState(relayProbeResults)
        val droneState = buildDroneState(
            watch3014Summary = watch3014Summary,
            recentRemotePackets = recentRemotePackets,
            latestRemotePacket = latestRemotePacket,
            remoteTelemetryFresh = remoteTelemetryFresh,
            telemetryResults = telemetryResults,
            networkInfo = networkInfo,
            relayState = relayState,
            derivedFlightTelemetry = derivedFlightTelemetry,
            remoteBatteryReading = remoteBatteryReading,
            flightLogStatusText = flightLogStatusText,
            measurementUnit = measurementUnit
        )
        val preflight = buildPreflight(
            networkInfo = networkInfo,
            telemetryResults = telemetryResults,
            relayState = relayState,
            droneState = droneState
        )
        val warnings = buildFlightWarnings(
            recentRemotePackets = recentRemotePackets,
            measurementUnit = measurementUnit,
            nowMs = nowMs
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
        measurementUnit: MeasurementUnit = MeasurementUnit.METRIC,
        nowMs: Long = System.currentTimeMillis()
    ): List<FlightWarning> {
        val latestRemotePacket = recentRemotePackets.firstOrNull()
        val telemetryAgeMs = latestRemotePacket?.let { (nowMs - it.timestampMs).coerceAtLeast(0L) }
        val remoteTelemetryFresh = isTelemetryFresh(latestRemotePacket, nowMs)
        val snapshot = if (remoteTelemetryFresh) LegacyTelemetryDecoder.decode(recentRemotePackets) else null
        val warnings = mutableListOf<FlightWarning>()

        if (telemetryAgeMs != null && !remoteTelemetryFresh) {
            warnings += FlightWarning(
                title = if (telemetryAgeMs >= TELEMETRY_LOST_AFTER_MS) "Telemetry Lost" else "Telemetry Stale",
                detail = "No fresh UDP 6800 telemetry for ${formatAge(telemetryAgeMs)}. GPS, flight mode, aircraft power, gear, and elevation are hidden until live packets resume.",
                severity = if (telemetryAgeMs >= TELEMETRY_LOST_AFTER_MS) {
                    FlightWarningSeverity.CRITICAL
                } else {
                    FlightWarningSeverity.CAUTION
                }
            )
        }

        val recentSatelliteSamples = if (remoteTelemetryFresh) {
            recentRemotePackets
                .take(STABLE_NO_GPS_SAMPLE_COUNT)
                .mapNotNull { it.rawUnsigned(23) }
        } else {
            emptyList()
        }
        val stableLowGpsLock = recentSatelliteSamples.size == STABLE_NO_GPS_SAMPLE_COUNT &&
            recentSatelliteSamples.all { !LegacyTelemetryDecoder.hasGpsModeSatelliteLock(it) }
        if (stableLowGpsLock) {
            warnings += FlightWarning(
                title = "GPS Lock Low",
                detail = "Aircraft remains in Attitude until GPS lock reaches ${LegacyTelemetryDecoder.GPS_MODE_MIN_SATELLITES}+ satellites.",
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
                    detail = "Aircraft power is $batteryPercent%. Land immediately if safe; automatic return or landing behavior may already be active.",
                    severity = FlightWarningSeverity.CRITICAL
                )

                batteryPercent <= LOW_POWER_WARNING_THRESHOLD -> warnings += FlightWarning(
                    title = "Low Battery Return Home",
                    detail = "Aircraft power is $batteryPercent%. The flight controller may start return-home automatically; monitor position and prepare to land.",
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
        remoteTelemetryFresh: Boolean,
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
        val legacySnapshot = if (remoteTelemetryFresh) LegacyTelemetryDecoder.decode(recentRemotePackets) else null

        val satCount = when {
            packet != null && !remoteTelemetryFresh -> "Stale"
            legacySnapshot?.satellites != null -> legacySnapshot.satellites.toString()
            summary?.value(2033) != null -> summary.value(2033).toString()
            else -> "--"
        }

        val gpsText = when {
            packet != null && !remoteTelemetryFresh -> "Telemetry stale"
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
            packet != null && !remoteTelemetryFresh -> "Telemetry stale"
            legacySnapshot?.flightModeText != null -> legacySnapshot.flightModeText
            summary?.value(2034) != null -> "Mode ${summary.value(2034)}"
            else -> packet?.stateGuess?.label ?: "Unknown"
        }

        val baroHeightText = if (packet != null && !remoteTelemetryFresh) {
            "Stale"
        } else {
            derivedFlightTelemetry.altitudeMeters
                ?.let { formatAltitudeForUnit(it, measurementUnit) }
                ?: legacySnapshot?.baroHeightMeters
                    ?.let { formatAltitudeForUnit(it, measurementUnit) }
                ?: legacySnapshot?.altitudeMeters
                    ?.let { formatAltitudeForUnit(it, measurementUnit) }
                ?: "--"
        }
        val targetHeightText = legacySnapshot?.targetHeightMeters
            ?.let { formatAltitudeForUnit(it, measurementUnit) }
            ?: if (packet != null && !remoteTelemetryFresh) "Stale" else null
            ?: "--"
        val speedText = if (packet != null && !remoteTelemetryFresh) {
            "Stale"
        } else {
            derivedFlightTelemetry.speedMetersPerSecond
                ?.let { formatSpeedForUnit(it, measurementUnit) }
                ?: "--"
        }
        val distanceText = if (packet != null && !remoteTelemetryFresh) {
            "Stale"
        } else {
            derivedFlightTelemetry.distanceFromHomeMeters
                ?.let { formatDistanceForUnit(it, measurementUnit) }
                ?: "--"
        }

        val cameraReady = telemetryResults.any { it.label == "CMD 3009" && it.status.startsWith("HTTP 200") }
        val sdCardSummary = telemetryResults.firstOrNull { it.label == "CMD 3014" }?.preview?.let { 
            TelemetryProbe().parse3014Summary(it)
        }
        val sdCardText = buildSdCardText(
            telemetryResults = telemetryResults,
            fallback3014Summary = sdCardSummary,
            cameraReady = cameraReady
        )
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
                ?: if (packet != null && !remoteTelemetryFresh) "Stale" else LegacyTelemetryHints.droneBatteryHudText(packet),
            relaySignalText = buildWifiTelemetryText(networkInfo, relayState),
            sdCardText = sdCardText,
            remoteBatteryText = remoteBatteryReading?.let { "${it.percent}%" }
                ?: LegacyTelemetryHints.remoteBatteryHudText(packet),
            gearText = legacySnapshot?.gearSelection?.toString()
                ?: if (packet != null && !remoteTelemetryFresh) "Stale" else "--",
            flightLogText = flightLogStatusText,
            fovText = fovText,
            summary = if (packet != null && !remoteTelemetryFresh) {
                "Telemetry stale"
            } else {
                LegacyTelemetryHints.hudSummaryText(packet, flightLogStatusText, remoteBatteryReading)
            }
        )
    }

    private fun buildSdCardText(
        telemetryResults: List<TelemetryResult>,
        fallback3014Summary: Telemetry3014Summary?,
        cameraReady: Boolean
    ): String {
        val photoCount = telemetryResults
            .firstSuccessfulPreview("CMD 1003")
            ?.let { parseFirstIntTag(it, "Value") ?: parseFirstIntTag(it, "FREEPICNUM") }
        val videoSeconds = telemetryResults
            .firstSuccessfulPreview("CMD 2009")
            ?.let { parseFirstIntTag(it, "Value") }

        return when {
            photoCount != null && videoSeconds != null ->
                "$photoCount photos / ${formatCameraRemainingTime(videoSeconds)} video"
            photoCount != null -> "$photoCount photos"
            videoSeconds != null -> "${formatCameraRemainingTime(videoSeconds)} video"
            fallback3014Summary?.value(3015) != null -> "${fallback3014Summary.value(3015)} MB"
            cameraReady -> SD_CARD_PENDING_TEXT
            else -> "Unknown"
        }
    }

    private fun List<TelemetryResult>.firstSuccessfulPreview(label: String): String? =
        firstOrNull { it.label == label && it.status.startsWith("HTTP 200") }?.preview

    private fun parseFirstIntTag(xml: String, tag: String): Int? =
        Regex("<$tag>\\s*(-?\\d+)\\s*</$tag>", RegexOption.IGNORE_CASE)
            .find(xml)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

    private fun formatCameraRemainingTime(seconds: Int): String {
        val totalMinutes = seconds.coerceAtLeast(0) / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
    }

    private fun buildPreflight(
        networkInfo: DroneNetworkInfo,
        telemetryResults: List<TelemetryResult>,
        relayState: RelayStateUi,
        droneState: DroneStateUi
    ): List<PreflightItem> {
        val onCameraDirect = networkInfo.localIp?.startsWith("192.168.1.") == true
        val successfulCameraCommand = telemetryResults.any {
            it.url.contains("192.168.1.254") && it.status.startsWith("HTTP 200")
        }
        val relayLinkActive = relayState.status.equals("Connected", ignoreCase = true) &&
            !relayFieldLooksUnknown(relayState.currentAirSignal)
        val cameraConnected = onCameraDirect || successfulCameraCommand || relayLinkActive

        return listOf(
            PreflightItem(
                label = "GPS Sat",
                value = droneState.satelliteText,
                ok = droneState.satelliteText.toIntOrNull()
                    ?.let { LegacyTelemetryDecoder.hasGpsModeSatelliteLock(it) } == true
            ),
            PreflightItem(
                label = "Camera",
                value = if (cameraConnected) "Connected" else "Not connected",
                ok = cameraConnected
            ),
            PreflightItem(
                label = "Aircraft Power",
                value = droneState.droneBatteryText,
                ok = droneState.droneBatteryText != "--" && droneState.droneBatteryText != "Stale"
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
                ok = droneState.flightModeText != "Unknown" && droneState.flightModeText != "Telemetry stale"
            ),
            PreflightItem(
                label = "Gear",
                value = droneState.gearText,
                ok = droneState.gearText != "--" && droneState.gearText != "Stale"
            ),
            PreflightItem(
                label = "Elevation",
                value = droneState.baroHeightText,
                ok = droneState.baroHeightText != "Stale"
            ),
            PreflightItem(
                label = "Target Elevation",
                value = droneState.targetHeightText,
                ok = droneState.targetHeightText != "Stale"
            ),
            PreflightItem(
                label = "SD Card",
                value = droneState.sdCardText,
                ok = droneState.sdCardText != "Unknown" &&
                    droneState.sdCardText != "Reading..." &&
                    droneState.sdCardText != SD_CARD_PENDING_TEXT
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
                ?: parsedStatus
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
            onExtender && relayState.status.equals("Connected", ignoreCase = true) -> "Link active"
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

    private fun isTelemetryFresh(packet: RemoteTelemetryPacket?, nowMs: Long): Boolean =
        packet != null && nowMs - packet.timestampMs <= TELEMETRY_STALE_AFTER_MS

    private fun formatAge(ageMs: Long): String {
        val seconds = ageMs.coerceAtLeast(0L) / 1_000.0
        return "%.1fs".format(seconds)
    }
}
