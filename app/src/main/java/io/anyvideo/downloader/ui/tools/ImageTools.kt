package io.anyvideo.downloader.ui.tools

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.anyvideo.downloader.ui.PhotoPlaceholder
import io.anyvideo.downloader.ui.ScreenScaffold
import io.anyvideo.downloader.util.Imaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun ImagePreview(bitmap: Bitmap?, hint: String, onPick: () -> Unit) {
    if (bitmap == null) {
        PhotoPlaceholder(hint = hint, onPick = onPick)
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun GridMakerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var rows by remember { mutableIntStateOf(3) }
    var cols by remember { mutableIntStateOf(3) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { bitmap = withContext(Dispatchers.IO) { Imaging.load(context, uri) } }
    }

    ScreenScaffold(title = "Grid maker", onBack = onBack) { modifier ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ImagePreview(bitmap, "Pick a photo to split", { picker.launch("image/*") })
            Spacer(Modifier.height(16.dp))
            Text("Rows: $rows", style = MaterialTheme.typography.bodyMedium)
            Slider(value = rows.toFloat(), onValueChange = { rows = it.toInt() }, valueRange = 2f..4f, steps = 1)
            Text("Columns: $cols", style = MaterialTheme.typography.bodyMedium)
            Slider(value = cols.toFloat(), onValueChange = { cols = it.toInt() }, valueRange = 2f..4f, steps = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (bitmap != null) {
                    Button(onClick = { picker.launch("image/*") }) { Text("Replace photo") }
                }
                Button(
                    onClick = {
                        val source = bitmap
                        if (source == null) {
                            Toast.makeText(context, "Pick a photo first", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        scope.launch {
                            val tiles = withContext(Dispatchers.Default) { Imaging.splitGrid(source, rows, cols) }
                            var saved = 0
                            tiles.forEachIndexed { index, tile ->
                                val name = "grid-${System.currentTimeMillis()}-${index + 1}.png"
                                if (withContext(Dispatchers.IO) {
                                        Imaging.saveToGallery(context, tile, name)
                                    } != null
                                ) {
                                    saved++
                                }
                            }
                            Toast.makeText(context, "Saved $saved tile(s) to anyvideo", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text("Split & save") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Each tile is saved separately, ordered left-to-right, so you can post them in sequence.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SquarePhotoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { bitmap = withContext(Dispatchers.IO) { Imaging.load(context, uri) } }
    }

    ScreenScaffold(title = "Square photo", onBack = onBack) { modifier ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ImagePreview(bitmap, "Pick a photo", { picker.launch("image/*") })
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (bitmap != null) {
                    Button(onClick = { picker.launch("image/*") }) { Text("Replace photo") }
                }
                Button(onClick = {
                    val source = bitmap
                    if (source == null) {
                        Toast.makeText(context, "Pick a photo first", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    scope.launch {
                        val square = withContext(Dispatchers.Default) { Imaging.squareWithBlurredBackground(source) }
                        val saved = withContext(Dispatchers.IO) {
                            Imaging.saveToGallery(context, square, "square-${System.currentTimeMillis()}.png")
                        }
                        Toast.makeText(
                            context,
                            if (saved != null) "Saved to anyvideo" else "Could not save",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }) { Text("Save square") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "No cropping: the full photo sits on a blurred copy of itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ResizeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var width by remember { mutableStateOf("1080") }
    var height by remember { mutableStateOf("1080") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val loaded = withContext(Dispatchers.IO) { Imaging.load(context, uri) }
                bitmap = loaded
                if (loaded != null) {
                    width = loaded.width.toString()
                    height = loaded.height.toString()
                }
            }
        }
    }

    ScreenScaffold(title = "Resize photo", onBack = onBack) { modifier ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ImagePreview(bitmap, "Pick a photo to resize", { picker.launch("image/*") })
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = width,
                    onValueChange = { width = it.filter(Char::isDigit) },
                    label = { Text("Width") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = height,
                    onValueChange = { height = it.filter(Char::isDigit) },
                    label = { Text("Height") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (bitmap != null) {
                    Button(onClick = { picker.launch("image/*") }) { Text("Replace photo") }
                }
                Button(onClick = {
                    val source = bitmap
                    val targetWidth = width.toIntOrNull()
                    val targetHeight = height.toIntOrNull()
                    if (source == null || targetWidth == null || targetHeight == null) {
                        Toast.makeText(context, "Pick a photo and enter a size", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    scope.launch {
                        val resized = withContext(Dispatchers.Default) { Imaging.resize(source, targetWidth, targetHeight) }
                        val saved = withContext(Dispatchers.IO) {
                            Imaging.saveToGallery(context, resized, "resized-${System.currentTimeMillis()}.png")
                        }
                        Toast.makeText(
                            context,
                            if (saved != null) "Saved ${resized.width}x${resized.height}" else "Could not save",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }) { Text("Resize & save") }
                TextButton(onClick = {
                    bitmap?.let { width = it.width.toString(); height = it.height.toString() }
                }) { Text("Reset") }
            }
        }
    }
}

@Composable
fun DpMakerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<Bitmap?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                bitmap = withContext(Dispatchers.IO) { Imaging.load(context, uri) }
                result = null
            }
        }
    }

    ScreenScaffold(title = "DP maker", onBack = onBack) { modifier ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ImagePreview(result ?: bitmap, "Pick a photo for your DP", { picker.launch("image/*") })
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (bitmap != null) {
                    Button(onClick = { picker.launch("image/*") }) { Text("Replace photo") }
                }
                Button(onClick = {
                    val source = bitmap
                    if (source == null) {
                        Toast.makeText(context, "Pick a photo first", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    scope.launch {
                        val circle = withContext(Dispatchers.Default) { Imaging.circleCrop(source) }
                        result = circle
                        val saved = withContext(Dispatchers.IO) {
                            Imaging.saveToGallery(context, circle, "dp-${System.currentTimeMillis()}.png")
                        }
                        Toast.makeText(
                            context,
                            if (saved != null) "Saved to anyvideo" else "Could not save",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }) { Text("Make DP") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Square crop with a white ring, ready to upload as a profile picture.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
