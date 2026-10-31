package com.minifile.explorer.core.peer

import com.minifile.explorer.core.http.HttpConfig
import com.minifile.explorer.core.http.HttpFileServerEngine
import com.minifile.explorer.core.model.Clock
import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class PeerDiscoveryEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class MutableClock(var currentMs: Long = 100_000L) : Clock {
        override fun nowMs(): Long = currentMs
    }

    @Test
    fun packetCodec_roundTripsValidBeaconsAndDropsOversizedOffLanSelfAndSpoofedHost() {
        val lanAddr = InetAddress.getByName("192.168.1.42")
        val publicAddr = InetAddress.getByName("8.8.8.8")

        val beaconBytes = PeerDiscoveryEngine.encodeBeaconPacket(
            id = "peer-uuid-1",
            name = "Pixel\u0007 8 Pro",
            port = 8080,
            version = "1.0.6",
            readOnly = true,
        )
        assertTrue(beaconBytes.size <= PeerDiscoveryEngine.MAX_DATAGRAM_BYTES)

        val decoded = PeerDiscoveryEngine.decodePacket(
            bytes = beaconBytes,
            srcAddress = lanAddr,
            selfId = "my-self-id",
            nowMs = 5_000L,
        ) as? PeerDiscoveryEngine.DecodedPacket.Beacon
        assertNotNull(decoded)
        assertEquals("peer-uuid-1", decoded!!.peer.id)
        assertEquals("Pixel 8 Pro", decoded.peer.name)
        assertEquals("192.168.1.42", decoded.peer.host)
        assertEquals(8080, decoded.peer.port)
        assertTrue(decoded.peer.readOnly)
        assertEquals(PeerSource.BEACON, decoded.peer.source)

        // Own selfId must be dropped
        assertNull(
            PeerDiscoveryEngine.decodePacket(
                bytes = beaconBytes,
                srcAddress = lanAddr,
                selfId = "peer-uuid-1",
                nowMs = 5_000L,
            ),
        )

        // Public non-LAN source IP must be dropped (MUST-3)
        assertNull(
            PeerDiscoveryEngine.decodePacket(
                bytes = beaconBytes,
                srcAddress = publicAddr,
                selfId = "my-self-id",
                nowMs = 5_000L,
            ),
        )

        // Spoofed "host" field in JSON payload must be ignored in favor of UDP source IP (MUST-3)
        val spoofedPayload = (
            "MFP1{\"t\":\"beacon\",\"id\":\"peer-2\",\"name\":\"Galaxy\"," +
                "\"host\":\"10.99.99.99\",\"port\":8085,\"ver\":\"1.0.6\",\"ro\":false,\"proto\":1}"
            ).toByteArray(Charsets.UTF_8)
        val spoofDecoded = PeerDiscoveryEngine.decodePacket(
            bytes = spoofedPayload,
            srcAddress = lanAddr,
            selfId = "my-self-id",
            nowMs = 6_000L,
        ) as? PeerDiscoveryEngine.DecodedPacket.Beacon
        assertNotNull(spoofDecoded)
        assertEquals("192.168.1.42", spoofDecoded!!.peer.host)
        assertEquals(8085, spoofDecoded.peer.port)

        // > 512 B datagram must be rejected before parsing
        val oversized = ByteArray(513) { 'A'.code.toByte() }.also {
            "MFP1".toByteArray().copyInto(it, 0)
        }
        assertNull(
            PeerDiscoveryEngine.decodePacket(
                bytes = oversized,
                srcAddress = lanAddr,
                selfId = "my-self-id",
                nowMs = 6_000L,
            ),
        )

        // Bad magic & unsupported proto > 1 must be dropped
        val badMagic = "MFP9{\"t\":\"query\",\"id\":\"peer-3\"}".toByteArray(Charsets.UTF_8)
        assertNull(PeerDiscoveryEngine.decodePacket(badMagic, srcAddress = lanAddr, selfId = "self", nowMs = 1L))

        val futureProto = "MFP1{\"t\":\"beacon\",\"id\":\"peer-3\",\"name\":\"Future\",\"port\":8080,\"proto\":2}"
            .toByteArray(Charsets.UTF_8)
        assertNull(PeerDiscoveryEngine.decodePacket(futureProto, srcAddress = lanAddr, selfId = "self", nowMs = 1L))
    }

    @Test
    fun datagramSocketBinder_calledBeforeBind_supportsPortFallbackAndRebind() {
        val bindCalls = AtomicInteger(0)
        val wasUnboundAtCallback = ArrayList<Boolean>()
        val fallbackPorts = ArrayList<Int>()

        // Occupy a specific UDP port so we can test busy-port fallback to ephemeral port 0
        DatagramSocket(InetSocketAddress("0.0.0.0", 0)).use { blocker ->
            val occupiedPort = blocker.localPort
            PeerDiscoveryEngine(
                selfIdProvider = { "node-a" },
                selfNameProvider = { "Node A" },
                lanIpsProvider = { listOf("127.0.0.1") },
                portProvider = { 8080 },
                readOnlyProvider = { false },
                discoveryPort = occupiedPort,
                datagramSocketBinder = { ds ->
                    bindCalls.incrementAndGet()
                    synchronized(wasUnboundAtCallback) {
                        wasUnboundAtCallback.add(!ds.isBound)
                    }
                },
                onPortFallback = { bound -> fallbackPorts.add(bound) },
            ).use { engine ->
                engine.setReceiverRole(true)
                assertTrue(engine.boundDiscoveryPort > 0)
                assertTrue(engine.boundDiscoveryPort != occupiedPort)
                assertEquals(1, fallbackPorts.size)
                assertTrue(wasUnboundAtCallback.all { it })

                val firstPort = engine.boundDiscoveryPort
                val bindsBeforeRebind = bindCalls.get()
                engine.rebindSocket()
                assertTrue(bindCalls.get() > bindsBeforeRebind)
                assertTrue(engine.boundDiscoveryPort > 0)
                assertTrue(wasUnboundAtCallback.all { it })
                // Ensure socket remained operational after rebind
                assertTrue(firstPort > 0)
            }
        }
    }

    @Test
    fun liveLoopbackUdpQueryAndUnicastReply_discoversReceiverAndAppliesTtlExpiry() = runBlocking {
        val clock = MutableClock(1_000_000L)

        PeerDiscoveryEngine(
            selfIdProvider = { "receiver-id" },
            selfNameProvider = { "Receiver Phone" },
            lanIpsProvider = { listOf("127.0.0.1") },
            portProvider = { 18080 },
            readOnlyProvider = { false },
            discoveryPort = 0,
            clock = clock,
        ).use { receiver ->
            receiver.setReceiverRole(true)
            val receiverUdpPort = receiver.boundDiscoveryPort
            assertTrue(receiverUdpPort > 0)

            PeerDiscoveryEngine(
                selfIdProvider = { "browser-id" },
                selfNameProvider = { "Sender Phone" },
                lanIpsProvider = { listOf("127.0.0.1") },
                portProvider = { null },
                readOnlyProvider = { false },
                discoveryPort = 0,
                targetDiscoveryPort = receiverUdpPort,
                extraBroadcastTargets = listOf(InetSocketAddress("127.0.0.1", receiverUdpPort)),
                clock = clock,
            ).use { browser ->
                browser.setBrowserRole(true)

                var discovered: PeerInfo? = null
                repeat(30) {
                    discovered = browser.peers.value.firstOrNull { it.id == "receiver-id" }
                    if (discovered != null) return@repeat
                    delay(50L)
                }
                assertNotNull("Expected browser to discover receiver over UDP loopback", discovered)
                assertEquals("Receiver Phone", discovered!!.name)
                assertEquals("127.0.0.1", discovered!!.host)
                assertEquals(18080, discovered!!.port)
                assertFalse(discovered!!.readOnly)
                assertEquals(PeerSource.BEACON, discovered!!.source)

                // Stop receiver so no further beacons arrive, advance clock > 8s BEACON_TTL_MS
                receiver.setReceiverRole(false)
                clock.currentMs += 8_500L
                browser.evictExpired(clock.nowMs())
                assertTrue(browser.peers.value.isEmpty())
            }
        }
    }

    @Test
    fun probe_validatesPeerInfoAndMaps404_401_FutureProto_AndOffLanErrors() = runBlocking {
        val clock = MutableClock(2_000_000L)
        val rootDir = tempFolder.newFolder("peer_probe_root")
        val httpServer = HttpFileServerEngine(
            config = HttpConfig(
                port = 0,
                bindAddress = "127.0.0.1",
                root = VfsPath.Local(rootDir.canonicalPath),
                readOnly = true,
                deviceId = "pixel-9-id",
                deviceName = "Pixel 9",
                appVersion = "1.0.6",
            ),
            fs = LocalFileSystem(),
        )
        httpServer.start()
        try {
            PeerDiscoveryEngine(
                selfIdProvider = { "sender-id" },
                selfNameProvider = { "Sender" },
                lanIpsProvider = { listOf("127.0.0.1") },
                portProvider = { null },
                readOnlyProvider = { false },
                discoveryPort = 0,
                clock = clock,
            ).use { engine ->
                // 1. Successful probe against live HttpFileServerEngine
                val peer = engine.probeAddress("127.0.0.1:${httpServer.boundPort}")
                assertEquals("pixel-9-id", peer.id)
                assertEquals("Pixel 9", peer.name)
                assertTrue(peer.readOnly)
                assertEquals(PeerSource.MANUAL, peer.source)
                assertEquals(1, engine.peers.value.size)

                // Manual peer survives 8.5s (> beacon TTL) and expires after >30s
                clock.currentMs += 8_500L
                engine.evictExpired(clock.nowMs())
                assertEquals(1, engine.peers.value.size)
                clock.currentMs += 25_000L
                engine.evictExpired(clock.nowMs())
                assertTrue(engine.peers.value.isEmpty())

                // 2. Public non-LAN address rejected immediately
                try {
                    engine.probeAddress("8.8.8.8:8080")
                    fail("Expected off-LAN address to be rejected")
                } catch (e: PeerTransferException) {
                    assertTrue(e.userMessage.contains("LAN", ignoreCase = true))
                }

                // 3. Mock 404 (older Files without /api/peer/info), 401, and proto=2 responses
                fun withMockHttpResponse(statusLine: String, body: String, block: suspend (Int) -> Unit) {
                    ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { ss ->
                        val t = Thread {
                            runCatching {
                                ss.accept().use { s ->
                                    val ins = s.getInputStream()
                                    val buf = ByteArray(1024)
                                    ins.read(buf)
                                    val bytes = body.toByteArray(Charsets.UTF_8)
                                    val hdr = "$statusLine\r\n" +
                                        "Content-Type: application/json\r\n" +
                                        "Content-Length: ${bytes.size}\r\n" +
                                        "Connection: close\r\n\r\n"
                                    s.getOutputStream().write(hdr.toByteArray(Charsets.ISO_8859_1))
                                    s.getOutputStream().write(bytes)
                                    s.getOutputStream().flush()
                                }
                            }
                        }.apply { isDaemon = true; start() }
                        runBlocking { block(ss.localPort) }
                        t.join(2_000L)
                    }
                }

                withMockHttpResponse("HTTP/1.1 404 Not Found", "{\"error\":\"Not found\"}") { port ->
                    try {
                        engine.probe("127.0.0.1", port)
                        fail("Expected 404 PeerTransferException")
                    } catch (e: PeerTransferException) {
                        assertEquals(404, e.httpStatus)
                        assertTrue(e.userMessage.contains("needs Files 1.0.6+"))
                    }
                }

                withMockHttpResponse("HTTP/1.1 401 Unauthorized", "{\"error\":\"PIN required\"}") { port ->
                    try {
                        engine.probe("127.0.0.1", port)
                        fail("Expected 401 PeerTransferException")
                    } catch (e: PeerTransferException) {
                        assertEquals(401, e.httpStatus)
                        assertTrue(e.userMessage.contains("requires a PIN"))
                    }
                }

                withMockHttpResponse(
                    "HTTP/1.1 200 OK",
                    "{\"app\":\"Files\",\"proto\":2,\"id\":\"p2\",\"name\":\"Pixel 10\",\"ver\":\"2.0.0\",\"readOnly\":false}",
                ) { port ->
                    try {
                        engine.probe("127.0.0.1", port)
                        fail("Expected future proto PeerTransferException")
                    } catch (e: PeerTransferException) {
                        assertTrue(e.userMessage.contains("needs newer Files"))
                    }
                }
            }
        } finally {
            httpServer.stop()
        }
    }
}
