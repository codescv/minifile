package com.minifile.explorer.core.peer

import org.junit.Assert.assertEquals
import org.junit.Test

/** Stale cross-subnet LAN peers are pruned right after a Wi-Fi ↔ hotspot switch. */
class PeerDiscoveryPruneTest {

    private fun ip(s: String) = checkNotNull(HostDecision.parseIpv4(s))

    private fun peer(id: String, host: String, source: PeerSource, port: Int = 8080) = PeerInfo(
        id = id, name = id, host = host, port = port, version = "1", readOnly = false, source = source, lastSeenMs = 0L,
    )

    @Test
    fun pruneOffSubnet_dropsOldWifiPeers_keepsHotspotManualBleAndLoopback() {
        val engine = PeerDiscoveryEngine("self", "Me", { emptyList() }, { null }, { false })
        try {
            engine.upsert(peer("iphone-old-wifi", "192.168.1.40", PeerSource.BEACON))
            engine.upsert(peer("iphone-hotspot", "192.168.43.50", PeerSource.BEACON))
            engine.upsert(peer("swept", "192.168.1.77", PeerSource.PROBE))
            engine.upsert(peer("announced", "10.9.9.9", PeerSource.ANNOUNCE))
            engine.upsert(peer("typed-vlan", "10.1.9.20", PeerSource.MANUAL))
            engine.upsert(peer("qr", "10.2.2.2", PeerSource.QR))
            engine.upsert(peer("ble", "", PeerSource.BLE, port = 0))
            engine.upsert(peer("adb", "127.0.0.1", PeerSource.BEACON))

            val removed = engine.pruneOffSubnet(listOf(ip("192.168.43.1") to 24))
            assertEquals(3, removed)
            assertEquals(
                setOf("iphone-hotspot", "typed-vlan", "qr", "ble", "adb"),
                engine.peers.value.map { it.id }.toSet(),
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun pruneOffSubnet_emptySubnets_isNoOp() {
        val engine = PeerDiscoveryEngine("self", "Me", { emptyList() }, { null }, { false })
        try {
            engine.upsert(peer("a", "192.168.1.40", PeerSource.BEACON))
            assertEquals(0, engine.pruneOffSubnet(emptyList()))
            assertEquals(1, engine.peers.value.size)
        } finally {
            engine.close()
        }
    }
}
