package com.example.xirolite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

data class PortScanResult(
    val host: String,
    val port: Int,
    val status: String,
    val detail: String
)

class PortScanner {
    companion object {
        val LEGACY_TCP_PORTS: List<Int> = listOf(
            80, 554, 8553, 6660, 6662, 6666, 6800,
            7001, 7002, 7003,
            8001, 8002, 8003, 8004, 8005, 8006, 8007, 8008,
            8018, 8020,
            8100, 8101, 8102, 8110,
            8200
        )
    }

    suspend fun scanHost(
        host: String,
        ports: List<Int>,
        timeoutMs: Int = 800
    ): List<PortScanResult> = withContext(Dispatchers.IO) {
        coroutineScope {
            ports.distinct().sorted().map { port ->
                async { scanSinglePort(host, port, timeoutMs) }
            }.awaitAll()
        }
    }

    private fun scanSinglePort(
        host: String,
        port: Int,
        timeoutMs: Int
    ): PortScanResult {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                PortScanResult(
                    host = host,
                    port = port,
                    status = "OPEN",
                    detail = "Connected successfully"
                )
            }
        } catch (e: java.net.SocketTimeoutException) {
            PortScanResult(
                host = host,
                port = port,
                status = "TIMEOUT",
                detail = e.message ?: "Connection timed out"
            )
        } catch (e: Exception) {
            PortScanResult(
                host = host,
                port = port,
                status = "CLOSED",
                detail = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
            )
        }
    }
}

