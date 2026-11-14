package io.anyvideo.downloader

import android.app.Application
import io.anyvideo.downloader.util.Prefs

class App : Application() {

    private var coldStart = true

    /** True once for the first activity created by this process. */
    fun consumeColdStart(): Boolean {
        val first = coldStart
        coldStart = false
        return first
    }

    /** The task was closed; the next activity should be treated as a fresh launch. */
    fun markColdStart() {
        coldStart = true
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }
}
