package com.minifile.explorer.core.peer

import com.minifile.explorer.core.ftp.FtpAccessDeniedException
import com.minifile.explorer.core.ftp.FtpServerEngine
import com.minifile.explorer.core.model.Clock
import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.MiniJson
import com.minifile.explorer.core.vfs.PathUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.URI

/**
 * Pure-JVM UDP LAN peer discovery + HTTP `/api/peer/info` prober (`zero android.* imports`).
 *
 * Wire format:
 * - Magic `"MFP1"` (4 ASCII bytes) + UTF-8 [MiniJson] object, total datagram `<= 512` bytes.
 * - Peer `host` is ALWAYS derived from the UDP datagram source IP (never payload JSON) and checked
 *   with [FtpServerEngine.isAllowedClientAddress].
 */
class PeerDiscoveryEngine(
    private val selfIdProvider: () -> String,
    private val selfNameProvider: () -> String,
    private val lanIpsProvider: () -> List<String>,
    private val portProvider: () -> Int?,
    private val readOnlyProvider: () -> Boolean,
    private val appVersion: String = DEFAULT_APP_VERSION,
    private val discoveryPort: Int = DEFAULT_DISCOVERY_PORT,
    private val targetDiscoveryPort: Int = discoveryPort,
    private val extraBroadcastTargets: List<InetSocketAddress> = emptyList(),
    private val datagramSocketBinder: (DatagramSocket) -> Unit = {},
    private val socketBinder: (Socket) -> Unit = {},
    private val onPortFallback: ((boundPort: Int) -> Unit)? = null,
    private val clock: Clock = Clock.SYSTEM,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {

    constructor(
        selfId: String,
        selfName: String,
        lanIpsProvider: () -> List<String>,
        portProvider: () -> Int?,
        readOnlyProvider: () -> Boolean,
        datagramSocketBinder: (DatagramSocket) -> Unit = {},
    ) : this(
        selfIdProvider = { selfId },
        selfNameProvider = { selfName },
        lanIpsProvider = lanIpsProvider,
        portProvider = portProvider,
        readOnlyProvider = readOnlyProvider,
        datagramSocketBinder = datagramSocketBinder,
    )

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val stateLock = Any()

    private val peerMap = LinkedHashMap<String, PeerInfo>()
    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val peers: StateFlow<List<PeerInfo>> = _peers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // Per-source-IP unicast reply rate limiter (<= 5 replies / 1000 ms)
    private val queryReplyLog = LinkedHashMap<String, QueryRateWindow>()

    @Volatile
    private var receiverActive: Boolean = false

    @Volatile
    private var browserActive: Boolean = false

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    var boundDiscoveryPort: Int = -1
        private set

    private var receiveJob: Job? = null
    private var receiverBeaconJob: Job? = null
    private var browserQueryJob: Job? = null
    private var maintenanceJob: Job? = null
    private var scanJob: Job? = null

    val isAnyRoleActive: Boolean
        get() = synchronized(stateLock) { receiverActive || browserActive }

    fun setReceiverRole(enabled: Boolean) {
        synchronized(stateLock) {
            if (receiverActive == enabled) {
                if (enabled) triggerImmediateBeaconLocked()
                return
            }
            receiverActive = enabled
            syncRolesLocked()
        }
    }

    fun setBrowserRole(enabled: Boolean) {
        synchronized(stateLock) {
            if (browserActive == enabled) return
            browserActive = enabled
            syncRolesLocked()
        }
    }

    /**
     * Recreates and rebinds the [DatagramSocket] when Android's tracked Wi-Fi `Network` changes
     * (`onAvailable` / `onLost`) so discovery never stays stuck on a dead interface (`#80` note 1).
     */
    fun rebindSocket() {
        synchronized(stateLock) {
            if (!receiverActive && !browserActive) return
            closeSocketLocked()
            ensureSocketLocked()
            if (receiverActive) {
                restartReceiverBeaconLoopLocked()
            }
            if (browserActive) {
                restartBrowserQueryLoopLocked()
            }
        }
    }

    fun notifyMetadataChanged() {
        synchronized(stateLock) {
            if (receiverActive) {
                triggerImmediateBeaconLocked()
            }
        }
    }

    fun clearPeers() {
        synchronized(stateLock) {
            peerMap.clear()
            publishPeersLocked()
        }
    }

    internal fun evictExpired(nowMs: Long = clock.nowMs()) {
        synchronized(stateLock) {
            val changed = pruneExpiredLocked(nowMs)
            if (changed) {
                publishPeersLocked()
            }
        }
    }

    /**
     * Parses `host[:port]` (or `http://host[:port]`) and probes `GET /api/peer/info`.
     * Enforces [FtpServerEngine.isAllowedClientAddress] (`MUST-3`) and `proto` / status rules (`SHOULD-5`).
     */
    suspend fun probeAddress(
        rawAddress: String,
        defaultPort: Int = DEFAULT_HTTP_PORT,
        source: PeerSource = PeerSource.MANUAL,
        timeoutMs: Int = PROBE_TIMEOUT_MS,
    ): PeerInfo {
        val (host, port) = parseHostAndPort(rawAddress, defaultPort)
        return probe(host = host, port = port, source = source, timeoutMs = timeoutMs)
    }

    suspend fun probe(
        host: String,
        port: Int,
        source: PeerSource = PeerSource.MANUAL,
        timeoutMs: Int = PROBE_TIMEOUT_MS,
    ): PeerInfo = withContext(ioDispatcher) {
        val cleanHost = host.trim().removeSurrounding("[", "]").substringBefore('%')
        if (cleanHost.isEmpty()) {
            throw PeerTransferException(null, "Enter a device IP address")
        }
        if (port !in 1..65535) {
            throw PeerTransferException(null, "Port must be between 1 and 65535")
        }

        val addr = try {
            InetAddress.getByName(cleanHost)
        } catch (e: IOException) {
            throw PeerTransferException(null, "$cleanHost:$port not reachable", e)
        }
        if (!FtpServerEngine.isAllowedClientAddress(addr)) {
            throw PeerTransferException(null, "Only local network (LAN) addresses are allowed")
        }

        val resolvedIp = addr.hostAddress?.substringBefore('%') ?: cleanHost
        val hostUrlPart = if (resolvedIp.contains(':')) "[$resolvedIp]" else resolvedIp
        val endpointLabel = "$hostUrlPart:$port"
        val infoUrl = "http://$endpointLabel/api/peer/info"

        val resp = try {
            PeerHttpClient(
                connectTimeoutMs = timeoutMs,
                soTimeoutMs = timeoutMs,
                socketBinder = socketBinder,
            ).use { client ->
                client.get(infoUrl, timeoutMs = timeoutMs, connectTimeoutOverrideMs = timeoutMs)
            }
        } catch (e: FtpAccessDeniedException) {
            throw PeerTransferException(null, "Only local network (LAN) addresses are allowed", e)
        } catch (e: IOException) {
            throw PeerTransferException(null, "$endpointLabel not reachable", e)
        }

        when (resp.status) {
            404 -> throw PeerTransferException(404, "$endpointLabel needs Files 1.0.6+")
            401 -> throw PeerTransferException(401, "$endpointLabel requires a PIN — update Files")
        }
        if (resp.status != 200) {
            throw PeerTransferException(resp.status, "$endpointLabel returned HTTP ${resp.status}")
        }

        val obj = try {
            MiniJson.parse(resp.bodyText) as? JsonValue.Obj
        } catch (_: Exception) {
            null
        } ?: throw PeerTransferException(null, "$endpointLabel is not a Files peer")

        val app = obj.str("app")
        if (app != null && app != "Files") {
            throw PeerTransferException(null, "$endpointLabel is not a Files peer")
        }

        val rawName = obj.str("name").orEmpty()
        val displayName = PeerPathPolicy.sanitizeDisplayName(rawName, MAX_NAME_CHARS)
            .ifEmpty { resolvedIp }
        val proto = obj.int("proto", 1)
        if (proto > SUPPORTED_PROTO) {
            throw PeerTransferException(null, "$displayName needs newer Files")
        }
        if (proto < 1) {
            throw PeerTransferException(null, "$endpointLabel reported unsupported protocol")
        }

        val id = obj.str("id")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_ID_CHARS }
            ?: "$resolvedIp:$port"
        if (id == selfIdProvider()) {
            throw PeerTransferException(null, "That address is this device")
        }
        val ver = PeerPathPolicy.sanitizeDisplayName(obj.str("ver").orEmpty(), MAX_VER_CHARS)
            .ifEmpty { DEFAULT_APP_VERSION }
        val readOnly = obj.bool("readOnly", false)

        val info = PeerInfo(
            id = id,
            name = displayName,
            host = resolvedIp,
            port = port,
            version = ver,
            readOnly = readOnly,
            source = source,
            lastSeenMs = clock.nowMs(),
            proto = proto,
        )
        synchronized(stateLock) {
            upsertPeerLocked(info)
            publishPeersLocked()
        }
        info
    }

    /**
     * Manual `/24` TCP sweep across `{extraPorts, 8080..8090}` deduped (`SHOULD-2`),
     * 300 ms connect timeout, 48 parallel workers, followed by `GET /api/peer/info`.
     */
    suspend fun scanSubnet(extraPorts: Collection<Int> = emptyList()): List<PeerInfo> = withContext(ioDispatcher) {
        if (!_isScanning.compareAndSet(false, true)) {
            return@withContext peers.value
        }
        try {
            val ports = buildList {
                for (p in extraPorts) {
                    if (p in 1..65535) add(p)
                }
                for (p in 8080..8090) {
                    add(p)
                }
            }.distinct()

            val ownIps = lanIpsProvider().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val prefixes = LinkedHashSet<String>()
            for (ip in ownIps) {
                val parts = ip.split('.')
                if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
                    val b0 = parts[0].toInt()
                    if (b0 != 127) {
                        prefixes.add("${parts[0]}.${parts[1]}.${parts[2]}")
                    }
                }
                if (prefixes.size >= 2) break
            }

            if (prefixes.isEmpty() || ports.isEmpty()) {
                return@withContext peers.value
            }

            val sem = Semaphore(SCAN_CONCURRENCY)
            // Port-major order checks common port 8080 across all hosts first
            coroutineScope {
                for (port in ports) {
                    for (prefix in prefixes) {
                        for (hostOctet in 1..254) {
                            val candidateIp = "$prefix.$hostOctet"
                            if (candidateIp in ownIps) continue
                            launch {
                                sem.withPermit {
                                    if (isTcpPortOpen(candidateIp, port, SCAN_CONNECT_TIMEOUT_MS)) {
                                        runCatching {
                                            probe(
                                                host = candidateIp,
                                                port = port,
                                                source = PeerSource.PROBE,
                                                timeoutMs = PROBE_TIMEOUT_MS,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            peers.value
        } finally {
            _isScanning.value = false
        }
    }

    fun launchScanSubnet(extraPorts: Collection<Int> = emptyList()) {
        synchronized(stateLock) {
            if (scanJob?.isActive == true) return
            scanJob = scope.launch {
                scanSubnet(extraPorts)
            }
        }
    }

    override fun close() {
        synchronized(stateLock) {
            receiverActive = false
            browserActive = false
            syncRolesLocked()
            scanJob?.cancel()
            scanJob = null
        }
        scope.cancel()
    }

    private fun isTcpPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        val sock = Socket()
        return try {
            socketBinder(sock)
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(ip, port), timeoutMs)
            true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun syncRolesLocked() {
        val wantSocket = receiverActive || browserActive
        if (!wantSocket) {
            receiverBeaconJob?.cancel()
            receiverBeaconJob = null
            browserQueryJob?.cancel()
            browserQueryJob = null
            maintenanceJob?.cancel()
            maintenanceJob = null
            closeSocketLocked()
            return
        }

        ensureSocketLocked()

        if (receiverActive) {
            if (receiverBeaconJob?.isActive != true) {
                restartReceiverBeaconLoopLocked()
            }
        } else {
            receiverBeaconJob?.cancel()
            receiverBeaconJob = null
        }

        if (browserActive) {
            if (browserQueryJob?.isActive != true) {
                restartBrowserQueryLoopLocked()
            }
            if (maintenanceJob?.isActive != true) {
                restartMaintenanceLoopLocked()
            }
        } else {
            browserQueryJob?.cancel()
            browserQueryJob = null
            maintenanceJob?.cancel()
            maintenanceJob = null
        }
    }

    private fun ensureSocketLocked(): DatagramSocket? {
        socket?.takeIf { !it.isClosed }?.let { return it }

        val ds = try {
            DatagramSocket(null as SocketAddress?)
        } catch (_: SocketException) {
            return null
        }

        var usedFallback = false
        try {
            // MUST-1 / MUST-2: bind unbound DatagramSocket to Wi-Fi Network BEFORE bind()
            datagramSocketBinder(ds)
            ds.reuseAddress = true
            ds.broadcast = true
            try {
                ds.bind(InetSocketAddress(discoveryPort))
            } catch (_: IOException) {
                usedFallback = true
                ds.bind(InetSocketAddress(0))
            }
        } catch (_: Throwable) {
            runCatching { ds.close() }
            return null
        }

        socket = ds
        boundDiscoveryPort = ds.localPort
        if (usedFallback) {
            onPortFallback?.invoke(ds.localPort)
        }

        receiveJob?.cancel()
        receiveJob = scope.launch {
            receiveLoop(ds)
        }
        return ds
    }

    private fun closeSocketLocked() {
        receiveJob?.cancel()
        receiveJob = null
        val old = socket
        socket = null
        boundDiscoveryPort = -1
        if (old != null) {
            runCatching { old.close() }
        }
    }

    private fun triggerImmediateBeaconLocked() {
        val ds = socket?.takeIf { !it.isClosed } ?: ensureSocketLocked() ?: return
        scope.launch {
            sendBroadcastBeaconOnce(ds)
        }
    }

    private fun restartReceiverBeaconLoopLocked() {
        receiverBeaconJob?.cancel()
        receiverBeaconJob = scope.launch {
            while (isActive && receiverActive) {
                val ds = socket?.takeIf { !it.isClosed }
                if (ds != null) {
                    sendBroadcastBeaconOnce(ds)
                }
                delay(BEACON_INTERVAL_MS)
            }
        }
    }

    private fun restartBrowserQueryLoopLocked() {
        browserQueryJob?.cancel()
        browserQueryJob = scope.launch {
            // Initial burst at 0 ms, 300 ms, 900 ms, then every 2 s (#77 / #79 §6)
            val initialDelays = longArrayOf(0L, 300L, 600L)
            for (d in initialDelays) {
                if (d > 0L) delay(d)
                if (!isActive || !browserActive) return@launch
                socket?.takeIf { !it.isClosed }?.let { sendBroadcastQueryOnce(it) }
            }
            while (isActive && browserActive) {
                delay(QUERY_INTERVAL_MS)
                if (!isActive || !browserActive) break
                socket?.takeIf { !it.isClosed }?.let { sendBroadcastQueryOnce(it) }
            }
        }
    }

    private fun restartMaintenanceLoopLocked() {
        maintenanceJob?.cancel()
        maintenanceJob = scope.launch {
            var lastReprobeMs = clock.nowMs()
            while (isActive && browserActive) {
                delay(TTL_SWEEP_INTERVAL_MS)
                val now = clock.nowMs()
                val toReprobe = ArrayList<PeerInfo>()
                synchronized(stateLock) {
                    val changed = pruneExpiredLocked(now)
                    if (changed) {
                        publishPeersLocked()
                    }
                    if (now - lastReprobeMs >= REPROBE_INTERVAL_MS) {
                        lastReprobeMs = now
                        for (p in peerMap.values) {
                            if ((p.source == PeerSource.MANUAL || p.source == PeerSource.PROBE) &&
                                now - p.lastSeenMs >= REPROBE_INTERVAL_MS
                            ) {
                                toReprobe.add(p)
                            }
                        }
                    }
                }
                for (candidate in toReprobe) {
                    if (!isActive || !browserActive) break
                    runCatching {
                        probe(
                            host = candidate.host,
                            port = candidate.port,
                            source = candidate.source,
                            timeoutMs = REPROBE_TIMEOUT_MS,
                        )
                    }
                }
            }
        }
    }

    private fun receiveLoop(ds: DatagramSocket) {
        // 513-byte buffer allows detecting and rejecting >512 B datagrams before parsing (SHOULD-1)
        val buf = ByteArray(MAX_DATAGRAM_BYTES + 1)
        while (!ds.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                ds.receive(packet)
            } catch (_: IOException) {
                break
            }
            val srcAddr = packet.address ?: continue
            val srcPort = packet.port
            val now = clock.nowMs()
            val decoded = decodePacket(
                bytes = packet.data,
                offset = packet.offset,
                length = packet.length,
                srcAddress = srcAddr,
                selfId = selfIdProvider(),
                nowMs = now,
            ) ?: continue

            when (decoded) {
                is DecodedPacket.Query -> {
                    if (receiverActive && srcPort in 1..65535) {
                        val httpPort = portProvider()
                        if (httpPort != null && httpPort in 1..65535 && allowQueryReply(srcAddr, now)) {
                            val replyBytes = encodeBeaconPacket(
                                id = selfIdProvider(),
                                name = selfNameProvider(),
                                port = httpPort,
                                version = appVersion,
                                readOnly = readOnlyProvider(),
                            )
                            runCatching {
                                ds.send(DatagramPacket(replyBytes, replyBytes.size, srcAddr, srcPort))
                            }
                        }
                    }
                }

                is DecodedPacket.Beacon -> {
                    if (browserActive) {
                        synchronized(stateLock) {
                            upsertPeerLocked(decoded.peer)
                            publishPeersLocked()
                        }
                    }
                }
            }
        }
    }

    private fun sendBroadcastBeaconOnce(ds: DatagramSocket) {
        val httpPort = portProvider() ?: return
        if (httpPort !in 1..65535) return
        val bytes = encodeBeaconPacket(
            id = selfIdProvider(),
            name = selfNameProvider(),
            port = httpPort,
            version = appVersion,
            readOnly = readOnlyProvider(),
        )
        sendToBroadcastTargets(ds, bytes)
    }

    private fun sendBroadcastQueryOnce(ds: DatagramSocket) {
        val bytes = encodeQueryPacket(
            id = selfIdProvider(),
            name = selfNameProvider(),
        )
        sendToBroadcastTargets(ds, bytes)
    }

    private fun sendToBroadcastTargets(ds: DatagramSocket, payload: ByteArray) {
        val targets = collectBroadcastTargets()
        for (target in targets) {
            runCatching {
                ds.send(DatagramPacket(payload, payload.size, target))
            }
        }
    }

    private fun collectBroadcastTargets(): List<InetSocketAddress> {
        val seen = LinkedHashSet<InetSocketAddress>()
        for (extra in extraBroadcastTargets) {
            seen.add(extra)
        }
        if (targetDiscoveryPort in 1..65535) {
            runCatching {
                seen.add(InetSocketAddress(InetAddress.getByName("255.255.255.255"), targetDiscoveryPort))
            }
            runCatching {
                val ifaces = NetworkInterface.getNetworkInterfaces()
                while (ifaces != null && ifaces.hasMoreElements()) {
                    val nif = ifaces.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    for (ia in nif.interfaceAddresses) {
                        val bcast = ia.broadcast
                        if (bcast is Inet4Address) {
                            seen.add(InetSocketAddress(bcast, targetDiscoveryPort))
                        }
                    }
                }
            }
        }
        return seen.toList()
    }

    private fun allowQueryReply(srcAddr: InetAddress, nowMs: Long): Boolean {
        val key = srcAddr.hostAddress ?: return false
        synchronized(stateLock) {
            if (queryReplyLog.size > 256 && key !in queryReplyLog) {
                val firstKey = queryReplyLog.keys.firstOrNull()
                if (firstKey != null) queryReplyLog.remove(firstKey)
            }
            val win = queryReplyLog.getOrPut(key) { QueryRateWindow(nowMs, 0) }
            if (nowMs - win.windowStartMs >= 1_000L) {
                win.windowStartMs = nowMs
                win.count = 1
                return true
            }
            if (win.count >= MAX_QUERY_REPLIES_PER_SEC) {
                return false
            }
            win.count++
            return true
        }
    }

    private fun upsertPeerLocked(incoming: PeerInfo) {
        val existing = peerMap[incoming.id]
        if (existing == null && peerMap.size >= MAX_PEERS) {
            // Evict oldest peer (preferring BEACON over MANUAL)
            val victim = peerMap.values
                .minWithOrNull(
                    compareBy<PeerInfo> { if (it.source == PeerSource.MANUAL) 1 else 0 }
                        .thenBy { it.lastSeenMs },
                )
            if (victim != null) {
                peerMap.remove(victim.id)
            }
        }
        val mergedSource = when {
            incoming.source == PeerSource.MANUAL -> PeerSource.MANUAL
            existing?.source == PeerSource.MANUAL -> PeerSource.MANUAL
            incoming.source == PeerSource.PROBE -> PeerSource.PROBE
            else -> incoming.source
        }
        peerMap[incoming.id] = incoming.copy(source = mergedSource)
    }

    private fun pruneExpiredLocked(nowMs: Long): Boolean {
        var removed = false
        val it = peerMap.entries.iterator()
        while (it.hasNext()) {
            val peer = it.next().value
            val ttl = when (peer.source) {
                PeerSource.BEACON -> BEACON_TTL_MS
                PeerSource.PROBE, PeerSource.MANUAL -> PROBE_MANUAL_TTL_MS
            }
            if (nowMs - peer.lastSeenMs > ttl) {
                it.remove()
                removed = true
            }
        }
        return removed
    }

    private fun publishPeersLocked() {
        val sorted = peerMap.values.sortedWith { a, b ->
            val cmpName = PathUtils.naturalCompare(a.name, b.name)
            if (cmpName != 0) cmpName else a.id.compareTo(b.id)
        }
        _peers.value = sorted
    }

    private class QueryRateWindow(
        var windowStartMs: Long,
        var count: Int,
    )

    internal sealed interface DecodedPacket {
        data class Query(val senderId: String) : DecodedPacket
        data class Beacon(val peer: PeerInfo) : DecodedPacket
    }

    companion object {
        const val MAGIC_PREFIX = "MFP1"
        const val DEFAULT_DISCOVERY_PORT = 21210
        const val DEFAULT_HTTP_PORT = 8080
        const val DEFAULT_APP_VERSION = "1.0.6"
        const val SUPPORTED_PROTO = 1
        const val MAX_DATAGRAM_BYTES = 512
        const val MAX_PEERS = 64
        const val MAX_NAME_CHARS = 64
        const val MAX_VER_CHARS = 16
        const val MAX_ID_CHARS = 128

        const val BEACON_INTERVAL_MS = 2_000L
        const val QUERY_INTERVAL_MS = 2_000L
        const val BEACON_TTL_MS = 8_000L
        const val PROBE_MANUAL_TTL_MS = 30_000L
        const val REPROBE_INTERVAL_MS = 10_000L
        const val TTL_SWEEP_INTERVAL_MS = 1_000L

        private const val MAX_QUERY_REPLIES_PER_SEC = 5
        private const val SCAN_CONCURRENCY = 48
        private const val SCAN_CONNECT_TIMEOUT_MS = 300
        private const val PROBE_TIMEOUT_MS = 3_000
        private const val REPROBE_TIMEOUT_MS = 2_000

        internal fun encodeBeaconPacket(
            id: String,
            name: String,
            port: Int,
            version: String,
            readOnly: Boolean,
            proto: Int = SUPPORTED_PROTO,
        ): ByteArray {
            val cleanId = id.take(MAX_ID_CHARS)
            val cleanVer = PeerPathPolicy.sanitizeDisplayName(version, MAX_VER_CHARS).ifEmpty { DEFAULT_APP_VERSION }
            var nameLimit = MAX_NAME_CHARS
            while (true) {
                val cleanName = PeerPathPolicy.sanitizeDisplayName(name, nameLimit)
                val json = MiniJson.stringify(
                    MiniJson.obj(
                        "t" to JsonValue.Str("beacon"),
                        "id" to JsonValue.Str(cleanId),
                        "name" to JsonValue.Str(cleanName),
                        "port" to JsonValue.Num(port),
                        "ver" to JsonValue.Str(cleanVer),
                        "ro" to JsonValue.Bool(readOnly),
                        "proto" to JsonValue.Num(proto),
                    ),
                )
                val packet = (MAGIC_PREFIX + json).toByteArray(Charsets.UTF_8)
                if (packet.size <= MAX_DATAGRAM_BYTES || nameLimit <= 1) {
                    return packet
                }
                nameLimit /= 2
            }
        }

        internal fun encodeQueryPacket(
            id: String,
            name: String,
        ): ByteArray {
            val cleanId = id.take(MAX_ID_CHARS)
            val cleanName = PeerPathPolicy.sanitizeDisplayName(name, 32)
            val json = MiniJson.stringify(
                MiniJson.obj(
                    "t" to JsonValue.Str("query"),
                    "id" to JsonValue.Str(cleanId),
                    "name" to JsonValue.Str(cleanName),
                ),
            )
            return (MAGIC_PREFIX + json).toByteArray(Charsets.UTF_8)
        }

        internal fun decodePacket(
            bytes: ByteArray,
            offset: Int = 0,
            length: Int = bytes.size - offset,
            srcAddress: InetAddress,
            selfId: String,
            nowMs: Long,
        ): DecodedPacket? {
            if (length < MAGIC_PREFIX.length + 2 || length > MAX_DATAGRAM_BYTES) {
                return null
            }
            if (!FtpServerEngine.isAllowedClientAddress(srcAddress)) {
                return null
            }
            if (bytes[offset] != 'M'.code.toByte() ||
                bytes[offset + 1] != 'F'.code.toByte() ||
                bytes[offset + 2] != 'P'.code.toByte() ||
                bytes[offset + 3] != '1'.code.toByte()
            ) {
                return null
            }

            val jsonStr = try {
                String(bytes, offset + 4, length - 4, Charsets.UTF_8)
            } catch (_: Exception) {
                return null
            }
            val obj = try {
                MiniJson.parse(jsonStr) as? JsonValue.Obj
            } catch (_: Exception) {
                return null
            } ?: return null

            val id = obj.str("id")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_ID_CHARS }
                ?: return null
            if (id == selfId) return null

            return when (obj.str("t")) {
                "query" -> DecodedPacket.Query(senderId = id)
                "beacon" -> {
                    val port = obj.int("port", -1)
                    if (port !in 1..65535) return null
                    val proto = obj.int("proto", SUPPORTED_PROTO)
                    if (proto < 1 || proto > SUPPORTED_PROTO) return null

                    // MUST-3: host is ALWAYS taken from datagram source IP, never from JSON payload
                    val host = srcAddress.hostAddress?.substringBefore('%')?.takeIf { it.isNotEmpty() }
                        ?: return null
                    val name = PeerPathPolicy.sanitizeDisplayName(obj.str("name").orEmpty(), MAX_NAME_CHARS)
                        .ifEmpty { host }
                    val ver = PeerPathPolicy.sanitizeDisplayName(obj.str("ver").orEmpty(), MAX_VER_CHARS)
                        .ifEmpty { DEFAULT_APP_VERSION }
                    val ro = obj.bool("ro", false)
                    DecodedPacket.Beacon(
                        PeerInfo(
                            id = id,
                            name = name,
                            host = host,
                            port = port,
                            version = ver,
                            readOnly = ro,
                            source = PeerSource.BEACON,
                            lastSeenMs = nowMs,
                            proto = proto,
                        ),
                    )
                }

                else -> null
            }
        }

        fun parseHostAndPort(rawInput: String, defaultPort: Int = DEFAULT_HTTP_PORT): Pair<String, Int> {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty()) {
                throw PeerTransferException(null, "Enter a device IP address")
            }

            if (trimmed.contains("://")) {
                val uri = try {
                    URI(trimmed)
                } catch (e: Exception) {
                    throw PeerTransferException(null, "Invalid address: $trimmed", e)
                }
                val h = uri.host?.removeSurrounding("[", "]")
                    ?: throw PeerTransferException(null, "Missing host in address: $trimmed")
                val p = if (uri.port != -1) uri.port else defaultPort
                if (p !in 1..65535) {
                    throw PeerTransferException(null, "Port must be between 1 and 65535")
                }
                return h to p
            }

            val withoutPath = trimmed.substringBefore('/')
            if (withoutPath.startsWith("[")) {
                val closeIdx = withoutPath.indexOf(']')
                if (closeIdx <= 1) {
                    throw PeerTransferException(null, "Invalid IPv6 address: $trimmed")
                }
                val host = withoutPath.substring(1, closeIdx)
                val rest = withoutPath.substring(closeIdx + 1)
                val port = when {
                    rest.isEmpty() -> defaultPort
                    rest.startsWith(":") -> rest.substring(1).toIntOrNull()
                        ?: throw PeerTransferException(null, "Invalid port in address: $trimmed")
                    else -> throw PeerTransferException(null, "Invalid address: $trimmed")
                }
                if (port !in 1..65535) {
                    throw PeerTransferException(null, "Port must be between 1 and 65535")
                }
                return host to port
            }

            val firstColon = withoutPath.indexOf(':')
            val lastColon = withoutPath.lastIndexOf(':')
            if (firstColon > 0 && firstColon == lastColon) {
                val host = withoutPath.substring(0, firstColon).trim()
                val portStr = withoutPath.substring(firstColon + 1).trim()
                val port = portStr.toIntOrNull()
                    ?: throw PeerTransferException(null, "Invalid port: $portStr")
                if (port !in 1..65535) {
                    throw PeerTransferException(null, "Port must be between 1 and 65535")
                }
                return host to port
            }

            return withoutPath to defaultPort
        }
    }
}
