package io.anyvideo.downloader.ui

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.anyvideo.downloader.util.MediaTrimmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ToneKind(
    val label: String,
    val subtitle: String,
    val target: MediaTrimmer.AudioTarget,
) {
    RINGTONE("Ringtone", "Save this clip as the default ringtone", MediaTrimmer.AudioTarget.RINGTONE),
    NOTIFICATION("Notification sound", "Use the selected clip for messages and alerts", MediaTrimmer.AudioTarget.NOTIFICATION),
    ALARM("Alarm", "Use the selected clip as an alarm tone", MediaTrimmer.AudioTarget.ALARM),
}

@Composable
fun RingtoneScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf("") }
    var size by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(1L) }
    var start by remember { mutableLongStateOf(0L) }
    var end by remember { mutableLongStateOf(1L) }
    var playing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(ToneKind.RINGTONE) }
    var saved by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var ready by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            uri = picked
            playing = false
            ready = false
            saved = false
            start = 0L
            end = 1L
        }
    }

    LaunchedEffect(uri) {
        val picked = uri ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, picked)
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?.coerceAtLeast(1L) ?: 1L
                    start = 0L
                    end = duration
                    name = context.contentResolver.query(
                        picked,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                        ?: "audio"
                    size = context.contentResolver.query(
                        picked,
                        arrayOf(android.provider.OpenableColumns.SIZE),
                        null,
                        null,
                        null,
                    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
                } finally {
                    retriever.release()
                }
            }
        }
    }

    DisposableEffect(uri) {
        onDispose {
            runCatching {
                player?.stop()
                player?.release()
            }
            player = null
        }
    }

    LaunchedEffect(uri) {
        val picked = uri ?: return@LaunchedEffect
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        runCatching {
            mediaPlayer.setDataSource(context, picked)
            mediaPlayer.setOnPreparedListener {
                ready = true
                it.seekTo(start.toInt())
            }
            mediaPlayer.prepareAsync()
        }
    }

    LaunchedEffect(playing, start, end) {
        while (playing) {
            val mediaPlayer = player
            if (mediaPlayer != null) {
                if (mediaPlayer.currentPosition >= end) {
                    mediaPlayer.seekTo(start.toInt())
                }
                if (!mediaPlayer.isPlaying) {
                    runCatching { mediaPlayer.start() }
                }
            }
            delay(180)
        }
    }

    ScreenScaffold(
        title = "Ringtone maker",
        onBack = onBack,
    ) { modifier ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            if (uri == null) {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(42.dp),
                        )
                        Text(
                            "Choose audio first",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Text(
                            "Trim a downloaded song or recording into a ringtone, notification or alarm tone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 5.dp, bottom = 16.dp),
                        )
                        Button(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("Choose audio") }
                    }
                }
                return@Column
            }

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(9.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f).padding(start = 9.dp)) {
                            Text(
                                name,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "Music/anyvideo · ${formatDuration(duration)} · ${formatBytes(size)}",
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = {
                                val mediaPlayer = player ?: return@IconButton
                                if (!ready) return@IconButton
                                if (playing) {
                                    mediaPlayer.pause()
                                    playing = false
                                } else {
                                    if (mediaPlayer.currentPosition !in start.toInt()..end.toInt()) {
                                        mediaPlayer.seekTo(start.toInt())
                                    }
                                    mediaPlayer.start()
                                    playing = true
                                }
                            },
                        ) {
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        repeat(36) { index ->
                            val height = 18 + ((index * 37) % 66)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(height / 100f)
                                    .align(Alignment.CenterVertically)
                                    .background(
                                        if (index % 4 == 0) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                                        RoundedCornerShape(2.dp),
                                    ),
                            )
                        }
                    }

                    RangeSlider(
                        value = start.toFloat()..end.toFloat(),
                        onValueChange = { range ->
                            start = range.start.toLong().coerceIn(0L, duration - 1L)
                            end = range.endInclusive.toLong().coerceIn(start + 1L, duration)
                            if (playing) player?.seekTo(start.toInt())
                        },
                        valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(formatDuration(start), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${formatDuration((end - start).coerceAtLeast(0L))} selected",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(formatDuration(end), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = {
                                val position = player?.currentPosition?.toLong() ?: return@TextButton
                                start = position.coerceIn(0L, end - 1L)
                                player?.seekTo(start.toInt())
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Set start") }
                        TextButton(
                            onClick = {
                                val position = player?.currentPosition?.toLong() ?: return@TextButton
                                end = position.coerceIn(start + 1L, duration)
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Set end") }
                    }
                }
            }

            Text(
                "Tone type",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
            )
            ToneKind.entries.forEach { option ->
                val selected = kind == option
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable {
                            kind = option
                            saved = false
                        },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            when (option) {
                                ToneKind.RINGTONE -> Icons.Filled.MusicNote
                                ToneKind.NOTIFICATION -> Icons.Filled.Notifications
                                ToneKind.ALARM -> Icons.Filled.Alarm
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(option.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                                option.subtitle,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(19.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else Color.Transparent,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                    }
                }
            }

            Text(
                "The clip is trimmed first, then saved to the matching system folder. Android may ask you to confirm it as the default sound.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(11.dp),
            )

            Button(
                onClick = {
                    val picked = uri ?: return@Button
                    saving = true
                    saved = false
                    scope.launch {
                        val result = MediaTrimmer.trimAudio(context, picked, start, end, kind.target)
                        saving = false
                        result.onSuccess {
                            saved = true
                            Toast.makeText(
                                context,
                                "Saved to ${kind.target.folder.substringBefore('/')}",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }.onFailure {
                            Toast.makeText(
                                context,
                                it.message ?: "This audio format cannot be trimmed",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                enabled = !saving && end > start,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            ) {
                Icon(
                    if (saved) Icons.Filled.Check else Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    when {
                        saving -> "Saving tone"
                        saved -> "Saved ${kind.label.lowercase()}"
                        else -> "Save ${formatDuration((end - start).coerceAtLeast(0L))} ${kind.label.lowercase()}"
                    },
                    modifier = Modifier.padding(start = 7.dp),
                )
            }

            if (saved) {
                TextButton(
                    onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open sound settings") }
            }
            Text(
                "Works offline · choose the range before saving",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 20.dp),
            )
        }
    }
}
