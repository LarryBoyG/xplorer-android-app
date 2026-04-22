package com.example.xirolite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class LocalLibraryItem(
    val id: String,
    val name: String,
    val kind: MediaKind,
    val previewFile: File?,
    val hdFile: File?,
    val remoteUrl: String? = null,
    val remotePreviewUrl: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val isDownloaded: Boolean get() = hdFile?.exists() == true
}

data class XiroFolders(
    val root: File,
    val preview: File,
    val hd: File,
    val videoThumbnail: File,
    val metadata: File,
    val cropPictureTemp: File,
    val gpuImageFilter: File,
    val upgrade: File
)

class LocalLibraryManager(private val context: Context) {
    companion object {
        private const val TAG = "LocalLibraryManager"
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun resolveRootFolder(): File = File(Environment.getExternalStorageDirectory(), "XIRO")

    private fun resolveAppPrivateRootFolder(): File = File(context.getExternalFilesDir(null), "XIRO")

    private fun buildFolders(root: File): XiroFolders {
        val preview = File(root, "Preview")
        val hd = File(root, "HD")
        val videoThumbnail = File(root, "video_thumbnail")
        val metadata = File(root, "hj")
        val cropPictureTemp = File(root, "crop_picture_temp")
        val gpuImageFilter = File(root, "GPUImageFilter")
        val upgrade = File(root, "upgrade")
        return XiroFolders(root, preview, hd, videoThumbnail, metadata, cropPictureTemp, gpuImageFilter, upgrade)
    }

    private fun ensureFolderTree(folders: XiroFolders): Boolean {
        return listOf(
            folders.root,
            folders.preview,
            folders.hd,
            folders.videoThumbnail,
            folders.metadata,
            folders.cropPictureTemp,
            folders.gpuImageFilter,
            folders.upgrade
        ).all { folder -> folder.exists() || folder.mkdirs() }
    }

    fun ensureFolders(): XiroFolders {
        // Try shared storage first if we have permission or are on older Android
        val sharedRoot = resolveRootFolder()
        val primary = buildFolders(sharedRoot)

        // On Android 10+ (API 29), writing to the root of SD card usually fails without MANAGE_EXTERNAL_STORAGE
        // or using Scoped Storage. We attempt it, but check if we actually succeeded.
        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED && ensureFolderTree(primary)) {
            // Verify write permission by trying to actually open a FileOutputStream.
            // Some devices/versions return true for mkdirs/createNewFile but fail on stream open.
            val testFile = File(primary.metadata, ".permission_test_${System.currentTimeMillis()}")
            try {
                FileOutputStream(testFile).use { it.write(0x00) }
                if (testFile.exists()) {
                    testFile.delete()
                    Log.d(TAG, "Using shared XIRO root: ${primary.root.absolutePath}")
                    return primary
                }
            } catch (e: Exception) {
                Log.w(TAG, "Shared root ${primary.root.absolutePath} exists but is not writable: ${e.message}")
                runCatching { testFile.delete() }
            }
        }

        val fallback = buildFolders(resolveAppPrivateRootFolder())
        ensureFolderTree(fallback)
        Log.w(TAG, "Falling back to app-private XIRO root: ${fallback.root.absolutePath}")
        return fallback
    }

    fun activeRootPath(): String = ensureFolders().root.absolutePath

    fun allKnownRootPaths(): List<String> = listOf(resolveRootFolder(), resolveAppPrivateRootFolder())
        .distinctBy { it.absolutePath }
        .map { it.absolutePath }

