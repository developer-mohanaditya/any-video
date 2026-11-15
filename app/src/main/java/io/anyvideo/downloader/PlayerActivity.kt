package io.anyvideo.downloader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import io.anyvideo.downloader.data.Library
import io.anyvideo.downloader.data.MediaKind
import io.anyvideo.downloader.data.MimeTypes
import io.anyvideo.downloader.data.SavedMedia
import io.anyvideo.downloader.ui.AnyVideoTheme
import io.anyvideo.downloader.ui.FullscreenPlayer

/**
 * Playback lives in its own activity so picture-in-picture shows the media surface rather than
 * a dialog layered over the Downloads screen.
 */
class PlayerActivity : ComponentActivity() {

    private var pipMode by mutableStateOf(false)

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipMode = isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.setBackgroundColor(Color.BLACK)
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        val kind = runCatching {
            MediaKind.valueOf(intent.getStringExtra(EXTRA_KIND).orEmpty())
        }.getOrDefault(MediaKind.VIDEO)
        val media = SavedMedia(
            uri = uri,
            name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Saved file" },
            kind = kind,
            size = intent.getLongExtra(EXTRA_SIZE, 0L),
            dateAdded = intent.getLongExtra(EXTRA_DATE_ADDED, 0L),
            path = intent.getStringExtra(EXTRA_PATH),
        )
        setContent {
            AnyVideoTheme {
                FullscreenPlayer(
                    media = media,
                    onDismiss = { finish() },
                    onShare = { share(media) },
                    onDelete = { delete(media) },
                    pipMode = pipMode,
                )
            }
        }
    }

    private fun delete(media: SavedMedia) {
        val deleted = Library.delete(this, media)
        Toast.makeText(
            this,
            if (deleted) "File deleted" else "Could not delete this file",
            Toast.LENGTH_SHORT,
        ).show()
        if (deleted) setResult(Activity.RESULT_OK)
        finish()
    }

    private fun share(media: SavedMedia) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MimeTypes.forKind(media.name, media.kind)
            putExtra(Intent.EXTRA_STREAM, media.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(send, "Share")) }
            .onFailure {
                Toast.makeText(this, "No app can share this file", Toast.LENGTH_SHORT).show()
            }
    }

    companion object {
        private const val EXTRA_NAME = "player_name"
        private const val EXTRA_KIND = "player_kind"
        private const val EXTRA_SIZE = "player_size"
        private const val EXTRA_DATE_ADDED = "player_date_added"
        private const val EXTRA_PATH = "player_path"

        fun intent(context: Context, media: SavedMedia): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                data = media.uri
                putExtra(EXTRA_NAME, media.name)
                putExtra(EXTRA_KIND, media.kind.name)
                putExtra(EXTRA_SIZE, media.size)
                putExtra(EXTRA_DATE_ADDED, media.dateAdded)
                putExtra(EXTRA_PATH, media.path)
            }
    }
}
