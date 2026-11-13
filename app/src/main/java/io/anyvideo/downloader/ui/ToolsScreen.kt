package io.anyvideo.downloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FormatLineSpacing
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.anyvideo.downloader.Screen

private data class Tool(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val screen: Screen,
)

private val downloadTools = listOf(
    Tool(Icons.Filled.Layers, "Bulk download", "Whole profile feed", Screen.BULK),
    Tool(Icons.Filled.Tag, "Status saver", "WhatsApp images", Screen.WHATSAPP),
)

private val createTools = listOf(
    Tool(Icons.Filled.Share, "Repost", "Credit and share", Screen.REPOST),
    Tool(Icons.Filled.PhotoLibrary, "Captions", "Ready-made text", Screen.CAPTIONS),
    Tool(Icons.Filled.GridOn, "Grid maker", "Split into tiles", Screen.GRID),
    Tool(Icons.Filled.AspectRatio, "Square photo", "No-crop square", Screen.SQUARE),
    Tool(Icons.Filled.Image, "Resize photo", "Exact pixel sizes", Screen.RESIZE),
    Tool(Icons.Filled.Face, "DP maker", "Circular picture", Screen.DP),
    Tool(Icons.Filled.FormatLineSpacing, "Line break", "Caption formatting", Screen.LINE_BREAK),
)

@Composable
fun ToolsScreen(onNavigate: (Screen) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp)) {
                Text(
                    "Tools",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Edit, format and share: nine tools that work on your saved files.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { SectionTitle("Download") }
        items(downloadTools.chunked(2)) { row -> TileRow(row, onNavigate) }

        item { SectionTitle("Create & format") }
        items(createTools.chunked(2)) { row -> TileRow(row, onNavigate) }

        item {
            Spacer(Modifier.height(10.dp))
            Hint("Tools save into the same Pictures/anyvideo and Movies/anyvideo folders as downloads.")
        }
    }
}

@Composable
private fun TileRow(tiles: List<Tool>, onNavigate: (Screen) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        tiles.forEach { tool ->
            GridTile(
                icon = tool.icon,
                title = tool.title,
                subtitle = tool.subtitle,
                onClick = { onNavigate(tool.screen) },
                modifier = Modifier.weight(1f),
            )
        }
        if (tiles.size == 1) Spacer(Modifier.weight(1f))
    }
}
