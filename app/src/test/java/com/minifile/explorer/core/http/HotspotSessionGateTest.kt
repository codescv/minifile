package com.minifile.explorer.core.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/** Spec §2.4 [HotspotSessionGate] incl. Hotspot Direct open announce. */
class HotspotSessionGateTest {

    private var nowMs = 5_000_000L
    private lateinit var gate: HotspotSessionGate

    private val ap = InetAddress.getByName("192.168.43.1")
    private val iphone = InetAddress.getByName("192.168.43.77")
    private val sta = InetAddress.getByName("10.0.0.5")
    private val tok = "0123456789abcdef0123456789abcdef"

    @Before
    fun setUp() {
        gate = HotspotSessionGate(nowMs = { nowMs })
        gate.hotspotBindIp = ap
        gate.hotspotPrefixLength = 24
    }

    @Test
    fun closedByDefault_unarmedAnnounceIsRejected() {
        assertEquals(HotspotSessionGate.AnnounceMode.CLOSED, gate.announceMode)
        assertFalse(gate.isOpenAnnounce)
        assertEquals(HotspotSessionGate.OpenAnnounce.CLOSED, gate.admitOpenAnnounce(ap, iphone, "ios-1"))
    }

    @Test
    fun openAnnounce_acceptsOnHotspotInterfaceFromHotspotSubnet() {
        gate.setOpenAnnounce(true)
        assertEquals(HotspotSessionGate.AnnounceMode.OPEN, gate.announceMode)
        assertEquals(HotspotSessionGate.OpenAnnounce.ACCEPTED, gate.admitOpenAnnounce(ap, iphone, "ios-1"))
    }

    @Test
    fun openAnnounce_rejectsWrongInterface_wrongSubnet_selfAndNotHosting() {
        gate.setOpenAnnounce(true)
        // Arrived on the STA address (wlan0), not the hotspot.
        assertEquals(HotspotSessionGate.OpenAnnounce.WRONG_NETWORK, gate.admitOpenAnnounce(sta, iphone, "a"))
        assertEquals(HotspotSessionGate.OpenAnnounce.WRONG_NETWORK, gate.admitOpenAnnounce(null, iphone, "a"))
        // Client outside the hotspot /24.
        val outside = InetAddress.getByName("192.168.44.9")
        assertEquals(HotspotSessionGate.OpenAnnounce.WRONG_NETWORK, gate.admitOpenAnnounce(ap, outside, "a"))
        // Narrow prefix honoured.
        gate.hotspotPrefixLength = 28
        assertEquals(HotspotSessionGate.OpenAnnounce.WRONG_NETWORK, gate.admitOpenAnnounce(ap, iphone, "a"))
        gate.hotspotPrefixLength = 24
        // The AP's own address and IPv6 clients are not joiners.
        assertEquals(HotspotSessionGate.OpenAnnounce.WRONG_NETWORK, gate.admitOpenAnnounce(ap, ap, "a"))
        assertEquals(
            HotspotSessionGate.OpenAnnounce.WRONG_NETWORK,
            gate.admitOpenAnnounce(ap, InetAddress.getByName("fe80::1"), "a"),
        )
        // Not hosting at all.
        gate.hotspotBindIp = null
        assertEquals(HotspotSessionGate.OpenAnnounce.WRONG_NETWORK, gate.admitOpenAnnounce(ap, iphone, "a"))
    }

    @Test
    fun openAnnounce_rateLimitedPerMinute_thenWindowSlides() {
        gate.setOpenAnnounce(true)
        repeat(HotspotSessionGate.OPEN_ANNOUNCE_MAX_PER_WINDOW) {
            assertEquals(HotspotSessionGate.OpenAnnounce.ACCEPTED, gate.admitOpenAnnounce(ap, iphone, "ios-1"))
            nowMs += 1_000
        }
        assertEquals(HotspotSessionGate.OpenAnnounce.RATE_LIMITED, gate.admitOpenAnnounce(ap, iphone, "ios-1"))
        nowMs += HotspotSessionGate.OPEN_ANNOUNCE_WINDOW_MS
        assertEquals(HotspotSessionGate.OpenAnnounce.ACCEPTED, gate.admitOpenAnnounce(ap, iphone, "ios-1"))
    }

