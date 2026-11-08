package com.minifile.explorer.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.minifile.explorer.BuildConfig
import com.minifile.explorer.core.peer.HostDecision
import com.minifile.explorer.core.peer.PeerDiscoveryEngine
import com.minifile.explorer.core.peer.PeerInfo
import com.minifile.explorer.data.LanAddressResolver
import com.minifile.explorer.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** The LocalOnlyHotspot this device hosts (spec §3.4): interface, gateway IPv4 and real prefix length. */
data class LocalHotspotBinding(
    val interfaceName: String,
    val ip: Inet4Address,
    val prefixLength: Int,
)

/**
 * Android lifecycle & network-binding wrapper around pure-JVM [PeerDiscoveryEngine].
 *
 * Implements MUST-2 (hotspot / unvalidated Wi-Fi routing):
 * - Tracks `TRANSPORT_WIFI` [Network]s **without** `NET_CAPABILITY_INTERNET` or `NET_CAPABILITY_VALIDATED`
 *   requirements so joining a phone hotspot with mobile data off still binds discovery & HTTP sockets
 *   to `wlan0` instead of cellular `rmnet0`.
 * - When no `TRANSPORT_WIFI` [Network] exists (this device IS the hotspot AP on `ap0`/`swlan0`),
 *   skips `bindSocket` so Android's `local_network` tethering route handles traffic.
 * - Recreates the discovery UDP socket via [PeerDiscoveryEngine.rebindSocket] whenever the tracked
 *   Wi-Fi [Network] changes (`onAvailable` / `onLost`, `#80` note 1).
 * - Holds [WifiManager.MulticastLock] only while receiver or browser role is active (`SHOULD-1`).
 * - Spec §3.4 (v1.0.18): [pinnedNetwork] (set while joined to a peer hotspot via `WifiNetworkSpecifier`) wins for
 *   both TCP and UDP; while this device hosts a LocalOnlyHotspot ([localHotspot]) sockets to targets inside the
 *   hotspot subnet are bound to the hotspot IP **without** `Network.bindSocket` (fixes the STA+AP `wlan0`
 *   fwmark black-hole) and the UDP discovery socket skips the Wi-Fi bind so beacons reach `ap0` too.
 */
