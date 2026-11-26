package com.minifile.explorer.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.minifile.explorer.core.ftp.FtpServerEngine
import com.minifile.explorer.core.net.HotspotInterfaceClassifier
import com.minifile.explorer.core.net.IfaceIpv4
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class LanEndpoint(
    val interfaceName: String,
    val ipv4: String,
    val categoryLabel: String,
    val priority: Int,
)

/** An IPv4 this device serves as a (LocalOnly)Hotspot / soft-AP gateway (spec §3.4). */
data class HotspotEndpoint(
    val interfaceName: String,
    val ipv4: String,
    val prefixLength: Int,
)

class LanAddressResolver(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Enumerates up, non-loopback, non-virtual local LAN IPv4 interfaces while explicitly
     * excluding cellular WAN and VPN/tunnel interfaces (`rmnet*`, `ccmni*`, `pdp*`, `wwan*`,
     * `tun*`, `tap*`, `ppp*`, `wg*`, `ipsec*`, `clat*`, `v4-*`).
     * Only includes private RFC 1918, link-local (`169.254/16`), or CGNAT (`100.64/10`) IPv4
     * addresses on local interfaces (`wlan*`, `ap*`, `swlan*`, `softap*`, `rndis*`, `usb*`,
     * `ncm*`, `eth*`, `bridge*`).
     */
    fun currentEndpoints(): List<LanEndpoint> {
        val rawIfaces = runCatching {
            val enum = NetworkInterface.getNetworkInterfaces()
            if (enum != null) Collections.list(enum) else emptyList()
        }.getOrDefault(emptyList())

        val staIps = trackedNetworkIpv4s()
        val endpoints = ArrayList<LanEndpoint>()
        for (nif in rawIfaces) {
            val up = runCatching { nif.isUp }.getOrDefault(false)
            val loopback = runCatching { nif.isLoopback }.getOrDefault(false)
            val virtual = runCatching { nif.isVirtual }.getOrDefault(false)
            val ptp = runCatching { nif.isPointToPoint }.getOrDefault(false)
            if (!up || loopback || virtual || ptp) continue

            val rawName = nif.name ?: continue
            if (!isAllowedLocalInterface(rawName)) continue

            val addrs = runCatching { Collections.list(nif.inetAddresses) }.getOrDefault(emptyList())
            for (addr in addrs) {
                if (addr !is Inet4Address || !isAllowedLanIpv4(addr)) continue
                val ip = addr.hostAddress ?: continue

                val (priority, label) = classifyInterface(rawName.lowercase(), addr.isSiteLocalAddress, ip in staIps)
                endpoints.add(
                    LanEndpoint(
                        interfaceName = rawName,
                        ipv4 = ip,
                        categoryLabel = label,
                        priority = priority,
                    ),
                )
            }
        }
        return endpoints
            .distinctBy { it.ipv4 }
            .sortedWith(compareBy<LanEndpoint> { it.priority }.thenBy { it.interfaceName }.thenBy { it.ipv4 })
    }

    fun currentIpv4List(): List<String> = currentEndpoints().map { it.ipv4 }

    /**
     * LocalOnlyHotspot gateway IPv4s (real `InterfaceAddress.networkPrefixLength`, fallback 24), classified by
     * [HotspotInterfaceClassifier]: soft-AP style interfaces (`ap*`, `swlan*`, `softap*`, `sap*`, `p2p*`) and
     * secondary radios (`wlan[1-9]*`, OnePlus/Qualcomm STA+AP) whose address is **not** any tracked
     * [ConnectivityManager] network (i.e. not a station/STA address). With a pre-LOHS [before] snapshot
     * (see [snapshotLocalIpv4s]) newly appeared wireless IPv4s — even on `wlan0` — are recognised and ranked first.
     */
    fun hotspotEndpoints(before: Set<String>? = null, beforeIfaces: Set<String>? = null): List<HotspotEndpoint> =
        HotspotInterfaceClassifier.classify(localIpv4s(), trackedNetworkIpv4s(), before, beforeIfaces)
            .map { HotspotEndpoint(interfaceName = it.interfaceName, ipv4 = it.ipv4, prefixLength = it.prefixLength) }

    /**
     * The local (LOHS) IPv4 sharing a subnet with [peerIp] that did not exist before LOHS started — the fallback
     * when a joined peer reports its address before classification found the hotspot interface.
     */
    fun hotspotEndpointForPeer(peerIp: String, before: Set<String>?): HotspotEndpoint? =
        HotspotInterfaceClassifier.matchPeerSubnet(peerIp, localIpv4s(), before)
            ?.let { HotspotEndpoint(interfaceName = it.interfaceName, ipv4 = it.ipv4, prefixLength = it.prefixLength) }

    /**
     * Diagnostic summary of EVERY up, non-loopback IPv4 interface (no name / range filter), flagged `sta` / `local` /
     * `new` / `excl` (see [HotspotInterfaceClassifier.describe]). Never throws.
     */
    fun describeIpv4Interfaces(before: Set<String>? = null): String = runCatching {
        val nets = trackedNetworks()
        HotspotInterfaceClassifier.describe(
            all = allUpIpv4s(),
            stationIps = HotspotInterfaceClassifier.stationIps(nets),
            localNetworkIps = nets.filter { it.isLocalNetwork }.flatMapTo(HashSet()) { it.ipv4s },
            before = before,
        )
    }.getOrElse { "[error ${it.javaClass.simpleName}]" }

    /**
     * Every live local IPv4 subnet `(ifaceName, Inet4Address, prefixLength)` on an up, non-loopback interface that can
     * carry peer traffic (Wi-Fi STA, System Personal Hotspot `wlan1`/`ap0`/`ap_br_*`/`swlan*`/`softap*`, LOHS, USB
     * tether `rndis*`/`ncm*`/`usb*`, Wi-Fi Direct `p2p*`, `bridge*`, Ethernet). Cellular, VPN, loopback and dummy
     * interfaces are excluded. Never throws: an enumeration failure yields an empty list.
     */
    fun allLocalIpv4Subnets(): List<Triple<String, Inet4Address, Int>> = runCatching {
        val rawIfaces = runCatching {
            val enum = NetworkInterface.getNetworkInterfaces()
            if (enum != null) Collections.list(enum) else emptyList()
        }.getOrDefault(emptyList())
        val out = ArrayList<Triple<String, Inet4Address, Int>>()
        for (nif in rawIfaces) {
            val up = runCatching { nif.isUp }.getOrDefault(false)
            if (!up || runCatching { nif.isLoopback }.getOrDefault(true)) continue
            val name = nif.name ?: continue
            // Any non-excluded interface: an OEM soft-AP with an unusual name must still route its own subnet
            // (otherwise `LocalSubnetRouter` falls back to binding `wlan0` for a `10.x` hotspot peer).
            if (isExcludedInterface(name)) continue
            val ifaceAddrs = runCatching { nif.interfaceAddresses.toList() }.getOrDefault(emptyList())
            for (ia in ifaceAddrs) {
                val addr = ia?.address as? Inet4Address ?: continue
                if (!isAllowedLanIpv4(addr)) continue
                val prefix = ia.networkPrefixLength.toInt().takeIf { it in 1..32 } ?: 24
                out.add(Triple(name, addr, prefix))
            }
        }
        out.toList()
    }.getOrDefault(emptyList())

    /** Every LAN-range IPv4 currently on an up local interface; call just before `startLocalOnlyHotspot`. */
    fun snapshotLocalIpv4s(): Set<String> = localIpv4s().mapTo(HashSet()) { it.ipv4 }

    /** Like [snapshotLocalIpv4s] but keeps interface names (enables the classifier's relaxed new-interface pass). */
    fun snapshotLocalIfaces(): List<IfaceIpv4> = localIpv4s()

    /** Every up, non-loopback IPv4 with its real prefix — unfiltered, for diagnostics only. */
    private fun allUpIpv4s(): List<IfaceIpv4> {
        val rawIfaces = runCatching {
            val enum = NetworkInterface.getNetworkInterfaces()
            if (enum != null) Collections.list(enum) else emptyList()
        }.getOrDefault(emptyList())
        val out = ArrayList<IfaceIpv4>()
        for (nif in rawIfaces) {
            if (!runCatching { nif.isUp }.getOrDefault(false)) continue
            if (runCatching { nif.isLoopback }.getOrDefault(true)) continue
            val name = nif.name ?: continue
            for (ia in runCatching { nif.interfaceAddresses.toList() }.getOrDefault(emptyList())) {
                val addr = ia?.address as? Inet4Address ?: continue
                val ip = addr.hostAddress ?: continue
                out.add(IfaceIpv4(interfaceName = name, ipv4 = ip, prefixLength = ia.networkPrefixLength.toInt()))
            }
        }
        return out
    }

    private fun localIpv4s(): List<IfaceIpv4> {
        val rawIfaces = runCatching {
            val enum = NetworkInterface.getNetworkInterfaces()
            if (enum != null) Collections.list(enum) else emptyList()
        }.getOrDefault(emptyList())
        val out = ArrayList<IfaceIpv4>()
        for (nif in rawIfaces) {
            val up = runCatching { nif.isUp }.getOrDefault(false)
            if (!up || runCatching { nif.isLoopback }.getOrDefault(true)) continue
            val name = nif.name ?: continue
            if (isExcludedInterface(name)) continue
            val ifaceAddrs = runCatching { nif.interfaceAddresses.toList() }.getOrDefault(emptyList())
            for (ia in ifaceAddrs) {
                val addr = ia.address as? Inet4Address ?: continue
                if (!isAllowedLanIpv4(addr)) continue
                val ip = addr.hostAddress ?: continue
                out.add(IfaceIpv4(interfaceName = name, ipv4 = ip, prefixLength = ia.networkPrefixLength.toInt()))
            }
        }
        return out
    }

    /**
     * IPv4 addresses of every **station-like** network ConnectivityManager tracks (Wi-Fi STA, Ethernet, cellular…).
     * Networks with `NET_CAPABILITY_LOCAL_NETWORK` (API 35+: the tethering local network agent Android registers for
     * the LOHS downstream itself) are excluded — they are the hotspot, not a station.
     */
    private fun trackedNetworkIpv4s(): Set<String> = HotspotInterfaceClassifier.stationIps(trackedNetworks())

    @Suppress("DEPRECATION")
    private fun trackedNetworks(): List<HotspotInterfaceClassifier.TrackedNetIps> = runCatching {
        val out = ArrayList<HotspotInterfaceClassifier.TrackedNetIps>()
        for (n in connectivityManager.allNetworks) {
            val lp = connectivityManager.getLinkProperties(n) ?: continue
            val ips = lp.linkAddresses.mapNotNull { (it.address as? Inet4Address)?.hostAddress }
            if (ips.isEmpty()) continue
            val caps = runCatching { connectivityManager.getNetworkCapabilities(n) }.getOrNull()
            out += HotspotInterfaceClassifier.TrackedNetIps(
                interfaceName = lp.interfaceName,
                ipv4s = ips,
                isLocalNetwork = caps != null && isLocalNetwork(caps),
            )
        }
        out.toList()
    }.getOrDefault(emptyList())

    private fun isLocalNetwork(caps: NetworkCapabilities): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)

    fun observeEndpoints(): Flow<List<LanEndpoint>> = callbackFlow {
        trySend(currentEndpoints())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(currentEndpoints())
            }

            override fun onLost(network: Network) {
                trySend(currentEndpoints())
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                trySend(currentEndpoints())
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(currentEndpoints())
            }
        }
        val req = NetworkRequest.Builder().build()
        val registered = runCatching {
            connectivityManager.registerNetworkCallback(req, callback)
            true
        }.getOrDefault(false)

        awaitClose {
            if (registered) {
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            }
        }
    }

    private fun classifyInterface(name: String, isSiteLocal: Boolean, isTrackedNetwork: Boolean): Pair<Int, String> = when {
        // `wlan1+` that is not a tracked STA network address is a soft-AP (LOHS on a secondary radio).
        WLAN_SECONDARY.matches(name) && !isTrackedNetwork -> 2 to "Hotspot"
        name.startsWith("wlan") && isSiteLocal -> 0 to "Wi-Fi"
        name.startsWith("wlan") -> 1 to "Wi-Fi"
        name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") ||
            name.startsWith("sap") -> 2 to "Hotspot"
        name.startsWith("p2p") -> 2 to "Wi-Fi Direct"
        name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> 3 to "USB Tethering"
        name.startsWith("eth") -> 4 to "Ethernet"
        name.startsWith("bridge") -> 5 to "Bridge"
        else -> 6 to "LAN"
    }

    companion object {
        private val EXCLUDED_WAN_OR_TUNNEL_PREFIXES = listOf(
            "rmnet",
            "r_rmnet",
            "ccmni",
            "pdp",
            "wwan",
            "tun",
            "tap",
            "ppp",
            "wg",
            "ipsec",
            "clat",
            "v4-",
            "utun",
            "epdg",
            "sit",
            "ip6tnl",
            "dummy",
        )

        private val ALLOWED_LOCAL_PREFIXES = listOf(
            "wlan",
            "ap",
            "swlan",
            "softap",
            "rndis",
            "usb",
            "ncm",
            "eth",
            "bridge",
            "sap",
            "uap",
            "p2p",
            "wfd",
            "wifi",
        )

        private val WLAN_SECONDARY = Regex("^wlan[1-9][0-9]*$")

        /** Spec §3.4: interface names that can carry a LocalOnlyHotspot / soft-AP gateway address. */
        fun isHotspotInterfaceName(interfaceName: String): Boolean =
            !isExcludedInterface(interfaceName) && HotspotInterfaceClassifier.isHotspotInterfaceName(interfaceName)

        fun isExcludedInterface(interfaceName: String): Boolean {
            val lower = interfaceName.trim().lowercase()
            if (lower.isEmpty()) return true
            return EXCLUDED_WAN_OR_TUNNEL_PREFIXES.any { lower.startsWith(it) } ||
                HotspotInterfaceClassifier.isExcluded(lower)
        }

        fun isAllowedLocalInterface(interfaceName: String): Boolean {
            val lower = interfaceName.trim().lowercase()
            if (lower.isEmpty() || isExcludedInterface(lower)) return false
            return ALLOWED_LOCAL_PREFIXES.any { lower.startsWith(it) } || HotspotInterfaceClassifier.isLocalLanName(lower)
        }

        fun isAllowedLanIpv4(addr: Inet4Address): Boolean {
            if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isMulticastAddress) return false
            return FtpServerEngine.isAllowedClientAddress(addr)
        }
    }
}
