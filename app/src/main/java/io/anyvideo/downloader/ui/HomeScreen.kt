package io.anyvideo.downloader.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.anyvideo.downloader.R
import io.anyvideo.downloader.Screen
import io.anyvideo.downloader.data.MediaCandidate
import io.anyvideo.downloader.download.Downloader
import io.anyvideo.downloader.download.DownloadRegistry
import io.anyvideo.downloader.net.Resolver
import io.anyvideo.downloader.util.Prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    autoUrl: String?,
    onAutoUrlConsumed: () -> Unit,
    onNavigate: (Screen) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var link by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<MediaCandidate>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    /** True when the current card was already queued by the paste, so it needs no button. */
    var autoSaved by remember { mutableStateOf(false) }
    var recent by remember { mutableStateOf(listOf<RecentProfile>()) }
    var pendingYouTube by remember { mutableStateOf<String?>(null) }
    /** The last link we acted on, so auto-save fires once per paste instead of on every keystroke. */
    var handled by remember { mutableStateOf("") }
    /** Pending auto-resolve, cancelled on every edit so typing one URL costs one request. */
    var autoResolve by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        recent = instagramProfilesFrom(DownloadRegistry.recentSources(context, 40))
    }

    /** Every supported link in the text, so a list of URLs queues in one go. */
    fun linksFrom(text: String): List<String> =
        text.split(Regex("\\s+"))
            .map { it.trim().trimEnd(',', ')', ';') }
            .filter { it.startsWith("http", true) && Resolver.isSupported(it) }
            .distinct()

    fun fetch(target: String, auto: Boolean = false) {
        val links = linksFrom(target).ifEmpty { listOf(target.trim()) }.filter { it.isNotBlank() }
        if (links.isEmpty()) {
            message = "Paste a link first."
            return
        }
        if (activity == null) return
        loading = true
        message = null
        scope.launch {
            val found = ArrayList<MediaCandidate>()
            var note: String? = null
            for (url in links) {
                val outcome = Resolver.resolve(activity, url)
                found += outcome.candidates
                if (outcome.candidates.isEmpty() && note == null) note = outcome.note
            }
            loading = false
            // YouTube keeps its Download button: the quality was just chosen, the tap is the
            // confirmation. Everything else saves itself, but still shows what is being saved.
            val autoSave = auto && Prefs.autoDownloadClipboard && !isYouTubeLink(target)
            if (autoSave && found.isNotEmpty()) {
                val queued = Downloader.enqueueAll(context, found)
                results = found
                autoSaved = queued > 0
                message = if (queued > 0) {
                    "Saving $queued file(s) to Pictures, Movies or Music (anyvideo)."
                } else {
                    "Could not start the downloads."
                }
                recent = instagramProfilesFrom(DownloadRegistry.recentSources(context, 40))
            } else {
                results = found
                autoSaved = false
                message = note
            }
        }
    }

    /** Non-YouTube links download at once; YouTube opens the format sheet first. */
    fun submit(text: String) {
        val target = text.trim()
        if (target.isBlank()) {
            message = "Paste a link first."
            return
        }
        if (!hasPostPath(target)) {
            message = "That is a platform home page. Paste a post, reel or profile link."
            return
        }
        if (isYouTubeLink(target)) {
            pendingYouTube = target
        } else {
            // The debounce above already saved this exact link; a tap on the arrow must not
            // enqueue the same carousel a second time.
            if (target == handled && autoSaved) return
            handled = target
            fetch(target, auto = true)
        }
    }

    fun onTextChange(value: String) {
        link = value
        val candidate = value.trim()
        // Every intermediate string while a link is typed out would otherwise resolve on its own,
        // burning the instance's rate-limit budget. Wait for the edit to settle, then resolve once.
        autoResolve?.cancel()
        if (!candidate.startsWith("http", true) ||
            !Resolver.isSupported(candidate) ||
            isYouTubeLink(candidate) ||
            candidate == handled
        ) {
            return
        }
        autoResolve = scope.launch {
            delay(700)
            if (link.trim() == candidate && candidate != handled) {
                handled = candidate
                fetch(candidate, auto = true)
            }
        }
    }

    LaunchedEffect(autoUrl) {
        if (!autoUrl.isNullOrBlank()) {
            link = autoUrl
            submit(autoUrl)
            onAutoUrlConsumed()
        }
    }

    pendingYouTube?.let { target ->
        YouTubePickerDialog(
            onDismiss = { pendingYouTube = null },
            onConfirm = { audio, quality, format, bitrate ->
                Prefs.youtubeAudioOnly = audio
                Prefs.youtubeQuality = quality
                Prefs.youtubeAudioFormat = format
                Prefs.youtubeAudioBitrate = bitrate
                pendingYouTube = null
                handled = target
                fetch(target, auto = true)
            },
        )
    }

    val preview = results.firstOrNull()

    Column(modifier = Modifier.fillMaxSize().animateContentSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher),
                contentDescription = null,
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "AnyVideo",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onNavigate(Screen.SETTINGS) }) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (recent.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(recent) { profile ->
                    RecentProfileBubble(profile) {
                        link = profile.url
                        submit(profile.url)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
        )
        Spacer(Modifier.height(14.dp))
        SupportedAppsRow()

        // Everything between the brand row and the field: the preview card when we have one.
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                preview != null -> BoxWithConstraints {
                PreviewCard(
                    candidate = preview,
                    count = results.size,
                    sourceUrl = link,
                    maxCardHeight = maxHeight,
                    queued = autoSaved,
                    onSave = {
                        val id = Downloader.enqueue(context, preview)
                        Toast.makeText(
                            context,
                            if (id != null) "Download started" else "Could not start the download",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    onSaveAll = {
                        val count = Downloader.enqueueAll(context, results)
                        Toast.makeText(context, "Queued $count download(s)", Toast.LENGTH_SHORT).show()
                    },
                )
                }
                loading -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }

        PasteField(
            link = link,
            loading = loading,
            onTextChange = { onTextChange(it) },
            onSubmit = { submit(link) },
            onPaste = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(context)?.toString()
                if (!text.isNullOrBlank()) onTextChange(text.substringBefore(' ').trim())
            },
        )

        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Text(
                message.orEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Below the field: a smaller share than the space above pulls it lower than dead centre,
        // and keeps it clear of the navigation bar once a card is showing.
        Spacer(Modifier.weight(if (preview != null) 0.2f else 0.65f))
    }
}

/** A link's thumbnail and its save action, sized to the shape of that content. */
@Composable
private fun PreviewCard(
    candidate: MediaCandidate,
    count: Int,
    sourceUrl: String,
    maxCardHeight: Dp,
    queued: Boolean,
    onSave: () -> Unit,
    onSaveAll: () -> Unit,
) {
    val platform = Platform.of(sourceUrl)
    // YouTube always asks; other services only ask when auto-save is off and nothing is queued.
    val needsAction = platform == Platform.YOUTUBE || !queued
    // The platform ratio is only a placeholder while the image loads; once the thumbnail is
    // decoded its own proportions win, so nothing is cropped at the top or bottom.
    var measuredRatio by remember(sourceUrl, candidate.thumbnailUrl) { mutableStateOf<Float?>(null) }
    val ratio = measuredRatio ?: platform?.ratioFor(sourceUrl) ?: if (candidate.isVideo) 1.7778f else 1f
    val footer = if (needsAction) 104.dp else 70.dp
    val maxWidth = 236.dp
    val thumbHeight = minOf(maxWidth / ratio, (maxCardHeight - footer).coerceAtLeast(72.dp))
    val width = thumbHeight * ratio

    Surface(
        modifier = Modifier.width(width).animateContentSize(),
        shape = RoundedCornerShape(22.dp),
        // No elevation: the card floats on colour contrast alone, with no drop shadow.
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(thumbHeight)) {
                val thumb = candidate.thumbnailUrl ?: fallbackThumbnail(sourceUrl)
                if (thumb != null) {
                    AsyncImage(
                        model = thumb,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        onSuccess = { state ->
                            val size = state.painter.intrinsicSize
                            if (size.width > 0f && size.height > 0f) {
                                measuredRatio = size.width / size.height
                            }
                        },
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (candidate.isVideo) Icons.Filled.PlayArrow else Icons.Filled.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                if (platform != null) {
                    Box(
                        modifier = Modifier
                            .padding(10.dp)
                            .size(26.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                            .align(Alignment.TopStart),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(platform.icon),
                            contentDescription = platform.label,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                if (count > 1) {
                    Surface(
                        modifier = Modifier.padding(10.dp).align(Alignment.TopEnd),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    ) {
                        Text(
                            "$count items",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (candidate.isVideo) {
                    Surface(
                        modifier = Modifier.padding(10.dp).size(30.dp).align(Alignment.BottomEnd),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            Column(modifier = Modifier.padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
                Text(
                    candidate.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    listOfNotNull(platform?.label, Downloader.kindLabel(candidate.fileName, candidate.isVideo))
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (needsAction) {
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(
                        onClick = if (count > 1) onSaveAll else onSave,
                        modifier = Modifier.fillMaxWidth().height(38.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text(
                            if (count > 1) "Download all $count" else "Download",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

/** The services AnyVideo saves from, with their real marks. */
private enum class Platform(
    val label: String,
    @DrawableRes val icon: Int,
    val postRatio: Float,
    val videoRatio: Float,
) {
    INSTAGRAM("Instagram", R.drawable.ic_brand_instagram, 1f, 0.5625f),
    TIKTOK("TikTok", R.drawable.ic_brand_tiktok, 0.5625f, 0.5625f),
    YOUTUBE("YouTube", R.drawable.ic_brand_youtube, 1.7778f, 1.7778f),
    X("X", R.drawable.ic_brand_x, 1.7778f, 1.7778f),
    PINTEREST("Pinterest", R.drawable.ic_brand_pinterest, 0.75f, 0.75f);

    /** Reels and stories are portrait even though the platform defaults to square posts. */
    fun ratioFor(url: String): Float {
        val portraitPath = url.contains("/reel", true) ||
            url.contains("/reels", true) ||
            url.contains("/stories", true) ||
            url.contains("/tv", true) ||
            url.contains("/shorts", true)
        return if (portraitPath) 0.5625f else postRatio
    }

    companion object {
        fun of(url: String): Platform? = when {
            url.contains("instagram", true) -> INSTAGRAM
            url.contains("tiktok", true) -> TIKTOK
            url.contains("youtube", true) || url.contains("youtu.be", true) -> YOUTUBE
            url.contains("pinterest", true) -> PINTEREST
            url.contains("twitter.com", true) || url.contains("x.com", true) -> X
            else -> null
        }
    }
}

/** YouTube always has a poster frame even when the resolver returns no thumbnail. */
private fun fallbackThumbnail(url: String): String? {
    val id = when {
        url.contains("youtu.be/", true) -> url.substringAfter("youtu.be/", "").substringBefore('?').substringBefore('&')
        url.contains("v=", true) -> url.substringAfter("v=", "").substringBefore('&')
        url.contains("/shorts/", true) -> url.substringAfter("/shorts/", "").substringBefore('?').substringBefore('&')
        else -> null
    }
    return id?.takeIf { it.isNotBlank() }?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
}

/** Brand marks, evenly spread and centred. */
@Composable
private fun SupportedAppsRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Platform.entries.forEach { platform ->
            Image(
                painter = painterResource(platform.icon),
                contentDescription = platform.label,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/** One paste control: clipboard on the left, download on the right. */
@Composable
private fun PasteField(
    link: String,
    loading: Boolean,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onPaste: () -> Unit,
) {
    OutlinedTextField(
        value = link,
        onValueChange = onTextChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        placeholder = { Text("Paste a link") },
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        leadingIcon = {
            IconButton(onClick = onPaste) {
                Icon(
                    Icons.Filled.ContentPaste,
                    contentDescription = "Paste from clipboard",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingIcon = {
            FilledIconButton(
                onClick = onSubmit,
                enabled = !loading && link.isNotBlank(),
                modifier = Modifier.padding(end = 4.dp).size(42.dp),
                shape = CircleShape,
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "Download",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
    )
}

/** An Instagram handle the user downloaded from before. */
private data class RecentProfile(val label: String, val url: String)

private fun isYouTubeLink(url: String): Boolean =
    url.contains("youtube.com", true) || url.contains("youtu.be", true)

/** True when a link points at something specific, not just https://platform.com/. */
private fun hasPostPath(url: String): Boolean =
    url.substringAfter("://", url).substringAfter('/', "").isNotBlank()

/**
 * Recent downloads narrowed to Instagram, which is the only source we can turn back into a real
 * profile URL. Other platforms are ignored until they expose a handle we can use.
 */
private fun instagramProfilesFrom(sources: List<String>): List<RecentProfile> =
    sources.mapNotNull { source ->
        if (!source.contains("instagram", true)) return@mapNotNull null
        instagramHandleFrom(source)?.let { RecentProfile("@$it", "https://www.instagram.com/$it/") }
    }.distinctBy { it.label }.take(10)

/**
 * The handle in an Instagram link, whether it is a profile (`/name/`) or a post that carries the
 * author (`/name/p/<code>/`). Post links without a name in the path return null.
 */
private fun instagramHandleFrom(url: String): String? {
    val first = url.substringAfter("instagram.com", "")
        .substringBefore('?')
        .substringBefore('#')
        .trim('/')
        .substringBefore('/')
    if (first.isBlank()) return null
    val reserved = setOf("p", "reel", "reels", "tv", "stories", "explore", "accounts", "direct")
    return first.removePrefix("@").takeIf { it.isNotBlank() && it.lowercase() !in reserved }
}

@Composable
private fun RecentProfileBubble(profile: RecentProfile, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(modifier = Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                Text(
                    profile.label.removePrefix("@").take(1).uppercase(),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            profile.label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val VIDEO_QUALITIES = listOf(
    "max" to "Best",
    "2160" to "2160p",
    "1440" to "1440p",
    "1080" to "1080p",
    "720" to "720p",
    "480" to "480p",
    "360" to "360p",
    "240" to "240p",
    "144" to "144p",
)

/** cobalt's audioFormat values; "best" gives the original muxed audio stream. */
private val AUDIO_FORMATS = listOf(
    "mp3" to "MP3",
    "best" to "M4A",
    "opus" to "Opus",
    "ogg" to "OGG",
    "wav" to "WAV",
)

/**
 * cobalt's audioBitrate values, in kbps. The list is exactly the API's: anything else (192, say)
 * is not a value the instance accepts, so offering it would silently fall back to 128.
 */
private val AUDIO_BITRATES = listOf(
    "320" to "320k",
    "256" to "256k",
    "128" to "128k",
    "96" to "96k",
)

/** Asks for video quality or audio format before a YouTube link is resolved. */
@Composable
private fun YouTubePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (audio: Boolean, quality: String, format: String, bitrate: String) -> Unit,
) {
    var audio by remember { mutableStateOf(Prefs.youtubeAudioOnly) }
    var quality by remember { mutableStateOf(Prefs.youtubeQuality) }
    var format by remember { mutableStateOf(Prefs.youtubeAudioFormat) }
    var bitrate by remember { mutableStateOf(Prefs.youtubeAudioBitrate) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Save from YouTube", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OptionChip("Video", !audio) { audio = false }
                    Spacer(Modifier.width(6.dp))
                    OptionChip("Audio", audio) { audio = true }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    if (audio) "Audio format" else "Video quality",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val options = if (audio) AUDIO_FORMATS else VIDEO_QUALITIES
                options.chunked(3).forEach { rowOptions ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowOptions.forEach { (value, label) ->
                            val selected = if (audio) format == value else quality == value
                            OptionChip(label, selected) {
                                if (audio) format = value else quality = value
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }


                // "best" keeps the original stream, where a bitrate choice would do nothing.
                if (audio && format != "best") {
                    Text(
                        "Audio bitrate",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    AUDIO_BITRATES.chunked(3).forEach { rowOptions ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowOptions.forEach { (value, label) ->
                                OptionChip(label, bitrate == value) { bitrate = value }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(audio, quality, format, bitrate) }) { Text("Download") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Small selectable pill used by the YouTube format chooser. */
@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
