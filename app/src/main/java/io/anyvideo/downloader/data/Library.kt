package io.anyvideo.downloader.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

enum class MediaKind { PHOTO, VIDEO, AUDIO }

data class SavedMedia(
    val uri: Uri,
    val name: String,
    val kind: MediaKind,
    val size: Long,
    val dateAdded: Long,
    val path: String?,
)

/** Reads the Photos/Videos/Audio tabs of the downloads screen straight from MediaStore. */
object Library {

    fun load(context: Context, kind: MediaKind): List<SavedMedia> {
        val collection = when (kind) {
            MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            MediaKind.PHOTO -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(MediaStore.MediaColumns.DATA)
        }.toTypedArray()

        val folder = when (kind) {
            MediaKind.VIDEO -> DownloadPaths.VIDEO_RELATIVE
            MediaKind.AUDIO -> DownloadPaths.AUDIO_RELATIVE
            MediaKind.PHOTO -> DownloadPaths.IMAGE_RELATIVE
        }
        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" to arrayOf("$folder%")
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ?" to arrayOf("%$folder/%")
        }

        val items = ArrayList<SavedMedia>()
        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val dataColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val size = cursor.getLong(sizeColumn)
                    // DownloadManager can leave an empty MediaStore row behind; don't list those.
                    if (size <= 0L) continue
                    val path = if (dataColumn >= 0) cursor.getString(dataColumn) else null
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    // A row can outlive its file (deleted from another app or from a shell), and
                    // such a ghost tile only fails when tapped. Only list what can be opened.
                    if (!canOpen(context, uri)) continue
                    items += SavedMedia(
                        uri = uri,
                        name = cursor.getString(nameColumn) ?: "file",
                        kind = kind,
                        size = size,
                        dateAdded = cursor.getLong(dateColumn) * 1000L,
                        path = path,
                    )
                }
            }
        } catch (t: Throwable) {
            return emptyList()
        }
        return items
    }

    /** True when a file with this name is already in the shared folder. */
    fun exists(context: Context, relativePath: String, fileName: String): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val collection = when {
                relativePath.startsWith("Movies") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                relativePath.startsWith("Music") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            return runCatching {
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
                    selection,
                    arrayOf("$relativePath%", fileName),
                    null,
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    var found = false
                    while (cursor.moveToNext()) {
                        val uri = Uri.withAppendedPath(collection, cursor.getLong(idColumn).toString())
                        val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
                        if (size <= 0L) {
                            // A zero-byte row blocks a fresh insert and shows as an empty tile.
                            runCatching { context.contentResolver.delete(uri, null, null) }
                            continue
                        }
                        // A row can outlive its file, and a stale row must not make the app think
                        // this download already happened. Only count a row that can be opened.
                        if (canOpen(context, uri)) {
                            found = true
                        } else {
                            // A row with no file behind it blocks a fresh insert with a UNIQUE
                            // error on _data and shows as a broken tile. Drop it.
                            runCatching { context.contentResolver.delete(uri, null, null) }
                        }
                    }
                    found
                } ?: false
            }.getOrDefault(false)
        }
        val standard = relativePath.substringBefore('/')
        val folder = File(
            android.os.Environment.getExternalStoragePublicDirectory(standard),
            relativePath.substringAfter('/', "anyvideo"),
        )
        val file = File(folder, fileName)
        if (file.exists() && file.length() <= 0L) file.delete()
        return file.exists() && file.length() > 0L
    }

    private fun canOpen(context: Context, uri: Uri): Boolean = runCatching {
        // `length` is -1 (unknown) for some providers, which still means the file is there.
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length != 0L } ?: false
    }.getOrDefault(false)

    fun delete(context: Context, media: SavedMedia): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.delete(media.uri, null, null) > 0
        } else {
            media.path?.let { File(it).delete() } ?: false
        }
    } catch (t: Throwable) {
        false
    }
}

object DownloadPaths {
    const val IMAGE_RELATIVE = "Pictures/anyvideo"
    const val VIDEO_RELATIVE = "Movies/anyvideo"
    const val AUDIO_RELATIVE = "Music/anyvideo"
}
