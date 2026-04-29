package com.example.xirolite

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object LiveFlightTelemetryHub {
    private data class PositionSample(
        val coordinate: FlightCoordinate,
        val timestampMs: Long
    )

    private const val SPEED_WINDOW_MS = 3_000L
    private const val MAX_POSITION_SAMPLES = 16
    private const val MIN_SPEED_DISTANCE_METERS = 3.0
    private const val MIN_SPEED_MPS = 0.35

    private val probe = RemoteTelemetryProbe()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var watchJob: Job? = null
    private var logger: ((String) -> Unit)? = null
    private var homeCoordinate: FlightCoordinate? = null
    private val recentPositionSamples = ArrayDeque<PositionSample>()

    private val _recentPackets = MutableStateFlow<List<RemoteTelemetryPacket>>(emptyList())
    val recentPackets: StateFlow<List<RemoteTelemetryPacket>> = _recentPackets.asStateFlow()

    private val _derivedTelemetry = MutableStateFlow(DerivedFlightTelemetry())
    val derivedTelemetry: StateFlow<DerivedFlightTelemetry> = _derivedTelemetry.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    @Synchronized
    fun start(onLog: ((String) -> Unit)? = null) {
        if (onLog != null) logger = onLog
        if (watchJob != null) return

        _isRunning.value = true
        watchJob = scope.launch {
            try {
                probe.watchUdp6800(
                    listenPort = 6800,
                    previewBytes = 32,
                    sourceFilter = null,
                    onPacket = { packet ->
                        updateDerivedTelemetry(packet)
                        _recentPackets.value = buildList {
                            add(packet)
                            addAll(_recentPackets.value.take(39))
                        }
                    },
                    onLog = { message -> logger?.invoke(message) }
                )
            } finally {
                _isRunning.value = false
                synchronized(this@LiveFlightTelemetryHub) {
                    watchJob = null
                }
            }
        }
    }

    @Synchronized
    fun stop(clearPackets: Boolean = false) {
        watchJob?.cancel()
        watchJob = null
        _isRunning.value = false
        if (clearPackets) {
            _recentPackets.value = emptyList()
            resetDerivedTelemetry()
        }
    }

    fun clearPackets() {
        _recentPackets.value = emptyList()
        resetDerivedTelemetry()
    }

    private fun updateDerivedTelemetry(packet: RemoteTelemetryPacket) {
        val snapshot = LegacyTelemetryDecoder.decode(packet) ?: return
        val decodedAltitude = snapshot.baroHeightMeters
            ?: snapshot.altitudeMeters
            ?: _derivedTelemetry.value.altitudeMeters
        val satellites = snapshot.satellites ?: 0
        val position = snapshot.position

        if (LegacyTelemetryDecoder.hasGpsModeSatelliteLock(satellites) && position != null) {
            if (homeCoordinate == null) {
                homeCoordinate = position
            }
            rememberPositionSample(position, packet.timestampMs)

            val distanceFromHome = homeCoordinate?.let { home ->
                haversineMeters(home, position).let { distance ->
                    if (distance < 2.0) 0.0 else distance
                }
            }

            _derivedTelemetry.value = DerivedFlightTelemetry(
                homeCoordinate = homeCoordinate,
                latestCoordinate = position,
                speedMetersPerSecond = computeGroundSpeedMetersPerSecond(),
                distanceFromHomeMeters = distanceFromHome,
                altitudeMeters = decodedAltitude
            )
            return
        }

        _derivedTelemetry.value = _derivedTelemetry.value.copy(
            speedMetersPerSecond = null,
            latestCoordinate = null,
            altitudeMeters = decodedAltitude,
            distanceFromHomeMeters = null
        )
    }

    private fun rememberPositionSample(position: FlightCoordinate, timestampMs: Long) {
        recentPositionSamples.addFirst(PositionSample(position, timestampMs))
        while (recentPositionSamples.size > MAX_POSITION_SAMPLES) {
            recentPositionSamples.removeLast()
        }
        while (recentPositionSamples.size > 1 &&
            (recentPositionSamples.first().timestampMs - recentPositionSamples.last().timestampMs) > SPEED_WINDOW_MS
        ) {
            recentPositionSamples.removeLast()
        }
    }

    private fun computeGroundSpeedMetersPerSecond(): Double? {
        val newest = recentPositionSamples.firstOrNull() ?: return null
        val oldest = recentPositionSamples.lastOrNull() ?: return null
        val elapsedMs = newest.timestampMs - oldest.timestampMs
        if (elapsedMs < 1_000L) return null

        val distanceMeters = haversineMeters(oldest.coordinate, newest.coordinate)
        if (distanceMeters < MIN_SPEED_DISTANCE_METERS) return 0.0

        val speed = distanceMeters / (elapsedMs / 1_000.0)
        return if (speed < MIN_SPEED_MPS) 0.0 else speed
    }

    private fun resetDerivedTelemetry() {
        homeCoordinate = null
        recentPositionSamples.clear()
        _derivedTelemetry.value = DerivedFlightTelemetry()
    }

    private fun haversineMeters(a: FlightCoordinate, b: FlightCoordinate): Double {
        val earthRadiusMeters = 6_371_000.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)

        val h = sin(dLat / 2).pow(2.0) +
            cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(h), sqrt(1 - h))
        return earthRadiusMeters * c
    }
}
