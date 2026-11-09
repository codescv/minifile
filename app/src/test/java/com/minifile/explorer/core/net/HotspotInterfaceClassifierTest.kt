package com.minifile.explorer.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec §3.4 LocalOnlyHotspot interface detection across OEM naming schemes. */
class HotspotInterfaceClassifierTest {

    private fun ip(name: String, ip: String, prefix: Int = 24) = IfaceIpv4(name, ip, prefix)

    @Test
    fun onePlusStaOnWlan0_apOnWlan1() {
        val cur = listOf(ip("wlan0", "192.168.1.23"), ip("wlan1", "192.168.110.1"), ip("rmnet_data0", "10.64.1.2"))
        val out = HotspotInterfaceClassifier.classify(cur, trackedIps = setOf("192.168.1.23", "10.64.1.2"))
        assertEquals(listOf(HotspotCandidate("wlan1", "192.168.110.1", 24)), out)
    }

    @Test
    fun onePlusApOnWlan2_withSnapshot_newAddressWins() {
        val before = setOf("192.168.1.23")
        val cur = listOf(ip("wlan0", "192.168.1.23"), ip("wlan2", "10.32.7.141", 24))
        val out = HotspotInterfaceClassifier.classify(cur, trackedIps = setOf("192.168.1.23"), before = before)
        assertEquals("wlan2", out.single().interfaceName)
        assertEquals("10.32.7.141", out.single().ipv4)
    }

    @Test
    fun aospAp0_andSamsungSwlan0() {
        val ap0 = HotspotInterfaceClassifier.classify(listOf(ip("ap0", "192.168.43.1")), emptySet())
        assertEquals("ap0", ap0.single().interfaceName)
        val sw = HotspotInterfaceClassifier.classify(
            listOf(ip("wlan0", "192.168.0.8"), ip("swlan0", "192.168.104.1", 24)),
            trackedIps = setOf("192.168.0.8"),
        )
        assertEquals(listOf(HotspotCandidate("swlan0", "192.168.104.1", 24)), sw)
    }

    @Test
    fun wlan0_isOnlyAHotspotWhenItAppearedAfterLohsStart() {
        // STA off: the AP itself comes up on wlan0.
        val cur = listOf(ip("wlan0", "192.168.49.1"))
        assertTrue(HotspotInterfaceClassifier.classify(cur, emptySet(), before = null).isEmpty())
        assertEquals(
            "wlan0",
            HotspotInterfaceClassifier.classify(cur, emptySet(), before = emptySet()).single().interfaceName,
        )
        // A pre-existing wlan0 address is never treated as the hotspot.
        assertTrue(HotspotInterfaceClassifier.classify(cur, emptySet(), before = setOf("192.168.49.1")).isEmpty())
    }

    @Test
    fun trackedStaAddressOnSecondaryRadioIsExcluded() {
        // Dual-STA devices may put the station on wlan1: tracked ⇒ not a hotspot.
        val cur = listOf(ip("wlan1", "192.168.1.50"), ip("wlan2", "192.168.88.1"))
        val out = HotspotInterfaceClassifier.classify(cur, trackedIps = setOf("192.168.1.50"))
        assertEquals(listOf("wlan2"), out.map { it.interfaceName })
    }

    @Test
    fun ranking_newFirst_thenSoftAp_thenSecondaryWlan_thenP2p_dotOneFirst() {
        val cur = listOf(
            ip("p2p0", "192.168.49.1"),
            ip("wlan1", "192.168.5.9"),
            ip("ap0", "192.168.43.7"),
            ip("ap1", "192.168.44.1"),
        )
        val out = HotspotInterfaceClassifier.classify(cur, emptySet())
        assertEquals(listOf("ap1", "ap0", "wlan1", "p2p0"), out.map { it.interfaceName })
        val withNew = HotspotInterfaceClassifier.classify(
            cur + ip("wlan2", "10.1.1.9"),
            emptySet(),
            before = setOf("192.168.49.1", "192.168.5.9", "192.168.43.7", "192.168.44.1"),
        )
        assertEquals("wlan2", withNew.first().interfaceName)
    }

    @Test
    fun excludedAndInvalidAndPrefixFallback() {
        val cur = listOf(
            ip("rmnet0", "10.0.0.1"),
            ip("tun0", "10.8.0.1"),
            ip("eth0", "192.168.2.1"), // wired, not new-wireless ⇒ never a LOHS
            ip("ap0", "not-an-ip"),
            ip("wlan1", "192.168.77.1", prefix = 0),
        )
        val out = HotspotInterfaceClassifier.classify(cur, emptySet(), before = emptySet())
        assertEquals(listOf(HotspotCandidate("wlan1", "192.168.77.1", 24)), out)
    }

    @Test
    fun nameHelpers() {
        assertTrue(HotspotInterfaceClassifier.isHotspotInterfaceName("wlan1"))
        assertTrue(HotspotInterfaceClassifier.isHotspotInterfaceName("wlan12"))
        assertTrue(HotspotInterfaceClassifier.isHotspotInterfaceName("swlan0"))
        assertTrue(HotspotInterfaceClassifier.isHotspotInterfaceName("ap0"))
        assertFalse(HotspotInterfaceClassifier.isHotspotInterfaceName("wlan0"))
        assertFalse(HotspotInterfaceClassifier.isHotspotInterfaceName("rmnet_data1"))
        assertFalse(HotspotInterfaceClassifier.isHotspotInterfaceName(""))
    }
}
