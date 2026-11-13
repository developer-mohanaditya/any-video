package io.anyvideo.downloader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.anyvideo.downloader.data.Catalog

@Composable
fun CaptionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }

    ScreenScaffold(title = "Captions & hashtags", onBack = onBack) { modifier ->
        Column(modifier = modifier) {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                listOf("Captions", "Hashtags").forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                }
            }
            if (tab == 0) {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    Catalog.captions.forEach { group ->
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp),
                            ) {
                                Text(
                                    group.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    copy(context, group.captions.joinToString("\n\n"))
                                }) { Text("Copy all") }
                            }
                        }
                        items(group.captions) { caption ->
                            SurfaceCard {
                                Text(caption, style = MaterialTheme.typography.bodyMedium)
                                Row {
                                    TextButton(onClick = { copy(context, caption) }) { Text("Copy") }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    Catalog.hashtags.forEach { group ->
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp),
                            ) {
                                Text(
                                    group.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { copy(context, group.tags.joinToString(" ")) }) {
                                    Text("Copy all")
                                }
                            }
                        }
                        item {
                            SurfaceCard {
                                Text(group.tags.joinToString("  "), style = MaterialTheme.typography.bodyMedium)
                                Row {
                                    TextButton(onClick = {
                                        copy(context, group.tags.take(10).joinToString(" "))
                                    }) { Text("Copy top 10") }
                                    TextButton(onClick = {
                                        copy(context, group.tags.joinToString(" "))
                                    }) { Text("Copy group") }
                                }
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(16.dp))
                        Hint("Tag sets are bundled so the tools work offline; edit Catalog.kt to change them.")
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("caption", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
