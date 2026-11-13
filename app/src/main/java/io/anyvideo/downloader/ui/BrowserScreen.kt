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

/**
 * Clicks Instagram's "Decline optional cookies" button once React has rendered it. The banner
 * is present in the DOM but clipped, so a real tap can never reach it. The interval gives a slow
 * first paint a few seconds and then gives up without touching normal pages.
 */
private const val DISMISS_COOKIE_SHEET = """
(function () {
  var tries = 0;
  var timer = setInterval(function () {
    var buttons = document.querySelectorAll('button');
    for (var i = 0; i < buttons.length; i++) {
      if (/decline optional cookies/i.test(buttons[i].innerText || '')) {
        buttons[i].click();
        clearInterval(timer);
        return;
      }
    }
    if (++tries > 20) clearInterval(timer);
  }, 400);
})();
"""

private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36"

/**
 * Instagram's login page builds its form inside wrappers that report zero height with overflow
 * clipping. The form is fully laid out, but the clip removes it from both painting and hit
 * testing, so the page looks black and taps reach nothing. Growing every zero-height wrapper to
 * the viewport height restores the real form. The interval catches wrappers React replaces while
 * the page settles.
 */
private const val REVEAL_CLIPPED_LOGIN_FORM = """
(function () {
  function expand(el) {
    el.style.setProperty('height', window.innerHeight + 'px', 'important');
    el.style.setProperty('min-height', window.innerHeight + 'px', 'important');
    el.style.setProperty('overflow', 'visible', 'important');
  }
  function fix() {
    var input = document.querySelector('input[name="username"]');
    if (!input) return;
    expand(document.documentElement);
    expand(document.body);
    var el = input;
    while (el && el !== document.body) {
      if (el.getBoundingClientRect().height < 10) expand(el);
      el = el.parentElement;
    }
  }
  fix();
  var tries = 0;
  var timer = setInterval(function () {
    fix();
    if (++tries > 30) clearInterval(timer);
  }, 300);
})();
"""

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
                                    // Instagram's consent sheet lays out to zero height inside a
                                    // WebView, leaving an invisible scrim that swallows every tap.
                                    // Choosing essential-only cookies dismisses it and unblocks the
                                    // page, and it is the choice that keeps tracking to a minimum.
                                    if (it.contains("instagram.com")) {
                                        view.evaluateJavascript(DISMISS_COOKIE_SHEET, null)
                                    }
                                    if (it.contains("instagram.com/accounts/login")) {
                                        view.evaluateJavascript(REVEAL_CLIPPED_LOGIN_FORM, null)
                                    }
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
