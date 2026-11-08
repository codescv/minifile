package com.minifile.explorer.ui

import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import com.minifile.explorer.core.http.HttpConfig
import com.minifile.explorer.core.http.HttpFileServerEngine
import com.minifile.explorer.core.model.Clock
import com.minifile.explorer.core.model.InMemoryKeyValueStore
import com.minifile.explorer.core.model.MediaScanNotifier
import com.minifile.explorer.core.ops.FileOperations
import com.minifile.explorer.core.ops.NomediaManager
import com.minifile.explorer.core.ops.ZipArchiver
import com.minifile.explorer.core.peer.PeerInfo
import com.minifile.explorer.core.peer.PeerSource
import com.minifile.explorer.core.vfs.FileSystemRepository
import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.core.vfs.Volume
import com.minifile.explorer.core.vfs.VolumeKind
import com.minifile.explorer.data.DefaultFavoritesRepository
import com.minifile.explorer.data.DefaultFtpServersRepository
import com.minifile.explorer.data.DefaultGDriveServersRepository
import com.minifile.explorer.data.SettingsRepository
import com.minifile.explorer.service.HotspotJoinController
import com.minifile.explorer.service.JoinedNetwork
import com.minifile.explorer.service.OperationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spec §3.3 [v2] regression suite for `MainViewModel` hotspot join/release lifecycle:
 * superseded `joinFromQr`, send success (`releaseAfter`), send failure/early-exit (`release`),
 * null `ownIp` fallback, and cancelled document-picker cleanup.
 */
class HotspotJoinLifecycleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var vmScope: CoroutineScope
    private lateinit var operationRegistry: OperationRegistry
    private lateinit var joinController: RecordingJoinController
    private val dummyContext = ContextWrapper(null)

    private class RecordingJoinController(
        var nextJoined: JoinedNetwork = JoinedNetwork(
            network = null,
            gateway = "127.0.0.1",
            ownIp = "127.0.0.2",
            prefixLength = 8,
        ),
    ) : HotspotJoinController {
        @Volatile
        override var joined: JoinedNetwork? = null
        val joinCalls = AtomicInteger(0)
        val releaseCalls = AtomicInteger(0)
        val releaseAfterCalls = AtomicInteger(0)

        override suspend fun join(ssid: String, pass: String, sec: String, expectedHostIp: String?): JoinedNetwork {
            joinCalls.incrementAndGet()
            val net = nextJoined
            joined = net
            return net
        }

        override fun releaseAfter(graceMs: Long) {
            releaseAfterCalls.incrementAndGet()
            joined = null
        }

        override fun release() {
            releaseCalls.incrementAndGet()
            joined = null
        }
    }

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("root")
        vmScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        operationRegistry = OperationRegistry()
        joinController = RecordingJoinController()
    }

    @After
    fun tearDown() {
        vmScope.cancel()
    }

    private fun createViewModel(
        probePeer: suspend (String, Int, PeerSource, Int) -> PeerInfo? = { _, _, _, _ -> null },
    ): MainViewModel {
        val store = InMemoryKeyValueStore()
        val clock = Clock { 1_700_000_000_000L }
        val settingsRepository = SettingsRepository(store = store, defaultDeviceNameProvider = { "SenderPhone" })
        val favoritesRepository = DefaultFavoritesRepository(
            store = store,
            clock = clock,
            defaultPrimaryRoot = rootDir.absolutePath,
        )
        val ftpServersRepository = DefaultFtpServersRepository(store = store, clock = clock)
        val gdriveServersRepository = DefaultGDriveServersRepository(store = store, clock = clock)
        val fs = FileSystemRepository(listOf(LocalFileSystem()))
        val volumesFlow = MutableStateFlow(
            listOf(
                Volume(
                    id = "primary",
                    name = "Internal",
                    kind = VolumeKind.PRIMARY,
                    root = VfsPath.Local(rootDir.absolutePath),
                    totalBytes = 64_000_000_000L,
                    freeBytes = 32_000_000_000L,
                    isMounted = true,
                    needsSafGrant = false,
                ),
            ),
        )
        val deps = MainViewModelDependencies(
            settingsRepository = settingsRepository,
            favoritesRepository = favoritesRepository,
            ftpServersRepository = ftpServersRepository,
            gdriveServersRepository = gdriveServersRepository,
            fileSystem = fs,
            fileOperations = FileOperations(fs, clock),
            zipArchiver = ZipArchiver(fs),
            nomediaManager = NomediaManager(fs, MediaScanNotifier.NOOP),
            operationRegistry = operationRegistry,
            volumes = volumesFlow,
            refreshVolumesAction = {},
            hotspotJoinController = joinController,
            probePeer = probePeer,
            primaryStoragePathProvider = { rootDir.absolutePath },
        )
        return MainViewModel(
            _container = null,
            savedStateHandle = SavedStateHandle(),
            deps = deps,
            scopeOverride = vmScope,
            ioDispatcher = Dispatchers.IO,
        )
    }

    @Test
    fun joinFromQr_supersededOrInvalidQr_releasesActiveWifiJoin() = runBlocking {
        val enteredProbe = CompletableDeferred<Unit>()
        val holdProbe = CompletableDeferred<PeerInfo?>()
        val vm = createViewModel(
            probePeer = { host, _, _, _ ->
                if (host == "127.0.0.1") {
                    enteredProbe.complete(Unit)
                    holdProbe.await()
                } else {
                    null
                }
            },
        )

        // Start a WIFI: QR join that succeeds in join() and then suspends inside findHotspotHost probe.
        vm.joinFromQr("WIFI:T:WPA;S:AndroidShare_1111;P:secret123;;")
        withTimeout(3_000) { enteredProbe.await() }
        assertTrue(vm.isJoinedToPeerHotspot)

        // Supersede with an invalid QR: must cancel prior joinJob AND release the joined hotspot!
        vm.joinFromQr("not-a-qr")
        assertFalse(vm.isJoinedToPeerHotspot)
        assertTrue(joinController.releaseCalls.get() >= 1)
        assertTrue(vm.joinState.value is JoinUiState.Failed)
    }

    @Test
    fun joinFromQr_supersededByHttpQr_releasesPriorWifiJoin() = runBlocking {
        val enteredWifiProbe = CompletableDeferred<Unit>()
        val holdWifiProbe = CompletableDeferred<PeerInfo?>()
        val vm = createViewModel(
            probePeer = { host, port, _, _ ->
                if (host == "127.0.0.1") {
                    enteredWifiProbe.complete(Unit)
                    holdWifiProbe.await()
                } else {
                    PeerInfo("lan-peer", "LAN Host", host, port, "1.0.18", false, PeerSource.QR, 0L)
                }
            },
        )

        vm.joinFromQr("WIFI:T:WPA;S:AndroidShare_2222;P:secret123;;")
        withTimeout(3_000) { enteredWifiProbe.await() }
        assertTrue(vm.isJoinedToPeerHotspot)

        vm.joinFromQr("http://192.168.1.50:8080/")
        withTimeout(3_000) {
            while (vm.joinState.value !is JoinUiState.Found) kotlinx.coroutines.delay(20)
        }
        assertFalse("HTTP QR does not own a Wi-Fi join; prior hotspot join must be released", vm.isJoinedToPeerHotspot)
    }

    @Test
    fun sendToPeer_earlyExits_releaseJoinedHotspotImmediately() {
        val vm = createViewModel()
        val peer = PeerInfo("host-1", "Pixel", "127.0.0.1", 8080, "1.0.18", false, PeerSource.QR, 0L)

        // 1) Empty items list
        joinController.joined = joinController.nextJoined
        vm.sendToPeer(dummyContext, peer, emptyList())
        assertFalse(vm.isJoinedToPeerHotspot)
        assertEquals(1, joinController.releaseCalls.get())

        // 2) Read-only peer
        joinController.joined = joinController.nextJoined
        val f = File(rootDir, "hello.txt").apply { writeText("hi") }
        vm.sendToPeer(dummyContext, peer.copy(readOnly = true), listOf(VfsPath.Local(f.absolutePath)))
        assertFalse(vm.isJoinedToPeerHotspot)
        assertEquals(2, joinController.releaseCalls.get())

        // 3) Cancelled document picker on WirelessScreen (uris.isEmpty())
        joinController.joined = joinController.nextJoined
        vm.releaseJoinedHotspotForPeer(peer, immediate = true)
        assertFalse(vm.isJoinedToPeerHotspot)
        assertEquals(3, joinController.releaseCalls.get())
    }

    @Test
    fun sendToPeer_transferFailure_releasesJoinedHotspotImmediatelyInFinally() = runBlocking {
        val vm = createViewModel()
        val closedPort = ServerSocket(0).use { it.localPort }
        val f = File(rootDir, "payload.bin").apply { writeBytes(ByteArray(32) { 7 }) }
        val peer = PeerInfo("host-err", "Pixel", "127.0.0.1", closedPort, "1.0.18", false, PeerSource.BEACON, 0L)

        joinController.joined = joinController.nextJoined
        val done = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { operationRegistry.completions.first() }
        }
        vm.sendToPeer(dummyContext, peer, listOf(VfsPath.Local(f.absolutePath)))
        val completion = done.await()

        assertTrue(completion.error != null)
        assertFalse("failed send must release joined hotspot in finally", vm.isJoinedToPeerHotspot)
        assertEquals(1, joinController.releaseCalls.get())
        assertEquals(0, joinController.releaseAfterCalls.get())
    }

    @Test
    fun sendToPeer_successAndNullOwnIpFallback_callsReleaseAfter() = runBlocking {
        val recvDir = tempFolder.newFolder("recv")
        val srv = HttpFileServerEngine(
            config = HttpConfig(
                port = 0,
                bindAddress = "127.0.0.1",
                root = VfsPath.Local(recvDir.canonicalPath),
                deviceId = "recv-id",
                deviceName = "Receiver",
            ),
            fs = LocalFileSystem(),
        )
        srv.start()
        try {
            val vm = createViewModel()
            val f = File(rootDir, "ok.txt").apply { writeText("hotspot transfer ok") }
            val peer = PeerInfo("recv-id", "Receiver", "127.0.0.1", srv.boundPort, "1.0.18", false, PeerSource.BEACON, 0L)

            // Simulate DHCP lag where ownIp is null and only gateway is populated.
            joinController.joined = JoinedNetwork(network = null, gateway = "127.0.0.1", ownIp = null, prefixLength = 8)
            val done1 = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) { operationRegistry.completions.first() }
            }
            vm.sendToPeer(dummyContext, peer, listOf(VfsPath.Local(f.absolutePath)))
            assertEquals(null, done1.await().error)
            assertFalse(vm.isJoinedToPeerHotspot)
            assertEquals(1, joinController.releaseAfterCalls.get())

            // Simulate extreme DHCP lag where BOTH ownIp and gateway are null while isJoined == true.
            joinController.joined = JoinedNetwork(network = null, gateway = null, ownIp = null, prefixLength = 24)
            val done2 = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) { operationRegistry.completions.first() }
            }
            vm.sendToPeer(dummyContext, peer, listOf(VfsPath.Local(f.absolutePath)))
            assertEquals(null, done2.await().error)
            assertFalse(vm.isJoinedToPeerHotspot)
            assertEquals(2, joinController.releaseAfterCalls.get())
        } finally {
            srv.stop()
        }
    }
}
