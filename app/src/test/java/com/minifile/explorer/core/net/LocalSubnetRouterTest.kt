package com.minifile.explorer.core.net

import com.minifile.explorer.core.peer.HostDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Outbound peer socket routing (fixes "iPhone returned HTTP 302" with STA+AP concurrency / VPN). */
class LocalSubnetRouterTest {

    private fun ip(s: String): ByteArray = checkNotNull(HostDecision.parseIpv4(s)) { s }

    private val wlan0Handle = 1001L
    private val wlan0Router = TrackedNetSubnet(wlan0Handle, "wlan0", ip("192.168.1.25"), 24)
    private val sysHotspotWlan1 = LocalIfaceSubnet("wlan1", ip("192.168.43.1"), 24)
    private val wlan0Iface = LocalIfaceSubnet("wlan0", ip("192.168.1.25"), 24)

    private fun decide(
        target: String,
        pinned: Boolean = false,
        hs: LocalIfaceSubnet? = null,
        tracked: List<TrackedNetSubnet> = listOf(wlan0Router),
        ifaces: List<LocalIfaceSubnet> = listOf(wlan0Iface, sysHotspotWlan1),
        fallback: Long? = wlan0Handle,
        activeSubnets: List<TrackedNetSubnet> = tracked.filter { it.handleId == fallback },
    ) = LocalSubnetRouter.decideTcp(ip(target), pinned, hs, tracked, ifaces, fallback, activeSubnets)

    @Test
    fun manualSystemHotspotPeer_bindsHotspotSourceIp_neverWlan0() {
        val d = decide("192.168.43.50")
        assertEquals(TcpBindDecision.BindLocalSourceIp(ip("192.168.43.1"), "wlan1"), d)
        assertNotEquals(TcpBindDecision.BindTrackedNetwork(wlan0Handle), d)
    }

    @Test
    fun routerLanPeer_bindsTrackedWlan0_forVpnBypass() {
        assertEquals(TcpBindDecision.BindTrackedNetwork(wlan0Handle), decide("192.168.1.88"))
    }

    @Test
    fun clientOnIphonePersonalHotspot_bindsTrackedWlan0() {
        val iphoneHs = TrackedNetSubnet(7L, "wlan0", ip("172.20.10.2"), 28)
        val d = decide(
            "172.20.10.1",
            tracked = listOf(iphoneHs),
            ifaces = listOf(LocalIfaceSubnet("wlan0", ip("172.20.10.2"), 28)),
            fallback = 7L,
        )
        assertEquals(TcpBindDecision.BindTrackedNetwork(7L), d)
    }

    @Test
    fun lohsOnWlan2_bindsLohsSourceIp() {
        val lohs = LocalIfaceSubnet("wlan2", ip("192.168.49.1"), 24)
        val d = decide("192.168.49.77", hs = lohs, ifaces = listOf(wlan0Iface, lohs))
        assertEquals(TcpBindDecision.BindLocalSourceIp(ip("192.168.49.1"), "wlan2"), d)
    }

    @Test
    fun lohsBindingWinsEvenWhenInterfaceEnumerationMissedIt() {
        val lohs = LocalIfaceSubnet("ap0", ip("192.168.49.1"), 24)
        val d = decide("192.168.49.20", hs = lohs, ifaces = listOf(wlan0Iface))
        assertEquals(TcpBindDecision.BindLocalSourceIp(ip("192.168.49.1"), "ap0"), d)
    }

    @Test
    fun pinnedSpecifierNetwork_wins() {
        assertEquals(TcpBindDecision.UsePinnedNetwork, decide("192.168.43.50", pinned = true))
        assertEquals(TcpBindDecision.UsePinnedNetwork, decide("10.9.9.9", pinned = true))
    }

    @Test
    fun loopback_isNeverBound() {
        assertEquals(TcpBindDecision.NoExplicitBind, decide("127.0.0.1"))
        assertEquals(TcpBindDecision.NoExplicitBind, decide("127.0.0.1", pinned = true))
    }

    @Test
    fun multiVlanCampus_routedTargetBindsWlan0_hotspotTargetBindsSoftAp() {
        val campus = TrackedNetSubnet(55L, "wlan0", ip("10.1.2.5"), 24)
        val ifaces = listOf(LocalIfaceSubnet("wlan0", ip("10.1.2.5"), 24), sysHotspotWlan1)
        assertEquals(
            TcpBindDecision.BindTrackedNetwork(55L),
            decide("10.1.9.20", tracked = listOf(campus), ifaces = ifaces, fallback = 55L),
        )
        assertEquals(
            TcpBindDecision.BindLocalSourceIp(ip("192.168.43.1"), "wlan1"),
            decide("192.168.43.99", tracked = listOf(campus), ifaces = ifaces, fallback = 55L),
        )
    }

    @Test
    fun androidStandaloneSoftApOnWlan0_isUntracked_bindsSourceIp() {
        // STA off, soft-AP brought up on wlan0 itself: no tracked network owns it.
        val ap = LocalIfaceSubnet("wlan0", ip("192.168.43.1"), 24)
        val d = decide("192.168.43.10", tracked = emptyList(), ifaces = listOf(ap), fallback = null)
        assertEquals(TcpBindDecision.BindLocalSourceIp(ip("192.168.43.1"), "wlan0"), d)
    }

    @Test
    fun usbTetherAndApBridge_bindSourceIp() {
        val rndis = LocalIfaceSubnet("rndis0", ip("192.168.42.129"), 24)
        val apBr = LocalIfaceSubnet("ap_br_wlan1", ip("192.168.191.1"), 24)
        val ifaces = listOf(wlan0Iface, rndis, apBr)
        assertEquals(TcpBindDecision.BindLocalSourceIp(ip("192.168.42.129"), "rndis0"), decide("192.168.42.20", ifaces = ifaces))
        assertEquals(TcpBindDecision.BindLocalSourceIp(ip("192.168.191.1"), "ap_br_wlan1"), decide("192.168.191.5", ifaces = ifaces))
    }

