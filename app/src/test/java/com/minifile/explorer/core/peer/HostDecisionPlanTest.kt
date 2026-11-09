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
}
