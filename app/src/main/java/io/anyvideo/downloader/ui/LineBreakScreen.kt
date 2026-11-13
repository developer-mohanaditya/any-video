package io.anyvideo.downloader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun LineBreakScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var width by remember { mutableStateOf(24f) }
    var output by remember { mutableStateOf("") }

    ScreenScaffold(title = "Line break", onBack = onBack) { modifier ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Caption") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
            )
            Spacer(Modifier.height(16.dp))
            Text("Characters per line: ${width.toInt()}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = width,
                onValueChange = { width = it },
                valueRange = 10f..60f,
                steps = 49,
            )
            Row {
                Button(onClick = { output = breakLines(input, width.toInt()) }) {
                    Text("Add line breaks")
                }
                Spacer(Modifier.height(0.dp))
                TextButton(onClick = {
                    if (output.isNotBlank()) copyText(context, output)
                }) { Text("Copy result") }
            }
            Spacer(Modifier.height(16.dp))
            if (output.isNotBlank()) {
                SurfaceCard {
                    Text(output, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Hint("Drops the text into short lines without splitting words, ready to paste into a caption or bio.")
        }
    }
}

private fun breakLines(text: String, width: Int): String {
    val words = text.replace("\n", " ").split(" ").filter { it.isNotBlank() }
    val builder = StringBuilder()
    var lineLength = 0
    words.forEach { word ->
        if (lineLength > 0 && lineLength + 1 + word.length > width) {
            builder.append('\n')
            lineLength = 0
        } else if (lineLength > 0) {
            builder.append(' ')
            lineLength += 1
        }
        builder.append(word)
        lineLength += word.length
    }
    return builder.toString()
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("caption", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
