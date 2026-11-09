package com.minifile.explorer.core.peer

/**
 * Pure-JVM automatic Bluetooth fallback for LAN / hotspot sends (`zero android.* imports`).
 *
 * Typical case: the user manually joined this phone's System Personal Hotspot with an iPhone while a VPN app
 * (Clash / Sing-box / V2Ray / corporate `tun0`) is active. Android's `netd` VPN rule runs before the `local_network`
 * soft-AP route, so the VPN hijacks `POST http://<iphone>:8080/api/peer/begin` and answers `302 Found`. When that
 * happens (or the peer is simply unreachable) **before any file byte was sent**, and the very same device is visible
 * over Bluetooth LE (same `id6`), the send is retried transparently over BLE negotiation with
 * `lanHttpBlocked = true` (L2CAP `MFL2`, no VPN involved, zero QR, zero password).
 */
object PeerSendFallback {

    /** Largest transfer retried over Bluetooth automatically (same budget as [HostDecision.L2CAP_AUTO_MAX_BYTES]). */
    const val BLE_FALLBACK_MAX_BYTES: Long = HostDecision.L2CAP_AUTO_MAX_BYTES

    /** `true` when [ble] advertises the same Files installation as [lan] (`id6` match, case-insensitive). */
    fun sameDevice(lan: PeerInfo, ble: PeerInfo): Boolean {
        val a = HotspotLinkCodec.id6Of(lan.id)
        val b = HotspotLinkCodec.id6Of(ble.id)
        return a.isNotEmpty() && a.equals(b, ignoreCase = true)
    }

    /** The BLE row in [blePeers] that is the same device as [lan], if any. */
    fun matchingBlePeer(lan: PeerInfo, blePeers: List<PeerInfo>): PeerInfo? =
        blePeers.firstOrNull { sameDevice(lan, it) }

    /**
     * Whether a failed LAN send may be retried over Bluetooth: the failure happened before a session existed, it was
     * an interception (`301..308`) or a connect failure, the user did not cancel, a matching BLE peer is in range and
     * the payload fits [BLE_FALLBACK_MAX_BYTES].
     */
    fun shouldFallback(
        error: PeerTransferException,
        cancelled: Boolean,
        blePeerAvailable: Boolean,
        bytes: Long,
    ): Boolean {
        if (cancelled || !blePeerAvailable) return false
        if (!error.beforeSession) return false
        if (error.kind != PeerFailureKind.INTERCEPTED && error.kind != PeerFailureKind.UNREACHABLE) return false
        return bytes in 0..BLE_FALLBACK_MAX_BYTES
    }

    /** User-facing copy when no fallback is possible; mentions the VPN explicitly when one is known to be active. */
    fun finalMessage(error: PeerTransferException, peerName: String, vpnActive: Boolean): String {
        if (!error.isInterceptedRedirect) return error.userMessage
        val status = error.httpStatus ?: 302
        return if (vpnActive) {
            "Connection to $peerName was intercepted by a VPN (HTTP $status) — turn off the VPN (or enable LAN bypass) and try again"
        } else {
            PeerTransferException.interceptedMessage(peerName, status)
        }
    }

    /**
     * Runs [lanSend]; on an eligible failure (see [shouldFallback]) calls [onFallback] (e.g. show the Bluetooth link
     * dialog) and returns [bleSend]`(lanHttpBlocked)`. When the Bluetooth attempt fails too, its error is surfaced only
     * if the receiver actively declined (`403`); otherwise the original, actionable LAN error is thrown.
     */
    suspend fun sendWithBleFallback(
        peer: PeerInfo,
        bytes: Long,
        isCancelled: () -> Boolean,
        blePeerAvailable: () -> Boolean,
        vpnActive: () -> Boolean,
        lanSend: suspend () -> PeerSendResult,
        bleSend: suspend (lanHttpBlocked: Boolean) -> PeerSendResult,
        onFallback: (PeerTransferException) -> Unit = {},
    ): PeerSendResult {
        try {
            return lanSend()
        } catch (e: PeerTransferException) {
            val name = PeerPathPolicy.sanitizeDisplayName(peer.name, 64).ifEmpty { peer.host }
            if (!shouldFallback(e, isCancelled(), blePeerAvailable(), bytes)) {
                if (e.isInterceptedRedirect) {
                    throw PeerTransferException(
                        httpStatus = e.httpStatus,
                        userMessage = finalMessage(e, name, vpnActive()),
                        cause = e,
                        beforeSession = e.beforeSession,
                        kind = e.kind,
                    )
                }
                throw e
            }
            onFallback(e)
            try {
                return bleSend(e.isInterceptedRedirect)
            } catch (bleErr: PeerTransferException) {
                if (bleErr.httpStatus == 403 || isCancelled()) throw bleErr
                throw PeerTransferException(
                    httpStatus = e.httpStatus,
                    userMessage = finalMessage(e, name, vpnActive()),
                    cause = bleErr,
                    beforeSession = e.beforeSession,
                    kind = e.kind,
                )
            }
        }
    }
}
