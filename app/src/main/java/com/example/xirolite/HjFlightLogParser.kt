package com.example.xirolite

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class HjFlightSummary(
    val id: String,
    val file: File,
    val fileName: String,
    val fileSizeBytes: Long,
    val recordCount: Int,
    val startTimestampMs: Long?,
    val endTimestampMs: Long?,
    val startTimestampText: String,
    val durationMs: Long?
)

data class HjTelemetrySample(
    val index: Int,
    val timestampMs: Long?,
    val timestampText: String,
    val elapsedMsFromStart: Long?,
    val position: FlightCoordinate?,
    val satellites: Int?,
    val aircraftPowerPercent: Int?,
    val aircraftPowerPercentExact: Double?,
    val voltageVolts: Double?,
    val altitudeMeters: Double?,
    val baroHeightMeters: Double?,
    val targetHeightMeters: Double?,
    val flightModeText: String?,
    val gearSelection: Int?,
    val switchControlState: Int?,
    val distanceFromHomeMeters: Double?,
    val speedMetersPerSecond: Double?
)

data class HjFlightLog(
    val summary: HjFlightSummary,
    val playbackSamples: List<HjTelemetrySample>,
    val maxSatellites: Int?,
    val minAircraftPowerPercent: Int?,
    val maxDistanceMeters: Double?,
    val maxSpeedMetersPerSecond: Double?
)

object HjFlightLogParser {
    private const val RECORD_SIZE = 99
    private const val HJ_MARKER_0 = 0x24.toByte()
    private const val HJ_MARKER_1 = 0x53.toByte()
    private const val HJ_MARKER_2 = 0x54.toByte()
    private const val HJ_MARKER_3 = 0x50.toByte()

    private const val LATITUDE_F32_OFFSET = 4
    private const val LONGITUDE_F32_OFFSET = 8
    private const val SATELLITE_OFFSET = 24
    private const val BARO_HGT_U16_OFFSET = 48
    private const val TARGET_HGT_U16_OFFSET = 84
    private const val POWER_U16_OFFSET = 52
    private const val GEAR_OFFSET = 55
    private const val SWITCH_CONTROL_OFFSET = 60
    private const val VOLTAGE_U16_OFFSET = 70

    private const val MIN_SPEED_DISTANCE_METERS = 3.0
    private const val MIN_SPEED_MPS = 0.35

    fun scanFlightSummaries(directory: File): List<HjFlightSummary> {
        val files = directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("hj", ignoreCase = true) }

