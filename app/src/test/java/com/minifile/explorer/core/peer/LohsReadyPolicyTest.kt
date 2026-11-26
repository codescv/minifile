package com.minifile.explorer.core.peer

import org.junit.Assert.assertEquals
import org.junit.Test

/** `lohs-s` BLE `ready` handling: every `ready` is evaluated until one probe succeeds (2026-11-26 device logs). */
class LohsReadyPolicyTest {

    @Test
    fun realLogSequence_hostIpUnknown_bothReadiesAreProbed() {
        // 09:54:24 bogus station IP while hostIp=? → probe (it may still be reachable on a shared LAN).
        val first = LohsReadyPolicy.decide("100.74.206.81", 8080, hostIp = "", prefixLength = 24, inFlightKey = null)
        assertEquals(ReadyAction.Probe("100.74.206.81", 8080), first)
        // 09:55:15 real LOHS IP while the first probe is (or was) running → probe it (supersedes the old one).
        val second = LohsReadyPolicy.decide("10.122.99.178", 8080, hostIp = "", prefixLength = 24, inFlightKey = "100.74.206.81:8080")
        assertEquals(ReadyAction.Probe("10.122.99.178", 8080), second)
        assertEquals("10.122.99.178:8080", (second as ReadyAction.Probe).key)
    }

    @Test
    fun hostIpKnown_offSubnetIgnored_onSubnetProbed() {
        assertEquals(
            ReadyAction.OffHotspotSubnet,
            LohsReadyPolicy.decide("100.74.206.81", 8080, hostIp = "10.122.99.203", prefixLength = 24, inFlightKey = null),
        )
        assertEquals(
            ReadyAction.Probe("10.122.99.178", 8080),
            LohsReadyPolicy.decide("10.122.99.178", 8080, hostIp = "10.122.99.203", prefixLength = 24, inFlightKey = null),
        )
    }

    @Test
    fun duplicateReadyWhileSameProbeRuns_isNotRestarted_butAfterItEndedItIs() {
        assertEquals(
            ReadyAction.AlreadyProbing,
            LohsReadyPolicy.decide("10.122.99.178", 8080, "", 24, inFlightKey = "10.122.99.178:8080"),
        )
        assertEquals(ReadyAction.Probe("10.122.99.178", 8080), LohsReadyPolicy.decide("10.122.99.178", 8080, "", 24, null))
        // Same IP, new port (iOS restarted its listener) → new probe.
        assertEquals(
            ReadyAction.Probe("10.122.99.178", 8081),
            LohsReadyPolicy.decide("10.122.99.178", 8081, "", 24, inFlightKey = "10.122.99.178:8080"),
        )
    }

    @Test
    fun badEndpoints() {
        for ((ip, port) in listOf(
            "10.122.99.178" to 0,
            "10.122.99.178" to 70000,
            "" to 8080,
            "not-an-ip" to 8080,
            "0.0.0.0" to 8080,
            "127.0.0.1" to 8080,
            "224.0.0.251" to 8080,
            "10.122.99.255" to 8080,
        )) {
            assertEquals("$ip:$port", ReadyAction.BadEndpoint, LohsReadyPolicy.decide(ip, port, "", 24, null))
        }
        // Our own hotspot address echoed back.
        assertEquals(ReadyAction.BadEndpoint, LohsReadyPolicy.decide("10.122.99.203", 8080, "10.122.99.203", 24, null))
    }

    @Test
    fun bogusPrefixFallsBackToSlash24() {
        assertEquals(
            ReadyAction.OffHotspotSubnet,
            LohsReadyPolicy.decide("10.122.98.7", 8080, hostIp = "10.122.99.203", prefixLength = 0, inFlightKey = null),
        )
        assertEquals(
            ReadyAction.Probe("10.122.99.7", 8080),
            LohsReadyPolicy.decide("10.122.99.7", 8080, hostIp = "10.122.99.203", prefixLength = 0, inFlightKey = null),
        )
    }
}
