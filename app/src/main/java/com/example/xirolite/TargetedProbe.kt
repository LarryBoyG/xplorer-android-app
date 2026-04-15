package com.example.xirolite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

data class TargetedProbeResult(
    val label: String,
    val endpoint: String,
    val status: String,
    val preview: String
)

class TargetedProbe {
    companion object {
        private const val LEGACY_RTSP_USER_AGENT = "xiroRTSP (Rtsp Client/1.0.0)"
        private val LEGACY_A9_TCP_PORTS = listOf(
            7001, 7002, 7003,
            8001, 8002, 8003, 8004, 8005, 8006, 8007, 8008,
            8018, 8020,
            8100, 8101, 8102, 8110,
            8200
        )
    }

    suspend fun run(host: String): List<TargetedProbeResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<TargetedProbeResult>()

        results += probeRtspOptions(host, 554)
        results += probeRtspOptionsLiteral(host, 554, "xxxx.mov")
        results += probeRtspDescribeLiteral(host, 554, "xxxx.mov")
        results += probeRtspOptionsUri(host, 8553, "videoCodecType=H.264", "RTSP OPTIONS legacy live")
        results += probeRtspDescribeUri(host, 8553, "videoCodecType=H.264", "RTSP DESCRIBE legacy live")

        val setupResult = probeRtspSetupLiteral(host, 554, "xxxx.mov", "track1")
        results += setupResult

        val sessionId = extractSessionId(setupResult.preview)
        results += probeRtspPlayLiteral(host, 554, "xxxx.mov", sessionId)

        results += probeTcpConnect(host, 6662, "TCP 6662")
        results += probeTcpConnect(host, 6666, "TCP 6666")
        LEGACY_A9_TCP_PORTS.forEach { port ->
            results += probeTcpConnect(host, port, "A9 TCP $port")
        }
        results += probeUdpPing(host, 6970, "UDP 6970")

