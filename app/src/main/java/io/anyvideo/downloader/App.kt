package io.anyvideo.downloader

import android.app.Application
import io.anyvideo.downloader.util.Prefs

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }
}