    suspend fun scanLocalItems(remoteItems: List<CameraMediaItem> = emptyList()): List<LocalLibraryItem> = withContext(Dispatchers.IO) {
        val folderCandidates = listOf(buildFolders(resolveRootFolder()), buildFolders(resolveAppPrivateRootFolder()))
            .distinctBy { it.root.absolutePath }
        val previewFiles = folderCandidates.flatMap { it.preview.listFiles().orEmpty().asList() }
            .filter { it.isFile }
            .distinctBy { it.absolutePath }
        val hdFiles = folderCandidates.flatMap { it.hd.listFiles().orEmpty().asList() }
            .filter { it.isFile }
            .distinctBy { it.absolutePath }

        val remoteByName = remoteItems.associateBy { it.name.substringAfterLast('/') }
        val previewByBase = previewFiles.associateBy { baseMediaName(it.name) }
        val hdByBase = hdFiles.associateBy { baseMediaName(it.name) }
        val allBaseNames = linkedSetOf<String>().apply {
            addAll(hdByBase.keys)
        }

        return@withContext allBaseNames.map { base ->
            val previewFile = previewByBase[base]
            val hdFile = hdByBase[base]
            val remote = remoteByName.values.firstOrNull { baseMediaName(it.name) == base }
            val kind = when {
                hdFile != null -> kindForName(hdFile.name)
                remote != null -> remote.kind
                previewFile != null -> kindForName(previewFile.name)
                else -> MediaKind.PHOTO
            }
            LocalLibraryItem(
                id = base,
                name = hdFile?.name ?: previewFile?.name ?: remote?.name ?: base,
                kind = kind,
                previewFile = previewFile,
                hdFile = hdFile,
                remoteUrl = remote?.downloadUrl,
                remotePreviewUrl = remote?.previewUrl,
                timestampMs = listOfNotNull(hdFile?.lastModified(), previewFile?.lastModified()).maxOrNull()
                    ?: System.currentTimeMillis()
            )
        }.sortedByDescending { it.timestampMs }
    }

