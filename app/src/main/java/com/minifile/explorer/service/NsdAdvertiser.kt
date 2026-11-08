package com.minifile.explorer.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.minifile.explorer.BuildConfig
import com.minifile.explorer.core.peer.PeerSource
import com.minifile.explorer.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Bonjour / DNS-SD parity with iOS (spec D1): registers `_minifile._tcp` while the HTTP share runs
 * (TXT `id`, `ver`, `ro`, `proto=1`, `app=Files`) so iOS `NWBrowser` finds Android instantly, and browses
 * `_minifile._tcp` while peer browsing is active so iOS devices (which never send MFP1) appear in the LAN list.
 * Resolved hosts are verified via `GET /api/peer/info` through [PeerDiscoveryHolder] (LAN firewall applies).
 */
class NsdAdvertiser(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val peerDiscoveryHolder: PeerDiscoveryHolder,
) {
    private val appContext = context.applicationContext
    private val nsd: NsdManager? = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var registeredPort: Int = -1
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = Channel<NsdServiceInfo>(capacity = 32)
    private var registeredName: String? = null

    init {
        // Pre-API-34 NsdManager allows only one resolve in flight: serialize through a queue.
        scope.launch {
            for (info in resolveQueue) {
                val resolved = resolveOnce(info) ?: continue
                @Suppress("DEPRECATION")
                val host = resolved.host?.hostAddress?.substringBefore('%') ?: continue
                val port = resolved.port.takeIf { it in 1..65535 } ?: continue
                runCatching {
                    peerDiscoveryHolder.engine.probe(host = host, port = port, source = PeerSource.PROBE, timeoutMs = 2_500)
                }
            }
        }
    }

    fun register(port: Int) {
        val mgr = nsd ?: return
        synchronized(lock) {
            if (registrationListener != null && registeredPort == port) return
            unregisterLocked()
            val s = settingsRepository.settings.value
            val info = NsdServiceInfo().apply {
                serviceName = s.peerDeviceName.ifBlank { "Files" }.take(63)
                serviceType = SERVICE_TYPE
                setPort(port)
                setAttribute("id", s.peerDeviceId)
                setAttribute("ver", BuildConfig.VERSION_NAME)
                setAttribute("ro", if (s.httpReadOnly || s.ftpReadOnly) "1" else "0")
                setAttribute("proto", "1")
                setAttribute("app", "Files")
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    synchronized(lock) { registeredName = serviceInfo.serviceName }
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    synchronized(lock) {
                        if (registrationListener === this) {
                            registrationListener = null
                            registeredPort = -1
                        }
                    }
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            }
            val ok = runCatching { mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess
            if (ok) {
                registrationListener = listener
                registeredPort = port
            }
        }
    }

    fun unregister() = synchronized(lock) { unregisterLocked() }

    private fun unregisterLocked() {
        val l = registrationListener ?: return
        registrationListener = null
        registeredPort = -1
        registeredName = null
        runCatching { nsd?.unregisterService(l) }
    }

    fun startBrowse() {
        val mgr = nsd ?: return
        synchronized(lock) {
            if (discoveryListener != null) return
            val listener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    val own = synchronized(lock) { registeredName }
                    if (own != null && serviceInfo.serviceName == own) return
                    resolveQueue.trySend(serviceInfo)
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    synchronized(lock) { if (discoveryListener === this) discoveryListener = null }
                }

                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            val ok = runCatching { mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess
            if (ok) discoveryListener = listener
        }
    }

    fun stopBrowse() {
        val l = synchronized(lock) {
            val cur = discoveryListener
            discoveryListener = null
            cur
        } ?: return
        runCatching { nsd?.stopServiceDiscovery(l) }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveOnce(info: NsdServiceInfo): NsdServiceInfo? {
        val mgr = nsd ?: return null
        return withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val l = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(serviceInfo)
                    }
                }
                runCatching { mgr.resolveService(info, l) }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    companion object {
        const val SERVICE_TYPE = "_minifile._tcp"
        private const val RESOLVE_TIMEOUT_MS = 5_000L
    }
}
