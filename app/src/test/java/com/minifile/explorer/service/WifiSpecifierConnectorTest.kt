package com.minifile.explorer.service

import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Spec §3.3 [v2] leak-audit regression tests for [WifiSpecifierConnector]. */
class WifiSpecifierConnectorTest {

    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class FakeOps(
        override val sdkInt: Int,
        var linkSnapshot: WifiSpecifierConnector.LinkSnapshot =
            WifiSpecifierConnector.LinkSnapshot(ownIp = null, gateway = "192.168.49.1", prefixLength = 24),
        var legacyPollSnapshot: WifiSpecifierConnector.LinkSnapshot? = null,
    ) : WifiSpecifierConnector.PlatformOps {
        val pinnedHistory = CopyOnWriteArrayList<Boolean>() // true = non-null/pinned, false = null
        val pinnedCurrently = AtomicBoolean(false)
        val unregistered = CopyOnWriteArrayList<Any>()
        val releasedLegacyIds = CopyOnWriteArrayList<Int>()
        val readLinkCalls = AtomicInteger(0)
        val legacyPollCalls = AtomicInteger(0)
        val enteredLinkRead = CompletableDeferred<Unit>()
        val enteredLegacyPoll = CompletableDeferred<Unit>()
        var lastListener: WifiSpecifierConnector.SpecifierListener? = null
        var autoDeliverOnAvailable: Boolean = true

        override fun pinNetwork(network: Network?) {
            // In unit tests `network` may be null placeholder; track pin calls after onAvailable vs null on release.
            pinnedCurrently.set(network != null)
        }

        override fun requestSpecifier(
            ssid: String,
            pass: String,
            sec: String,
            timeoutMs: Int,
            listener: WifiSpecifierConnector.SpecifierListener,
        ): Any {
            lastListener = listener
            val token = "reg-$ssid-${unregistered.size + 1}-${System.nanoTime()}"
            if (autoDeliverOnAvailable) {
                listener.onAvailable(null)
            }
            return token
        }

        override fun unregisterSpecifier(registration: Any) {
            unregistered.add(registration)
        }

        override fun readLinkSnapshot(network: Network?): WifiSpecifierConnector.LinkSnapshot {
            readLinkCalls.incrementAndGet()
            enteredLinkRead.complete(Unit)
            pinnedHistory.add(true)
            return linkSnapshot
        }

        override fun addAndEnableLegacy(ssid: String, pass: String): Int = 42

        override fun pollLegacyConnected(netId: Int): WifiSpecifierConnector.LinkSnapshot? {
            legacyPollCalls.incrementAndGet()
            enteredLegacyPoll.complete(Unit)
            return legacyPollSnapshot
        }

        override fun releaseLegacy(netId: Int) {
            releasedLegacyIds.add(netId)
        }
    }

