package com.minifile.explorer.core.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A1: `soTimeout` never bounds `write()`. A receiver that stops reading (iPhone suspended while the user is still in
 * the Camera app ⇒ zero TCP window) must fail the send instead of hanging the "Sending to iPhone" screen forever.
 */
class PeerHttpClientWriteStallTest {

    @Test
    fun guard_firesOnlyForWritesBlockedLongerThanTimeout() {
        var now = 0L
        val fired = AtomicInteger(0)
        val guard = WriteStallGuard(timeoutMs = 1_000, onStall = { fired.incrementAndGet() }, nowNanos = { now })
        try {
            guard.check()
            assertEquals(0, fired.get())
            // Time between writes (reading the source file) never counts.
            now += 5_000_000_000L
            guard.check()
            assertEquals(0, fired.get())

            guard.enter()
            now += 999_000_000L
            guard.check()
            assertEquals(0, fired.get())
            guard.exit()

            guard.enter()
            now += 1_000_000_000L
            guard.check()
            guard.check()
            assertEquals(1, fired.get())
            assertTrue(guard.stalled)
        } finally {
            guard.close()
        }
    }

    @Test
    fun postStream_receiverStopsReading_failsWithWriteStallException() {
        val server = ServerSocket()
        server.receiveBufferSize = 4 * 1024
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        val accepted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val srvThread = thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    val input = BufferedInputStream(s.getInputStream())
                    readHead(input)
                    s.getOutputStream().apply {
                        write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                        flush()
                    }
                    accepted.countDown()
                    // Never read the body: the client's send buffer fills and write() blocks (zero window).
                    release.await(30, TimeUnit.SECONDS)
                }
            }
        }
        val client = PeerHttpClient(connectTimeoutMs = 3_000, soTimeoutMs = 3_000, writeStallTimeoutMs = 600L)
        val started = System.nanoTime()
        try {
            client.postStream(
                url = "http://127.0.0.1:${server.localPort}/api/peer/send?session=s&path=a.bin",
                contentLength = 512L * 1024 * 1024,
            ) { out ->
                val chunk = ByteArray(256 * 1024)
                repeat(2_048) { out.write(chunk) }
            }
            fail("expected the stalled write to fail")
        } catch (e: IOException) {
            assertTrue("expected WriteStallException, got $e", e is WriteStallException)
        } finally {
            release.countDown()
            client.close()
            server.close()
            srvThread.join(2_000)
        }
        assertTrue(accepted.count == 0L)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue("stall must be detected promptly, took $elapsedMs ms", elapsedMs < 10_000L)
    }

    @Test
    fun mapTransportError_stall_hasActionableMessage() {
        val peer = PeerInfo(
            id = "ios-1", name = "iPhone", host = "192.168.49.7", port = 8080, version = "",
            readOnly = false, source = PeerSource.ANNOUNCE, lastSeenMs = 0L,
        )
        val mapped = PeerTransferEngine.mapTransportError(peer, IOException("x", WriteStallException(30_000)), midTransfer = true)
        assertEquals("iPhone stopped receiving — keep Files open on iPhone", mapped.userMessage)
        assertFalse(mapped.beforeSession)
    }

    @Test
    fun linkedBeginTimeout_isShorterButStillCoversConsentPrompt() {
        val base = PeerInfo(
            id = "ios-1", name = "iPhone", host = "192.168.49.7", port = 8080, version = "",
            readOnly = false, source = PeerSource.ANNOUNCE, lastSeenMs = 0L,
        )
        assertEquals(PeerTransferEngine.BEGIN_TIMEOUT_MS, PeerTransferEngine.beginTimeoutMs(base))
        assertEquals(PeerTransferEngine.LINKED_BEGIN_TIMEOUT_MS, PeerTransferEngine.beginTimeoutMs(base.copy(linkToken = "abc")))
        assertTrue(PeerTransferEngine.LINKED_BEGIN_TIMEOUT_MS > 60_000)
    }

    private fun readHead(input: InputStream) {
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return
        }
    }
}
