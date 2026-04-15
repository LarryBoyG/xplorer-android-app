package com.example.xirolite

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HjLogRecorderState(
    val isActive: Boolean = false,
    val recordsWritten: Int = 0,
    val activeFile: File? = null,
    val lastSavedFile: File? = null,
    val statusText: String = "Idle"
)

class HjLogRecorder(
    private val localLibraryManager: LocalLibraryManager
) {
    private var outputStream: FileOutputStream? = null
    private var activeFile: File? = null
    private var lastSavedFile: File? = null
    private var recordsWritten: Int = 0
    private var sessionStartedAtMs: Long = 0L
    private var firstPacketTimestampMs: Long? = null
    private var lastPacketTimestampMs: Long? = null

    @Synchronized
    fun startSession(tag: String = "XIROLITE"): HjLogRecorderState {
        closeInternal(deleteActiveFile = recordsWritten == 0)

        val folders = localLibraryManager.ensureFolders()
        val now = System.currentTimeMillis()
        val timestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date(now))
        val safeTag = tag.replace(Regex("[^A-Za-z0-9]+"), "").ifBlank { "XIROLITE" }
        val file = File(folders.metadata, "ANDROID_T${timestamp}${safeTag}.hj")

        outputStream = FileOutputStream(file, false)
        activeFile = file
        recordsWritten = 0
        sessionStartedAtMs = now
        firstPacketTimestampMs = null
        lastPacketTimestampMs = null

        return currentState("Recording .hj to ${file.name}")
    }

    @Synchronized
    fun appendPacket(packet: RemoteTelemetryPacket): HjLogRecorderState {
        val record = packet.toHjRecord() ?: return currentState("Waiting for HJ-compatible UDP packets")
        val stream = outputStream ?: return currentState()
        if (firstPacketTimestampMs == null) {
            firstPacketTimestampMs = packet.timestampMs
        }
        lastPacketTimestampMs = packet.timestampMs
        stream.write(record)
        stream.flush()
        recordsWritten += 1
        return currentState("Recording .hj (${recordsWritten} records)")
    }

    @Synchronized
    fun stopSession(): HjLogRecorderState {
        val written = recordsWritten
        val sourceFile = activeFile
        val referenceStartMs = firstPacketTimestampMs ?: sessionStartedAtMs
        val referenceEndMs = lastPacketTimestampMs ?: System.currentTimeMillis()
        closeInternal(deleteActiveFile = recordsWritten == 0)
        val savedFile = if (written > 0) {
            finalizeSavedFile(
                sourceFile = sourceFile,
                referenceStartMs = referenceStartMs,
                referenceEndMs = referenceEndMs
            )
        } else {
            null
        }
        if (savedFile != null) {
            lastSavedFile = savedFile
        }
        return if (savedFile != null) {
            HjLogRecorderState(
                isActive = false,
                recordsWritten = written,
                activeFile = null,
                lastSavedFile = savedFile,
                statusText = "Saved ${savedFile.name} ($written records)"
            )
        } else {
            HjLogRecorderState(
                isActive = false,
                recordsWritten = 0,
                activeFile = null,
                lastSavedFile = lastSavedFile,
                statusText = "Idle"
            )
        }
    }

    @Synchronized
    fun currentState(statusOverride: String? = null): HjLogRecorderState {
        return HjLogRecorderState(
            isActive = outputStream != null,
            recordsWritten = recordsWritten,
            activeFile = activeFile,
            lastSavedFile = lastSavedFile,
            statusText = statusOverride
                ?: if (outputStream != null) {
                    "Recording .hj (${recordsWritten} records)"
                } else {
                    lastSavedFile?.let { "Last saved: ${it.name}" } ?: "Idle"
                }
        )
    }

    @Synchronized
    private fun closeInternal(deleteActiveFile: Boolean) {
        runCatching { outputStream?.flush() }
        runCatching { outputStream?.close() }
        outputStream = null
        if (deleteActiveFile) {
            runCatching { activeFile?.delete() }
        }
        activeFile = null
        recordsWritten = 0
        sessionStartedAtMs = 0L
        firstPacketTimestampMs = null
        lastPacketTimestampMs = null
    }

    private fun finalizeSavedFile(
        sourceFile: File?,
        referenceStartMs: Long,
        referenceEndMs: Long
    ): File? {
        val file = sourceFile ?: return null
        val durationMs = (referenceEndMs - referenceStartMs).coerceAtLeast(0L)
        val readableDate = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(referenceStartMs))
        val durationText = durationMs.toDurationFileToken()
        val target = uniqueSibling(
            file = file,
            desiredName = "${file.nameWithoutExtension}_${readableDate}_${durationText}.hj"
        )
        if (target.absolutePath == file.absolutePath) return file
        return if (file.renameTo(target)) target else file
    }

    private fun uniqueSibling(file: File, desiredName: String): File {
        var candidate = File(file.parentFile, desiredName)
        var suffix = 2
        while (candidate.exists()) {
            val stem = desiredName.removeSuffix(".hj")
            candidate = File(file.parentFile, "${stem}_$suffix.hj")
            suffix += 1
        }
        return candidate
    }
}

private fun Long.toDurationFileToken(): String {
    val totalSeconds = this / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "dur_%02dh%02dm%02ds", hours, minutes, seconds)
}

private fun RemoteTelemetryPacket.toHjRecord(): ByteArray? {
    if (rawData.size < 98) return null
    val record = ByteArray(99)
    record[0] = 0x24
    record[1] = 0x53
    record[2] = 0x54
    record[3] = 0x50
    System.arraycopy(rawData, 3, record, 4, 95)
    return record
}
