package com.example.xirolite

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExportHelper(private val context: Context) {

    fun exportProbeResults(content: String, prefix: String = "xiro_probe_results"): Result<File> {
        return try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: return Result.failure(IllegalStateException("External documents directory unavailable"))

            if (!dir.exists()) {
                dir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safePrefix = prefix.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_').ifBlank { "xiro_probe_results" }
            val file = File(dir, "${safePrefix}_$timestamp.txt")
            file.writeText(content)

            Result.success(file)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

