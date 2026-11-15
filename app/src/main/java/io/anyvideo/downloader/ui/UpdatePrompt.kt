package io.anyvideo.downloader.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.anyvideo.downloader.update.Updates
import io.anyvideo.downloader.util.Prefs
import kotlinx.coroutines.delay

/** Checks at most once a day after launch, without blocking the first frame or the splash. */
@Composable
fun AutoUpdateCheck() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        delay(1800)
        Updates.autoCheck(context)
    }
}

/**
 * One prompt for a downloaded or available update. Downloads happen in the app; Android's own
 * installer is still the only thing that can replace the package.
 */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val update by Updates.state.collectAsState()
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(update) {
        when (val state = update) {
            is Updates.State.Available -> {
                if (Prefs.dismissedUpdateVersion != state.release.name) visible = true
            }
            is Updates.State.Downloading, is Updates.State.Ready -> visible = true
            is Updates.State.Failed -> if (state.release != null) visible = true
            else -> Unit
        }
    }

    if (!visible) return
    val release = when (val state = update) {
        is Updates.State.Available -> state.release
        is Updates.State.Downloading -> state.release
        is Updates.State.Ready -> state.release
        is Updates.State.Failed -> state.release
        else -> null
    } ?: return

    fun dismiss() {
        visible = false
        if (update is Updates.State.Available) {
            Prefs.dismissedUpdateVersion = release.name
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (update !is Updates.State.Downloading) dismiss()
        },
        title = { Text("AnyVideo ${release.name} is available") },
        text = {
            Column {
                Text(
                    "Download it inside AnyVideo. Android will ask you to confirm the install.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (release.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        release.notes,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (release.size > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${formatBytes(release.size)} download",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (val state = update) {
                    is Updates.State.Downloading -> {
                        Spacer(Modifier.height(10.dp))
                        if (state.total > 0) {
                            LinearProgressIndicator(
                                progress = {
                                    (state.received.toFloat() / state.total).coerceIn(0f, 1f)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${formatBytes(state.received)} of ${formatBytes(state.total)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(4.dp))
                            Text(
                                formatBytes(state.received),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    is Updates.State.Ready -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "The signed update is ready. Android asks for confirmation before it replaces the app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is Updates.State.Failed -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (val state = update) {
                is Updates.State.Available -> {
                    Button(onClick = { Updates.startDownload(context, release) }) {
                        Text("Download update")
                    }
                }
                is Updates.State.Downloading -> {
                    Button(enabled = false, onClick = {}) { Text("Downloading…") }
                }
                is Updates.State.Ready -> {
                    Button(
                        onClick = {
                            if (!Updates.install(context, state.file)) {
                                Toast.makeText(
                                    context,
                                    "Allow installs from AnyVideo, then tap Install update",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                    ) { Text("Install update") }
                }
                is Updates.State.Failed -> {
                    Button(
                        onClick = {
                            if (state.release != null) {
                                Updates.startDownload(context, state.release)
                            } else {
                                Updates.startCheck(context)
                            }
                        },
                    ) { Text("Try again") }
                }
                else -> Unit
            }
        },
        dismissButton = {
            if (update !is Updates.State.Downloading) {
                TextButton(onClick = { dismiss() }) { Text("Later") }
            }
        },
    )
}
