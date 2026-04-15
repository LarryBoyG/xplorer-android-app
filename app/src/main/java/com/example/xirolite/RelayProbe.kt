package com.example.xirolite

import com.example.xirolite.data.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class RelayProbe {
    private val client = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    private val legacyRelayControlPayloads = listOf(
        byteArrayOf(0x00)
    )

    data class TcpVariant(
        val label: String,
        val port: Int,
        val payload: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TcpVariant) return false
            return label == other.label &&
                    port == other.port &&
                    payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = label.hashCode()
            result = 31 * result + port
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    private data class LegacyBindMenuSessionResult(
        val status: CommandResult,
        val currentAir: CommandResult,
        val wifiList: CommandResult
    )

    private class LegacyRelaySession(
        val host: String,
        val sessionSocket: Socket,
        val controlSocket: Socket?,
        auxSocket: Socket?
    ) {
        val sessionInput = sessionSocket.getInputStream()
        val sessionOutput = sessionSocket.getOutputStream()
        val controlInput = controlSocket?.getInputStream()
        val controlOutput = controlSocket?.getOutputStream()
        var auxSocket: Socket? = auxSocket
        val openedAtMs = System.currentTimeMillis()
        var lastUsedAtMs = openedAtMs
        var lastControlPulseAtMs = 0L
        val auxCloseDeadlineMs = openedAtMs + 5_500L

        fun isHealthy(now: Long = System.currentTimeMillis()): Boolean {
            val sessionAlive = !sessionSocket.isClosed && sessionSocket.isConnected
            val controlAlive = controlSocket == null || (!controlSocket.isClosed && controlSocket.isConnected)
            return sessionAlive && controlAlive && now - lastUsedAtMs < 45_000L
        }

        fun close() {
            runCatching { auxSocket?.close() }
            runCatching { controlSocket?.close() }
            runCatching { sessionSocket.close() }
            auxSocket = null
        }
    }

    private val sessionLock = Any()
    private var legacyRelaySession: LegacyRelaySession? = null

    suspend fun probe(host: String): List<CommandResult> = withContext(Dispatchers.IO) {
        probeSettingsRoot(host)
    }

    suspend fun probeSettingsRoot(host: String): List<CommandResult> = withContext(Dispatchers.IO) {
        val sessionResult = tryPersistentSettingsRootSession(host)
        val status = if (looksUsable(sessionResult?.status?.preview.orEmpty())) {
            sessionResult!!.status
        } else {
            getRepeaterStatus(host)
        }
        val currentAir = if (looksUsable(sessionResult?.currentAir?.preview.orEmpty())) {
            sessionResult!!.currentAir
        } else {
            getCurrentAirWifiInfo(host)
        }

        listOf(
            status,
            currentAir,
            getRepeaterVersion(host)
        )
    }

    suspend fun probeBindMenu(host: String, forceRescan: Boolean = true): List<CommandResult> = withContext(Dispatchers.IO) {
        val sessionResult = tryPersistentBindMenuSession(host, forceRescan)
        val status = if (looksUsable(sessionResult?.status?.preview.orEmpty())) {
            sessionResult!!.status
        } else {
            getRepeaterStatus(host)
        }
        val currentAir = if (looksUsable(sessionResult?.currentAir?.preview.orEmpty())) {
            sessionResult!!.currentAir
        } else {
            getCurrentAirWifiInfo(host)
        }
        val wifiList = if (looksLikeWifiList(sessionResult?.wifiList?.preview.orEmpty())) {
            sessionResult!!.wifiList.copy(
                preview = normalizeWifiListPreview(sessionResult.wifiList.preview)
            )
        } else {
            getWifiList(host)
        }

        listOf(
            status,
            currentAir,
            wifiList,
            getRepeaterVersion(host)
        )
    }

    fun closeSession() {
        synchronized(sessionLock) {
            clearLegacyRelaySessionLocked()
        }
    }

    suspend fun setRepeaterName(host: String, ssid: String): CommandResult = withContext(Dispatchers.IO) {
        val payload = buildJsonCall(
            call = "set_repeater_ssid_password",
            fields = mapOf(
                "ssid" to ssid,
                "password_need_change" to "0",
                "password_need_value" to "",
                "authmode" to "wpa2_psk"
            )
        )
        tryRelayJson(host, "Set Repeater Name", payload)
    }

    suspend fun setRepeaterPassword(host: String, password: String): CommandResult = withContext(Dispatchers.IO) {
        val payload = buildJsonCall(
            call = "set_repeater_ssid_password",
            fields = mapOf(
                "ssid" to "",
                "password_need_change" to "1",
                "password_need_value" to password,
                "authmode" to "wpa2_psk"
            )
        )
        tryRelayJson(host, "Set Repeater Password", payload)
    }

    suspend fun bindCandidate(
        host: String,
        ssid: String,
        password: String,
        mac: String = "",
        channel: String = "",
        signal: String = "",
        encrypt: String = "",
        mode: String = "",
        channelBond: String = "",
        sideBand: String = ""
    ): CommandResult = withContext(Dispatchers.IO) {
        val payload = buildLegacyBindPayload(
            ssid = ssid,
            password = password,
            mac = mac,
            channel = channel,
            signal = signal,
            encrypt = encrypt,
            mode = mode,
            channelBond = channelBond,
            sideBand = sideBand
        )
        val sessionResult = tryLegacyBindCommandSession(host, payload)
        if (sessionResult.status.startsWith("SOCKET OK")) {
            return@withContext sessionResult
        }
        val socketResult = tryRelayCommandVariants(
            host = host,
            label = "Bind Candidate",
            variants = listOf(
                TcpVariant("tcp-legacy-bind-6662", 6662, payload.toByteArray())
            )
        )
        if (socketResult.status.startsWith("SOCKET OK")) {
            return@withContext socketResult
        }
        tryRelayJson(host, "Bind Candidate", payload)
    }

    suspend fun getRepeaterStatus(host: String): CommandResult = withContext(Dispatchers.IO) {
        val legacySessionResult = tryLegacyRepeaterStatusSession(host, "Repeater Status")
        if (looksUsable(legacySessionResult.preview)) return@withContext legacySessionResult

        val socketResult = getRepeaterStatusSocketOnly(host)
        if (looksUsable(socketResult.preview)) return@withContext socketResult
        tryRelayJson(host, "Repeater Status", buildJsonCall("get_repeater_status"))
    }

    suspend fun getRepeaterStatusSocketOnly(host: String): CommandResult = withContext(Dispatchers.IO) {
        tryRelaySocketVariants(
            host = host,
            label = "Repeater Status",
            variants = listOf(
                TcpVariant("tcp-byte-status-6662-0x0C", 6662, byteArrayOf(0x0C)),
                TcpVariant("tcp-json-status-6666", 6666, (buildJsonCall("get_repeater_status") + "\n").toByteArray()),
                TcpVariant("tcp-json-status-6662", 6662, (buildJsonCall("get_repeater_status") + "\n").toByteArray()),
                TcpVariant("tcp-json-status-16662", 16662, (buildJsonCall("get_repeater_status") + "\n").toByteArray())
            )
        )
    }

    suspend fun getRepeaterVersion(host: String): CommandResult = withContext(Dispatchers.IO) {
        tryRelayJson(host, "Repeater Version", buildJsonCall("get_repeater_version"))
    }

    suspend fun getCurrentAirWifiInfo(host: String): CommandResult = withContext(Dispatchers.IO) {
        val legacySessionResult = tryLegacyRepeaterStatusSession(host, "Current Air Wi-Fi")
        if (looksUsable(legacySessionResult.preview)) return@withContext legacySessionResult

        val socketResult = getCurrentAirWifiInfoSocketOnly(host)
        if (looksUsable(socketResult.preview)) return@withContext socketResult
        tryRelayJson(host, "Current Air Wi-Fi", buildJsonCall("get_current_air_wifi_info"))
    }

    suspend fun getCurrentAirWifiInfoSocketOnly(host: String): CommandResult = withContext(Dispatchers.IO) {
        tryRelaySocketVariants(
            host = host,
            label = "Current Air Wi-Fi",
            variants = listOf(
                TcpVariant("tcp-byte-current-air-6662-0x0C", 6662, byteArrayOf(0x0C))
            ) + relayClientJsonVariants("current-air", buildJsonCall("get_current_air_wifi_info"))
        )
    }

    suspend fun getWifiList(host: String): CommandResult = withContext(Dispatchers.IO) {
        val legacyDualSocketResult = tryLegacyDualSocketWifiListFlow(host)
        if (looksLikeWifiList(legacyDualSocketResult.preview)) {
            return@withContext legacyDualSocketResult.copy(
                preview = normalizeWifiListPreview(legacyDualSocketResult.preview)
            )
        }

        val sessionResult = tryRelayWifiScanSession(host)
        val socketResult = if (looksLikeWifiList(sessionResult.preview)) {
            sessionResult
        } else {
            tryRelaySocketVariants(
                host = host,
                label = "Wi-Fi List",
                variants = listOf(TcpVariant("tcp-byte-scan-6662-0x0B", 6662, byteArrayOf(0x0B))) +
                    relayClientJsonVariants("wifi-list", buildJsonCall("get_wifi_list"))
            )
        }

        val result = if (looksLikeWifiList(socketResult.preview)) {
            socketResult
        } else {
            val httpResult = tryRelayJson(host, "Wi-Fi List", buildJsonCall("get_wifi_list"))
            if (looksLikeWifiList(httpResult.preview)) httpResult else socketResult
        }

        val normalizedPreview = normalizeWifiListPreview(result.preview)
        if (normalizedPreview == "<empty body>") {
            return@withContext result.copy(
                preview = "<empty body>",
                status = result.status
            )
        }

        result.copy(preview = normalizedPreview)
    }

    private fun tryRelayWifiScanSession(host: String): CommandResult {
        val targets = linkedSetOf(host, "192.168.2.254")
        var last: CommandResult? = null

        for (target in targets) {
            val result = try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.soTimeout = 1200
                    socket.connect(InetSocketAddress(target, 6662), 2500)

                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()

                    val transcript = ByteArrayOutputStream()

                    repeat(2) {
                        output.write(byteArrayOf(0x0B))
                        output.flush()
                        Thread.sleep(140L)
                    }

                    val bytes = readWindowed(
                        input = input,
                        maxBytes = 16384,
                        totalWindowMs = 5200L,
                        quietAfterDataMs = 700L
                    )
                    if (bytes.isNotEmpty()) {
                        transcript.write(bytes)
                    }

                    val merged = transcript.toByteArray()
                    CommandResult(
                        label = "Wi-Fi List",
                        url = "socket://$target:6662/session-0x0B",
                        status = if (merged.isNotEmpty()) "SOCKET OK ${merged.size} bytes" else "SOCKET OK 0 bytes",
                        preview = merged.toUtf8Printable().take(2400)
                    )
                }
            } catch (t: Throwable) {
                CommandResult(
                    label = "Wi-Fi List",
                    url = "socket://$target:6662/session-0x0B",
                    status = t::class.java.simpleName ?: "Error",
                    preview = t.message.orEmpty()
                )
            }

            if (looksLikeWifiList(result.preview)) return result
            last = result
        }

        return last ?: CommandResult("Wi-Fi List", "socket://$host:6662/session-0x0B", "Error", "No relay session response")
    }

    private fun tryLegacyRepeaterStatusSession(host: String, label: String): CommandResult {
        val targets = linkedSetOf(host, "192.168.2.254")
        var last: CommandResult? = null

        for (target in targets) {
            val result = try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.soTimeout = 1400
                    socket.connect(InetSocketAddress(target, 6662), 2500)

                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    val transcript = ByteArrayOutputStream()

                    repeat(3) { index ->
                        output.write(byteArrayOf(0x0C))
                        output.flush()
                        val bytes = readAvailable(input, maxBytes = 8192, idleWaitMs = if (index == 0) 320L else 220L)
                        if (bytes.isNotEmpty()) {
                            transcript.write(bytes)
                        }
                        Thread.sleep(if (index == 0) 140L else 200L)
                    }

                    val merged = transcript.toByteArray()
                    CommandResult(
                        label = label,
                        url = "socket://$target:6662/status-session-0x0C",
                        status = if (merged.isNotEmpty()) "SOCKET OK ${merged.size} bytes" else "SOCKET OK 0 bytes",
                        preview = merged.toUtf8Printable().take(2400)
                    )
                }
            } catch (t: Throwable) {
                CommandResult(
                    label = label,
                    url = "socket://$target:6662/status-session-0x0C",
                    status = t::class.java.simpleName ?: "Error",
                    preview = t.message.orEmpty()
                )
            }

            if (looksUsable(result.preview)) return result
            last = result
        }

        return last ?: CommandResult(label, "socket://$host:6662/status-session-0x0C", "Error", "No relay status response")
    }

    private fun tryLegacyDualSocketWifiListFlow(host: String): CommandResult {
        val targets = linkedSetOf(host, "192.168.2.254")
        var last: CommandResult? = null

        for (target in targets) {
            for (controlPayload in legacyRelayControlPayloads) {
                val label = "dual-socket-6666+6662-${controlPayload.joinToString("") { "%02X".format(it) }}"
                val result = try {
                    Socket().use { control ->
                        Socket().use { scan ->
                            control.tcpNoDelay = true
                            control.keepAlive = true
                            control.soTimeout = 1200
                            scan.tcpNoDelay = true
                            scan.keepAlive = true
                            scan.soTimeout = 1500

                            control.connect(InetSocketAddress(target, 6666), 2500)
                            scan.connect(InetSocketAddress(target, 6662), 2500)

                            val controlInput = control.getInputStream()
                            val controlOutput = control.getOutputStream()
                            val scanInput = scan.getInputStream()
                            val scanOutput = scan.getOutputStream()
                            val transcript = ByteArrayOutputStream()

                            repeat(2) {
                                controlOutput.write(controlPayload)
                                controlOutput.flush()
                                val controlReply = readAvailable(controlInput, maxBytes = 2048, idleWaitMs = 120L)
                                if (controlReply.isNotEmpty()) {
                                    transcript.write(controlReply)
                                }
                                Thread.sleep(90L)
                            }

                            repeat(2) {
                                scanOutput.write(byteArrayOf(0x0B))
                                scanOutput.flush()
                                Thread.sleep(160L)
                            }

                            val scanReply = readWindowed(
                                input = scanInput,
                                maxBytes = 16384,
                                totalWindowMs = 5200L,
                                quietAfterDataMs = 700L,
                                tick = {
                                    runCatching {
                                        controlOutput.write(byteArrayOf(0x00))
                                        controlOutput.flush()
                                    }
                                }
                            )
                            if (scanReply.isNotEmpty()) {
                                transcript.write(scanReply)
                            }

                            val merged = transcript.toByteArray()
                            CommandResult(
                                label = "Wi-Fi List",
                                url = "socket://$target/$label",
                                status = if (merged.isNotEmpty()) "SOCKET OK ${merged.size} bytes" else "SOCKET OK 0 bytes",
                                preview = merged.toUtf8Printable().take(2400)
                            )
                        }
                    }
                } catch (t: Throwable) {
                    CommandResult(
                        label = "Wi-Fi List",
                        url = "socket://$target/$label",
                        status = t::class.java.simpleName ?: "Error",
                        preview = t.message.orEmpty()
                    )
                }

                if (looksLikeWifiList(result.preview)) return result
                last = result
            }
        }

        return last ?: CommandResult("Wi-Fi List", "socket://$host/dual", "Error", "No dual-socket relay response")
    }

    private fun tryLegacyBindMenuSession(host: String): LegacyBindMenuSessionResult? {
        val targets = linkedSetOf(host, "192.168.2.254")
        var fallback: LegacyBindMenuSessionResult? = null

        for (target in targets) {
            val plainSession = runLegacyBindMenuSession(target, useControlCompanion = false)
            if (plainSession != null) {
                if (looksUsable(plainSession.status.preview) || looksLikeWifiList(plainSession.wifiList.preview)) {
                    return plainSession
                }
                fallback = fallback ?: plainSession
            }

            val controlSession = runLegacyBindMenuSession(target, useControlCompanion = true)
            if (controlSession != null) {
                if (looksUsable(controlSession.status.preview) || looksLikeWifiList(controlSession.wifiList.preview)) {
                    return controlSession
                }
                fallback = fallback ?: controlSession
            }
        }

        return fallback
    }

    private fun runLegacyBindMenuSession(target: String, useControlCompanion: Boolean): LegacyBindMenuSessionResult? {
        return try {
            Socket().use { session ->
                session.tcpNoDelay = true
                session.keepAlive = true
                session.soTimeout = 700
                session.connect(InetSocketAddress(target, 6662), 2500)

                val input = session.getInputStream()
                val output = session.getOutputStream()
                val controlSocket = if (useControlCompanion) {
                    Socket().apply {
                        tcpNoDelay = true
                        keepAlive = true
                        soTimeout = 700
                        connect(InetSocketAddress(target, 6666), 2500)
                    }
                } else {
                    null
                }

                controlSocket.use { control ->
                    val controlInput = control?.getInputStream()
                    val controlOutput = control?.getOutputStream()

                    output.write(byteArrayOf(0x0C))
                    output.flush()
                    val statusBytes = readWindowed(
                        input = input,
                        maxBytes = 8192,
                        totalWindowMs = 1200L,
                        quietAfterDataMs = 260L
                    )

                    if (controlOutput != null && controlInput != null) {
                        repeat(2) {
                            controlOutput.write(byteArrayOf(0x00))
                            controlOutput.flush()
                            val controlReply = readWindowed(
                                input = controlInput,
                                maxBytes = 2048,
                                totalWindowMs = 450L,
                                quietAfterDataMs = 160L
                            )
                            if (controlReply.isNotEmpty()) {
                                // Keep the companion exchange alive before the delayed 6662 scan response.
                            }
                            Thread.sleep(100L)
                        }
                    }

                    Thread.sleep(180L)
                    output.write(byteArrayOf(0x0B))
                    output.flush()
                    val scanBytes = readWindowed(
                        input = input,
                        maxBytes = 16384,
                        totalWindowMs = 5200L,
                        quietAfterDataMs = 700L,
                        tick = {
                            if (control != null) {
                                runCatching {
                                    controlOutput?.write(byteArrayOf(0x00))
                                    controlOutput?.flush()
                                }
                            }
                        }
                    )

                    val statusPreview = statusBytes.toUtf8Printable().take(2400)
                    val scanPreview = scanBytes.toUtf8Printable().take(2400)
                    LegacyBindMenuSessionResult(
                        status = CommandResult(
                            label = "Repeater Status",
                            url = "socket://$target:6662/bind-session-status${if (useControlCompanion) "+6666" else ""}",
                            status = if (statusBytes.isNotEmpty()) "SOCKET OK ${statusBytes.size} bytes" else "SOCKET OK 0 bytes",
                            preview = statusPreview
                        ),
                        currentAir = CommandResult(
                            label = "Current Air Wi-Fi",
                            url = "socket://$target:6662/bind-session-current${if (useControlCompanion) "+6666" else ""}",
                            status = if (statusBytes.isNotEmpty()) "SOCKET OK ${statusBytes.size} bytes" else "SOCKET OK 0 bytes",
                            preview = statusPreview
                        ),
                        wifiList = CommandResult(
                            label = "Wi-Fi List",
                            url = "socket://$target:6662/bind-session-scan${if (useControlCompanion) "+6666" else ""}",
                            status = if (scanBytes.isNotEmpty()) "SOCKET OK ${scanBytes.size} bytes" else "SOCKET OK 0 bytes",
                            preview = scanPreview
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun tryPersistentSettingsRootSession(host: String): LegacyBindMenuSessionResult? {
        return synchronized(sessionLock) {
            val session = ensureLegacyRelaySessionLocked(host) ?: return@synchronized null
            runCatching {
                val statusBytes = requestStatusBytesLocked(session)
                buildSessionResult(
                    target = session.host,
                    statusBytes = statusBytes,
                    wifiListBytes = ByteArray(0),
                    urlSuffix = "persistent-root"
                )
            }.getOrElse {
                clearLegacyRelaySessionLocked()
                null
            }
        }
    }

    private fun tryPersistentBindMenuSession(host: String, forceRescan: Boolean): LegacyBindMenuSessionResult? {
        return synchronized(sessionLock) {
            val session = ensureLegacyRelaySessionLocked(host) ?: return@synchronized null
            runCatching {
                val statusBytes = requestStatusBytesLocked(session)
                val wifiListBytes = requestWifiListBytesLocked(session, forceRescan = forceRescan)
                buildSessionResult(
                    target = session.host,
                    statusBytes = statusBytes,
                    wifiListBytes = wifiListBytes,
                    urlSuffix = if (forceRescan) "persistent-bind-rescan" else "persistent-bind"
                )
            }.getOrElse {
                clearLegacyRelaySessionLocked()
                null
            }
        }
    }

    private fun tryLegacyBindCommandSession(host: String, payload: String): CommandResult {
        return synchronized(sessionLock) {
            val session = ensureLegacyRelaySessionLocked(host)
            if (session == null) {
                return@synchronized CommandResult(
                    label = "Bind Candidate",
                    url = "socket://$host:6662/legacy-bind-session",
                    status = "Error",
                    preview = "No relay session available"
                )
            }

            runCatching {
                maintainCompanionSocketsLocked(session, forceControlPulse = true)
                session.sessionOutput.write(payload.toByteArray())
                session.sessionOutput.flush()
                val reply = readWindowed(
                    input = session.sessionInput,
                    maxBytes = 4096,
                    totalWindowMs = 9_000L,
                    quietAfterDataMs = 1_200L,
                    tick = { maintainCompanionSocketsLocked(session) }
                )
                session.lastUsedAtMs = System.currentTimeMillis()
                val preview = reply.toUtf8Printable().take(2400)
                CommandResult(
                    label = "Bind Candidate",
                    url = "socket://${session.host}:6662/legacy-bind-session",
                    status = when {
                        reply.contentEquals(byteArrayOf(0x01)) -> "SOCKET OK bind-accepted"
                        reply.isNotEmpty() -> "SOCKET OK ${reply.size} bytes"
                        else -> "SOCKET OK 0 bytes"
                    },
                    preview = preview
                )
            }.getOrElse { error ->
                clearLegacyRelaySessionLocked()
                CommandResult(
                    label = "Bind Candidate",
                    url = "socket://$host:6662/legacy-bind-session",
                    status = error::class.java.simpleName ?: "Error",
                    preview = error.message.orEmpty()
                )
            }
        }
    }

    private fun buildSessionResult(
        target: String,
        statusBytes: ByteArray,
        wifiListBytes: ByteArray,
        urlSuffix: String
    ): LegacyBindMenuSessionResult {
        val statusPreview = statusBytes.toUtf8Printable().take(2400)
        val wifiPreview = wifiListBytes.toUtf8Printable().take(2400)
        val statusText = if (statusBytes.isNotEmpty()) "SOCKET OK ${statusBytes.size} bytes" else "SOCKET OK 0 bytes"
        val wifiText = if (wifiListBytes.isNotEmpty()) "SOCKET OK ${wifiListBytes.size} bytes" else "SOCKET OK 0 bytes"
        return LegacyBindMenuSessionResult(
            status = CommandResult(
                label = "Repeater Status",
                url = "socket://$target:6662/$urlSuffix-status",
                status = statusText,
                preview = statusPreview
            ),
            currentAir = CommandResult(
                label = "Current Air Wi-Fi",
                url = "socket://$target:6662/$urlSuffix-current",
                status = statusText,
                preview = statusPreview
            ),
            wifiList = CommandResult(
                label = "Wi-Fi List",
                url = "socket://$target:6662/$urlSuffix-scan",
                status = wifiText,
                preview = wifiPreview
            )
        )
    }

    private fun ensureLegacyRelaySessionLocked(host: String): LegacyRelaySession? {
        val now = System.currentTimeMillis()
        legacyRelaySession?.let { existing ->
            if (existing.host == host && existing.isHealthy(now)) {
                existing.lastUsedAtMs = now
                maintainCompanionSocketsLocked(existing)
                return existing
            }
            clearLegacyRelaySessionLocked()
        }

        val targets = linkedSetOf(host, "192.168.2.254")
        for (target in targets) {
            openLegacyRelaySession(target)?.let { session ->
                legacyRelaySession = session
                return session
            }
        }
        return null
    }

    private fun openLegacyRelaySession(target: String): LegacyRelaySession? {
        fun openSocket(port: Int, timeoutMs: Int): Socket {
            return Socket().apply {
                tcpNoDelay = true
                keepAlive = true
                soTimeout = timeoutMs
                connect(InetSocketAddress(target, port), 2500)
            }
        }

        var sessionSocket: Socket? = null
        var controlSocket: Socket? = null
        var auxSocket: Socket? = null
        return try {
            sessionSocket = openSocket(port = 6662, timeoutMs = 850)
            controlSocket = runCatching { openSocket(port = 6666, timeoutMs = 850) }.getOrNull()
            auxSocket = runCatching { openSocket(port = 6660, timeoutMs = 1200) }.getOrNull()
            LegacyRelaySession(
                host = target,
                sessionSocket = sessionSocket,
                controlSocket = controlSocket,
                auxSocket = auxSocket
            )
        } catch (_: Throwable) {
            runCatching { auxSocket?.close() }
            runCatching { controlSocket?.close() }
            runCatching { sessionSocket?.close() }
            null
        }
    }

    private fun requestStatusBytesLocked(session: LegacyRelaySession): ByteArray {
        maintainCompanionSocketsLocked(session, forceControlPulse = true)
        val sessionAgeMs = System.currentTimeMillis() - session.openedAtMs
        val attempts = if (sessionAgeMs < 4_000L) 2 else 1
        val bytes = ByteArrayOutputStream()

        repeat(attempts) { attempt ->
            session.sessionOutput.write(byteArrayOf(0x0C))
            session.sessionOutput.flush()
            val reply = readWindowed(
                input = session.sessionInput,
                maxBytes = 8192,
                totalWindowMs = if (sessionAgeMs < 4_000L) 3_600L else 2_200L,
                quietAfterDataMs = 320L,
                tick = { maintainCompanionSocketsLocked(session) }
            )
            if (reply.isNotEmpty()) {
                bytes.write(reply)
                session.lastUsedAtMs = System.currentTimeMillis()
                return bytes.toByteArray()
            }

            if (attempt < attempts - 1) {
                Thread.sleep(450L)
            }
        }

        session.lastUsedAtMs = System.currentTimeMillis()
        return bytes.toByteArray()
    }

    private fun requestWifiListBytesLocked(session: LegacyRelaySession, forceRescan: Boolean): ByteArray {
        maintainCompanionSocketsLocked(session, forceControlPulse = true)
        val sessionAgeMs = System.currentTimeMillis() - session.openedAtMs
        val attempts = if (forceRescan) 2 else 1

        repeat(attempts) { attempt ->
            session.sessionOutput.write(byteArrayOf(0x0B))
            session.sessionOutput.flush()
            val reply = readWindowed(
                input = session.sessionInput,
                maxBytes = 16384,
                totalWindowMs = if (sessionAgeMs < 4_000L) 7_200L else 5_200L,
                quietAfterDataMs = 900L,
                tick = { maintainCompanionSocketsLocked(session) }
            )
            if (reply.isNotEmpty()) {
                session.lastUsedAtMs = System.currentTimeMillis()
                return reply
            }

            if (attempt < attempts - 1) {
                Thread.sleep(650L)
            }
        }

        session.lastUsedAtMs = System.currentTimeMillis()
        return ByteArray(0)
    }

    private fun maintainCompanionSocketsLocked(
        session: LegacyRelaySession,
        forceControlPulse: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        if (session.auxSocket != null && now >= session.auxCloseDeadlineMs) {
            runCatching { session.auxSocket?.close() }
            session.auxSocket = null
        }
        if (session.controlSocket != null && (forceControlPulse || now - session.lastControlPulseAtMs >= 2_700L)) {
            runCatching {
                session.controlOutput?.write(byteArrayOf(0x00))
                session.controlOutput?.flush()
            }
            session.lastControlPulseAtMs = now
        }
    }

    private fun clearLegacyRelaySessionLocked() {
        legacyRelaySession?.close()
        legacyRelaySession = null
    }

    private fun buildJsonCall(call: String, fields: Map<String, String> = emptyMap()): String {
        val bodyFields = buildString {
            append("\"call\":\"")
            append(escapeJson(call))
            append("\"")
            for ((key, value) in fields) {
                append(',')
                append('"')
                append(escapeJson(key))
                append("\":\"")
                append(escapeJson(value))
                append('"')
            }
        }
        return "{$bodyFields}"
    }

    private fun buildLegacyBindPayload(
        ssid: String,
        password: String,
        mac: String,
        channel: String,
        signal: String,
        encrypt: String,
        mode: String,
        channelBond: String,
        sideBand: String
    ): String {
        fun normalizeNumber(value: String, fallback: Int): String {
            return value.trim().toIntOrNull()?.toString() ?: fallback.toString()
        }

        val normalizedMac = mac.trim()
        val normalizedMode = mode.trim().ifBlank { "(G)" }
        val normalizedEncrypt = encrypt.trim().ifBlank { "WPA2-PSK" }
        val normalizedSignal = normalizeNumber(signal, fallback = 0)
        val normalizedChannel = normalizeNumber(channel, fallback = 1)
        val normalizedChannelBond = normalizeNumber(channelBond, fallback = 0)
        val normalizedSideBand = normalizeNumber(sideBand, fallback = 1)

        return buildString {
            append('{')
            append("\"Signal\":").append(normalizedSignal)
            append(",\"Action\":1")
            append(",\"BSSID\":\"").append(escapeJson(normalizedMac)).append('"')
            append(",\"Mode\":\"").append(escapeJson(normalizedMode)).append('"')
            append(",\"Channel\":").append(normalizedChannel)
            append(",\"Enable\":1")
            append(",\"PWD\":\"").append(escapeJson(password)).append('"')
            append(",\"SSID\":\"").append(escapeJson(ssid)).append('"')
            append(",\"Encrypt\":\"").append(escapeJson(normalizedEncrypt)).append('"')
            append(",\"ChannelBond\":").append(normalizedChannelBond)
            append(",\"SideBand\":").append(normalizedSideBand)
            append('}')
        }
    }

    private fun tryRelayJson(host: String, label: String, json: String): CommandResult {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val normalizedHosts = linkedSetOf(host, "192.168.2.254")
        val urls = normalizedHosts.flatMap { target ->
            listOf(
                "http://$target/",
                "http://$target/cgi-bin/relay",
                "http://$target/relay",
                "http://$target/call"
            )
        }

        var last: CommandResult? = null

        for (url in urls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .post(json.toRequestBody(mediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty().ifBlank { "<empty body>" }
                    val result = CommandResult(
                        label = label,
                        url = url,
                        status = "${response.code} ${response.message}",
                        preview = body.take(2400)
                    )

                    if (response.isSuccessful && !isHtmlError(body)) {
                        return result
                    }

                    last = result
                }
            } catch (t: Throwable) {
                last = CommandResult(
                    label = label,
                    url = "http://$host/",
                    status = t::class.java.simpleName ?: "Error",
                    preview = t.message.orEmpty()
                )
            }
        }

        return last ?: CommandResult(label, "(none)", "Error", "No relay response")
    }

    private fun tryRelaySocketVariants(host: String, label: String, variants: List<TcpVariant>): CommandResult {
        val normalizedHosts = linkedSetOf(host, "192.168.2.254")
        var last: CommandResult? = null

        for (target in normalizedHosts) {
            for (variant in variants.distinct()) {
                val result = trySocket(target, label, variant)

                if (label == "Wi-Fi List") {
                    if (looksLikeWifiList(result.preview)) return result
                } else {
                    if (looksUsable(result.preview)) return result
                }

                last = result
            }
        }

        return last ?: CommandResult(label, "socket://$host", "Error", "No relay socket response")
    }

    private fun tryRelayCommandVariants(host: String, label: String, variants: List<TcpVariant>): CommandResult {
        val normalizedHosts = linkedSetOf(host, "192.168.2.254")
        var last: CommandResult? = null

        for (target in normalizedHosts) {
            for (variant in variants.distinct()) {
                val result = trySocket(target, label, variant)
                if (result.status.startsWith("SOCKET OK")) return result
                last = result
            }
        }

        return last ?: CommandResult(label, "socket://$host", "Error", "No relay socket response")
    }

    private fun trySocket(host: String, label: String, variant: TcpVariant): CommandResult {
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.soTimeout = 4500
                socket.connect(InetSocketAddress(host, variant.port), 2500)

                val out = socket.getOutputStream()
                out.write(variant.payload)
                out.flush()

                val previewBytes = readAvailable(socket)
                val previewText = previewBytes.toUtf8Printable()

                CommandResult(
                    label = label,
                    url = "socket://$host:${variant.port}/${variant.label}",
                    status = if (previewBytes.isNotEmpty()) {
                        "SOCKET OK ${previewBytes.size} bytes"
                    } else {
                        "SOCKET OK 0 bytes"
                    },
                    preview = previewText.take(2400)
                )
            }
        } catch (t: Throwable) {
            CommandResult(
                label = label,
                url = "socket://$host:${variant.port}/${variant.label}",
                status = t::class.java.simpleName ?: "Error",
                preview = t.message.orEmpty()
            )
        }
    }

    private fun readAvailable(socket: Socket): ByteArray {
        return readAvailable(socket.getInputStream(), maxBytes = 16384, idleWaitMs = 180L)
    }

    private fun readAvailable(input: java.io.InputStream, maxBytes: Int, idleWaitMs: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var consecutiveTimeouts = 0
        var idleLoopsAfterData = 0

        while (output.size() < maxBytes && consecutiveTimeouts < 3) {
            try {
                val read = input.read(buffer)
                if (read <= 0) break

                output.write(buffer, 0, read)
                consecutiveTimeouts = 0
                idleLoopsAfterData = 0

                if (input.available() == 0 && output.size() > 0) {
                    val waitMs = if (output.size() <= 1) 420L else idleWaitMs
                    Thread.sleep(waitMs)
                    if (input.available() == 0) {
                        idleLoopsAfterData++
                        if (output.size() > 1 || idleLoopsAfterData >= 2) break
                    }
                }
            } catch (_: java.net.SocketTimeoutException) {
                consecutiveTimeouts++
            }
        }

        return output.toByteArray()
    }

    private fun readWindowed(
        input: java.io.InputStream,
        maxBytes: Int,
        totalWindowMs: Long,
        quietAfterDataMs: Long,
        tick: (() -> Unit)? = null
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        val deadline = System.currentTimeMillis() + totalWindowMs
        var lastDataAt = 0L
        var lastTickAt = 0L

        while (System.currentTimeMillis() < deadline && output.size() < maxBytes) {
            val now = System.currentTimeMillis()
            if (tick != null && now - lastTickAt >= 900L) {
                tick()
                lastTickAt = now
            }

            try {
                val read = input.read(buffer)
                if (read > 0) {
                    output.write(buffer, 0, read)
                    lastDataAt = System.currentTimeMillis()
                    continue
                }
            } catch (_: java.net.SocketTimeoutException) {
                // Keep waiting inside the overall window.
            }

            if (output.size() > 0 && lastDataAt > 0L && System.currentTimeMillis() - lastDataAt >= quietAfterDataMs) {
                break
            }
        }

        return output.toByteArray()
    }

    private fun ByteArray.toUtf8Printable(): String {
        if (isEmpty()) return "<empty body>"

        val mixedPrintableRuns = extractPrintableRuns(this)
        val widePrintableRuns = extractWidePrintableRuns(this)
        val mergedRuns = (mixedPrintableRuns + widePrintableRuns).distinct()
        if (mergedRuns.isNotEmpty()) {
            return mergedRuns.joinToString("\n")
        }

        val asciiScore = count { byte ->
            val value = byte.toInt() and 0xFF
            value == 9 || value == 10 || value == 13 || value in 32..126
        }

        return if (asciiScore >= size * 0.75) {
            toString(Charsets.UTF_8)
        } else {
            joinToString(separator = " ") { byte ->
                "%02X".format(byte.toInt() and 0xFF)
            }
        }
    }

    private fun extractPrintableRuns(bytes: ByteArray): List<String> {
        val results = mutableListOf<String>()
        val current = StringBuilder()

        fun flushCurrent() {
            val candidate = current.toString()
                .trim()
                .trim('"', '\'')
                .trimEnd { it == '\u0000' || it.isWhitespace() }
            if (candidate.length >= 3 && candidate.any(Char::isLetter)) {
                results += candidate
            }
            current.setLength(0)
        }

        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            if (value == 9 || value == 10 || value == 13 || value in 32..126) {
                current.append(value.toChar())
            } else {
                flushCurrent()
            }
        }
        flushCurrent()

        return results
            .map { it.replace('\u0000', ' ').trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.equals("<empty body>", ignoreCase = true) }
            .distinct()
            .take(20)
    }

    private fun extractWidePrintableRuns(bytes: ByteArray): List<String> {
        val littleEndianRuns = collectWideAsciiRuns(bytes, charByteFirst = true)
        val bigEndianRuns = collectWideAsciiRuns(bytes, charByteFirst = false)
        return (littleEndianRuns + bigEndianRuns)
            .map { it.replace('\u0000', ' ').trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(20)
    }

    private fun collectWideAsciiRuns(bytes: ByteArray, charByteFirst: Boolean): List<String> {
        val results = mutableListOf<String>()

        fun isPrintableAscii(value: Int): Boolean {
            return value == 9 || value == 10 || value == 13 || value in 32..126
        }

        for (offset in 0..1) {
            val current = StringBuilder()

            fun flushCurrent() {
                val candidate = current.toString()
                    .trim()
                    .trim('"', '\'')
                    .trimEnd { it == '\u0000' || it.isWhitespace() }
                if (candidate.length >= 3 && candidate.any(Char::isLetter)) {
                    results += candidate
                }
                current.setLength(0)
            }

            var index = offset
            while (index + 1 < bytes.size) {
                val first = bytes[index].toInt() and 0xFF
                val second = bytes[index + 1].toInt() and 0xFF
                val charValue = if (charByteFirst) first else second
                val zeroValue = if (charByteFirst) second else first
                if (zeroValue == 0 && isPrintableAscii(charValue)) {
                    current.append(charValue.toChar())
                } else {
                    flushCurrent()
                }
                index += 2
            }
            flushCurrent()
        }

        return results
    }

    private fun relayClientJsonVariants(baseLabel: String, json: String): List<TcpVariant> {
        val newlinePayload = (json + "\n").toByteArray()
        val rawPayload = json.toByteArray()
        return listOf(
            TcpVariant("tcp-json-$baseLabel-16662", 16662, newlinePayload),
            TcpVariant("tcp-json-$baseLabel-6666", 6666, newlinePayload),
            TcpVariant("tcp-json-$baseLabel-6662", 6662, newlinePayload),
            TcpVariant("tcp-json-$baseLabel-16662-raw", 16662, rawPayload),
            TcpVariant("tcp-json-$baseLabel-6666-raw", 6666, rawPayload),
            TcpVariant("tcp-json-$baseLabel-6662-raw", 6662, rawPayload)
        )
    }

    private fun isHtmlError(text: String): Boolean {
        val normalized = text.trim()
        return normalized.contains("<html", ignoreCase = true) ||
                normalized.contains("<head", ignoreCase = true) ||
                normalized.contains("<body", ignoreCase = true) ||
                normalized.contains("400 Bad Request", ignoreCase = true) ||
                normalized.contains("malformed or illegal request", ignoreCase = true)
    }

    private fun looksUsable(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isBlank() || normalized == "<empty body>") return false
        if (isHtmlError(normalized)) return false
        if (normalized.contains("UnknownHost", ignoreCase = true)) return false
        if (normalized.contains("SocketTimeout", ignoreCase = true)) return false
        if (looksLikeTransportNoise(normalized)) return false
        return true
    }

    private fun looksLikeWifiList(text: String): Boolean {
        return RelayPayloadParser.looksLikeStructuredWifiListPayload(text) ||
            sanitizeWifiListPreview(text).isNotEmpty()
    }

    private fun normalizeWifiListPreview(text: String): String {
        if (RelayPayloadParser.looksLikeStructuredWifiListPayload(text)) {
            return text.trim().ifBlank { "<empty body>" }
        }

        val cleaned = sanitizeWifiListPreview(text)
        return if (cleaned.isEmpty()) "<empty body>" else cleaned.joinToString("\n")
    }

    private fun sanitizeWifiListPreview(text: String): List<String> {
        val normalized = text.trim()
        if (normalized.isBlank()) return emptyList()
        if (normalized == "<empty body>") return emptyList()
        if (isHtmlError(normalized)) return emptyList()
        if (normalized.contains("UnknownHost", ignoreCase = true)) return emptyList()
        if (normalized.contains("SocketTimeout", ignoreCase = true)) return emptyList()
        if (normalized.contains("Connection refused", ignoreCase = true)) return emptyList()
        if (normalized.contains("SOCKET OK", ignoreCase = true)) return emptyList()

        return normalized.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it == "<empty body>" }
            .filterNot { candidate -> isHtmlError(candidate) }
            .filterNot { candidate -> candidate.startsWith("<") }
            .filterNot { candidate -> candidate.contains("Connection refused", ignoreCase = true) }
            .filterNot { candidate -> candidate.contains("Exception", ignoreCase = true) }
            .filterNot { candidate -> candidate.contains("SocketTimeout", ignoreCase = true) }
            .filterNot { candidate -> candidate.contains("SOCKET OK", ignoreCase = true) }
            .filterNot { candidate -> looksLikeTransportNoise(candidate) }
            .filter { candidate -> candidate.length in 2..64 }
            .distinct()
            .toList()
    }

    private fun looksLikeTransportNoise(text: String): Boolean {
        val compact = text.trim()
        if (compact.isBlank()) return true
        if (compact.length <= 4 && compact.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return true
        if (compact.length <= 8 && compact.matches(Regex("(?:[0-9A-Fa-f]{2}\\s*)+"))) return true
        if (compact.equals("ff", ignoreCase = true)) return true
        return false
    }

    private fun escapeJson(value: String): String = buildString(value.length + 8) {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
}
