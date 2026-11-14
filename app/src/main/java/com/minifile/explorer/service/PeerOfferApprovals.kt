package com.minifile.explorer.service

import com.minifile.explorer.core.http.PeerBeginOffer
import com.minifile.explorer.core.peer.PeerPathPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Transfer ▸ Require Approval for LAN peer offers (`POST /api/peer/begin`), mirroring iOS where `begin` blocks on the
 * consent sheet for up to 60 s. The pending offer is surfaced as an [IncomingBleRequest] (`viaLan = true`) so the
 * MainActivity-level `PeerConsentDialog` handles BLE and LAN offers alike.
 */
class PeerOfferApprovals(
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L

        /** Disjoint from BLE request ids (which count up from 1) so `respond` can be routed to both safely. */
        const val REQUEST_ID_BASE = 1_000_000_000L

        val instance: PeerOfferApprovals by lazy { PeerOfferApprovals() }
    }

    private class Pending(val request: IncomingBleRequest) {
        val latch = CountDownLatch(1)

        @Volatile var accepted = false
    }

    private val lock = Any()
    private val ids = AtomicLong(REQUEST_ID_BASE)
    private var pending: Pending? = null
    private val _pendingRequest = MutableStateFlow<IncomingBleRequest?>(null)
    val pendingRequest: StateFlow<IncomingBleRequest?> = _pendingRequest.asStateFlow()

    /**
     * Blocks the caller (an HTTP connection thread) until the user answers. Returns `true` immediately when
     * [confirmRequired] is `false`; `false` when another offer is already pending, on Decline, or on timeout.
     */
    fun requestApproval(offer: PeerBeginOffer, confirmRequired: Boolean): Boolean {
        if (!confirmRequired) return true
        val now = clock()
        val p = Pending(
            IncomingBleRequest(
                requestId = ids.incrementAndGet(),
                fromId = offer.clientIp,
                fromName = PeerPathPolicy.sanitizeDisplayName(offer.deviceName, 64).ifEmpty { offer.clientIp },
                os = "",
                files = offer.files,
                bytes = offer.bytes,
                deadlineMs = now + timeoutMs,
                viaLan = true,
            ),
        )
        synchronized(lock) {
            if (pending != null) return false
            pending = p
            _pendingRequest.value = p.request
        }
        try {
            val answered = p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return answered && p.accepted
        } finally {
            synchronized(lock) {
                if (pending === p) {
                    pending = null
                    _pendingRequest.value = null
                }
            }
        }
    }

    /** Accept / Decline from the consent dialog; ignores ids that are not the pending LAN offer. */
    fun respond(requestId: Long, accept: Boolean) {
        val p = synchronized(lock) { pending?.takeIf { it.request.requestId == requestId } } ?: return
        p.accepted = accept
        p.latch.countDown()
    }

    /** Declines whatever is pending (e.g. the HTTP server stopped or Discoverable was turned off). */
    fun declinePending() {
        val p = synchronized(lock) { pending } ?: return
        p.accepted = false
        p.latch.countDown()
    }
}
