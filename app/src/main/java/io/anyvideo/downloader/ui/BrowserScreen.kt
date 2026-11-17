package io.anyvideo.downloader.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.anyvideo.downloader.net.IgSession

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
 * Some Instagram feeds keep the document at a fixed height while their own nested scroller
 * handles the page. Giving the root a minimum viewport height keeps touch targets reachable.
 */
private const val REVEAL_INSTAGRAM_PAGE = """
(function () {
  if (!document.body) return;
  document.documentElement.style.setProperty('min-height', '100%', 'important');
  document.body.style.setProperty('min-height', '100vh', 'important');
})();
"""

/**
 * A plain browser. Its job is reading pages and, importantly, holding the Instagram login
 * session the rest of the app borrows: the cookies live in this WebView's jar.
 */
@Composable
fun BrowserScreen(
    startUrl: String = "https://www.instagram.com",
    onFullscreenChanged: (Boolean) -> Unit = {},
) {
    var urlText by remember(startUrl) { mutableStateOf(startUrl) }
    var currentUrl by remember(startUrl) { mutableStateOf(startUrl) }
    var progress by remember { mutableIntStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }
    var browser by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    val view = LocalView.current

    LaunchedEffect(fullscreen) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (fullscreen) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onFullscreenChanged(fullscreen)
    }
    DisposableEffect(Unit) {
        onDispose {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
            onFullscreenChanged(false)
        }
    }
    BackHandler(enabled = canGoBack) { browser?.goBack() }
    BackHandler(enabled = fullscreen && !canGoBack) { fullscreen = false }

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

        if (!fullscreen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    modifier = Modifier.weight(1f),
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
                IconButton(onClick = { fullscreen = true }) {
                    Icon(Icons.Filled.Fullscreen, contentDescription = "Full screen")
                }
            }

            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.setSupportZoom(true)
                        // A phone user agent matters: Instagram answers the desktop one with a
                        // "get the tablet app" interstitial instead of the page.
                        settings.userAgentString = IgSession.webUserAgent(ctx)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val uri = request.url
                                if (uri.scheme == "http" || uri.scheme == "https") return false
                                return runCatching {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                }.isSuccess
                            }

                            override fun doUpdateVisitedHistory(
                                view: WebView,
                                url: String?,
                                isReload: Boolean,
                            ) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                // Instagram is a single page app, so a tap can change history
                                // without a page load. Keep the system back gesture in the page
                                // instead of letting it leave the Browser tab.
                                canGoBack = view.canGoBack()
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                url?.let {
                                    currentUrl = it
                                    urlText = it
                                    canGoBack = view.canGoBack()
                                    // Instagram's consent sheet lays out to zero height inside a
                                    // WebView, leaving an invisible scrim that swallows every tap.
                                    // Choosing essential-only cookies dismisses it and unblocks the
                                    // page, and it is the choice that keeps tracking to a minimum.
                                    if (it.contains("instagram.com")) {
                                        view.evaluateJavascript(DISMISS_COOKIE_SHEET, null)
                                        view.evaluateJavascript(REVEAL_INSTAGRAM_PAGE, null)
                                    }
                                    if (it.contains("instagram.com/accounts/login")) {
                                        view.evaluateJavascript(REVEAL_CLIPPED_LOGIN_FORM, null)
                                    }
                                }
                            }
                        }
                        browser = this
                        loadUrl(currentUrl)
                    }
                },
                update = { webView ->
                    if (webView.url != currentUrl && currentUrl.isNotBlank()) webView.loadUrl(currentUrl)
                },
                // A WebView keeps a renderer process alive until it is destroyed, so leaving the
                // tab would otherwise leak one per visit.
                onRelease = { webView ->
                    browser = null
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.clearHistory()
                    webView.removeAllViews()
                    webView.destroy()
                },
            )

            if (fullscreen) {
                IconButton(
                    onClick = { fullscreen = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(36.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                ) {
                    Icon(
                        Icons.Filled.FullscreenExit,
                        contentDescription = "Exit full screen",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}
