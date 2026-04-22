package com.example.xirolite

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.SocketFactory

data class RemoteBatteryReading(
    val percent: Int,
    val raw: Int,
    val responseHex: String,
    val timestampMs: Long,
    val confidence: String
)

class RemoteBatteryProbe(
    private val context: Context
) {
    suspend fun query(host: String = DEFAULT_RELAY_HOST): Result<RemoteBatteryReading> =
        withContext(Dispatchers.IO) {
            runCatching {
                val route = WifiRouteSelector.selectWifiNetwork(context, host)
                val socketFactory = route?.socketFactory ?: SocketFactory.getDefault()
                val socket = socketFactory.createSocket() as Socket

                socket.use {
                    it.tcpNoDelay = true
                    it.keepAlive = false
                    it.soTimeout = READ_TIMEOUT_MS
                    it.connect(InetSocketAddress(host, LEGACY_CONTROL_PORT), CONNECT_TIMEOUT_MS)

                    val output = it.getOutputStream()
                    output.write(REMOTE_BATTERY_WAKE)
                    output.flush()
                    Thread.sleep(LEGACY_COMMAND_GAP_MS)
                    output.write(REMOTE_BATTERY_REQUEST)
                    output.flush()

                    val response = readBatteryResponse(it.getInputStream())
                        ?: error("No remote battery response")
                    val raw = response[4].toInt() and 0xFF
                    RemoteBatteryReading(
                        percent = decodePercent(raw),
                        raw = raw,
                        responseHex = response.toHex(),
                        timestampMs = System.currentTimeMillis(),
                        confidence = "calibrated"
                    )
                }
            }
        }

    private fun readBatteryResponse(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32)

        while (output.size() < MAX_RESPONSE_BYTES) {
            try {
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                findBatteryResponse(output.toByteArray())?.let { return it }

                if (input.available() == 0) {
                    Thread.sleep(READ_IDLE_WAIT_MS)
                    findBatteryResponse(output.toByteArray())?.let { return it }
                }
            } catch (_: SocketTimeoutException) {
                break
            }
        }

        return findBatteryResponse(output.toByteArray())
    }

    private fun findBatteryResponse(bytes: ByteArray): ByteArray? {
        if (bytes.size < RESPONSE_LENGTH) return null
        for (index in 0..bytes.size - RESPONSE_LENGTH) {
            if ((bytes[index].toInt() and 0xFF) != 0x1A) continue
            if ((bytes[index + 1].toInt() and 0xFF) != 0x06) continue
            if ((bytes[index + 2].toInt() and 0xFF) != 0xAC) continue
            if ((bytes[index + 3].toInt() and 0xFF) != 0x03) continue

            val candidate = bytes.copyOfRange(index, index + RESPONSE_LENGTH)
            if (hasValidChecksum(candidate)) return candidate
        }
        return null
    }

    private fun hasValidChecksum(bytes: ByteArray): Boolean {
        if (bytes.size != RESPONSE_LENGTH) return false
        val expected = bytes.take(RESPONSE_LENGTH - 1)
            .sumOf { it.toInt() and 0xFF }
            .and(0xFF)
        val actual = bytes.last().toInt() and 0xFF
        return expected == actual
    }

    private fun decodePercent(raw: Int): Int {
        return when {
            raw >= 150 -> 100
            raw >= 140 -> 90
            raw >= 130 -> 80
            raw >= 124 -> 70
            raw >= 119 -> 60
            raw >= 112 -> 50
            raw >= 103 -> 40
            raw >= 97 -> 30
            raw >= 90 -> 20
            raw >= 82 -> 10
            raw > 0 -> 5
            else -> 0
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    companion object {
        private const val DEFAULT_RELAY_HOST = "192.168.2.254"
        private const val LEGACY_CONTROL_PORT = 6666
        private const val CONNECT_TIMEOUT_MS = 1_500
        private const val READ_TIMEOUT_MS = 1_200
        private const val LEGACY_COMMAND_GAP_MS = 90L
        private const val READ_IDLE_WAIT_MS = 40L
        private const val MAX_RESPONSE_BYTES = 64
        private const val RESPONSE_LENGTH = 6

        private val REMOTE_BATTERY_WAKE = byteArrayOf(0xA1.toByte(), 0x04, 0xAC.toByte(), 0x51)
        private val REMOTE_BATTERY_REQUEST = byteArrayOf(0x1A, 0x06, 0xAC.toByte(), 0x06, 0xD2.toByte())
    }
}

object RemoteBatteryHub {
    private const val POLL_INTERVAL_MS = 7_000L
    private const val MAX_FAILURES_BEFORE_CLEAR = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var currentHost: String? = null
    private var logger: ((String) -> Unit)? = null

    private val _latestReading = MutableStateFlow<RemoteBatteryReading?>(null)
    val latestReading: StateFlow<RemoteBatteryReading?> = _latestReading.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    @Synchronized
    fun start(context: Context, host: String = "192.168.2.254", onLog: ((String) -> Unit)? = null) {
        if (onLog != null) logger = onLog
        if (pollJob != null && currentHost == host) return

        stop(clearReading = false)
        currentHost = host
        val appContext = context.applicationContext
        val probe = RemoteBatteryProbe(appContext)

        _isRunning.value = true
        pollJob = scope.launch {
            var failureCount = 0
            try {
                while (isActive) {
                    probe.query(host)
                        .onSuccess { reading ->
                            failureCount = 0
                            val previous = _latestReading.value
                            _latestReading.value = reading
                            if (previous?.percent != reading.percent || previous.raw != reading.raw) {
                                logger?.invoke("Remote battery ${reading.percent}% (raw ${reading.raw}, ${reading.responseHex})")
                            }
                        }
                        .onFailure { error ->
                            failureCount++
                            if (failureCount == 1) {
                                logger?.invoke("Remote battery query waiting: ${error.message ?: error::class.java.simpleName}")
                            }
                            if (failureCount >= MAX_FAILURES_BEFORE_CLEAR) {
                                _latestReading.value = null
                            }
                        }
                    delay(POLL_INTERVAL_MS)
                }
            } finally {
                _isRunning.value = false
                synchronized(this@RemoteBatteryHub) {
                    pollJob = null
                }
            }
        }
    }

    @Synchronized
    fun stop(clearReading: Boolean = false) {
        pollJob?.cancel()
        pollJob = null
        currentHost = null
        _isRunning.value = false
        if (clearReading) _latestReading.value = null
    }
}
