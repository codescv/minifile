package com.minifile.explorer.service

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import com.minifile.explorer.core.peer.HostDecision
import kotlinx.coroutines.CoroutineDispatcher
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

/** Lifecycle contract for joining and guaranteed-releasing a peer hotspot (spec §3.3 [v2]). */
interface HotspotJoinController {
    val joined: JoinedNetwork?
    val isJoined: Boolean get() = joined != null
    suspend fun join(ssid: String, pass: String, sec: String, expectedHostIp: String? = null): JoinedNetwork
    fun releaseAfter(graceMs: Long = WifiSpecifierConnector.JOIN_GRACE_MS)
    fun release()

    companion object {
        val NOOP: HotspotJoinController = object : HotspotJoinController {
            override val joined: JoinedNetwork? get() = null
            override suspend fun join(ssid: String, pass: String, sec: String, expectedHostIp: String?): JoinedNetwork =
                throw JoinException("Wi-Fi join unavailable")
            override fun releaseAfter(graceMs: Long) = Unit
            override fun release() = Unit
        }
    }
}

/**
 * Joins a peer's LocalOnlyHotspot (spec §3.3): API 29+ `WifiNetworkSpecifier` (system "Connect" dialog, never
 * `bindProcessToNetwork`, 60 s), API 26–28 `WifiConfiguration` fallback. While joined, [PeerDiscoveryHolder] is
 * pinned to the specifier network. [release] is idempotent and guaranteed on success (+10 s), cancel, error,
 * `onUnavailable` and ViewModel clear.
 */
