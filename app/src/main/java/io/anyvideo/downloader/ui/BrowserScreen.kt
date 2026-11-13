package io.anyvideo.downloader.ui

import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.anyvideo.downloader.data.FileNames
import io.anyvideo.downloader.data.MediaCandidate
import io.anyvideo.downloader.download.Downloader
import io.anyvideo.downloader.net.Http
import org.json.JSONArray
import java.util.Collections

private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36"

@Composable
fun BrowserScreen(startUrl: String = "https://www.instagram.com") {
    val context = LocalContext.current
    // The Instagram sign-in action asks for its login page; everywhere else keeps the default.
    var urlText by remember(startUrl) { mutableStateOf(startUrl) }
    var currentUrl by remember(startUrl) { mutableStateOf(startUrl) }
    var progress by remember { mutableIntStateOf(0) }
    var found by remember { mutableStateOf<List<MediaCandidate>>(emptyList()) }
    var showResults by remember { mutableStateOf(false) }
    val intercepted = remember { Collections.synchronizedSet(LinkedHashSet<String>()) }

    fun toCandidates(urls: List<String>): List<MediaCandidate> = urls
        .filter { url ->
            (FileNames.looksLikeVideo(url) || FileNames.looksLikeImage(url)) &&
                !url.contains("applovin", true) && !url.contains("googlesyndication", true)
        }
        .distinct()
        .mapIndexed { index, url ->
            val isVideo = FileNames.looksLikeVideo(url)
            MediaCandidate(
                url = url,
                fileName = FileNames.build("User", "${System.currentTimeMillis()}-$index", FileNames.extensionFromUrl(url, isVideo)),
                thumbnailUrl = if (isVideo) null else url,
                isVideo = isVideo,
                sourceUrl = currentUrl,
            )
        }

    Column(modifier = Modifier.fillMaxSize()) {
        val go: () -> Unit = {
            val target = when {
                urlText.startsWith("http") -> urlText
                urlText.contains('.') && !urlText.contains(' ') -> "https://$urlText"
                urlText.isNotBlank() ->
                    "https://www.google.com/search?q=" + java.net.URLEncoder.encode(urlText, "UTF-8")
                else -> currentUrl
            }
            currentUrl = target
        }
        OutlinedTextField(
            value = urlText,
            onValueChange = { urlText = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
            singleLine = true,
            placeholder = { Text("Search or paste a link") },
            shape = RoundedCornerShape(18.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            leadingIcon = {
                androidx.compose.material3.Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                TextButton(onClick = go) { Text("Go") }
            },
        )

        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        // A phone UA matters here: with the desktop one, Instagram answers with
                        // its "get the tablet app" interstitial instead of the actual page.
                        settings.userAgentString = MOBILE_UA
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                val text = message.message()
                                if (text.startsWith("MEDIA:")) {
                                    try {
                                        val array = JSONArray(text.removePrefix("MEDIA:"))
                                        for (i in 0 until array.length()) {
                                            intercepted.add(array.optString(i))
                                        }
                                    } catch (t: Throwable) {
                                        // Ignore malformed page output.
                                    }
                                }
                                return true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: android.webkit.WebResourceRequest,
                            ): android.webkit.WebResourceResponse? {
                                request.url?.toString()?.let { url ->
                                    if (FileNames.looksLikeVideo(url) || FileNames.looksLikeImage(url)) {
                                        // Bounded: a long browsing session would otherwise keep
                                        // every media URL it saw in memory.
                                        if (intercepted.size < 300) intercepted.add(url)
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                view.evaluateJavascript(
                                    """
                                    (function(){
                                      var out = [];
                                      document.querySelectorAll('video,source').forEach(function(v){ if(v.src) out.push(v.src); });
                                      document.querySelectorAll('img').forEach(function(i){ if(i.src) out.push(i.src); });
                                      console.log('MEDIA:' + JSON.stringify(out));
                                    })();
                                    """.trimIndent(),
                                    null,
                                )
                            }
                        }
                        loadUrl(currentUrl)
                    }
                },
                update = { webView ->
                    if (webView.url != currentUrl && currentUrl.isNotBlank()) webView.loadUrl(currentUrl)
                },
                // A WebView keeps a Chromium renderer process and its caches alive until it is
                // destroyed. Leaving the Browser tab used to leak one of those every visit.
                onRelease = { webView ->
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.clearHistory()
                    webView.removeAllViews()
                    webView.destroy()
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${intercepted.size} media request(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                found = toCandidates(intercepted.toList())
                showResults = true
            }) { Text("Review") }
            Button(onClick = {
                val candidates = toCandidates(intercepted.toList())
                if (candidates.isEmpty()) {
                    Toast.makeText(context, "No media detected yet", Toast.LENGTH_SHORT).show()
                } else {
                    val count = Downloader.enqueueAll(context, candidates)
                    Toast.makeText(context, "Queued $count download(s)", Toast.LENGTH_SHORT).show()
                    intercepted.clear()
                }
            }) {
                androidx.compose.material3.Icon(
                    Icons.Filled.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Save all")
            }
        }
    }

    if (showResults) {
        AlertDialog(
            onDismissRequest = { showResults = false },
            title = { Text("Detected media (${found.size})") },
            text = {
                if (found.isEmpty()) {
                    Text("Nothing detected on this page yet.")
                } else {
                    LazyColumn(modifier = Modifier.height(320.dp)) {
                        items(found) { candidate ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (candidate.isVideo) "Video" else "Photo",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    Downloader.enqueue(context, candidate)
                                    Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
                                }) { Text("Save") }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val count = Downloader.enqueueAll(context, found)
                    Toast.makeText(context, "Queued $count download(s)", Toast.LENGTH_SHORT).show()
                    showResults = false
                }) { Text("Save all") }
            },
            dismissButton = {
                TextButton(onClick = { showResults = false }) { Text("Close") }
            },
        )
    }
}
