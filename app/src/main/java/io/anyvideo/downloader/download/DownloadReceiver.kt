package io.anyvideo.downloader.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.widget.Toast
import io.anyvideo.downloader.data.MediaCandidate
import io.anyvideo.downloader.data.MimeTypes
import io.anyvideo.downloader.net.Resolver
import io.anyvideo.downloader.util.Prefs
import org.json.JSONObject
import java.io.File

/**
 * Completion hook: verifies the file, scans it into the gallery and retries failures through
 * the proxy.
 *
 * DownloadManager reports `STATUS_SUCCESSFUL` for transfers that ended without an error even
 * when nothing usable arrived (an interrupted tunnel, a 200 with no body). Left alone that is
 * the "0 KB corrupted file" the user sees in the gallery, so every success is checked for a
 * real byte count before it is indexed.
 */
class DownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id == -1L) return

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
        val entry = DownloadRegistry.get(context, id) ?: return

        var status = DownloadManager.STATUS_FAILED
        var localUri: String? = null
        try {
            manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val local = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    localUri = local
                }
            }
        } catch (t: Throwable) {
            return
        }

        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            val path = localUri?.removePrefix("file://")
            val size = path?.let { File(it).length() } ?: 0L
            if (path != null && size > 0L) {
                if (isErrorPage(path)) {
                    // A CDN refusing a private file can answer 200 with a login or error page.
                    // Saving that would put a broken tile in the gallery, so it is dropped here.
                    runCatching { File(path).delete() }
                    DownloadRegistry.forget(context, id)
                    toast(context, "The server sent a web page instead of the file. Sign in again and retry")
                    return
                }
                val mimes = MimeTypes.of(entry.optString("fileName"))?.let { arrayOf(it) }
                MediaScannerConnection.scanFile(context, arrayOf(path), mimes, null)
                DownloadRegistry.forget(context, id)
            } else {
                handleEmpty(context, id, entry, path)
            }
        } else if (status == DownloadManager.STATUS_FAILED) {
            // The reply behind this id may hold tunnel URLs that have since expired, so drop the
            // resolve cache: the next attempt asks for a fresh one instead of failing the same way.
            Resolver.invalidate()
            DownloadRetry.retry(
                context = context,
                id = id,
                url = entry.optString("url"),
                fileName = entry.optString("fileName"),
                isVideo = entry.optBoolean("isVideo"),
            )
            // With nothing left to retry with, drop the record instead of keeping a dead id.
            if (Prefs.fallbackProxy.isBlank() || entry.optBoolean("retried", false)) {
                DownloadRegistry.forget(context, id)
                toast(
                    context,
                    if (isInstagram(entry)) {
                        "Instagram refused that file. Reopen the profile and try again"
                    } else {
                        "That download failed. Try the link again"
                    },
                )
            }
        }
    }

    /**
     * A zero-byte download: delete the placeholder immediately, then give the URL one more
     * chance, since these are usually a transient tunnel failure rather than a dead link.
     */
    private fun handleEmpty(context: Context, id: Long, entry: JSONObject, path: String?) {
        path?.let { runCatching { File(it).delete() } }
        DownloadRegistry.forget(context, id)

        val url = entry.optString("url")
        if (entry.optBoolean("retried", false) || url.isBlank()) {
            toast(context, "The file came back empty. Try that link again")
            return
        }

        val again = Downloader.enqueue(
            context,
            MediaCandidate(
                url = url,
                fileName = entry.optString("fileName"),
                isVideo = entry.optBoolean("isVideo"),
                sourceUrl = entry.optString("source").takeIf { it.isNotBlank() },
                // Same URL, so it keeps the session cookie it was queued with.
                headers = DownloadRegistry.headers(entry),
            ),
            // A retry that also comes back empty must not retry forever.
            retried = true,
        )
        toast(
            context,
            if (again != null) "Empty download, retrying once"
            else "The file came back empty. Try that link again",
        )
    }

    /**
     * True when the transfer is a web page rather than media: CDNs answer a rejected private
     * file with an HTML login/error page, which DownloadManager reports as a plain success.
     * Every real media container starts with binary magic bytes, so a leading '<' or '{' is
     * never a valid file.
     */
    private fun isErrorPage(path: String): Boolean {
        return try {
            val head = ByteArray(512)
            val read = File(path).inputStream().use { it.read(head) }
            if (read <= 0) {
                false
            } else {
                val text = String(head, 0, read).trimStart()
                text.startsWith("<") || text.startsWith("{")
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun isInstagram(entry: JSONObject): Boolean {
        val source = entry.optString("source")
        return source.contains("instagram.com", true)
    }

    private fun toast(context: Context, message: String) {
        runCatching { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }
}
