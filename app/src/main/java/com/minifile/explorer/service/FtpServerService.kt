package com.minifile.explorer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.minifile.explorer.MainActivity
import com.minifile.explorer.MiniFileApplication
import com.minifile.explorer.R
import com.minifile.explorer.core.ftp.FtpAuth
import com.minifile.explorer.core.ftp.FtpConfig
import com.minifile.explorer.core.ftp.FtpServerEngine
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.BindException

class FtpServerService : Service() {

    companion object {
        const val CHANNEL_ID = "ftp"
        const val NOTIF_ID_RUNNING = 2001
        const val NOTIF_ID_STOPPED_BY_SYSTEM = 2002

        const val ACTION_START = "com.minifile.explorer.action.FTP_START"
        const val ACTION_STOP = "com.minifile.explorer.action.FTP_STOP"

        fun start(context: Context) {
            FtpServerHolder.markStarting()
            val intent = Intent(context, FtpServerService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FtpServerService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: FtpServerEngine? = null
    private var eventsJob: Job? = null
    private var sessionsJob: Job? = null
    private var networkJob: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdownServer(errorMessage = null, removeNotification = true)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                if (engine != null && FtpServerHolder.state.value.isRunning) {
                    refreshRunningNotification()
                    return START_STICKY
                }
                startForegroundCompat(buildRunningNotification("Starting FTP server…", "Preparing listener"))
                launchEngine()
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleSystemTimeout()
    }

    override fun onTimeout(startId: Int) {
        handleSystemTimeout()
    }

    private fun handleSystemTimeout() {
        shutdownServer(
            errorMessage = "Stopped by Android foreground service timeout",
            removeNotification = true,
        )
        postStoppedBySystemNotification()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shutdownServer(errorMessage = null, removeNotification = true)
        scope.cancel()
        super.onDestroy()
    }

    private fun launchEngine() {
        val container = (application as MiniFileApplication).container
        val settings = container.settingsRepository.settings.value
        val rootPath: VfsPath = settings.ftpRoot
            ?.let { runCatching { VfsPath.parse(it) }.getOrNull() }
            ?: VfsPath.Local(Environment.getExternalStorageDirectory().absolutePath)

        val auth = if (settings.ftpAnonymous) {
            FtpAuth.Anonymous
        } else {
            FtpAuth.Credentials(
                username = settings.ftpUsername.ifBlank { "minifile" },
                password = settings.ftpPassword,
            )
        }
        val pasvRange = settings.ftpPasvStart..settings.ftpPasvEnd.coerceAtLeast(settings.ftpPasvStart)
        val config = FtpConfig(
            port = settings.ftpPort,
            bindAddress = "0.0.0.0",
            root = rootPath,
            readOnly = settings.ftpReadOnly,
            auth = auth,
            passivePorts = pasvRange,
            idleTimeoutMs = settings.ftpIdleTimeoutSec * 1000L,
            maxClients = 8,
            serverName = "MiniFile FTP",
        )

        val srv = FtpServerEngine(
            config = config,
            fs = container.fileSystem,
            externalIpProvider = { container.lanAddressResolver.currentIpv4List() },
        )

        try {
            srv.start()
        } catch (_: BindException) {
            val msg = "Port ${settings.ftpPort} is already in use. Choose another port in Wireless settings."
            shutdownServer(errorMessage = msg, removeNotification = true)
            FtpServerHolder.markStopped(errorMessage = msg)
            stopSelf()
            return
        } catch (e: Exception) {
            val msg = "Could not start FTP server: ${e.message ?: "unknown error"}"
            shutdownServer(errorMessage = msg, removeNotification = true)
            FtpServerHolder.markStopped(errorMessage = msg)
            stopSelf()
            return
        }

        engine = srv
        acquireLocks()

        val initialIps = container.lanAddressResolver.currentIpv4List()
        FtpServerHolder.markStarted(srv.boundPort, initialIps)
        refreshRunningNotification()

        eventsJob = scope.launch {
            srv.events.collect { ev ->
                FtpServerHolder.onEvent(ev)
                refreshRunningNotification()
            }
        }
        sessionsJob = scope.launch {
            srv.activeSessions.collect { count ->
                FtpServerHolder.updateActiveClients(count)
                refreshRunningNotification()
            }
        }
        networkJob = scope.launch {
            container.lanAddressResolver.observeEndpoints().collect { endpoints ->
                FtpServerHolder.updateLanIps(endpoints.map { it.ipv4 })
                refreshRunningNotification()
            }
        }
    }

    private fun shutdownServer(errorMessage: String?, removeNotification: Boolean) {
        eventsJob?.cancel()
        eventsJob = null
        sessionsJob?.cancel()
        sessionsJob = null
        networkJob?.cancel()
        networkJob = null

        engine?.let { runCatching { it.stop() } }
        engine = null
        releaseLocks()
        FtpServerHolder.markStopped(errorMessage)

        if (removeNotification) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID_RUNNING)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm?.createWifiLock(mode, "minifile:ftp-wifi")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        runCatching {
            val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "minifile:ftp-cpu")?.apply {
                setReferenceCounted(false)
                acquire(12L * 60L * 60L * 1000L)
            }
        }
    }

    private fun releaseLocks() {
        runCatching {
            wifiLock?.let { if (it.isHeld) it.release() }
        }
        wifiLock = null
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID_RUNNING,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID_RUNNING, notification)
        }
    }

    private fun refreshRunningNotification() {
        val st = FtpServerHolder.state.value
        if (!st.isRunning) return
        val primaryUrl = st.urls.firstOrNull() ?: "ftp://127.0.0.1:${st.boundPort}"
        val clientLabel = if (st.activeClients == 1) "1 client" else "${st.activeClients} clients"
        val transfers = if (st.uploadsCount > 0 || st.downloadsCount > 0) {
            " · ↑ ${st.uploadsCount} ↓ ${st.downloadsCount}"
        } else {
            ""
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIF_ID_RUNNING,
            buildRunningNotification(
                title = "MiniFile FTP · $primaryUrl",
                subtitle = "$clientLabel$transfers",
            ),
        )
    }

    private fun buildRunningNotification(title: String, subtitle: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("open_tab", "wireless")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, FtpServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun postStoppedBySystemNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = PendingIntent.getActivity(
            this,
            12,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("open_tab", "wireless")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("MiniFile FTP was stopped by the system")
            .setContentText("Tap to open Wireless Transfer and restart")
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        nm.notify(NOTIF_ID_STOPPED_BY_SYSTEM, notif)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Wireless FTP Server",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Active status and Stop action for MiniFile's LAN FTP server"
                }
                nm.createNotificationChannel(ch)
            }
        }
    }
}
