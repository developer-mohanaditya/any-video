package io.anyvideo.downloader.net

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Runs a page's own decoder inside a detached WebView and harvests the media
 * URLs it prints to `console.log`.
 *
 * The original app replaces `decodeURIComponent(escape(r))` with a logging
 * variant, loads the rewritten document in an invisible WebView, and collects
 * URLs from `WebChromeClient.onConsoleMessage`. That is reproduced here.
 */
class WebViewExtractor(private val activity: Activity) {

    suspend fun decode(html: String): List<String> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            val found = LinkedHashSet<String>()
            var webView: WebView? = null
            // The WebView is never attached to a window, and View.postDelayed() only
            // queues until attach in that case, so the timeouts need their own handler.
            val handler = Handler(Looper.getMainLooper())

            fun finish() {
                if (!finished.compareAndSet(false, true)) return
                handler.removeCallbacksAndMessages(null)
                webView?.destroy()
                webView = null
                if (continuation.isActive) continuation.resume(found.toList())
            }

            try {
                val view = WebView(activity)
                webView = view
                with(view.settings) {
                    javaScriptEnabled = true
                    userAgentString = Http.DESKTOP_UA
                    domStorageEnabled = true
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                }
                view.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        collectUrls(message.message()).forEach { found += it }
                        return true
                    }

                    override fun onConsoleMessage(message: String?, lineNumber: Int, sourceID: String?) {
                        message?.let { collectUrls(it).forEach { url -> found += url } }
                    }
                }
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        handler.postDelayed({ finish() }, 1500)
                    }
                }
                view.loadDataWithBaseURL("https://snapsave.app/", html, "text/html", "utf-8", null)
                handler.postDelayed({ finish() }, 12_000)
            } catch (t: Throwable) {
                finish()
            }
        }
    }

    private fun collectUrls(message: String): List<String> =
        Regex("""https?://[^\s"')]+ """.trim()).findAll(message).map { it.value }.toList()
}
