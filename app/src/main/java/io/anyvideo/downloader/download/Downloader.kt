package io.anyvideo.downloader.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import io.anyvideo.downloader.data.MediaCandidate
import io.anyvideo.downloader.data.MimeTypes
import io.anyvideo.downloader.util.Prefs
import org.json.JSONObject
import java.io.File

object Downloader {

    private const val TAG = "Downloader"

    const val IMAGE_FOLDER = "Pictures/anyvideo"
    const val VIDEO_FOLDER = "Movies/anyvideo"
    const val AUDIO_FOLDER = "Music/anyvideo"

    /** One extension table for the whole app: the MIME type already knows what audio is. */
    fun isAudio(fileName: String): Boolean =
        MimeTypes.of(fileName)?.startsWith("audio/") == true

    /**
     * Audio gets its own folder so music players, which scan Music/, can actually see it.
     * Videos stay in Movies and pictures in Pictures.
     */
    private fun standardDirectory(candidate: MediaCandidate): String = when {
        candidate.isVideo -> Environment.DIRECTORY_MOVIES
        isAudio(candidate.fileName) -> Environment.DIRECTORY_MUSIC
        else -> Environment.DIRECTORY_PICTURES
    }

    fun folderFor(candidate: MediaCandidate): String =
        "${standardDirectory(candidate)}/anyvideo"

    /** Row label for a candidate: Video, Audio or Photo. */
    fun kindLabel(fileName: String, isVideo: Boolean): String = when {
        isVideo -> "Video"
        isAudio(fileName) -> "Audio"
        else -> "Photo"
    }

    /**
     * DownloadManager only accepts a standard directory name here; the folder and file
     * name go in the relative sub path, so "Movies/anyvideo" as a directory is rejected
     * with "Not one of standard directories".
     */
    private fun destinationSubPath(fileName: String): String = "anyvideo/$fileName"

    /**
     * Enqueues one media item and records it so the completion receiver can retry failures.
     * [retried] marks an automatic second attempt after an empty transfer, so it cannot loop.
     */
    fun enqueue(context: Context, candidate: MediaCandidate, retried: Boolean = false): Long? {
        return try {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

            // A carousel enqueues every item at once and DownloadManager creates the
            // destination folder itself, so two requests race on mkdir and one fails with
            // "Failed to create parent". Creating it here first removes the race.
            val directory = standardDirectory(candidate)
            val folder = File(
                Environment.getExternalStoragePublicDirectory(directory),
                "anyvideo",
            )
            if (!folder.exists()) folder.mkdirs()

            val request = DownloadManager.Request(Uri.parse(candidate.url))
                .setMimeType(MimeTypes.of(candidate.fileName))
                .setTitle(candidate.fileName)
                .setDescription("AnyVideo")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setNotificationVisibility(
                    if (Prefs.showDownloadNotifications) {
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    } else {
                        DownloadManager.Request.VISIBILITY_HIDDEN
                    },
                )
                .setDestinationInExternalPublicDir(
                    directory,
                    destinationSubPath(candidate.fileName),
                )

            candidate.headers.forEach { (key, value) -> request.addRequestHeader(key, value) }
            val id = manager.enqueue(request)
            DownloadRegistry.remember(context, id, candidate, retried)
            id
        } catch (t: Throwable) {
            Log.w(TAG, "Could not enqueue ${candidate.url}", t)
            null
        }
    }

    fun enqueueAll(context: Context, candidates: List<MediaCandidate>): Int =
        candidates.count { enqueue(context, it) != null }
}

/** Tracks the media behind each DownloadManager id, since requests carry no custom extras. */
object DownloadRegistry {
    private const val KEY_PREFIX = "download_"
    private const val MAX_ENTRIES = 300

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("download_registry", Context.MODE_PRIVATE)

    fun remember(context: Context, id: Long, candidate: MediaCandidate, retried: Boolean = false) {
        val json = JSONObject()
            .put("url", candidate.url)
            .put("fileName", candidate.fileName)
            .put("isVideo", candidate.isVideo)
            .put("source", candidate.sourceUrl ?: "")
            .put("retried", retried)
        // A signed-in Instagram download needs its session cookie again on an automatic retry,
        // so the headers are kept with the entry rather than rebuilt from scratch.
        if (candidate.headers.isNotEmpty()) json.put("headers", JSONObject(candidate.headers as Map<*, *>))
        val store = prefs(context)
        val editor = store.edit().putString("$KEY_PREFIX$id", json.toString())
        // Entries are only removed on a successful download, so a long-lived install with a few
        // failures would otherwise grow this file without limit. Keep the newest 300.
        val ids = store.all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .mapNotNull { it.removePrefix(KEY_PREFIX).toLongOrNull() }
            .sortedDescending()
        ids.drop(MAX_ENTRIES).forEach { editor.remove("$KEY_PREFIX$it") }
        editor.apply()
    }

    /** Headers remembered with an entry, so a retry sends the same session cookie. */
    fun headers(entry: JSONObject): Map<String, String> {
        val stored = entry.optJSONObject("headers") ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        stored.keys().forEach { key ->
            stored.optString(key).takeIf { it.isNotBlank() }?.let { out[key] = it }
        }
        return out
    }

    fun get(context: Context, id: Long): JSONObject? {
        val raw = prefs(context).getString("$KEY_PREFIX$id", null) ?: return null
        return try {
            JSONObject(raw)
        } catch (t: Throwable) {
            null
        }
    }

    fun markRetried(context: Context, id: Long) {
        val json = get(context, id) ?: return
        json.put("retried", true)
        prefs(context).edit().putString("$KEY_PREFIX$id", json.toString()).apply()
    }

    fun forget(context: Context, id: Long) {
        prefs(context).edit().remove("$KEY_PREFIX$id").apply()
    }

    /**
     * Source pages behind the most recent downloads, newest first. The home screen turns these
     * into its "recent profiles" row, so the list has to survive app restarts.
     */
    fun recentSources(context: Context, limit: Int): List<String> = try {
        prefs(context).all.entries
            .filter { it.key.startsWith(KEY_PREFIX) }
            .sortedByDescending { it.key.removePrefix(KEY_PREFIX).toLongOrNull() ?: 0L }
            .mapNotNull { entry ->
                (entry.value as? String)?.let { raw ->
                    runCatching { JSONObject(raw).optString("source") }.getOrNull()
                }
            }
            .filter { it.isNotBlank() }
            .distinct()
            .take(limit)
    } catch (t: Throwable) {
        emptyList()
    }
}

object DownloadRetry {

    /**
     * Failure fallback from the original app: re-request the media through the
     * configured proxy instead of the direct CDN URL.
     */
    fun retry(context: Context, id: Long, url: String, fileName: String, isVideo: Boolean) {
        val proxy = Prefs.fallbackProxy.trim()
        if (proxy.isEmpty()) return
        val entry = DownloadRegistry.get(context, id) ?: return
        if (entry.optBoolean("retried", false)) return
        if (url.startsWith(proxy)) return

        DownloadRegistry.markRetried(context, id)
        // Headers are deliberately dropped here: the proxy is a third party, and a signed-in
        // Instagram session cookie must never be handed to it.
        val separator = if (proxy.contains('?')) '&' else '?'
        val proxied = "$proxy${separator}isvideo=$isVideo&url=${Uri.encode(url)}"
        Downloader.enqueue(
            context,
            MediaCandidate(
                url = proxied,
                fileName = fileName,
                isVideo = isVideo,
                headers = emptyMap(),
            ),
        )
    }
}
