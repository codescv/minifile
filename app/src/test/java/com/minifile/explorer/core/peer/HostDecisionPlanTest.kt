package com.minifile.explorer.core.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostDecisionPlanTest {

    private val fullCaps = listOf("lohs", "join")

    @Test
    fun capsFor_onlyAdvertisesLohsWhenHostingCanSucceed() {
        assertEquals(listOf("lohs", "join"), HostDecision.capsFor(canHostHotspot = true))
        assertEquals(listOf("join"), HostDecision.capsFor(canHostHotspot = false))
        assertEquals(HostDecision.CAP_LOHS, "lohs")
        assertEquals(HostDecision.CAP_JOIN, "join")
    }

    @Test
    fun receiverCanHost_respectsAndroidCapsAndStaysLenientForLegacyOrIos() {
        assertTrue(HostDecision.receiverCanHost("android", fullCaps))
        assertTrue(HostDecision.receiverCanHost("ANDROID", listOf(" LOHS ")))
        assertFalse(HostDecision.receiverCanHost("android", listOf("join")))
        // Empty caps = unknown / older build: keep v1.0.18 behaviour (attempt lohs-r).
        assertTrue(HostDecision.receiverCanHost("android", emptyList()))
        // iOS receivers are never asked to host (lohs-s), so caps don't matter.
        assertTrue(HostDecision.receiverCanHost("ios", listOf("join")))
    }

    @Test
    fun plan_wireModeAlwaysMatchesDecideTable() {
        val cases = listOf(
            Triple("android", "android", false),
            Triple("android", "ios", false),
            Triple("ios", "android", false),
            Triple("android", "ios", true),
            Triple("android", "android", true),
        )
        for ((s, r, lan) in cases) {
            val plan = HostDecision.plan(s, r, lan, fullCaps, senderCanHost = true)
            assertEquals("$s→$r lan=$lan", HostDecision.decide(s, r, lan), plan.mode)
            assertNull("$s→$r lan=$lan", plan.blocker)
        }
    }

    @Test
    fun plan_androidReceiverWithoutLohs_isBlockedWithActionableMessage() {
        val plan = HostDecision.plan("android", "android", false, listOf("join"), senderCanHost = true)
        assertEquals(LinkMode.LOHS_R, plan.mode)
        assertEquals(LinkBlocker.RECEIVER_CANNOT_HOST, plan.blocker)
        assertEquals(
            "Allow Nearby Wi-Fi permission on Pixel 9 (Files ▸ Transfer)",
            HostDecision.blockerMessage(plan.blocker!!, "Pixel 9"),
        )
    }

    @Test
    fun plan_lanOverlapNeverBlocked() {
        val plan = HostDecision.plan("android", "android", true, listOf("join"), senderCanHost = false)
        assertEquals(LinkMode.LAN, plan.mode)
        assertNull(plan.blocker)
    }

    @Test
    fun plan_iosReceiverNeedsSenderHotspot() {
        val ok = HostDecision.plan("android", "ios", false, emptyList(), senderCanHost = true)
        assertEquals(LinkMode.LOHS_S, ok.mode)
        assertNull(ok.blocker)

        val blocked = HostDecision.plan("android", "ios", false, emptyList(), senderCanHost = false)
        assertEquals(LinkMode.LOHS_S, blocked.mode)
        assertEquals(LinkBlocker.SENDER_CANNOT_HOST, blocked.blocker)
        assertTrue(HostDecision.blockerMessage(LinkBlocker.SENDER_CANNOT_HOST, "iPhone").contains("iPhone"))
    }

    @Test
    fun plan_iosPairIsBlocked() {
        val plan = HostDecision.plan("ios", "ios", false, fullCaps, senderCanHost = true)
        assertNull(plan.mode)
        assertEquals(LinkBlocker.IOS_PAIR, plan.blocker)
        assertEquals("Use Nearby (AWDL) between two iPhones", HostDecision.blockerMessage(LinkBlocker.IOS_PAIR, "x"))
    }

    @Test
    fun ackCaps_roundTripThroughCodec() {
        val ack = HotspotLinkMessage.Ack(
            id = "ab12cd34-5678",
            name = "Pixel",
            os = HotspotLinkCodec.OS_ANDROID,
            caps = HostDecision.capsFor(canHostHotspot = false),
            port = 8080,
            lan = emptyList(),
        )
        val decoded = HotspotLinkCodec.decodeMessage(HotspotLinkCodec.encodeMessage(ack)) as HotspotLinkMessage.Ack
        assertFalse(HostDecision.receiverCanHost(decoded.os, decoded.caps))

        val card = BlePeerCard("id", "n", "android", "1.0", HostDecision.capsFor(true), 8080, emptyList())
        val decodedCard = HotspotLinkCodec.decodePeerCard(HotspotLinkCodec.encodePeerCard(card))!!
        assertTrue(HostDecision.receiverCanHost(decodedCard.os, decodedCard.caps))
    }
    // --- v1.0.20 BLE L2CAP (MFL2) ---

    private val mb = 1024L * 1024L

    @Test
    fun l2cap_androidToIos_smallTransfer_choosesL2capEvenWithoutHotspotPermission() {
        for (canHost in listOf(true, false)) {
            val plan = HostDecision.plan("android", "ios", false, emptyList(), senderCanHost = canHost, ackPsm = 0x80, bytes = 3 * mb)
            assertEquals(LinkMode.L2CAP, plan.mode)
            assertNull(plan.blocker)
        }
        // Exactly 50 MB is still L2CAP (inclusive).
        assertEquals(
            LinkMode.L2CAP,
            HostDecision.plan("android", "ios", false, emptyList(), true, ackPsm = 129, bytes = HostDecision.L2CAP_AUTO_MAX_BYTES).mode,
        )
        assertEquals(52_428_800L, HostDecision.L2CAP_AUTO_MAX_BYTES)
    }

    @Test
    fun l2cap_androidToIos_largeTransfer_prefersHotspot_butL2capBeatsBlocker() {
        val big = HostDecision.plan("android", "ios", false, emptyList(), senderCanHost = true, ackPsm = 129, bytes = 51 * mb)
        assertEquals(LinkMode.LOHS_S, big.mode)
        assertNull(big.blocker)
        val bigNoPerm = HostDecision.plan("android", "ios", false, emptyList(), senderCanHost = false, ackPsm = 129, bytes = 51 * mb)
        assertEquals(LinkMode.L2CAP, bigNoPerm.mode)
        assertNull(bigNoPerm.blocker)
    }

    @Test
    fun l2cap_iosToAndroid_smallTransfer_choosesL2cap() {
        val plan = HostDecision.plan("ios", "android", false, fullCaps, senderCanHost = false, ackPsm = 200, bytes = mb)
        assertEquals(LinkMode.L2CAP, plan.mode)
        val big = HostDecision.plan("ios", "android", false, fullCaps, senderCanHost = false, ackPsm = 200, bytes = 60 * mb)
        assertEquals(LinkMode.LOHS_R, big.mode)
    }

    @Test
    fun l2cap_androidToAndroid_keepsLohsRFirst_andFallsBackToL2capWithoutPermission() {
        val fast = HostDecision.plan("android", "android", false, fullCaps, senderCanHost = true, ackPsm = 150, bytes = mb)
        assertEquals(LinkMode.LOHS_R, fast.mode)
        assertNull(fast.blocker)
        val noPerm = HostDecision.plan("android", "android", false, listOf("join", "l2cap"), senderCanHost = true, ackPsm = 150, bytes = 500 * mb)
        assertEquals(LinkMode.L2CAP, noPerm.mode)
        assertNull(noPerm.blocker)
    }

    @Test
    fun l2cap_backwardCompat_psmZeroOrInvalid_behavesLikeV1019() {
        for (psm in listOf(0, -1, 70_000)) {
            val ios = HostDecision.plan("android", "ios", false, emptyList(), senderCanHost = false, ackPsm = psm, bytes = 1)
            assertEquals(LinkMode.LOHS_S, ios.mode)
            assertEquals(LinkBlocker.SENDER_CANNOT_HOST, ios.blocker)
            val android = HostDecision.plan("android", "android", false, listOf("join"), senderCanHost = true, ackPsm = psm, bytes = 1)
            assertEquals(LinkBlocker.RECEIVER_CANNOT_HOST, android.blocker)
        }
        // LAN overlap always wins.
        assertEquals(LinkMode.LAN, HostDecision.plan("android", "ios", true, emptyList(), true, ackPsm = 99, bytes = 1).mode)
        assertEquals(LinkBlocker.IOS_PAIR, HostDecision.plan("ios", "ios", false, emptyList(), true, ackPsm = 99).blocker)
    }

    @Test
    fun l2capFallback_picksHotspotModeThatCanWork() {
        assertEquals(LinkMode.LOHS_S, HostDecision.l2capFallback("ios", emptyList(), senderCanHost = true))
        assertNull(HostDecision.l2capFallback("ios", emptyList(), senderCanHost = false))
        assertEquals(LinkMode.LOHS_R, HostDecision.l2capFallback("android", fullCaps, senderCanHost = false))
        assertNull(HostDecision.l2capFallback("android", listOf("join", "l2cap"), senderCanHost = true))
    }

    @Test
    fun ackPsm_codecRoundTrip_andBackwardCompat() {
        val ack = HotspotLinkMessage.Ack("id", "Pixel", "android", HostDecision.capsFor(false, l2cap = true), 8080, emptyList(), psm = 0x0085)
        val json = String(HotspotLinkCodec.encodeMessage(ack), Charsets.UTF_8)
        assertTrue(json, json.contains("\"psm\":133"))
        assertTrue(json, json.contains("\"l2cap\""))
        val back = HotspotLinkCodec.decodeMessage(json.toByteArray()) as HotspotLinkMessage.Ack
        assertEquals(133, back.psm)
        assertTrue(back.hasL2cap)
        assertEquals(listOf("join", "l2cap"), back.caps)

        // v1.0.19 ack: no psm key at all, and psm 0 is never emitted.
        val legacy = HotspotLinkMessage.Ack("id", "Pixel", "android", listOf("lohs", "join"), 8080, emptyList())
        val legacyJson = String(HotspotLinkCodec.encodeMessage(legacy), Charsets.UTF_8)
        assertFalse(legacyJson.contains("psm"))
        val decodedLegacy = HotspotLinkCodec.decodeMessage(legacyJson.toByteArray()) as HotspotLinkMessage.Ack
        assertEquals(0, decodedLegacy.psm)
        assertFalse(decodedLegacy.hasL2cap)
        // Out-of-range PSM from a buggy peer is ignored.
        val bogus = HotspotLinkCodec.decodeMessage("{\"t\":\"ack\",\"id\":\"x\",\"psm\":99999}".toByteArray()) as HotspotLinkMessage.Ack
        assertEquals(0, bogus.psm)
    }

    @Test
    fun linkL2cap_codecRoundTrip() {
        val msg = HotspotLinkMessage.Link(LinkMode.L2CAP)
        assertEquals("{\"t\":\"link\",\"mode\":\"l2cap\"}", String(HotspotLinkCodec.encodeMessage(msg), Charsets.UTF_8))
        assertEquals(msg, HotspotLinkCodec.decodeMessage(HotspotLinkCodec.encodeMessage(msg)))
        assertEquals(LinkMode.L2CAP, LinkMode.fromWire("l2cap"))
        assertEquals("l2cap", HostDecision.CAP_L2CAP)
    }
}
