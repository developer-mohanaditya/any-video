package io.anyvideo.downloader.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import io.anyvideo.downloader.PlayerActivity
import io.anyvideo.downloader.data.Library
import io.anyvideo.downloader.data.MediaKind
import io.anyvideo.downloader.data.MimeTypes
import io.anyvideo.downloader.data.SavedMedia
import io.anyvideo.downloader.download.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TABS = listOf("Photos", "Videos", "Audio")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen() {
    val context = LocalContext.current
    val pager = rememberPagerState(pageCount = { TABS.size })
    var refreshKey by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var selection by remember { mutableStateOf<Set<SavedMedia>>(emptySet()) }
    var previewItems by remember { mutableStateOf<List<SavedMedia>>(emptyList()) }
    var previewStart by remember { mutableIntStateOf(0) }
    var previewOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Set<SavedMedia>>(emptySet()) }
    val playerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) refreshKey++
    }

    val tasks by Downloader.tasks.collectAsState()
    val active = tasks.filter {
        it.state == Downloader.State.QUEUED ||
            it.state == Downloader.State.RUNNING ||
            it.state == Downloader.State.PAUSED
    }
    val completed = tasks.filter { it.state == Downloader.State.DONE }
    val failed = tasks.filter { it.state == Downloader.State.FAILED }
    val doneCount = completed.size
    var sessionTab by remember { mutableIntStateOf(0) }

    fun taskKind(task: Downloader.Task): MediaKind = when {
        task.isVideo -> MediaKind.VIDEO
        Downloader.isAudio(task.fileName) -> MediaKind.AUDIO
        else -> MediaKind.PHOTO
    }

    val openTask: (Downloader.Task) -> Unit = { task ->
        scope.launch {
            val media = withContext(Dispatchers.IO) {
                Library.load(context, taskKind(task)).firstOrNull { it.name == task.fileName }
            }
            if (media == null) {
                Toast.makeText(context, "That file is no longer in the gallery", Toast.LENGTH_SHORT).show()
            } else if (media.kind == MediaKind.PHOTO) {
                previewItems = listOf(media)
                previewStart = 0
                previewOpen = true
            } else {
                playerLauncher.launch(PlayerActivity.intent(context, media))
            }
        }
    }
    val shareTask: (Downloader.Task) -> Unit = { task ->
        scope.launch {
            val media = withContext(Dispatchers.IO) {
                Library.load(context, taskKind(task)).firstOrNull { it.name == task.fileName }
            }
            if (media == null) {
                Toast.makeText(context, "That file is no longer in the gallery", Toast.LENGTH_SHORT).show()
            } else {
                share(context, media)
            }
        }
    }

    // A finished transfer must show up in the grid without the user pulling to refresh.
    LaunchedEffect(doneCount) {
        if (doneCount > 0) refreshKey++
    }

    Scaffold(
        topBar = {
            if (selection.isNotEmpty()) {
                SelectionBar(
                    count = selection.size,
                    onShare = { shareSelection(context, selection) },
                    onDelete = { pendingDelete = selection },
                    onClose = { selection = emptySet() },
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Downloads", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            if (active.isEmpty()) "Saved in Pictures, Movies and Music"
                            else "Downloading ${active.size} file(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SessionTabs(
                selected = sessionTab,
                active = active.size,
                completed = completed.size,
                failed = failed.size,
                onSelect = { sessionTab = it },
            )
            Text(
                when (sessionTab) {
                    1 -> "Completed downloads"
                    2 -> "Failed downloads"
                    else -> "Current downloads"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 2.dp),
            )
            when (sessionTab) {
                1 -> if (completed.isNotEmpty()) {
                    CompletedDownloads(completed, openTask, shareTask)
                } else {
                    SessionEmpty("Finished downloads will appear here")
                }
                2 -> if (failed.isNotEmpty()) {
                    FailedDownloads(failed)
                } else {
                    SessionEmpty("Failed downloads will appear here")
                }
                else -> if (active.isNotEmpty()) {
                    ActiveDownloads(active)
                } else {
                    SessionEmpty("No active downloads")
                }
            }

            TabRow(
                selectedTabIndex = pager.currentPage,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                TABS.forEachIndexed { index, label ->
                    Tab(
                        selected = pager.currentPage == index,
                        onClick = {
                            selection = emptySet()
                            scope.launch { pager.animateScrollToPage(index) }
                        },
                        text = { Text(label) },
                    )
                }
            }

            // Tabs follow the pager, and the pager follows a swipe, so the two are one control.
            LaunchedEffect(pager.currentPage) {
                selection = emptySet()
            }

            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                MediaPage(
                    kind = when (page) {
                        1 -> MediaKind.VIDEO
                        2 -> MediaKind.AUDIO
                        else -> MediaKind.PHOTO
                    },
                    refreshKey = refreshKey,
                    selection = selection,
                    onOpen = { items, media ->
                        if (media.kind == MediaKind.PHOTO) {
                            previewItems = items
                            previewStart = items.indexOfFirst { it.uri == media.uri }.coerceAtLeast(0)
                            previewOpen = true
                        } else {
                            playerLauncher.launch(PlayerActivity.intent(context, media))
                        }
                    },
                    onToggle = { media ->
                        selection = if (media in selection) selection - media else selection + media
                    },
                )
            }
        }
    }

    if (previewOpen && previewItems.isNotEmpty()) {
        MediaPreviewDialog(
            items = previewItems,
            initialPage = previewStart,
            onDismiss = { previewOpen = false },
            onShare = { media ->
                share(context, media)
                previewOpen = false
            },
            onDelete = { media -> pendingDelete = setOf(media) },
        )
    }

    if (pendingDelete.isNotEmpty()) {
        val count = pendingDelete.size
        AlertDialog(
            onDismissRequest = { pendingDelete = emptySet() },
            title = { Text(if (count == 1) "Delete this file?" else "Delete $count files?") },
            text = {
                Text(
                    "This permanently removes the selected file(s) from your gallery. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteSelection(context, pendingDelete)
                    selection = emptySet()
                    pendingDelete = emptySet()
                    previewOpen = false
                    refreshKey++
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = emptySet() }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SessionTabs(
    selected: Int,
    active: Int,
    completed: Int,
    failed: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf("Active" to active, "Completed" to completed, "Failed" to failed).forEachIndexed { index, entry ->
            val chosen = selected == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(99.dp))
                    .background(
                        if (chosen) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${entry.first} ${entry.second}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (chosen) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SessionEmpty(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun CompletedDownloads(
    tasks: List<Downloader.Task>,
    onOpen: (Downloader.Task) -> Unit,
    onShare: (Downloader.Task) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tasks.take(6).forEach { task ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onOpen(task) },
                shape = RoundedCornerShape(15.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            taskIcon(task),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(
                            task.fileName,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${taskMeta(task)} · tap to open",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    StateChip(task)
                    RoundAction(Icons.Filled.PlayArrow, "Play") { onOpen(task) }
                    RoundAction(Icons.Filled.Share, "Share") { onShare(task) }
                }
            }
        }
        if (tasks.size > 6) {
            Text(
                "+${tasks.size - 6} more completed",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FailedDownloads(tasks: List<Downloader.Task>) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tasks.take(6).forEach { task ->
            FailedDownloadCard(task)
        }
        if (tasks.size > 6) {
            Text(
                "+${tasks.size - 6} more failed",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FailedDownloadCard(task: Downloader.Task) {
    val context = LocalContext.current
    var menu by remember(task.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    task.fileName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    task.error ?: "Could not finish this download",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { Downloader.retry(context, task.id) }) {
                Text("Retry", fontSize = 10.sp)
            }
            Box {
                RoundAction(Icons.Filled.MoreVert, "More") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Remove from list") },
                        onClick = {
                            Downloader.remove(task.id)
                            menu = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveDownloads(tasks: List<Downloader.Task>) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tasks.take(3).forEach { task -> ActiveDownloadCard(task) }
        if (tasks.size > 3) {
            Text(
                "+${tasks.size - 3} more in progress",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActiveDownloadCard(task: Downloader.Task) {
    val context = LocalContext.current
    var menu by remember(task.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = taskIcon(task),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        task.fileName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        taskMeta(task),
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StateChip(task)
                if (task.state == Downloader.State.QUEUED) {
                    RoundAction(Icons.Filled.PlayArrow, "Start download") {
                        Downloader.start(context, task.id)
                    }
                }
            }
            if (task.state == Downloader.State.RUNNING || task.state == Downloader.State.PAUSED) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.weight(1f).height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    RoundAction(
                        icon = if (task.state == Downloader.State.PAUSED) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        label = if (task.state == Downloader.State.PAUSED) "Resume" else "Pause",
                    ) {
                        if (task.state == Downloader.State.PAUSED) {
                            Downloader.resume(context, task.id)
                        } else {
                            Downloader.pause(task.id)
                        }
                    }
                    Box {
                        RoundAction(Icons.Filled.MoreVert, "More") { menu = true }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Cancel download") },
                                onClick = {
                                    Downloader.cancel(task.id)
                                    menu = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StateChip(task: Downloader.Task) {
    val label = when (task.state) {
        Downloader.State.QUEUED -> "Queued"
        Downloader.State.PAUSED -> "Paused"
        Downloader.State.FAILED -> "Failed"
        Downloader.State.DONE -> "Saved"
        else -> "${(task.progress * 100).toInt()}%"
    }
    Text(
        label,
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(99.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun RoundAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(24.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun taskIcon(task: Downloader.Task) = when {
    task.isVideo -> Icons.Filled.Videocam
    Downloader.isAudio(task.fileName) -> Icons.Filled.MusicNote
    else -> Icons.Filled.Image
}

private fun taskMeta(task: Downloader.Task): String {
    val kind = when {
        task.isVideo -> "Video"
        Downloader.isAudio(task.fileName) -> "Audio"
        else -> "Photo"
    }
    val size = if (task.total > 0L) formatBytes(task.total) else task.mime ?: "File"
    val state = when (task.state) {
        Downloader.State.PAUSED -> "paused"
        Downloader.State.QUEUED -> "waiting"
        Downloader.State.FAILED -> "failed"
        Downloader.State.DONE -> "saved"
        else -> "${(task.progress * 100).toInt()}%"
    }
    return "$kind · $size · $state"
}

@Composable
private fun MediaPage(
    kind: MediaKind,
    refreshKey: Int,
    selection: Set<SavedMedia>,
    onOpen: (List<SavedMedia>, SavedMedia) -> Unit,
    onToggle: (SavedMedia) -> Unit,
) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<SavedMedia>>(emptyList()) }

    LaunchedEffect(kind, refreshKey) {
        items = withContext(Dispatchers.IO) { Library.load(context, kind) }
    }

    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                when (kind) {
                    MediaKind.PHOTO -> "Saved photos will be listed here."
                    MediaKind.VIDEO -> "Saved videos will be listed here."
                    MediaKind.AUDIO -> "Saved audio will be listed here."
                },
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selection.isNotEmpty()) {
            Text(
                if (selection.size == 1) "1 item selected" else "${selection.size} items selected",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(items, key = { it.uri.toString() }) { media ->
                val chosen = media in selection
                MediaTile(
                    media = media,
                    chosen = chosen,
                    onClick = { if (selection.isEmpty()) onOpen(items, media) else onToggle(media) },
                    onLongClick = { onToggle(media) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(
    media: SavedMedia,
    chosen: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        when (media.kind) {
            MediaKind.AUDIO -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = media.name,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
            MediaKind.VIDEO -> VideoThumbnail(media)
            MediaKind.PHOTO -> AsyncImage(
                model = media.uri,
                contentDescription = media.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (media.kind == MediaKind.VIDEO) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(30.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Text(
            formatBytes(media.size),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )

        if (chosen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
            )
        }
    }
}

/**
 * Coil cannot decode a video frame without the extra video artifact, so the first frame is
 * pulled here. A failed decode falls back to the tile background and the play badge.
 */
@Composable
private fun VideoThumbnail(media: SavedMedia) {
    val context = LocalContext.current
    var frame by remember(media.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(media.uri) {
        frame = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, media.uri)
                    retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    retriever.release()
                }
            }.getOrNull()
        }
    }
    frame?.let {
        androidx.compose.foundation.Image(
            bitmap = it.asImageBitmap(),
            contentDescription = media.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "Clear selection")
        }
        Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onShare) {
            Icon(Icons.Filled.Share, contentDescription = "Share")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaPreviewDialog(
    items: List<SavedMedia>,
    initialPage: Int,
    onDismiss: () -> Unit,
    onShare: (SavedMedia) -> Unit,
    onDelete: (SavedMedia) -> Unit,
) {
    val pager = rememberPagerState(
        initialPage = initialPage.coerceIn(0, items.lastIndex),
        pageCount = { items.size },
    )
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { zoomed = false }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !zoomed,
            ) { page ->
                val media = items[page]
                if (media.kind == MediaKind.PHOTO) {
                    ZoomableImage(
                        media = media,
                        onZoomChanged = { active ->
                            if (page == pager.currentPage) zoomed = active
                        },
                    )
                } else {
                    VideoSurface(media)
                }
            }

            val current = items[pager.currentPage.coerceIn(0, items.lastIndex)]
            val duration = rememberMediaDuration(current)

            if (items.size > 1) {
                Text(
                    "${pager.currentPage + 1} / ${items.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp),
                )
            }

            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onShare(current) }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White)
                }
                IconButton(onClick = { onDelete(current) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color.White)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            ) {
                Text(
                    current.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (duration != null) {
                    Text(
                        formatDuration(duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(
    media: SavedMedia,
    onZoomChanged: (Boolean) -> Unit = {},
) {
    var scale by remember(media.uri) { mutableStateOf(1f) }
    var offsetX by remember(media.uri) { mutableStateOf(0f) }
    var offsetY by remember(media.uri) { mutableStateOf(0f) }

    LaunchedEffect(media.uri) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        onZoomChanged(false)
    }
    LaunchedEffect(scale) { onZoomChanged(scale > 1f) }

    AsyncImage(
        model = media.uri,
        contentDescription = media.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(media.uri) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        val zoomChange = event.calculateZoom()
                        val pan = event.calculatePan()
                        // At normal size a one finger drag belongs to the pager, so only a
                        // pinch or a pan while zoomed consumes the gesture.
                        if (pressed > 1 || scale > 1f) {
                            scale = (scale * zoomChange).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                            event.changes.forEach { if (it.pressed) it.consume() }
                        }
                    }
                }
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY,
            ),
    )
}

/** The player's own controls show a countdown, so the preview also labels the full length. */
@Composable
private fun rememberMediaDuration(media: SavedMedia): Long? {
    val context = LocalContext.current
    var duration by remember(media.uri) { mutableStateOf<Long?>(null) }
    LaunchedEffect(media.uri, media.kind) {
        duration = if (media.kind == MediaKind.PHOTO) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, media.uri)
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull()
                            ?.takeIf { it > 0L }
                    } finally {
                        retriever.release()
                    }
                }.getOrNull()
            }
        }
    }
    return duration
}

@Composable
private fun VideoSurface(media: SavedMedia) {
    val context = LocalContext.current
    var view by remember { mutableStateOf<VideoView?>(null) }
    AndroidView(
        factory = {
            VideoView(context).apply {
                val controller = MediaController(context)
                controller.setAnchorView(this)
                setMediaController(controller)
                setVideoURI(media.uri)
                setOnPreparedListener { player ->
                    player.isLooping = false
                    start()
                }
                view = this
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
    DisposableEffect(media.uri) {
        onDispose { view?.stopPlayback() }
    }
}

private fun share(context: android.content.Context, media: SavedMedia) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = MimeTypes.forKind(media.name, media.kind)
        putExtra(Intent.EXTRA_STREAM, media.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share")) }
        .onFailure { Toast.makeText(context, "No app can share this file", Toast.LENGTH_SHORT).show() }
}

private fun shareSelection(context: android.content.Context, media: Set<SavedMedia>) {
    val uris = ArrayList(media.map { it.uri })
    if (uris.isEmpty()) return
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share ${uris.size} files")) }
        .onFailure { Toast.makeText(context, "No app can share these files", Toast.LENGTH_SHORT).show() }
}

private fun deleteSelection(context: android.content.Context, media: Set<SavedMedia>) {
    var deleted = 0
    media.forEach { if (Library.delete(context, it)) deleted++ }
    Toast.makeText(context, "Deleted $deleted file(s)", Toast.LENGTH_SHORT).show()
}
