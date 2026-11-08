package com.minifile.explorer.service

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import com.minifile.explorer.core.peer.HostDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import kotlin.coroutines.resume

/** Result of joining a peer hotspot as a client. [network] is `null` on the API 26–28 legacy path. */
data class JoinedNetwork(
    val network: Network?,
    val gateway: String?,
    val ownIp: String?,
    val prefixLength: Int,
)

class JoinException(message: String) : Exception(message)

/**
 * Joins a peer's LocalOnlyHotspot (spec §3.3): API 29+ `WifiNetworkSpecifier` (system "Connect" dialog, never
 * `bindProcessToNetwork`, 60 s), API 26–28 `WifiConfiguration` fallback. While joined, [PeerDiscoveryHolder] is
 * pinned to the specifier network. [release] is idempotent and must run on success (+10 s), cancel and error.
 */
class WifiSpecifierConnector(
    context: Context,
    private val peerDiscoveryHolder: PeerDiscoveryHolder,
) {
    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var legacyNetworkId: Int = -1
    private var releaseJob: Job? = null

    @Volatile
    var joined: JoinedNetwork? = null
        private set

    val isJoined: Boolean get() = joined != null

    /**
     * @param sec `wpa2` | `wpa3t` | `wpa3` | `open` / `nopass`.
     * @param expectedHostIp optional host/gateway hint (BLE `hotspot.ip` or Files-only QR `ip`).
     */
    suspend fun join(ssid: String, pass: String, sec: String, expectedHostIp: String? = null): JoinedNetwork {
        release()
        releaseJob?.cancel()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            joinWithSpecifier(ssid, pass, sec)
        } else {
            joinLegacy(ssid, pass, expectedHostIp)
        }.also { joined = it }
    }

    /** Releases the joined network after [graceMs] (spec: 10 s after the transfer). */
    fun releaseAfter(graceMs: Long = JOIN_GRACE_MS) {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(graceMs)
            release()
        }
    }

    /** Idempotent: unregisters the request (OS restores the previous Wi-Fi) / removes the legacy network. */
    fun release() {
        val cb: ConnectivityManager.NetworkCallback?
        val legacyId: Int
        synchronized(lock) {
            cb = callback
            callback = null
            legacyId = legacyNetworkId
            legacyNetworkId = -1
            joined = null
        }
        peerDiscoveryHolder.setPinnedNetwork(null)
        if (cb != null) runCatching { cm.unregisterNetworkCallback(cb) }
        if (legacyId >= 0) releaseLegacy(legacyId)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun joinWithSpecifier(ssid: String, pass: String, sec: String): JoinedNetwork {
        val builder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        when {
            pass.isEmpty() || sec == "open" || sec == "nopass" -> Unit
            sec == "wpa3" -> builder.setWpa3Passphrase(pass)
            else -> builder.setWpa2Passphrase(pass) // wpa2 + wpa3t (transition mode accepts WPA2-PSK)
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(builder.build())
            .build()

        val result = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Result<Network>> { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(Result.success(network))
                    }

                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(Result.failure(JoinException("Connection declined or timed out")))
                        scope.launch { release() }
                    }

                    override fun onLost(network: Network) {
                        if (joined?.network == network) scope.launch { release() }
                    }
                }
                synchronized(lock) { callback = cb }
                try {
                    cm.requestNetwork(request, cb, JOIN_TIMEOUT_MS.toInt())
                } catch (e: Exception) {
                    synchronized(lock) { callback = null }
                    if (cont.isActive) cont.resume(Result.failure(JoinException("Could not request Wi-Fi: ${e.message}")))
                }
                cont.invokeOnCancellation { scope.launch { release() } }
            }
        }
        val network = result.getOrElse { throw it }
        peerDiscoveryHolder.setPinnedNetwork(network)

        // Link properties may lag onAvailable by a few hundred ms (DHCP).
        var lp: LinkProperties? = null
        val got = withTimeoutOrNull(LINK_WAIT_MS) {
            while (true) {
                lp = cm.getLinkProperties(network)
                if (lp?.linkAddresses?.any { it.address is Inet4Address } == true) break
                delay(200)
            }
            true
        }
        if (got == null) lp = cm.getLinkProperties(network)
        val v4 = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        val gateway = lp?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
            ?: dhcpGatewayIfInSubnet(v4?.address?.hostAddress, v4?.prefixLength ?: 24)
        return JoinedNetwork(
            network = network,
            gateway = gateway,
            ownIp = v4?.address?.hostAddress,
            prefixLength = v4?.prefixLength ?: 24,
        )
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun joinLegacy(ssid: String, pass: String, expectedHostIp: String?): JoinedNetwork =
        withContext(Dispatchers.IO) {
            val wifi = wm ?: throw JoinException("Wi-Fi is not available")
            if (!wifi.isWifiEnabled) runCatching { wifi.isWifiEnabled = true }
            val conf = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                if (pass.isEmpty()) {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                } else {
                    preSharedKey = "\"$pass\""
                }
            }
            val id = wifi.addNetwork(conf)
            if (id < 0) throw JoinException("Could not add Wi-Fi network $ssid")
            synchronized(lock) { legacyNetworkId = id }
            wifi.disconnect()
            wifi.enableNetwork(id, true)
            wifi.reconnect()
            val expectedSubnetBase = expectedHostIp?.takeIf { HostDecision.parseIpv4(it) != null }
            val joinedInfo = withTimeoutOrNull(JOIN_TIMEOUT_MS) {
                var found: JoinedNetwork? = null
                while (found == null) {
                    val info = wifi.connectionInfo
                    val ipInt = info?.ipAddress ?: 0
                    if (ipInt != 0 && info?.networkId == id) {
                        val ownIp = intToIp(ipInt)
                        val inSubnet = expectedSubnetBase == null || HostDecision.inSubnet(ownIp, expectedSubnetBase, 24)
                        if (inSubnet) {
                            val gw = wifi.dhcpInfo?.gateway?.takeIf { it != 0 }?.let(::intToIp)
                            found = JoinedNetwork(null, gw, ownIp, wlanPrefixLength(ownIp))
                        }
                    }
                    if (found == null) delay(500)
                }
                found
            }
            joinedInfo ?: run {
                release()
                throw JoinException("Could not join $ssid within 60 s")
            }
        }

    @Suppress("DEPRECATION")
    private fun releaseLegacy(id: Int) {
        val wifi = wm ?: return
        runCatching {
            wifi.disableNetwork(id)
            wifi.removeNetwork(id)
            wifi.reconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun dhcpGatewayIfInSubnet(ownIp: String?, prefix: Int): String? {
        val gwInt = wm?.dhcpInfo?.gateway ?: return null
        if (gwInt == 0) return null
        val gw = intToIp(gwInt)
        if (ownIp != null && !HostDecision.inSubnet(gw, ownIp, prefix)) return null
        return gw
    }

    private fun wlanPrefixLength(ip: String): Int = runCatching {
        for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (ia in nif.interfaceAddresses) {
                if (ia.address?.hostAddress == ip) return@runCatching ia.networkPrefixLength.toInt()
            }
        }
        24
    }.getOrDefault(24)

    companion object {
        const val JOIN_TIMEOUT_MS = 60_000L
        const val JOIN_GRACE_MS = 10_000L
        private const val LINK_WAIT_MS = 5_000L

        /** `WifiManager` little-endian int → dotted IPv4. */
        fun intToIp(v: Int): String =
            "${v and 0xFF}.${(v ushr 8) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 24) and 0xFF}"
    }
}
