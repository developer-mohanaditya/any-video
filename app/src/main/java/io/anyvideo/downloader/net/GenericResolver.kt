package io.anyvideo.downloader.net

import android.app.Activity
import io.anyvideo.downloader.data.FileNames
import io.anyvideo.downloader.data.MediaCandidate
import io.anyvideo.downloader.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fallback resolver for links the direct Instagram path cannot handle
 * (Facebook, TikTok, X, Vimeo, Dailymotion, Pinterest, ...).
 *
 * The original hard-codes `https://snapsave.app/action.php` and rewrites the
 * answered page so its decoder logs instead of writing the DOM. The endpoint is
 * configurable here because it is a third-party service.
 */
object GenericResolver {

    private val blockedHosts = listOf(
        "play.google.com", "applovin", "googlesyndication", "googleadservices",
        "doubleclick", "gstatic.com", "googleapis.com", "unityads", "unity3d.com",
        "applvn.com", "applov.in", "cloudflare.com",
    )

    suspend fun resolve(activity: Activity, pageUrl: String): List<MediaCandidate> {
        val proxy = Prefs.fallbackProxy
        if (proxy.isNotBlank()) {
            val direct = resolveViaProxy(proxy, pageUrl)
            if (direct.isNotEmpty()) return direct
        }

        val body = withContext(Dispatchers.IO) {
            Http.postForm(
                Prefs.extractorEndpoint,
                mapOf("url" to pageUrl),
                mapOf("user-agent" to Http.DESKTOP_UA, "cookie" to "", "referer" to "https://snapsave.app/"),
            )
        } ?: return emptyList()

        val patched = body.replace(
            "decodeURIComponent(escape(r))",
            "console.log('Hello'+decodeURIComponent(escape(r)))",
        )
        val urls = if (patched.contains("console.log")) {
            WebViewExtractor(activity).decode(patched)
        } else {
            // Some extractors answer with a plain page; fall back to reading attributes.
            Regex("""https?://[^\s"'\\)\]]+""").findAll(body).map { it.value }.toList()
        }
        return urls.filter(::isMediaUrl).distinct().mapIndexed { index, url ->
            buildCandidate(url, pageUrl, index)
        }
    }

    private suspend fun resolveViaProxy(endpoint: String, pageUrl: String): List<MediaCandidate> {
        val base = endpoint.trim()
        val separator = if (base.contains('?')) '&' else '?'
        val url = "$base${separator}isvideo=false&url=${java.net.URLEncoder.encode(pageUrl, "UTF-8")}"
        val body = withContext(Dispatchers.IO) { Http.get(url) } ?: return emptyList()
        val trimmed = body.trim()
        if (trimmed.startsWith("http")) {
            return listOf(buildCandidate(trimmed.lines().first(), pageUrl, 0))
        }
        val urls = Regex("""https?://[^\s"'\\)\]]+""").findAll(body).map { it.value }.filter(::isMediaUrl).toList()
        return urls.distinct().mapIndexed { index, media -> buildCandidate(media, pageUrl, index) }
    }

    private fun isMediaUrl(url: String): Boolean {
        if (blockedHosts.any { url.contains(it, true) }) return false
        val isVideo = FileNames.looksLikeVideo(url)
        val isImage = FileNames.looksLikeImage(url)
        return isVideo || isImage
    }

    private fun buildCandidate(url: String, pageUrl: String, index: Int): MediaCandidate {
        val isVideo = FileNames.looksLikeVideo(url)
        val stamp = System.currentTimeMillis()
        return MediaCandidate(
            url = url,
            fileName = FileNames.build("User", "$stamp-$index", FileNames.extensionFromUrl(url, isVideo)),
            thumbnailUrl = if (isVideo) null else url,
            isVideo = isVideo,
            sourceUrl = pageUrl,
            headers = mapOf("User-Agent" to Http.DESKTOP_UA, "Referer" to pageUrl),
        )
    }
}
