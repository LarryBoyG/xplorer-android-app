package com.example.xirolite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class TelemetryResult(
    val label: String,
    val url: String,
    val status: String,
    val preview: String
)

data class TelemetryDiffResult(
    val label: String,
    val url: String,
    val before: String,
    val after: String
)

data class Telemetry3014Summary(
    val values: Map<Int, Int>
) {
    fun value(cmd: Int): Int? = values[cmd]

    val motorStateGuess: String
        get() = when (values[2005]) {
            6 -> "State 6"
            0 -> "State 0"
            null -> "Unknown"
            else -> "State ${values[2005]}"
        }

    val gpsGuess: String
        get() = when (values[2033]) {
            0 -> "No lock / inactive"
            1 -> "State 1"
            null -> "Unknown"
            else -> "State ${values[2033]}"
        }

    val flightModeGuess: String
        get() = when (values[2034]) {
            3 -> "Mode 3"
            0 -> "Mode 0"
            null -> "Unknown"
            else -> "Mode ${values[2034]}"
        }

    val systemStateGuess: String
        get() = when (values[2010]) {
            4 -> "State 4"
            0 -> "State 0"
            null -> "Unknown"
            else -> "State ${values[2010]}"
        }

    val auxStateGuess: String
        get() = when (values[2011]) {
            -1 -> "Unavailable"
            0 -> "OK"
            null -> "Unknown"
            else -> "State ${values[2011]}"
        }

    val cameraStateGuess: String
        get() = when (values[3009]) {
            1 -> "Active"
            0 -> "Idle"
            null -> "Unknown"
            else -> "State ${values[3009]}"
        }
}

class TelemetryProbe {

    private val focusedCommands: List<Pair<String, String>> = listOf(
        "cmd=3002" to "CMD 3002",
        "cmd=3014" to "CMD 3014",
        "cmd=1003" to "CMD 1003",
        "cmd=2009" to "CMD 2009",
        "cmd=3017" to "CMD 3017",
        "cmd=2010" to "CMD 2010",
        "cmd=2005" to "CMD 2005",
        "cmd=2033" to "CMD 2033",
        "cmd=2034" to "CMD 2034",
        "cmd=3009" to "CMD 3009",
        "cmd=3080" to "CMD 3080",
        "cmd=3081" to "CMD 3081",
        "cmd=4001" to "CMD 4001"
    )

    suspend fun runOnce(host: String): List<TelemetryResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<TelemetryResult>()
        for ((query, label) in focusedCommands) {
            results += httpGet(host, query, label)
            delay(250)
        }
        results
    }

    suspend fun runLoop(
        host: String,
        intervalMs: Long = 2500,
        onUpdate: (List<TelemetryResult>) -> Unit
    ) = withContext(Dispatchers.IO) {
        while (isActive) {
            val results = mutableListOf<TelemetryResult>()
            for ((query, label) in focusedCommands) {
                results += httpGet(host, query, label)
                delay(250)
            }
            onUpdate(results)
            delay(intervalMs)
        }
    }

    suspend fun diffSnapshots(
        host: String,
        delayMs: Long = 5000
    ): List<TelemetryDiffResult> = withContext(Dispatchers.IO) {
        val before = runOnce(host)
        delay(delayMs)
        val after = runOnce(host)

        val beforeMap = before.associateBy { it.url }

        after.mapNotNull { current ->
            val previous = beforeMap[current.url] ?: return@mapNotNull null
            val beforeNorm = normalize(previous.preview)
            val afterNorm = normalize(current.preview)

            if (previous.status != current.status || beforeNorm != afterNorm) {
                TelemetryDiffResult(
                    label = current.label,
                    url = current.url,
                    before = previous.preview,
                    after = current.preview
                )
            } else {
                null
            }
        }
    }

    suspend fun readCameraStorage(host: String): List<TelemetryResult> = withContext(Dispatchers.IO) {
        listOf(
            httpGet(host, "cmd=1003", "CMD 1003"),
            httpGet(host, "cmd=2009", "CMD 2009"),
            httpGet(host, "cmd=3014", "CMD 3014")
        )
    }

    suspend fun watch3014(
        host: String,
        intervalMs: Long = 3000,
        startupDelayMs: Long = 2000,
        onUpdate: (TelemetryResult) -> Unit
    ) = withContext(Dispatchers.IO) {
        delay(startupDelayMs)
        while (isActive) {
            val result = httpGet(host, "cmd=3014", "CMD 3014")
            onUpdate(result)
            delay(intervalMs)
        }
    }

    fun parse3014Summary(xml: String): Telemetry3014Summary? {
        val cmdRegex = Regex("<Cmd>(-?\\d+)</Cmd>")
        val statusRegex = Regex("<Status>(-?\\d+)</Status>")

        val cmds = cmdRegex.findAll(xml).map { it.groupValues[1].toInt() }.toList()
        val statuses = statusRegex.findAll(xml).map { it.groupValues[1].toInt() }.toList()

        if (cmds.isEmpty() || statuses.isEmpty()) return null

        val pairs = mutableMapOf<Int, Int>()
        val count = minOf(cmds.size, statuses.size)

        for (i in 0 until count) {
            pairs[cmds[i]] = statuses[i]
        }

        return Telemetry3014Summary(pairs)
    }

    fun httpGet(host: String, query: String, label: String): TelemetryResult {
        val urlString = "http://$host/?custom=1&$query"

        return try {
            val conn = URL(urlString).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 2200
            conn.readTimeout = 2200
            conn.setRequestProperty("User-Agent", "XIRO-Lite")
            conn.setRequestProperty("Connection", "close")

            val code = conn.responseCode
            val body = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                if (stream != null) {
                    BufferedReader(InputStreamReader(stream)).use { reader ->
                        reader.readText()
                    }
                } else {
                    ""
                }
            } catch (_: Exception) {
                ""
            }

            TelemetryResult(
                label = label,
                url = urlString,
                status = "HTTP $code",
                preview = body.ifBlank { "(empty response)" }
            )
        } catch (e: Exception) {
            TelemetryResult(
                label = label,
                url = urlString,
                status = e.javaClass.simpleName,
                preview = e.message ?: "No detail"
            )
        }
    }

    private fun normalize(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
