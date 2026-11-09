package com.minifile.explorer.core.http

import com.minifile.explorer.core.peer.HostDecision
import com.minifile.explorer.core.peer.HotspotLinkCodec
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Shared, thread-safe hotspot/BLE session state consulted by [HttpFileServerEngine]
 * (spec §2.4, `zero android.* imports`).
 *
 * - [hotspotBindIp]: IPv4 of the LocalOnlyHotspot interface this device currently hosts (`null` = not hosting).
 *   Drives `/api/peer/info` `"hotspot"` and the `announce` interface check.
 * - [confineToHotspot] (`httpStartedForHotspot`): the HTTP share was auto-started for a hotspot session, so every
 *   request whose `socket.localAddress != hotspotBindIp` is rejected (the share is never silently exposed on `wlan0`).
 * - Session token (BLE `lohs-r` / Files-only QR): while armed and not expired, `begin` requires `tok`.
 *   Universal `WIFI:` QR (Scheme B) never arms it.
 * - Announce token (BLE `lohs-s`): single-use; while armed a matching `tok` is always required.
 * - [openAnnounce] (Hotspot Direct only): with no announce token armed, an iPhone that joined via the universal
 *   `WIFI:` QR may announce without `tok`, but only on the hotspot interface, from inside the hotspot subnet, and
 *   bounded by [OPEN_ANNOUNCE_MAX_PER_WINDOW] accepts per [OPEN_ANNOUNCE_WINDOW_MS] and
 *   [OPEN_ANNOUNCE_MAX_DISTINCT_IDS] distinct ids per session. Otherwise unarmed ⇒ `403 {"code":"token"}`.
 */
