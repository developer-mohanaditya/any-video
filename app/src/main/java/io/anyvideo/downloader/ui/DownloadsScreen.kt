package io.anyvideo.downloader.ui

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
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

    val tasks by Downloader.tasks.collectAsState()
    val active = tasks.filter { it.state == Downloader.State.QUEUED || it.state == Downloader.State.RUNNING }
    val doneCount = tasks.count { it.state == Downloader.State.DONE }

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
                    onDelete = {
                        deleteSelection(context, selection)
                        selection = emptySet()
                        refreshKey++
                    },
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
            if (active.isNotEmpty()) {
                ActiveDownloads(active)
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
                        previewItems = items
                        previewStart = items.indexOfFirst { it.uri == media.uri }.coerceAtLeast(0)
                        previewOpen = true
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
            onDelete = { media ->
                Library.delete(context, media)
                previewOpen = false
                refreshKey++
            },
        )
    }
}

@Composable
private fun ActiveDownloads(tasks: List<Downloader.Task>) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tasks.take(3).forEach { task ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        task.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    Text(
                        when (task.state) {
                            Downloader.State.QUEUED -> "Queued"
                            Downloader.State.FAILED -> "Failed"
                            else -> "${(task.progress * 100).toInt()}%"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (task.state == Downloader.State.RUNNING) {
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 4.dp),
                    )
                }
            }
        }
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

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
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
