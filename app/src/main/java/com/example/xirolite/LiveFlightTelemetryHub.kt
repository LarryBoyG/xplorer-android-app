package com.example.xirolite

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object LiveFlightTelemetryHub {
    private val probe = RemoteTelemetryProbe()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var watchJob: Job? = null
    private var logger: ((String) -> Unit)? = null

    private val _recentPackets = MutableStateFlow<List<RemoteTelemetryPacket>>(emptyList())
    val recentPackets: StateFlow<List<RemoteTelemetryPacket>> = _recentPackets.asStateFlow()

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
        }
    }

    fun clearPackets() {
        _recentPackets.value = emptyList()
    }
}