    @Test
    fun openAnnounce_distinctIdsCapped_knownIdStillAccepted() {
        gate.setOpenAnnounce(true)
        for (i in 0 until HotspotSessionGate.OPEN_ANNOUNCE_MAX_DISTINCT_IDS) {
            assertEquals(HotspotSessionGate.OpenAnnounce.ACCEPTED, gate.admitOpenAnnounce(ap, iphone, "id-$i"))
            nowMs += HotspotSessionGate.OPEN_ANNOUNCE_WINDOW_MS // keep the per-minute budget out of the way
        }
        assertEquals(HotspotSessionGate.OpenAnnounce.RATE_LIMITED, gate.admitOpenAnnounce(ap, iphone, "id-new"))
        assertEquals(HotspotSessionGate.OpenAnnounce.ACCEPTED, gate.admitOpenAnnounce(ap, iphone, "id-3"))
    }

    @Test
    fun armedToken_winsOverOpenAnnounce_andStaysSingleUse() {
        gate.setOpenAnnounce(true)
        gate.armAnnounceToken(tok)
        assertEquals(HotspotSessionGate.AnnounceMode.TOKEN, gate.announceMode)
        assertEquals(HotspotSessionGate.OpenAnnounce.CLOSED, gate.admitOpenAnnounce(ap, iphone, "ios-1"))
        assertFalse(gate.consumeAnnounceToken(null))
        assertTrue(gate.consumeAnnounceToken(tok))
        assertFalse(gate.consumeAnnounceToken(tok))
    }

    @Test
    fun announceToken_boundToId_allowsIdempotentReannounceFromSamePeerOnly() {
        gate.armAnnounceToken(tok)
        assertTrue(gate.consumeAnnounceToken(tok, "ios-1"))
        assertFalse(gate.isAnnounceArmed)
        // Re-announce (iPhone restarted its listener on a new port): same token + same id is accepted again.
        assertEquals(HotspotSessionGate.AnnounceMode.TOKEN, gate.announceMode)
        assertTrue(gate.consumeAnnounceToken(tok, "ios-1"))
        assertTrue(gate.consumeAnnounceToken(tok, "ios-1"))
        // Another device, a missing id, or a wrong token never ride on the consumed token.
        assertFalse(gate.consumeAnnounceToken(tok, "ios-2"))
        assertFalse(gate.consumeAnnounceToken(tok, null))
        assertFalse(gate.consumeAnnounceToken(tok, ""))
        assertFalse(gate.consumeAnnounceToken("ffffffffffffffffffffffffffffffff", "ios-1"))
        assertFalse(gate.consumeAnnounceToken(null, "ios-1"))
        // Open announces stay closed while the re-announce grant is alive.
        gate.setOpenAnnounce(true)
        assertEquals(HotspotSessionGate.OpenAnnounce.CLOSED, gate.admitOpenAnnounce(ap, iphone, "ios-3"))
    }

    @Test
    fun reannounceGrant_expiresWithOriginalTtl_andClearsOnRearmOrReset() {
        gate.armAnnounceToken(tok)
        nowMs += 100_000L
        assertTrue(gate.consumeAnnounceToken(tok, "ios-1"))
        nowMs += HotspotSessionGate.TOKEN_TTL_MS - 100_000L - 1
        assertTrue(gate.consumeAnnounceToken(tok, "ios-1"))
        nowMs += 1
        assertFalse(gate.consumeAnnounceToken(tok, "ios-1"))
        assertEquals(HotspotSessionGate.AnnounceMode.CLOSED, gate.announceMode)

        gate.armAnnounceToken(tok)
        assertTrue(gate.consumeAnnounceToken(tok, "ios-1"))
        gate.clearAnnounceToken()
        assertFalse(gate.consumeAnnounceToken(tok, "ios-1"))

        gate.armAnnounceToken(tok)
        assertTrue(gate.consumeAnnounceToken(tok, "ios-1"))
        gate.reset()
        assertFalse(gate.consumeAnnounceToken(tok, "ios-1"))
        assertEquals(HotspotSessionGate.AnnounceMode.CLOSED, gate.announceMode)
    }

    @Test
    fun resetAndDisable_clearOpenAnnounceAndBudget() {
        gate.setOpenAnnounce(true)
        repeat(HotspotSessionGate.OPEN_ANNOUNCE_MAX_PER_WINDOW) { gate.admitOpenAnnounce(ap, iphone, "x") }
        assertEquals(HotspotSessionGate.OpenAnnounce.RATE_LIMITED, gate.admitOpenAnnounce(ap, iphone, "x"))
        gate.setOpenAnnounce(false)
        gate.setOpenAnnounce(true)
        assertEquals(HotspotSessionGate.OpenAnnounce.ACCEPTED, gate.admitOpenAnnounce(ap, iphone, "x"))

        gate.reset()
        assertFalse(gate.isOpenAnnounce)
        assertEquals(HotspotSessionGate.AnnounceMode.CLOSED, gate.announceMode)
        assertEquals(24, gate.hotspotPrefixLength)
        assertFalse(gate.isHosting)
    }
}
