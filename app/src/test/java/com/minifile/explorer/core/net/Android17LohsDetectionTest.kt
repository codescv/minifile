package com.minifile.explorer.core.net

import com.minifile.explorer.core.peer.HostDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-device regression (2026-11-26, OnePlus PKX110 / Android 17 hosting LOHS `AndroidShare_1413` for an iPhone 17):
 * station `wlan0 = 100.74.207.102/21`, randomized LOHS subnet `10.122.99.x/24` with a non-`.1` gateway, iPhone joined
 * as `10.122.99.178` — Android logged `hostIp=?` for the whole session.
 */
class Android17LohsDetectionTest {

    private fun ip(name: String, ip: String, prefix: Int = 24) = IfaceIpv4(name, ip, prefix)
    private fun b(s: String): ByteArray = checkNotNull(HostDecision.parseIpv4(s)) { s }

    private val sta = ip("wlan0", "100.74.207.102", 21)
    private val lohs = ip("wlan2", "10.122.99.203", 24)
    private val before = setOf("100.74.207.102")
    private val beforeIfaces = setOf("wlan0")

    // ---- Root cause: LOHS registered as a NET_CAPABILITY_LOCAL_NETWORK network --------------------------------------

    @Test
    fun localNetworkAgentIps_areNotStationIps() {
        val nets = listOf(
            HotspotInterfaceClassifier.TrackedNetIps("wlan0", listOf("100.74.207.102"), isLocalNetwork = false),
            HotspotInterfaceClassifier.TrackedNetIps("wlan2", listOf("10.122.99.203"), isLocalNetwork = true),
            HotspotInterfaceClassifier.TrackedNetIps("rmnet_data1", listOf("10.64.3.9"), isLocalNetwork = false),
        )
        assertEquals(setOf("100.74.207.102", "10.64.3.9"), HotspotInterfaceClassifier.stationIps(nets))
    }

    @Test
    fun oldBehaviour_trackedLohsIpWasRejected_newStationFilterFindsIt() {
        val cur = listOf(sta, lohs)
        // Pre-fix: every ConnectivityManager IP (incl. the LOHS local network agent) counted as "tracked".
        val allTracked = setOf("100.74.207.102", "10.122.99.203")
        assertTrue(HotspotInterfaceClassifier.classify(cur, allTracked, before).isEmpty())
        // Fix: only station IPs are excluded.
        val out = HotspotInterfaceClassifier.classify(cur, setOf("100.74.207.102"), before, beforeIfaces)
        assertEquals(HotspotCandidate("wlan2", "10.122.99.203", 24), out.first())
    }

    @Test
    fun relaxedPass_newInterfaceWins_evenIfTrackedOrOddlyNamed() {
        val odd = ip("lohs0", "10.122.99.203")
        val cur = listOf(sta, odd)
        val tracked = setOf("100.74.207.102", "10.122.99.203")
        // Without interface names from the pre-LOHS snapshot the strict rules stay conservative.
        assertTrue(HotspotInterfaceClassifier.classify(cur, tracked, before).isEmpty())
        val out = HotspotInterfaceClassifier.classify(cur, tracked, before, beforeIfaces)
        assertEquals(listOf(HotspotCandidate("lohs0", "10.122.99.203", 24)), out)
    }

    @Test
    fun relaxedPass_neverPicksStationThatRenumbered_norExcludedIfaces() {
        // wlan0 got a new DHCP lease during LOHS start: it had an address before ⇒ never the hotspot.
        val cur = listOf(ip("wlan0", "100.74.201.5", 21), ip("tun0", "10.8.0.2"), ip("rmnet_data0", "10.64.1.2"))
        val out = HotspotInterfaceClassifier.classify(cur, setOf("100.74.201.5"), before, beforeIfaces)
        assertTrue(out.toString(), out.isEmpty())
    }

    @Test
    fun relaxedPass_ranksWirelessBeforeOtherNewInterfaces() {
        val cur = listOf(sta, ip("br9", "10.9.9.1"), ip("ap_br_wlan2", "10.122.99.203"))
        val tracked = setOf("100.74.207.102", "10.9.9.1", "10.122.99.203")
        val out = HotspotInterfaceClassifier.classify(cur, tracked, before, beforeIfaces)
        assertEquals(listOf("ap_br_wlan2", "br9"), out.map { it.interfaceName })
    }

    @Test
    fun loopbackExclusionIsExact() {
        assertTrue(HotspotInterfaceClassifier.isExcluded("lo"))
        assertTrue(HotspotInterfaceClassifier.isExcluded("lo0"))
        assertFalse(HotspotInterfaceClassifier.isExcluded("lohs0"))
        assertFalse(HotspotInterfaceClassifier.isExcluded("local_ap0"))
    }

    // ---- Adoption from the peer's BLE `ready` IP ----------------------------------------------------------------

    @Test
    fun matchPeerSubnet_realReadyIp_adoptsLohsAddress() {
        val m = HotspotInterfaceClassifier.matchPeerSubnet("10.122.99.178", listOf(sta, lohs), before)
        assertEquals(HotspotCandidate("wlan2", "10.122.99.203", 24), m)
    }