    fun loadCachedPreviewBitmap(item: CameraMediaItem): Bitmap? {
        val file = cachedPreviewFile(item)
        if (!file.exists() || file.length() <= 0L) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    fun cacheRemotePreviewBitmap(item: CameraMediaItem, bitmap: Bitmap): File? {
        return runCatching {
            val outFile = cachedPreviewFile(item)
            val tempFile = File(outFile.parentFile, outFile.name + ".part")
            FileOutputStream(tempFile).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                    error("Failed to encode preview")
                }
            }
            if (outFile.exists()) outFile.delete()
            if (!tempFile.renameTo(outFile)) {
                tempFile.delete()
                error("Failed to store preview cache")
            }
            outFile
        }.getOrNull()
    }

    suspend fun deleteLocalItem(item: LocalLibraryItem): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val targets = listOfNotNull(item.previewFile, item.hdFile)
                .distinctBy { it.absolutePath }
            targets.forEach { file ->
                if (file.exists() && !file.delete()) {
                    error("Failed to delete ${file.name}")
                }
            }
        }
    }

    suspend fun savePhotoPreview(item: CameraMediaItem): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val folders = ensureFolders()
            val url = item.previewUrl ?: item.downloadUrl
            val ext = item.name.substringAfterLast('.', "jpg")
            val outFile = File(folders.preview, "preview_${baseMediaName(item.name)}.$ext")
            downloadToFile(url, outFile, onProgress = null)
        }
    }



    suspend fun saveRtspSnapshotPreview(baseName: String, bitmap: Bitmap): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val folders = ensureFolders()
            val outFile = File(folders.preview, "preview_${baseMediaName(baseName)}.jpg")
            val tempFile = File(outFile.parentFile, outFile.name + ".part")
            FileOutputStream(tempFile).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                    error("Failed to encode RTSP snapshot")
                }
            }
            if (outFile.exists()) outFile.delete()
            tempFile.renameTo(outFile)
            outFile
        }
    }

    suspend fun createVideoPreviewPlaceholder(nameHint: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val folders = ensureFolders()
            val outFile = File(folders.preview, "preview_${baseMediaName(nameHint)}.mp4")
            if (!outFile.exists()) outFile.writeBytes(ByteArray(0))
            outFile
        }
    }

    suspend fun downloadToHd(item: LocalLibraryItem): Result<File> = withContext(Dispatchers.IO) {
        downloadToHd(item, onProgress = null)
    }

    suspend fun downloadToHd(
        item: LocalLibraryItem,
        onProgress: ((Long, Long?) -> Unit)?
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val url = item.remoteUrl ?: error("Remote download URL unavailable")
            val folders = ensureFolders()
            val ext = item.name.substringAfterLast('.', if (item.kind == MediaKind.VIDEO) "mov" else "jpg")
            val outFile = File(folders.hd, "${baseMediaName(item.name)}.$ext")
            downloadToFile(url, outFile, onProgress)
        }
    }

    suspend fun downloadToHd(item: CameraMediaItem): Result<File> = withContext(Dispatchers.IO) {
        downloadToHd(item, onProgress = null)
    }

    suspend fun downloadToHd(
        item: CameraMediaItem,
        onProgress: ((Long, Long?) -> Unit)?
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val folders = ensureFolders()
            val ext = item.name.substringAfterLast('.', if (item.kind == MediaKind.VIDEO) "mov" else "jpg")
            val outFile = File(folders.hd, "${baseMediaName(item.name)}.$ext")
            downloadToFile(item.downloadUrl, outFile, onProgress)
        }
    }

    private fun downloadToFile(
        url: String,
        outFile: File,
        onProgress: ((Long, Long?) -> Unit)?
    ): File {
        val tempFile = File(outFile.parentFile, outFile.name + ".part")
        val request = Request.Builder().url(url).header("User-Agent", "XIRO-Lite-Beta").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("Empty body")
            val totalBytes = body.contentLength().takeIf { it > 0L }
            FileOutputStream(tempFile).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var bytesCopied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        bytesCopied += read
                        onProgress?.invoke(bytesCopied, totalBytes)
                    }
                }
            }
        }
        val bytes = tempFile.readBytes()
        if (looksLikeXmlResponse(bytes)) {
            tempFile.delete()
            error("Rejected XML/control payload instead of media")
        }
        if (!looksLikeSupportedMedia(bytes, outFile.extension)) {
            tempFile.delete()
            error("Rejected unsupported media payload")
        }
        if (outFile.exists()) outFile.delete()
        tempFile.renameTo(outFile)
        return outFile
    }

    private fun looksLikeXmlResponse(bytes: ByteArray): Boolean {
        val head = bytes.take(160).toByteArray().toString(Charsets.UTF_8).trimStart()
        return head.startsWith("<?xml", ignoreCase = true) || head.startsWith("<Function>", ignoreCase = true)
    }

    private fun looksLikeSupportedMedia(bytes: ByteArray, extension: String): Boolean {
        if (bytes.size < 12) return false
        val normalizedExt = extension.lowercase()
        val isJpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val isPng = bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val mediaBox = bytes.copyOfRange(4, minOf(bytes.size, 12)).toString(Charsets.ISO_8859_1)
        val isIsoMedia = mediaBox.contains("ftyp")
        return when (normalizedExt) {
            "jpg", "jpeg" -> isJpeg
            "png" -> isPng
            "mov", "mp4", "avi" -> isIsoMedia
            else -> isJpeg || isPng || isIsoMedia
        }
    }

    private fun kindForName(name: String): MediaKind =
        when {
            name.contains("__video.", true) -> MediaKind.VIDEO
            name.endsWith(".mov", true) || name.endsWith(".mp4", true) || name.endsWith(".avi", true) -> MediaKind.VIDEO
            else -> MediaKind.PHOTO
        }

    private fun baseMediaName(name: String): String = name
        .substringAfterLast('/')
        .removePrefix("preview_")
        .substringBefore("__video")
        .substringBefore("__photo")
        .substringBeforeLast('.')

    private fun cachedPreviewFile(item: CameraMediaItem): File {
        val folders = ensureFolders()
        val suffix = when (item.kind) {
            MediaKind.PHOTO -> "__photo.jpg"
            MediaKind.VIDEO -> "__video.jpg"
        }
        return File(folders.preview, "preview_${baseMediaName(item.name)}$suffix")
    }
}
