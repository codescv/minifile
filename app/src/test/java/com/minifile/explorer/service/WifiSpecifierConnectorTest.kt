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
}
