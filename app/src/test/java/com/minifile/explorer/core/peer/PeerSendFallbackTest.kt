package com.minifile.explorer.core.peer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Automatic LAN → Bluetooth fallback (`MainViewModel.runPeerSend`): iPhone on this phone's manual hotspot while a VPN
 * hijacks `POST /api/peer/begin` with `302` → retried over BLE negotiation with `lanHttpBlocked = true` (L2CAP).
 */
class PeerSendFallbackTest {

    private val lanIphone = PeerInfo(
        id = "ABC123-ios-full-device-id",
        name = "iPhone",
        host = "192.168.43.50",
        port = 8080,
        version = "1.0.20",
        readOnly = false,
        source = PeerSource.BEACON,
        lastSeenMs = 0L,
        platform = "ios",
    )
    private val bleIphone = lanIphone.copy(id = "abc123", host = "", port = 0, source = PeerSource.BLE)
    private val okResult = PeerSendResult(files = 1, bytes = 10L, skipped = 0, errors = emptyList())
    private val intercepted = PeerTransferException(
        302,
        PeerTransferException.interceptedMessage("iPhone", 302),
        beforeSession = true,
        kind = PeerFailureKind.INTERCEPTED,
    )

    private fun run(
        lanError: PeerTransferException?,
        bytes: Long = 1_024L,
        cancelled: Boolean = false,
        bleAvailable: Boolean = true,
        vpn: Boolean = false,
        bleOutcome: () -> PeerSendResult = { okResult },
        calls: MutableList<String> = mutableListOf(),
    ): PeerSendResult = runBlocking {
        PeerSendFallback.sendWithBleFallback(
            peer = lanIphone,
            bytes = bytes,
            isCancelled = { cancelled },
            blePeerAvailable = { bleAvailable },
            vpnActive = { vpn },
            lanSend = {
                calls += "lan"
                if (lanError != null) throw lanError
                okResult
            },
            bleSend = { blocked ->
                calls += "ble:$blocked"
                bleOutcome()
            },
            onFallback = { calls += "fallback" },
        )
    }

    @Test
    fun intercepted302_withSameBlePeer_fallsBackToL2capAndSucceeds() {
        val calls = mutableListOf<String>()
        val res = run(intercepted, calls = calls)
        assertSame(okResult, res)
        assertEquals(listOf("lan", "fallback", "ble:true"), calls)
    }

    @Test
    fun unreachable_withBlePeer_fallsBackWithoutBlockingLan() {
        val calls = mutableListOf<String>()
        val unreachable = PeerTransferException(null, "iPhone not reachable", beforeSession = true, kind = PeerFailureKind.UNREACHABLE)
        run(unreachable, calls = calls)
        assertEquals(listOf("lan", "fallback", "ble:false"), calls)
    }

    @Test
    fun lanSuccess_neverTouchesBluetooth() {
        val calls = mutableListOf<String>()
        run(null, calls = calls)
        assertEquals(listOf("lan"), calls)
    }

    @Test
    fun noBlePeer_surfacesVpnMessage() {
        try {
            run(intercepted, bleAvailable = false, vpn = true)
            fail("expected error")
        } catch (e: PeerTransferException) {
            assertTrue(e.userMessage, e.userMessage.contains("intercepted by a VPN (HTTP 302)"))
            assertFalse(e.userMessage.contains("returned HTTP"))
            assertTrue(e.isInterceptedRedirect)
        }
        try {
            run(intercepted, bleAvailable = false, vpn = false)
            fail("expected error")
        } catch (e: PeerTransferException) {
            assertEquals(PeerTransferException.interceptedMessage("iPhone", 302), e.userMessage)
        }
    }

    @Test
    fun over50MB_doesNotFallBack() {
        val calls = mutableListOf<String>()
        try {
            run(intercepted, bytes = HostDecision.L2CAP_AUTO_MAX_BYTES + 1, calls = calls)
            fail("expected error")
        } catch (e: PeerTransferException) {
            assertTrue(e.isInterceptedRedirect)
        }
        assertEquals(listOf("lan"), calls)
    }

    @Test
    fun cancelled_orMidSession_orDeclined_doNotFallBack() {
        val calls = mutableListOf<String>()
        runCatching { run(intercepted, cancelled = true, calls = calls) }
        val midSession = PeerTransferException(null, "Connection to iPhone lost", beforeSession = false, kind = PeerFailureKind.OTHER)
        runCatching { run(midSession, calls = calls) }
        val declined = PeerTransferException(403, "Declined by iPhone", beforeSession = true)
        runCatching { run(declined, calls = calls) }
        assertEquals(listOf("lan", "lan", "lan"), calls)
    }

    @Test
    fun bleFailure_surfacesOriginalActionableError_unlessDeclined() {
        try {
            run(intercepted, bleOutcome = { throw PeerTransferException(null, "iPhone is out of Bluetooth range") })
            fail("expected error")
        } catch (e: PeerTransferException) {
            assertTrue(e.userMessage.contains("intercepted (HTTP 302)"))
        }
        try {
            run(intercepted, bleOutcome = { throw PeerTransferException(403, "Declined by iPhone") })
            fail("expected error")
        } catch (e: PeerTransferException) {
            assertEquals("Declined by iPhone", e.userMessage)
        }
    }

    @Test
    fun id6Matching() {
        assertTrue(PeerSendFallback.sameDevice(lanIphone, bleIphone))
        assertNotNull(PeerSendFallback.matchingBlePeer(lanIphone, listOf(bleIphone)))
        assertNull(PeerSendFallback.matchingBlePeer(lanIphone, listOf(bleIphone.copy(id = "zzz999"))))
    }

    @Test
    fun shouldFallbackRules() {
        assertTrue(PeerSendFallback.shouldFallback(intercepted, false, true, 0L))
        assertTrue(PeerSendFallback.shouldFallback(intercepted, false, true, PeerSendFallback.BLE_FALLBACK_MAX_BYTES))
        assertFalse(PeerSendFallback.shouldFallback(intercepted, false, false, 10L))
        assertFalse(PeerSendFallback.shouldFallback(intercepted, true, true, 10L))
        assertFalse(PeerSendFallback.shouldFallback(PeerTransferException(302, "x"), false, true, 10L)) // not pre-session
    }

    @Test
    fun plan_lanHttpBlocked_prefersL2capAndNeverLan() {
        val p = HostDecision.plan("android", "ios", lanOverlap = true, receiverCaps = listOf("join", "l2cap"), senderCanHost = true, ackPsm = 0x0081, bytes = 400L * 1024 * 1024, lanHttpBlocked = true)
        assertEquals(LinkPlan(LinkMode.L2CAP, null), p)
        val noPsm = HostDecision.plan("android", "ios", lanOverlap = true, receiverCaps = listOf("join"), senderCanHost = true, ackPsm = 0, bytes = 1L, lanHttpBlocked = true)
        assertEquals(LinkMode.LOHS_S, noPsm.mode)
        val normal = HostDecision.plan("android", "ios", lanOverlap = true, receiverCaps = listOf("join", "l2cap"), senderCanHost = true, ackPsm = 0x0081, bytes = 1L)
        assertEquals(LinkMode.LAN, normal.mode)
        val androidRx = HostDecision.plan("android", "android", lanOverlap = true, receiverCaps = listOf("lohs", "join", "l2cap"), senderCanHost = true, ackPsm = 0x0085, bytes = 1L, lanHttpBlocked = true)
        assertEquals(LinkMode.L2CAP, androidRx.mode)
    }
}