    @Test
    fun matchPeerSubnet_bogusStationReadyIp_neverAdoptsWlan0() {
        // iOS first reported its old corp Wi-Fi IP, which is inside wlan0's /21.
        assertNull(HotspotInterfaceClassifier.matchPeerSubnet("100.74.206.81", listOf(sta, lohs), before))
        // Without a snapshot the station would match: proves the `before` filter is what protects wlan0.
        assertEquals("wlan0", HotspotInterfaceClassifier.matchPeerSubnet("100.74.206.81", listOf(sta), null)?.interfaceName)
    }

    @Test
    fun matchPeerSubnet_rejectsGarbage_selfAndExcluded() {
        assertNull(HotspotInterfaceClassifier.matchPeerSubnet("nope", listOf(lohs), before))
        assertNull(HotspotInterfaceClassifier.matchPeerSubnet("10.122.99.203", listOf(lohs), before))
        assertNull(HotspotInterfaceClassifier.matchPeerSubnet("10.8.0.9", listOf(ip("tun0", "10.8.0.2")), before))
        assertNull(HotspotInterfaceClassifier.matchPeerSubnet("192.168.5.5", listOf(sta, lohs), before))
    }

    @Test
    fun matchPeerSubnet_longestPrefixWins() {
        val wide = ip("wlan3", "10.122.0.1", 16)
        val m = HotspotInterfaceClassifier.matchPeerSubnet("10.122.99.178", listOf(wide, lohs), before)
        assertEquals("wlan2", m?.interfaceName)
    }

    // ---- Diagnostics -------------------------------------------------------------------------------------------

    @Test
    fun describe_listsEveryInterfaceWithFlags() {
        val s = HotspotInterfaceClassifier.describe(
            all = listOf(sta, lohs, ip("rmnet_data0", "10.64.1.2", 30)),
            stationIps = setOf("100.74.207.102", "10.64.1.2"),
            localNetworkIps = setOf("10.122.99.203"),
            before = setOf("100.74.207.102", "10.64.1.2"),
        )
        assertEquals(
            "[wlan0 100.74.207.102/21 sta, wlan2 10.122.99.203/24 local new, rmnet_data0 10.64.1.2/30 sta excl]",
            s,
        )
        assertEquals("[]", HotspotInterfaceClassifier.describe(emptyList(), emptySet()))
    }

    // ---- Routing towards the iPhone on the LOHS ----------------------------------------------------------------

    private val wlan0Handle = 634765889549L
    private val wlan0Tracked = TrackedNetSubnet(wlan0Handle, "wlan0", b("100.74.207.102"), 21)
    private val wlan0Iface = LocalIfaceSubnet("wlan0", b("100.74.207.102"), 21)
    private val lohsIface = LocalIfaceSubnet("wlan2", b("10.122.99.203"), 24)

    private fun route(target: String, hs: LocalIfaceSubnet?, ifaces: List<LocalIfaceSubnet>) =
        LocalSubnetRouter.decideTcp(
            targetIpv4 = b(target),
            hasPinnedNetwork = false,
            localHotspotSubnet = hs,
            trackedSubnets = listOf(wlan0Tracked),
            localIfaces = ifaces,
            fallbackActiveWifiHandle = wlan0Handle,
            activeWifiSubnets = listOf(wlan0Tracked),
        )

    @Test
    fun readyIpOnLohs_bindsLohsSourceIp_whenHostIpKnown() {
        val d = route("10.122.99.178", hs = LocalIfaceSubnet("wlan2", b("10.122.99.203"), 24, true), ifaces = listOf(wlan0Iface))
        assertEquals(TcpBindDecision.BindLocalSourceIp(b("10.122.99.203"), "wlan2"), d)
        assertNotEquals(TcpBindDecision.BindTrackedNetwork(wlan0Handle), d)
    }

    @Test
    fun readyIpOnLohs_bindsLohsSourceIp_fromLiveInterfaces_whenHostIpUnknown() {
        val odd = LocalIfaceSubnet("lohs0", b("10.122.99.203"), 24)
        assertEquals(TcpBindDecision.BindLocalSourceIp(b("10.122.99.203"), "wlan2"), route("10.122.99.178", null, listOf(wlan0Iface, lohsIface)))
        assertEquals(TcpBindDecision.BindLocalSourceIp(b("10.122.99.203"), "lohs0"), route("10.122.99.178", null, listOf(wlan0Iface, odd)))
    }

    @Test
    fun readyIpOnLohs_withNoLocalInterfaceKnown_fallsBackToWlan0_whichIsWhyWeAdoptFirst() {
        // Documents the pre-fix misroute: nothing local matched 10.122.99.x ⇒ private target ⇒ wlan0.
        assertEquals(TcpBindDecision.BindTrackedNetwork(wlan0Handle), route("10.122.99.178", null, listOf(wlan0Iface)))
    }

    @Test
    fun bogusStationReadyIp_staysOnWlan0() {
        assertEquals(TcpBindDecision.BindTrackedNetwork(wlan0Handle), route("100.74.206.81", null, listOf(wlan0Iface, lohsIface)))
    }

    @Test
    fun udpStaysUnboundWhileHostingEvenBeforeHostIpIsKnown() {
        val d = LocalSubnetRouter.decideUdp(
            hasPinnedNetwork = false,
            hostingLocalHotspot = true,
            hasActiveWifi = true,
            activeWifiIfaceName = "wlan0",
            trackedSubnets = listOf(wlan0Tracked),
            localIfaces = listOf(wlan0Iface),
        )
        assertEquals(UdpBindDecision.NO_EXPLICIT_BIND, d)
    }
}
