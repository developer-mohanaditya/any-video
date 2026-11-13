package io.anyvideo.downloader.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import io.anyvideo.downloader.data.StatusFile
import io.anyvideo.downloader.data.WhatsAppStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WhatsAppScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<StatusFile>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        items = withContext(Dispatchers.IO) { WhatsAppStatus.scan() }
    }

    ScreenScaffold(
        title = "WhatsApp status saver",
        onBack = onBack,
        actions = {
            IconButton(onClick = { refresh++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        },
    ) { modifier ->
        Column(modifier = modifier) {
            if (items.isEmpty()) {
                Hint(
                    "No status found. Watch a status on WhatsApp first; on Android 11+ the .Statuses folder " +
                        "needs the all-files access permission.",
                )
                TextButton(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    onClick = {
                        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                .setData(Uri.parse("package:${context.packageName}"))
                        } else {
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:${context.packageName}"))
                        }
                        try {
                            context.startActivity(intent)
                        } catch (t: Throwable) {
                            Toast.makeText(context, "Open Settings and grant storage access", Toast.LENGTH_LONG).show()
                        }
                    },
                ) { Text("Grant folder access") }
                EmptyState("Statuses you have already watched will appear here.")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(items) { status ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = status.file,
                                contentDescription = status.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(56.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    status.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                )
                                Text(
                                    if (status.isVideo) "Video" else "Photo",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    val uri = withContext(Dispatchers.IO) { WhatsAppStatus.save(context, status) }
                                    Toast.makeText(
                                        context,
                                        if (uri != null) "Saved to anyvideo" else "Could not save",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }) { Text("Save") }
                            TextButton(onClick = {
                                val deleted = WhatsAppStatus.delete(status)
                                Toast.makeText(
                                    context,
                                    if (deleted) "Deleted" else "Could not delete",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                refresh++
                            }) { Text("Delete") }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}
