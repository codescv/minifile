package com.minifile.explorer.core.peer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.util.Collections

/** `lohs-s` recovery when the iPhone re-announces a restarted HTTP listener (pure JVM). */
class ReannounceRetryTest {

    private val peer = PeerInfo(
        id = "ios-1", name = "iPhone", host = "192.168.49.7", port = 8080, version = "",
        readOnly = false, source = PeerSource.ANNOUNCE, lastSeenMs = 0L, platform = "ios", linkToken = "tok",
    )

    private fun announced(port: Int, host: String = peer.host) = peer.copy(host = host, port = port, linkToken = null)

    private fun refused() = PeerTransferException(null, "iPhone not reachable", ConnectException("refused"), beforeSession = true)

    @Test
    fun noReannounceFlow_runsExactlyOnce() = runBlocking {
        var calls = 0
        try {
            ReannounceRetry.run<Unit>(peer, null, abortActive = {}, isCancelled = { false }) { _, _ ->
                calls++
                throw refused()
            }
            fail("expected failure")
        } catch (e: PeerTransferException) {
            assertEquals(1, calls)
        }
    }

    @Test
    fun refusedBegin_thenReannounceOnNewPort_retriesAgainstNewEndpoint_keepingToken() = runBlocking {
        val events = Channel<PeerInfo>(Channel.UNLIMITED)
        val targets = Collections.synchronizedList(ArrayList<PeerInfo>())
        var waited = 0
        val result = async {
            ReannounceRetry.run(
                peer,
                events.receiveAsFlow(),
                abortActive = {},
                isCancelled = { false },
                onWaiting = { waited++ },
            ) { target, started ->
                targets += target
                if (targets.size == 1) throw refused()
                started()
                "ok"
            }
        }
        delay(50)
        events.send(announced(8081))
        assertEquals("ok", withTimeout(5_000) { result.await() })
        assertEquals(listOf(8080, 8081), targets.map { it.port })
        assertEquals("tok", targets[1].linkToken)
        assertEquals("ios", targets[1].platform)
        assertEquals(1, waited)
    }

    @Test
    fun reannounceOnDifferentEndpoint_whileBeginBlocked_abortsAndRetries() = runBlocking {
        val events = MutableSharedFlow<PeerInfo>(extraBufferCapacity = 8)
        val blocked = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        var aborts = 0
        val targets = Collections.synchronizedList(ArrayList<PeerInfo>())
        val result = async {
            ReannounceRetry.run(
                peer,
                events,
                abortActive = {
                    aborts++
                    unblock.complete(Unit)
                },
                isCancelled = { false },
            ) { target, started ->
                targets += target
                if (targets.size == 1) {
                    blocked.complete(Unit)
                    unblock.await() // a `begin` stuck in a dead listener's backlog until the socket is closed
                    throw PeerTransferException(null, "iPhone not reachable", IOException("Socket closed"), beforeSession = true)
                }
                started()
                "sent"
            }
        }
        blocked.await()
        events.emit(announced(9000))
        assertEquals("sent", withTimeout(5_000) { result.await() })
        assertEquals(1, aborts)
        assertEquals(listOf(8080, 9000), targets.map { it.port })
    }

    @Test
    fun sameEndpointReannounce_abortsOnlyAfterGracePeriod() = runBlocking {
        val events = MutableSharedFlow<PeerInfo>(extraBufferCapacity = 8)
        var now = 1_000L
        var aborts = 0
        val blocked = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        var calls = 0
        val result = async {
            ReannounceRetry.run(
                peer,
                events,
                abortActive = {
                    aborts++
                    unblock.complete(Unit)
                },
                isCancelled = { false },
                nowMs = { now },
            ) { _, started ->
                calls++
                if (calls == 1) {
                    blocked.complete(Unit)
                    unblock.await()
                    throw PeerTransferException(null, "iPhone not reachable", IOException("Socket closed"), beforeSession = true)
                }
                started()
                "sent"
            }
        }
        blocked.await()
        events.emit(announced(8080)) // same endpoint, immediately: the begin may still be answered normally
        delay(50)
        assertEquals(0, aborts)
        now += ReannounceRetry.SAME_ENDPOINT_ABORT_AFTER_MS
        events.emit(announced(8080))
        assertEquals("sent", withTimeout(5_000) { result.await() })
        assertEquals(1, aborts)
        assertEquals(2, calls)
    }