    @Test
    fun api34_cancelDuringDhcpLinkWait_unregistersSpecifierAndClearsState() = runBlocking {
        // linkSnapshot.ownIp == null forces joinWithSpecifier into the 5 s DHCP delay(200) loop after onAvailable.
        val ops = FakeOps(
            sdkInt = 34,
            linkSnapshot = WifiSpecifierConnector.LinkSnapshot(ownIp = null, gateway = "192.168.49.1", prefixLength = 24),
        )
        val connector = WifiSpecifierConnector(
            ops = ops,
            scope = scope,
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val job = async(Dispatchers.Default) {
            connector.join("AndroidShare_1234", "pass1234", "wpa2")
        }
        withTimeout(3_000) { ops.enteredLinkRead.await() }
        // Cancel while suspended inside the post-onAvailable DHCP wait loop.
        job.cancel(CancellationException("user cancelled mid-DHCP"))
        runCatching { job.await() }

        assertEquals("registered callback must be unregistered on mid-join cancel", 1, ops.unregistered.size)
        assertFalse(connector.isJoined)
        assertNull(connector.joined)
        assertFalse(ops.pinnedCurrently.get())
    }

    @Test
    fun api28_cancelDuringLegacyPoll_removesAddedWifiConfiguration() = runBlocking {
        val ops = FakeOps(sdkInt = 28, legacyPollSnapshot = null)
        val connector = WifiSpecifierConnector(
            ops = ops,
            scope = scope,
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Default,
        )

        val job = async(Dispatchers.Default) {
            connector.join("AndroidShare_Legacy", "pass1234", "wpa2", expectedHostIp = "192.168.43.1")
        }
        withTimeout(3_000) { ops.enteredLegacyPoll.await() }
        job.cancel(CancellationException("user cancelled legacy poll"))
        runCatching { job.await() }

        assertEquals(listOf(42), ops.releasedLegacyIds.toList())
        assertFalse(connector.isJoined)
        assertNull(connector.joined)
    }

    @Test
    fun release_cancelsPendingReleaseAfterTimer_beforeSubsequentJoin() = runBlocking {
        val ops = FakeOps(
            sdkInt = 34,
            linkSnapshot = WifiSpecifierConnector.LinkSnapshot(
                ownIp = "192.168.99.20",
                gateway = "192.168.99.1",
                prefixLength = 24,
            ),
        )
        val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val connector = WifiSpecifierConnector(
                ops = ops,
                scope = timerScope,
                mainDispatcher = Dispatchers.Unconfined,
                ioDispatcher = Dispatchers.Unconfined,
            )
            connector.join("FirstHotspot", "pass1234", "wpa2")
            assertTrue(connector.isJoined)

            // Schedule a short releaseAfter, then immediately start a second join (which calls release()).
            connector.releaseAfter(graceMs = 80L)
            connector.join("SecondHotspot", "pass5678", "wpa2")
            assertTrue(connector.isJoined)

            // Wait well past the first 80 ms timer: SecondHotspot must stay joined!
            delay(220L)
            assertTrue("stale releaseAfter timer must not tear down the new join", connector.isJoined)
            assertNotNull(connector.joined)

            connector.release()
            assertFalse(connector.isJoined)
        } finally {
            timerScope.cancel()
        }
    }

    @Test
    fun staleCallbackFromPreviousJoin_doesNotReleaseNewJoin() = runBlocking {
        val ops = FakeOps(
            sdkInt = 34,
            linkSnapshot = WifiSpecifierConnector.LinkSnapshot(
                ownIp = "192.168.77.12",
                gateway = "192.168.77.1",
                prefixLength = 24,
            ),
        )
        val connector = WifiSpecifierConnector(
            ops = ops,
            scope = scope,
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
        )
        connector.join("HotspotA", "pass1234", "wpa2")
        val staleListener = ops.lastListener!!

        connector.join("HotspotB", "pass5678", "wpa2")
        assertTrue(connector.isJoined)

        // Firing onLost / onUnavailable on the old registration must be ignored.
        staleListener.onLost(null)
        staleListener.onUnavailable()
        assertTrue(connector.isJoined)

        connector.release()
        assertFalse(connector.isJoined)
    }

    @Test
    fun securityAttemptOrder_fallsBackToTheOtherPassphraseType() {
        assertEquals(listOf("wpa2", "wpa3"), WifiSpecifierConnector.securityAttemptOrder("wpa2", "pw123456"))
        assertEquals(listOf("wpa2", "wpa3"), WifiSpecifierConnector.securityAttemptOrder("wpa3t", "pw123456"))
        assertEquals(listOf("wpa3", "wpa2"), WifiSpecifierConnector.securityAttemptOrder("wpa3", "pw123456"))
        assertEquals(listOf("wpa3", "wpa2"), WifiSpecifierConnector.securityAttemptOrder("SAE", "pw123456"))
        assertEquals(listOf("wpa2", "wpa3"), WifiSpecifierConnector.securityAttemptOrder("", "pw123456"))
        assertEquals(listOf("open"), WifiSpecifierConnector.securityAttemptOrder("open", "pw"))
        assertEquals(listOf("open"), WifiSpecifierConnector.securityAttemptOrder("nopass", ""))
        assertEquals(listOf("open"), WifiSpecifierConnector.securityAttemptOrder("wpa2", ""))
    }