        return files.mapNotNull { file ->
            runCatching { parseSummary(file) }.getOrNull()
        }.sortedWith(
            compareByDescending<HjFlightSummary> { it.startTimestampMs ?: it.file.lastModified() }
                .thenByDescending { it.file.lastModified() }
        )
    }

    fun parse(file: File): HjFlightLog? {
        if (!file.exists() || !file.isFile) return null
        val bytes = file.readBytes()
        val recordCount = (bytes.size / RECORD_SIZE)
        if (recordCount <= 0) return null

        val rawSamples = buildList {
            for (recordIndex in 0 until recordCount) {
                val start = recordIndex * RECORD_SIZE
                if (start + RECORD_SIZE > bytes.size) break
                val record = bytes.copyOfRange(start, start + RECORD_SIZE)
                if (!hasHjMarker(record)) continue
                add(parseRecord(recordIndex, record))
            }
        }
        if (rawSamples.isEmpty()) return null

        val playbackSamples = collapsePlaybackSamples(rawSamples)
        val startSample = playbackSamples.firstOrNull()
        val endSample = playbackSamples.lastOrNull()
        val summary = HjFlightSummary(
            id = file.absolutePath,
            file = file,
            fileName = file.name,
            fileSizeBytes = file.length(),
            recordCount = rawSamples.size,
            startTimestampMs = startSample?.timestampMs,
            endTimestampMs = endSample?.timestampMs,
            startTimestampText = startSample?.timestampText ?: "Unknown",
            durationMs = computeDurationMs(startSample?.timestampMs, endSample?.timestampMs)
        )

        return HjFlightLog(
            summary = summary,
            playbackSamples = playbackSamples,
            maxSatellites = playbackSamples.mapNotNull { it.satellites }.maxOrNull(),
            minAircraftPowerPercent = playbackSamples.mapNotNull { it.aircraftPowerPercent }.minOrNull(),
            maxDistanceMeters = playbackSamples.mapNotNull { it.distanceFromHomeMeters }.maxOrNull(),
            maxSpeedMetersPerSecond = playbackSamples.mapNotNull { it.speedMetersPerSecond }.maxOrNull()
        )
    }

    fun playbackDelayMs(samples: List<HjTelemetrySample>, currentIndex: Int): Long {
        val current = samples.getOrNull(currentIndex) ?: return 250L
        val next = samples.getOrNull(currentIndex + 1) ?: return 250L
        val currentMs = current.timestampMs
        val nextMs = next.timestampMs
        if (currentMs == null || nextMs == null) return 250L
        return (nextMs - currentMs).coerceAtLeast(150L)
    }

    private fun parseSummary(file: File): HjFlightSummary? {
        val fileLength = file.length()
        val recordCount = (fileLength / RECORD_SIZE).toInt()
        if (recordCount <= 0) return null

        RandomAccessFile(file, "r").use { raf ->
            val firstRecord = ByteArray(RECORD_SIZE)
            raf.readFully(firstRecord)

            val lastRecord = ByteArray(RECORD_SIZE)
            raf.seek(((recordCount - 1).toLong()) * RECORD_SIZE)
            raf.readFully(lastRecord)

            val firstTimestamp = parseTimestamp(firstRecord)
            val lastTimestamp = parseTimestamp(lastRecord)

            return HjFlightSummary(
                id = file.absolutePath,
                file = file,
                fileName = file.name,
                fileSizeBytes = fileLength,
                recordCount = recordCount,
                startTimestampMs = firstTimestamp.epochMs,
                endTimestampMs = lastTimestamp.epochMs,
                startTimestampText = firstTimestamp.text,
                durationMs = computeDurationMs(firstTimestamp.epochMs, lastTimestamp.epochMs)
            )
        }
    }

    private fun parseRecord(index: Int, record: ByteArray): HjTelemetrySample {
        val timestamp = parseTimestamp(record)
        val position = parseCoordinate(record)
        val satellites = record.getOrNull(SATELLITE_OFFSET)?.toInt()?.and(0xFF)
        val aircraftPowerExact = readU16Le(record, POWER_U16_OFFSET)?.div(256.0)
        val aircraftPowerPercent = aircraftPowerExact?.roundToInt()
        val voltage = readU16Le(record, VOLTAGE_U16_OFFSET)?.div(204.8)
        val baroHeight = readU16Le(record, BARO_HGT_U16_OFFSET)?.div(10.0)
        val targetHeight = readU16Le(record, TARGET_HGT_U16_OFFSET)?.div(10.0)
        val gear = record.getOrNull(GEAR_OFFSET)?.toInt()?.and(0xFF)?.takeIf { it in 1..3 }
        val switchControl = record.getOrNull(SWITCH_CONTROL_OFFSET)?.toInt()?.and(0xFF)?.takeIf { it in 1..3 }

        val flightModeText = LegacyTelemetryDecoder.flightModeTextForSatellites(satellites)

        return HjTelemetrySample(
            index = index,
            timestampMs = timestamp.epochMs,
            timestampText = timestamp.text,
            elapsedMsFromStart = null,
            position = position,
            satellites = satellites,
            aircraftPowerPercent = aircraftPowerPercent,
            aircraftPowerPercentExact = aircraftPowerExact,
            voltageVolts = voltage,
            altitudeMeters = baroHeight,
            baroHeightMeters = baroHeight,
            targetHeightMeters = targetHeight,
            flightModeText = flightModeText,
            gearSelection = gear ?: switchControl,
            switchControlState = switchControl,
            distanceFromHomeMeters = null,
            speedMetersPerSecond = null
        )
    }

    private fun collapsePlaybackSamples(rawSamples: List<HjTelemetrySample>): List<HjTelemetrySample> {
        if (rawSamples.isEmpty()) return emptyList()

        val perSecondSamples = buildList {
            var pending = rawSamples.first()
            var pendingKey = pending.timestampText

            for (sample in rawSamples.drop(1)) {
                val sampleKey = sample.timestampText
                if (sample.timestampMs != null && pending.timestampMs != null && sampleKey == pendingKey) {
                    pending = sample
                } else {
                    add(pending)
                    pending = sample
                    pendingKey = sampleKey
                }
            }
            add(pending)
        }

        var homeCoordinate: FlightCoordinate? = null
        var firstTimestampMs: Long? = perSecondSamples.firstOrNull()?.timestampMs
        var previousTimedSample: HjTelemetrySample? = null

        return perSecondSamples.map { sample ->
            if (homeCoordinate == null &&
                sample.position != null &&
                LegacyTelemetryDecoder.hasGpsModeSatelliteLock(sample.satellites)
            ) {
                homeCoordinate = sample.position
            }

            val distanceFromHome = if (homeCoordinate != null && sample.position != null) {
                haversineMeters(homeCoordinate!!, sample.position).let { distance ->
                    if (distance < 2.0) 0.0 else distance
                }
            } else {
                null
            }

            val speed = if (previousTimedSample?.position != null &&
                sample.position != null &&
                previousTimedSample?.timestampMs != null &&
                sample.timestampMs != null
            ) {
                val elapsedMs = sample.timestampMs - previousTimedSample!!.timestampMs!!
                if (elapsedMs >= 1_000L) {
                    val distance = haversineMeters(previousTimedSample!!.position!!, sample.position)
                    if (distance < MIN_SPEED_DISTANCE_METERS) {
                        0.0
                    } else {
                        val candidate = distance / (elapsedMs / 1_000.0)
                        if (candidate < MIN_SPEED_MPS) 0.0 else candidate
                    }
                } else {
                    null
                }
            } else {
                null
            }

            val enriched = sample.copy(
                elapsedMsFromStart = if (firstTimestampMs != null && sample.timestampMs != null) {
                    (sample.timestampMs - firstTimestampMs).coerceAtLeast(0L)
                } else {
                    null
                },
                distanceFromHomeMeters = distanceFromHome,
                speedMetersPerSecond = speed
            )

            if (sample.position != null && sample.timestampMs != null) {
                previousTimedSample = enriched
            }
            enriched
        }
    }

    private fun parseCoordinate(record: ByteArray): FlightCoordinate? {
        val latitude = readF32Le(record, LATITUDE_F32_OFFSET)?.toDouble() ?: return null
        val longitude = readF32Le(record, LONGITUDE_F32_OFFSET)?.toDouble() ?: return null
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        if (latitude !in -90.0..90.0) return null
        if (longitude !in -180.0..180.0) return null
        if (latitude == 0.0 && longitude == 0.0) return null
        return FlightCoordinate(latitude = latitude, longitude = longitude)
    }

    private fun parseTimestamp(record: ByteArray): ParsedHjTimestamp {
        val year = 2000 + (record.getOrNull(25)?.toInt()?.and(0xFF) ?: 0)
        val month = record.getOrNull(26)?.toInt()?.and(0xFF) ?: 0
        val day = record.getOrNull(27)?.toInt()?.and(0xFF) ?: 0
        val hour = record.getOrNull(28)?.toInt()?.and(0xFF) ?: 0
        val minute = record.getOrNull(29)?.toInt()?.and(0xFF) ?: 0
        val second = record.getOrNull(30)?.toInt()?.and(0xFF) ?: 0

        val text = String.format(
            Locale.US,
            "%04d-%02d-%02d %02d:%02d:%02d",
            year,
            month,
            day,
            hour,
            minute,
            second
        )

        val epochMs = runCatching {
            GregorianCalendar(year, month - 1, day, hour, minute, second).apply {
                isLenient = false
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }.getOrNull()

        return ParsedHjTimestamp(
            text = if (epochMs != null) text else "Unknown",
            epochMs = epochMs
        )
    }

    private fun hasHjMarker(record: ByteArray): Boolean {
        if (record.size < RECORD_SIZE) return false
        return record[0] == HJ_MARKER_0 &&
            record[1] == HJ_MARKER_1 &&
            record[2] == HJ_MARKER_2 &&
            record[3] == HJ_MARKER_3
    }

    private fun readU16Le(record: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 1 >= record.size) return null
        val lo = record[offset].toInt() and 0xFF
        val hi = record[offset + 1].toInt() and 0xFF
        return lo or (hi shl 8)
    }

    private fun readF32Le(record: ByteArray, offset: Int): Float? {
        if (offset < 0 || offset + 3 >= record.size) return null
        return runCatching {
            ByteBuffer.wrap(record, offset, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .float
        }.getOrNull()
    }

    private fun computeDurationMs(startMs: Long?, endMs: Long?): Long? {
        if (startMs == null || endMs == null) return null
        return (endMs - startMs).coerceAtLeast(0L)
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

    private data class ParsedHjTimestamp(
        val text: String,
        val epochMs: Long?
    )
}
