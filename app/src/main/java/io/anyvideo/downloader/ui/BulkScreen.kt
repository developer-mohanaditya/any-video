package io.anyvideo.downloader.ui

import android.app.Activity
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.anyvideo.downloader.data.MediaCandidate
import io.anyvideo.downloader.download.Downloader
import io.anyvideo.downloader.net.IgSession
import io.anyvideo.downloader.net.Instagram
import kotlinx.coroutines.launch

@Composable
fun BulkScreen(onBack: () -> Unit, onSignIn: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<MediaCandidate>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var message by remember { mutableStateOf<String?>(null) }
    var signedIn by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf<String?>(null) }

    // The browser session can change while this screen is open (sign in from Settings), so the
    // state is re-read on entry rather than kept in a single snapshot.
    LaunchedEffect(Unit) {
        signedIn = IgSession.signedIn()
        if (signedIn) account = IgSession.refreshAccount(context)
    }

    fun load() {
        if (username.isBlank() || activity == null) {
            message = "Enter a profile name first."
            return
        }
        loading = true
        message = null
        scope.launch {
            // Instagram answers anonymous profile requests with 403/404 now, so the request is not
            // sent at all until there is a session to send it with.
            if (!signedIn) {
                items = emptyList()
                selected = emptySet()
                message = "Sign in to Instagram first. It no longer returns profile feeds to " +
                    "anonymous requests."
                loading = false
                return@launch
            }
            val result = Instagram.profileFeed(activity, username.trim().removePrefix("@"))
            items = result.items
            selected = result.items.map { it.url }.toSet()
            signedIn = result.signedIn
            if (result.signedIn) account = IgSession.refreshAccount(context)
            message = result.note ?: "Loaded ${result.items.size} item(s)"
            loading = false
        }
    }

    ScreenScaffold(title = "Bulk download", onBack = onBack) { modifier ->
        Column(modifier = modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Instagram username") },
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { load() }, enabled = !loading) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Load")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (signedIn) {
                        account?.let { "Signed in as @$it" } ?: "Signed in"
                    } else {
                        "Not signed in. Instagram needs a login to list any profile."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSignIn) { Text(if (signedIn) "Switch" else "Sign in") }
            }

            message?.let { Hint(it) }

            if (items.isEmpty() && !loading && message == null) {
                EmptyState(
                    icon = Icons.Filled.Layers,
                    title = "Load a profile feed",
                    body = "Type an Instagram username above and tap Load to list its posts and " +
                        "reels. Pick what you want, then download the whole selection at once.",
                )
            }

            if (items.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${selected.size} of ${items.size} selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        selected = if (selected.size == items.size) emptySet() else items.map { it.url }.toSet()
                    }) { Text(if (selected.size == items.size) "Clear" else "Select all") }
                    Button(onClick = {
                        val chosen = items.filter { it.url in selected }
                        val count = Downloader.enqueueAll(context, chosen)
                        Toast.makeText(context, "Queued $count download(s)", Toast.LENGTH_SHORT).show()
                    }) { Text("Download") }
                }
            }

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(items) { candidate ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = candidate.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(52.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                candidate.fileName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                            Text(
                                Downloader.kindLabel(candidate.fileName, candidate.isVideo),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Checkbox(
                            checked = candidate.url in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + candidate.url else selected - candidate.url
                            },
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
