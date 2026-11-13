package io.anyvideo.downloader.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.anyvideo.downloader.R
import io.anyvideo.downloader.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while transfers run and mirrors the engine's progress into the
 * notification shade. The downloads themselves live in [Downloader], so leaving the app does
 * not cancel a transfer and coming back still shows the same progress.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(0, 0, true))
        watch = scope.launch {
            Downloader.tasks.collect { tasks ->
                val active = tasks.filter { it.state == Downloader.State.QUEUED || it.state == Downloader.State.RUNNING }
                if (active.isEmpty()) {
                    stopSelf()
                    return@collect
                }
                val received = active.sumOf { it.received }
                val total = active.sumOf { it.total }
                val indeterminate = total <= 0L
                val percent = if (indeterminate) 0 else ((received * 100) / total).toInt()
                notify(buildNotification(active.size, percent, indeterminate))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Downloader.busy) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watch?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(count: Int, percent: Int, indeterminate: Boolean): Notification {
        val title = when {
            count <= 0 -> "Preparing downloads"
            count == 1 -> "Downloading 1 file"
            else -> "Downloading $count files"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, percent, false)
            builder.setContentText("$percent%")
        }
        return builder.build()
    }

    private fun notify(notification: Notification) {
        if (!Prefs.showDownloadNotifications) return
        runCatching {
            ContextCompat.getSystemService(this, NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress for files AnyVideo is downloading"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "anyvideo_downloads"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
