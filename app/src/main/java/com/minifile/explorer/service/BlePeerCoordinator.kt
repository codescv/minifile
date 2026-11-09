package com.minifile.explorer.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.os.Environment
import android.os.ParcelUuid
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.minifile.explorer.BuildConfig
import com.minifile.explorer.core.http.HttpEvent
import com.minifile.explorer.core.http.HttpTransferDirection
import com.minifile.explorer.core.model.MediaScanNotifier
import com.minifile.explorer.core.ops.ProgressSink
import com.minifile.explorer.core.peer.GattDiscoveryPolicy
import com.minifile.explorer.core.peer.GattEvent
import com.minifile.explorer.core.peer.GattOpCorrelator
import com.minifile.explorer.core.peer.L2capPeerTransferEngine
import com.minifile.explorer.core.peer.L2capReceiveListener
import com.minifile.explorer.core.peer.PeerSendItem
import com.minifile.explorer.core.peer.PeerSendResult
import com.minifile.explorer.core.peer.PendingGattOp
import com.minifile.explorer.core.peer.SingleUseToken
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.core.peer.BleAdvertisement
import com.minifile.explorer.core.peer.BlePeerCard
import com.minifile.explorer.core.peer.HostDecision
import com.minifile.explorer.core.peer.HotspotLinkCodec
import com.minifile.explorer.core.peer.HotspotLinkMessage
import com.minifile.explorer.core.peer.HotspotLinkUuids
import com.minifile.explorer.core.peer.LinkBlocker
import com.minifile.explorer.core.peer.LinkMode
import com.minifile.explorer.core.peer.PeerInfo
import com.minifile.explorer.core.peer.PeerPathPolicy
import com.minifile.explorer.core.peer.PeerSource
import com.minifile.explorer.core.peer.PeerTransferException
import com.minifile.explorer.data.LanAddressResolver
import com.minifile.explorer.data.SettingsRepository
import com.minifile.explorer.ui.util.NearbyPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Incoming BLE `hello` awaiting the receiver's Accept / Decline (60 s, spec §2.3 step 2). */
data class IncomingBleRequest(
    val requestId: Long,
    val fromId: String,
    val fromName: String,
    val os: String,
    val files: Int,
    val bytes: Long,
    val deadlineMs: Long,
)

enum class BleLinkRole { SENDER, RECEIVER }

/** Progress / QR state for the MainActivity-level hotspot link dialog. */
data class BleLinkUiState(
    val role: BleLinkRole,
    val peerName: String,
    val phase: String,
    val hotspot: HotspotInfo? = null,
    /** `true` when the peer is an iPhone that must scan the `WIFI:` QR with its Camera. */
    val showQr: Boolean = false,
    /**
     * `true` while an Android peer is auto-joining this phone's hotspot (no QR needed): the dialog may offer a
     * "Show QR code instead" fallback for ROMs that reject `WifiNetworkSpecifier`.
     */
    val qrFallback: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
    /** `0f..1f` byte progress for direct Bluetooth (L2CAP) transfers, `null` when not applicable. */
    val progress: Float? = null,
)

enum class BleAvailability { READY, UNSUPPORTED, BLUETOOTH_OFF, NO_PERMISSION, LOCATION_OFF }

/**
 * Negotiated link: send to [peer] (with `linkToken`) — or, for [LinkMode.L2CAP], through [directSend] over the open
 * Bluetooth channel — then always call [teardown].
 */
class LinkResult(
    val peer: PeerInfo,
    val mode: LinkMode,
    val teardown: suspend (ok: Boolean) -> Unit,
    val directSend: (suspend (items: List<PeerSendItem>, sink: ProgressSink) -> PeerSendResult)? = null,
)

/**
 * Bluetooth LE discovery + hotspot negotiation (spec §2.1–§2.3, §3.5). Peripheral (advertiser + GATT server =
 * receiver) and Central (scanner + GATT client = sender). Identity comes from passive advertisements only
 * (Android `0xFFFF` manufacturer data / iOS `MF:<id6>:<name>`); a cached, serialized GATT `INFO` read happens only
 * when both are missing. GATT ops are strictly serialized, `gatt.close()` always runs on disconnect, `status == 133`
 * is retried once after 300 ms, and two `BluetoothGatt` objects never exist for one address.
 */
