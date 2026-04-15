package com.example.xirolite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LiveConnectionMonitor {

    suspend fun start(
        host: String,
        onLog: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)

        fun ts(message: String): String {
            return "[${formatter.format(Date())}] $message"
        }

        onLog(ts("Live monitor started for $host"))

        while (isActive) {
            val p80 = checkPort(host, 80)
            val p554 = checkPort(host, 554)

            onLog(ts("Port 80: $p80 | Port 554: $p554"))

            delay(2000)
        }
    }

    private fun checkPort(host: String, port: Int): String {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 500)
                "OPEN"
            }
        } catch (e: Exception) {
            "CLOSED"
        }
    }
}

