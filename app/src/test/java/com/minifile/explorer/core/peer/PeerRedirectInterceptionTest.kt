package com.minifile.explorer.core.peer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Regression for "iPhone returned HTTP 302": a VPN (`tun0`) / proxy / captive portal answering the LAN request with a
 * redirect must surface as an actionable interception error and be classified for the Bluetooth fallback.
 */
class PeerRedirectInterceptionTest {

    private fun peer(port: Int, name: String = "iPhone") = PeerInfo(
        id = "ios-peer-id-123",
        name = name,
        host = "127.0.0.1",
        port = port,
        version = "1.0.20",
        readOnly = false,
        source = PeerSource.BEACON,
        lastSeenMs = System.currentTimeMillis(),
    )

    /** Fake intercepting proxy: answers every request with [status] and a token-bearing `Location`. */
    private fun withRedirectServer(status: Int, body: String = "", block: (port: Int, hits: AtomicInteger) -> Unit) {
        val hits = AtomicInteger(0)
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { ss ->
            val t = thread(isDaemon = true) {
                while (!ss.isClosed) {
                    val s = runCatching { ss.accept() }.getOrNull() ?: break
                    s.use { sock ->
                        val input = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        hits.incrementAndGet()
                        val bytes = body.toByteArray()
                        val resp = "HTTP/1.1 $status Found\r\n" +
                            "Location: http://user:pw@10.0.0.1:8888/portal/login?token=SECRET123#frag\r\n" +
                            "Content-Type: text/html\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        sock.getOutputStream().write(resp.toByteArray(Charsets.ISO_8859_1) + bytes)
                        sock.getOutputStream().flush()
                    }
                }
            }
            try {
                block(ss.localPort, hits)
            } finally {
                runCatching { ss.close() }
                t.join(2_000)
            }
        }
    }

    private val oneFile = listOf(
        PeerSendItem(relPath = "a.txt", size = 3, mtime = 1L, open = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }),
    )

    @Test
    fun beginRedirect_allStatuses_areInterceptedWithActionableEnglish() {
        for (status in listOf(301, 302, 303, 307, 308)) {
            withRedirectServer(status) { port, _ ->
                val engine = PeerTransferEngine(client = PeerHttpClient(connectTimeoutMs = 2_000, soTimeoutMs = 2_000))
                try {
                    runBlocking { engine.send(peer(port), oneFile) }
                    fail("expected interception for $status")
                } catch (e: PeerTransferException) {
                    assertEquals(status, e.httpStatus)
                    assertTrue(e.isInterceptedRedirect)
                    assertEquals(PeerFailureKind.INTERCEPTED, e.kind)
                    assertTrue("must be pre-session", e.beforeSession)
                    assertFalse(e.userMessage, e.userMessage.contains("returned HTTP"))
                    assertTrue(e.userMessage, e.userMessage.contains("intercepted (HTTP $status)"))
                    assertTrue(e.userMessage, e.userMessage.contains("VPN or proxy"))
                    assertTrue(e.userMessage, e.userMessage.startsWith("Connection to iPhone"))
                    assertFalse("never leak tokens", e.userMessage.contains("SECRET123"))
                }
            }
        }
    }

    @Test
    fun mapHttpError_redirectVsOtherStatuses() {
        val p = peer(8080)
        for (status in PeerTransferException.REDIRECT_STATUSES) {
            val e = PeerTransferEngine.mapHttpError(p, PeerHttpResponse(status, "Found", mapOf("location" to "http://x/"), ByteArray(0)))
            assertTrue(e.isInterceptedRedirect)
            assertEquals(
                "Connection to iPhone was intercepted (HTTP $status) — turn off VPN or proxy (or enable LAN bypass) and try again",
                e.userMessage,
            )
        }
        val e500 = PeerTransferEngine.mapHttpError(p, PeerHttpResponse(500, "Err", emptyMap(), ByteArray(0)))
        assertFalse(e500.isInterceptedRedirect)
        assertEquals(PeerFailureKind.OTHER, e500.kind)
        val e304 = PeerTransferEngine.mapHttpError(p, PeerHttpResponse(304, "Not Modified", emptyMap(), ByteArray(0)))
        assertFalse(e304.isInterceptedRedirect)
    }

    @Test
    fun probeInfoRedirect_isIntercepted() {
        withRedirectServer(302, body = "<html>login</html>") { port, _ ->
            val engine = PeerDiscoveryEngine(
                selfId = "self",
                selfName = "Me",
                lanIpsProvider = { emptyList() },
                portProvider = { null },
                readOnlyProvider = { false },
            )
            try {
                runBlocking { engine.probe("127.0.0.1", port, timeoutMs = 2_000) }
                fail("expected interception")
            } catch (e: PeerTransferException) {
                assertTrue(e.isInterceptedRedirect)
                assertFalse(e.userMessage.contains("returned HTTP"))
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun probeInfo_nonFilesJson_isRejected() {
        val hits = AtomicInteger(0)
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { ss ->
            val t = thread(isDaemon = true) {
                val s = runCatching { ss.accept() }.getOrNull() ?: return@thread
                s.use { sock ->
                    val input = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    hits.incrementAndGet()
                    val body = "{\"status\":\"ok\",\"portal\":true}".toByteArray()
                    sock.getOutputStream().write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.ISO_8859_1) + body,
                    )
                }
            }
            val engine = PeerDiscoveryEngine("self", "Me", { emptyList() }, { null }, { false })
            try {
                runBlocking { engine.probe("127.0.0.1", ss.localPort, timeoutMs = 2_000) }
                fail("expected rejection")
            } catch (e: PeerTransferException) {
                assertTrue(e.userMessage, e.userMessage.contains("is not a Files peer"))
            } finally {
                engine.close()
                t.join(2_000)
            }
        }
    }

    @Test
    fun connectRefused_isUnreachableBeforeSession() {
        val freePort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val engine = PeerTransferEngine(client = PeerHttpClient(connectTimeoutMs = 1_000, soTimeoutMs = 1_000))
        try {
            runBlocking { engine.send(peer(freePort), oneFile) }
            fail("expected failure")
        } catch (e: PeerTransferException) {
            assertEquals(PeerFailureKind.UNREACHABLE, e.kind)
            assertTrue(e.beforeSession)
            assertFalse(e.isInterceptedRedirect)
            assertEquals("iPhone not reachable", e.userMessage)
        }
    }

    @Test
    fun sanitizeLocation_stripsUserinfoQueryAndFragment() {
        assertEquals(
            "http://10.0.0.1:8888/portal/login",
            PeerTransferException.sanitizeLocation("http://user:pw@10.0.0.1:8888/portal/login?token=SECRET123#frag"),
        )
        assertEquals("https://captive.example/", PeerTransferException.sanitizeLocation("https://captive.example/?a=b"))
        assertEquals("/login", PeerTransferException.sanitizeLocation("/login?x=1"))
        assertNull(PeerTransferException.sanitizeLocation(""))
        assertNull(PeerTransferException.sanitizeLocation(null))
    }

    @Test
    fun legacyConstructor_staysCompatible() {
        val e = PeerTransferException(302, "x")
        assertTrue(e.isInterceptedRedirect)
        assertFalse(e.beforeSession)
        val e2 = PeerTransferException(null, "y")
        assertEquals(PeerFailureKind.OTHER, e2.kind)
    }
}
