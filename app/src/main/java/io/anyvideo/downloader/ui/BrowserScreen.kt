package io.anyvideo.downloader.ui

import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36"

/**
 * A plain browser. Its job is reading pages and, importantly, holding the Instagram login
 * session the rest of the app borrows: the cookies live in this WebView's jar.
 */
@Composable
fun BrowserScreen(startUrl: String = "https://www.instagram.com") {
    var urlText by remember(startUrl) { mutableStateOf(startUrl) }
    var currentUrl by remember(startUrl) { mutableStateOf(startUrl) }
    var progress by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        val go: () -> Unit = {
            val target = when {
                urlText.startsWith("http") -> urlText
                urlText.contains('.') && !urlText.contains(' ') -> "https://$urlText"
                urlText.isNotBlank() ->
                    "https://www.google.com/search?q=" + java.net.URLEncoder.encode(urlText, "UTF-8")
                else -> currentUrl
            }
            urlText = target
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
                Icon(
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
                        // A phone user agent matters: Instagram answers the desktop one with a
                        // "get the tablet app" interstitial instead of the page.
                        settings.userAgentString = MOBILE_UA
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                url?.let {
                                    currentUrl = it
                                    urlText = it
                                }
                            }
                        }
                        loadUrl(currentUrl)
                    }
                },
                update = { webView ->
                    if (webView.url != currentUrl && currentUrl.isNotBlank()) webView.loadUrl(currentUrl)
                },
                // A WebView keeps a renderer process alive until it is destroyed, so leaving the
                // tab would otherwise leak one per visit.
                onRelease = { webView ->
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.clearHistory()
                    webView.removeAllViews()
                    webView.destroy()
                },
            )
        }
    }
}