    @Test
    fun longestPrefixWins() {
        val wide = TrackedNetSubnet(1L, "eth0", ip("10.0.0.2"), 8)
        val narrow = TrackedNetSubnet(2L, "wlan0", ip("10.1.2.3"), 24)
        assertEquals(
            TcpBindDecision.BindTrackedNetwork(2L),
            decide("10.1.2.50", tracked = listOf(wide, narrow), ifaces = emptyList(), fallback = 2L),
        )
        assertEquals(
            TcpBindDecision.BindTrackedNetwork(1L),
            decide("10.200.0.1", tracked = listOf(wide, narrow), ifaces = emptyList(), fallback = 2L),
        )
    }

    @Test
    fun trackedInterfaceWithoutPrefixYet_usesItsNetwork() {
        // LinkProperties carry a different (stale) address but the interface name matches a live iface.
        val stale = TrackedNetSubnet(9L, "wlan0", ip("192.168.7.2"), 24)
        val d = decide("192.168.1.40", tracked = listOf(stale), ifaces = listOf(wlan0Iface), fallback = 9L)
        assertEquals(TcpBindDecision.BindTrackedNetwork(9L), d)
    }

    @Test
    fun fallback_noActiveWifi_noExplicitBind() {
        assertEquals(
            TcpBindDecision.NoExplicitBind,
            decide("10.50.0.1", tracked = emptyList(), ifaces = emptyList(), fallback = null),
        )
    }

    @Test
    fun fallback_linkPropertiesUnknown_bindsActiveWifi() {
        assertEquals(
            TcpBindDecision.BindTrackedNetwork(3L),
            decide("192.168.5.5", tracked = emptyList(), ifaces = emptyList(), fallback = 3L, activeSubnets = emptyList()),
        )
    }

    @Test
    fun udp_staysUnboundWhileSystemHotspotIsUp() {
        assertEquals(
            UdpBindDecision.NO_EXPLICIT_BIND,
            LocalSubnetRouter.decideUdp(false, false, true, "wlan0", listOf(wlan0Router), listOf(wlan0Iface, sysHotspotWlan1)),
        )
        assertEquals(
            UdpBindDecision.BIND_ACTIVE_WIFI,
            LocalSubnetRouter.decideUdp(false, false, true, "wlan0", listOf(wlan0Router), listOf(wlan0Iface)),
        )
        assertEquals(
            UdpBindDecision.USE_PINNED_NETWORK,
            LocalSubnetRouter.decideUdp(true, true, true, "wlan0", listOf(wlan0Router), listOf(wlan0Iface, sysHotspotWlan1)),
        )
        assertEquals(
            UdpBindDecision.NO_EXPLICIT_BIND,
            LocalSubnetRouter.decideUdp(false, true, true, "wlan0", listOf(wlan0Router), listOf(wlan0Iface)),
        )
        assertEquals(
            UdpBindDecision.NO_EXPLICIT_BIND,
            LocalSubnetRouter.decideUdp(false, false, false, null, emptyList(), listOf(sysHotspotWlan1)),
        )
    }

    @Test
    fun onAnyLocalSubnet_andPrivateRanges() {
        val subnets = listOf(ip("192.168.43.1") to 24, ip("172.20.10.2") to 28)
        assertTrue(LocalSubnetRouter.isOnAnyLocalSubnet(ip("192.168.43.7"), subnets))
        assertTrue(LocalSubnetRouter.isOnAnyLocalSubnet(ip("172.20.10.1"), subnets))
        assertFalse(LocalSubnetRouter.isOnAnyLocalSubnet(ip("172.20.10.17"), subnets))
        assertFalse(LocalSubnetRouter.isOnAnyLocalSubnet(ip("192.168.1.9"), subnets))
        assertTrue(LocalSubnetRouter.isPrivateLike(ip("100.64.1.1")))
        assertTrue(LocalSubnetRouter.isPrivateLike(ip("169.254.3.3")))
        assertTrue(LocalSubnetRouter.isPrivateLike(ip("172.31.0.1")))
        assertFalse(LocalSubnetRouter.isPrivateLike(ip("172.32.0.1")))
        assertFalse(LocalSubnetRouter.isPrivateLike(ip("8.8.8.8")))
    }

    @Test
    fun interfaceNameClassification() {
        for (n in listOf("ap0", "ap_br_wlan1", "swlan0", "softap0", "sap0", "uap0", "wlan1", "wlan2", "p2p0", "rndis0", "ncm0", "usb0", "bridge0")) {
            assertTrue(n, HotspotInterfaceClassifier.isHotspotOrTetherName(n))
            assertTrue(n, HotspotInterfaceClassifier.isLocalLanName(n))
        }
        assertFalse(HotspotInterfaceClassifier.isHotspotOrTetherName("wlan0"))
        assertTrue(HotspotInterfaceClassifier.isLocalLanName("wlan0"))
        for (n in listOf("rmnet_data0", "ccmni0", "pdp_ip0", "clat4", "v4-rmnet0", "tun0", "ppp0", "ipsec0", "wg0", "tailscale0", "utun3", "lo", "dummy0", "epdg0", "ims0", "oem0")) {
            assertTrue(n, HotspotInterfaceClassifier.isExcluded(n))
            assertFalse(n, HotspotInterfaceClassifier.isHotspotOrTetherName(n))
            assertFalse(n, HotspotInterfaceClassifier.isLocalLanName(n))
        }
    }
}
