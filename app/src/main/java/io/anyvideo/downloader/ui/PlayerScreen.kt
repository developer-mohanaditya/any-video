package io.anyvideo.downloader.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.anyvideo.downloader.data.CaptionCue
import io.anyvideo.downloader.data.CaptionFile
import io.anyvideo.downloader.data.CaptionParser
import io.anyvideo.downloader.data.CaptionStore
import io.anyvideo.downloader.data.MimeTypes
import io.anyvideo.downloader.data.SavedMedia
import io.anyvideo.downloader.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

private val PlaybackSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val SleepChoices = listOf(0, 15, 30, 60)
private val CaptionSizes: List<Pair<String, TextUnit>> = listOf(
    "Small" to 14.sp,
    "Medium" to 16.sp,
    "Large" to 18.sp,
)
private val CaptionBackgrounds: List<Pair<String, Float>> = listOf(
    "Light" to 0.22f,
    "Medium" to 0.42f,
    "Strong" to 0.62f,
)
private val CaptionPositions = listOf("Low", "Normal", "High")
private val OrientationChoices = listOf(
    "auto" to "Auto",
    "portrait" to "Portrait",
    "landscape" to "Landscape",
)

private data class CaptionStyle(
    val size: Int,
    val background: Int,
    val position: Int,
    val lines: Int,
)

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
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
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
    var captionsOn by remember(media.name) { mutableStateOf(false) }
    var captionFiles by remember(media.name) { mutableStateOf(emptyList<CaptionFile>()) }
    var selectedCaption by remember(media.name) { mutableStateOf<CaptionFile?>(null) }
    var captionCues by remember(media.name) {
        mutableStateOf(emptyList<CaptionCue>())
    }
    var captionMenuOpen by remember { mutableStateOf(false) }
    var captionStyle by remember {
        mutableStateOf(
            CaptionStyle(
                size = Prefs.captionSize.coerceIn(0, CaptionSizes.lastIndex),
                background = Prefs.captionBackground.coerceIn(0, CaptionBackgrounds.lastIndex),
                position = Prefs.captionPosition.coerceIn(0, CaptionPositions.lastIndex),
                lines = Prefs.captionLines.coerceIn(1, 3),
            ),
        )
    }
    var orientation by remember { mutableStateOf(Prefs.playerOrientation) }
    var fullscreen by remember { mutableStateOf(false) }
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var captionActionCue by remember { mutableStateOf<CaptionCue?>(null) }
    var audioOnly by remember { mutableStateOf(media.kind == io.anyvideo.downloader.data.MediaKind.AUDIO) }
    var controlsVisible by remember { mutableStateOf(true) }
    var moreOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val playbackHandler = remember { Handler(Looper.getMainLooper()) }
    var lastPlaybackToggleAt by remember { mutableLongStateOf(0L) }
    var lastCenterTapAt by remember { mutableLongStateOf(0L) }
    val currentControlsVisible = rememberUpdatedState(controlsVisible)
    val currentPlaying = rememberUpdatedState(playing)
    val currentPosition = rememberUpdatedState(position)
    val currentDuration = rememberUpdatedState(duration)

    LaunchedEffect(media.name) {
        val files = withContext(Dispatchers.IO) {
            CaptionStore.available(context, media.name)
        }
        captionFiles = files
        selectedCaption = files.firstOrNull()
        if (files.isEmpty()) {
            captionsOn = false
            captionCues = emptyList()
        }
    }

    LaunchedEffect(media.name, selectedCaption) {
        val file = selectedCaption
        if (file == null) {
            captionCues = emptyList()
            return@LaunchedEffect
        }
        val cues = withContext(Dispatchers.IO) { CaptionStore.read(file) }
        captionCues = cues
        if (cues.isNotEmpty()) captionsOn = true
    }

    fun applySpeed(value: Float) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                player?.playbackParams = PlaybackParams().setSpeed(value)
            }
        }
    }

    fun updateCaptionStyle(next: CaptionStyle) {
        captionStyle = next
        Prefs.captionSize = next.size
        Prefs.captionBackground = next.background
        Prefs.captionPosition = next.position
        Prefs.captionLines = next.lines
    }

    fun chooseOrientation(value: String) {
        orientation = value
        Prefs.playerOrientation = value
        fullscreen = false
    }

    LaunchedEffect(orientation, fullscreen) {
        val activity = context.findActivity() ?: return@LaunchedEffect
        applyVideoOrientation(context, orientation, fullscreen)
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(playing, controlsVisible, pipMode, captionMenuOpen, moreOpen) {
        if (playing && controlsVisible && !pipMode && !captionMenuOpen && !moreOpen) {
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

    DisposableEffect(playbackHandler) {
        onDispose { playbackHandler.removeCallbacksAndMessages(null) }
    }

    DisposableEffect(media.uri) {
        val activity = context.findActivity()
        onDispose {
            runCatching { videoView?.stopPlayback() }
            runCatching { player?.release() }
            if (activity != null) {
                runCatching {
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                        .show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }

    DisposableEffect(playing) {
        val window = context.findActivity()?.window
        if (playing) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    BackHandler(onBack = onDismiss)
    val visibleCue = if (captionsOn && !audioOnly && !controlsVisible) {
        CaptionParser.currentCue(captionCues, position)
    } else {
        null
    }
    val latestCue = rememberUpdatedState(visibleCue)

    fun seekBy(deltaMs: Long) {
        val view = videoView
        val from = view?.let { runCatching { it.currentPosition.toLong() }.getOrNull() }
            ?: currentPosition.value
        val durationMs = currentDuration.value.takeIf { it > 0L }
            ?: view?.let { runCatching { it.duration.toLong() }.getOrNull() }
            ?: 0L
        val target = (from + deltaMs).coerceIn(0L, durationMs.coerceAtLeast(0L))
        position = target
        view?.seekTo(target.toInt())
    }

    fun togglePlaybackOnce() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlaybackToggleAt < 400L) return
        lastPlaybackToggleAt = now
        val view = videoView ?: return
        val isPlaying = runCatching { view.isPlaying }.getOrDefault(currentPlaying.value)
        if (isPlaying) view.pause() else view.start()
        playing = !isPlaying
    }

    fun schedulePlaybackToggle() {
        playbackHandler.removeCallbacksAndMessages(null)
        playbackHandler.postDelayed({ togglePlaybackOnce() }, 300L)
    }

    fun commitPlaybackToggle() {
        playbackHandler.removeCallbacksAndMessages(null)
        togglePlaybackOnce()
    }

    fun handleDoubleTap(x: Float, width: Float) {
        if (width <= 0f) return
        when {
            x < width * 0.35f -> seekBy(-10_000L)
            x > width * 0.65f -> seekBy(10_000L)
            else -> commitPlaybackToggle()
        }
    }

    // The video view handles taps and double taps on the picture itself. This catches single
    // taps on the letterboxed area around it so the whole player surface toggles the controls.
    val letterboxTaps = if (!pipMode) {
        Modifier.pointerInput(controlsVisible) {
            detectTapGestures { controlsVisible = !controlsVisible }
        }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(letterboxTaps),
    ) {
            key(media.uri) {
                AndroidView(
                    factory = { viewContext ->
                        VideoView(viewContext).apply {
                            var downX = 0f
                            var dragging = false
                            val touchSlop = ViewConfiguration.get(viewContext).scaledTouchSlop
                            val gestureDetector = GestureDetector(
                                viewContext,
                                object : GestureDetector.SimpleOnGestureListener() {
                                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                                        val metrics = resources.displayMetrics
                                        val centerSlop = 44f * metrics.density
                                        val location = IntArray(2)
                                        getLocationOnScreen(location)
                                        val screenX = e.x + location[0]
                                        val screenY = e.y + location[1]
                                        val centerTap = currentControlsVisible.value &&
                                            abs(screenX - metrics.widthPixels / 2f) <= centerSlop &&
                                            abs(screenY - metrics.heightPixels / 2f) <= centerSlop
                                        if (centerTap) {
                                            schedulePlaybackToggle()
                                        } else {
                                            controlsVisible = !controlsVisible
                                        }
                                        return true
                                    }

                                    override fun onDoubleTap(e: MotionEvent): Boolean {
                                        val location = IntArray(2)
                                        getLocationOnScreen(location)
                                        handleDoubleTap(
                                            e.x + location[0],
                                            resources.displayMetrics.widthPixels.toFloat(),
                                        )
                                        return true
                                    }
                                },
                            )
                            setOnTouchListener { view, event ->
                                gestureDetector.onTouchEvent(event)
                                when (event.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> {
                                        downX = event.x
                                        dragging = false
                                    }
                                    MotionEvent.ACTION_MOVE -> {
                                        if (abs(event.x - downX) > touchSlop) dragging = true
                                    }
                                    MotionEvent.ACTION_UP -> {
                                        val dx = event.x - downX
                                        if (dragging && abs(dx) > view.width * 0.12f) {
                                            if (dx < 0) onNext?.invoke() else onPrevious?.invoke()
                                        }
                                    }
                                }
                                true
                            }
                            setOnPreparedListener { preparedPlayer ->
                                player = preparedPlayer
                                prepared = true
                                duration = preparedPlayer.duration.toLong()
                                videoWidth = preparedPlayer.videoWidth
                                videoHeight = preparedPlayer.videoHeight
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
                modifier = Modifier.zIndex(2f),
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
                        IconButton(onClick = { fullscreen = !fullscreen }) {
                            Icon(
                                if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                contentDescription = if (fullscreen) "Exit full screen" else "Full screen",
                                tint = Color.White,
                            )
                        }
                        Box {
                            IconButton(onClick = { moreOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Color.White)
                            }
                            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Orientation") },
                                    onClick = {},
                                    enabled = false,
                                )
                                OrientationChoices.forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text((if (orientation == value) "✓ " else "") + label)
                                        },
                                        onClick = {
                                            moreOpen = false
                                            chooseOrientation(value)
                                        },
                                    )
                                }
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
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastCenterTapAt < 300L) {
                                    lastCenterTapAt = 0L
                                    commitPlaybackToggle()
                                } else {
                                    lastCenterTapAt = now
                                    schedulePlaybackToggle()
                                }
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
                        SlimSeekBar(
                            position = position,
                            duration = duration,
                            onSeek = { value ->
                                position = value
                                videoView?.seekTo(value.toInt())
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
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
                            Box {
                                ControlButton(
                                    Icons.Filled.Subtitles,
                                    if (captionsOn) "Captions on" else "Captions",
                                ) { captionMenuOpen = true }
                                CaptionMenu(
                                    expanded = captionMenuOpen,
                                    files = captionFiles,
                                    selected = selectedCaption,
                                    captionsOn = captionsOn,
                                    style = captionStyle,
                                    onDismiss = { captionMenuOpen = false },
                                    onToggle = {
                                        captionsOn = !captionsOn
                                        captionMenuOpen = false
                                    },
                                    onSelect = { file ->
                                        selectedCaption = file
                                        captionsOn = true
                                        captionMenuOpen = false
                                    },
                                    onStyle = { updateCaptionStyle(it) },
                                )
                            }
                            ControlButton(Icons.Filled.OpenInNew, "Open with another app") {
                                openExternally(context, media)
                            }
                            ControlButton(Icons.Filled.PictureInPictureAlt, "Picture in picture") {
                                enterPip(context, videoWidth, videoHeight)
                            }
                            ControlButton(Icons.Filled.Headphones, "Audio only") {
                                audioOnly = !audioOnly
                            }
                        }
                    }
                }
            }

            visibleCue?.let { cue ->
                // The caption sits above the video but below the controls, so it never takes
                // Pause, Back or Full screen taps while the controls are visible.
                val bottom = when {
                    pipMode -> 10.dp
                    else -> when (captionStyle.position) {
                        0 -> 16.dp
                        1 -> 30.dp
                        else -> 58.dp
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(1f)
                        .fillMaxWidth(if (pipMode) 0.94f else 0.9f)
                        .navigationBarsPadding()
                        .padding(start = 12.dp, end = 12.dp, bottom = bottom),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        cue.text,
                        color = Color.White,
                        fontSize = if (pipMode) 12.sp else CaptionSizes[captionStyle.size].second,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = if (pipMode) 2 else captionStyle.lines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .pointerInput(pipMode) {
                                detectTapGestures(
                                    onTap = { controlsVisible = true },
                                    onLongPress = {
                                        if (!pipMode) {
                                            captionActionCue = latestCue.value
                                        }
                                    },
                                )
                            }
                            .background(
                                Color.Black.copy(
                                    alpha = if (pipMode) 0.55f
                                    else CaptionBackgrounds[captionStyle.background].second,
                                ),
                                RoundedCornerShape(10.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
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

            captionActionCue?.let { cue ->
                AlertDialog(
                    onDismissRequest = { captionActionCue = null },
                    title = { Text("Caption at ${formatDuration(cue.startMs)}") },
                    text = { Text(cue.text) },
                    confirmButton = {
                        Row {
                            TextButton(onClick = {
                                shareCaption(context, cue)
                                captionActionCue = null
                            }) { Text("Share") }
                            TextButton(onClick = {
                                copyCaption(context, cue)
                                captionActionCue = null
                            }) { Text("Copy") }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { captionActionCue = null }) { Text("Cancel") }
                    },
                    properties = DialogProperties(dismissOnClickOutside = false),
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

@Composable
private fun CaptionMenu(
    expanded: Boolean,
    files: List<CaptionFile>,
    selected: CaptionFile?,
    captionsOn: Boolean,
    style: CaptionStyle,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    onSelect: (CaptionFile) -> Unit,
    onStyle: (CaptionStyle) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (files.isEmpty()) {
            DropdownMenuItem(text = { Text("No subtitles") }, onClick = onDismiss)
        } else {
            DropdownMenuItem(
                text = { Text(if (captionsOn) "Turn captions off" else "Turn captions on") },
                onClick = onToggle,
            )
            files.forEach { file ->
                DropdownMenuItem(
                    text = {
                        Text((if (selected == file) "✓ " else "") + file.label)
                    },
                    onClick = { onSelect(file) },
                )
            }
            DropdownMenuItem(
                text = { Text("Size: ${CaptionSizes[style.size].first}") },
                onClick = {
                    onStyle(style.copy(size = (style.size + 1) % CaptionSizes.size))
                },
            )
            DropdownMenuItem(
                text = { Text("Background: ${CaptionBackgrounds[style.background].first}") },
                onClick = {
                    onStyle(style.copy(background = (style.background + 1) % CaptionBackgrounds.size))
                },
            )
            DropdownMenuItem(
                text = { Text("Position: ${CaptionPositions[style.position]}") },
                onClick = {
                    onStyle(style.copy(position = (style.position + 1) % CaptionPositions.size))
                },
            )
            DropdownMenuItem(
                text = { Text("Lines: ${style.lines}") },
                onClick = {
                    onStyle(style.copy(lines = if (style.lines >= 3) 1 else style.lines + 1))
                },
            )
        }
    }
}

@Composable
private fun SlimSeekBar(
    position: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var width by remember { mutableIntStateOf(1) }
    val max = duration.coerceAtLeast(1L)
    val fraction = (position.coerceIn(0L, max).toFloat() / max).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .height(22.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(max, width) {
                detectTapGestures { offset ->
                    onSeek((offset.x / width * max).toLong().coerceIn(0L, max))
                }
            }
            .pointerInput(max, width) {
                detectHorizontalDragGestures { change, _ ->
                    onSeek((change.position.x / width * max).toLong().coerceIn(0L, max))
                    change.consume()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2f
            val thickness = 2.dp.toPx()
            drawLine(
                color = Color.White.copy(alpha = 0.22f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = thickness,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.White.copy(alpha = 0.94f),
                start = Offset(0f, y),
                end = Offset(size.width * fraction, y),
                strokeWidth = thickness,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = Color.White,
                radius = 4.dp.toPx(),
                center = Offset(size.width * fraction, y),
            )
        }
    }
}

private fun applyVideoOrientation(
    context: Context,
    mode: String,
    fullscreen: Boolean,
) {
    val activity = context.findActivity() ?: return
    val target = when {
        // Auto follows the phone, not the video. A horizontal clip therefore keeps the current
        // orientation until the user asks for full screen.
        fullscreen -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        mode == "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        mode == "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    if (activity.requestedOrientation != target) activity.requestedOrientation = target
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

private fun enterPip(context: Context, width: Int, height: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        Toast.makeText(context, "Picture in picture needs Android 8 or newer", Toast.LENGTH_SHORT).show()
        return
    }
    val activity = context.findActivity() ?: return
    runCatching {
        activity.enterPictureInPictureMode(
            PictureInPictureParams.Builder()
                .setAspectRatio(pipAspectRatio(width, height))
                .build(),
        )
    }.onFailure {
        Toast.makeText(context, "Picture in picture is unavailable", Toast.LENGTH_SHORT).show()
    }
}

private fun pipAspectRatio(width: Int, height: Int): Rational {
    if (width <= 0 || height <= 0) return Rational(16, 9)
    val ratio = width.toDouble() / height
    return when {
        ratio > 2.39 -> Rational(239, 100)
        ratio < 1.0 / 2.39 -> Rational(100, 239)
        else -> Rational(width, height)
    }
}

private fun copyCaption(context: Context, cue: CaptionCue) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Caption", cue.text))
    Toast.makeText(context, "Caption copied", Toast.LENGTH_SHORT).show()
}

private fun shareCaption(context: Context, cue: CaptionCue) {
    val text = "[${formatDuration(cue.startMs)}] ${cue.text}"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Share caption")) }
        .onFailure {
            Toast.makeText(context, "No app can share this caption", Toast.LENGTH_SHORT).show()
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
