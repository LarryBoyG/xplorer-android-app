package com.example.xirolite

import com.example.xirolite.data.LegacyCompatibilityCatalog
import com.example.xirolite.data.LegacyDroneProfile
import com.example.xirolite.data.XiroCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

private data class CameraCommandCandidate(
    val label: String,
    val url: String
)

data class CameraActionResult(
    val action: String,
    val url: String,
    val status: String,
    val body: String
)

enum class MediaKind { PHOTO, VIDEO }

enum class CaptureMode { PHOTO, VIDEO }

data class CameraMediaItem(
    val name: String,
    val kind: MediaKind,
    val previewUrl: String?,
    val downloadUrl: String,
    val detail: String = "",
    val source: String = ""
)

data class CameraLibraryState(
    val items: List<CameraMediaItem>,
    val rawResponses: List<CameraActionResult>,
    val lastRefreshMessage: String = ""
)

data class CameraOperationResult(
    val title: String,
    val success: Boolean,
    val entries: List<CameraActionResult>,
    val summary: String
)

data class CameraPhotoCaptureResult(
    val operation: CameraOperationResult,
    val libraryState: CameraLibraryState?,
    val capturedItem: CameraMediaItem?
)

data class CameraRecoveryResult(
    val operation: CameraOperationResult,
    val recovered: Boolean
)

enum class DebugCaptureCommand(val title: String) {
    PHOTO_TRIGGER_ONLY("Debug Photo Trigger"),
    PHOTO_MODE_THEN_TRIGGER("Debug Photo Mode Then Trigger"),
    VIDEO_START_ONLY("Debug Video Start"),
    VIDEO_STOP_ONLY("Debug Video Stop")
}

