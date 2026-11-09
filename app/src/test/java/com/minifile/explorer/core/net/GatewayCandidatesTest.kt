package com.minifile.explorer.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayCandidatesTest {

    @Test
    fun iphonePersonalHotspot_slash28() {
        assertEquals(listOf("172.20.10.1"), GatewayCandidates.forWifi("172.20.10.2", 28, "172.20.10.1"))
        assertEquals(listOf("172.20.10.1"), GatewayCandidates.forWifi("172.20.10.2", 28, null))
    }

    @Test
    fun iphonePersonalHotspot_secondDhcpBlock_includesBlockGatewayAndDotOne() {
        val c = GatewayCandidates.forWifi("172.20.10.18", 28, null)
        assertEquals(listOf("172.20.10.17", "172.20.10.1"), c)
        val withRoute = GatewayCandidates.forWifi("172.20.10.18", 28, "172.20.10.17")
        assertEquals(listOf("172.20.10.17", "172.20.10.1"), withRoute)
    }

    @Test
    fun androidHotspot_slash24() {
        assertEquals(listOf("192.168.43.1"), GatewayCandidates.forWifi("192.168.43.105", 24, "192.168.43.1"))
        assertEquals(listOf("192.168.191.1"), GatewayCandidates.forWifi("192.168.191.33", 24, null))
    }

    @Test
    fun excludesOwnIpLoopbackAndBroadcast() {
        // Own address is the would-be gateway (.1): excluded.
        assertTrue(GatewayCandidates.forWifi("192.168.43.1", 24, null).isEmpty())
        assertTrue(GatewayCandidates.forWifi("192.168.43.9", 24, "127.0.0.1") == listOf("192.168.43.1"))
        assertTrue(GatewayCandidates.forWifi("192.168.43.9", 24, "0.0.0.0") == listOf("192.168.43.1"))
        assertFalse(GatewayCandidates.forWifi("10.0.0.5", 24, "10.0.0.255").contains("10.0.0.255"))
        assertTrue(GatewayCandidates.forWifi(null, 24, null).isEmpty())
        assertEquals(listOf("192.168.1.254", "192.168.1.1"), GatewayCandidates.forWifi("192.168.1.20", 24, "192.168.1.254"))
    }

    @Test
    fun bogusPrefixFallsBackToSlash24() {
        assertEquals(listOf("192.168.43.1"), GatewayCandidates.forWifi("192.168.43.105", 0, null))
        assertEquals(listOf("192.168.43.1"), GatewayCandidates.forWifi("192.168.43.105", 33, null))
    }

    @Test
    fun ports_extraOnPhoneHotspots() {
        assertEquals(listOf(8080, 8081, 8082), GatewayCandidates.probePorts("192.168.1.20", metered = false))
        val hs = GatewayCandidates.probePorts("172.20.10.2", metered = false)
        assertEquals((8080..8090).toList(), hs)
        assertEquals((8080..8090).toList(), GatewayCandidates.probePorts("10.0.0.5", metered = true))
        assertEquals(listOf(9000, 8080, 8081, 8082), GatewayCandidates.probePorts("192.168.1.20", false, preferredPort = 9000))
        assertEquals(listOf(8080, 8081, 8082), GatewayCandidates.probePorts("192.168.1.20", false, preferredPort = 8080))
    }

    @Test
    fun phoneHotspotHeuristic() {
        assertTrue(GatewayCandidates.looksLikePhoneHotspot("172.20.10.3", false))
        assertTrue(GatewayCandidates.looksLikePhoneHotspot("192.168.43.20", false))
        assertTrue(GatewayCandidates.looksLikePhoneHotspot("192.168.49.20", false))
        assertFalse(GatewayCandidates.looksLikePhoneHotspot("192.168.1.20", false))
        assertTrue(GatewayCandidates.looksLikePhoneHotspot("192.168.1.20", true))
        assertFalse(GatewayCandidates.looksLikePhoneHotspot(null, false))
    }
}
