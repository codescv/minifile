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

                val (priority, label) = classifyInterface(rawName.lowercase(), addr.isSiteLocalAddress)
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

    private fun classifyInterface(name: String, isSiteLocal: Boolean): Pair<Int, String> = when {
        name.startsWith("wlan") && isSiteLocal -> 0 to "Wi-Fi"
        name.startsWith("wlan") -> 1 to "Wi-Fi"
        name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") -> 2 to "Hotspot"
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
        )

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
