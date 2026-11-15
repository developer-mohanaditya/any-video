package io.anyvideo.downloader.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.anyvideo.downloader.data.MimeTypes
import io.anyvideo.downloader.data.SavedMedia
import kotlinx.coroutines.delay

private val PlaybackSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val SleepChoices = listOf(0, 15, 30, 60)

/**
 * Full-screen player for saved video and audio. The player owns playback, while the surrounding
 * download screen keeps ownership of the gallery item so share and delete stay consistent.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullscreenPlayer(
    media: SavedMedia,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    pipMode: Boolean = false,
) {
    val context = LocalContext.current
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var sleepMinutes by remember { mutableIntStateOf(0) }
    var subtitles by remember { mutableStateOf(false) }
    var audioOnly by remember { mutableStateOf(media.kind == io.anyvideo.downloader.data.MediaKind.AUDIO) }
    var controlsVisible by remember { mutableStateOf(true) }
    var moreOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun applySpeed(value: Float) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                player?.playbackParams = PlaybackParams().setSpeed(value)
            }
        }
    }

    LaunchedEffect(playing, controlsVisible, pipMode) {
        if (playing && controlsVisible && !pipMode) {
            delay(3500)
            controlsVisible = false
        }
    }

    LaunchedEffect(pipMode) {
        controlsVisible = !pipMode
    }

    LaunchedEffect(videoView) {
        while (videoView != null) {
            val view = videoView ?: break
            runCatching { position = view.currentPosition.toLong() }
            runCatching { duration = view.duration.toLong() }
            runCatching { playing = view.isPlaying }
            delay(400)
        }
    }

    LaunchedEffect(sleepMinutes) {
        if (sleepMinutes > 0) {
            delay(sleepMinutes * 60_000L)
            videoView?.pause()
            playing = false
            sleepMinutes = 0
        }
    }

    DisposableEffect(media.uri) {
        onDispose {
            runCatching { videoView?.stopPlayback() }
            runCatching { player?.release() }
        }
    }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { controlsVisible = !controlsVisible } },
    ) {
            key(media.uri) {
                AndroidView(
                    factory = { viewContext ->
                        VideoView(viewContext).apply {
                            setOnPreparedListener { preparedPlayer ->
                                player = preparedPlayer
                                prepared = true
                                duration = preparedPlayer.duration.toLong()
                                runCatching {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        preparedPlayer.playbackParams = PlaybackParams().setSpeed(speed)
                                    }
                                }
                                start()
                                playing = true
                            }
                            setOnCompletionListener {
                                playing = false
                                controlsVisible = true
                            }
                            setOnErrorListener { _, _, _ ->
                                Toast.makeText(context, "This file cannot be played", Toast.LENGTH_LONG).show()
                                true
                            }
                            setVideoURI(media.uri)
                        }.also { videoView = it }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (audioOnly) 0f else 1f),
                )
            }

            if (audioOnly) {
                AudioBackdrop(media)
            }

            AnimatedVisibility(
                visible = controlsVisible && !pipMode,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f))) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                        Text(
                            if (media.kind == io.anyvideo.downloader.data.MediaKind.AUDIO) "AUDIO" else "VIDEO",
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp,
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.14f), CircleShape)
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                        )
                        Box(modifier = Modifier.weight(1f))
                        IconButton(onClick = onShare) {
                            Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White)
                        }
                        Box {
                            IconButton(onClick = { moreOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Color.White)
                            }
                            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Open with another app") },
                                    onClick = {
                                        moreOpen = false
                                        openExternally(context, media)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = {
                                        moreOpen = false
                                        confirmDelete = true
                                    },
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(62.dp)
                            .background(Color.Black.copy(alpha = 0.42f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        IconButton(
                            onClick = {
                                if (playing) videoView?.pause() else videoView?.start()
                                playing = !playing
                            },
                            modifier = Modifier.size(62.dp),
                        ) {
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            media.name,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.fillMaxWidth().basicMarquee(),
                        )
                        Text(
                            "${if (media.kind == io.anyvideo.downloader.data.MediaKind.AUDIO) "Audio" else "Saved video"} · ${formatDuration(duration)}",
                            color = Color.White.copy(alpha = 0.68f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                        )
                        Slider(
                            value = position.coerceIn(0L, duration.coerceAtLeast(1L)).toFloat(),
                            onValueChange = { value ->
                                position = value.toLong()
                                videoView?.seekTo(value.toInt())
                            },
                            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                            ),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(formatDuration(position), color = Color.White.copy(alpha = 0.78f), fontSize = 10.sp)
                            Text(
                                "-${formatDuration((duration - position).coerceAtLeast(0L))}",
                                color = Color.White.copy(alpha = 0.78f),
                                fontSize = 10.sp,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ControlButton(Icons.Filled.Replay10, "Back 10 seconds") {
                                videoView?.seekTo((position - 10_000L).coerceAtLeast(0L).toInt())
                            }
                            ControlButton(Icons.Filled.Forward10, "Forward 10 seconds") {
                                videoView?.seekTo((position + 10_000L).coerceAtMost(duration).toInt())
                            }
                            Text(
                                "${speed}×",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(Color.White.copy(alpha = 0.14f), CircleShape)
                                    .padding(horizontal = 9.dp, vertical = 6.dp)
                                    .pointerInput(Unit) {
                                        detectTapGestures {
                                            val next = PlaybackSpeeds[(PlaybackSpeeds.indexOf(speed) + 1) % PlaybackSpeeds.size]
                                            speed = next
                                            applySpeed(next)
                                        }
                                    },
                            )
                            ControlButton(Icons.Filled.Bedtime, "Sleep timer") {
                                val index = SleepChoices.indexOf(sleepMinutes)
                                sleepMinutes = SleepChoices[(index + 1) % SleepChoices.size]
                                val label = if (sleepMinutes == 0) "Sleep timer off" else "Sleep timer: $sleepMinutes min"
                                Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
                            }
                            ControlButton(Icons.Filled.Subtitles, "Subtitles") {
                                subtitles = !subtitles
                                if (subtitles) {
                                    Toast.makeText(context, "No subtitle track in this file", Toast.LENGTH_SHORT).show()
                                }
                            }
                            ControlButton(Icons.Filled.OpenInNew, "Open with another app") {
                                openExternally(context, media)
                            }
                            ControlButton(Icons.Filled.PictureInPictureAlt, "Picture in picture") {
                                enterPip(context)
                            }
                            ControlButton(Icons.Filled.Headphones, "Audio only") {
                                audioOnly = !audioOnly
                            }
                        }
                    }
                }
            }

            if (controlsVisible && !prepared) {
                Text(
                    "Preparing player",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("Delete this file?") },
                    text = { Text("It will be removed from the gallery.") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmDelete = false
                            onDelete()
                        }) { Text("Delete") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                    },
                )
            }
        }
}

@Composable
private fun AudioBackdrop(media: SavedMedia) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF15151F), Color(0xFF2A2140), Color(0xFF0B0B12)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Headphones,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.22f),
            modifier = Modifier.size(118.dp),
        )
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(34.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(19.dp),
        )
    }
}

private fun openExternally(context: Context, media: SavedMedia) {
    val type = MimeTypes.of(media.name)
        ?: if (media.kind == io.anyvideo.downloader.data.MediaKind.AUDIO) "audio/*" else "video/*"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(media.uri, type)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Open with")) }
        .onFailure { Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show() }
}

private fun enterPip(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        Toast.makeText(context, "Picture in picture needs Android 8 or newer", Toast.LENGTH_SHORT).show()
        return
    }
    val activity = context.findActivity() ?: return
    runCatching {
        activity.enterPictureInPictureMode(
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build(),
        )
    }.onFailure {
        Toast.makeText(context, "Picture in picture is unavailable", Toast.LENGTH_SHORT).show()
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
