package com.minifile.explorer.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.minifile.explorer.BuildConfig
import com.minifile.explorer.core.net.GatewayCandidates
import com.minifile.explorer.core.net.LocalIfaceSubnet
import com.minifile.explorer.core.net.LocalSubnetRouter
import com.minifile.explorer.core.net.TcpBindDecision
import com.minifile.explorer.core.net.TrackedNetSubnet
import com.minifile.explorer.core.net.UdpBindDecision
import com.minifile.explorer.core.peer.PeerAnnounceWire
import com.minifile.explorer.core.peer.PeerDiscoveryEngine
import com.minifile.explorer.core.peer.PeerHttpClient
import com.minifile.explorer.core.peer.PeerInfo
import com.minifile.explorer.core.peer.PeerSource
import com.minifile.explorer.data.LanAddressResolver
import com.minifile.explorer.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
 * - Tracks every `TRANSPORT_WIFI` / `TRANSPORT_ETHERNET` [Network] (and its [LinkProperties]) **without**
 *   `NET_CAPABILITY_INTERNET` / `VALIDATED` requirements so joining a phone hotspot with mobile data off still binds
 *   discovery & HTTP sockets to `wlan0` instead of cellular `rmnet0`.
 * - Outbound peer TCP sockets are routed by pure-JVM [LocalSubnetRouter]: a tracked network that owns the target
 *   subnet wins (`Network.bindSocket` = exact interface + `tun0` VPN bypass); a target on an untracked local interface
 *   (System Personal Hotspot `wlan1`/`ap0`/`ap_br_*`, LOHS, USB tether, Wi-Fi Direct) is bound to that interface's
 *   source IP and **never** to `wlan0` (fixes "iPhone returned HTTP 302" with STA+AP concurrency).
 * - The UDP discovery socket stays unbound whenever such an untracked interface exists so `MFP1` broadcasts reach
 *   every interface; it is recreated via [PeerDiscoveryEngine.rebindSocket] whenever tracked networks change.
 * - On Wi-Fi / interface transitions, cached LAN peers off every live local subnet are pruned.
 * - Automatic hotspot gateway pairing: when the device joins a Wi-Fi (e.g. the system Camera scanned a `WIFI:` QR of an
 *   iPhone Personal Hotspot) while Files is in the foreground, the gateway is probed for `GET /api/peer/info`; a
 *   Files host is upserted into Nearby Devices and this phone announces itself (`POST /api/peer/announce`).
 * - Holds [WifiManager.MulticastLock] only while receiver or browser role is active (`SHOULD-1`).
 * - Spec §3.4 (v1.0.18): [pinnedNetwork] (set while joined to a peer hotspot via `WifiNetworkSpecifier`) wins for
 *   both TCP and UDP.
 */
