package com.minifile.explorer.core.peer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.UUID

class GattOpCorrelatorTest {
    private val ctrl = UUID.fromString(HotspotLinkUuids.CTRL)
    private val info = UUID.fromString(HotspotLinkUuids.INFO)
    private val cccd = UUID.fromString(HotspotLinkUuids.CCCD)

    @Test
    fun unsolicitedMtuChange_duringDiscover_neverCompletesIt() {
        val c = GattOpCorrelator()
        val d = c.begin(PendingGattOp.Discover)
        // iOS-initiated ATT MTU exchange lands while discoverServices() is pending (v1.0.19 root cause).
        assertFalse(c.onEvent(GattEvent.MtuChanged(517, 0)))
        assertFalse(d.isCompleted)
        assertEquals(517, c.mtu)
        assertTrue(c.onEvent(GattEvent.ServicesDiscovered(0)))
        assertEquals(0, runBlocking { d.await() })
    }

    @Test
    fun unsolicitedMtuChange_duringWrites_neverCompletesThem() {
        val c = GattOpCorrelator()
        val w = c.begin(PendingGattOp.WriteCharacteristic(ctrl))
        assertFalse(c.onEvent(GattEvent.MtuChanged(185, 0)))
        assertFalse(c.onEvent(GattEvent.CharacteristicWritten(info, 0))) // other characteristic
        assertFalse(w.isCompleted)
        assertTrue(c.onEvent(GattEvent.CharacteristicWritten(ctrl, 0)))
        assertEquals(0, runBlocking { w.await() })
        assertEquals(185, c.mtu)

        val dsc = c.begin(PendingGattOp.WriteDescriptor(cccd))
        assertFalse(c.onEvent(GattEvent.MtuChanged(247, 0)))
        assertFalse(c.onEvent(GattEvent.ServicesDiscovered(0)))
        assertFalse(dsc.isCompleted)
        assertTrue(c.onEvent(GattEvent.DescriptorWritten(cccd, 0)))
    }

    @Test
    fun solicitedMtu_completesWithNewMtu_andFailedStatusKeepsOldMtu() {
        val c = GattOpCorrelator()
        val m = c.begin(PendingGattOp.Mtu)
        assertTrue(c.onEvent(GattEvent.MtuChanged(247, 0)))
        assertEquals(247, runBlocking { m.await() })
        c.onEvent(GattEvent.MtuChanged(517, 133))
        assertEquals(247, c.mtu)
    }

    @Test
    fun read_completesOnlyForMatchingUuid_andNullOnError() {
        val c = GattOpCorrelator()
        val r = c.begin(PendingGattOp.Read(info))
        assertFalse(c.onEvent(GattEvent.CharacteristicRead(ctrl, 0, byteArrayOf(1))))
        assertTrue(c.onEvent(GattEvent.CharacteristicRead(info, 0, byteArrayOf(7))))
        assertEquals(7, (runBlocking { r.await() } as ByteArray)[0].toInt())
        val r2 = c.begin(PendingGattOp.Read(info))
        c.onEvent(GattEvent.CharacteristicRead(info, 5, byteArrayOf(9)))
        assertNull(runBlocking { r2.await() })
    }

    @Test
    fun lateCallback_afterTimeoutEnd_doesNotLeakIntoNextOp() {
        val c = GattOpCorrelator()
        c.begin(PendingGattOp.Discover)
        c.end(PendingGattOp.Discover) // timed out
        val w = c.begin(PendingGattOp.WriteCharacteristic(ctrl))
        assertFalse(c.onEvent(GattEvent.ServicesDiscovered(0))) // the late discovery result
        assertFalse(w.isCompleted)
    }

    @Test
    fun disconnect_failsPendingOp() {
        val c = GattOpCorrelator()
        val d = c.begin(PendingGattOp.Discover)
        assertTrue(c.onEvent(GattEvent.Disconnected))
        assertTrue(d.isCompleted)
        assertTrue(runCatching { runBlocking { d.await() } }.exceptionOrNull() is IOException)
        val e = c.begin(PendingGattOp.Mtu)
        c.failPending(IOException("closed"))
        assertTrue(e.isCompleted)
    }

    @Test
    fun serviceChanged_isFlaggedAndConsumedOnce() {
        val c = GattOpCorrelator()
        val d = c.begin(PendingGattOp.Discover)
        assertFalse(c.onEvent(GattEvent.ServiceChanged))
        assertFalse(d.isCompleted)
        assertTrue(c.serviceChanged)
        assertTrue(c.consumeServiceChanged())
        assertFalse(c.consumeServiceChanged())
    }

    // --- discovery retry policy ---

    private class FakeGatt(
        val results: List<Pair<Int?, Boolean>>,
        val serviceChangedOn: Set<Int> = emptySet(),
    ) {
        var attempts = 0
        var refreshes = 0
        var hasService = false
        var changed = false

        suspend fun run(): Boolean = GattDiscoveryPolicy.discoverWithRetry(
            discover = {
                val (status, svc) = results[attempts]
                attempts++
                hasService = svc
                if (attempts in serviceChangedOn) changed = true
                status
            },
            hasService = { hasService },
            consumeServiceChanged = { changed.also { changed = false } },
            refresh = { refreshes++ },
            delayMs = 1,
        )
    }

    @Test
    fun discovery_staleCacheWithoutService_refreshesAndRetries() = runBlocking {
        val g = FakeGatt(listOf(0 to false, 0 to true))
        assertTrue(g.run())
        assertEquals(2, g.attempts)
        assertEquals(1, g.refreshes)
    }

    @Test
    fun discovery_serviceChangedDuringDiscovery_retriesEvenIfServiceSeen() = runBlocking {
        val g = FakeGatt(listOf(0 to true, 0 to true), serviceChangedOn = setOf(1))
        assertTrue(g.run())
        assertEquals(2, g.attempts)
    }

    @Test
    fun discovery_givesUpAfterThreeAttempts() = runBlocking {
        val g = FakeGatt(listOf(0 to false, null to false, 133 to false, 0 to true))
        assertFalse(g.run())
        assertEquals(GattDiscoveryPolicy.MAX_ATTEMPTS, g.attempts)
        assertEquals(2, g.refreshes)
    }

    @Test
    fun discovery_firstTrySuccess_noRefresh() = runBlocking {
        val g = FakeGatt(listOf(0 to true))
        assertTrue(g.run())
        assertEquals(1, g.attempts)
        assertEquals(0, g.refreshes)
    }

    @Test
    fun shouldRetry_table() {
        assertFalse(GattDiscoveryPolicy.shouldRetry(1, 0, hasService = true, serviceChanged = false))
        assertTrue(GattDiscoveryPolicy.shouldRetry(1, 517, hasService = true, serviceChanged = false))
        assertTrue(GattDiscoveryPolicy.shouldRetry(2, 0, hasService = false, serviceChanged = false))
        assertFalse(GattDiscoveryPolicy.shouldRetry(3, 0, hasService = false, serviceChanged = true))
    }
}