    /** Fails (onUnavailable) every specifier whose security is in [failing]; records every request. */
    private class RetryOps(private val failing: Set<String>) : WifiSpecifierConnector.PlatformOps {
        override val sdkInt: Int = 34
        val requests = CopyOnWriteArrayList<Pair<String, Int>>()
        val unregistered = CopyOnWriteArrayList<Any>()

        override fun pinNetwork(network: Network?) = Unit

        override fun requestSpecifier(
            ssid: String,
            pass: String,
            sec: String,
            timeoutMs: Int,
            listener: WifiSpecifierConnector.SpecifierListener,
        ): Any {
            requests.add(sec to timeoutMs)
            val token = "reg-${requests.size}"
            if (sec in failing) listener.onUnavailable() else listener.onAvailable(null)
            return token
        }

        override fun unregisterSpecifier(registration: Any) {
            unregistered.add(registration)
        }

        override fun readLinkSnapshot(network: Network?) =
            WifiSpecifierConnector.LinkSnapshot(ownIp = "192.168.43.20", gateway = "192.168.43.1", prefixLength = 24)

        override fun addAndEnableLegacy(ssid: String, pass: String): Int = -1
        override fun pollLegacyConnected(netId: Int): WifiSpecifierConnector.LinkSnapshot? = null
        override fun releaseLegacy(netId: Int) = Unit
    }

    private fun connectorFor(ops: WifiSpecifierConnector.PlatformOps) = WifiSpecifierConnector(
        ops = ops,
        scope = scope,
        mainDispatcher = Dispatchers.Unconfined,
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun wpa2JoinFails_retriesOnceWithWpa3_andSucceeds() = runBlocking {
        val ops = RetryOps(failing = setOf("wpa2"))
        val connector = connectorFor(ops)
        val joined = connector.join("AndroidShare_9", "pw123456", "wpa3t")
        assertEquals("192.168.43.1", joined.gateway)
        assertEquals(listOf("wpa2", "wpa3"), ops.requests.map { it.first })
        assertEquals(WifiSpecifierConnector.FIRST_ATTEMPT_TIMEOUT_MS.toInt(), ops.requests[0].second)
        assertEquals(WifiSpecifierConnector.JOIN_TIMEOUT_MS.toInt(), ops.requests[1].second)
        assertEquals("failed first registration must be unregistered", listOf<Any>("reg-1"), ops.unregistered.toList())
        assertTrue(connector.isJoined)
        connector.release()
        assertFalse(connector.isJoined)
    }

    @Test
    fun wpa3JoinFails_retriesWithWpa2() = runBlocking {
        val ops = RetryOps(failing = setOf("wpa3"))
        val connector = connectorFor(ops)
        connector.join("AndroidShare_9", "pw123456", "wpa3")
        assertEquals(listOf("wpa3", "wpa2"), ops.requests.map { it.first })
        assertTrue(connector.isJoined)
    }

    @Test
    fun bothAttemptsFail_throwsJoinException_andLeavesNothingRegistered() = runBlocking {
        val ops = RetryOps(failing = setOf("wpa2", "wpa3"))
        val connector = connectorFor(ops)
        val err = runCatching { connector.join("AndroidShare_9", "pw123456", "wpa2") }.exceptionOrNull()
        assertTrue(err is JoinException)
        assertEquals(listOf("wpa2", "wpa3"), ops.requests.map { it.first })
        assertEquals(setOf<Any>("reg-1", "reg-2"), ops.unregistered.toSet())
        assertFalse(connector.isJoined)
    }

    @Test
    fun openNetwork_singleAttempt() = runBlocking {
        val ops = RetryOps(failing = setOf("open"))
        val connector = connectorFor(ops)
        assertTrue(runCatching { connector.join("Open", "", "open") }.exceptionOrNull() is JoinException)
        assertEquals(listOf("open"), ops.requests.map { it.first })
    }
}
