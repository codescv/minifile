package com.minifile.explorer.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.minifile.explorer.core.ftp.FtpServerEngine
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
     * IPv4s (with the real `InterfaceAddress.networkPrefixLength`, fallback 24) on up soft-AP style interfaces
     * (`ap*`, `swlan*`, `softap*`, `sap*`, `p2p*`, `wlan[1-9]*`) that are **not** the address of any tracked
     * [ConnectivityManager] network (i.e. not a station/STA address). `*.1` addresses are listed first.
     */
    fun hotspotEndpoints(): List<HotspotEndpoint> {
        val rawIfaces = runCatching {
            val enum = NetworkInterface.getNetworkInterfaces()
            if (enum != null) Collections.list(enum) else emptyList()
        }.getOrDefault(emptyList())
        val staIps = trackedNetworkIpv4s()
        val out = ArrayList<HotspotEndpoint>()
        for (nif in rawIfaces) {
            val up = runCatching { nif.isUp }.getOrDefault(false)
            if (!up || runCatching { nif.isLoopback }.getOrDefault(true)) continue
            val name = nif.name ?: continue
            if (!isHotspotInterfaceName(name)) continue
            val ifaceAddrs = runCatching { nif.interfaceAddresses.toList() }.getOrDefault(emptyList())
            for (ia in ifaceAddrs) {
                val addr = ia.address as? Inet4Address ?: continue
                if (!isAllowedLanIpv4(addr)) continue
                val ip = addr.hostAddress ?: continue
                if (ip in staIps) continue
                val prefix = ia.networkPrefixLength.toInt().takeIf { it in 8..30 } ?: 24
                out.add(HotspotEndpoint(interfaceName = name, ipv4 = ip, prefixLength = prefix))
            }
        }
        return out.distinctBy { it.ipv4 }.sortedWith(
            compareBy<HotspotEndpoint> { if (it.ipv4.endsWith(".1")) 0 else 1 }.thenBy { it.interfaceName },
        )
    }

    /** IPv4 addresses of every network ConnectivityManager tracks (Wi-Fi STA, Ethernet, cellular…). */
    @Suppress("DEPRECATION")
    private fun trackedNetworkIpv4s(): Set<String> = runCatching {
        val out = HashSet<String>()
        for (n in connectivityManager.allNetworks) {
            val lp = connectivityManager.getLinkProperties(n) ?: continue
            for (la in lp.linkAddresses) {
                val a = la.address
                if (a is Inet4Address) a.hostAddress?.let { out.add(it) }
            }
        }
        out as Set<String>
    }.getOrDefault(emptySet())

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
            "p2p",
        )

        private val WLAN_SECONDARY = Regex("^wlan[1-9][0-9]*$")
        private val HOTSPOT_PREFIXES = listOf("ap", "swlan", "softap", "sap", "p2p")

        /** Spec §3.4: interface names that can carry a LocalOnlyHotspot / soft-AP gateway address. */
        fun isHotspotInterfaceName(interfaceName: String): Boolean {
            val lower = interfaceName.trim().lowercase()
            if (lower.isEmpty() || isExcludedInterface(lower)) return false
            return HOTSPOT_PREFIXES.any { lower.startsWith(it) } || WLAN_SECONDARY.matches(lower)
        }

        fun isExcludedInterface(interfaceName: String): Boolean {
            val lower = interfaceName.trim().lowercase()
            if (lower.isEmpty()) return true
            return EXCLUDED_WAN_OR_TUNNEL_PREFIXES.any { lower.startsWith(it) }
        }

        fun isAllowedLocalInterface(interfaceName: String): Boolean {
            val lower = interfaceName.trim().lowercase()
            if (lower.isEmpty() || isExcludedInterface(lower)) return false
            return ALLOWED_LOCAL_PREFIXES.any { lower.startsWith(it) }
        }

        fun isAllowedLanIpv4(addr: Inet4Address): Boolean {
            if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isMulticastAddress) return false
            return FtpServerEngine.isAllowedClientAddress(addr)
        }
    }
}