class WifiSpecifierConnector internal constructor(
    private val ops: PlatformOps,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HotspotJoinController {

    constructor(
        context: Context,
        peerDiscoveryHolder: PeerDiscoveryHolder,
    ) : this(
        ops = AndroidPlatformOps(context.applicationContext, peerDiscoveryHolder),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private val lock = Any()
    private var activeRegistration: Any? = null
    private var legacyNetworkId: Int = -1
    private var releaseJob: Job? = null

    @Volatile
    override var joined: JoinedNetwork? = null
        private set

    internal interface SpecifierListener {
        fun onAvailable(network: Network?)
        fun onUnavailable()
        fun onLost(network: Network?)
    }

    internal data class LinkSnapshot(
        val ownIp: String?,
        val gateway: String?,
        val prefixLength: Int,
    )

    internal interface PlatformOps {
        val sdkInt: Int
        fun pinNetwork(network: Network?)
        fun requestSpecifier(
            ssid: String,
            pass: String,
            sec: String,
            timeoutMs: Int,
            listener: SpecifierListener,
        ): Any
        fun unregisterSpecifier(registration: Any)
        fun readLinkSnapshot(network: Network?): LinkSnapshot
        fun addAndEnableLegacy(ssid: String, pass: String): Int
        fun pollLegacyConnected(netId: Int): LinkSnapshot?
        fun releaseLegacy(netId: Int)
    }

    /**
     * @param sec `wpa2` | `wpa3t` | `wpa3` | `open` / `nopass`.
     * @param expectedHostIp optional host/gateway hint (BLE `hotspot.ip` or Files-only QR `ip`).
     */
    override suspend fun join(ssid: String, pass: String, sec: String, expectedHostIp: String?): JoinedNetwork {
        release()
        return try {
            val net = if (ops.sdkInt >= Build.VERSION_CODES.Q) {
                joinWithSpecifier(ssid, pass, sec)
            } else {
                joinLegacy(ssid, pass, expectedHostIp)
            }
            synchronized(lock) { joined = net }
            net
        } catch (t: Throwable) {
            // Spec §3.3 [v2]: guarantees release even if cancelled during post-onAvailable DHCP wait or legacy poll.
            release()
            throw t
        }
    }

    /** Releases the joined network after [graceMs] (spec: 10 s after the transfer). */
    override fun releaseAfter(graceMs: Long) {
        synchronized(lock) {
            releaseJob?.cancel()
            releaseJob = scope.launch {
                delay(graceMs)
                release()
            }
        }
    }

    /** Idempotent: cancels any pending [releaseAfter], unregisters specifier / removes legacy network. */
    override fun release() {
        val reg: Any?
        val legacyId: Int
        val pendingJob: Job?
        synchronized(lock) {
            pendingJob = releaseJob
            releaseJob = null
            reg = activeRegistration
            activeRegistration = null
            legacyId = legacyNetworkId
            legacyNetworkId = -1
            joined = null
        }
        pendingJob?.cancel()
        ops.pinNetwork(null)
        if (reg != null) runCatching { ops.unregisterSpecifier(reg) }
        if (legacyId >= 0) ops.releaseLegacy(legacyId)
    }

    /** Unregisters a failed attempt's callback if it is still the active registration (no-op otherwise). */
    private fun discardRegistration(reg: Any?) {
        if (reg == null) return
        val mine = synchronized(lock) {
            if (activeRegistration === reg) {
                activeRegistration = null
                true
            } else {
                false
            }
        }
        if (mine) runCatching { ops.unregisterSpecifier(reg) }
    }

    private fun releaseIfCurrentRegistration(expected: Any?) {
        if (expected == null) return
        val shouldRelease = synchronized(lock) { activeRegistration === expected }
        if (shouldRelease) release()
    }

    /**
     * Tries each security mode from [securityAttemptOrder] once (spec §3.3): a WPA2 specifier never matches an
     * SAE-only AP and some OEM LOHS report `wpa3` for a transition AP, so a failed / timed-out first attempt is
     * retried once with the other passphrase type before giving up.
     */
    private suspend fun joinWithSpecifier(ssid: String, pass: String, sec: String): JoinedNetwork {
        val attempts = securityAttemptOrder(sec, pass)
        var lastError: Throwable? = null
        for ((index, mode) in attempts.withIndex()) {
            val isLast = index == attempts.lastIndex
            val timeoutMs = if (isLast) JOIN_TIMEOUT_MS else FIRST_ATTEMPT_TIMEOUT_MS
            try {
                return joinWithSpecifierOnce(ssid, pass, mode, timeoutMs)
            } catch (e: JoinException) {
                lastError = e
                if (isLast) throw e
            }
        }
        throw lastError ?: JoinException("Connection declined or timed out")
    }

    private suspend fun joinWithSpecifierOnce(ssid: String, pass: String, mode: String, timeoutMs: Long): JoinedNetwork {
        var attemptReg: Any? = null
        val result = withContext(mainDispatcher) {
            suspendCancellableCoroutine<Result<Network?>> { cont ->
                var regToken: Any? = null
                val listener = object : SpecifierListener {
                    override fun onAvailable(network: Network?) {
                        if (cont.isActive) cont.resume(Result.success(network))
                    }

                    override fun onUnavailable() {
                        // Release BEFORE resuming: an undispatched continuation may start the next attempt at once.
                        releaseIfCurrentRegistration(regToken)
                        if (cont.isActive) cont.resume(Result.failure(JoinException("Connection declined or timed out")))
                    }

                    override fun onLost(network: Network?) {
                        releaseIfCurrentRegistration(regToken)
                    }
                }
                try {
                    val reg = ops.requestSpecifier(ssid, pass, mode, timeoutMs.toInt(), listener)
                    regToken = reg
                    attemptReg = reg
                    synchronized(lock) { activeRegistration = reg }
                } catch (e: Exception) {
                    synchronized(lock) { activeRegistration = null }
                    if (cont.isActive) cont.resume(Result.failure(JoinException("Could not request Wi-Fi: ${e.message}")))
                }
                cont.invokeOnCancellation { releaseIfCurrentRegistration(regToken) }
            }
        }
        if (result.isFailure) discardRegistration(attemptReg)
        val network = result.getOrElse { throw it }
        ops.pinNetwork(network)

        // Link properties may lag onAvailable by a few hundred ms (DHCP).
        var snap = ops.readLinkSnapshot(network)
        if (snap.ownIp == null) {
            withTimeoutOrNull(LINK_WAIT_MS) {
                while (snap.ownIp == null) {
                    delay(200)
                    snap = ops.readLinkSnapshot(network)
                }
            }
        }
        return JoinedNetwork(
            network = network,
            gateway = snap.gateway,
            ownIp = snap.ownIp,
            prefixLength = snap.prefixLength,
        )
    }

    private suspend fun joinLegacy(ssid: String, pass: String, expectedHostIp: String?): JoinedNetwork =
        withContext(ioDispatcher) {
            val id = ops.addAndEnableLegacy(ssid, pass)
            if (id < 0) throw JoinException("Could not add Wi-Fi network $ssid")
            synchronized(lock) { legacyNetworkId = id }
            val expectedSubnetBase = expectedHostIp?.takeIf { HostDecision.parseIpv4(it) != null }
            val joinedInfo = withTimeoutOrNull(JOIN_TIMEOUT_MS) {
                var found: JoinedNetwork? = null
                while (found == null) {
                    val snap = ops.pollLegacyConnected(id)
                    val ownIp = snap?.ownIp
                    if (ownIp != null) {
                        val inSubnet = expectedSubnetBase == null || HostDecision.inSubnet(ownIp, expectedSubnetBase, 24)
                        if (inSubnet) {
                            found = JoinedNetwork(null, snap.gateway, ownIp, snap.prefixLength)
                        }
                    }
                    if (found == null) delay(500)
                }
                found
            }
            joinedInfo ?: throw JoinException("Could not join $ssid within 60 s")
        }

    private class AndroidPlatformOps(
        private val appContext: Context,
        private val peerDiscoveryHolder: PeerDiscoveryHolder,
    ) : PlatformOps {
        private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        private val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

        override val sdkInt: Int get() = Build.VERSION.SDK_INT

        override fun pinNetwork(network: Network?) {
            peerDiscoveryHolder.setPinnedNetwork(network)
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        override fun requestSpecifier(
            ssid: String,
            pass: String,
            sec: String,
            timeoutMs: Int,
            listener: SpecifierListener,
        ): Any {
            val builder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            when (sec) {
                SEC_OPEN -> Unit
                SEC_WPA3 -> builder.setWpa3Passphrase(pass)
                else -> builder.setWpa2Passphrase(pass) // wpa2 + wpa3t (transition mode accepts WPA2-PSK)
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(builder.build())
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = listener.onAvailable(network)
                override fun onUnavailable() = listener.onUnavailable()
                override fun onLost(network: Network) = listener.onLost(network)
            }
            cm.requestNetwork(request, cb, timeoutMs)
            return cb
        }

        override fun unregisterSpecifier(registration: Any) {
            if (registration is ConnectivityManager.NetworkCallback) {
                cm.unregisterNetworkCallback(registration)
            }
        }

        override fun readLinkSnapshot(network: Network?): LinkSnapshot {
            val lp = network?.let { runCatching { cm.getLinkProperties(it) }.getOrNull() }
            val v4 = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }
            val ownIp = v4?.address?.hostAddress
            val prefix = v4?.prefixLength ?: 24
            val gateway = lp?.routes
                ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway?.hostAddress
                ?: dhcpGatewayIfInSubnet(ownIp, prefix)
            return LinkSnapshot(ownIp = ownIp, gateway = gateway, prefixLength = prefix)
        }

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        override fun addAndEnableLegacy(ssid: String, pass: String): Int {
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
            if (id >= 0) {
                wifi.disconnect()
                wifi.enableNetwork(id, true)
                wifi.reconnect()
            }
            return id
        }

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        override fun pollLegacyConnected(netId: Int): LinkSnapshot? {
            val wifi = wm ?: return null
            val info = wifi.connectionInfo
            val ipInt = info?.ipAddress ?: 0
            if (ipInt == 0 || info?.networkId != netId) return null
            val ownIp = intToIp(ipInt)
            val gw = wifi.dhcpInfo?.gateway?.takeIf { it != 0 }?.let(::intToIp)
            return LinkSnapshot(ownIp = ownIp, gateway = gw, prefixLength = wlanPrefixLength(ownIp))
        }

        @Suppress("DEPRECATION")
        override fun releaseLegacy(netId: Int) {
            val wifi = wm ?: return
            runCatching {
                wifi.disableNetwork(netId)
                wifi.removeNetwork(netId)
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
    }

    companion object {
        const val JOIN_TIMEOUT_MS = 60_000L
        const val JOIN_GRACE_MS = 10_000L
        internal const val LINK_WAIT_MS = 5_000L

        /** First of two specifier attempts (a second, other-security attempt follows on failure). */
        const val FIRST_ATTEMPT_TIMEOUT_MS = 30_000L

        const val SEC_WPA2 = "wpa2"
        const val SEC_WPA3 = "wpa3"
        const val SEC_OPEN = "open"

        /**
         * Concrete `WifiNetworkSpecifier` security modes to try, in order, for an advertised [sec]
         * (`wpa2` | `wpa3t` | `wpa3` | `sae` | `open` | `nopass` …):
         * - open / nopass / empty [pass] → `[open]`
         * - `wpa3` / `sae` (SAE-only) → `[wpa3, wpa2]`
         * - anything else (`wpa2`, `wpa3t` transition, `wpa`, unknown) → `[wpa2, wpa3]`
         */
        fun securityAttemptOrder(sec: String, pass: String): List<String> {
            val s = sec.trim().lowercase()
            if (pass.isEmpty() || s == "open" || s == "nopass" || s == "none") return listOf(SEC_OPEN)
            return when (s) {
                "wpa3", "sae", "wpa3-sae", "wpa3_sae" -> listOf(SEC_WPA3, SEC_WPA2)
                else -> listOf(SEC_WPA2, SEC_WPA3)
            }
        }

        /** `WifiManager` little-endian int → dotted IPv4. */
        fun intToIp(v: Int): String =
            "${v and 0xFF}.${(v ushr 8) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 24) and 0xFF}"
    }
}
