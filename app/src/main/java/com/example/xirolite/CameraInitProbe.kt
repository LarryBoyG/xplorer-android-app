package com.example.xirolite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class CameraInitResult(
    val label: String,
    val url: String,
    val status: String,
    val preview: String
)

class CameraInitProbe {

    suspend fun run(host: String): List<CameraInitResult> = withContext(Dispatchers.IO) {
        listOf(
            httpGet(host, "cmd=2009", "CMD 2009"),
            httpGet(host, "cmd=2031&par=2", "CMD 2031 par=2"),
            httpGet(host, "cmd=1003", "CMD 1003"),
            httpGet(host, "cmd=3012", "CMD 3012")
        )
    }

    private fun httpGet(host: String, query: String, label: String): CameraInitResult {
        val urlString = "http://$host/?custom=1&$query"

        return try {
            val conn = URL(urlString).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.setRequestProperty("User-Agent", "XIRO-Lite")
            conn.setRequestProperty("Connection", "close")

            val code = conn.responseCode
            val body = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                if (stream != null) {
                    BufferedReader(InputStreamReader(stream)).use { reader ->
                        reader.readText()
                    }
                } else {
                    ""
                }
            } catch (_: Exception) {
                ""
            }

            CameraInitResult(
                label = label,
                url = urlString,
                status = "HTTP $code",
                preview = body.ifBlank { "(empty response)" }
            )
        } catch (e: Exception) {
            CameraInitResult(
                label = label,
                url = urlString,
                status = e.javaClass.simpleName,
                preview = e.message ?: "No detail"
            )
        }
    }
}

