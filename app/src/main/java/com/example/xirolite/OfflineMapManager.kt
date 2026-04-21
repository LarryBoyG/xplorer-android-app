package com.example.xirolite

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

private const val OFFLINE_MAP_PREF_KEY = "active_offline_map_name"

data class OfflineMapFile(
    val file: File,
    val isActive: Boolean
) {
    val fileName: String get() = file.name
    val displayName: String get() = file.nameWithoutExtension
    val sizeBytes: Long get() = file.length()
}

class OfflineMapManager(
    private val context: Context,
    private val localLibraryManager: LocalLibraryManager
) {
    companion object {
        const val MAP_DOWNLOAD_URL = "https://download.mapsforge.org/"
        const val MAP_ATTRIBUTION = "Map data \u00A9 OpenStreetMap contributors"
        const val MAP_ENGINE_ATTRIBUTION = "Offline rendering powered by mapsforge"
    }

    private val prefs = context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)

    fun mapsDirectory(): File {
        val root = localLibraryManager.ensureFolders().root
        val mapsDir = File(root, "maps")
        if (!mapsDir.exists()) mapsDir.mkdirs()
        return mapsDir
    }

    fun listInstalledMaps(): List<OfflineMapFile> {
        val activeName = prefs.getString(OFFLINE_MAP_PREF_KEY, null)
        return mapsDirectory()
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("map", ignoreCase = true) }
            .sortedBy { it.name.lowercase(Locale.US) }
            .map { file ->
                OfflineMapFile(
                    file = file,
                    isActive = file.name == activeName
                )
            }
            .let { installed ->
                if (installed.any { it.isActive }) {
                    installed
                } else if (installed.isNotEmpty()) {
                    val first = installed.first()
                    setActiveMap(first.fileName)
                    installed.map { it.copy(isActive = it.file == first.file) }
                } else {
                    installed
                }
            }
    }

    fun activeMapFile(): OfflineMapFile? = listInstalledMaps().firstOrNull { it.isActive }

    fun setActiveMap(fileName: String?) {
        prefs.edit().putString(OFFLINE_MAP_PREF_KEY, fileName).apply()
    }

    suspend fun importMap(uri: Uri): Result<OfflineMapFile> = withContext(Dispatchers.IO) {
        runCatching {
            val mapsDir = mapsDirectory()
            val displayName = resolveDisplayName(context.contentResolver, uri)
            val sanitizedName = sanitizeMapFileName(displayName)
            val targetFile = File(mapsDir, sanitizedName)
            val tempFile = File(mapsDir, "$sanitizedName.part")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: error("Unable to read selected file")
            if (tempFile.length() <= 0L) {
                tempFile.delete()
                error("Imported map file was empty")
            }
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.delete()
                error("Failed to import offline map")
            }
            if (prefs.getString(OFFLINE_MAP_PREF_KEY, null).isNullOrBlank()) {
                setActiveMap(targetFile.name)
            }
            activeMapFile()
                ?.takeIf { it.fileName == targetFile.name }
                ?: OfflineMapFile(targetFile, isActive = prefs.getString(OFFLINE_MAP_PREF_KEY, null) == targetFile.name)
        }
    }

    fun deleteMap(fileName: String): Result<Unit> = runCatching {
        val file = File(mapsDirectory(), fileName)
        if (file.exists() && !file.delete()) {
            error("Failed to delete ${file.name}")
        }
        if (prefs.getString(OFFLINE_MAP_PREF_KEY, null) == fileName) {
            val next = mapsDirectory()
                .listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension.equals("map", ignoreCase = true) }
                .sortedBy { it.name.lowercase(Locale.US) }
                .firstOrNull()
            setActiveMap(next?.name)
        }
    }

    private fun resolveDisplayName(contentResolver: ContentResolver, uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (columnIndex >= 0 && cursor.moveToFirst()) {
                val name = cursor.getString(columnIndex)
                if (!name.isNullOrBlank()) return name
            }
        }
        return uri.lastPathSegment ?: "offline_region.map"
    }

    private fun sanitizeMapFileName(rawName: String): String {
        val base = rawName
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "offline_region.map" }
        return if (base.lowercase(Locale.US).endsWith(".map")) base else "$base.map"
    }
}
