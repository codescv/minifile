package com.minifile.explorer.service

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.minifile.explorer.core.http.HotspotSessionGate
import com.minifile.explorer.core.http.HttpEvent
import com.minifile.explorer.data.LanAddressResolver
import com.minifile.explorer.ui.util.NearbyPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.coroutines.resume

/** Why the hotspot is up (drives token arming + auto-stop policy, spec §3.2). */
enum class HotspotPurpose {
    /** Scheme B "Hotspot Direct": universal `WIFI:` QR, no token (or Files-only QR token), 5 min idle stop. */
    DIRECT,

    /** BLE `lohs-r`: this device receives; `sessionToken` gates `begin`; stop 15 s after the session ends. */
    BLE_RECEIVER,

    /** BLE `lohs-s`: this device sends to an iOS joiner; `announce` token armed; stop 15 s after done. */
    BLE_SENDER,
}

data class HotspotInfo(
    val ssid: String,
    val pass: String,
    /** `wpa2` | `wpa3t` | `wpa3` | `open`. */
    val sec: String,
    val hostIp: String,
    val prefixLength: Int,
    val ifaceName: String,
    val httpPort: Int,
    val token: String?,
    val expiresAt: Long,
    val purpose: HotspotPurpose,
) {
    val url: String? get() = if (hostIp.isNotEmpty() && httpPort > 0) "http://$hostIp:$httpPort/" else null
}

sealed interface HotspotState {
    data object Idle : HotspotState
    data object Starting : HotspotState
    data class Active(val info: HotspotInfo) : HotspotState
    data class Failed(val message: String) : HotspotState
}

class HotspotException(message: String) : Exception(message)

/**
 * Android `LocalOnlyHotspot` owner (spec §3.2). Started from a foreground UI action; the reservation is held while
 * [FtpServerService] keeps the process at foreground-service importance (it always runs the HTTP share the hotspot
 * serves), and [FtpServerService] tears it down with itself.
 */
