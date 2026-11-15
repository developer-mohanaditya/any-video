package io.anyvideo.downloader.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.anyvideo.downloader.data.FileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Trims local media without re-encoding. The range is copied sample by sample into a new
 * container, so a clip keeps the original quality and never touches the source file.
 */
object MediaTrimmer {

    enum class AudioTarget(val folder: String) {
        RINGTONE("Ringtones/anyvideo"),
        NOTIFICATION("Notifications/anyvideo"),
        ALARM("Alarms/anyvideo"),
    }

    suspend fun trimVideo(
        context: Context,
        source: Uri,
        startMs: Long,
        endMs: Long,
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching { trim(context, source, startMs, endMs, video = true, target = null) }
    }

    suspend fun trimAudio(
        context: Context,
        source: Uri,
        startMs: Long,
        endMs: Long,
        target: AudioTarget,
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching { trim(context, source, startMs, endMs, video = false, target = target) }
    }

    private fun trim(
        context: Context,
        source: Uri,
        startMs: Long,
        endMs: Long,
        video: Boolean,
        target: AudioTarget?,
    ): Uri {
        require(endMs > startMs) { "The selected range is empty" }
        val startUs = startMs.coerceAtLeast(0L) * 1000L
        val endUs = endMs.coerceAtLeast(startMs + 1L) * 1000L
        val extension = if (video) "mp4" else "m4a"
        val temp = File(context.cacheDir, "trim-${System.nanoTime()}.$extension")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, source, null)
            val videoIndex = if (video) {
                (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("video/") == true
                }
            } else {
                null
            }
            val audioIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }
            if (video && videoIndex == null) error("This file has no video track")
            if (!video && audioIndex == null) error("This file has no audio track")

            val tracks = listOfNotNull(videoIndex, audioIndex)
            val output = MediaMuxer(temp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = output
            val outputTrack = HashMap<Int, Int>()
            tracks.forEach { index ->
                val format = extractor.getTrackFormat(index)
                outputTrack[index] = output.addTrack(format)
            }
            output.start()
            tracks.forEach { index ->
                extractor.selectTrack(index)
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val format = extractor.getTrackFormat(index)
                val maxInput = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                } else {
                    2 * 1024 * 1024
                }
                val buffer = ByteBuffer.allocateDirect(maxInput.coerceAtLeast(2 * 1024 * 1024))
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0 || extractor.sampleTime >= endUs) break
                    val time = extractor.sampleTime
                    if (time >= startUs) {
                        // MediaExtractor sample flags are not MediaCodec buffer flags. The muxer
                        // only needs the key-frame bit; partial-frame flags are not valid here.
                        val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else {
                            0
                        }
                        info.set(0, size, time - startUs, flags)
                        output.writeSampleData(outputTrack.getValue(index), buffer, info)
                    }
                    extractor.advance()
                }
            }
            output.stop()
            output.release()
            muxer = null
            if (temp.length() <= 0L) error("The selected range produced no media")
            return publish(context, temp, source, video, target)
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
            temp.delete()
        }
    }

    private fun publish(
        context: Context,
        temp: File,
        source: Uri,
        video: Boolean,
        target: AudioTarget?,
    ): Uri {
        val sourceName = displayName(context, source)
        val base = FileNames.noEmDash(sourceName.substringBeforeLast('.').ifBlank { "clip" })
            .replace(Regex("""[\\/:*?"<>|\r\n\t]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(80)
            .ifBlank { "clip" }
        val suffix = if (video) "clip" else "tone"
        val extension = if (video) "mp4" else "m4a"
        val name = "$base-$suffix.$extension"
        val mime = if (video) "video/mp4" else "audio/mp4"
        val relative = if (video) "Movies/anyvideo" else target?.folder ?: "Music/anyvideo"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (video) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                if (!video && target != null) {
                    when (target) {
                        AudioTarget.RINGTONE -> put(MediaStore.Audio.Media.IS_RINGTONE, 1)
                        AudioTarget.NOTIFICATION -> put(MediaStore.Audio.Media.IS_NOTIFICATION, 1)
                        AudioTarget.ALARM -> put(MediaStore.Audio.Media.IS_ALARM, 1)
                    }
                }
            }
            val uri = context.contentResolver.insert(collection, values)
                ?: error("Could not create the output file")
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    temp.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Could not write the output file")
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                return uri
            } catch (t: Throwable) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw t
            }
        }

        val standard = when {
            video -> Environment.DIRECTORY_MOVIES
            target == AudioTarget.RINGTONE -> Environment.DIRECTORY_RINGTONES
            target == AudioTarget.NOTIFICATION -> Environment.DIRECTORY_NOTIFICATIONS
            target == AudioTarget.ALARM -> Environment.DIRECTORY_ALARMS
            else -> Environment.DIRECTORY_MUSIC
        }
        val folder = File(
            Environment.getExternalStoragePublicDirectory(standard),
            relative.substringAfter('/', "anyvideo"),
        ).apply { mkdirs() }
        val output = File(folder, name)
        temp.inputStream().use { input -> output.outputStream().use { input.copyTo(it) } }
        MediaScannerConnection.scanFile(context, arrayOf(output.absolutePath), arrayOf(mime), null)
        return Uri.fromFile(output)
    }

    private fun displayName(context: Context, uri: Uri): String {
        return context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "clip"
    }
}
