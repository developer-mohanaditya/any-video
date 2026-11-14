package io.anyvideo.downloader.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import io.anyvideo.downloader.BuildConfig
import io.anyvideo.downloader.update.Updates
import io.anyvideo.downloader.net.Http
import io.anyvideo.downloader.net.IgSession
import io.anyvideo.downloader.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    themeMode: String,
    onThemeChanged: (String) -> Unit,
) {
    val context = LocalContext.current
    var notifications by remember { mutableStateOf(Prefs.showDownloadNotifications) }
    var autoClipboard by remember { mutableStateOf(Prefs.autoDownloadClipboard) }
    var extractor by remember { mutableStateOf(Prefs.extractorEndpoint) }
    var proxy by remember { mutableStateOf(Prefs.fallbackProxy) }
    var showPrivacy by remember { mutableStateOf(false) }
    var resolverApi by remember { mutableStateOf(Prefs.resolverApiBase) }
    var resolverExpanded by remember { mutableStateOf(Prefs.resolverApiOverride.isNotEmpty()) }
    var resolverKey by remember { mutableStateOf(Prefs.resolverApiKey) }
    var advanced by remember { mutableStateOf(Prefs.showAdvancedEndpoints) }
    var testing by remember { mutableStateOf(false) }
    var igCheck by remember { mutableStateOf<IgSession.Check?>(null) }
    var igChecking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val updateState by Updates.state.collectAsState()

    // Reading the cookie is local; only a signed-in session costs a request to name itself.
    fun runSessionCheck() {
        scope.launch {
            igChecking = true
            igCheck = IgSession.check(context)
            igChecking = false
        }
    }
    LaunchedEffect(Unit) { runSessionCheck() }

    ScreenScaffold(title = "Settings", onBack = onBack) { modifier ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SurfaceCard {
                Text("Appearance", fontWeight = FontWeight.SemiBold)
                Text(
                    "Auto follows your phone's light or dark setting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "Auto", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                        FilterChip(
                            selected = themeMode == value,
                            onClick = {
                                Prefs.themeMode = value
                                onThemeChanged(value)
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }

            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Download notifications", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Show a notification while a download runs and when it finishes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = notifications,
                        onCheckedChange = {
                            notifications = it
                            Prefs.showDownloadNotifications = it
                        },
                    )
                }
            }

            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-download from clipboard", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Open the app with a link copied and it saves straight away, no Fetch or " +
                                "Save tap. Several links at once are all queued. Turn off to review first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoClipboard,
                        onCheckedChange = {
                            autoClipboard = it
                            Prefs.autoDownloadClipboard = it
                        },
                    )
                }
            }

            SurfaceCard {
                Text("Instagram account", fontWeight = FontWeight.SemiBold)
                val state = igCheck
                Text(
                    when {
                        state == null -> "Checking…"
                        !state.signedIn ->
                            "Not signed in. Private profiles and posts need a login, which happens " +
                                "on Instagram's own page in the Browser tab. This app never sees " +
                                "the password."
                        state.username != null -> "Signed in as @${state.username}"
                        else -> "Session found, but Instagram would not name the account."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The literal answer Instagram gave for each account lookup: when a private
                // download fails, this says whether the session, the CSRF token or the account
                // lookup is the part that broke.
                state?.let {
                    Text(
                        it.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onSignIn) {
                        Text(if (state?.signedIn == true) "Switch account" else "Sign in")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { runSessionCheck() }, enabled = !igChecking) {
                        Text(if (igChecking) "Checking…" else "Check session")
                    }
                    if (state?.signedIn == true) {
                        TextButton(onClick = {
                            scope.launch {
                                IgSession.signOut()
                                igCheck = IgSession.check(context)
                                Toast.makeText(context, "Signed out of Instagram", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Sign out") }
                    }
                }
            }

            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Resolver", fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                Prefs.resolverApiOverride.isNotEmpty() ->
                                    "Custom server: ${Prefs.resolverApiOverride}"
                                Prefs.hasBuiltInResolver -> "Built-in instance. Nothing to set up."
                                else ->
                                    "No resolver set. Add a cobalt instance to download YouTube, " +
                                        "X, Pinterest and TikTok links."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        enabled = !testing && Prefs.resolverApiBase.isNotEmpty(),
                        onClick = {
                            val base = Prefs.resolverApiBase
                            val key = Prefs.resolverApiKey
                            testing = true
                            scope.launch {
                                val message = probeApi(base, key)
                                testing = false
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        },
                    ) { Text(if (testing) "Testing…" else "Test") }
                }
                TextButton(onClick = { resolverExpanded = !resolverExpanded }) {
                    Text(if (resolverExpanded) "Hide server settings" else "Change server")
                }
                if (resolverExpanded) {
                OutlinedTextField(
                    value = resolverApi,
                    onValueChange = { resolverApi = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("API base URL") },
                    placeholder = { Text("https://cobalt.example.com") },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = resolverKey,
                    onValueChange = { resolverKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("API key (optional)") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        Prefs.resolverApiBase = resolverApi
                        Prefs.resolverApiKey = resolverKey
                        resolverApi = Prefs.resolverApiBase
                        Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                    }) { Text("Save") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = !testing,
                        onClick = {
                            val base = resolverApi.ifBlank { Prefs.resolverApiBase }
                            Prefs.resolverApiBase = base
                            Prefs.resolverApiKey = resolverKey
                            testing = true
                            scope.launch {
                                val message = probeApi(base, resolverKey)
                                testing = false
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        },
                    ) { Text(if (testing) "Testing…" else "Test connection") }
                }
                Hint(
                    "Instance owners issue keys. The public api.cobalt.tools sits behind bot protection " +
                        "and will not answer app traffic, so self-host an instance or ask an owner for access.",
                )
                if (Prefs.resolverApiOverride.isNotEmpty()) {
                    TextButton(onClick = {
                        Prefs.resolverApiOverride = ""
                        Prefs.resolverApiKey = ""
                        resolverApi = Prefs.resolverApiBase
                        resolverKey = ""
                        Toast.makeText(
                            context,
                            if (Prefs.hasBuiltInResolver) "Back to the built-in instance"
                            else "Resolver cleared",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }) { Text(if (Prefs.hasBuiltInResolver) "Reset to built-in" else "Clear server") }
                }
                }
            }

            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Advanced endpoints", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Only needed if a site stops working. Off by default.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = advanced,
                        onCheckedChange = {
                            advanced = it
                            Prefs.showAdvancedEndpoints = it
                        },
                    )
                }
                if (advanced) {
                Spacer(Modifier.height(12.dp))
                Text("Generic extractor endpoint", fontWeight = FontWeight.SemiBold)
                Text(
                    "Form endpoint that receives `url` and answers with a page the WebView decoder can read.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = extractor,
                    onValueChange = { extractor = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Extractor URL") },
                )
                Spacer(Modifier.height(12.dp))
                Text("Download fallback proxy (optional)", fontWeight = FontWeight.SemiBold)
                Text(
                    "Called as `?isvideo=<bool>&url=<link>` when a direct download fails.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = proxy,
                    onValueChange = { proxy = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("e.g. https://example.com/find/download.php") },
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = {
                    Prefs.extractorEndpoint = extractor
                    Prefs.fallbackProxy = proxy
                    Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                }) { Text("Save endpoints") }
                }
            }

            SurfaceCard {
                Text("About", fontWeight = FontWeight.SemiBold)
                Text(
                    "AnyVideo ${BuildConfig.VERSION_NAME}. Save photos and videos from your social apps, " +
                        "without AdMob, AppLovin, Unity Ads, Firebase analytics or Play Billing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showPrivacy = true }) { Text("Privacy policy") }
                Hint(
                    "Downloads you make are your responsibility. Respect creators and the " +
                        "terms of the services you download from.",
                )
            }
            SurfaceCard {
                Text("Updates", fontWeight = FontWeight.SemiBold)
                Text(
                                            "AnyVideo ${BuildConfig.VERSION_NAME}. Updates come from this project's GitHub " +
                            "releases. The app opens the APK download; your browser saves it and Android " +
                            "installs it, so the app never asks for permission to install packages.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                val update = updateState
                Text(
                    when (update) {
                        is Updates.State.Idle -> "Check to see whether a newer version exists."
                        is Updates.State.Checking -> "Checking GitHub…"
                        is Updates.State.UpToDate -> "You are on the newest version."
                        is Updates.State.Available ->
                            "Version ${update.release.name} is available to download."
                        is Updates.State.Failed -> update.message
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (update is Updates.State.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                when (update) {
                    is Updates.State.Available -> {
                        if (update.release.notes.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                update.release.notes,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 5,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        if (update.release.size > 0) {
                            Text(
                                "${formatBytes(update.release.size)} download",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { Updates.open(context, update.release) }) {
                            Text("Download update")
                        }
                    }
                    else -> {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            enabled = update !is Updates.State.Checking,
                            onClick = { scope.launch { Updates.check() } },
                        ) {
                            Text(
                                when {
                                    update is Updates.State.Checking -> "Checking…"
                                    update is Updates.State.Failed -> "Try again"
                                    else -> "Check for updates"
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text("Privacy policy") },
            text = {
                Text(
                    "This app stores settings on your device only. It has no analytics, no advertising " +
                        "SDKs and no server of its own. Links you resolve are sent directly to the site " +
                        "they belong to, plus the generic extractor endpoint configured above. " +
                        "Files are saved to Pictures/anyvideo and Movies/anyvideo.",
                )
            },
            confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text("Close") } },
        )
    }
}

/** `GET /` on a cobalt instance reports its version and the services it supports. */
private suspend fun probeApi(base: String, key: String): String = withContext(Dispatchers.IO) {
    val clean = base.trim().trimEnd('/')
    if (clean.isEmpty()) return@withContext "Enter the instance URL first"
    val headers = buildMap {
        put("Accept", "application/json")
        if (key.isNotBlank()) {
            put(
                "Authorization",
                if (key.startsWith("Bearer ", true) || key.startsWith("Api-Key ", true)) key else "Api-Key $key",
            )
        }
    }
    val body = Http.get("$clean/", headers) ?: return@withContext "No answer from $clean"
    runCatching {
        val cobalt = JSONObject(body).optJSONObject("cobalt")
            ?: return@runCatching "Answered, but not like a cobalt instance"
        val version = cobalt.optString("version").ifBlank { "?" }
        val services = cobalt.optJSONArray("services")
        "Connected to cobalt $version" + if (services != null) " (${services.length()} services)" else ""
    }.getOrElse { "Answered, but not like a cobalt instance" }
}