class CameraMediaController(
    private val profile: LegacyDroneProfile = LegacyCompatibilityCatalog.defaultProfile
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(3000, TimeUnit.MILLISECONDS)
        .build()

    private val commandSet = profile.cameraCommands ?: LegacyCompatibilityCatalog.defaultProfile.cameraCommands!!

    suspend fun syncClock(host: String): CameraOperationResult = withContext(Dispatchers.IO) {
        val now = Date()
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getDefault() }
        val entries = listOf(
            fetch("Clock Sync", "http://$host/?custom=1&cmd=3005&str=${URLEncoder.encode(dateFmt.format(now), "UTF-8")}"),
            fetch("Clock Sync", "http://$host/?custom=1&cmd=3006&str=${URLEncoder.encode(timeFmt.format(now), "UTF-8")}")
        )
        toOperationResult("Clock Sync", entries)
    }

    suspend fun setMode(host: String, mode: CaptureMode): CameraOperationResult = withContext(Dispatchers.IO) {
        val entries = mutableListOf<CameraActionResult>()
        val candidate = when (mode) {
            CaptureMode.VIDEO -> CameraCommandCandidate("Set video mode", commandUrl(host, commandSet.setVideoModePath))
            CaptureMode.PHOTO -> CameraCommandCandidate("Set photo mode", commandUrl(host, commandSet.setPhotoModePath))
        }
        entries += fetch("Set ${mode.name.lowercase(Locale.US).replaceFirstChar(Char::titlecase)} Mode - ${candidate.label}", candidate.url)
        toOperationResult("Set ${mode.name.lowercase(Locale.US).replaceFirstChar(Char::titlecase)} Mode", entries)
    }

    suspend fun triggerPrimaryAction(host: String, mode: CaptureMode, currentlyRecording: Boolean): CameraOperationResult = withContext(Dispatchers.IO) {
        val entries = mutableListOf<CameraActionResult>()
        val title: String
        val candidate: CameraCommandCandidate
        when (mode) {
            CaptureMode.PHOTO -> {
                title = "Take Photo"
                candidate = CameraCommandCandidate("Photo cmd 1001", commandUrl(host, commandSet.takePhotoPath))
            }

            CaptureMode.VIDEO -> {
                if (currentlyRecording) {
                    title = "Stop Video"
                    candidate = CameraCommandCandidate("Stop cmd 2001 par=0", commandUrl(host, commandSet.stopVideoPath))
                } else {
                    title = "Start Video"
                    candidate = CameraCommandCandidate("Start cmd 2001 par=1", commandUrl(host, commandSet.startVideoPath))
                }
            }
        }
        entries += fetch("$title - ${candidate.label}", candidate.url)
        toOperationResult(title, entries)
    }

    suspend fun capturePhoto(host: String): CameraPhotoCaptureResult = withContext(Dispatchers.IO) {
        val entries = mutableListOf<CameraActionResult>()
        entries += fetch("Take Photo - Photo cmd 1001", commandUrl(host, commandSet.takePhotoPath))

        val baseResult = toOperationResult("Take Photo", entries)
        val summary = buildString {
            append(baseResult.summary)
            append(" - using safe trigger-only photo path; local preview capture recommended")
        }
        CameraPhotoCaptureResult(
            operation = baseResult.copy(summary = summary),
            libraryState = null,
            capturedItem = null
        )
    }

    suspend fun capturePhotoPrimed(host: String, delayMs: Long = 180): CameraPhotoCaptureResult = withContext(Dispatchers.IO) {
        val entries = mutableListOf<CameraActionResult>()
        entries += fetch("Set Photo Mode - Photo cmd 3001 par=0", commandUrl(host, commandSet.setPhotoModePath))
        if (delayMs > 0) {
            Thread.sleep(delayMs)
        }
        entries += fetch("Take Photo - Photo cmd 1001", commandUrl(host, commandSet.takePhotoPath))

        val baseResult = toOperationResult("Take Photo", entries)
        val summary = buildString {
            append(baseResult.summary)
            append(" - using primed photo-mode sequence (3001 par=0 -> 1001)")
        }
        CameraPhotoCaptureResult(
            operation = baseResult.copy(summary = summary),
            libraryState = null,
            capturedItem = null
        )
    }

    suspend fun runDebugCaptureCommand(host: String, command: DebugCaptureCommand): CameraOperationResult = withContext(Dispatchers.IO) {
        val entries = mutableListOf<CameraActionResult>()
        val title = command.title
        when (command) {
            DebugCaptureCommand.PHOTO_TRIGGER_ONLY -> {
                entries += fetch("Take Photo - Photo cmd 1001", commandUrl(host, commandSet.takePhotoPath))
            }

            DebugCaptureCommand.PHOTO_MODE_THEN_TRIGGER -> {
                entries += fetch("Set Photo Mode - Photo cmd 3001 par=0", commandUrl(host, commandSet.setPhotoModePath))
                Thread.sleep(180)
                entries += fetch("Take Photo - Photo cmd 1001", commandUrl(host, commandSet.takePhotoPath))
            }

            DebugCaptureCommand.VIDEO_START_ONLY -> {
                entries += fetch("Start Video - Start cmd 2001 par=1", commandUrl(host, commandSet.startVideoPath))
            }

            DebugCaptureCommand.VIDEO_STOP_ONLY -> {
                entries += fetch("Stop Video - Stop cmd 2001 par=0", commandUrl(host, commandSet.stopVideoPath))
            }
        }
        toOperationResult(title, entries)
    }

    suspend fun runCatalogCommand(host: String, command: XiroCommand): CameraOperationResult = withContext(Dispatchers.IO) {
        val url = commandUrl(host, command.path)
        val entry = fetch("${command.name} - ${command.path}", url)
        toOperationResult(command.name, listOf(entry)).copy(
            summary = buildString {
                append(if (looksSuccessful(entry)) "Success candidate seen" else "No confirmed success yet")
                append(" - ${entry.status}")
                if (command.notes.isNotBlank()) {
                    append(" - ${command.notes}")
                }
            }
        )
    }

    suspend fun waitForCameraRecovery(
        host: String,
        maxWaitMs: Long = 20000,
        pollIntervalMs: Long = 1500
    ): CameraRecoveryResult = withContext(Dispatchers.IO) {
        val entries = mutableListOf<CameraActionResult>()
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + maxWaitMs
        do {
            val ping = fetch("Recovery ping", "http://$host/")
            entries += ping
            if (ping.status.startsWith("HTTP")) {
                val elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000.0
                val operation = toOperationResult("Camera Recovery", entries).copy(
                    summary = "Camera host reachable again after ${String.format(Locale.US, "%.1f", elapsedSeconds)}s - ${ping.status}"
                )
                return@withContext CameraRecoveryResult(operation = operation, recovered = true)
            }
            if (System.currentTimeMillis() < deadline) {
                Thread.sleep(pollIntervalMs)
            }
        } while (System.currentTimeMillis() < deadline)

        val lastStatus = entries.lastOrNull()?.status ?: "No response"
        val operation = toOperationResult("Camera Recovery", entries).copy(
            summary = "Camera host still unavailable after ${maxWaitMs / 1000}s - $lastStatus"
        )
        CameraRecoveryResult(operation = operation, recovered = false)
    }

    suspend fun loadLibrary(host: String): CameraLibraryState = withContext(Dispatchers.IO) {
        val rawResponses = mutableListOf<CameraActionResult>()
        val items = linkedMapOf<String, CameraMediaItem>()

        val libraryCandidates = commandSet.libraryPaths.map { path ->
            val label = when {
                "3015" in path -> "Library cmd 3015"
                "3012" in path -> "Status 3012"
                "3014" in path -> "Status 3014"
                "photo" in path.lowercase() -> "Photo dir"
                "movie" in path.lowercase() -> "Movie dir"
                else -> "Library"
            }
            CameraCommandCandidate(label, commandUrl(host, path))
        }

        libraryCandidates.forEach { candidate ->
            val result = fetch(candidate.label, candidate.url)
            rawResponses += result
            parseLibraryResponse(host, result).forEach { item -> items[item.downloadUrl] = item }
        }

        CameraLibraryState(
            items = items.values.sortedByDescending { it.name }.toList(),
            rawResponses = rawResponses,
            lastRefreshMessage = if (items.isEmpty()) "No items discovered from 3015/DCIM scan" else "Discovered ${items.size} remote media candidate(s)"
        )
    }

    private fun toOperationResult(title: String, entries: List<CameraActionResult>): CameraOperationResult {
        val success = entries.any { looksSuccessful(it) }
        val last = entries.lastOrNull()
        val summary = buildString {
            append(if (success) "Success candidate seen" else "No confirmed success yet")
            if (last != null) append(" - ${last.status}")
        }
        return CameraOperationResult(title, success, entries, summary)
    }

    private fun looksSuccessful(result: CameraActionResult): Boolean {
        if (!result.status.startsWith("HTTP 200")) return false
        val lower = result.body.lowercase(Locale.US)
        return lower.contains("<status>0</status>") ||
            lower.contains("<value>0</value>") ||
            lower.contains("\"rval\":0") ||
            lower.contains("success") ||
            lower.contains("ok") ||
            lower.contains("dcim/") ||
            lower.contains(".jpg") ||
            lower.contains(".mov") ||
            lower.contains(".mp4")
    }

    private fun fetch(action: String, url: String): CameraActionResult {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "XIRO-Lite-Beta")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                CameraActionResult(
                    action = action,
                    url = url,
                    status = "HTTP ${response.code}",
                    body = response.body?.string().orEmpty()
                )
            }
        } catch (t: Throwable) {
            CameraActionResult(
                action = action,
                url = url,
                status = t::class.java.simpleName ?: "Error",
                body = t.message.orEmpty()
            )
        }
    }

    private fun parseLibraryResponse(host: String, result: CameraActionResult): List<CameraMediaItem> {
        val body = result.body
        if (body.isBlank()) return emptyList()
        val items = mutableListOf<CameraMediaItem>()

        val jpgRegex = Regex("([A-Za-z0-9_./-]+\\.(?:JPG|JPEG|jpg|jpeg))")
        val videoRegex = Regex("([A-Za-z0-9_./-]+\\.(?:MP4|MOV|AVI|mp4|mov|avi))")
        val fileNameRegex = Regex("<FileName>([^<]+)</FileName>", RegexOption.IGNORE_CASE)
        val filePathRegex = Regex("(/DCIM/(?:PHOTO|MOVIE)/[^\"'<>\\s]+)", RegexOption.IGNORE_CASE)

        fileNameRegex.findAll(body).forEach { match ->
            val name = sanitizePath(match.groupValues[1]) ?: return@forEach
            val kind = if (name.endsWith(".mov", true) || name.endsWith(".mp4", true) || name.endsWith(".avi", true)) MediaKind.VIDEO else MediaKind.PHOTO
            items += buildMediaItem(host, name, kind, result.action)
        }
        filePathRegex.findAll(body).forEach { match ->
            val raw = sanitizePath(match.groupValues[1]) ?: return@forEach
            val kind = if (raw.contains("/MOVIE/", true) || raw.endsWith(".mov", true) || raw.endsWith(".mp4", true)) MediaKind.VIDEO else MediaKind.PHOTO
            items += buildMediaItem(host, raw, kind, result.action)
        }
        jpgRegex.findAll(body).forEach { match ->
            val name = sanitizePath(match.groupValues[1]) ?: return@forEach
            items += buildMediaItem(host, name, MediaKind.PHOTO, result.action)
        }
        videoRegex.findAll(body).forEach { match ->
            val name = sanitizePath(match.groupValues[1]) ?: return@forEach
            items += buildMediaItem(host, name, MediaKind.VIDEO, result.action)
        }

        return items.distinctBy { it.downloadUrl }
    }

    private fun buildMediaItem(host: String, rawName: String, kind: MediaKind, source: String): CameraMediaItem {
        val name = sanitizePath(rawName) ?: rawName.substringAfterLast('/')
        val normalizedPath = when {
            rawName.startsWith("http://") || rawName.startsWith("https://") -> rawName
            rawName.startsWith("/") -> "http://$host$rawName"
            rawName.contains("DCIM/") -> "http://$host/${rawName.trimStart('/')}"
            kind == MediaKind.PHOTO -> "http://$host/DCIM/PHOTO/${name.substringAfterLast('/')}"
            else -> "http://$host/DCIM/MOVIE/${name.substringAfterLast('/')}"
        }
        val previewUrl = when (kind) {
            MediaKind.PHOTO -> normalizedPath
            MediaKind.VIDEO -> null
        }
        return CameraMediaItem(
            name = name.substringAfterLast('/'),
            kind = kind,
            previewUrl = previewUrl,
            downloadUrl = normalizedPath,
            detail = when (kind) {
                MediaKind.PHOTO -> "Direct camera photo preview over Wi-Fi"
                MediaKind.VIDEO -> "Camera SD-card video file"
            },
            source = source
        )
    }

    private fun sanitizePath(value: String): String? {
        val cleaned = value.trim().trim('"', '\'', '>', '<')
        if (cleaned.isBlank()) return null
        return cleaned.removePrefix("./")
    }

    private fun newestCapturedPhoto(beforeNames: Set<String>, items: List<CameraMediaItem>): CameraMediaItem? {
        val photos = items
            .filter { it.kind == MediaKind.PHOTO }
            .sortedByDescending { it.name }
        return photos.firstOrNull { normalizeMediaKey(it.name) !in beforeNames } ?: photos.firstOrNull()
    }

    private fun normalizeMediaKey(name: String): String =
        name.substringAfterLast('/').uppercase(Locale.US)

    private fun commandUrl(host: String, path: String): String {
        return when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith("/") -> "http://$host$path"
            else -> "http://$host/$path"
        }
    }
}



