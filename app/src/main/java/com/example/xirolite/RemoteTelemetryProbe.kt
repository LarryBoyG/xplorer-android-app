package com.example.xirolite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

data class WatchedOffsetValue(
    val index: Int,
    val hex: String,
    val unsigned: Int,
    val signed: Int,
    val changed: Boolean
)

data class PairDecodeValue(
    val startIndex: Int,
    val hex: String,
    val u16le: Int,
    val s16le: Int
)

data class RemoteStateGuess(
    val familyHex: String,
    val subStateHex: String,
    val label: String
)

data class RemoteTelemetryPacket(
    val sourceIp: String,
    val sourcePort: Int,
    val length: Int,
    val rawData: ByteArray,
    val hexFull: String,
    val hexPreview: String,
    val asciiPreview: String,
    val timestampMs: Long,
    val changedByteIndexes: List<Int>,
    val watchedOffsets: List<WatchedOffsetValue>,
    val watchedPairs: List<PairDecodeValue>,
    val stateGuess: RemoteStateGuess?,
    val controlTripletHex: String,
    val stepPairHex: String,
    val phaseCounterHex: String,
    val tailHex: String,
    val stickEstimate: RemoteStickEstimate
) {
    fun rawUnsigned(index: Int): Int? = rawData.getOrNull(index)?.toInt()?.and(0xFF)

    fun rawSigned(index: Int): Int? = rawData.getOrNull(index)?.toInt()

    fun rawU16le(startIndex: Int): Int? {
        if (startIndex < 0 || startIndex + 1 >= rawData.size) return null
        val lo = rawData[startIndex].toInt() and 0xFF
        val hi = rawData[startIndex + 1].toInt() and 0xFF
        return lo or (hi shl 8)
    }

    fun rawS16le(startIndex: Int): Int? {
        val u16 = rawU16le(startIndex) ?: return null
        return if (u16 >= 0x8000) u16 - 0x10000 else u16
    }

    fun rawF32le(startIndex: Int): Float? {
        if (startIndex < 0 || startIndex + 3 >= rawData.size) return null
        return runCatching {
            ByteBuffer.wrap(rawData, startIndex, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .float
        }.getOrNull()
    }

    fun rawSlice(startIndex: Int, endExclusive: Int): ByteArray? {
        if (startIndex < 0 || endExclusive > rawData.size || startIndex >= endExclusive) return null
        return rawData.copyOfRange(startIndex, endExclusive)
    }
}

class RemoteTelemetryProbe {

    private val defaultWatchedOffsets = listOf(
        19, 20, 21,
        23,
        28, 29,
        41,
        46, 47, 48,
        51, 52,
        54,
        57, 59,
        69, 70,
        79,
        86, 87, 88,
        97
    )

    private val defaultWatchedPairs = listOf(
        19, 20,
        23,
        28,
        46, 47,
        51,
        57,
        69,
        79,
        86, 87
    )

    suspend fun watchUdp6800(
        listenPort: Int = 6800,
        socketTimeoutMs: Int = 1000,
        previewBytes: Int = 32,
        sourceFilter: String? = null,
        watchedOffsets: List<Int> = defaultWatchedOffsets,
        watchedPairs: List<Int> = defaultWatchedPairs,
        onPacket: (RemoteTelemetryPacket) -> Unit,
        onLog: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val socket = DatagramSocket(null)
        var previousData: ByteArray? = null

        try {
            socket.reuseAddress = true
            socket.broadcast = true
            socket.soTimeout = socketTimeoutMs
            socket.bind(InetSocketAddress(listenPort))

            onLog("UDP listener bound on port $listenPort")

            val buffer = ByteArray(4096)

            while (isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)

                    val sourceIpActual = packet.address?.hostAddress ?: "unknown"
                    val sourcePortActual = packet.port

                    if (sourceFilter != null && sourceIpActual != sourceFilter) {
                        continue
                    }

                    val data = packet.data.copyOf(packet.length)
                    val preview = data.copyOf(minOf(previewBytes, data.size))
                    val changed = diffIndexes(previousData, data)
                    val stateGuess = buildStateGuess(data)
                    val stickEstimate = buildStickEstimate(data, stateGuess)

                    val result = RemoteTelemetryPacket(
                        sourceIp = sourceIpActual,
                        sourcePort = sourcePortActual,
                        length = packet.length,
                        rawData = data,
                        hexFull = toHex(data),
                        hexPreview = toHex(preview),
                        asciiPreview = toAscii(preview),
                        timestampMs = System.currentTimeMillis(),
                        changedByteIndexes = changed,
                        watchedOffsets = buildWatchedOffsets(previousData, data, watchedOffsets),
                        watchedPairs = buildWatchedPairs(data, watchedPairs),
                        stateGuess = stateGuess,
                        controlTripletHex = bytesToHexRange(data, 19, 21),
                        stepPairHex = bytesToHexRange(data, 46, 47),
                        phaseCounterHex = bytesToHexRange(data, 79, 80),
                        tailHex = bytesToHexRange(data, 86, 88),
                        stickEstimate = stickEstimate
                    )

                    previousData = data
                    onPacket(result)
                } catch (_: SocketTimeoutException) {
                    // normal timeout
                }
            }
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            onLog("UDP listener closed")
        }
    }

    private fun buildStateGuess(data: ByteArray): RemoteStateGuess? {
        val family = data.getOrNull(28)?.toInt()?.and(0xFF) ?: return null
        val sub = data.getOrNull(29)?.toInt()?.and(0xFF) ?: return null

        val familyHex = "%02X".format(family)
        val subHex = "%02X".format(sub)

        val label = when {
            family == 0x3A -> "Manual Stick Input (3A family)"

            family == 0x08 && sub == 0x20 -> "Motors Off Idle (guess)"
            family == 0x0A && sub == 0x07 -> "Motors On Idle - Hands Off (guess)"
            family == 0x0A && sub == 0x36 -> "Motors On Idle / Run State (guess)"
            family == 0x0B && sub == 0x30 -> "Throttle Up (guess)"
            family == 0x0C && (sub == 0x2E || sub == 0x2F) -> "Throttle Down / Stop Transition (guess)"

            family == 0x1E && sub == 0x03 -> "Forward (guess)"
            family == 0x1F && sub == 0x28 -> "Left (guess)"
            family == 0x20 && sub == 0x03 -> "Right (guess)"
            family == 0x1F && sub == 0x04 -> "Back (guess)"
            family == 0x1C && sub == 0x25 -> "Rotate Left (guess)"
            family == 0x1D && sub == 0x0C -> "Rotate Right (guess)"

            family == 0x16 && (sub == 0x06 || sub == 0x07) -> "Motor Up From Center (guess)"
            family == 0x16 && sub == 0x3A -> "Motors On Idle Only (guess)"
            family == 0x17 && sub == 0x2E -> "Back / Rear Motion Family (guess)"
            family == 0x18 && (sub == 0x27 || sub == 0x2A) -> "Motor Down Until Stop (guess)"

            else -> "Unknown"
        }

        return RemoteStateGuess(
            familyHex = familyHex,
            subStateHex = subHex,
            label = label
        )
    }

    private fun buildStickEstimate(
        data: ByteArray,
        stateGuess: RemoteStateGuess?
    ): RemoteStickEstimate {
        val family = data.getOrNull(28)?.toInt()?.and(0xFF) ?: 0
        val rawX = data.getOrNull(19)?.toInt()?.and(0xFF) ?: 0
        val rawY = data.getOrNull(20)?.toInt()?.and(0xFF) ?: 0

        fun normalize(v: Int, center: Int): Float {
            val delta = v - center
            return (delta / 80f).coerceIn(-1f, 1f)
        }

        return if (family == 0x3A) {
            RemoteStickEstimate(
                left = StickPosition(
                    x = normalize(rawX, 0x80),
                    y = normalize(rawY, 0x80)
                ),
                right = StickPosition(0f, 0f),
                label = "Manual Stick Input (3A family)"
            )
        } else {
            RemoteStickEstimate(
                left = StickPosition(0f, 0f),
                right = StickPosition(0f, 0f),
                label = stateGuess?.label ?: "Unknown"
            )
        }
    }

    private fun buildWatchedOffsets(
        previous: ByteArray?,
        current: ByteArray,
        offsets: List<Int>
    ): List<WatchedOffsetValue> {
        return offsets.distinct().mapNotNull { index ->
            val value = current.getOrNull(index) ?: return@mapNotNull null
            val unsigned = value.toInt() and 0xFF
            val signed = value.toInt()
            val previousValue = previous?.getOrNull(index)
            val changed = previousValue == null || previousValue != value

            WatchedOffsetValue(
                index = index,
                hex = "%02X".format(unsigned),
                unsigned = unsigned,
                signed = signed,
                changed = changed
            )
        }
    }

    private fun buildWatchedPairs(
        data: ByteArray,
        pairStarts: List<Int>
    ): List<PairDecodeValue> {
        return pairStarts.distinct().mapNotNull { start ->
            if (start + 1 >= data.size) return@mapNotNull null

            val lo = data[start].toInt() and 0xFF
            val hi = data[start + 1].toInt() and 0xFF
            val u16 = lo or (hi shl 8)
            val s16 = if (u16 >= 0x8000) u16 - 0x10000 else u16

            PairDecodeValue(
                startIndex = start,
                hex = "%02X %02X".format(lo, hi),
                u16le = u16,
                s16le = s16
            )
        }
    }

    private fun diffIndexes(previous: ByteArray?, current: ByteArray): List<Int> {
        if (previous == null) return emptyList()

        val maxLen = maxOf(previous.size, current.size)
        val changed = mutableListOf<Int>()

        for (i in 0 until maxLen) {
            val a = previous.getOrNull(i)
            val b = current.getOrNull(i)
            if (a != b) changed += i
        }

        return changed
    }

    private fun bytesToHexRange(data: ByteArray, start: Int, endInclusive: Int): String {
        if (start < 0 || endInclusive >= data.size || start > endInclusive) return "(n/a)"
        return (start..endInclusive).joinToString(" ") { index ->
            "%02X".format(data[index].toInt() and 0xFF)
        }
    }

    private fun toHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { b -> "%02X".format(b.toInt() and 0xFF) }
    }

    private fun toAscii(bytes: ByteArray): String {
        return buildString {
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                append(if (v in 32..126) v.toChar() else '.')
            }
        }
    }
}
