package com.minifile.explorer.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
     * Enumerates all up, non-loopback, non-virtual IPv4 interfaces (`ARCHITECTURE.md` §6.2).
     * Does NOT filter solely by `isSiteLocalAddress()`, so 100.64/10 CGNAT, 169.254/16 link-local,
     * and corporate non-RFC1918 LANs are included.
     * Ordered: site-local `wlan*` -> other `wlan*` -> `ap*`/`swlan*` (hotspot) -> `rndis*`/`usb*` -> others.
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
            if (!up || loopback || virtual) continue

            val name = nif.name?.lowercase().orEmpty()
            val addrs = runCatching { Collections.list(nif.inetAddresses) }.getOrDefault(emptyList())
            for (addr in addrs) {
                if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                val ip = addr.hostAddress ?: continue
                if (ip == "0.0.0.0") continue

                val (priority, label) = classifyInterface(name, addr.isSiteLocalAddress)
                endpoints.add(
                    LanEndpoint(
                        interfaceName = nif.name ?: name,
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
        isSiteLocal -> 5 to "LAN"
        else -> 6 to "Network"
    }
}
