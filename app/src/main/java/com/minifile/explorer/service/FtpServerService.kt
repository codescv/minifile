package com.minifile.explorer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import com.minifile.explorer.ui.util.NearbyPermissions
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.minifile.explorer.BuildConfig
import com.minifile.explorer.MainActivity
import com.minifile.explorer.MiniFileApplication
import com.minifile.explorer.R
import com.minifile.explorer.core.ftp.FtpAuth
import com.minifile.explorer.core.ftp.FtpConfig
import com.minifile.explorer.core.ftp.FtpServerEngine
import com.minifile.explorer.core.http.HttpConfig
import com.minifile.explorer.core.http.HttpFileServerEngine
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
        const val ACTION_HTTP_START = "com.minifile.explorer.action.HTTP_START"
        const val ACTION_HTTP_STOP = "com.minifile.explorer.action.HTTP_STOP"
        const val ACTION_STOP_ALL = "com.minifile.explorer.action.STOP_ALL"

        fun start(context: Context) {
            FtpServerHolder.markStarting()
            val intent = Intent(context, FtpServerService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FtpServerService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun startHttp(context: Context) {
            HttpServerHolder.markStarting()
            val intent = Intent(context, FtpServerService::class.java).setAction(ACTION_HTTP_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopHttp(context: Context) {
            val intent = Intent(context, FtpServerService::class.java).setAction(ACTION_HTTP_STOP)
            context.startService(intent)
        }

        fun stopAll(context: Context) {
            val intent = Intent(context, FtpServerService::class.java).setAction(ACTION_STOP_ALL)
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var ftpEngine: FtpServerEngine? = null
    private var ftpEventsJob: Job? = null
    private var ftpSessionsJob: Job? = null

    private var httpEngine: HttpFileServerEngine? = null
    private var httpEventsJob: Job? = null
    private var httpClientsJob: Job? = null

    private var networkJob: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val isAnyServerActive: Boolean
        get() = ftpEngine != null || httpEngine != null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALL -> {
                shutdownAll(errorMessage = null, removeNotification = true)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_STOP -> {
                shutdownFtp(errorMessage = null)
                if (!isAnyServerActive) {
                    teardownServiceResources(removeNotification = true)
                    stopSelf()
                    return START_NOT_STICKY
                }
                refreshRunningNotification()
                return START_STICKY
            }

            ACTION_HTTP_STOP -> {
                shutdownHttp(errorMessage = null)
                if (!isAnyServerActive) {
                    teardownServiceResources(removeNotification = true)
                    stopSelf()
                    return START_NOT_STICKY
                }
                refreshRunningNotification()
                return START_STICKY
            }

            ACTION_HTTP_START -> {
                startForegroundCompat(
                    currentRunningNotificationOrNull()
                        ?: buildRunningNotification("Starting HTTP Share…", "Preparing listener"),
                )
                if (httpEngine != null && HttpServerHolder.state.value.isRunning) {
                    refreshRunningNotification()
                    return START_STICKY
                }
                launchHttpEngine()
                return if (isAnyServerActive) START_STICKY else START_NOT_STICKY
            }

            ACTION_START, null -> {
                startForegroundCompat(
                    currentRunningNotificationOrNull()
                        ?: buildRunningNotification("Starting FTP server…", "Preparing listener"),
                )
                if (ftpEngine != null && FtpServerHolder.state.value.isRunning) {
                    refreshRunningNotification()
                    return START_STICKY
                }
                launchFtpEngine()
                return if (isAnyServerActive) START_STICKY else START_NOT_STICKY
            }
        }
        return if (isAnyServerActive) START_STICKY else START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleSystemTimeout()
    }

    override fun onTimeout(startId: Int) {
        handleSystemTimeout()
    }

    private fun handleSystemTimeout() {
        shutdownAll(
            errorMessage = "Stopped by Android foreground service timeout",
            removeNotification = true,
        )
        postStoppedBySystemNotification()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shutdownAll(errorMessage = null, removeNotification = true)
        scope.cancel()
        super.onDestroy()
    }

    private fun launchFtpEngine() {
        val container = (application as MiniFileApplication).container
        val settings = container.settingsRepository.settings.value
        val rootRaw = settings.ftpRoot ?: settings.httpRoot
        val rootPath: VfsPath = rootRaw
            ?.let { runCatching { VfsPath.parse(it) }.getOrNull() }
            ?: VfsPath.Local(Environment.getExternalStorageDirectory().absolutePath)
        val readOnly = settings.ftpReadOnly || settings.httpReadOnly

        val auth = if (settings.ftpAnonymous) {
            FtpAuth.Anonymous
        } else {
            FtpAuth.Credentials(
                username = settings.ftpUsername.ifBlank { "minifile" },
                password = settings.ftpPassword,
            )
        }
        val pasvRange = settings.ftpPasvStart..settings.ftpPasvEnd.coerceAtLeast(settings.ftpPasvStart)
        val baseConfig = FtpConfig(
            port = settings.ftpPort,
            bindAddress = "0.0.0.0",
            root = rootPath,
            readOnly = readOnly,
            auth = auth,
            passivePorts = pasvRange,
            idleTimeoutMs = settings.ftpIdleTimeoutSec * 1000L,
            maxClients = 8,
            serverName = "MiniFile FTP",
        )

        val candidatePorts = buildList {
            add(settings.ftpPort)
            for (p in 2122..2130) {
                if (p != settings.ftpPort) add(p)
            }
            add(0)
        }

        var startedSrv: FtpServerEngine? = null
        var lastBindError: BindException? = null
        for (candidatePort in candidatePorts) {
            val srv = FtpServerEngine(
                config = baseConfig.copy(port = candidatePort),
                fs = container.fileSystem,
                externalIpProvider = { container.lanAddressResolver.currentIpv4List() },
            )
            try {
                srv.start()
                startedSrv = srv
                break
            } catch (b: BindException) {
                lastBindError = b
                runCatching { srv.stop() }
            } catch (e: Exception) {
                runCatching { srv.stop() }
                val msg = "Could not start FTP server: ${e.message ?: "unknown error"}"
                shutdownFtp(errorMessage = msg)
                if (!isAnyServerActive) {
                    teardownServiceResources(removeNotification = true)
                    stopSelf()
                } else {
                    refreshRunningNotification()
                }
                return
            }
        }

        val srv = startedSrv
        if (srv == null) {
            val msg = "Could not bind FTP server port: ${lastBindError?.message ?: "all ports busy"}"
            shutdownFtp(errorMessage = msg)
            if (!isAnyServerActive) {
                teardownServiceResources(removeNotification = true)
                stopSelf()
            } else {
                refreshRunningNotification()
            }
            return
        }

        ftpEngine = srv
        ensureLocksAndNetworkObserver(container)

        val initialIps = container.lanAddressResolver.currentIpv4List()
        FtpServerHolder.markStarted(srv.boundPort, initialIps)
        refreshRunningNotification()

        ftpEventsJob = scope.launch {
            srv.events.collect { ev ->
                FtpServerHolder.onEvent(ev)
                refreshRunningNotification()
            }
        }
        ftpSessionsJob = scope.launch {
            srv.activeSessions.collect { count ->
                FtpServerHolder.updateActiveClients(count)
                refreshRunningNotification()
            }
        }
    }

    private fun launchHttpEngine() {
        val container = (application as MiniFileApplication).container
        val settings = container.settingsRepository.settings.value
        val rootRaw = settings.ftpRoot ?: settings.httpRoot
        val rootPath: VfsPath = rootRaw
            ?.let { runCatching { VfsPath.parse(it) }.getOrNull() }
            ?: VfsPath.Local(Environment.getExternalStorageDirectory().absolutePath)
        val readOnly = settings.ftpReadOnly || settings.httpReadOnly

        val baseConfig = HttpConfig(
            port = settings.httpPort,
            bindAddress = "0.0.0.0",
            root = rootPath,
            readOnly = readOnly,
            showHidden = settings.showHidden,
            serverName = "Files HTTP",
            deviceId = settings.peerDeviceId.ifBlank { "local" },
            deviceName = settings.peerDeviceName.ifBlank { "Files" },
            appVersion = BuildConfig.VERSION_NAME,
            peerInboxDir = "Received",
        )

        val candidatePorts = buildList {
            add(settings.httpPort)
            for (p in 8081..8090) {
                if (p != settings.httpPort) add(p)
            }
            add(0)
        }

        var startedSrv: HttpFileServerEngine? = null
        var lastBindError: BindException? = null
        for (candidatePort in candidatePorts) {
            val srv = HttpFileServerEngine(
                config = baseConfig.copy(port = candidatePort),
                fs = container.fileSystem,
                hotspotGate = HttpServerHolder.hotspotGate,
            )
            try {
                srv.start()
                startedSrv = srv
                break
            } catch (b: BindException) {
                lastBindError = b
                runCatching { srv.stop() }
            } catch (e: Exception) {
                runCatching { srv.stop() }
                val msg = "Could not start HTTP server: ${e.message ?: "unknown error"}"
                shutdownHttp(errorMessage = msg)
                if (!isAnyServerActive) {
                    teardownServiceResources(removeNotification = true)
                    stopSelf()
                } else {
                    refreshRunningNotification()
                }
                return
            }
        }

        val srv = startedSrv
        if (srv == null) {
            val msg = "Could not bind HTTP server port: ${lastBindError?.message ?: "all ports busy"}"
            shutdownHttp(errorMessage = msg)
            if (!isAnyServerActive) {
                teardownServiceResources(removeNotification = true)
                stopSelf()
            } else {
                refreshRunningNotification()
            }
            return
        }

        httpEngine = srv
        ensureLocksAndNetworkObserver(container)

        val initialIps = container.lanAddressResolver.currentIpv4List()
        HttpServerHolder.markStarted(srv.boundPort, initialIps)
        container.peerDiscoveryHolder.setReceiverRole(true)
        // D1: Bonjour `_minifile._tcp` so iOS discovers this phone instantly.
        runCatching { container.nsdAdvertiser.register(srv.boundPort) }
        refreshRunningNotification()

        httpEventsJob = scope.launch {
            srv.events.collect { ev ->
                HttpServerHolder.onEvent(ev)
                container.peerReceiveBridge.onHttpEvent(srv, ev)
                refreshRunningNotification()
            }
        }
        httpClientsJob = scope.launch {
            srv.activeClients.collect { count ->
                HttpServerHolder.updateActiveClients(count)
                refreshRunningNotification()
            }
        }
    }

    private fun ensureLocksAndNetworkObserver(container: com.minifile.explorer.di.AppContainer) {
        if (wifiLock == null && wakeLock == null) {
            acquireLocks()
        }
        if (networkJob == null) {
            networkJob = scope.launch {
                container.lanAddressResolver.observeEndpoints().collect { endpoints ->
                    val ips = endpoints.map { it.ipv4 }
                    FtpServerHolder.updateLanIps(ips)
                    HttpServerHolder.updateLanIps(ips)
                    refreshRunningNotification()
                }
            }
        }
    }

    private fun shutdownFtp(errorMessage: String?) {
        ftpEventsJob?.cancel()
        ftpEventsJob = null
        ftpSessionsJob?.cancel()
        ftpSessionsJob = null

        ftpEngine?.let { runCatching { it.stop() } }
        ftpEngine = null
        FtpServerHolder.markStopped(errorMessage)
    }

    private fun shutdownHttp(errorMessage: String?) {
        val container = (application as? MiniFileApplication)?.container
        container?.peerDiscoveryHolder?.setReceiverRole(false)
        if (httpEngine != null) {
            container?.let { c -> runCatching { c.nsdAdvertiser.unregister() } }
            // An ephemeral hotspot share is useless without HTTP: take the hotspot down with it.
            container?.let { c ->
                if (c.localOnlyHotspotManager.httpStartedForHotspot) {
                    runCatching { c.localOnlyHotspotManager.stop(fromService = true) }
                }
            }
        }

        httpEventsJob?.cancel()
        httpEventsJob = null
        httpClientsJob?.cancel()
        httpClientsJob = null

        httpEngine?.let { runCatching { it.stop() } }
        httpEngine = null
        container?.peerReceiveBridge?.cancelAndClearAll()
        HttpServerHolder.markStopped(errorMessage)
    }

    private fun shutdownAll(errorMessage: String?, removeNotification: Boolean) {
        // Spec §3.2: the LocalOnlyHotspot never outlives the foreground service that keeps it alive.
        (application as? MiniFileApplication)?.container?.let { c ->
            runCatching { c.localOnlyHotspotManager.stop(fromService = true) }
        }
        shutdownHttp(errorMessage)
        shutdownFtp(errorMessage)
        teardownServiceResources(removeNotification)
    }

    private fun teardownServiceResources(removeNotification: Boolean) {
        networkJob?.cancel()
        networkJob = null
        releaseLocks()

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
            // Spec §3.1 [v2]: SPECIAL_USE always; OR-in CONNECTED_DEVICE only when BLUETOOTH_CONNECT is granted
            // (API 34+ throws SecurityException for CONNECTED_DEVICE without the BT runtime permission).
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (NearbyPermissions.hasBleConnectPermission(this)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(
                NOTIF_ID_RUNNING,
                notification,
                type,
            )
        } else {
            startForeground(NOTIF_ID_RUNNING, notification)
        }
    }

    private fun currentRunningNotificationOrNull(): Notification? {
        val ftpSt = FtpServerHolder.state.value
        val httpSt = HttpServerHolder.state.value
        if (!ftpSt.isRunning && !httpSt.isRunning) return null

        return when {
            httpSt.isRunning && ftpSt.isRunning -> {
                val httpUrl = httpSt.urls.firstOrNull() ?: "http://127.0.0.1:${httpSt.boundPort}"
                val ftpUrl = ftpSt.urls.firstOrNull() ?: "ftp://127.0.0.1:${ftpSt.boundPort}"
                val totalClients = httpSt.activeClients + ftpSt.activeClients
                val clientLabel = if (totalClients == 1) "1 active connection" else "$totalClients active connections"
                buildRunningNotification(
                    title = "Files Transfer · HTTP & FTP active",
                    subtitle = "$httpUrl · $ftpUrl · $clientLabel",
                )
            }

            httpSt.isRunning -> {
                val primaryUrl = httpSt.urls.firstOrNull() ?: "http://127.0.0.1:${httpSt.boundPort}"
                val clientLabel = if (httpSt.activeClients == 1) "1 active request" else "${httpSt.activeClients} active requests"
                val transfers = if (httpSt.uploadsCount > 0 || httpSt.downloadsCount > 0) {
                    " · ↑ ${httpSt.uploadsCount} ↓ ${httpSt.downloadsCount}"
                } else {
                    ""
                }
                buildRunningNotification(
                    title = "Files HTTP · $primaryUrl",
                    subtitle = "$clientLabel$transfers",
                )
            }

            else -> {
                val primaryUrl = ftpSt.urls.firstOrNull() ?: "ftp://127.0.0.1:${ftpSt.boundPort}"
                val clientLabel = if (ftpSt.activeClients == 1) "1 client" else "${ftpSt.activeClients} clients"
                val transfers = if (ftpSt.uploadsCount > 0 || ftpSt.downloadsCount > 0) {
                    " · ↑ ${ftpSt.uploadsCount} ↓ ${ftpSt.downloadsCount}"
                } else {
                    ""
                }
                buildRunningNotification(
                    title = "Files FTP · $primaryUrl",
                    subtitle = "$clientLabel$transfers",
                )
            }
        }
    }

    private fun refreshRunningNotification() {
        val notif = currentRunningNotificationOrNull() ?: return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_RUNNING, notif)
    }

    private fun buildRunningNotification(title: String, subtitle: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("open_tab", "transfer")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, FtpServerService::class.java).setAction(ACTION_STOP_ALL),
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
                putExtra("open_tab", "transfer")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Wireless Transfer was stopped by the system")
            .setContentText("Tap to open Transfer and restart")
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
                    "Wireless Transfer",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Active status and Stop action for Files HTTP & FTP LAN servers"
                }
                nm.createNotificationChannel(ch)
            }
        }
    }
}