@SuppressLint("MissingPermission") // every entry point checks NearbyPermissions before touching the radio
class BlePeerCoordinator(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val lanAddressResolver: LanAddressResolver,
    private val peerDiscoveryHolder: PeerDiscoveryHolder,
    private val hotspotManager: LocalOnlyHotspotManager,
    private val connector: WifiSpecifierConnector,
    private val fileSystem: FileSystem? = null,
    private val mediaScanNotifier: MediaScanNotifier = MediaScanNotifier.NOOP,
) {
    private val appContext = context.applicationContext
    private val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = btManager?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _blePeers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val blePeers: StateFlow<List<PeerInfo>> = _blePeers.asStateFlow()

    private val _incoming = MutableSharedFlow<IncomingBleRequest>(
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val incoming: SharedFlow<IncomingBleRequest> = _incoming.asSharedFlow()

    private val _pendingIncoming = MutableStateFlow<IncomingBleRequest?>(null)
    val pendingIncoming: StateFlow<IncomingBleRequest?> = _pendingIncoming.asStateFlow()

    private val _linkUi = MutableStateFlow<BleLinkUiState?>(null)
    val linkUi: StateFlow<BleLinkUiState?> = _linkUi.asStateFlow()

    private val _availability = MutableStateFlow(BleAvailability.UNSUPPORTED)
    val availability: StateFlow<BleAvailability> = _availability.asStateFlow()

    /** Receiver presence (GATT server + advertise): held while MainActivity is started. */
    private var presenceCount = 0

    /** Active scanning (presence + filtered scan): Transfer tab / Send sheet / Hotspot sheet visible. */
    private var scanRefCount = 0
    private var scanning = false
    private var advertising = false

    /** Exact `0xFFFF` scan-response payload in effect (id + name + flags) so any change re-advertises. */
    private var advertisedPayload: ByteArray? = null

    @Volatile
    private var gattServer: BluetoothGattServer? = null
    private var pruneJob: Job? = null

    /** Serializes radio start/stop across main-thread (lifecycle, broadcasts) and IO (settings, sessions) callers. */
    private val radioLock = Any()
    private var stateReceiverRegistered = false

    private val radioStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Bluetooth toggled in Quick Settings / Location toggled (Android ≤ 12): start or stop radios now.
            restartRadios()
        }
    }

    private class Seen(
        val adv: BleAdvertisement,
        val device: BluetoothDevice,
        val lastSeenMs: Long,
        val fullId: String? = null,
    )

    private val seen = ConcurrentHashMap<String, Seen>()

    /**
     * Last sighting per `id6`, kept after scanning stops (e.g. the Send sheet closed the moment a peer was tapped)
     * so [negotiate] can still reach a peer the user just picked instead of failing "out of Bluetooth range".
     */
    private val recentDevices = ConcurrentHashMap<String, Seen>()
    private val infoReadAttempted = ConcurrentHashMap.newKeySet<String>()
    private val infoReadQueue = Channel<BluetoothDevice>(capacity = 16)

    private val requestIds = AtomicLong(1)

    // --- Peripheral (receiver) state ---
    private val serverMtu = ConcurrentHashMap<String, Int>()
    private val serverReassemblers = ConcurrentHashMap<String, HotspotLinkCodec.Reassembler>()
    private val notifyMutex = Mutex()

    @Volatile
    private var notifySent: CompletableDeferred<Int>? = null

    private class IncomingSession(
        val requestId: Long,
        val device: BluetoothDevice,
        val hello: HotspotLinkMessage.Hello,
        val decision: CompletableDeferred<Boolean> = CompletableDeferred(),
        @Volatile var accepted: Boolean = false,
        @Volatile var hostedHotspot: Boolean = false,
        @Volatile var httpStartedHere: Boolean = false,
        /** Listening L2CAP server (API 29+) whose PSM went out in `ack`; closed after one accept or on any end. */
        @Volatile var l2capServer: BluetoothServerSocket? = null,
        @Volatile var l2capSocket: BluetoothSocket? = null,
    )

    @Volatile
    private var incomingSession: IncomingSession? = null

    // --- Central (sender) state ---
    @Volatile
    private var negotiationJob: Job? = null

    /** Open sender-side L2CAP channel, closed by [cancelLink] so a blocked write unblocks at once. */
    @Volatile
    private var activeSenderSocket: BluetoothSocket? = null

    init {
        refreshAvailability()
        scope.launch {
            for (device in infoReadQueue) {
                runCatching { readInfoOnce(device) }
            }
        }
        scope.launch {
            HttpServerHolder.peerEvents.collect { ev -> onHttpPeerEvent(ev) }
        }
        scope.launch {
            // Live Settings: device name / id / read-only changes re-advertise immediately.
            settingsRepository.settings
                .map { AdvertisedSettings(it.peerDeviceName, it.peerDeviceId, it.httpReadOnly, it.ftpReadOnly) }
                .distinctUntilChanged()
                .drop(1)
                .collect { refreshAdvertisedFlags() }
        }
    }

    private data class AdvertisedSettings(
        val name: String,
        val id: String,
        val httpReadOnly: Boolean,
        val ftpReadOnly: Boolean,
    )

    // =============================================================================================
    // Lifecycle
    // =============================================================================================

    fun refreshAvailability(): BleAvailability {
        val a = when {
            btManager == null || adapter == null || !NearbyPermissions.hasBleHardware(appContext) -> BleAvailability.UNSUPPORTED
            !NearbyPermissions.hasAllBlePermissions(appContext) -> BleAvailability.NO_PERMISSION
            !NearbyPermissions.isBluetoothOn(appContext) -> BleAvailability.BLUETOOTH_OFF
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S && NearbyPermissions.needsLocationServices(appContext) ->
                BleAvailability.LOCATION_OFF
            else -> BleAvailability.READY
        }
        _availability.value = a
        return a
    }

    /**
     * Receiver presence, held while MainActivity is started (any tab): keeps the GATT server + advertisement up so
     * this phone is discoverable and the consent dialog can pop up from Browse / Settings too. Never prompts for
     * permissions — it only activates when BLE permissions are already granted and Bluetooth is on.
     */
    fun acquirePresence() {
        synchronized(lock) { presenceCount++ }
        restartRadios()
    }

    fun releasePresence() {
        synchronized(lock) { if (presenceCount > 0) presenceCount-- }
        applyRefs()
    }

    /** Refcounted active scanning: Transfer tab / Send sheet / Hotspot sheet visible ⇒ presence + filtered scan. */
    fun acquire() {
        synchronized(lock) { scanRefCount++ }
        restartRadios()
    }

    fun release() {
        synchronized(lock) { if (scanRefCount > 0) scanRefCount-- }
        applyRefs()
    }

    private fun refs(): Pair<Boolean, Boolean> = synchronized(lock) { (presenceCount > 0) to (scanRefCount > 0) }

    /** Call after permissions were granted, Bluetooth / Location toggled, settings changed, or on resume. */
    fun restartRadios() {
        synchronized(radioLock) {
            val (presence, scan) = refs()
            refreshAvailability()
            if (!presence && !scan) return
            registerStateReceiver()
            if (_availability.value != BleAvailability.READY) {
                stopScanning()
                stopAdvertising()
                if (incomingSession == null) closeGattServer()
                return
            }
            ensureGattServer()
            startAdvertising()
            if (scan) {
                startScan()
                if (pruneJob?.isActive != true) {
                    pruneJob = scope.launch {
                        while (isActive) {
                            delay(2_000)
                            pruneSeen()
                        }
                    }
                }
            } else {
                stopScanning()
            }
        }
    }

    /** Drops whatever the remaining refcounts no longer need. */
    private fun applyRefs() {
        synchronized(radioLock) {
            val (presence, scan) = refs()
            if (!scan) stopScanning()
            if (!presence && !scan) {
                stopAdvertising()
                // An accepted / pending incoming session keeps its GATT server until it ends.
                if (incomingSession == null) closeGattServer()
                unregisterStateReceiver()
            }
        }
    }

    /** Stops the scan + pruning and forgets seen peers; presence (advertise + GATT server) is untouched. */
    private fun stopScanning() {
        stopScan()
        pruneJob?.cancel()
        pruneJob = null
        if (seen.isNotEmpty()) {
            seen.clear()
            publishPeers()
        }
    }

    private fun registerStateReceiver() {
        if (stateReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        stateReceiverRegistered = runCatching {
            ContextCompat.registerReceiver(appContext, radioStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
    }

    private fun unregisterStateReceiver() {
        if (!stateReceiverRegistered) return
        stateReceiverRegistered = false
        runCatching { appContext.unregisterReceiver(radioStateReceiver) }
    }

    /** Hosting a LocalOnlyHotspot can succeed right now (permission granted and, on Android ≤ 12, Location on). */
    private fun canHostHotspot(): Boolean =
        NearbyPermissions.hasHotspotPermissions(appContext) && !NearbyPermissions.needsLocationServices(appContext)

    // =============================================================================================
    // Advertising (spec §2.1)
    // =============================================================================================

    private fun currentFlags(): Int {
        var f = HotspotLinkCodec.FLAG_ANDROID
        if (canHostHotspot()) f = f or HotspotLinkCodec.FLAG_CAN_HOST
        val s = settingsRepository.settings.value
        // b2 "receiving": this phone can accept (Files auto-starts the share after Accept) unless read-only.
        if (!(s.httpReadOnly || s.ftpReadOnly)) f = f or HotspotLinkCodec.FLAG_RECEIVING
        if (incomingSession != null || negotiationJob?.isActive == true) f = f or HotspotLinkCodec.FLAG_BUSY
        return f
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) = Unit
        override fun onStartFailure(errorCode: Int) {
            synchronized(lock) {
                advertising = false
                advertisedPayload = null
            }
        }
    }

    private fun startAdvertising() {
        if (!NearbyPermissions.hasBleAdvertisePermission(appContext)) return
        val adv = adapter?.bluetoothLeAdvertiser ?: return
        val s = settingsRepository.settings.value
        val payload = HotspotLinkCodec.encodeAndroidManufacturerData(s.peerDeviceId, s.peerDeviceName, currentFlags())
        synchronized(lock) {
            // Re-advertise when id / name / flags changed, not only flags.
            if (advertising && advertisedPayload?.contentEquals(payload) == true) return
            if (advertising) runCatching { adv.stopAdvertising(advertiseCallback) }
            advertising = false
            advertisedPayload = null
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(HotspotLinkUuids.SERVICE_UUID))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .addManufacturerData(HotspotLinkCodec.MANUFACTURER_ID, payload)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val ok = runCatching { adv.startAdvertising(settings, data, scanResponse, advertiseCallback) }.isSuccess
        synchronized(lock) {
            advertising = ok
            advertisedPayload = if (ok) payload else null
        }
    }

    private fun stopAdvertising() {
        val adv = adapter?.bluetoothLeAdvertiser
        synchronized(lock) {
            if (!advertising) return
            advertising = false
            advertisedPayload = null
        }
        runCatching { adv?.stopAdvertising(advertiseCallback) }
    }

    /** Refreshes the advertisement (busy / receiving / can-host flags, name, id) while presence is active. */
    private fun refreshAdvertisedFlags() {
        synchronized(radioLock) {
            val (presence, scan) = refs()
            val active = (presence || scan) && synchronized(lock) { advertising }
            if (active) startAdvertising()
        }
    }

    // =============================================================================================
    // Scanning + passive identification (spec §2.1 [v2])
    // =============================================================================================

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (r in results) handleScanResult(r)
        }

        override fun onScanFailed(errorCode: Int) {
            synchronized(lock) { scanning = false }
        }
    }

    private fun startScan() {
        if (!NearbyPermissions.hasBleScanPermission(appContext)) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        synchronized(lock) {
            if (scanning) return
            scanning = true
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HotspotLinkUuids.SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val ok = runCatching { scanner.startScan(filters, settings, scanCallback) }.isSuccess
        if (!ok) synchronized(lock) { scanning = false }
    }

    private fun stopScan() {
        val scanner = adapter?.bluetoothLeScanner
        synchronized(lock) {
            if (!scanning) return
            scanning = false
        }
        runCatching { scanner?.stopScan(scanCallback) }
    }

    private fun selfId6(): String = HotspotLinkCodec.id6Of(settingsRepository.settings.value.peerDeviceId)

    private fun handleScanResult(result: ScanResult) {
        val rec = result.scanRecord ?: return
        val adv = HotspotLinkCodec.parseManufacturerData(rec.getManufacturerSpecificData(HotspotLinkCodec.MANUFACTURER_ID))
            ?: HotspotLinkCodec.parseIosLocalName(rec.deviceName)
        if (adv == null) {
            // Name/manufacturer stripped (e.g. backgrounded iOS): one cached, serialized INFO read per address.
            val addr = result.device.address ?: return
            if (infoReadAttempted.add(addr)) infoReadQueue.trySend(result.device)
            return
        }
        if (adv.id6 == selfId6()) return
        val prev = seen[adv.id6]
        val entry = Seen(adv, result.device, System.currentTimeMillis(), prev?.fullId ?: recentDevices[adv.id6]?.fullId)
        seen[adv.id6] = entry
        recentDevices[adv.id6] = entry
        if (prev == null || prev.adv != adv) publishPeers()
    }

    private fun pruneSeen() {
        val now = System.currentTimeMillis()
        var changed = false
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.lastSeenMs > SEEN_TTL_MS) {
                it.remove()
                changed = true
            }
        }
        if (changed) publishPeers()
        recentDevices.entries.removeIf { now - it.value.lastSeenMs > RECENT_DEVICE_TTL_MS }
    }

    private fun publishPeers() {
        _blePeers.value = seen.values
            .sortedBy { it.adv.name.lowercase() }
            .map { s ->
                PeerInfo(
                    id = s.fullId ?: s.adv.id6,
                    name = PeerPathPolicy.sanitizeDisplayName(s.adv.name, 64).ifEmpty { "Nearby device" },
                    host = "",
                    port = 0,
                    version = "",
                    readOnly = s.adv.platform == HotspotLinkCodec.OS_ANDROID && !s.adv.receiving,
                    source = PeerSource.BLE,
                    lastSeenMs = s.lastSeenMs,
                    platform = s.adv.platform,
                    bleFlags = s.adv.flags,
                )
            }
    }

    private suspend fun readInfoOnce(device: BluetoothDevice) {
        if (!NearbyPermissions.hasBleConnectPermission(appContext)) return
        val client = GattClient(device)
        try {
            withTimeout(INFO_READ_TIMEOUT_MS) {
                if (!client.connect()) return@withTimeout
                if (!client.discover()) return@withTimeout
                val bytes = client.readInfo() ?: return@withTimeout
                val card = HotspotLinkCodec.decodePeerCard(bytes) ?: return@withTimeout
                val id6 = HotspotLinkCodec.id6Of(card.id)
                if (id6 == selfId6()) return@withTimeout
                val adv = BleAdvertisement(
                    platform = card.os.ifEmpty { HotspotLinkCodec.OS_IOS },
                    id6 = id6,
                    name = card.name,
                    flags = if (card.os == HotspotLinkCodec.OS_ANDROID) HotspotLinkCodec.FLAG_ANDROID else 0,
                )
                val entry = Seen(adv, device, System.currentTimeMillis(), card.id)
                seen[id6] = entry
                recentDevices[id6] = entry
                publishPeers()
            }
        } catch (_: Exception) {
            // best effort
        } finally {
            client.close()
        }
    }

    // =============================================================================================
    // GATT server (peripheral = receiver)
    // =============================================================================================

    private fun ensureGattServer() = synchronized(radioLock) { ensureGattServerLocked() }

    private fun ensureGattServerLocked() {
        if (gattServer != null) return
        if (!NearbyPermissions.hasBleConnectPermission(appContext)) return
        val mgr = btManager ?: return
        val server = runCatching { mgr.openGattServer(appContext, serverCallback) }.getOrNull() ?: return
        val service = BluetoothGattService(HotspotLinkUuids.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val info = BluetoothGattCharacteristic(
            HotspotLinkUuids.INFO_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val ctrl = BluetoothGattCharacteristic(
            HotspotLinkUuids.CTRL_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val evt = BluetoothGattCharacteristic(
            HotspotLinkUuids.EVT_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        evt.addDescriptor(
            BluetoothGattDescriptor(
                HotspotLinkUuids.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(info)
        service.addCharacteristic(ctrl)
        service.addCharacteristic(evt)
        runCatching { server.addService(service) }
        gattServer = server
    }

    private fun closeGattServer() {
        val s = synchronized(radioLock) {
            val cur = gattServer ?: return
            gattServer = null
            cur
        }
        runCatching { s.clearServices() }
        runCatching { s.close() }
        serverMtu.clear()
        serverReassemblers.clear()
    }

    private fun buildInfoCard(): ByteArray {
        val s = settingsRepository.settings.value
        val http = HttpServerHolder.state.value
        val card = BlePeerCard(
            id = s.peerDeviceId,
            name = PeerPathPolicy.sanitizeDisplayName(s.peerDeviceName, 64).ifEmpty { "Files" },
            os = HotspotLinkCodec.OS_ANDROID,
            ver = BuildConfig.VERSION_NAME,
            caps = HostDecision.capsFor(canHostHotspot()),
            port = if (http.isRunning) http.boundPort else s.httpPort,
            lan = lanIpv4s().take(4),
        )
        return HotspotLinkCodec.encodePeerCard(card)
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverMtu.remove(device.address)
                serverReassemblers.remove(device.address)
                val sess = incomingSession
                if (sess != null && sess.device.address == device.address && !sess.accepted) {
                    // Central vanished before the user decided: drop the prompt.
                    endIncomingSession(error = null)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            serverMtu[device.address] = mtu
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val server = gattServer ?: return
            if (characteristic.uuid != HotspotLinkUuids.INFO_UUID) {
                runCatching { server.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null) }
                return
            }
            val value = buildInfoCard()
            if (offset > value.size) {
                runCatching { server.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null) }
                return
            }
            runCatching {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val server = gattServer ?: return
            if (characteristic.uuid != HotspotLinkUuids.CTRL_UUID || preparedWrite) {
                if (responseNeeded) {
                    runCatching { server.sendResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null) }
                }
                return
            }
            if (responseNeeded) runCatching { server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null) }
            val r = serverReassemblers.getOrPut(device.address) { HotspotLinkCodec.Reassembler() }
            val msgBytes = synchronized(r) { r.feed(value) } ?: return
            val msg = HotspotLinkCodec.decodeMessage(msgBytes) ?: return
            scope.launch { handleCtrl(device, msg) }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (responseNeeded) {
                runCatching { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null) }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            runCatching {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE,
                )
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notifySent?.complete(status)
        }
    }

    /** EVT notify, strictly one frame at a time, waiting for `onNotificationSent` (spec §2.2). */
    private suspend fun notifyMessage(device: BluetoothDevice, msg: HotspotLinkMessage): Boolean {
        val server = gattServer ?: return false
        val evt = server.getService(HotspotLinkUuids.SERVICE_UUID)?.getCharacteristic(HotspotLinkUuids.EVT_UUID)
            ?: return false
        val chunk = HotspotLinkCodec.chunkForMtu(serverMtu[device.address] ?: 23)
        val frames = HotspotLinkCodec.split(HotspotLinkCodec.encodeMessage(msg), chunk)
        return notifyMutex.withLock {
            for (frame in frames) {
                val sent = CompletableDeferred<Int>()
                notifySent = sent
                val queued = runCatching {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notifyApi33(server, device, evt, frame)
                    } else {
                        notifyLegacy(server, device, evt, frame)
                    }
                }.getOrDefault(false)
                if (!queued) return@withLock false
                val status = withTimeoutOrNull(NOTIFY_TIMEOUT_MS) { sent.await() } ?: return@withLock false
                if (status != BluetoothGatt.GATT_SUCCESS) return@withLock false
            }
            true
        }
    }

    @RequiresApi(33)
    private fun notifyApi33(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        evt: BluetoothGattCharacteristic,
        frame: ByteArray,
    ): Boolean = server.notifyCharacteristicChanged(device, evt, false, frame) == BluetoothStatusCodes.SUCCESS

    @Suppress("DEPRECATION")
    private fun notifyLegacy(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        evt: BluetoothGattCharacteristic,
        frame: ByteArray,
    ): Boolean {
        evt.value = frame
        return server.notifyCharacteristicChanged(device, evt, false)
    }

    private suspend fun handleCtrl(device: BluetoothDevice, msg: HotspotLinkMessage) {
        when (msg) {
            is HotspotLinkMessage.Hello -> onHello(device, msg)
            is HotspotLinkMessage.Link -> onLink(device, msg.mode)
            is HotspotLinkMessage.Joined -> {
                val sess = incomingSession ?: return
                if (sess.device.address != device.address) return
                updateReceiverUi(sess, "Connected — waiting for files…")
            }
            is HotspotLinkMessage.Done -> {
                val sess = incomingSession ?: return
                if (sess.device.address != device.address) return
                finishIncoming(sess, ok = msg.ok, message = if (msg.ok) "Transfer complete" else "Transfer ended")
            }
            is HotspotLinkMessage.Error -> {
                val sess = incomingSession ?: return
                if (sess.device.address != device.address) return
                val error = if (msg.code == HotspotLinkMessage.ERR_PERM && !canHostHotspot()) {
                    receiverHotspotBlockerMessage()
                } else {
                    "${sess.hello.name}: ${msg.code}"
                }
                endIncomingSession(error = error)
            }
            else -> Unit
        }
    }

    private suspend fun onHello(device: BluetoothDevice, hello: HotspotLinkMessage.Hello) {
        val current = incomingSession
        if (current != null || negotiationJob?.isActive == true) {
            notifyMessage(device, HotspotLinkMessage.Decline(HotspotLinkMessage.DECLINE_BUSY))
            return
        }
        val s = settingsRepository.settings.value
        if (s.httpReadOnly || s.ftpReadOnly) {
            notifyMessage(device, HotspotLinkMessage.Decline(HotspotLinkMessage.DECLINE_PERM))
            return
        }
        val reqId = requestIds.getAndIncrement()
        val sess = IncomingSession(reqId, device, hello)
        incomingSession = sess
        refreshAdvertisedFlags()
        val req = IncomingBleRequest(
            requestId = reqId,
            fromId = hello.id,
            fromName = PeerPathPolicy.sanitizeDisplayName(hello.name, 64).ifEmpty { "Nearby device" },
            os = hello.os,
            files = hello.files,
            bytes = hello.bytes,
            deadlineMs = System.currentTimeMillis() + CONSENT_TIMEOUT_MS,
        )
        _pendingIncoming.value = req
        _incoming.tryEmit(req)

        val accepted = withTimeoutOrNull(CONSENT_TIMEOUT_MS) { sess.decision.await() }
        _pendingIncoming.value = null
        if (incomingSession !== sess) return
        when (accepted) {
            null -> {
                notifyMessage(device, HotspotLinkMessage.Decline(HotspotLinkMessage.DECLINE_TIMEOUT))
                endIncomingSession(error = null)
            }
            false -> {
                notifyMessage(device, HotspotLinkMessage.Decline(HotspotLinkMessage.DECLINE_USER))
                endIncomingSession(error = null)
            }
            true -> {
                sess.accepted = true
                updateReceiverUi(sess, "Preparing…")
                // Zero-QR, zero-password direct channel: listen for one L2CAP connection and advertise its PSM.
                val psm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) openL2capServer(sess) else 0
                val port = try {
                    ensureHttpForReceive(sess)
                } catch (e: Exception) {
                    if (psm in 1..65535) {
                        0 // HTTP could not start: Bluetooth (L2CAP) can still deliver the files.
                    } else {
                        notifyMessage(device, HotspotLinkMessage.Error(HotspotLinkMessage.ERR_LOHS_FAILED))
                        endIncomingSession(error = e.message ?: "Could not start receiving")
                        return
                    }
                }
                val ack = HotspotLinkMessage.Ack(
                    id = s.peerDeviceId,
                    name = PeerPathPolicy.sanitizeDisplayName(s.peerDeviceName, 64).ifEmpty { "Files" },
                    os = HotspotLinkCodec.OS_ANDROID,
                    caps = HostDecision.capsFor(canHostHotspot(), l2cap = psm in 1..65535),
                    port = port,
                    lan = lanIpv4s().take(4),
                    psm = psm,
                )
                if (!notifyMessage(device, ack)) {
                    endIncomingSession(error = "Bluetooth link lost")
                    return
                }
                updateReceiverUi(sess, "Waiting for ${req.fromName}…")
                // Whole negotiation is bounded (240 s) so an aborted handshake never lingers.
                scope.launch {
                    delay(NEGOTIATION_TIMEOUT_MS)
                    if (incomingSession === sess && _linkUi.value?.finished != true) {
                        val receiving = _linkUi.value?.phase?.startsWith("Receiving") == true
                        if (!receiving) endIncomingSession(error = "Timed out")
                    }
                }
            }
        }
    }

    private suspend fun ensureHttpForReceive(sess: IncomingSession): Int {
        val st = HttpServerHolder.state.value
        if (st.isRunning && st.boundPort in 1..65535) return st.boundPort
        sess.httpStartedHere = true
        FtpServerService.startHttp(appContext)
        return withTimeout(HTTP_START_TIMEOUT_MS) {
            HttpServerHolder.state.first { it.isRunning && it.boundPort in 1..65535 }.boundPort
        }
    }

    private suspend fun onLink(device: BluetoothDevice, mode: LinkMode) {
        val sess = incomingSession ?: return
        if (sess.device.address != device.address || !sess.accepted) return
        if (mode != LinkMode.L2CAP) closeL2capServer(sess) // e.g. iOS fell back to lohs-r after a failed channel
        when (mode) {
            LinkMode.L2CAP -> {
                if (sess.l2capServer == null && sess.l2capSocket == null) {
                    notifyMessage(device, HotspotLinkMessage.Error(HotspotLinkMessage.ERR_BUSY))
                    endIncomingSession(error = "Bluetooth direct transfer is unavailable on this phone")
                    return
                }
                if (sess.l2capSocket == null) updateReceiverUi(sess, "Connecting over Bluetooth…")
            }
            LinkMode.LAN -> updateReceiverUi(sess, "Waiting for files over Wi-Fi…")
            LinkMode.LOHS_R -> {
                if (!canHostHotspot()) {
                    // Tell the sender precisely why instead of a generic "could not start a hotspot".
                    notifyMessage(device, HotspotLinkMessage.Error(HotspotLinkMessage.ERR_PERM))
                    endIncomingSession(error = receiverHotspotBlockerMessage())
                    return
                }
                updateReceiverUi(sess, "Starting hotspot…")
                val info = try {
                    if (sess.httpStartedHere) hotspotManager.adoptEphemeralHttp()
                    hotspotManager.start(HotspotPurpose.BLE_RECEIVER, sess.hello.tok)
                } catch (e: Exception) {
                    notifyMessage(device, HotspotLinkMessage.Error(HotspotLinkMessage.ERR_LOHS_FAILED))
                    endIncomingSession(error = e.message ?: "Hotspot failed")
                    return
                }
                sess.hostedHotspot = true
                val msg = HotspotLinkMessage.Hotspot(
                    ssid = info.ssid,
                    pass = info.pass,
                    sec = if (info.sec == "open") "wpa2" else info.sec,
                    ip = info.hostIp,
                    port = info.httpPort,
                    tok = sess.hello.tok,
                )
                val isIos = sess.hello.os == HotspotLinkCodec.OS_IOS
                _linkUi.value = BleLinkUiState(
                    role = BleLinkRole.RECEIVER,
                    peerName = sess.hello.name,
                    phase = if (isIos) {
                        "Scan this QR with the iPhone Camera to join, then return to Files"
                    } else {
                        "Waiting for ${sess.hello.name} to join…"
                    },
                    hotspot = info,
                    // Android senders auto-join over BLE + WifiNetworkSpecifier: no QR. Only iPhones scan it.
                    showQr = isIos,
                    qrFallback = !isIos,
                )
                // BLE may already be gone (iOS backgrounded): the HTTP path still completes the flow.
                notifyMessage(device, msg)
            }
            LinkMode.LOHS_S -> {
                // Only an iOS receiver is asked to join a sender's hotspot.
                notifyMessage(device, HotspotLinkMessage.Error(HotspotLinkMessage.ERR_BUSY))
                endIncomingSession(error = "Unsupported link mode")
            }
        }
    }

    private fun receiverHotspotBlockerMessage(): String =
        if (NearbyPermissions.hasHotspotPermissions(appContext) && NearbyPermissions.needsLocationServices(appContext)) {
            "Turn on Location to receive without QR or shared Wi-Fi"
        } else {
            "Allow Nearby Wi-Fi permission (Files ▸ Transfer ▸ Allow) to receive without QR or shared Wi-Fi"
        }

    private fun onHttpPeerEvent(ev: HttpEvent) {
        val sess = incomingSession ?: return
        if (!sess.accepted) return
        // Bluetooth (L2CAP) sessions are driven by runL2capReceiver; their mirrored log events are ignored here.
        if (ev is HttpEvent.PeerSessionStart && ev.sessionId.startsWith(L2CAP_SESSION_PREFIX)) return
        if (ev is HttpEvent.PeerSessionEnd && ev.sessionId.startsWith(L2CAP_SESSION_PREFIX)) return
        when (ev) {
            is HttpEvent.PeerSessionStart -> updateReceiverUi(sess, "Receiving from ${ev.deviceName}…")
            is HttpEvent.PeerSessionEnd -> scope.launch {
                finishIncoming(sess, ok = ev.ok, message = if (ev.ok) "Received ${ev.files} file(s)" else "Transfer ended")
            }
            else -> Unit
        }
    }

    private suspend fun finishIncoming(sess: IncomingSession, ok: Boolean, message: String) {
        if (incomingSession !== sess) return
        closeL2capServer(sess)
        _linkUi.value = _linkUi.value?.copy(phase = message, finished = true, showQr = false, qrFallback = false)
            ?: BleLinkUiState(BleLinkRole.RECEIVER, sess.hello.name, message, finished = true)
        if (sess.hostedHotspot) hotspotManager.scheduleStop(LocalOnlyHotspotManager.SESSION_GRACE_MS)
        if (sess.httpStartedHere && !sess.hostedHotspot) {
            scope.launch {
                delay(LocalOnlyHotspotManager.SESSION_GRACE_MS)
                if (incomingSession == null) runCatching { FtpServerService.stopHttp(appContext) }
            }
        }
        incomingSession = null
        refreshAdvertisedFlags()
        // The session kept the GATT server alive past MainActivity.onStop(): release it now that it's over.
        val (presence, scan) = refs()
        if (!presence && !scan) closeGattServer()
        delay(if (ok) 1_500 else 3_000)
        if (_linkUi.value?.finished == true && _linkUi.value?.role == BleLinkRole.RECEIVER) _linkUi.value = null
    }

    private fun endIncomingSession(error: String?) {
        val sess = incomingSession ?: return
        incomingSession = null
        _pendingIncoming.value = null
        sess.decision.complete(false)
        closeL2capServer(sess)
        sess.l2capSocket?.let { sock -> runCatching { sock.close() } }
        if (sess.hostedHotspot) hotspotManager.stop()
        HttpServerHolder.hotspotGate.clearSessionToken()
        if (sess.httpStartedHere && !sess.hostedHotspot) runCatching { FtpServerService.stopHttp(appContext) }
        refreshAdvertisedFlags()
        _linkUi.value = if (error != null && sess.accepted) {
            BleLinkUiState(BleLinkRole.RECEIVER, sess.hello.name, "Failed", error = error, finished = true)
        } else {
            null
        }
        val (presence, scan) = refs()
        if (!presence && !scan) closeGattServer()
    }

    private fun updateReceiverUi(sess: IncomingSession, phase: String) {
        val cur = _linkUi.value
        val hideQr = phase.startsWith("Receiving") || phase.startsWith("Connected")
        _linkUi.value = if (cur != null && cur.role == BleLinkRole.RECEIVER) {
            cur.copy(phase = phase, showQr = cur.showQr && !hideQr, qrFallback = cur.qrFallback && !hideQr)
        } else {
            BleLinkUiState(BleLinkRole.RECEIVER, sess.hello.name, phase)
        }
    }

    /** Accept / Decline from the consent dialog. */
    fun respondToIncoming(requestId: Long, accept: Boolean) {
        val sess = incomingSession ?: return
        if (sess.requestId != requestId) return
        sess.decision.complete(accept)
    }

    /** Cancel button on the link dialog (either role). */
    fun cancelLink() {
        negotiationJob?.cancel(CancellationException("Cancelled"))
        activeSenderSocket?.let { sock -> runCatching { sock.close() } }
        val sess = incomingSession
        if (sess != null) {
            scope.launch {
                runCatching { notifyMessage(sess.device, HotspotLinkMessage.Error(HotspotLinkMessage.ERR_CANCELLED)) }
                endIncomingSession(error = null)
            }
        }
        _linkUi.value = null
    }

    fun dismissLinkUi() {
        if (_linkUi.value?.finished == true || _linkUi.value?.error != null) _linkUi.value = null
    }

    // =============================================================================================
    // BLE L2CAP CoC direct transfer (`MFL2` v1): zero QR, zero hotspot, zero password
    // =============================================================================================

    /** Opens the receiver's insecure L2CAP server and starts the single accept; returns its PSM or `0`. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun openL2capServer(sess: IncomingSession): Int {
        if (fileSystem == null || !NearbyPermissions.hasBleConnectPermission(appContext)) return 0
        val server = runCatching { adapter?.listenUsingInsecureL2capChannel() }.getOrNull() ?: return 0
        val psm = runCatching { server.psm }.getOrDefault(0)
        if (psm !in 1..65535) {
            runCatching { server.close() }
            return 0
        }
        sess.l2capServer = server
        scope.launch {
            // Blocks until the sender connects; closing the server (any session end / other link mode) unblocks it.
            val sock = runCatching { server.accept() }.getOrNull()
            if (sock != null) sess.l2capSocket = sock // set before closing the server: onLink(l2cap) checks both
            closeL2capServer(sess)
            if (sock == null) return@launch
            if (incomingSession !== sess || !sess.accepted) {
                sess.l2capSocket = null
                runCatching { sock.close() }
                return@launch
            }
            runL2capReceiver(sess, sock)
        }
        return psm
    }

    private fun closeL2capServer(sess: IncomingSession) {
        val server = sess.l2capServer ?: return
        sess.l2capServer = null
        runCatching { server.close() }
    }

    /** Where Bluetooth transfers are saved: the shared root (same as HTTP Share) + `/Received`. */
    private fun receiveRoot(): VfsPath {
        val st = settingsRepository.settings.value
        val raw = st.ftpRoot ?: st.httpRoot
        return raw?.let { runCatching { VfsPath.parse(it) }.getOrNull() }
            ?: VfsPath.Local(Environment.getExternalStorageDirectory().absolutePath)
    }

    private suspend fun runL2capReceiver(sess: IncomingSession, sock: BluetoothSocket) {
        val fs = fileSystem ?: return
        val sessionId = "$L2CAP_SESSION_PREFIX${sess.requestId}"
        val peerName = PeerPathPolicy.sanitizeDisplayName(sess.hello.name, 64).ifEmpty { "Nearby device" }
        val startedAt = System.currentTimeMillis()
        var began = false
        var lastUiBytes = -L2CAP_UI_STEP_BYTES
        updateReceiverUi(sess, "Receiving via Bluetooth…")
        val listener = object : L2capReceiveListener {
            override fun onBegin(senderId: String, senderName: String, files: Int, bytes: Long) {
                began = true
                HttpServerHolder.onEvent(HttpEvent.PeerSessionStart(sessionId, BLUETOOTH_CLIENT, senderName, files, bytes))
            }

            override fun onProgress(
                processedFiles: Int,
                totalFiles: Int,
                processedBytes: Long,
                totalBytes: Long,
                currentItem: String,
            ) {
                val due = processedBytes - lastUiBytes >= L2CAP_UI_STEP_BYTES || processedBytes >= totalBytes
                if (!due) {
                    return
                }
                lastUiBytes = processedBytes
                if (incomingSession === sess) {
                    val cur = _linkUi.value
                    val phase = "Receiving via Bluetooth… (${byteProgress(processedBytes, totalBytes)})"
                    val fraction = if (totalBytes > 0) (processedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
                    _linkUi.value = if (cur != null && cur.role == BleLinkRole.RECEIVER && !cur.finished) {
                        cur.copy(phase = phase, progress = fraction, showQr = false, qrFallback = false)
                    } else {
                        BleLinkUiState(BleLinkRole.RECEIVER, peerName, phase, progress = fraction)
                    }
                }
                HttpServerHolder.onEvent(
                    HttpEvent.PeerProgress(sessionId, processedFiles, totalFiles, processedBytes, totalBytes, currentItem),
                )
            }

            override fun onFileSaved(savedRelPath: String, path: VfsPath, bytes: Long, durationMs: Long) {
                runCatching { mediaScanNotifier.rescan(path) }
                HttpServerHolder.onEvent(
                    HttpEvent.Transfer(
                        clientIp = BLUETOOTH_CLIENT,
                        direction = HttpTransferDirection.UPLOAD,
                        path = "/${L2capPeerTransferEngine.DEFAULT_INBOX}/$savedRelPath",
                        bytes = bytes,
                        durationMs = durationMs,
                        peerSessionId = sessionId,
                    ),
                )
            }
        }
        val res = try {
            L2capPeerTransferEngine().receive(
                input = sock.inputStream,
                output = sock.outputStream,
                token = SingleUseToken(sess.hello.tok),
                fs = fs,
                root = receiveRoot(),
                inboxDir = L2capPeerTransferEngine.DEFAULT_INBOX,
                listener = listener,
                isCancelled = { incomingSession !== sess },
            )
        } finally {
            runCatching { sock.close() }
            sess.l2capSocket = null
        }
        if (began) {
            HttpServerHolder.onEvent(
                HttpEvent.PeerSessionEnd(
                    sessionId = sessionId,
                    clientIp = BLUETOOTH_CLIENT,
                    deviceName = res.senderName.ifEmpty { peerName },
                    files = res.files,
                    bytes = res.bytes,
                    durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L),
                    ok = res.ok,
                    cancelled = res.cancelled,
                    error = res.error,
                ),
            )
        }
        if (incomingSession !== sess) return
        if (res.ok) {
            val count = if (res.files == 1) "1 file" else "${res.files} files"
            finishIncoming(sess, ok = true, message = "Received $count (${PathUtils.formatBytes(res.bytes)})")
        } else {
            endIncomingSession(error = res.error ?: "Bluetooth transfer failed")
        }
    }

    /**
     * Sender side of [LinkMode.L2CAP]: writes `link{l2cap}`, opens the channel to `ack.psm` (8 s timeout) and returns a
     * [LinkResult] whose [LinkResult.directSend] streams `MFL2` over it while GATT stays up until `done`. Returns
     * `null` when the channel could not be opened so the caller can fall back to a hotspot.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun runL2capSender(
        c: GattClient,
        device: BluetoothDevice,
        ack: HotspotLinkMessage.Ack,
        ackName: String,
        receiverOs: String,
        tok: String,
        phase: (String) -> Unit,
    ): LinkResult? {
        phase("Connecting to $ackName over Bluetooth…")
        c.requestHighPriority()
        if (!c.writeMessage(HotspotLinkMessage.Link(LinkMode.L2CAP))) return null
        val sock = openL2capSocket(device, ack.psm) ?: return null
        activeSenderSocket = sock
        phase("Sending to $ackName via Bluetooth…")
        val s = settingsRepository.settings.value
        val senderName = PeerPathPolicy.sanitizeDisplayName(s.peerDeviceName, 64).ifEmpty { "Files" }
        val peerInfo = PeerInfo(
            id = ack.id.ifEmpty { ackName },
            name = ackName,
            host = "",
            port = 0,
            version = "",
            readOnly = false,
            source = PeerSource.BLE,
            lastSeenMs = System.currentTimeMillis(),
            platform = receiverOs,
        )
        return LinkResult(
            peer = peerInfo,
            mode = LinkMode.L2CAP,
            teardown = { ok ->
                if (activeSenderSocket === sock) activeSenderSocket = null
                runCatching { sock.close() }
                senderTeardown(c, ok, joined = false, hosted = false)
            },
            directSend = { items, sink ->
                L2capPeerTransferEngine().send(
                    input = sock.inputStream,
                    output = sock.outputStream,
                    token = tok,
                    senderId = s.peerDeviceId,
                    senderName = senderName,
                    peerName = ackName,
                    items = items,
                    sink = senderProgressSink(sink),
                )
            },
        )
    }

    /** Connects an insecure L2CAP channel with a hard timeout (closing the socket unblocks `connect()`). */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun openL2capSocket(device: BluetoothDevice, psm: Int): BluetoothSocket? {
        val sock = runCatching { device.createInsecureL2capChannel(psm) }.getOrNull() ?: return null
        val connecting = scope.async(Dispatchers.IO) { sock.connect() }
        var connected = false
        try {
            connected = withTimeoutOrNull(L2CAP_CONNECT_TIMEOUT_MS) {
                runCatching { connecting.await() }.isSuccess
            } == true
            return if (connected) sock else null
        } finally {
            if (!connected) runCatching { sock.close() }
        }
    }

    /** Mirrors sender byte progress into the link dialog ("Sending via Bluetooth… (X / Y)"). */
    private fun senderProgressSink(base: ProgressSink): ProgressSink = object : ProgressSink {
        private var lastBytes = -L2CAP_UI_STEP_BYTES

        override fun onProgress(p: com.minifile.explorer.core.model.OperationProgress) {
            base.onProgress(p)
            val due = p.processedBytes - lastBytes >= L2CAP_UI_STEP_BYTES || p.processedBytes >= p.totalBytes
            if (!due) {
                return
            }
            lastBytes = p.processedBytes
            val cur = _linkUi.value ?: return
            if (cur.role != BleLinkRole.SENDER || cur.finished) return
            val fraction = if (p.totalBytes > 0) (p.processedBytes.toFloat() / p.totalBytes).coerceIn(0f, 1f) else null
            _linkUi.value = cur.copy(
                phase = "Sending via Bluetooth… (${byteProgress(p.processedBytes, p.totalBytes)})",
                progress = fraction,
            )
        }

        override fun isCancelled(): Boolean = base.isCancelled()
    }

    private fun byteProgress(done: Long, total: Long): String =
        "${PathUtils.formatBytes(done)} / ${PathUtils.formatBytes(total)}"

    // =============================================================================================
    // Central (sender) negotiation (spec §2.3)
    // =============================================================================================

    /**
     * Negotiates a link to the BLE-only [peer] and returns where to send. Throws [PeerTransferException] with a
     * user-facing message on decline / timeout / failure. The caller MUST invoke [LinkResult.teardown].
     */
    suspend fun negotiate(peer: PeerInfo, files: Int, bytes: Long): LinkResult = coroutineScope {
        negotiationJob = coroutineContext[Job]
        refreshAdvertisedFlags()
        val peerName = PeerPathPolicy.sanitizeDisplayName(peer.name, 64).ifEmpty { "Nearby device" }
        fun phase(text: String, hotspot: HotspotInfo? = null, showQr: Boolean = false) {
            _linkUi.value = BleLinkUiState(BleLinkRole.SENDER, peerName, text, hotspot, showQr)
        }
        if (refreshAvailability() != BleAvailability.READY) {
            val msg = NearbyPermissions.nearbyHint(appContext) ?: "Bluetooth is unavailable"
            phaseError(peerName, msg)
            throw PeerTransferException(null, msg)
        }
        val target = findTarget(peer) ?: run {
            phaseError(peerName, "$peerName is out of Bluetooth range — keep Files open on $peerName and try again")
            throw PeerTransferException(null, "$peerName is out of Bluetooth range")
        }

        var client: GattClient? = null
        var hostedHotspot = false
        var joinedWifi = false
        var success = false
        try {
            phase("Connecting to $peerName…")
            val c = GattClient(target.device)
            client = c
            if (!c.connect()) throw PeerTransferException(null, "Could not connect to $peerName over Bluetooth")
            c.requestMtu(517)
            if (!c.discover()) {
                throw PeerTransferException(
                    null,
                    "Could not reach Files on $peerName — keep Files open on $peerName with Bluetooth enabled",
                )
            }
            if (!c.enableNotifications()) throw PeerTransferException(null, "Bluetooth setup with $peerName failed")

            val s = settingsRepository.settings.value
            val tok = HotspotLinkCodec.generateToken()
            val myIps = lanIpv4s()
            val hello = HotspotLinkMessage.Hello(
                id = s.peerDeviceId,
                name = PeerPathPolicy.sanitizeDisplayName(s.peerDeviceName, 64).ifEmpty { "Files" },
                os = HotspotLinkCodec.OS_ANDROID,
                ver = BuildConfig.VERSION_NAME,
                files = files,
                bytes = bytes,
                tok = tok,
                lan = myIps.take(4),
            )
            if (!c.writeMessage(hello)) throw PeerTransferException(null, "Bluetooth link to $peerName lost")
            phase("Waiting for $peerName to accept…")

            val reply = c.awaitMessage(CONSENT_TIMEOUT_MS + 5_000) {
                it is HotspotLinkMessage.Ack || it is HotspotLinkMessage.Decline || it is HotspotLinkMessage.Error
            } ?: throw PeerTransferException(null, "$peerName did not respond")
            val ack = when (reply) {
                is HotspotLinkMessage.Ack -> reply
                is HotspotLinkMessage.Decline -> throw PeerTransferException(
                    403,
                    when (reply.reason) {
                        HotspotLinkMessage.DECLINE_BUSY -> "$peerName is busy — try again shortly"
                        HotspotLinkMessage.DECLINE_TIMEOUT -> "$peerName did not accept in time"
                        HotspotLinkMessage.DECLINE_PERM -> "$peerName cannot receive (permissions / read-only)"
                        else -> "Declined by $peerName"
                    },
                )
                is HotspotLinkMessage.Error -> throw PeerTransferException(null, "$peerName: ${reply.code}")
                else -> throw PeerTransferException(null, "$peerName did not respond")
            }
            val ackName = PeerPathPolicy.sanitizeDisplayName(ack.name, 64).ifEmpty { peerName }
            val receiverOs = ack.os.ifEmpty { peer.platform ?: HotspotLinkCodec.OS_ANDROID }

            // --- LAN short-circuit ---
            if (HostDecision.sharesSlash24(myIps, ack.lan) && ack.port in 1..65535) {
                phase("Checking shared Wi-Fi…")
                val lanPeer = probeFirst(ack.lan.filter { HostDecision.sharesSlash24(myIps, listOf(it)) }, ack.port)
                if (lanPeer != null) {
                    c.writeMessage(HotspotLinkMessage.Link(LinkMode.LAN))
                    phase("Sending over Wi-Fi…")
                    success = true
                    val cc = c
                    return@coroutineScope LinkResult(
                        peer = lanPeer.copy(linkToken = tok, platform = receiverOs),
                        mode = LinkMode.LAN,
                        teardown = { ok -> senderTeardown(cc, ok, joined = false, hosted = false) },
                    )
                }
            }

            val plan = HostDecision.plan(
                senderOs = HotspotLinkCodec.OS_ANDROID,
                receiverOs = receiverOs,
                lanOverlap = false,
                receiverCaps = ack.caps,
                senderCanHost = canHostHotspot(),
                ackPsm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ack.psm else 0,
                bytes = bytes,
            )
            plan.blocker?.let { blocker ->
                if (blocker != LinkBlocker.IOS_PAIR) c.writeMessage(HotspotLinkMessage.Error(HotspotLinkMessage.ERR_PERM))
                throw PeerTransferException(null, HostDecision.blockerMessage(blocker, ackName))
            }

            suspend fun runLohsR(): LinkResult {
                c.writeMessage(HotspotLinkMessage.Link(LinkMode.LOHS_R))
                phase("$ackName is starting a hotspot…")
                val hs = c.awaitMessage(HOTSPOT_WAIT_MS) {
                    it is HotspotLinkMessage.Hotspot || it is HotspotLinkMessage.Error
                }
                val hotspot = when (hs) {
                    is HotspotLinkMessage.Hotspot -> hs
                    is HotspotLinkMessage.Error -> throw PeerTransferException(
                        null,
                        when (hs.code) {
                            HotspotLinkMessage.ERR_LOHS_FAILED -> "$ackName could not start a hotspot"
                            HotspotLinkMessage.ERR_PERM -> HostDecision.blockerMessage(LinkBlocker.RECEIVER_CANNOT_HOST, ackName)
                            else -> "$ackName: ${hs.code}"
                        },
                    )
                    else -> throw PeerTransferException(null, "$ackName did not start a hotspot in time")
                }
                phase("Connecting to Wi-Fi “${hotspot.ssid}”…")
                val joined = try {
                    connector.join(hotspot.ssid, hotspot.pass, hotspot.sec, hotspot.ip)
                } catch (e: JoinException) {
                    c.writeMessage(HotspotLinkMessage.Error(HotspotLinkMessage.ERR_JOIN_FAILED))
                    throw PeerTransferException(null, e.message ?: "Could not join $ackName's hotspot")
                }
                joinedWifi = true
                phase("Finding $ackName…")
                val hostIp = hotspot.ip.ifEmpty { joined.gateway.orEmpty() }
                val linked = probeWithRetries(listOfNotNull(hostIp, joined.gateway).distinct(), hotspot.port, 10)
                    ?: throw PeerTransferException(null, "$ackName not reachable on its hotspot")
                c.writeMessage(HotspotLinkMessage.Joined(joined.ownIp.orEmpty()))
                phase("Sending to $ackName…")
                success = true
                val cc = c
                return LinkResult(
                    peer = linked.copy(linkToken = hotspot.tok.ifEmpty { tok }, platform = receiverOs),
                    mode = LinkMode.LOHS_R,
                    teardown = { ok -> senderTeardown(cc, ok, joined = true, hosted = false) },
                )
            }

            suspend fun runLohsS(): LinkResult {
                phase("Starting hotspot…")
                val info = try {
                    hotspotManager.start(HotspotPurpose.BLE_SENDER, tok)
                } catch (e: Exception) {
                    c.writeMessage(HotspotLinkMessage.Error(HotspotLinkMessage.ERR_LOHS_FAILED))
                    throw PeerTransferException(null, e.message ?: "Could not start a hotspot")
                }
                hostedHotspot = true
                c.writeMessage(HotspotLinkMessage.Link(LinkMode.LOHS_S))
                c.writeMessage(
                    HotspotLinkMessage.Hotspot(
                        ssid = info.ssid,
                        pass = info.pass,
                        sec = if (info.sec == "open") "wpa2" else info.sec,
                        ip = info.hostIp,
                        port = info.httpPort,
                        tok = tok,
                    ),
                )
                phase("On the iPhone: tap Accept, then scan this QR with the Camera and return to Files", info, showQr = true)
                val target2 = awaitAnnounceOrReady(c, info)
                    ?: throw PeerTransferException(null, "$ackName did not join the hotspot in time")
                phase("Sending to $ackName…", info, showQr = false)
                success = true
                val cc = c
                return LinkResult(
                    peer = target2.copy(linkToken = tok, platform = HotspotLinkCodec.OS_IOS, name = target2.name.ifEmpty { ackName }),
                    mode = LinkMode.LOHS_S,
                    teardown = { ok -> senderTeardown(cc, ok, joined = false, hosted = true) },
                )
            }

            when (plan.mode) {
                LinkMode.L2CAP -> {
                    val direct = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        runL2capSender(c, target.device, ack, ackName, receiverOs, tok) { text -> phase(text) }
                    } else {
                        null
                    }
                    if (direct != null) {
                        success = true
                        direct
                    } else {
                        // Channel refused / timed out: fall back to a hotspot on the same GATT session.
                        when (HostDecision.l2capFallback(receiverOs, ack.caps, canHostHotspot())) {
                            LinkMode.LOHS_S -> runLohsS()
                            LinkMode.LOHS_R -> runLohsR()
                            else -> {
                                c.writeMessage(HotspotLinkMessage.Error(HotspotLinkMessage.ERR_JOIN_FAILED))
                                throw PeerTransferException(
                                    null,
                                    "Could not open a Bluetooth channel to $ackName — keep Files open on $ackName and try again",
                                )
                            }
                        }
                    }
                }
                LinkMode.LOHS_R -> runLohsR()
                LinkMode.LOHS_S -> runLohsS()
                else -> throw PeerTransferException(null, "Use Nearby (AWDL) between two iPhones")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PeerTransferException) {
            phaseError(peerName, e.userMessage)
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: "Direct link to $peerName failed"
            phaseError(peerName, msg)
            throw PeerTransferException(null, msg, e)
        } finally {
            if (!success) {
                client?.let { cl ->
                    withContext(kotlinx.coroutines.NonCancellable) {
                        runCatching { withTimeoutOrNull(1_500) { cl.writeMessage(HotspotLinkMessage.Error(HotspotLinkMessage.ERR_CANCELLED)) } }
                    }
                    cl.close()
                }
                if (joinedWifi) connector.release()
                if (hostedHotspot) hotspotManager.stop()
                if (_linkUi.value?.error == null && _linkUi.value?.role == BleLinkRole.SENDER) _linkUi.value = null
                negotiationJob = null
                refreshAdvertisedFlags()
            }
        }
    }

    private fun findTarget(peer: PeerInfo): Seen? {
        val id6 = HotspotLinkCodec.id6Of(peer.id)
        val now = System.currentTimeMillis()
        return seen[peer.id] ?: seen[id6] ?: seen.values.firstOrNull { it.fullId == peer.id }
            ?: (recentDevices[id6] ?: recentDevices.values.firstOrNull { it.fullId == peer.id })
                ?.takeIf { now - it.lastSeenMs <= RECENT_DEVICE_TTL_MS }
    }

    // ---------------------------------------------------------------------------------------------
    // Instant sender feedback ("tapped Send, nothing happened")
    // ---------------------------------------------------------------------------------------------

    /**
     * Shows the sender link dialog immediately (t = 0 ms) — before files are enumerated or Bluetooth connects — so
     * tapping a Bluetooth peer is never followed by a silent gap.
     */
    fun beginSenderPreparing(peerName: String) {
        val cur = _linkUi.value
        if (cur != null && !cur.finished && cur.error == null) return // another link is already in progress
        _linkUi.value = BleLinkUiState(BleLinkRole.SENDER, sanitizePeerName(peerName), "Preparing files…")
    }

    /** Ends the "Preparing files…" state when the send stopped before [negotiate] ran. */
    fun endSenderPreparing(peerName: String, error: String?) {
        val cur = _linkUi.value ?: return
        if (cur.role != BleLinkRole.SENDER || cur.finished || negotiationJob?.isActive == true) return
        _linkUi.value = if (error != null) {
            BleLinkUiState(BleLinkRole.SENDER, sanitizePeerName(peerName), "Failed", error = error, finished = true)
        } else {
            null
        }
    }

    private fun sanitizePeerName(name: String): String =
        PeerPathPolicy.sanitizeDisplayName(name, 64).ifEmpty { "Nearby device" }

    private fun phaseError(peerName: String, message: String) {
        _linkUi.value = BleLinkUiState(BleLinkRole.SENDER, peerName, "Failed", error = message, finished = true)
    }

    private suspend fun senderTeardown(c: GattClient, ok: Boolean, joined: Boolean, hosted: Boolean) {
        withContext(kotlinx.coroutines.NonCancellable) {
            runCatching { withTimeoutOrNull(2_000) { c.writeMessage(HotspotLinkMessage.Done(ok)) } }
            c.close()
            if (joined) connector.releaseAfter(WifiSpecifierConnector.JOIN_GRACE_MS)
            if (hosted) hotspotManager.scheduleStop(LocalOnlyHotspotManager.SESSION_GRACE_MS)
            negotiationJob = null
            refreshAdvertisedFlags()
            val cur = _linkUi.value
            if (cur != null && cur.role == BleLinkRole.SENDER) {
                _linkUi.value = cur.copy(phase = if (ok) "Sent" else "Transfer ended", finished = true, showQr = false)
                delay(1_200)
                if (_linkUi.value?.finished == true && _linkUi.value?.error == null) _linkUi.value = null
            }
        }
    }

    /** lohs-s: wait for `/api/peer/announce` (HTTP, survives BLE loss) or EVT `ready` (BLE). */
    private suspend fun awaitAnnounceOrReady(c: GattClient, info: HotspotInfo): PeerInfo? =
        withTimeoutOrNull(ANNOUNCE_WAIT_MS) {
            coroutineScope {
                val announce = CompletableDeferred<PeerInfo?>()
                val annJob = launch {
                    val ev = HttpServerHolder.peerEvents.first { it is HttpEvent.PeerAnnounce && !it.open } as HttpEvent.PeerAnnounce
                    announce.complete(
                        PeerInfo(
                            id = ev.id,
                            name = ev.name,
                            host = ev.clientIp.substringBefore('%'),
                            port = ev.port,
                            version = "",
                            readOnly = false,
                            source = PeerSource.ANNOUNCE,
                            lastSeenMs = System.currentTimeMillis(),
                            platform = ev.os,
                        ),
                    )
                }
                val readyJob = launch {
                    val r = c.awaitMessage(ANNOUNCE_WAIT_MS) { it is HotspotLinkMessage.Ready } as? HotspotLinkMessage.Ready
                        ?: return@launch
                    if (r.port !in 1..65535) return@launch
                    if (info.hostIp.isNotEmpty() && !HostDecision.inSubnet(r.ip, info.hostIp, info.prefixLength)) return@launch
                    val probed = probeWithRetries(listOf(r.ip), r.port, 5)
                    if (probed != null) announce.complete(probed)
                }
                val res = announce.await()
                annJob.cancel()
                readyJob.cancel()
                res
            }
        }

    private suspend fun probeFirst(ips: List<String>, port: Int): PeerInfo? {
        for (ip in ips) {
            val p = runCatching {
                peerDiscoveryHolder.engine.probe(host = ip, port = port, source = PeerSource.PROBE, timeoutMs = 2_000)
            }.getOrNull()
            if (p != null) return p
        }
        return null
    }

    private suspend fun probeWithRetries(ips: List<String>, port: Int, attempts: Int): PeerInfo? {
        val candidates = ips.filter { it.isNotBlank() }
        if (candidates.isEmpty()) return null
        repeat(attempts) {
            for (ip in candidates) {
                val p = runCatching {
                    peerDiscoveryHolder.engine.probe(host = ip, port = port, source = PeerSource.QR, timeoutMs = 1_500)
                }.getOrNull()
                if (p != null) return p
            }
            delay(1_000)
        }
        return null
    }

    private fun lanIpv4s(): List<String> {
        val hotspotIps = lanAddressResolver.hotspotEndpoints().map { it.ipv4 }.toSet()
        return lanAddressResolver.currentIpv4List().filter { it !in hotspotIps }
    }

    // =============================================================================================
    // GATT client (central): strictly serialized ops
    // =============================================================================================

    private inner class GattClient(val device: BluetoothDevice) {
        @Volatile
        private var gatt: BluetoothGatt? = null
        private val opMutex = Mutex()

        /** Type-safe op ↔ callback correlation: unsolicited MTU / service-changed callbacks never clobber an op. */
        private val ops = GattOpCorrelator()

        @Volatile
        private var connectResult: CompletableDeferred<Int>? = null

        @Volatile
        private var closed = false
        private val reassembler = HotspotLinkCodec.Reassembler()
        val messages = Channel<HotspotLinkMessage>(capacity = 32)

        val mtu: Int get() = ops.mtu

        private val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connectResult?.complete(status)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectResult?.complete(if (status == BluetoothGatt.GATT_SUCCESS) -1 else status)
                    ops.onEvent(GattEvent.Disconnected)
                    runCatching { g.close() }
                    if (gatt === g) gatt = null
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
                // iOS starts its own ATT MTU exchange on connect: this may arrive while discovery is pending.
                ops.onEvent(GattEvent.MtuChanged(newMtu, status))
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                ops.onEvent(GattEvent.ServicesDiscovered(status))
            }

            override fun onServiceChanged(g: BluetoothGatt) {
                // API 31+: the peer (iPhone) changed its GATT table, e.g. Files published its service just now.
                ops.onEvent(GattEvent.ServiceChanged)
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                ops.onEvent(GattEvent.DescriptorWritten(descriptor.uuid, status))
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                ops.onEvent(GattEvent.CharacteristicWritten(characteristic.uuid, status))
            }

            @Deprecated("Deprecated in API 33")
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                ops.onEvent(GattEvent.CharacteristicRead(characteristic.uuid, status, characteristic.value))
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                ops.onEvent(GattEvent.CharacteristicRead(characteristic.uuid, status, value))
            }

            @Deprecated("Deprecated in API 33")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                onFrame(characteristic.uuid == HotspotLinkUuids.EVT_UUID, characteristic.value)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                onFrame(characteristic.uuid == HotspotLinkUuids.EVT_UUID, value)
            }
        }

        private fun onFrame(isEvt: Boolean, value: ByteArray?) {
            if (!isEvt) return
            val bytes = synchronized(reassembler) { reassembler.feed(value) } ?: return
            val msg = HotspotLinkCodec.decodeMessage(bytes) ?: return
            messages.trySend(msg)
        }

        /** Connects with one retry on `status == 133` after 300 ms; never holds two gatt objects. */
        suspend fun connect(): Boolean {
            repeat(2) { attempt ->
                val res = CompletableDeferred<Int>()
                connectResult = res
                val g = withContext(Dispatchers.Main) {
                    runCatching {
                        device.connectGatt(appContext, false, cb, BluetoothDevice.TRANSPORT_LE)
                    }.getOrNull()
                } ?: return false
                gatt = g
                val status = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { res.await() }
                if (status == BluetoothGatt.GATT_SUCCESS) return true
                runCatching { g.disconnect() }
                runCatching { g.close() }
                gatt = null
                if (status == GATT_ERROR_133 && attempt == 0) {
                    delay(300)
                } else {
                    return false
                }
            }
            return false
        }

        @Suppress("UNCHECKED_CAST")
        private suspend fun <T> op(kind: PendingGattOp, timeoutMs: Long, start: (BluetoothGatt) -> Boolean): T? =
            opMutex.withLock {
                val g = gatt ?: return@withLock null
                val d = ops.begin(kind)
                val started = runCatching { start(g) }.getOrDefault(false)
                if (!started) {
                    ops.end(kind)
                    return@withLock null
                }
                val r = runCatching { withTimeoutOrNull(timeoutMs) { d.await() } }.getOrNull() as T?
                ops.end(kind)
                r
            }

        suspend fun requestMtu(want: Int) {
            op<Int>(PendingGattOp.Mtu, MTU_TIMEOUT_MS) { it.requestMtu(want) }
        }

        /**
         * Discovers services, recovering from Android's stale GATT cache (iPhone system table cached before Files
         * published its service) and API 31+ `onServiceChanged`: best-effort `refresh()` + 350 ms, up to 3 attempts.
         */
        suspend fun discover(): Boolean = GattDiscoveryPolicy.discoverWithRetry(
            discover = { op<Int>(PendingGattOp.Discover, DISCOVER_TIMEOUT_MS) { it.discoverServices() } },
            hasService = { gatt?.getService(HotspotLinkUuids.SERVICE_UUID) != null },
            consumeServiceChanged = { ops.consumeServiceChanged() },
            refresh = { gatt?.let { refreshGattCache(it) } },
        )

        suspend fun readInfo(): ByteArray? {
            val ch = gatt?.getService(HotspotLinkUuids.SERVICE_UUID)?.getCharacteristic(HotspotLinkUuids.INFO_UUID)
                ?: return null
            return op<ByteArray>(PendingGattOp.Read(ch.uuid), DISCOVER_TIMEOUT_MS) { it.readCharacteristic(ch) }
        }

        suspend fun enableNotifications(): Boolean {
            val g = gatt ?: return false
            val evt = g.getService(HotspotLinkUuids.SERVICE_UUID)?.getCharacteristic(HotspotLinkUuids.EVT_UUID)
                ?: return false
            if (!runCatching { g.setCharacteristicNotification(evt, true) }.getOrDefault(false)) return false
            val cccd = evt.getDescriptor(HotspotLinkUuids.CCCD_UUID) ?: return false
            val st = op<Int>(PendingGattOp.WriteDescriptor(cccd.uuid), WRITE_TIMEOUT_MS) { gg ->
                if (Build.VERSION.SDK_INT >= 33) {
                    writeDescriptorApi33(gg, cccd)
                } else {
                    writeDescriptorLegacy(gg, cccd)
                }
            }
            return st == BluetoothGatt.GATT_SUCCESS
        }

        suspend fun writeMessage(msg: HotspotLinkMessage): Boolean {
            val g = gatt ?: return false
            val ctrl = g.getService(HotspotLinkUuids.SERVICE_UUID)?.getCharacteristic(HotspotLinkUuids.CTRL_UUID)
                ?: return false
            val frames = HotspotLinkCodec.split(HotspotLinkCodec.encodeMessage(msg), HotspotLinkCodec.chunkForMtu(mtu))
            for (frame in frames) {
                val st = op<Int>(PendingGattOp.WriteCharacteristic(ctrl.uuid), WRITE_TIMEOUT_MS) { gg ->
                    if (Build.VERSION.SDK_INT >= 33) {
                        writeCharApi33(gg, ctrl, frame)
                    } else {
                        writeCharLegacy(gg, ctrl, frame)
                    }
                }
                if (st != BluetoothGatt.GATT_SUCCESS) return false
            }
            return true
        }

        /** Best-effort: faster connection interval while an L2CAP transfer runs. */
        fun requestHighPriority() {
            runCatching { gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
        }

        suspend fun awaitMessage(timeoutMs: Long, predicate: (HotspotLinkMessage) -> Boolean): HotspotLinkMessage? =
            withTimeoutOrNull(timeoutMs) {
                var found: HotspotLinkMessage? = null
                while (isActive && found == null) {
                    val m = select<HotspotLinkMessage?> { messages.onReceiveCatching { it.getOrNull() } } ?: break
                    if (predicate(m)) found = m
                }
                found
            }

        fun close() {
            if (closed) return
            closed = true
            val g = gatt
            gatt = null
            ops.failPending(IOException("closed"))
            if (g != null) {
                runCatching { g.disconnect() }
                runCatching { g.close() }
            }
            messages.close()
        }
    }

    /** Hidden `BluetoothGatt.refresh()` (drops Android's cached GATT table for this device); best effort. */
    private fun refreshGattCache(g: BluetoothGatt) {
        runCatching {
            val m = g.javaClass.getMethod("refresh")
            m.invoke(g)
        }
    }

    @RequiresApi(33)
    private fun writeCharApi33(g: BluetoothGatt, ch: BluetoothGattCharacteristic, frame: ByteArray): Boolean =
        g.writeCharacteristic(ch, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS

    @Suppress("DEPRECATION")
    private fun writeCharLegacy(g: BluetoothGatt, ch: BluetoothGattCharacteristic, frame: ByteArray): Boolean {
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ch.value = frame
        return g.writeCharacteristic(ch)
    }

    @RequiresApi(33)
    private fun writeDescriptorApi33(g: BluetoothGatt, d: BluetoothGattDescriptor): Boolean =
        g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS

    @Suppress("DEPRECATION")
    private fun writeDescriptorLegacy(g: BluetoothGatt, d: BluetoothGattDescriptor): Boolean {
        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return g.writeDescriptor(d)
    }

    companion object {
        const val CONSENT_TIMEOUT_MS = 60_000L
        const val NEGOTIATION_TIMEOUT_MS = 240_000L
        const val HOTSPOT_WAIT_MS = 30_000L
        const val ANNOUNCE_WAIT_MS = 180_000L
        private const val SEEN_TTL_MS = 10_000L
        private const val RECENT_DEVICE_TTL_MS = 120_000L
        private const val L2CAP_CONNECT_TIMEOUT_MS = 8_000L
        private const val L2CAP_ACCEPT_WAIT_MS = 20_000L
        private const val L2CAP_UI_STEP_BYTES = 64L * 1024
        private const val INFO_READ_TIMEOUT_MS = 8_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val MTU_TIMEOUT_MS = 2_000L
        private const val DISCOVER_TIMEOUT_MS = 8_000L
        private const val WRITE_TIMEOUT_MS = 3_000L
        private const val NOTIFY_TIMEOUT_MS = 2_000L
        private const val HTTP_START_TIMEOUT_MS = 6_000L
        private const val GATT_ERROR_133 = 133
        private const val L2CAP_SESSION_PREFIX = "l2cap-"
        private const val BLUETOOTH_CLIENT = "Bluetooth"
    }
}