class PeerDiscoveryHolder(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val lanAddressResolver: LanAddressResolver,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val wifiNetworks = LinkedHashSet<Network>()

    @Volatile
    private var activeWifiNetwork: Network? = null

    /** Specifier network this device joined as a hotspot client (never `bindProcessToNetwork`). */
    @Volatile
    var pinnedNetwork: Network? = null
        private set

    /** LocalOnlyHotspot this device currently hosts. */
    @Volatile
    var localHotspot: LocalHotspotBinding? = null
        private set

    private var networkCallbackRegistered = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private var receiverEnabled = false
    private var browserRefCount = 0

    val engine: PeerDiscoveryEngine = PeerDiscoveryEngine(
        selfIdProvider = { settingsRepository.settings.value.peerDeviceId },
        selfNameProvider = { settingsRepository.settings.value.peerDeviceName },
        lanIpsProvider = { lanAddressResolver.currentIpv4List() },
        portProvider = {
            val st = HttpServerHolder.state.value
            if (st.isRunning && st.boundPort in 1..65535) st.boundPort else null
        },
        readOnlyProvider = {
            val s = settingsRepository.settings.value
            s.httpReadOnly || s.ftpReadOnly
        },
        appVersion = BuildConfig.VERSION_NAME,
        datagramSocketBinder = ::bindDatagramSocket,
        socketBinder = ::bindTcpSocket,
        onPortFallback = { boundPort ->
            HttpServerHolder.logInfo(
                "discovery",
                "Discovery port 21210 busy — beacon-only (bound $boundPort)",
            )
        },
    )

    val peers: StateFlow<List<PeerInfo>> = engine.peers
    val isScanning: StateFlow<Boolean> = engine.isScanning

    private val wifiNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            var changed = false
            synchronized(lock) {
                wifiNetworks.add(network)
                val latest = wifiNetworks.lastOrNull()
                if (activeWifiNetwork != latest) {
                    activeWifiNetwork = latest
                    changed = true
                }
            }
            if (changed) {
                engine.rebindSocket()
            }
        }

        override fun onLost(network: Network) {
            var changed = false
            synchronized(lock) {
                wifiNetworks.remove(network)
                val latest = wifiNetworks.lastOrNull()
                if (activeWifiNetwork != latest) {
                    activeWifiNetwork = latest
                    changed = true
                }
            }
            if (changed) {
                engine.rebindSocket()
            }
        }
    }

    init {
        registerWifiNetworkCallback()
        scope.launch {
            settingsRepository.settings
                .map { Triple(it.peerDeviceName, it.httpReadOnly, it.ftpReadOnly) }
                .distinctUntilChanged()
                .collect {
                    engine.notifyMetadataChanged()
                }
        }
    }

    fun setPinnedNetwork(network: Network?) {
        if (pinnedNetwork == network) return
        pinnedNetwork = network
        engine.rebindSocket()
    }

    fun setLocalHotspot(binding: LocalHotspotBinding?) {
        if (localHotspot == binding) return
        localHotspot = binding
        engine.rebindSocket()
    }

    fun bindDatagramSocket(socket: DatagramSocket) {
        pinnedNetwork?.let { net ->
            runCatching { net.bindSocket(socket) }
            return
        }
        // Hosting LOHS: keep the UDP socket unbound so MFP1 beacons/queries also reach the `ap0` broadcast.
        if (localHotspot != null) return
        val net = activeWifiNetwork ?: return
        runCatching { net.bindSocket(socket) }
    }

    fun bindTcpSocket(socket: Socket, target: InetAddress) {
        pinnedNetwork?.let { net ->
            runCatching { net.bindSocket(socket) }
            return
        }
        val hs = localHotspot
        if (hs != null && target is Inet4Address &&
            HostDecision.inSubnet(target.address, hs.ip.address, hs.prefixLength)
        ) {
            // Follow the `local_network` policy route to the soft-AP interface instead of the `wlan0` table.
            runCatching { socket.bind(InetSocketAddress(hs.ip, 0)) }
            return
        }
        if (target.isLoopbackAddress) return
        val net = activeWifiNetwork ?: return
        runCatching { net.bindSocket(socket) }
    }

    fun setReceiverRole(enabled: Boolean) {
        synchronized(lock) {
            receiverEnabled = enabled
            updateMulticastLockLocked()
        }
        engine.setReceiverRole(enabled)
    }

    fun acquireBrowser() {
        val shouldEnable: Boolean
        synchronized(lock) {
            browserRefCount += 1
            shouldEnable = browserRefCount == 1
            updateMulticastLockLocked()
        }
        if (shouldEnable) {
            engine.setBrowserRole(true)
        }
    }

    fun releaseBrowser() {
        val shouldDisable: Boolean
        synchronized(lock) {
            if (browserRefCount > 0) {
                browserRefCount -= 1
            }
            shouldDisable = browserRefCount == 0
            updateMulticastLockLocked()
        }
        if (shouldDisable) {
            engine.setBrowserRole(false)
        }
    }

    fun scanSubnet() {
        val configuredHttpPort = settingsRepository.settings.value.httpPort
        engine.launchScanSubnet(extraPorts = listOf(configuredHttpPort))
    }

    suspend fun probeAddress(
        rawAddress: String,
        source: com.minifile.explorer.core.peer.PeerSource = com.minifile.explorer.core.peer.PeerSource.MANUAL,
        timeoutMs: Int = 3_000,
    ): PeerInfo {
        return engine.probeAddress(
            rawAddress = rawAddress,
            defaultPort = PeerDiscoveryEngine.DEFAULT_HTTP_PORT,
            source = source,
            timeoutMs = timeoutMs,
        )
    }

    private fun registerWifiNetworkCallback() {
        synchronized(lock) {
            if (networkCallbackRegistered) return
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            runCatching {
                cm.registerNetworkCallback(req, wifiNetworkCallback)
                networkCallbackRegistered = true
            }
        }
    }

    private fun updateMulticastLockLocked() {
        val wantHeld = receiverEnabled || browserRefCount > 0
        if (wantHeld) {
            if (multicastLock == null) {
                val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wm?.createMulticastLock("minifile:peer-discovery")?.apply {
                    setReferenceCounted(false)
                }
            }
            runCatching {
                val lockObj = multicastLock
                if (lockObj != null && !lockObj.isHeld) {
                    lockObj.acquire()
                }
            }
        } else {
            runCatching {
                multicastLock?.let { if (it.isHeld) it.release() }
            }
        }
    }
}
