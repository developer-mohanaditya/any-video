package io.anyvideo.downloader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.anyvideo.downloader.util.Imaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class TagPosition(val label: String, val alignment: Alignment) {
    TOP_LEFT("Top\nleft", Alignment.TopStart),
    TOP_RIGHT("Top\nright", Alignment.TopEnd),
    BOTTOM_LEFT("Bottom\nleft", Alignment.BottomStart),
    BOTTOM_RIGHT("Bottom\nright", Alignment.BottomEnd),
    HIDDEN("No\ntag", Alignment.Center),
}

@Composable
fun RepostScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var tagPosition by remember { mutableStateOf(TagPosition.BOTTOM_LEFT) }
    var lightTag by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("@username") }
    var caption by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                bitmap = withContext(Dispatchers.IO) { Imaging.load(context, uri, 1600) }
            }
        }
    }

    ScreenScaffold(title = "Repost", onBack = onBack) { modifier ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            val current = bitmap
            if (current == null) {
                PhotoPlaceholder(hint = "Pick a photo to repost", onPick = { picker.launch("image/*") })
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = current.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (tagPosition != TagPosition.HIDDEN) {
                        Text(
                            text = username,
                            color = if (lightTag) Color.Black else Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(tagPosition.alignment)
                                .padding(14.dp)
                                .background(
                                    if (lightTag) Color.White.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.55f),
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TagPosition.entries.forEach { position ->
                    Surface(
                        onClick = { tagPosition = position },
                        shape = RoundedCornerShape(12.dp),
                        color = if (tagPosition == position) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ) {
                        Text(position.label, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Credit handle") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it },
                label = { Text("Caption") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { lightTag = !lightTag }) {
                    Text(if (lightTag) "Tag style: light" else "Tag style: dark")
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = {
                    bitmap?.let { picker.launch("image/*") }
                }) { Text("Replace photo") }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val current = bitmap
                        if (current == null) {
                            Toast.makeText(context, "Pick a photo first", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        scope.launch {
                            val composed = withContext(Dispatchers.Default) {
                                composeRepost(current, tagPosition, lightTag, username)
                            }
                            val saved = withContext(Dispatchers.IO) {
                                Imaging.saveToGallery(context, composed, "repost-${System.currentTimeMillis()}.png")
                            }
                            Toast.makeText(
                                context,
                                if (saved != null) "Saved to anyvideo" else "Could not save",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save copy") }

                Button(
                    onClick = {
                        val current = bitmap
                        if (current == null) {
                            Toast.makeText(context, "Pick a photo first", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        copyCaption(context, username, caption)
                        scope.launch {
                            val composed = withContext(Dispatchers.Default) {
                                composeRepost(current, tagPosition, lightTag, username)
                            }
                            val uri = withContext(Dispatchers.IO) {
                                Imaging.shareableFile(context, composed, "repost-${System.currentTimeMillis()}.png")
                            }
                            if (uri != null) {
                                shareToInstagram(context, uri)
                            } else {
                                Toast.makeText(context, "Could not prepare the image", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Share") }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { copyCaption(context, username, caption) }) {
                Text("Copy credit caption")
            }
            Hint("The caption is copied as `credit:${username}` followed by your text, ready to paste into Instagram.")
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun composeRepost(
    source: android.graphics.Bitmap,
    position: TagPosition,
    lightTag: Boolean,
    username: String,
): android.graphics.Bitmap {
    val output = source.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(output)
    if (position == TagPosition.HIDDEN) return output

    val textSize = output.width * 0.045f
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSize
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        color = if (lightTag) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    val padding = textSize * 0.5f
    val textWidth = paint.measureText(username)
    val textHeight = paint.descent() - paint.ascent()
    val margin = output.width * 0.035f

    val boxLeft = when (position) {
        TagPosition.TOP_LEFT, TagPosition.BOTTOM_LEFT -> margin
        else -> output.width - textWidth - margin - padding * 2
    }
    val boxTop = when (position) {
        TagPosition.TOP_LEFT, TagPosition.TOP_RIGHT -> margin
        else -> output.height - textHeight - margin - padding * 2
    }

    val backgroundPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = if (lightTag) {
            android.graphics.Color.argb(220, 255, 255, 255)
        } else {
            android.graphics.Color.argb(150, 0, 0, 0)
        }
    }
    val rect = android.graphics.RectF(
        boxLeft,
        boxTop,
        boxLeft + textWidth + padding * 2,
        boxTop + textHeight + padding * 2,
    )
    canvas.drawRoundRect(rect, padding, padding, backgroundPaint)
    canvas.drawText(username, boxLeft + padding, boxTop + padding - paint.ascent(), paint)
    return output
}

private fun copyCaption(context: Context, username: String, caption: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val text = "credit:$username" + if (caption.isNotBlank()) "\n$caption" else ""
    clipboard?.setPrimaryClip(ClipData.newPlainText("caption", text))
    Toast.makeText(context, "Caption copied", Toast.LENGTH_SHORT).show()
}

private fun shareToInstagram(context: Context, uri: Uri) {
    val intent = Imaging.shareIntent(uri, "com.instagram.android")
    try {
        context.startActivity(intent)
    } catch (t: Throwable) {
        Toast.makeText(context, "Instagram is not installed. Opening the share sheet", Toast.LENGTH_SHORT).show()
        context.startActivity(Intent.createChooser(Imaging.shareIntent(uri), "Share"))
    }
}
