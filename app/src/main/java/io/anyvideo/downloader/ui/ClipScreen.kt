package io.anyvideo.downloader.ui

import android.media.MediaMetadataRetriever
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.anyvideo.downloader.util.MediaTrimmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A range trimmer for saved videos. The preview is deliberately 9:16, matching the concept and
 * giving a phone-shaped frame to a vertical clip before the range is committed.
 */
@Composable
fun ClipScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<android.net.Uri?>(null) }
    var name by remember { mutableStateOf("") }
    var duration by remember { mutableLongStateOf(1L) }
    var start by remember { mutableLongStateOf(0L) }
    var end by remember { mutableLongStateOf(1L) }
    var playing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var videoView by remember { mutableStateOf<VideoView?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            uri = picked
            playing = false
            start = 0L
            end = 0L
        }
    }

    LaunchedEffect(uri) {
        val picked = uri ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, picked)
                    val millis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?.coerceAtLeast(1L) ?: 1L
                    duration = millis
                    start = 0L
                    end = millis
                    name = context.contentResolver.query(
                        picked,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                        ?: "video"
                } finally {
                    retriever.release()
                }
            }
        }
    }

    LaunchedEffect(playing, start, end) {
        while (playing) {
            val view = videoView
            if (view != null && view.currentPosition >= end) {
                view.seekTo(start.toInt())
                view.start()
            }
            delay(180)
        }
    }

    DisposableEffect(uri) {
        onDispose { videoView?.stopPlayback() }
    }

    ScreenScaffold(
        title = "Clip video",
        onBack = onBack,
    ) { modifier ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (uri == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(22.dp))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.ContentCut,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(42.dp),
                    )
                    Text(
                        "Choose a video to trim",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        "The selected range is copied into a new file. The original stays untouched.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 5.dp, bottom = 16.dp),
                    )
                    Button(onClick = { picker.launch(arrayOf("video/*")) }) { Text("Choose video") }
                }
                return@Column
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.68f)
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                key(uri) {
                    AndroidView(
                        factory = { viewContext ->
                            VideoView(viewContext).apply {
                                setOnPreparedListener { prepared ->
                                    runCatching { prepared.seekTo(start.toInt()) }
                                    videoView = this
                                }
                                setVideoURI(uri)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { view -> videoView = view },
                    )
                }
                IconButton(
                    onClick = {
                        val view = videoView ?: return@IconButton
                        if (view.isPlaying) {
                            view.pause()
                            playing = false
                        } else {
                            if (view.currentPosition !in start.toInt()..end.toInt()) view.seekTo(start.toInt())
                            view.start()
                            playing = true
                        }
                    },
                    modifier = Modifier
                        .size(54.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(99.dp)),
                ) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = Color.White,
                    )
                }
                Text(
                    "${formatDuration(start)} - ${formatDuration(end)}",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Clip range", fontWeight = FontWeight.SemiBold)
                Text(
                    "${formatDuration((end - start).coerceAtLeast(0L))} selected",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(10.dp)),
            ) {
                repeat(14) { index ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xFF121B36),
                                        Color(0xFF4C2A76),
                                        Color(0xFFB483C5).copy(alpha = 0.7f + (index % 3) * 0.1f),
                                    ),
                                ),
                            ),
                    )
                }
            }

            RangeSlider(
                value = start.toFloat()..end.toFloat(),
                onValueChange = { range ->
                    start = range.start.toLong().coerceIn(0L, duration - 1L)
                    end = range.endInclusive.toLong().coerceIn(start + 1L, duration)
                    if (videoView?.isPlaying == true) {
                        videoView?.seekTo(start.toInt())
                    }
                },
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatDuration(start), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatDuration(end), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val position = videoView?.currentPosition?.toLong() ?: return@OutlinedButton
                        start = position.coerceIn(0L, end - 1L)
                        videoView?.seekTo(start.toInt())
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Set start here") }
                OutlinedButton(
                    onClick = {
                        val position = videoView?.currentPosition?.toLong() ?: return@OutlinedButton
                        end = position.coerceIn(start + 1L, duration)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Set end here") }
            }

            Button(
                onClick = {
                    val picked = uri ?: return@Button
                    saving = true
                    scope.launch {
                        val result = MediaTrimmer.trimVideo(context, picked, start, end)
                        saving = false
                        result.onSuccess {
                            Toast.makeText(context, "Clip saved to Movies/anyvideo", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            Toast.makeText(
                                context,
                                it.message ?: "This video format cannot be trimmed",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                enabled = !saving && end > start,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(Icons.Filled.ContentCut, contentDescription = null, modifier = Modifier.size(17.dp))
                Text(
                    if (saving) "Saving clip" else "Save clip",
                    modifier = Modifier.padding(start = 7.dp),
                )
            }
            if (name.isNotBlank()) {
                Text(
                    name,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}