        results
    }

    private fun probeRtspOptions(host: String, port: Int): TargetedProbeResult {
        val endpoint = "$host:$port"
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1500)
                socket.soTimeout = 2000

                val out = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())

                val request = buildString {
                    append("OPTIONS rtsp://$host/ RTSP/1.0\r\n")
                    append("CSeq: 1\r\n")
                    append("User-Agent: $LEGACY_RTSP_USER_AGENT\r\n")
                    append("\r\n")
                }

                out.write(request.toByteArray(StandardCharsets.UTF_8))
                out.flush()

                val preview = readResponse(input)

                TargetedProbeResult(
                    label = "RTSP OPTIONS /",
                    endpoint = endpoint,
                    status = classifyRtspResponse(preview),
                    preview = preview
                )
            }
        } catch (e: Exception) {
            TargetedProbeResult(
                label = "RTSP OPTIONS /",
                endpoint = endpoint,
                status = e.javaClass.simpleName,
                preview = e.message ?: "No detail"
            )
        }
    }

    private fun probeRtspOptionsLiteral(host: String, port: Int, fileName: String): TargetedProbeResult {
        val endpoint = "$host:$port"
        val uri = "rtsp://$host/$fileName"

        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1500)
                socket.soTimeout = 2000

                val out = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())

                val request = buildString {
                    append("OPTIONS $uri RTSP/1.0\r\n")
                    append("CSeq: 1\r\n")
                    append("User-Agent: $LEGACY_RTSP_USER_AGENT\r\n")
                    append("\r\n")
                }

                out.write(request.toByteArray(StandardCharsets.UTF_8))
                out.flush()

                val preview = readResponse(input)

                TargetedProbeResult(
                    label = "RTSP OPTIONS /$fileName",
                    endpoint = endpoint,
                    status = classifyRtspResponse(preview),
                    preview = preview
                )
            }
        } catch (e: Exception) {
            TargetedProbeResult(
                label = "RTSP OPTIONS /$fileName",
                endpoint = endpoint,
                status = e.javaClass.simpleName,
                preview = e.message ?: "No detail"
            )
        }
    }

    private fun probeRtspDescribeLiteral(host: String, port: Int, fileName: String): TargetedProbeResult {
        val endpoint = "$host:$port"
        val uri = "rtsp://$host/$fileName"

        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1500)
                socket.soTimeout = 2500

                val out = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())

                val request = buildString {
                    append("DESCRIBE $uri RTSP/1.0\r\n")
                    append("CSeq: 2\r\n")
                    append("Accept: application/sdp\r\n")
                    append("User-Agent: $LEGACY_RTSP_USER_AGENT\r\n")
                    append("\r\n")
                }

                out.write(request.toByteArray(StandardCharsets.UTF_8))
                out.flush()

                val preview = readResponse(input)

                TargetedProbeResult(
                    label = "RTSP DESCRIBE /$fileName",
                    endpoint = endpoint,
                    status = classifyRtspResponse(preview),
                    preview = preview
                )
            }
        } catch (e: Exception) {
            TargetedProbeResult(
                label = "RTSP DESCRIBE /$fileName",
                endpoint = endpoint,
                status = e.javaClass.simpleName,
                preview = e.message ?: "No detail"
            )
        }
    }

    private fun probeRtspOptionsUri(host: String, port: Int, uriSuffix: String, label: String): TargetedProbeResult {
        val endpoint = "$host:$port"
        val uri = "rtsp://$host:$port/$uriSuffix"

        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1500)
                socket.soTimeout = 2000

                val out = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())

                val request = buildString {
                    append("OPTIONS $uri RTSP/1.0\r\n")
                    append("CSeq: 10\r\n")
                    append("User-Agent: $LEGACY_RTSP_USER_AGENT\r\n")
                    append("\r\n")
                }

                out.write(request.toByteArray(StandardCharsets.UTF_8))
                out.flush()

                val preview = readResponse(input)

                TargetedProbeResult(
                    label = label,
                    endpoint = endpoint,
                    status = classifyRtspResponse(preview),
                    preview = preview
                )
            }
        } catch (e: Exception) {
            TargetedProbeResult(
                label = label,
                endpoint = endpoint,
                status = e.javaClass.simpleName,
                preview = e.message ?: "No detail"
            )
        }
    }

    private fun probeRtspDescribeUri(host: String, port: Int, uriSuffix: String, label: String): TargetedProbeResult {
        val endpoint = "$host:$port"
        val uri = "rtsp://$host:$port/$uriSuffix"

        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1500)
                socket.soTimeout = 2500

                val out = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())

                val request = buildString {
                    append("DESCRIBE $uri RTSP/1.0\r\n")
                    append("CSeq: 11\r\n")
                    append("Accept: application/sdp\r\n")
                    append("User-Agent: $LEGACY_RTSP_USER_AGENT\r\n")
                    append("\r\n")
                }

                out.write(request.toByteArray(StandardCharsets.UTF_8))
                out.flush()

                val preview = readResponse(input)

                TargetedProbeResult(
                    label = label,
                    endpoint = endpoint,
                    status = classifyRtspResponse(preview),
                    preview = preview
                )
            }
        } catch (e: Exception) {
            TargetedProbeResult(
                label = label,
                endpoint = endpoint,
                status = e.javaClass.simpleName,
                preview = e.message ?: "No detail"
            )
        }
    }

    private fun probeRtspSetupLiteral(
        host: String,
        port: Int,
        fileName: String,
        trackName: String
    ): TargetedProbeResult {
        val endpoint = "$host:$port"
        val uri = "rtsp://$host/$fileName/$trackName"

        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1500)
                socket.soTimeout = 2500

                val out = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())

                val request = buildString {
                    append("SETUP $uri RTSP/1.0\r\n")
                    append("CSeq: 3\r\n")
                    append("Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n")
                    append("User-Agent: $LEGACY_RTSP_USER_AGENT\r\n")
                    append("\r\n")
                }

                out.write(request.toByteArray(StandardCharsets.UTF_8))
                out.flush()

                val preview = readResponse(input)

                TargetedProbeResult(
                    label = "RTSP SETUP /$fileName/$trackName",
                    endpoint = endpoint,
                    status = classifyRtspResponse(preview),
                    preview = preview
                )
            }
        } catch (e: Exception) {
            TargetedProbeResult(
                label = "RTSP SETUP /$fileName/$trackName",
                endpoint = endpoint,
                status = e.javaClass.simpleName,
                preview = e.message ?: "No detail"
            )
        }
    }

    private fun probeRtspPlayLiteral(
        host: String,
        port: Int,
        fileName: String,
        sessionId: String?
    ): TargetedProbeResult {
        val endpoint = "$host:$port"
        val uri = "rtsp://$host/$fileName"

        if (sessionId.isNullOrBlank()) {
            return TargetedProbeResult(
                label = "RTSP PLAY /$fileName",
                endpoint = endpoint,
                status = "NO_SESSION",
                preview = "No Session header was extracted from SETUP response"
            )
        }

        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1500)
                socket.soTimeout = 2500

                val out = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())

                val request = buildString {
                    append("PLAY $uri RTSP/1.0\r\n")
                    append("CSeq: 4\r\n")
                    append("Session: $sessionId\r\n")
                    append("User-Agent: $LEGACY_RTSP_USER_AGENT\r\n")
                    append("\r\n")
                }

                out.write(request.toByteArray(StandardCharsets.UTF_8))
                out.flush()

                val preview = readResponse(input)

                TargetedProbeResult(
                    label = "RTSP PLAY /$fileName",
                    endpoint = endpoint,
                    status = classifyRtspResponse(preview),
                    preview = preview
                )
            }
        } catch (e: Exception) {
            TargetedProbeResult(
                label = "RTSP PLAY /$fileName",
                endpoint = endpoint,
                status = e.javaClass.simpleName,
                preview = e.message ?: "No detail"
            )
        }
    }

    private fun extractSessionId(response: String): String? {
        val line = response.lines().firstOrNull { it.startsWith("Session:", ignoreCase = true) }
            ?: return null
        return line.substringAfter("Session:", "").trim().substringBefore(';').trim()
    }

    private fun probeTcpConnect(host: String, port: Int, label: String): TargetedProbeResult {
        val endpoint = "$host:$port"
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1500)
                socket.soTimeout = 1000

                val input = BufferedInputStream(socket.getInputStream())
                val buffer = ByteArray(512)

                val preview = try {
                    val read = input.read(buffer)
                    if (read > 0) {
                        String(buffer, 0, read, StandardCharsets.UTF_8)
                    } else {
                        "Connected, no immediate data"
                    }
                } catch (_: Exception) {
                    "Connected, no immediate data"
                }

                TargetedProbeResult(
                    label = label,
                    endpoint = endpoint,
                    status = "CONNECTED",
                    preview = preview
                )
            }
        } catch (e: Exception) {
            TargetedProbeResult(
                label = label,
                endpoint = endpoint,
                status = e.javaClass.simpleName,
                preview = e.message ?: "No detail"
            )
        }
    }

    private fun probeUdpPing(host: String, port: Int, label: String): TargetedProbeResult {
        val endpoint = "$host:$port"
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 1200
                val payload = "PING".toByteArray(StandardCharsets.UTF_8)
                val packet = DatagramPacket(payload, payload.size, InetAddress.getByName(host), port)
                socket.send(packet)

                val recvBuf = ByteArray(512)
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)

                try {
                    socket.receive(recvPacket)
                    val preview = String(
                        recvPacket.data,
                        recvPacket.offset,
                        recvPacket.length,
                        StandardCharsets.UTF_8
                    )
                    TargetedProbeResult(
                        label = label,
                        endpoint = endpoint,
                        status = "RESPONDED",
                        preview = preview
                    )
                } catch (_: Exception) {
                    TargetedProbeResult(
                        label = label,
                        endpoint = endpoint,
                        status = "SENT_NO_REPLY",
                        preview = "UDP packet sent, no reply"
                    )
                }
            }
        } catch (e: Exception) {
            TargetedProbeResult(
                label = label,
                endpoint = endpoint,
                status = e.javaClass.simpleName,
                preview = e.message ?: "No detail"
            )
        }
    }

    private fun readResponse(input: BufferedInputStream): String {
        val buffer = ByteArray(4096)
        val read = input.read(buffer)
        return if (read > 0) {
            String(buffer, 0, read, StandardCharsets.UTF_8)
        } else {
            "Connected, no response body"
        }
    }

    private fun classifyRtspResponse(response: String): String {
        return when {
            response.startsWith("RTSP/1.0 200") -> "OK"
            response.startsWith("RTSP/1.0 404") -> "NOT_FOUND"
            response.startsWith("RTSP/1.0 454") -> "SESSION_NOT_FOUND"
            response.startsWith("RTSP/1.0 45") -> "RTSP_ERROR"
            response.startsWith("RTSP/1.0") -> "RTSP_RESPONSE"
            else -> "CONNECTED"
        }
    }
}
