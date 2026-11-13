package io.anyvideo.downloader.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.anyvideo.downloader.Screen
import io.anyvideo.downloader.data.Library
import io.anyvideo.downloader.data.MediaKind
import io.anyvideo.downloader.data.MimeTypes
import io.anyvideo.downloader.data.SavedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(onNavigate: (Screen) -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<SavedMedia>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<SavedMedia?>(null) }

    LaunchedEffect(tab, refreshKey) {
        val kind = when (tab) {
            1 -> MediaKind.VIDEO
            2 -> MediaKind.AUDIO
            else -> MediaKind.PHOTO
        }
        items = withContext(Dispatchers.IO) { Library.load(context, kind) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Downloads", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${items.size} item(s) in anyvideo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { refreshKey++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }

        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            listOf("Photos", "Videos", "Audio").forEachIndexed { index, label ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(label) },
                )
            }
        }

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    when (tab) {
                        0 -> "Saved pictures will be listed here."
                        1 -> "Saved videos will be listed here."
                        else -> "Saved audio will be listed here."
                    },
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items) { media ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .combinedClickable(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(media.uri, MimeTypes.forKind(media.name, media.kind))
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    try {
                                        context.startActivity(intent)
                                    } catch (t: Throwable) {
                                        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onLongClick = { selected = media },
                            ),
                    ) {
                        if (media.kind == MediaKind.AUDIO) {
                            // Audio has no frame to show, so the tile carries the facts there are:
                            // where it came from, what format it is and how big it is.
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.MusicNote,
                                    contentDescription = media.name,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                            Text(
                                "${media.name.substringAfterLast('.', "").uppercase()} · ${media.size / 1024} KB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(4.dp),
                            )
                        } else {
                            AsyncImage(
                                model = media.uri,
                                contentDescription = media.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (media.kind == MediaKind.VIDEO) {
                            Text(
                                "VIDEO",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(6.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    selected?.let { media ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(media.name, style = MaterialTheme.typography.titleSmall) },
            text = {
                Column {
                    Text("${media.size / 1024} KB")
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = MimeTypes.forKind(media.name, media.kind)
                            putExtra(Intent.EXTRA_STREAM, media.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, "Share"))
                        selected = null
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Share")
                    }
                    if (media.kind == MediaKind.PHOTO) {
                        TextButton(onClick = {
                            selected = null
                            onNavigate(Screen.REPOST)
                        }) { Text("Repost with credit") }
                    }
                    TextButton(onClick = {
                        val deleted = Library.delete(context, media)
                        Toast.makeText(
                            context,
                            if (deleted) "Deleted" else "Could not delete",
                            Toast.LENGTH_SHORT,
                        ).show()
                        selected = null
                        refreshKey++
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Delete")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selected = null }) { Text("Close") }
            },
        )
    }
}
