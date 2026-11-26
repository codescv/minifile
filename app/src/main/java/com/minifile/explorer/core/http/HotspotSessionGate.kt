package com.minifile.explorer.core.http

import com.minifile.explorer.core.diag.Diag
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

    /**
     * After the announce token was consumed by peer [reannounceId], the same token from the same id stays valid until
     * [reannounceExpiresAt] so the receiver can re-announce (e.g. an iPhone that restarted its HTTP listener on a new
     * port after being suspended while the user was in the Camera app). Any other id is still rejected.
     */
    private var reannounceToken: String? = null
    private var reannounceId: String? = null
    private var reannounceExpiresAt: Long = 0L

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
                activeReannounceTokenLocked() != null -> AnnounceMode.TOKEN
                openAnnounce -> AnnounceMode.OPEN
                else -> AnnounceMode.CLOSED
            }
        }

    /** Enables / disables open (token-less) announces; any change resets the rate-limit and id budget. */
    fun setOpenAnnounce(enabled: Boolean) = synchronized(lock) {
        if (openAnnounce != enabled) {
            Diag.log(Diag.PEER) { "gate openAnnounce=$enabled bind=${hotspotBindIp?.hostAddress}" }
            openAcceptTimes.clear()
            openAnnounceIds.clear()
        }
        openAnnounce = enabled
    }

    fun armSessionToken(token: String, ttlMs: Long = TOKEN_TTL_MS) = synchronized(lock) {
        Diag.log(Diag.PEER) { "gate arm session tok=$token ttlMs=$ttlMs" }
        sessionToken = token
        sessionExpiresAt = nowMs() + ttlMs
    }

    fun armAnnounceToken(token: String, ttlMs: Long = TOKEN_TTL_MS) = synchronized(lock) {
        Diag.log(Diag.PEER) { "gate arm announce tok=$token ttlMs=$ttlMs bind=${hotspotBindIp?.hostAddress}" }
        announceToken = token
        announceExpiresAt = nowMs() + ttlMs
        clearReannounceLocked()
    }

    fun clearSessionToken() = synchronized(lock) {
        sessionToken = null
        sessionExpiresAt = 0L
    }

    fun clearAnnounceToken() = synchronized(lock) {
        announceToken = null
        announceExpiresAt = 0L
        clearReannounceLocked()
    }

    /** Clears every token and the hotspot binding (host stop / cancel / BLE `error`). */
    fun reset() {
        Diag.log(Diag.PEER) { "gate reset (bind=${hotspotBindIp?.hostAddress})" }
        synchronized(lock) {
            sessionToken = null
            sessionExpiresAt = 0L
            announceToken = null
            announceExpiresAt = 0L
            clearReannounceLocked()
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

    /**
     * Announce token check. Without [id] it is strictly single-use (`true` exactly once for the armed, non-expired
     * token). With [id] the first match binds the token to that peer: later announces with the same token **and the
     * same id** are accepted again until the original TTL ends (idempotent re-announce, e.g. after the receiver
     * restarted its HTTP listener on a new port); any other id is rejected.
     */
    fun consumeAnnounceToken(tok: String?, id: String? = null): Boolean =
        consumeAnnounceTokenLocked(tok, id).also { ok ->
            Diag.log(Diag.PEER) { "gate announce tok=${tok.orEmpty()} id=${id.orEmpty()} -> ${if (ok) "accepted" else "rejected"}" }
        }

    private fun consumeAnnounceTokenLocked(tok: String?, id: String?): Boolean = synchronized(lock) {
        if (tok == null) return false
        val expected = activeAnnounceTokenLocked()
        if (expected != null) {
            if (!HotspotLinkCodec.constantTimeEquals(expected, tok)) return false
            val expiresAt = announceExpiresAt
            announceToken = null
            announceExpiresAt = 0L
            if (!id.isNullOrEmpty()) {
                reannounceToken = expected
                reannounceId = id
                reannounceExpiresAt = expiresAt
            }
            return true
        }
        val again = activeReannounceTokenLocked() ?: return false
        if (id.isNullOrEmpty() || id != reannounceId) return false
        HotspotLinkCodec.constantTimeEquals(again, tok)
    }

    /**
     * Open announce admission (Hotspot Direct, no token armed). Accepts only when [openAnnounce] is on, the
     * request's [localAddress] is the hotspot IP, [clientAddress] is an IPv4 inside the hotspot subnet, the
     * sliding-window rate limit has room and [id] is known or the distinct-id budget has room.
     */
    fun admitOpenAnnounce(localAddress: InetAddress?, clientAddress: InetAddress?, id: String): OpenAnnounce =
        synchronized(lock) {
            if (!openAnnounce || activeAnnounceTokenLocked() != null || activeReannounceTokenLocked() != null) {
                return OpenAnnounce.CLOSED
            }
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

    private fun activeReannounceTokenLocked(): String? {
        val t = reannounceToken ?: return null
        if (nowMs() >= reannounceExpiresAt) {
            clearReannounceLocked()
            return null
        }
        return t
    }

    private fun clearReannounceLocked() {
        reannounceToken = null
        reannounceId = null
        reannounceExpiresAt = 0L
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