class LocalOnlyHotspotManager(
    context: Context,
    private val lanAddressResolver: LanAddressResolver,
    private val peerDiscoveryHolder: PeerDiscoveryHolder,
    private val gate: HotspotSessionGate = HttpServerHolder.hotspotGate,
) {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<HotspotState>(HotspotState.Idle)
    val state: StateFlow<HotspotState> = _state.asStateFlow()

    @Volatile
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    /** Spec §2.4 [v2]: the HTTP share was auto-started for this hotspot and is stopped with it. */
    @Volatile
    var httpStartedForHotspot: Boolean = false
        private set

    private var autoStopJob: Job? = null
    private var stopping = false

    val activeInfo: HotspotInfo?
        get() = (state.value as? HotspotState.Active)?.info

    init {
        scope.launch {
            HttpServerHolder.peerEvents.collect { ev -> onPeerEvent(ev) }
        }
    }

    /**
     * Starts (or reuses) the LocalOnlyHotspot and returns its credentials. For [HotspotPurpose.BLE_RECEIVER]
     * [token] arms the `begin` gate; for [HotspotPurpose.BLE_SENDER] it arms the single-use `announce` gate;
     * for [HotspotPurpose.DIRECT] a non-null [token] (Files-only QR) arms the `begin` gate.
     */
    suspend fun start(purpose: HotspotPurpose, token: String? = null): HotspotInfo = mutex.withLock {
        val wm = wifiManager ?: throw HotspotException("Wi-Fi is not available on this device")
        if (!NearbyPermissions.hasHotspotPermissions(appContext)) {
            throw HotspotException(
                if (Build.VERSION.SDK_INT >= 33) "Allow Nearby devices to start a hotspot" else "Allow Location to start a hotspot",
            )
        }
        if (NearbyPermissions.needsLocationServices(appContext)) {
            throw HotspotException("Location must be on for Android ≤12 to start a hotspot")
        }
        autoStopJob?.cancel()

        val port = ensureHttpRunning()

        val existing = activeInfo
        val base: HotspotInfo = if (existing != null && reservation != null) {
            existing
        } else {
            _state.value = HotspotState.Starting
            val res = try {
                startReservation(wm)
            } catch (e: HotspotException) {
                _state.value = HotspotState.Failed(e.message ?: "Hotspot failed")
                rollbackHttpIfNeeded()
                throw e
            }
            reservation = res
            val cfg = extractConfig(res)
            val endpoint = awaitHotspotEndpoint()
            HotspotInfo(
                ssid = cfg.ssid,
                pass = cfg.pass,
                sec = cfg.sec,
                hostIp = endpoint?.ipv4.orEmpty(),
                prefixLength = endpoint?.prefixLength ?: 24,
                ifaceName = endpoint?.interfaceName.orEmpty(),
                httpPort = port,
                token = null,
                expiresAt = 0L,
                purpose = purpose,
            )
        }

        val now = System.currentTimeMillis()
        val info = base.copy(
            httpPort = port,
            token = token,
            expiresAt = if (token != null) now + HotspotSessionGate.TOKEN_TTL_MS else 0L,
            purpose = purpose,
        )
        applyGate(info)
        _state.value = HotspotState.Active(info)
        scheduleIdleStopIfDirect(info)
        info
    }

    /** Re-arms the 5-minute idle timer (Hotspot Direct) after visible activity. */
    fun touch() {
        val info = activeInfo ?: return
        scheduleIdleStopIfDirect(info)
    }

    /** Stops the hotspot [delayMs] from now (BLE sessions: 15 s grace after the transfer). */
    fun scheduleStop(delayMs: Long = SESSION_GRACE_MS) {
        autoStopJob?.cancel()
        autoStopJob = scope.launch {
            delay(delayMs)
            stop()
        }
    }

    /**
     * Stops the hotspot, clears every token / binding and stops the HTTP share if it was auto-started for it.
     * [fromService] = `true` when called by [FtpServerService] teardown (never re-enter the service then).
     */
    fun stop(fromService: Boolean = false) {
        if (stopping) return
        stopping = true
        try {
            autoStopJob?.cancel()
            autoStopJob = null
            runCatching { reservation?.close() }
            reservation = null
            gate.reset()
            peerDiscoveryHolder.setLocalHotspot(null)
            val stopHttp = httpStartedForHotspot
            httpStartedForHotspot = false
            if (stopHttp && !fromService) {
                runCatching { FtpServerService.stopHttp(appContext) }
            }
            if (_state.value !is HotspotState.Idle) _state.value = HotspotState.Idle
        } finally {
            stopping = false
        }
    }

    /** The user explicitly started the HTTP share: it is no longer ephemeral nor confined to the hotspot. */
    fun onUserStartedHttp() {
        httpStartedForHotspot = false
        gate.confineToHotspot = false
    }

    /**
     * The HTTP share was auto-started moments ago for this hotspot session (BLE receiver accepted before the
     * link mode was known): treat it as ephemeral so it is confined to the hotspot and stopped with it.
     */
    fun adoptEphemeralHttp() {
        httpStartedForHotspot = true
    }

    private fun applyGate(info: HotspotInfo) {
        val bindIp: Inet4Address? = info.hostIp.takeIf { it.isNotEmpty() }
            ?.let { runCatching { InetAddress.getByName(it) as? Inet4Address }.getOrNull() }
        gate.hotspotBindIp = bindIp
        gate.confineToHotspot = httpStartedForHotspot && bindIp != null
        gate.clearSessionToken()
        gate.clearAnnounceToken()
        val tok = info.token
        if (tok != null) {
            when (info.purpose) {
                HotspotPurpose.BLE_SENDER -> gate.armAnnounceToken(tok)
                HotspotPurpose.BLE_RECEIVER, HotspotPurpose.DIRECT -> gate.armSessionToken(tok)
            }
        }
        peerDiscoveryHolder.setLocalHotspot(
            bindIp?.let { LocalHotspotBinding(info.ifaceName, it, info.prefixLength) },
        )
    }

    private fun scheduleIdleStopIfDirect(info: HotspotInfo) {
        if (info.purpose != HotspotPurpose.DIRECT) return
        autoStopJob?.cancel()
        autoStopJob = scope.launch {
            delay(DIRECT_IDLE_MS)
            stop()
        }
    }

    private fun onPeerEvent(ev: HttpEvent) {
        val info = activeInfo ?: return
        when (ev) {
            is HttpEvent.PeerSessionStart, is HttpEvent.PeerAnnounce -> {
                if (info.purpose == HotspotPurpose.DIRECT) touch() else autoStopJob?.cancel()
            }
            is HttpEvent.PeerSessionEnd -> {
                if (info.purpose == HotspotPurpose.BLE_RECEIVER) scheduleStop(SESSION_GRACE_MS) else touch()
            }
            else -> Unit
        }
    }

    private suspend fun ensureHttpRunning(): Int {
        val st = HttpServerHolder.state.value
        if (st.isRunning && st.boundPort in 1..65535) return st.boundPort
        if (!st.isRunning) {
            httpStartedForHotspot = true
            FtpServerService.startHttp(appContext)
        }
        val deadline = System.currentTimeMillis() + HTTP_START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val s = HttpServerHolder.state.value
            if (s.isRunning && s.boundPort in 1..65535) return s.boundPort
            if (!s.isStarting && !s.isRunning && s.lastError != null) break
            delay(100)
        }
        rollbackHttpIfNeeded()
        throw HotspotException(HttpServerHolder.state.value.lastError ?: "Could not start the transfer server")
    }

    private fun rollbackHttpIfNeeded() {
        if (httpStartedForHotspot) {
            httpStartedForHotspot = false
            runCatching { FtpServerService.stopHttp(appContext) }
        }
    }

    @SuppressLint("MissingPermission") // checked via NearbyPermissions.hasHotspotPermissions above
    private suspend fun startReservation(wm: WifiManager): WifiManager.LocalOnlyHotspotReservation =
        withContext(Dispatchers.Main) {
            val result = withTimeoutOrNull(LOHS_START_TIMEOUT_MS) {
                suspendCancellableCoroutine<Result<WifiManager.LocalOnlyHotspotReservation>> { cont ->
                    val cb = object : WifiManager.LocalOnlyHotspotCallback() {
                        override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation?) {
                            if (res == null) {
                                if (cont.isActive) cont.resume(Result.failure(HotspotException("Hotspot failed to start")))
                                return
                            }
                            if (cont.isActive) cont.resume(Result.success(res)) else runCatching { res.close() }
                        }

                        override fun onStopped() {
                            onSystemStopped()
                        }

                        override fun onFailed(reason: Int) {
                            if (cont.isActive) cont.resume(Result.failure(HotspotException(mapFailure(reason))))
                        }
                    }
                    try {
                        wm.startLocalOnlyHotspot(cb, Handler(Looper.getMainLooper()))
                    } catch (e: SecurityException) {
                        if (cont.isActive) cont.resume(Result.failure(HotspotException("Permission denied: ${e.message}")))
                    } catch (e: IllegalStateException) {
                        if (cont.isActive) {
                            cont.resume(Result.failure(HotspotException("A hotspot request is already active — retry")))
                        }
                    }
                }
            } ?: throw HotspotException("Hotspot did not start within 15 s — turn Wi-Fi on and retry")
            result.getOrElse { throw (it as? HotspotException ?: HotspotException(it.message ?: "Hotspot failed")) }
        }

    private fun onSystemStopped() {
        scope.launch {
            if (reservation != null || _state.value is HotspotState.Active) {
                stop()
                _state.value = HotspotState.Failed("The system stopped the hotspot")
            }
        }
    }

    private suspend fun awaitHotspotEndpoint() = withContext(Dispatchers.IO) {
        var found = lanAddressResolver.hotspotEndpoints().firstOrNull()
        var waited = 0L
        while (found == null && waited < ENDPOINT_POLL_MS) {
            delay(250)
            waited += 250
            found = lanAddressResolver.hotspotEndpoints().firstOrNull()
        }
        found
    }

    private data class Cfg(val ssid: String, val pass: String, val sec: String)

    private fun extractConfig(res: WifiManager.LocalOnlyHotspotReservation): Cfg =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            extractSoftApConfig(res)
        } else {
            extractLegacyConfig(res)
        }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun extractSoftApConfig(res: WifiManager.LocalOnlyHotspotReservation): Cfg {
        val c: SoftApConfiguration = res.softApConfiguration
        val ssid = (if (Build.VERSION.SDK_INT >= 33) ssidFromWifiSsid(c) else null)
            ?: @Suppress("DEPRECATION") c.ssid.orEmpty().removeSurrounding("\"")
        val sec = when (c.securityType) {
            SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> "wpa2"
            SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION -> "wpa3t"
            SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> "wpa3"
            SoftApConfiguration.SECURITY_TYPE_OPEN -> "open"
            else -> "wpa2"
        }
        return Cfg(ssid = ssid, pass = c.passphrase.orEmpty(), sec = sec)
    }

    /** Never `WifiSsid.toString()` (hex for non-ASCII) — decode the raw UTF-8 bytes (spec §3.2 [v2]). */
    @RequiresApi(33)
    private fun ssidFromWifiSsid(c: SoftApConfiguration): String? =
        c.wifiSsid?.bytes?.decodeToString()?.takeIf { it.isNotEmpty() }

    @Suppress("DEPRECATION")
    private fun extractLegacyConfig(res: WifiManager.LocalOnlyHotspotReservation): Cfg {
        val wc = res.wifiConfiguration
        return Cfg(
            ssid = wc?.SSID.orEmpty().removeSurrounding("\""),
            pass = wc?.preSharedKey.orEmpty().removeSurrounding("\""),
            sec = "wpa2",
        )
    }

    companion object {
        const val SESSION_GRACE_MS = 15_000L
        const val DIRECT_IDLE_MS = 5L * 60L * 1000L
        private const val LOHS_START_TIMEOUT_MS = 15_000L
        private const val HTTP_START_TIMEOUT_MS = 6_000L
        private const val ENDPOINT_POLL_MS = 5_000L

        fun mapFailure(reason: Int): String = when (reason) {
            WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
                "Turn off the system hotspot / Wi-Fi Direct first"
            WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "Blocked by device policy"
            WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL,
            WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC,
            -> "Turn Wi-Fi on and retry"
            else -> "Hotspot failed (code $reason)"
        }
    }
}
