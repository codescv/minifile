package com.minifile.explorer.core.peer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.UUID

/**
 * The single GATT operation a central is currently waiting on. Each op is completed ONLY by its own callback
 * kind (and characteristic / descriptor UUID), so unsolicited callbacks can never satisfy another op.
 */
sealed class PendingGattOp {
    data object Mtu : PendingGattOp()
    data object Discover : PendingGattOp()
    data class Read(val uuid: UUID) : PendingGattOp()
    data class WriteDescriptor(val uuid: UUID) : PendingGattOp()
    data class WriteCharacteristic(val uuid: UUID) : PendingGattOp()
}

/** Platform-neutral mirror of the `BluetoothGattCallback` events the central cares about. */
sealed class GattEvent {
    data class MtuChanged(val mtu: Int, val status: Int) : GattEvent()
    data class ServicesDiscovered(val status: Int) : GattEvent()
    data object ServiceChanged : GattEvent()
    class CharacteristicRead(val uuid: UUID, val status: Int, val value: ByteArray?) : GattEvent()
    data class DescriptorWritten(val uuid: UUID, val status: Int) : GattEvent()
    data class CharacteristicWritten(val uuid: UUID, val status: Int) : GattEvent()
    data object Disconnected : GattEvent()
}

/**
 * Pure-JVM, type-safe correlation of GATT callbacks to the pending op (`zero android.* imports`).
 *
 * Fixes the v1.0.19 race where iOS CoreBluetooth starts its own ATT MTU exchange right after connecting: the
 * resulting unsolicited `onMtuChanged(517, 0)` used to complete an in-flight `discoverServices()` with `517`
 * (≠ `GATT_SUCCESS`), so the iPhone looked like it "does not offer Files direct transfer". Now an unsolicited MTU
 * change only updates [mtu]; `onServiceChanged` (API 31+) raises [serviceChanged] for the discovery retry loop.
 */
class GattOpCorrelator {
    private val lock = Any()
    private var pendingOp: PendingGattOp? = null
    private var pendingResult: CompletableDeferred<Any?>? = null

    @Volatile
    var mtu: Int = DEFAULT_MTU
        private set

    @Volatile
    var serviceChanged: Boolean = false
        private set

    /** Registers [op] as the only op that may be completed; returns the deferred its callback completes. */
    fun begin(op: PendingGattOp): CompletableDeferred<Any?> = synchronized(lock) {
        pendingResult?.cancel()
        val d = CompletableDeferred<Any?>()
        pendingOp = op
        pendingResult = d
        d
    }

    /** Clears [op] if it is still the pending one (timeout / start failure). */
    fun end(op: PendingGattOp) = synchronized(lock) {
        if (pendingOp == op) {
            pendingOp = null
            pendingResult = null
        }
    }

    /** Returns and clears the `onServiceChanged` flag. */
    fun consumeServiceChanged(): Boolean = synchronized(lock) {
        val v = serviceChanged
        serviceChanged = false
        v
    }

    /**
     * Routes [event]; returns `true` when it completed the pending op. MTU changes always update [mtu] (on success)
     * whether solicited or not; [GattEvent.Disconnected] fails any pending op.
     */
    fun onEvent(event: GattEvent): Boolean {
        val (op, d) = synchronized(lock) {
            when (event) {
                is GattEvent.MtuChanged -> if (event.status == GATT_SUCCESS && event.mtu >= DEFAULT_MTU) mtu = event.mtu
                GattEvent.ServiceChanged -> serviceChanged = true
                else -> Unit
            }
            pendingOp to pendingResult
        }
        if (op == null || d == null) return false
        val value: Any? = when (event) {
            GattEvent.Disconnected -> {
                synchronized(lock) { if (pendingResult === d) clearLocked() }
                return d.completeExceptionally(IOException("Bluetooth disconnected"))
            }
            GattEvent.ServiceChanged -> return false
            is GattEvent.MtuChanged -> if (op is PendingGattOp.Mtu) event.mtu else return false
            is GattEvent.ServicesDiscovered -> if (op is PendingGattOp.Discover) event.status else return false
            is GattEvent.CharacteristicRead ->
                if (op is PendingGattOp.Read && op.uuid == event.uuid) {
                    if (event.status == GATT_SUCCESS) event.value else null
                } else {
                    return false
                }
            is GattEvent.DescriptorWritten ->
                if (op is PendingGattOp.WriteDescriptor && op.uuid == event.uuid) event.status else return false
            is GattEvent.CharacteristicWritten ->
                if (op is PendingGattOp.WriteCharacteristic && op.uuid == event.uuid) event.status else return false
        }
        synchronized(lock) { if (pendingResult === d) clearLocked() }
        return d.complete(value)
    }

    /** Fails any pending op (client closed). */
    fun failPending(cause: IOException) {
        val d = synchronized(lock) {
            val cur = pendingResult
            clearLocked()
            cur
        }
        d?.completeExceptionally(cause)
    }

    private fun clearLocked() {
        pendingOp = null
        pendingResult = null
    }

    companion object {
        const val GATT_SUCCESS = 0
        const val DEFAULT_MTU = 23
    }
}

/**
 * Service discovery with GATT-cache recovery (pure JVM, testable with fakes). Android caches an iPhone's system
 * GATT table from earlier BLE contact; when Files publishes its service later, discovery may return the stale table
 * and API 31+ fires `onServiceChanged`. Each failed / stale attempt does a best-effort `refresh()` + short delay and
 * retries, up to [MAX_ATTEMPTS] total.
 */
object GattDiscoveryPolicy {
    const val MAX_ATTEMPTS = 3
    const val REFRESH_DELAY_MS = 350L

    /** Whether attempt [attempt] (1-based) must be retried. */
    fun shouldRetry(attempt: Int, status: Int?, hasService: Boolean, serviceChanged: Boolean): Boolean {
        if (attempt >= MAX_ATTEMPTS) return false
        return status != GattOpCorrelator.GATT_SUCCESS || !hasService || serviceChanged
    }

    /**
     * @param discover starts one `discoverServices()` and returns its status (`null` on timeout / start failure)
     * @param hasService whether the Files service is in the current table
     * @param consumeServiceChanged returns-and-clears the `onServiceChanged` flag
     * @param refresh best-effort `BluetoothGatt.refresh()` (invalidates Android's GATT cache)
     * @return `true` when the Files service was found on a fresh, unchanged table (or on the last attempt)
     */
    suspend fun discoverWithRetry(
        discover: suspend () -> Int?,
        hasService: () -> Boolean,
        consumeServiceChanged: () -> Boolean,
        refresh: suspend () -> Unit,
        delayMs: Long = REFRESH_DELAY_MS,
    ): Boolean {
        var attempt = 0
        while (true) {
            attempt++
            val status = discover()
            val found = status == GattOpCorrelator.GATT_SUCCESS && hasService()
            val changed = consumeServiceChanged()
            if (!shouldRetry(attempt, status, found, changed)) return found
            refresh()
            delay(delayMs)
        }
    }
}
