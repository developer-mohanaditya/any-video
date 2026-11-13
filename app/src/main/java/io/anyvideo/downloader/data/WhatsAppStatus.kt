package io.anyvideo.downloader.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

data class StatusFile(
    val file: File,
    val isVideo: Boolean,
) {
    val name: String get() = file.name
}

/**
 * Reads the WhatsApp status folders. The original app lists `.Statuses`
 * directly and copies the selected item into `Pictures/anyvideo` or
 * `Movies/anyvideo` through MediaStore.
 */
object WhatsAppStatus {

    private val videoExtensions = setOf("mp4", "3gp", "mkv", "webm")

    fun directories(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        val candidates = listOf(
            File(root, "WhatsApp/Media/.Statuses"),
            File(root, "WhatsApp Business/Media/.Statuses"),
            File(root, "Android/media/com.whatsapp/WhatsApp/Media/.Statuses"),
            File(root, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses"),
        )
        return candidates.filter { it.isDirectory && it.canRead() }
    }

    fun scan(): List<StatusFile> {
        val files = ArrayList<StatusFile>()
        directories().forEach { dir ->
            dir.listFiles()?.forEach { file ->
                if (!file.isFile || file.length() == 0L) return@forEach
                val extension = file.extension.lowercase()
                val isVideo = extension in videoExtensions
                if (isVideo || extension in setOf("jpg", "jpeg", "png", "webp")) {
                    files += StatusFile(file, isVideo)
                }
            }
        }
        return files.sortedByDescending { it.file.lastModified() }
    }

    fun save(context: Context, status: StatusFile): Uri? = try {
        val mime = if (status.isVideo) "video/mp4" else "image/jpeg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relative = if (status.isVideo) "Movies/anyvideo" else "Pictures/anyvideo"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "WA-${status.file.name}")
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (status.isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val uri = context.contentResolver.insert(collection, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { output ->
                status.file.inputStream().use { input -> input.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            uri
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(
                    if (status.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES,
                ),
                "anyvideo",
            ).apply { mkdirs() }
            val target = File(dir, "WA-${status.file.name}")
            status.file.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            android.media.MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
            Uri.fromFile(target)
        }
    } catch (t: Throwable) {
        null
    }

    fun delete(status: StatusFile): Boolean = try {
        status.file.delete()
    } catch (t: Throwable) {
        false
    }
}
