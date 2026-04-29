package com.example.xirolite

import android.content.Context
import com.example.xirolite.data.LegacyDroneProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class RootedLiveViewCaptureSession(
    val transportFolderName: String,
    val folder: File,
    val pcapFile: File,
    val logcatFile: File,
    val metadataFile: File
)

class RootedLiveViewCaptureManager(
    private val context: Context,
    private val localLibraryManager: LocalLibraryManager
) {
    companion object {
        private const val CAPTURE_DURATION_SECONDS = 60
    }

    suspend fun startCapture(
        host: String,
        relayHost: String,
        profile: LegacyDroneProfile,
        streamProfile: StreamProfile,
        appPid: Int
    ): Result<RootedLiveViewCaptureSession> = withContext(Dispatchers.IO) {
        runCatching {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val transportFolderName = if (streamProfile.forceRtpTcp) "TCP" else "UDP"
            val captureRoot = File(localLibraryManager.activeRootPath(), "live_view_logs")
            val captureFolder = File(captureRoot, transportFolderName)
            if (!captureFolder.exists() && !captureFolder.mkdirs()) {
                error("Unable to create ${captureFolder.absolutePath}")
            }

            val fileBase = "xiro_live_view_${transportFolderName.lowercase(Locale.US)}_$timestamp"
            val pcapFile = File(captureFolder, "$fileBase.pcap")
            val logcatFile = File(captureFolder, "${fileBase}_logcat.txt")
            val metadataFile = File(captureFolder, "${fileBase}_meta.txt")
            val scriptFile = File(context.cacheDir, "${fileBase}_capture.sh")

            metadataFile.writeText(
                buildString {
                    appendLine("XIRO Lite Live View Combo Capture")
                    appendLine("Started: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                    appendLine("Profile: ${profile.displayName}")
                    appendLine("Requested Stream Profile: ${streamProfile.label}")
                    appendLine("Transport Folder: $transportFolderName")
                    appendLine("Camera Host: $host")
                    appendLine("Relay Host: $relayHost")
                    appendLine("App PID: $appPid")
                    appendLine("Duration: $CAPTURE_DURATION_SECONDS seconds")
                    appendLine("PCAP: ${pcapFile.absolutePath}")
                    appendLine("Logcat: ${logcatFile.absolutePath}")
                }
            )

            scriptFile.writeText(
                buildScript(
                    host = host,
                    relayHost = relayHost,
                    pcapFile = pcapFile,
                    logcatFile = logcatFile,
                    metadataFile = metadataFile,
                    appPid = appPid
                )
            )
            scriptFile.setReadable(true, false)
            scriptFile.setExecutable(true, false)
            scriptFile.setWritable(true, true)

            val launchCommand = "sh ${shellQuote(scriptFile.absolutePath)} >/dev/null 2>&1 &"
            val process = ProcessBuilder("su", "-c", launchCommand)
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(5, TimeUnit.SECONDS)
            val output = runCatching {
                process.inputStream.bufferedReader().use { it.readText().trim() }
            }.getOrDefault("")
            if (finished && process.exitValue() != 0) {
                error(output.ifBlank { "Root shell exited with code ${process.exitValue()}" })
            }

            RootedLiveViewCaptureSession(
                transportFolderName = transportFolderName,
                folder = captureFolder,
                pcapFile = pcapFile,
                logcatFile = logcatFile,
                metadataFile = metadataFile
            )
        }
    }

    private fun buildScript(
        host: String,
        relayHost: String,
        pcapFile: File,
        logcatFile: File,
        metadataFile: File,
        appPid: Int
    ): String {
        val cameraHost = shellQuote(host)
        val relayHostQuoted = shellQuote(relayHost)
        val pcapPath = shellQuote(pcapFile.absolutePath)
        val logcatPath = shellQuote(logcatFile.absolutePath)
        val metadataPath = shellQuote(metadataFile.absolutePath)
        return """
            #!/system/bin/sh
            CAMERA_HOST=$cameraHost
            RELAY_HOST=$relayHostQuoted
            PCAP_FILE=$pcapPath
            LOGCAT_FILE=$logcatPath
            META_FILE=$metadataPath
            APP_PID=$appPid

            TCPDUMP_BIN=""
            for candidate in "${'$'}(command -v tcpdump 2>/dev/null)" /system/bin/tcpdump /system/xbin/tcpdump /data/local/tmp/tcpdump /data/adb/modules/tcpdump/tcpdump; do
              if [ -n "${'$'}candidate" ] && [ -x "${'$'}candidate" ]; then
                TCPDUMP_BIN="${'$'}candidate"
                break
              fi
            done

            if [ -z "${'$'}TCPDUMP_BIN" ]; then
              echo "tcpdump not found in rooted shell" >> "${'$'}META_FILE"
              exit 1
            fi

            IFACE="${'$'}(ip route get "${'$'}CAMERA_HOST" 2>/dev/null | sed -n 's/.* dev \([^ ]*\).*/\1/p' | head -n 1)"
            if [ -z "${'$'}IFACE" ]; then
              IFACE="any"
            fi

            echo "Interface: ${'$'}IFACE" >> "${'$'}META_FILE"
            echo "tcpdump: ${'$'}TCPDUMP_BIN" >> "${'$'}META_FILE"
            echo "Capture running..." >> "${'$'}META_FILE"

            logcat --pid="${'$'}APP_PID" -v time > "${'$'}LOGCAT_FILE" 2>&1 &
            LOGCAT_PID=${'$'}!

            "${'$'}TCPDUMP_BIN" -i "${'$'}IFACE" -n -s 0 -U -w "${'$'}PCAP_FILE" "host ${'$'}CAMERA_HOST or host ${'$'}RELAY_HOST" >/dev/null 2>&1 &
            TCPDUMP_PID=${'$'}!

            sleep $CAPTURE_DURATION_SECONDS

            kill -2 "${'$'}TCPDUMP_PID" 2>/dev/null
            kill "${'$'}LOGCAT_PID" 2>/dev/null
            wait "${'$'}TCPDUMP_PID" 2>/dev/null
            wait "${'$'}LOGCAT_PID" 2>/dev/null

            echo "Finished: ${'$'}(date '+%Y-%m-%d %H:%M:%S')" >> "${'$'}META_FILE"
            echo "PCAP bytes: ${'$'}(wc -c < "${'$'}PCAP_FILE" 2>/dev/null || echo 0)" >> "${'$'}META_FILE"
            echo "Logcat bytes: ${'$'}(wc -c < "${'$'}LOGCAT_FILE" 2>/dev/null || echo 0)" >> "${'$'}META_FILE"
        """.trimIndent()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
