package com.minifile.explorer.core.http

import com.minifile.explorer.core.peer.HotspotLinkCodec
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
 * - Announce token (BLE `lohs-s`): **never open** — unarmed ⇒ always `403 {"code":"token"}`; single-use.
 */
class HotspotSessionGate(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()

    @Volatile
    var hotspotBindIp: InetAddress? = null

    @Volatile
    var confineToHotspot: Boolean = false

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
        }
        hotspotBindIp = null
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

    companion object {
        /** Spec §2.4: `expiresAt = armedAt + 240 s`. */
        const val TOKEN_TTL_MS = 240_000L
    }
}