class HotspotSessionGate(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()

    @Volatile
    var hotspotBindIp: InetAddress? = null

    @Volatile
    var confineToHotspot: Boolean = false

    /** Real prefix length of the hotspot subnet (fallback 24); used by the [openAnnounce] client-subnet check. */
    @Volatile
    var hotspotPrefixLength: Int = 24

    private var openAnnounce: Boolean = false
    private val openAcceptTimes = ArrayDeque<Long>()
    private val openAnnounceIds = LinkedHashSet<String>()

    private var sessionToken: String? = null
    private var sessionExpiresAt: Long = 0L
    private var announceToken: String? = null
    private var announceExpiresAt: Long = 0L

    val isHosting: Boolean
        get() = hotspotBindIp != null

    /** `true` while a non-expired session token gates `/api/peer/begin`. */
    val isSessionArmed: Boolean
        get() = synchronized(lock) { activeSessionTokenLocked() != null }

    val isAnnounceArmed: Boolean
        get() = synchronized(lock) { activeAnnounceTokenLocked() != null }

    /** `true` while unauthenticated Hotspot Direct announces may be admitted (see [admitOpenAnnounce]). */
    val isOpenAnnounce: Boolean
        get() = synchronized(lock) { openAnnounce }

    /** How `/api/peer/announce` is currently authorised. An armed token always wins over [openAnnounce]. */
    val announceMode: AnnounceMode
        get() = synchronized(lock) {
            when {
                activeAnnounceTokenLocked() != null -> AnnounceMode.TOKEN
                openAnnounce -> AnnounceMode.OPEN
                else -> AnnounceMode.CLOSED
            }
        }

    /** Enables / disables open (token-less) announces; any change resets the rate-limit and id budget. */
    fun setOpenAnnounce(enabled: Boolean) = synchronized(lock) {
        if (openAnnounce != enabled) {
            openAcceptTimes.clear()
            openAnnounceIds.clear()
        }
        openAnnounce = enabled
    }

    fun armSessionToken(token: String, ttlMs: Long = TOKEN_TTL_MS) = synchronized(lock) {
        sessionToken = token
        sessionExpiresAt = nowMs() + ttlMs
    }

    fun armAnnounceToken(token: String, ttlMs: Long = TOKEN_TTL_MS) = synchronized(lock) {
        announceToken = token
        announceExpiresAt = nowMs() + ttlMs
    }

    fun clearSessionToken() = synchronized(lock) {
        sessionToken = null
        sessionExpiresAt = 0L
    }

    fun clearAnnounceToken() = synchronized(lock) {
        announceToken = null
        announceExpiresAt = 0L
    }

    /** Clears every token and the hotspot binding (host stop / cancel / BLE `error`). */
    fun reset() {
        synchronized(lock) {
            sessionToken = null
            sessionExpiresAt = 0L
            announceToken = null
            announceExpiresAt = 0L
            openAnnounce = false
            openAcceptTimes.clear()
            openAnnounceIds.clear()
        }
        hotspotBindIp = null
        hotspotPrefixLength = 24
        confineToHotspot = false
    }

    /**
     * `begin` gate. Returns `true` when no session token is armed (plain LAN behaviour unchanged)
     * or when [tok] matches in constant time.
     */
    fun checkBeginToken(tok: String?): BeginGate = synchronized(lock) {
        val expected = activeSessionTokenLocked() ?: return BeginGate.OPEN
        if (tok != null && HotspotLinkCodec.constantTimeEquals(expected, tok)) BeginGate.MATCHED else BeginGate.REJECTED
    }

    /** Single-use announce consumption: `true` exactly once for the armed, non-expired token. */
    fun consumeAnnounceToken(tok: String?): Boolean = synchronized(lock) {
        val expected = activeAnnounceTokenLocked() ?: return false
        if (tok == null || !HotspotLinkCodec.constantTimeEquals(expected, tok)) return false
        announceToken = null
        announceExpiresAt = 0L
        true
    }

    /**
     * Open announce admission (Hotspot Direct, no token armed). Accepts only when [openAnnounce] is on, the
     * request's [localAddress] is the hotspot IP, [clientAddress] is an IPv4 inside the hotspot subnet, the
     * sliding-window rate limit has room and [id] is known or the distinct-id budget has room.
     */
    fun admitOpenAnnounce(localAddress: InetAddress?, clientAddress: InetAddress?, id: String): OpenAnnounce =
        synchronized(lock) {
            if (!openAnnounce || activeAnnounceTokenLocked() != null) return OpenAnnounce.CLOSED
            val bind = hotspotBindIp as? Inet4Address ?: return OpenAnnounce.WRONG_NETWORK
            if (localAddress == null || localAddress != bind) return OpenAnnounce.WRONG_NETWORK
            val client = clientAddress as? Inet4Address ?: return OpenAnnounce.WRONG_NETWORK
            if (client == bind) return OpenAnnounce.WRONG_NETWORK
            if (!HostDecision.inSubnet(client.address, bind.address, hotspotPrefixLength)) {
                return OpenAnnounce.WRONG_NETWORK
            }
            val now = nowMs()
            while (openAcceptTimes.isNotEmpty() && now - openAcceptTimes.first() >= OPEN_ANNOUNCE_WINDOW_MS) {
                openAcceptTimes.removeFirst()
            }
            if (openAcceptTimes.size >= OPEN_ANNOUNCE_MAX_PER_WINDOW) return OpenAnnounce.RATE_LIMITED
            if (id !in openAnnounceIds && openAnnounceIds.size >= OPEN_ANNOUNCE_MAX_DISTINCT_IDS) {
                return OpenAnnounce.RATE_LIMITED
            }
            openAcceptTimes.addLast(now)
            openAnnounceIds.add(id)
            OpenAnnounce.ACCEPTED
        }

    /**
     * Interface confinement: when hosting, `announce` (always) and every request (when [confineToHotspot])
     * must arrive on the hotspot address.
     */
    fun isOnHotspotInterface(localAddress: InetAddress?): Boolean {
        val bind = hotspotBindIp ?: return false
        return localAddress != null && localAddress == bind
    }

    fun shouldRejectForConfinement(localAddress: InetAddress?): Boolean {
        if (!confineToHotspot) return false
        return !isOnHotspotInterface(localAddress)
    }

    private fun activeSessionTokenLocked(): String? {
        val t = sessionToken ?: return null
        if (nowMs() >= sessionExpiresAt) {
            sessionToken = null
            sessionExpiresAt = 0L
            return null
        }
        return t
    }

    private fun activeAnnounceTokenLocked(): String? {
        val t = announceToken ?: return null
        if (nowMs() >= announceExpiresAt) {
            announceToken = null
            announceExpiresAt = 0L
            return null
        }
        return t
    }

    enum class BeginGate { OPEN, MATCHED, REJECTED }

    enum class AnnounceMode { TOKEN, OPEN, CLOSED }

    enum class OpenAnnounce { ACCEPTED, CLOSED, WRONG_NETWORK, RATE_LIMITED }

    companion object {
        /** Spec §2.4: `expiresAt = armedAt + 240 s`. */
        const val TOKEN_TTL_MS = 240_000L

        const val OPEN_ANNOUNCE_WINDOW_MS = 60_000L
        const val OPEN_ANNOUNCE_MAX_PER_WINDOW = 8
        const val OPEN_ANNOUNCE_MAX_DISTINCT_IDS = 16
    }
}
