package com.example.xirolite.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class CommandResult(
    val label: String,
    val url: String,
    val status: String,
    val preview: String = ""
)

class XiroRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    suspend fun runProbeSequence(
        host: String,
        profile: LegacyDroneProfile = LegacyCompatibilityCatalog.defaultProfile
    ): List<CommandResult> = withContext(Dispatchers.IO) {
        val candidates = (profile.cameraCommands?.probePaths ?: listOf("/", "/uav.cgi?op=", "/internal/get_tinty_q_vesion.json"))
            .map { path ->
                val label = when {
                    path == "/" -> "Root"
                    "uav.cgi" in path.lowercase() -> "UAV CGI"
                    "version" in path.lowercase() || "amba" in path.lowercase() -> "Version JSON"
                    else -> "Probe"
                }
                Pair(label, "http://$host$path")
            }
        candidates.map { fetch(it.first, it.second) }
    }

    suspend fun listPhotoEndpointCandidates(
        host: String,
        profile: LegacyDroneProfile = LegacyCompatibilityCatalog.defaultProfile
    ): List<CommandResult> = withContext(Dispatchers.IO) {
        val candidates = (profile.cameraCommands?.libraryPaths ?: listOf("/DCIM/PHOTO/", "/DCIM/MOVIE/"))
            .map { path ->
                val label = when {
                    "photo" in path.lowercase() -> "Photos folder"
                    "movie" in path.lowercase() -> "Movies folder"
                    else -> "Library"
                }
                Pair(label, "http://$host$path")
            } + listOf(
            Pair("Pic thumbs", "http://$host/internal/thumbnails/pic/"),
            Pair("Video thumbs", "http://$host/internal/thumbnails/video/")
        )
        candidates.map { fetch(it.first, it.second) }
    }

    private fun fetch(label: String, url: String): CommandResult {
        return try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty().take(500)
                CommandResult(
                    label = label,
                    url = url,
                    status = "${response.code} ${response.message}",
                    preview = body.ifBlank { "<empty body>" }
                )
            }
        } catch (t: Throwable) {
            CommandResult(
                label = label,
                url = url,
                status = t::class.java.simpleName ?: "Error",
                preview = t.message.orEmpty()
            )
        }
    }
}
