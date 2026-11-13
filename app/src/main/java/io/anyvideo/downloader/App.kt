package io.anyvideo.downloader

import android.app.Application
import android.app.DownloadManager
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import io.anyvideo.downloader.download.DownloadReceiver
import io.anyvideo.downloader.util.Prefs

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        ContextCompat.registerReceiver(
            this,
            DownloadReceiver(),
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }
}
