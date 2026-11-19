package com.minifile.explorer.ui

import androidx.lifecycle.SavedStateHandle
import com.minifile.explorer.core.model.Clock
import com.minifile.explorer.core.model.GDriveAuthMode
import com.minifile.explorer.core.model.GDriveServerBookmark
import com.minifile.explorer.core.model.InMemoryKeyValueStore
import com.minifile.explorer.core.model.MediaScanNotifier
import com.minifile.explorer.core.ops.FileOperations
import com.minifile.explorer.core.ops.NomediaManager
import com.minifile.explorer.core.ops.ZipArchiver
import com.minifile.explorer.core.share.ShareImportSource
import com.minifile.explorer.core.vfs.FileSystemRepository
import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.core.vfs.Volume
import com.minifile.explorer.core.vfs.VolumeKind
import com.minifile.explorer.data.DefaultFavoritesRepository
import com.minifile.explorer.data.DefaultFtpServersRepository
import com.minifile.explorer.data.DefaultGDriveServersRepository
import com.minifile.explorer.data.SettingsRepository
import com.minifile.explorer.data.share.IncomingSharePayload
import com.minifile.explorer.service.OperationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MainViewModelShareDestinationsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var primaryRoot: File
    private lateinit var vmScope: CoroutineScope

    @Before
    fun setUp() {
        primaryRoot = tempFolder.newFolder("storage", "emulated", "0")
        vmScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        vmScope.cancel()
    }

    @Test
    fun quickDestinationsIncludeReceivedDownloadsFtpGDriveAndFavorites() = runBlocking {
        val store = InMemoryKeyValueStore()
        val clock = Clock { 1_700_000_000_000L }
        val settingsRepo = SettingsRepository(store) { "Phone" }
        val favRepo = DefaultFavoritesRepository(store, clock, primaryRoot.absolutePath)
        val ftpRepo = DefaultFtpServersRepository(store, clock)
        val gdriveRepo = DefaultGDriveServersRepository(store, clock)

        val ftp = ftpRepo.add(
            name = "Home NAS",
            host = "192.168.1.50",
            port = 2121,
            anonymous = true,
            username = "anonymous",
            password = "",
            initialPath = "/uploads",
        )
        val gd = gdriveRepo.add(
            GDriveServerBookmark(
                id = "gd-work",
                name = "Work Drive",
                email = "user@example.com",
                authMode = GDriveAuthMode.PASTED_TOKEN,
                initialPath = "/",
            ),
        )

        val fs = FileSystemRepository(listOf(LocalFileSystem()))
        val deps = MainViewModelDependencies(
            settingsRepository = settingsRepo,
            favoritesRepository = favRepo,
            ftpServersRepository = ftpRepo,
            gdriveServersRepository = gdriveRepo,
            fileSystem = fs,
            fileOperations = FileOperations(fs, clock),
            zipArchiver = ZipArchiver(fs),
            nomediaManager = NomediaManager(fs, MediaScanNotifier.NOOP),
            operationRegistry = OperationRegistry(),
            volumes = MutableStateFlow(
                listOf(
                    Volume(
                        id = "primary",
                        name = "Internal",
                        kind = VolumeKind.PRIMARY,
                        root = VfsPath.Local(primaryRoot.absolutePath),
                    ),
                ),
            ),
            refreshVolumesAction = {},
            primaryStoragePathProvider = { primaryRoot.absolutePath },
        )

        val vm = MainViewModel(
            _container = null,
            savedStateHandle = SavedStateHandle(),
            deps = deps,
            scopeOverride = vmScope,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val quick = vm.buildShareQuickDestinations()
        assertTrue(quick.any { it.kind == ShareDestinationKind.RECEIVED && it.label == "Received" })
        assertTrue(quick.any { it.kind == ShareDestinationKind.DOWNLOADS && it.label == "Downloads" })
        assertTrue(quick.any { it.kind == ShareDestinationKind.GDRIVE && it.path == gd.rootPath && it.label == "Work Drive" })
        assertTrue(quick.any { it.kind == ShareDestinationKind.FTP && it.path == ftp.rootPath && it.label == "Home NAS" })

        assertEquals("Work Drive", vm.friendlyShareDestinationLabel(VfsPath.GDrive(gd.id, "/")))
        assertEquals("Work Drive / Scans", vm.friendlyShareDestinationLabel(VfsPath.GDrive(gd.id, "/Scans")))
        assertEquals("Home NAS / uploads", vm.friendlyShareDestinationLabel(VfsPath.Ftp(ftp.id, "/uploads")))
    }

    @Test
    fun queuesSecondShareWhileSavingAndPresentsAfterFirstCompletes() = runBlocking {
        val store = InMemoryKeyValueStore()
        val clock = Clock { 1_700_000_000_000L }
        val fs = FileSystemRepository(listOf(LocalFileSystem()))
        val deps = MainViewModelDependencies(
            settingsRepository = SettingsRepository(store) { "Phone" },
            favoritesRepository = DefaultFavoritesRepository(store, clock, primaryRoot.absolutePath),
            ftpServersRepository = DefaultFtpServersRepository(store, clock),
            gdriveServersRepository = DefaultGDriveServersRepository(store, clock),
            fileSystem = fs,
            fileOperations = FileOperations(fs, clock),
            zipArchiver = ZipArchiver(fs),
            nomediaManager = NomediaManager(fs, MediaScanNotifier.NOOP),
            operationRegistry = OperationRegistry(),
            volumes = MutableStateFlow(emptyList()),
            refreshVolumesAction = {},
            primaryStoragePathProvider = { primaryRoot.absolutePath },
        )
        val vm = MainViewModel(
            _container = null,
            savedStateHandle = SavedStateHandle(),
            deps = deps,
            scopeOverride = vmScope,
            ioDispatcher = Dispatchers.IO,
        )

        val firstPayload = IncomingSharePayload.Text(
            text = "first",
            subject = "first",
            suggestedFileName = "first.txt",
            byteCount = 5L,
            previewText = "first",
        )
        val secondPayload = IncomingSharePayload.Text(
            text = "second",
            subject = "second",
            suggestedFileName = "second.txt",
            byteCount = 6L,
            previewText = "second",
        )

        vm.presentOrQueueIncomingPayload(firstPayload)
        assertEquals("first.txt", vm.saveSharedState.value?.editableFileName)

        val startedLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val blockingSource = ShareImportSource(
            desiredName = "first.txt",
            sizeBytes = 5L,
            mimeType = "text/plain",
            openInput = {
                object : InputStream() {
                    private val delegate = ByteArrayInputStream("first".toByteArray())
                    override fun read(): Int {
                        startedLatch.countDown()
                        releaseLatch.await(5, TimeUnit.SECONDS)
                        return delegate.read()
                    }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        startedLatch.countDown()
                        releaseLatch.await(5, TimeUnit.SECONDS)
                        return delegate.read(b, off, len)
                    }
                }
            },
        )

        vm.startSaveSharedSources(listOf(blockingSource))
        assertTrue("First save should start on IO", startedLatch.await(5, TimeUnit.SECONDS))
        assertTrue(vm.saveSharedState.value?.isSaving == true)

        // Second share arrives while first save is active — must be queued, not dropped or clobbering
        vm.presentOrQueueIncomingPayload(secondPayload)
        assertEquals("first.txt", vm.saveSharedState.value?.editableFileName)
        assertTrue(vm.saveSharedState.value?.isSaving == true)

        // Release first save; second share should automatically appear once first completes
        releaseLatch.countDown()
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            if (vm.saveSharedState.value?.editableFileName == "second.txt") break
            Thread.sleep(20L)
        }
        val current = vm.saveSharedState.value
        assertNotNull(current)
        assertEquals("second.txt", current?.editableFileName)
        assertEquals("first", File(primaryRoot, "Received/first.txt").readText())
    }
}
