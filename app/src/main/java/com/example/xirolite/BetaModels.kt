package com.example.xirolite

import com.example.xirolite.data.CommandResult

data class TelemetryFieldCandidate(
    val label: String,
    val value: String,
    val confidence: String,
    val source: String
)

data class DroneStateUi(
    val heightText: String = "--",
    val speedText: String = "--",
    val distanceText: String = "--",
    val voltageText: String = "--",
    val satelliteText: String = "--",
    val gpsText: String = "Unknown",
    val flightModeText: String = "Unknown",
    val droneBatteryText: String = "--",
    val relaySignalText: String = "--",
    val sdCardText: String = "Unknown",
    val remoteBatteryText: String = "--",
    val gearText: String = "--",
    val flightLogText: String = "--",
    val fovText: String = "--",
    val summary: String = "Waiting for telemetry"
)

data class PreflightItem(
    val label: String,
    val value: String,
    val ok: Boolean,
    val detail: String = ""
)

enum class FlightWarningSeverity {
    INFO,
    CAUTION,
    CRITICAL
}

data class FlightWarning(
    val title: String,
    val detail: String,
    val severity: FlightWarningSeverity
)

data class RelayWifiCandidate(
    val ssid: String,
    val mac: String? = null,
    val channel: String? = null,
    val signal: String? = null,
    val encrypt: String? = null,
    val mode: String? = null,
    val channelBond: String? = null,
    val sideBand: String? = null
)

data class RelayStateUi(
    val status: String = "Unknown",
    val version: String = "--",
    val currentAirWifi: String = "--",
    val availableNetworks: List<RelayWifiCandidate> = emptyList(),
    val lastProbeText: String = "No relay probe yet"
)

data class BetaUiState(
    val droneState: DroneStateUi = DroneStateUi(),
    val relayState: RelayStateUi = RelayStateUi(),
    val preflight: List<PreflightItem> = emptyList(),
    val warnings: List<FlightWarning> = emptyList(),
    val candidateFields: List<TelemetryFieldCandidate> = emptyList(),
    val relayProbeResults: List<CommandResult> = emptyList()
)
