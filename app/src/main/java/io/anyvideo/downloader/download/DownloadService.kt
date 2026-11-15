package io.anyvideo.downloader.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import io.anyvideo.downloader.MainActivity
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
 * notification shade. Completed and failed files also get their own notification, so the
 * notification is the place to follow a download once the gallery-only Downloads tab is open.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null
    private val terminal = mutableSetOf<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        // Tasks already finished before this service instance existed are not new events.
        terminal += Downloader.tasks.value
            .filter { it.state == Downloader.State.DONE || it.state == Downloader.State.FAILED }
            .map { it.id }
        startForeground(NOTIFICATION_ID, buildNotification(0, 0, true))
        watch = scope.launch {
            Downloader.tasks.collect { tasks ->
                tasks.forEach { task ->
                    if (task.state != Downloader.State.DONE && task.state != Downloader.State.FAILED) {
                        return@forEach
                    }
                    if (!terminal.add(task.id)) return@forEach
                    when (task.state) {
                        Downloader.State.DONE -> notifyComplete(task)
                        Downloader.State.FAILED -> {
                            if (task.error != "Cancelled") notifyFailed(task)
                        }
                        else -> Unit
                    }
                }

                val active = tasks.filter {
                    it.state == Downloader.State.QUEUED ||
                        it.state == Downloader.State.RUNNING ||
                        it.state == Downloader.State.PAUSED
                }
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
        val id = intent?.getLongExtra(EXTRA_TASK_ID, -1L) ?: -1L
        when (intent?.action) {
            ACTION_PAUSE -> if (id >= 0) Downloader.pause(id)
            ACTION_RESUME -> if (id >= 0) Downloader.resume(this, id)
            ACTION_START -> if (id >= 0) Downloader.start(this, id)
            ACTION_CANCEL -> if (id >= 0) Downloader.cancel(id)
        }
        if (!Downloader.busy) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watch?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(count: Int, percent: Int, indeterminate: Boolean): Notification {
        val active = Downloader.tasks.value.filter {
            it.state == Downloader.State.QUEUED ||
                it.state == Downloader.State.RUNNING ||
                it.state == Downloader.State.PAUSED
        }
        val paused = active.isNotEmpty() && active.all { it.state == Downloader.State.PAUSED }
        val title = when {
            count <= 0 -> "Preparing downloads"
            paused && count == 1 -> "Download paused"
            paused -> "$count downloads paused"
            count == 1 -> "Downloading 1 file"
            else -> "Downloading $count files"
        }
        val first = active.firstOrNull()
        val detail = when {
            count <= 0 -> "Starting the transfer"
            count == 1 -> first?.fileName ?: "1 file"
            else -> "${first?.fileName ?: "$count files"} and ${count - 1} more"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(appIcon())
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(
                NotificationCompat.InboxStyle().also { style ->
                    active.take(5).forEach { style.addLine(it.fileName) }
                    if (active.size > 5) style.addLine("+${active.size - 5} more")
                },
            )
            .setContentIntent(openApp())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, percent, false)
            builder.setSubText("$percent%")
        }
        active.firstOrNull()?.let { task ->
            when (task.state) {
                Downloader.State.RUNNING -> builder.addAction(
                    android.R.drawable.ic_media_pause,
                    "Pause",
                    taskIntent(ACTION_PAUSE, task.id),
                )
                Downloader.State.PAUSED -> builder.addAction(
                    android.R.drawable.ic_media_play,
                    "Resume",
                    taskIntent(ACTION_RESUME, task.id),
                )
                Downloader.State.QUEUED -> builder.addAction(
                    android.R.drawable.ic_media_play,
                    "Start",
                    taskIntent(ACTION_START, task.id),
                )
                else -> Unit
            }
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                taskIntent(ACTION_CANCEL, task.id),
            )
        }
        return builder.build()
    }

    private fun taskIntent(action: String, taskId: Long): PendingIntent {
        val intent = Intent(this, DownloadService::class.java)
            .setAction(action)
            .putExtra(EXTRA_TASK_ID, taskId)
        return PendingIntent.getService(
            this,
            (action.hashCode() * 31 + taskId.toInt()) and 0x7fffffff,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** A finished file leaves a lasting notification instead of disappearing with the service. */
    private fun notifyComplete(task: Downloader.Task) {
        if (!Prefs.showDownloadNotifications) return
        val builder = NotificationCompat.Builder(this, COMPLETE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(appIcon())
            .setContentTitle("Download complete")
            .setContentText(task.fileName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(task.fileName))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        notify(builder.build(), COMPLETE_NOTIFICATION_BASE + task.id.toInt())
    }

    private fun notifyFailed(task: Downloader.Task) {
        if (!Prefs.showDownloadNotifications) return
        val builder = NotificationCompat.Builder(this, FAILED_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(appIcon())
            .setContentTitle("Download failed")
            .setContentText(task.error ?: task.fileName)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${task.fileName}\n${task.error ?: "Could not finish this download"}"),
            )
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        notify(builder.build(), FAILED_NOTIFICATION_BASE + task.id.toInt())
    }

    /** The expanded notification carries the actual app tile, not a generic download glyph. */
    private fun appIcon() = runCatching {
        ContextCompat.getDrawable(this, R.drawable.ic_launcher)?.toBitmap()
    }.getOrNull()

    private fun openApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notify(notification: Notification, id: Int = NOTIFICATION_ID) {
        if (!Prefs.showDownloadNotifications) return
        runCatching {
            ContextCompat.getSystemService(this, NotificationManager::class.java)
                ?.notify(id, notification)
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        fun channel(id: String, name: String, description: String, importance: Int) {
            if (manager.getNotificationChannel(id) != null) return
            manager.createNotificationChannel(
                NotificationChannel(id, name, importance).apply {
                    this.description = description
                    setShowBadge(id == COMPLETE_CHANNEL_ID || id == FAILED_CHANNEL_ID)
                },
            )
        }
        channel(
            CHANNEL_ID,
            "Downloads",
            "Progress for files AnyVideo is downloading",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel(
            COMPLETE_CHANNEL_ID,
            "Download complete",
            "Notifications for files that finished downloading",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel(
            FAILED_CHANNEL_ID,
            "Download failed",
            "Notifications for downloads that did not finish",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
    }

    companion object {
        private const val CHANNEL_ID = "anyvideo_downloads"
        private const val COMPLETE_CHANNEL_ID = "anyvideo_complete"
        private const val FAILED_CHANNEL_ID = "anyvideo_failed"
        private const val NOTIFICATION_ID = 41
        private const val COMPLETE_NOTIFICATION_BASE = 2000
        private const val FAILED_NOTIFICATION_BASE = 3000
        private const val EXTRA_TASK_ID = "task_id"
        private const val ACTION_PAUSE = "io.anyvideo.downloader.PAUSE"
        private const val ACTION_RESUME = "io.anyvideo.downloader.RESUME"
        private const val ACTION_START = "io.anyvideo.downloader.START"
        private const val ACTION_CANCEL = "io.anyvideo.downloader.CANCEL"

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stopIfIdle(context: Context) {
            if (Downloader.busy) return
            runCatching { context.stopService(Intent(context, DownloadService::class.java)) }
        }
    }
}
