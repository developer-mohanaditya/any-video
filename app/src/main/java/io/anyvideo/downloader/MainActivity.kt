package io.anyvideo.downloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import io.anyvideo.downloader.ui.BrowserScreen
import io.anyvideo.downloader.ui.BulkScreen
import io.anyvideo.downloader.ui.CaptionScreen
import io.anyvideo.downloader.ui.DownloadsScreen
import io.anyvideo.downloader.ui.HomeScreen
import io.anyvideo.downloader.ui.LineBreakScreen
import io.anyvideo.downloader.ui.RepostScreen
import io.anyvideo.downloader.ui.SettingsScreen
import io.anyvideo.downloader.ui.ToolsScreen
import io.anyvideo.downloader.ui.WhatsAppScreen
import io.anyvideo.downloader.net.IgSession
import io.anyvideo.downloader.ui.AnyVideoTheme
import io.anyvideo.downloader.util.Prefs
import io.anyvideo.downloader.ui.tools.DpMakerScreen
import io.anyvideo.downloader.ui.tools.GridMakerScreen
import io.anyvideo.downloader.ui.tools.ResizeScreen
import io.anyvideo.downloader.ui.tools.SquarePhotoScreen

enum class Screen {
    HOME, DOWNLOADS, BROWSE, TOOLS,
    REPOST, CAPTIONS, GRID, SQUARE, RESIZE, DP, LINE_BREAK, WHATSAPP, BULK, SETTINGS,
}

class MainActivity : ComponentActivity() {

    private var incomingUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            AppRoot(
                incomingUrl = incomingUrl,
                onIncomingConsumed = { incomingUrl = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        // The whole text is forwarded: the home screen splits it, so a shared list of links
        // queues every one of them instead of only the first.
        if (!text.isNullOrBlank()) incomingUrl = text.trim()
    }

    /**
     * FastSave behaviour: on launch or when returning from the browser, a supported link sitting
     * on the clipboard is picked up without any tap. Reading the clipboard requires window focus,
     * which is why this runs from onWindowFocusChanged rather than onCreate.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) pickUpClipboard()
    }

    private fun pickUpClipboard() {
        if (!Prefs.autoDownloadClipboard || incomingUrl != null) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = try {
            clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        } catch (t: Throwable) {
            null
        }
        if (text.isNullOrBlank() || text == Prefs.lastAutoClipboard) return
        if (!text.contains("http", true)) return
        Prefs.lastAutoClipboard = text
        incomingUrl = text
    }
}

private data class Tab(val screen: Screen, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Screen.HOME, "Home", Icons.Filled.Home),
    Tab(Screen.DOWNLOADS, "Downloads", Icons.Filled.Download),
    Tab(Screen.BROWSE, "Browser", Icons.Filled.Public),
    Tab(Screen.TOOLS, "Tools", Icons.Filled.Widgets),
)

private fun requiredPermissions(): Array<String> {
    val permissions = ArrayList<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions += Manifest.permission.POST_NOTIFICATIONS
        permissions += Manifest.permission.READ_MEDIA_IMAGES
        permissions += Manifest.permission.READ_MEDIA_VIDEO
    } else {
        permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
    }
    return permissions.toTypedArray()
}

@Composable
private fun AppRoot(incomingUrl: String?, onIncomingConsumed: () -> Unit) {
    var themeMode by remember { mutableStateOf(Prefs.themeMode) }
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    AnyVideoTheme(darkTheme = darkTheme) {
    AppRootContent(
        incomingUrl = incomingUrl,
        onIncomingConsumed = onIncomingConsumed,
        themeMode = themeMode,
        onThemeChanged = { themeMode = it },
    )
    }
}

@Composable
private fun AppRootContent(
    incomingUrl: String?,
    onIncomingConsumed: () -> Unit,
    themeMode: String,
    onThemeChanged: (String) -> Unit,
) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    var autoUrl by remember { mutableStateOf<String?>(null) }
    // What the Browser tab opens next; set by the Instagram sign-in actions.
    var browserUrl by remember { mutableStateOf("https://www.instagram.com") }
    val openInstagramSignIn: () -> Unit = {
        browserUrl = IgSession.LOGIN_URL
        screen = Screen.BROWSE
    }
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    LaunchedEffect(Unit) {
        try {
            permissionLauncher.launch(requiredPermissions())
        } catch (t: Throwable) {
            // Permission prompts are best-effort; every screen degrades gracefully.
        }
    }

    LaunchedEffect(incomingUrl) {
        if (!incomingUrl.isNullOrBlank()) {
            autoUrl = incomingUrl
            screen = Screen.HOME
            onIncomingConsumed()
        }
    }

    val showTabs = screen in setOf(Screen.HOME, Screen.DOWNLOADS, Screen.BROWSE, Screen.TOOLS)
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    Scaffold(
        bottomBar = {
            if (showTabs) {
                NavigationBar(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = screen == tab.screen,
                            onClick = { screen = tab.screen },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    autoUrl = autoUrl,
                    onAutoUrlConsumed = { autoUrl = null },
                    onNavigate = { screen = it },
                )

                Screen.DOWNLOADS -> DownloadsScreen()

                Screen.BROWSE -> BrowserScreen(startUrl = browserUrl)

                Screen.TOOLS -> ToolsScreen(onNavigate = { screen = it })

                Screen.REPOST -> RepostScreen(onBack = { screen = Screen.TOOLS })
                Screen.CAPTIONS -> CaptionScreen(onBack = { screen = Screen.TOOLS })
                Screen.GRID -> GridMakerScreen(onBack = { screen = Screen.TOOLS })
                Screen.SQUARE -> SquarePhotoScreen(onBack = { screen = Screen.TOOLS })
                Screen.RESIZE -> ResizeScreen(onBack = { screen = Screen.TOOLS })
                Screen.DP -> DpMakerScreen(onBack = { screen = Screen.TOOLS })
                Screen.LINE_BREAK -> LineBreakScreen(onBack = { screen = Screen.TOOLS })
                Screen.WHATSAPP -> WhatsAppScreen(onBack = { screen = Screen.TOOLS })
                Screen.BULK -> BulkScreen(
                    onBack = { screen = Screen.HOME },
                    onSignIn = openInstagramSignIn,
                )

                Screen.SETTINGS -> SettingsScreen(
                    onBack = { screen = Screen.HOME },
                    onSignIn = openInstagramSignIn,
                    themeMode = themeMode,
                    onThemeChanged = onThemeChanged,
                )
            }
        }
    }
}