    @Test
    fun failureAfterSessionStarted_isTerminal_neverDuplicatesFiles() = runBlocking {
        val events = Channel<PeerInfo>(Channel.UNLIMITED)
        events.send(announced(8081))
        var calls = 0
        val boom = PeerTransferException(null, "Connection to iPhone lost", IOException("reset"))
        try {
            ReannounceRetry.run<Unit>(peer, events.receiveAsFlow(), abortActive = {}, isCancelled = { false }) { _, started ->
                calls++
                started()
                throw boom
            }
            fail("expected failure")
        } catch (e: PeerTransferException) {
            assertSame(boom, e)
        }
        assertEquals(1, calls)
    }

    @Test
    fun httpRejection_isNotRetried_andNoReannounceTimesOut() = runBlocking {
        val events = Channel<PeerInfo>(Channel.UNLIMITED)
        var calls = 0
        try {
            ReannounceRetry.run<Unit>(peer, events.receiveAsFlow(), abortActive = {}, isCancelled = { false }) { _, _ ->
                calls++
                throw PeerTransferException(403, "Declined by iPhone", beforeSession = true)
            }
            fail("expected failure")
        } catch (e: PeerTransferException) {
            assertEquals(403, e.httpStatus)
        }
        assertEquals(1, calls)

        calls = 0
        val start = System.currentTimeMillis()
        try {
            ReannounceRetry.run<Unit>(peer, events.receiveAsFlow(), abortActive = {}, isCancelled = { false }, waitMs = 100) { _, _ ->
                calls++
                throw refused()
            }
            fail("expected failure")
        } catch (e: PeerTransferException) {
            assertEquals("iPhone not reachable", e.userMessage)
        }
        assertEquals(1, calls)
        assertTrue(System.currentTimeMillis() - start < 5_000)
    }

    @Test
    fun cancelledOrBudgetExhausted_stopsRetrying() = runBlocking {
        val events = Channel<PeerInfo>(Channel.UNLIMITED)
        var calls = 0
        try {
            ReannounceRetry.run<Unit>(
                peer,
                events.receiveAsFlow(),
                abortActive = {},
                isCancelled = { false },
                maxRetries = 2,
                waitMs = 2_000,
            ) { _, _ ->
                calls++
                events.trySend(announced(8100 + calls)) // the iPhone re-announces after every failure
                throw refused()
            }
            fail("expected failure")
        } catch (_: PeerTransferException) {
        }
        assertEquals(3, calls)

        calls = 0
        try {
            ReannounceRetry.run<Unit>(peer, events.receiveAsFlow(), abortActive = {}, isCancelled = { true }) { _, _ ->
                calls++
                events.trySend(announced(8200))
                throw refused()
            }
            fail("expected failure")
        } catch (_: PeerTransferException) {
        }
        assertEquals(1, calls)
    }

    @Test
    fun isRetryable_classification() {
        assertTrue(ReannounceRetry.isRetryable(refused(), sessionStarted = false))
        assertTrue(ReannounceRetry.isRetryable(IOException("reset"), sessionStarted = false))
        assertFalse(ReannounceRetry.isRetryable(refused(), sessionStarted = true))
        assertFalse(ReannounceRetry.isRetryable(PeerTransferException(410, "Rejected"), sessionStarted = false))
        assertFalse(ReannounceRetry.isRetryable(PeerTransferException(302, "Intercepted"), sessionStarted = false))
        assertFalse(ReannounceRetry.isRetryable(IllegalStateException("bug"), sessionStarted = false))
    }
}
