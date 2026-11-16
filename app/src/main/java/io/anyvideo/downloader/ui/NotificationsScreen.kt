package io.anyvideo.downloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.anyvideo.downloader.download.Downloader

/**
 * Download activity for the current app session, opened from the home notification bell.
 *
 * The gallery in Downloads only contains files that reached MediaStore. This screen is the
 * complementary view: live transfers, files that finished this session, and failures that did
 * not make it to the gallery.
 */
@Composable
fun NotificationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val tasks by Downloader.tasks.collectAsState()
    val active = tasks.filter {
        it.state == Downloader.State.QUEUED ||
            it.state == Downloader.State.RUNNING ||
            it.state == Downloader.State.PAUSED
    }
    val session = tasks
        .filter { it.state == Downloader.State.DONE || it.state == Downloader.State.FAILED }
        .asReversed()
    val saved = session.count { it.state == Downloader.State.DONE }
    val failed = session.count { it.state == Downloader.State.FAILED }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Notifications",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "This session's downloads",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (session.isNotEmpty()) {
                TextButton(onClick = { Downloader.clearFinished() }) {
                    Text("Clear")
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 15.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (active.isNotEmpty() || session.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Pill(
                            "${active.size} active",
                            MaterialTheme.colorScheme.primary,
                            horizontalPadding = 10.dp,
                            verticalPadding = 6.dp,
                        )
                        if (failed > 0) {
                            Pill(
                                "$failed failed",
                                MaterialTheme.colorScheme.error,
                                horizontalPadding = 10.dp,
                                verticalPadding = 6.dp,
                            )
                        }
                        if (saved > 0) {
                            Pill(
                                "$saved saved",
                                MaterialTheme.colorScheme.secondary,
                                horizontalPadding = 10.dp,
                                verticalPadding = 6.dp,
                            )
                        }
                    }
                }
            }

            item {
                SectionHead(
                    title = "Active downloads",
                    detail = if (active.isEmpty()) "Nothing running" else "${active.size} active",
                )
            }

            if (active.isEmpty()) {
                item { EmptyActiveCard() }
            } else {
                items(active, key = { it.id }) { task ->
                    ActiveDownloadCard(
                        task = task,
                        onPause = { Downloader.pause(task.id) },
                        onResume = { Downloader.resume(context, task.id) },
                        onStart = { Downloader.start(context, task.id) },
                        onCancel = {
                            Downloader.cancel(task.id)
                            Downloader.remove(task.id)
                        },
                    )
                }
            }

            if (session.isNotEmpty()) {
                item {
                    SectionHead(
                        title = "This session",
                        detail = "${session.size} ${if (session.size == 1) "item" else "items"}",
                        modifier = Modifier.padding(top = 7.dp),
                    )
                }
                items(session, key = { it.id }) { task ->
                    SessionDownloadCard(
                        task = task,
                        onRetry = { Downloader.retry(context, task.id) },
                    )
                }
            } else if (active.isEmpty()) {
                item {
                    Text(
                        "Nothing has finished in this session yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHead(title: String, detail: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyActiveCard() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "No active downloads",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Downloads you start from Home or a link will show their progress here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ActiveDownloadCard(
    task: Downloader.Task,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val percent = (task.progress * 100).toInt().coerceIn(0, 100)
    var menuOpen by remember(task.id) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(13.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                FileBadge(task, size = 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${sourceLabel(task)} · ${Downloader.kindLabel(task.fileName, task.isVideo)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(7.dp))
                Pill(
                    when (task.state) {
                        Downloader.State.QUEUED -> "Queued"
                        Downloader.State.PAUSED -> "Paused"
                        else -> "$percent%"
                    },
                    when (task.state) {
                        Downloader.State.QUEUED -> MaterialTheme.colorScheme.tertiary
                        Downloader.State.PAUSED -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
            }

            if (task.state != Downloader.State.QUEUED) {
                Spacer(Modifier.height(12.dp))
                if (task.total > 0L) {
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.fillMaxWidth().height(5.dp),
                    )
                } else if (task.state == Downloader.State.RUNNING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp))
                } else {
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.fillMaxWidth().height(5.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    progressText(task),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ActionIcon(
                    icon = when (task.state) {
                        Downloader.State.RUNNING -> Icons.Filled.Pause
                        else -> Icons.Filled.PlayArrow
                    },
                    description = when (task.state) {
                        Downloader.State.RUNNING -> "Pause"
                        Downloader.State.PAUSED -> "Resume"
                        else -> "Start"
                    },
                    onClick = when (task.state) {
                        Downloader.State.RUNNING -> onPause
                        Downloader.State.PAUSED -> onResume
                        else -> onStart
                    },
                )
                Spacer(Modifier.width(4.dp))
                Box {
                    ActionIcon(
                        icon = Icons.Filled.MoreVert,
                        description = "More options",
                        onClick = { menuOpen = true },
                    )
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Cancel download") },
                            onClick = {
                                menuOpen = false
                                onCancel()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionDownloadCard(task: Downloader.Task, onRetry: () -> Unit) {
    val done = task.state == Downloader.State.DONE
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileBadge(task, size = 34.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        sessionMeta(task),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (done) {
                    Pill("Saved", MaterialTheme.colorScheme.secondary)
                } else {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 9.dp),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.error.copy(alpha = 0.55f),
                        ),
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Retry",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (!done) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                ) {
                    Text(
                        task.error?.take(180).orEmpty().ifBlank { "Download failed" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun FileBadge(task: Downloader.Task, size: androidx.compose.ui.unit.Dp) {
    val audio = Downloader.isAudio(task.fileName)
    val icon: ImageVector = when {
        task.isVideo -> Icons.Filled.Movie
        audio -> Icons.Filled.MusicNote
        else -> Icons.Filled.Image
    }
    val color = when {
        task.state == Downloader.State.FAILED -> MaterialTheme.colorScheme.error
        task.isVideo -> MaterialTheme.colorScheme.primary
        audio -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    Box(
        modifier = Modifier
            .size(size)
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun Pill(
    text: String,
    color: Color,
    horizontalPadding: Dp = 8.dp,
    verticalPadding: Dp = 5.dp,
) {
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
        contentColor = color,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
            maxLines = 1,
        )
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun progressText(task: Downloader.Task): String = when (task.state) {
    Downloader.State.QUEUED -> "Waiting to start"
    Downloader.State.PAUSED -> {
        val percent = (task.progress * 100).toInt().coerceIn(0, 100)
        "Paused · $percent%"
    }
    Downloader.State.RUNNING -> when {
        task.total > 0L ->
            "${(task.progress * 100).toInt()}% · ${formatBytes(task.received)} of ${formatBytes(task.total)}"
        task.received > 0L -> "${formatBytes(task.received)} downloaded"
        else -> "Starting…"
    }
    else -> task.error?.take(80).orEmpty().ifBlank { "Download failed" }
}

private fun sessionMeta(task: Downloader.Task): String = listOfNotNull(
    sourceLabel(task),
    Downloader.kindLabel(task.fileName, task.isVideo),
    if (task.state == Downloader.State.DONE) formatBytes(task.received) else null,
).joinToString(" · ")

private fun sourceLabel(task: Downloader.Task): String {
    val source = task.sourceUrl.orEmpty()
    return when {
        source.contains("instagram", ignoreCase = true) -> "Instagram"
        source.contains("tiktok", ignoreCase = true) -> "TikTok"
        source.contains("youtube", ignoreCase = true) || source.contains("youtu.be", ignoreCase = true) -> "YouTube"
        source.contains("vimeo", ignoreCase = true) -> "Vimeo"
        source.contains("dailymotion", ignoreCase = true) -> "Dailymotion"
        source.contains("reddit", ignoreCase = true) -> "Reddit"
        source.contains("snapchat", ignoreCase = true) -> "Snapchat"
        source.contains("likee", ignoreCase = true) -> "Likee"
        source.contains("pinterest", ignoreCase = true) -> "Pinterest"
        source.contains("facebook", ignoreCase = true) || source.contains("fb.watch", ignoreCase = true) -> "Facebook"
        source.contains("twitter.com", ignoreCase = true) || source.contains("x.com", ignoreCase = true) -> "X"
        else -> "Download"
    }
}