class PeerDiscoveryHolder(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val lanAddressResolver: LanAddressResolver,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private class TrackedNet(
        val network: Network,
        @Volatile var linkProperties: LinkProperties? = null,
        @Volatile var isWifi: Boolean = true,
        @Volatile var metered: Boolean = false,
    )

    /** Insertion-ordered tracked Wi-Fi / Ethernet networks (latest last). Guarded by [lock]. */
    private val trackedNets = LinkedHashMap<Network, TrackedNet>()

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

    @Volatile
    private var foreground = false

    /** Short-lived cache of live local interfaces (enumerating `NetworkInterface` per socket would be wasteful). */
    @Volatile
    private var ifaceCache: Pair<Long, List<LocalIfaceSubnet>>? = null

    private var gatewayProbeJob: Job? = null
    private var pruneJob: Job? = null

    /**
     * Tethering (System Personal Hotspot / USB tether) raises no `ConnectivityManager` callback, so while discovery is
     * active a light watcher re-enumerates interfaces and rebinds the UDP socket when the untracked set changes.
     */
    private var ifaceWatchJob: Job? = null

    /** `(handle, ownIpv4, gateway)` of the last Wi-Fi the gateway prober ran for. */
    @Volatile
    private var lastGatewayKey: String? = null

    /** Network for which this holder auto-started the HTTP receiver (stopped again when that Wi-Fi goes away). */
    @Volatile
    private var httpStartedForNetwork: Network? = null

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

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val cm = connectivityManager()
            val caps = runCatching { cm?.getNetworkCapabilities(network) }.getOrNull()
            val lp = runCatching { cm?.getLinkProperties(network) }.getOrNull()
            synchronized(lock) {
                val t = trackedNets.remove(network) ?: TrackedNet(network)
                if (lp != null) t.linkProperties = lp
                if (caps != null) applyCaps(t, caps)
                trackedNets[network] = t
            }
            onTrackedNetworksChanged(network)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            synchronized(lock) { trackedNets[network]?.let { applyCaps(it, networkCapabilities) } }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            var changed = false
            synchronized(lock) {
                val t = trackedNets[network] ?: return@synchronized
                // Only IPv4 address / interface changes matter (DNS / route-MTU updates must not churn the socket).
                changed = ipv4Signature(t.linkProperties) != ipv4Signature(linkProperties)
                t.linkProperties = linkProperties
            }
            if (changed) onTrackedNetworksChanged(network, linkOnly = true)
        }

        override fun onLost(network: Network) {
            synchronized(lock) { trackedNets.remove(network) }
            if (httpStartedForNetwork == network) stopAutoStartedHttp()
            onTrackedNetworksChanged(null)
        }
    }

    init {
        registerNetworkCallback()
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
        invalidateIfaceCache()
        engine.rebindSocket()
    }

    // ---------------------------------------------------------------------------------------------
    // Socket binding
    // ---------------------------------------------------------------------------------------------

    fun bindDatagramSocket(socket: DatagramSocket) {
        val pinned = pinnedNetwork
        val active = activeWifiNetwork
        val decision = LocalSubnetRouter.decideUdp(
            hasPinnedNetwork = pinned != null,
            hostingLocalHotspot = localHotspot != null,
            hasActiveWifi = active != null,
            activeWifiIfaceName = active?.let { ifaceNameOf(it) },
            trackedSubnets = trackedSubnets(),
            localIfaces = localIfaces(),
        )
        when (decision) {
            UdpBindDecision.USE_PINNED_NETWORK -> pinned?.let { net -> runCatching { net.bindSocket(socket) } }
            UdpBindDecision.BIND_ACTIVE_WIFI -> active?.let { net -> runCatching { net.bindSocket(socket) } }
            UdpBindDecision.NO_EXPLICIT_BIND -> Unit
        }
    }

    fun bindTcpSocket(socket: Socket, target: InetAddress) {
        val pinned = pinnedNetwork
        if (target !is Inet4Address) {
            if (target.isLoopbackAddress) return
            val net = pinned ?: activeWifiNetwork ?: return
            runCatching { net.bindSocket(socket) }
            return
        }
        val tracked = trackedSubnets()
        val active = activeWifiNetwork
        val activeHandle = active?.networkHandle
        val decision = LocalSubnetRouter.decideTcp(
            targetIpv4 = target.address,
            hasPinnedNetwork = pinned != null,
            localHotspotSubnet = localHotspot?.let { LocalIfaceSubnet(it.interfaceName, it.ip.address, it.prefixLength, true) },
            trackedSubnets = tracked,
            localIfaces = localIfaces(),
            fallbackActiveWifiHandle = activeHandle,
            activeWifiSubnets = tracked.filter { it.handleId == activeHandle },
        )
        when (decision) {
            TcpBindDecision.UsePinnedNetwork -> pinned?.let { net -> runCatching { net.bindSocket(socket) } }
            is TcpBindDecision.BindTrackedNetwork -> {
                val net = networkForHandle(decision.handleId) ?: return
                // A non-bypassable VPN may refuse explicit selection (EPERM): fall back to default routing.
                runCatching { net.bindSocket(socket) }
            }
            is TcpBindDecision.BindLocalSourceIp -> runCatching {
                socket.bind(InetSocketAddress(InetAddress.getByAddress(decision.localIpv4), 0))
            }
            TcpBindDecision.NoExplicitBind -> Unit
        }
    }

    private fun connectivityManager(): ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private fun applyCaps(t: TrackedNet, caps: NetworkCapabilities) {
        t.isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        t.metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun ipv4Signature(lp: LinkProperties?): String? {
        lp ?: return null
        val addrs = lp.linkAddresses.filter { it.address is Inet4Address }.map { "${it.address.hostAddress}/${it.prefixLength}" }
        val gw = lp.routes.mapNotNull { (it.gateway as? Inet4Address)?.hostAddress }.distinct().sorted()
        return "${lp.interfaceName}|${addrs.sorted()}|$gw"
    }

    private fun networkForHandle(handle: Long): Network? = synchronized(lock) {
        trackedNets.keys.firstOrNull { it.networkHandle == handle }
    }

    private fun ifaceNameOf(network: Network): String? = synchronized(lock) {
        trackedNets[network]?.linkProperties?.interfaceName
    }

    /** IPv4 subnets of every tracked Wi-Fi / Ethernet network (from cached [LinkProperties]). */
    private fun trackedSubnets(): List<TrackedNetSubnet> {
        val snapshot = synchronized(lock) { trackedNets.values.toList() }
        val out = ArrayList<TrackedNetSubnet>()
        for (t in snapshot) {
            val lp = t.linkProperties ?: continue
            for (la in lp.linkAddresses) {
                val a = la.address as? Inet4Address ?: continue
                out += TrackedNetSubnet(
                    handleId = t.network.networkHandle,
                    ifaceName = lp.interfaceName,
                    ipv4 = a.address,
                    prefixLength = la.prefixLength,
                    isWifiOrEth = true,
                )
            }
        }
        return out
    }

    /** Live local IPv4 interfaces (cached for [IFACE_CACHE_MS]). */
    private fun localIfaces(): List<LocalIfaceSubnet> {
        val now = System.currentTimeMillis()
        ifaceCache?.let { (ts, list) -> if (now - ts in 0..IFACE_CACHE_MS) return list }
        val list = lanAddressResolver.allLocalIpv4Subnets().map { (name, addr, prefix) ->
            LocalIfaceSubnet(name, addr.address, prefix)
        }
        ifaceCache = now to list
        return list
    }

    private fun invalidateIfaceCache() {
        ifaceCache = null
    }

    private fun onTrackedNetworksChanged(changedNetwork: Network?, linkOnly: Boolean = false) {
        var activeChanged = false
        synchronized(lock) {
            val latest = trackedNets.values.lastOrNull { it.isWifi }?.network ?: trackedNets.keys.lastOrNull()
            if (activeWifiNetwork != latest) {
                activeWifiNetwork = latest
                activeChanged = true
            }
        }
        invalidateIfaceCache()
        if (activeChanged || linkOnly || changedNetwork == null) {
            engine.rebindSocket()
        }
        schedulePrune()
        if (changedNetwork != null) maybeProbeGateway(changedNetwork, force = false)
    }

    /** Prunes LAN peers that are off every live local subnet once interfaces have settled. */
    private fun schedulePrune() {
        synchronized(lock) {
            pruneJob?.cancel()
            pruneJob = scope.launch {
                delay(PRUNE_SETTLE_MS)
                invalidateIfaceCache()
                val subnets = localIfaces().map { it.ipv4 to it.prefixLength } +
                    trackedSubnets().map { it.ipv4 to it.prefixLength }
                engine.pruneOffSubnet(subnets)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Foreground / automatic hotspot gateway pairing
    // ---------------------------------------------------------------------------------------------

    /** Called from `MainActivity.onStart` / `onStop`. */
    fun setForeground(isForeground: Boolean) {
        foreground = isForeground
    }

    /** Called from `MainActivity.onResume`: re-checks the Wi-Fi gateway when the Wi-Fi changed while away. */
    fun onAppResumed() {
        foreground = true
        invalidateIfaceCache()
        schedulePrune()
        activeWifiNetwork?.let { maybeProbeGateway(it, force = false) }
    }

    private data class WifiSnapshot(
        val network: Network,
        val ownIpv4: String,
        val prefix: Int,
        val gateway: String?,
        val metered: Boolean,
    ) {
        val key: String get() = "${network.networkHandle}|$ownIpv4|$gateway"
    }

    private fun wifiSnapshot(network: Network): WifiSnapshot? {
        val t = synchronized(lock) { trackedNets[network] } ?: return null
        if (!t.isWifi) return null
        val lp = t.linkProperties ?: return null
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val own = la.address.hostAddress ?: return null
        val gw = lp.routes.firstOrNull { r ->
            val g = r.gateway
            g is Inet4Address && !g.isAnyLocalAddress && r.isDefaultRoute
        }?.gateway?.hostAddress ?: lp.routes.firstOrNull { r ->
            val g = r.gateway
            g is Inet4Address && !g.isAnyLocalAddress
        }?.gateway?.hostAddress
        return WifiSnapshot(network, own, la.prefixLength, gw, t.metered)
    }

    private fun maybeProbeGateway(network: Network, force: Boolean) {
        if (!foreground) return
        if (network == pinnedNetwork) return // in-app join runs its own host discovery
        val snap = wifiSnapshot(network) ?: return
        if (!force && snap.key == lastGatewayKey) return
        lastGatewayKey = snap.key
        synchronized(lock) {
            gatewayProbeJob?.cancel()
            gatewayProbeJob = scope.launch { runGatewayProbe(snap) }
        }
    }

    private suspend fun runGatewayProbe(snap: WifiSnapshot) {
        val hosts = GatewayCandidates.forWifi(snap.ownIpv4, snap.prefix, snap.gateway)
        if (hosts.isEmpty()) return
        val ports = GatewayCandidates.probePorts(snap.ownIpv4, snap.metered, settingsRepository.settings.value.httpPort)
            .let { if (GatewayCandidates.looksLikePhoneHotspot(snap.ownIpv4, snap.metered)) it else it.take(4) }
        var found: PeerInfo? = null
        repeat(GATEWAY_PROBE_ATTEMPTS) { attempt ->
            if (found != null) return@repeat
            if (attempt > 0) delay(GATEWAY_RETRY_MS)
            found = probeFirstGateway(hosts, ports)
        }
        val host = found ?: return
        HttpServerHolder.logInfo("discovery", "Found ${host.name} on Wi-Fi gateway ${host.endpoint}")
        announceTo(host, snap)
    }

    private suspend fun probeFirstGateway(hosts: List<String>, ports: List<Int>): PeerInfo? = coroutineScope {
        val jobs = hosts.flatMap { h ->
            ports.map { p ->
                async {
                    runCatching {
                        engine.probe(host = h, port = p, source = PeerSource.PROBE, timeoutMs = GATEWAY_PROBE_TIMEOUT_MS)
                    }.getOrNull()
                }
            }
        }
        jobs.awaitAll().firstOrNull { it != null }
    }

    /**
     * Announces this phone to the hotspot host [host] (`POST /api/peer/announce`) so its Hotspot Direct screen lists
     * us under "Joined devices". Uses the running HTTP receiver; when none runs, it is started only while Files is in
     * the foreground with Transfer / Send open and the Wi-Fi looks like a phone hotspot.
     */
    private suspend fun announceTo(host: PeerInfo, snap: WifiSnapshot) {
        val myPort = ensureHttpPortForAnnounce(snap) ?: return
        val s = settingsRepository.settings.value
        val url = PeerAnnounceWire.url(host.host, host.port, s.peerDeviceId, s.peerDeviceName, myPort)
        val body = PeerAnnounceWire.body(s.peerDeviceId, s.peerDeviceName, myPort).toByteArray(Charsets.UTF_8)
        repeat(ANNOUNCE_ATTEMPTS) { attempt ->
            val ok = runCatching {
                PeerHttpClient(
                    connectTimeoutMs = ANNOUNCE_TIMEOUT_MS,
                    soTimeoutMs = ANNOUNCE_TIMEOUT_MS,
                    socketBinder = ::bindTcpSocket,
                ).use { c ->
                    c.postStream(url, body.size.toLong(), "application/json", expectContinue = false) { out ->
                        out.write(body)
                    }.status in 200..299
                }
            }.getOrDefault(false)
            if (ok) {
                HttpServerHolder.logInfo("discovery", "Announced to ${host.name} (${host.endpoint})")
                return
            }
            if (attempt < ANNOUNCE_ATTEMPTS - 1) delay(ANNOUNCE_RETRY_MS)
        }
    }

    private suspend fun ensureHttpPortForAnnounce(snap: WifiSnapshot): Int? {
        val st = HttpServerHolder.state.value
        if (st.isRunning && st.boundPort in 1..65535) return st.boundPort
        val browsing = synchronized(lock) { browserRefCount > 0 || receiverEnabled }
        if (!foreground || !browsing || !GatewayCandidates.looksLikePhoneHotspot(snap.ownIpv4, snap.metered)) return null
        val started = runCatching { FtpServerService.startHttp(appContext) }.isSuccess
        if (!started) {
            runCatching { HttpServerHolder.markStopped(null) }
            return null
        }
        httpStartedForNetwork = snap.network
        return withTimeoutOrNull(HTTP_START_TIMEOUT_MS) {
            HttpServerHolder.state.first { it.isRunning && it.boundPort in 1..65535 }.boundPort
        }
    }

    private fun stopAutoStartedHttp() {
        httpStartedForNetwork = null
        val st = HttpServerHolder.state.value
        if (!st.isRunning || st.activeClients > 0 || localHotspot != null) return
        runCatching { FtpServerService.stopHttp(appContext) }
    }

    // ---------------------------------------------------------------------------------------------
    // Roles
    // ---------------------------------------------------------------------------------------------

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
        source: PeerSource = PeerSource.MANUAL,
        timeoutMs: Int = 3_000,
    ): PeerInfo {
        return engine.probeAddress(
            rawAddress = rawAddress,
            defaultPort = PeerDiscoveryEngine.DEFAULT_HTTP_PORT,
            source = source,
            timeoutMs = timeoutMs,
        )
    }

    /**
     * Hotspot Direct open announce (spec §2.4): a device that joined this phone's LocalOnlyHotspot (typically an
     * iPhone via the Camera `WIFI:` QR) announced its HTTP [port]. Probes `GET /api/peer/info` (a few retries while
     * its server settles) and upserts it with source [PeerSource.PROBE] so the browser keeps re-probing it and it
     * shows under Hotspot Direct "Joined devices". If every probe fails, the announced identity is upserted as an
     * [PeerSource.ANNOUNCE] peer (expires after the probe TTL unless re-announced). Returns the stored peer.
     */
    suspend fun probeAnnouncedPeer(
        clientIp: String,
        port: Int,
        id: String,
        name: String,
        os: String?,
        attempts: Int = ANNOUNCE_PROBE_ATTEMPTS,
    ): PeerInfo? {
        val host = clientIp.substringBefore('%')
        if (host.isEmpty() || port !in 1..65535) return null
        repeat(attempts.coerceAtLeast(1)) { i ->
            val probed = runCatching {
                engine.probe(host = host, port = port, source = PeerSource.PROBE, timeoutMs = ANNOUNCE_PROBE_TIMEOUT_MS)
            }.getOrNull()
            if (probed != null) {
                if (probed.platform == null && os != null) {
                    val withOs = probed.copy(platform = os)
                    engine.upsert(withOs)
                    return withOs
                }
                return probed
            }
            if (i < attempts - 1) delay(ANNOUNCE_PROBE_RETRY_MS)
        }
        val fallback = PeerInfo(
            id = id,
            name = name,
            host = host,
            port = port,
            version = "",
            readOnly = false,
            source = PeerSource.ANNOUNCE,
            lastSeenMs = System.currentTimeMillis(),
            platform = os,
        )
        engine.upsert(fallback)
        return fallback
    }

    /** `true` when the system reports an active VPN (`TRANSPORT_VPN`) — used to word interception errors. */
    @Suppress("DEPRECATION")
    fun isVpnActive(): Boolean = runCatching {
        val cm = connectivityManager() ?: return false
        cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrDefault(false)

    private fun registerNetworkCallback() {
        synchronized(lock) {
            if (networkCallbackRegistered) return
            val cm = connectivityManager() ?: return
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            runCatching {
                cm.registerNetworkCallback(req, networkCallback)
                networkCallbackRegistered = true
            }
        }
    }

    private fun updateIfaceWatchLocked(wantRunning: Boolean) {
        if (!wantRunning) {
            ifaceWatchJob?.cancel()
            ifaceWatchJob = null
            return
        }
        if (ifaceWatchJob?.isActive == true) return
        ifaceWatchJob = scope.launch {
            var lastSignature: String? = null
            while (true) {
                val sig = runCatching {
                    lanAddressResolver.allLocalIpv4Subnets()
                        .map { (name, addr, prefix) -> "$name/${addr.hostAddress}/$prefix" }
                        .sorted()
                        .joinToString(",")
                }.getOrNull()
                if (sig != null && lastSignature != null && sig != lastSignature) {
                    invalidateIfaceCache()
                    engine.rebindSocket()
                    schedulePrune()
                }
                if (sig != null) lastSignature = sig
                delay(IFACE_WATCH_INTERVAL_MS)
            }
        }
    }

    private fun updateMulticastLockLocked() {
        val wantHeld = receiverEnabled || browserRefCount > 0
        updateIfaceWatchLocked(wantHeld)
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

    companion object {
        private const val ANNOUNCE_PROBE_ATTEMPTS = 4
        private const val ANNOUNCE_PROBE_TIMEOUT_MS = 2_500
        private const val ANNOUNCE_PROBE_RETRY_MS = 600L

        private const val IFACE_CACHE_MS = 2_000L
        private const val IFACE_WATCH_INTERVAL_MS = 4_000L
        private const val PRUNE_SETTLE_MS = 600L

        private const val GATEWAY_PROBE_TIMEOUT_MS = 450
        private const val GATEWAY_PROBE_ATTEMPTS = 2
        private const val GATEWAY_RETRY_MS = 800L
        private const val ANNOUNCE_ATTEMPTS = 3
        private const val ANNOUNCE_TIMEOUT_MS = 2_000
        private const val ANNOUNCE_RETRY_MS = 700L
        private const val HTTP_START_TIMEOUT_MS = 5_000L
    }
}
