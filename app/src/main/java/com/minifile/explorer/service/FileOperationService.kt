package com.minifile.explorer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.minifile.explorer.MainActivity
import com.minifile.explorer.R
import com.minifile.explorer.core.model.OperationProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FileOperationService : Service() {

    companion object {
        const val CHANNEL_ID = "ops"
        const val NOTIF_ID_ACTIVE = 1001
        const val NOTIF_ID_DONE = 1002
        const val ACTION_CANCEL_ALL = "com.minifile.explorer.action.CANCEL_OPS"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectorJob: Job? = null
    private var completionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForegroundCompat(buildActiveNotification(emptyList()))

        collectorJob = scope.launch {
            OperationRegistry.instance.active.collect { activeList ->
                if (activeList.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIF_ID_ACTIVE, buildActiveNotification(activeList))
                }
            }
        }

        completionJob = scope.launch {
            OperationRegistry.instance.completions.collect { event ->
                postTransientCompletion(event)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) {
            OperationRegistry.instance.cancelAll()
            return START_NOT_STICKY
        }
        val active = OperationRegistry.instance.active.value
        if (active.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            startForegroundCompat(buildActiveNotification(active))
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        OperationRegistry.instance.cancelAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        collectorJob?.cancel()
        completionJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID_ACTIVE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIF_ID_ACTIVE, notification)
        }
    }

    private fun buildActiveNotification(active: List<OperationProgress>): Notification {
        val lead = active.firstOrNull()
        val title = when {
            active.isEmpty() -> "Finishing file operation…"
            active.size == 1 -> lead!!.title
            else -> "${lead!!.title} (+${active.size - 1} more)"
        }
        val text = lead?.currentItem?.takeIf { it.isNotEmpty() } ?: "Working…"
        val pct = lead?.fraction?.let { (it * 100).toInt().coerceIn(0, 100) }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FileOperationService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Cancel", cancelIntent)
            .apply {
                if (pct != null) {
                    setProgress(100, pct, false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    private fun postTransientCompletion(event: OperationCompletionEvent) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openAppIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(event.title)
            .setContentText(event.snackbarMessage)
            .setAutoCancel(true)
            .setTimeoutAfter(5_000L)
            .setContentIntent(openAppIntent)
            .build()
        nm.notify(NOTIF_ID_DONE, n)
        scope.launch {
            delay(5_000L)
            nm.cancel(NOTIF_ID_DONE)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "File Operations",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Progress of copy, move, delete, and archive tasks"
                }
                nm.createNotificationChannel(ch)
            }
        }
    }
}
