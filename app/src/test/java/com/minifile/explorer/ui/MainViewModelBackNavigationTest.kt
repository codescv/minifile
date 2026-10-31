package com.minifile.explorer.ui

import androidx.lifecycle.SavedStateHandle
import com.minifile.explorer.core.model.Clock
import com.minifile.explorer.core.model.Favorite
import com.minifile.explorer.core.model.InMemoryKeyValueStore
import com.minifile.explorer.core.model.MediaScanNotifier
import com.minifile.explorer.core.ops.FileOperations
import com.minifile.explorer.core.ops.NomediaManager
import com.minifile.explorer.core.ops.ZipArchiver
import com.minifile.explorer.core.vfs.FileSystemRepository
import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.core.vfs.Volume
import com.minifile.explorer.core.vfs.VolumeKind
import com.minifile.explorer.data.DefaultFavoritesRepository
import com.minifile.explorer.data.DefaultFtpServersRepository
import com.minifile.explorer.data.DefaultGDriveServersRepository
import com.minifile.explorer.data.SettingsRepository
import com.minifile.explorer.service.OperationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MainViewModelBackNavigationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var primaryRootFile: File
    private lateinit var primaryRootPath: VfsPath.Local
    private lateinit var vmScope: CoroutineScope

    @Before
    fun setUp() {
        primaryRootFile = tempFolder.newFolder("storage", "emulated", "0")
        primaryRootPath = VfsPath.Local(primaryRootFile.absolutePath)
        vmScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        vmScope.cancel()
    }

    private fun createViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): MainViewModel {
        val store = InMemoryKeyValueStore()
        val clock = Clock { 1_700_000_000_000L }
        val settingsRepository = SettingsRepository(
            store = store,
            defaultDeviceNameProvider = { "TestPhone" },
        )
        val favoritesRepository = DefaultFavoritesRepository(
            store = store,
            clock = clock,
            defaultPrimaryRoot = primaryRootFile.absolutePath,
        )
        val ftpServersRepository = DefaultFtpServersRepository(store = store, clock = clock)
        val gdriveServersRepository = DefaultGDriveServersRepository(store = store, clock = clock)
        val fileSystem = FileSystemRepository(listOf(LocalFileSystem()))
        val fileOperations = FileOperations(fileSystem, clock)
        val zipArchiver = ZipArchiver(fileSystem)
        val nomediaManager = NomediaManager(fileSystem, MediaScanNotifier.NOOP)
        val operationRegistry = OperationRegistry()
        val volumesFlow = MutableStateFlow(
            listOf(
                Volume(
                    id = "primary",
                    name = "Internal",
                    kind = VolumeKind.PRIMARY,
                    root = primaryRootPath,
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
            fileSystem = fileSystem,
            fileOperations = fileOperations,
            zipArchiver = zipArchiver,
            nomediaManager = nomediaManager,
            operationRegistry = operationRegistry,
            volumes = volumesFlow,
            refreshVolumesAction = {},
            primaryStoragePathProvider = { primaryRootFile.absolutePath },
        )
        return MainViewModel(
            _container = null,
            savedStateHandle = savedStateHandle,
            deps = deps,
            scopeOverride = vmScope,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun addFolderFavorite(vm: MainViewModel, name: String, dir: File): Favorite {
        dir.mkdirs()
        val path = VfsPath.Local(dir.absolutePath)
        vm.addFavorite(name = name, path = path, isDirectory = true)
        return vm.favorites.value.first { it.path == path }
    }

    private fun addFileFavorite(vm: MainViewModel, name: String, file: File, content: String = "hello"): Favorite {
        file.parentFile?.mkdirs()
        file.writeText(content)
        val path = VfsPath.Local(file.absolutePath)
        vm.addFavorite(name = name, path = path, isDirectory = false)
        return vm.favorites.value.first { it.path == path }
    }

    @Test
    fun openFavoriteFolderFromStorageHome_backReturnsDirectlyToStorageHome() = runBlocking {
        val vm = createViewModel()
        val deepFavDir = File(primaryRootFile, "Work/Projects/2026/TargetFolder")
        val fav = addFolderFavorite(vm, "TargetFolder", deepFavDir)

        assertNull(vm.browser.value.currentDir)
        assertNull(vm.overlay.value)

        // 1. Hardware Back via handleBack() returns directly to StorageHomeScreen (not parent folder)
        vm.openFavoriteInBrowse(fav)
        assertEquals(VfsPath.Local(deepFavDir.absolutePath), vm.browser.value.currentDir)
        assertNull(vm.overlay.value)
        assertTrue(vm.canHandleBack())

        vm.handleBack()
        assertNull(vm.browser.value.currentDir)
        assertNull(vm.overlay.value)
        assertFalse(vm.canHandleBack())

        // 2. Top-bar ArrowBack via navigateUpOrHome() also returns directly to StorageHomeScreen
        vm.openFavoriteInBrowse(fav)
        assertEquals(VfsPath.Local(deepFavDir.absolutePath), vm.browser.value.currentDir)

        vm.navigateUpOrHome()
        assertNull(vm.browser.value.currentDir)
        assertNull(vm.overlay.value)
    }

    @Test
    fun openFavoriteFolderThenSubfolder_twoBacksReturnToFavoriteFolderThenStorageHome() = runBlocking {
        val vm = createViewModel()
        val deepFavDir = File(primaryRootFile, "Documents/Clients/Acme")
        val childSubDir = File(deepFavDir, "Invoices").apply { mkdirs() }
        val fav = addFolderFavorite(vm, "Acme", deepFavDir)

        vm.openFavoriteInBrowse(fav)
        val favPath = VfsPath.Local(deepFavDir.absolutePath)
        val subPath = VfsPath.Local(childSubDir.absolutePath)
        assertEquals(favPath, vm.browser.value.currentDir)

        vm.navigateToDirectory(subPath)
        assertEquals(subPath, vm.browser.value.currentDir)

        // First Back -> returns to the Favorite folder
        vm.handleBack()
        assertEquals(favPath, vm.browser.value.currentDir)
        assertNull(vm.overlay.value)

        // Second Back -> returns directly to StorageHomeScreen (skipping Clients and Documents)
        vm.handleBack()
        assertNull(vm.browser.value.currentDir)
        assertNull(vm.overlay.value)
    }

    @Test
    fun openSeeAllFavoritesThenFavoriteFolder_backReturnsToSeeAllFavoritesThenStorageHome() = runBlocking {
        val vm = createViewModel()
        val deepFavDir = File(primaryRootFile, "Media/Photos/Trips/Japan")
        val childDayDir = File(deepFavDir, "Day1").apply { mkdirs() }
        val fav = addFolderFavorite(vm, "Japan Trip", deepFavDir)

        // Open See all -> OverlayScreen.Favorites
        vm.openOverlay(OverlayScreen.Favorites)
        assertEquals(OverlayScreen.Favorites, vm.overlay.value)
        assertNull(vm.browser.value.currentDir)

        // Open Favorite folder from See all
        vm.openFavoriteInBrowse(fav)
        val favPath = VfsPath.Local(deepFavDir.absolutePath)
        assertEquals(favPath, vm.browser.value.currentDir)
        assertNull(vm.overlay.value)

        // Drill into a child folder as well, then unwind all the way back
        val day1Path = VfsPath.Local(childDayDir.absolutePath)
        vm.navigateToDirectory(day1Path)
        assertEquals(day1Path, vm.browser.value.currentDir)

        // Back 1 -> Favorite folder
        vm.handleBack()
        assertEquals(favPath, vm.browser.value.currentDir)
        assertNull(vm.overlay.value)

        // Back 2 -> OverlayScreen.Favorites (See all), with currentDir == null
        vm.handleBack()
        assertNull(vm.browser.value.currentDir)
        assertEquals(OverlayScreen.Favorites, vm.overlay.value)

        // Back 3 -> StorageHomeScreen (overlay == null, currentDir == null)
        vm.handleBack()
        assertNull(vm.browser.value.currentDir)
        assertNull(vm.overlay.value)
    }

    @Test
    fun openFavoriteFileFromStorageHome_opensOverlayWithoutChangingCurrentDirAndBackReturnsToStorageHome() = runBlocking {
        val vm = createViewModel()
        val pdfFile = File(primaryRootFile, "Books/Papers/paper.pdf")
        val txtFile = File(primaryRootFile, "Notes/Deep/todo.txt")
        val zipFile = File(primaryRootFile, "Backups/2026/archive.zip")

        val pdfFav = addFileFavorite(vm, "Paper PDF", pdfFile, "%PDF-1.4")
        val txtFav = addFileFavorite(vm, "Todo TXT", txtFile, "buy milk\n")
        val zipFav = addFileFavorite(vm, "Backup ZIP", zipFile, "PK\u0005\u0006" + "\u0000".repeat(18))

        // PDF favorite from Recent Favorites on StorageHomeScreen
        vm.openFavoriteInBrowse(pdfFav)
        assertNull(vm.browser.value.currentDir)
        assertEquals(OverlayScreen.PdfViewer(VfsPath.Local(pdfFile.absolutePath)), vm.overlay.value)

        vm.handleBack()
        assertNull(vm.browser.value.currentDir)
        assertNull(vm.overlay.value)

        // TXT favorite from Recent Favorites on StorageHomeScreen
        vm.openFavoriteInBrowse(txtFav)
        assertNull(vm.browser.value.currentDir)
        assertEquals(OverlayScreen.TextEditor(VfsPath.Local(txtFile.absolutePath)), vm.overlay.value)

        vm.handleBack()
        assertNull(vm.browser.value.currentDir)
        assertNull(vm.overlay.value)

        // ZIP favorite from Recent Favorites on StorageHomeScreen
        vm.openFavoriteInBrowse(zipFav)
        assertNull(vm.browser.value.currentDir)
        assertEquals(OverlayScreen.ArchivePreview(VfsPath.Local(zipFile.absolutePath)), vm.overlay.value)

        vm.handleBack()
        assertNull(vm.browser.value.currentDir)
        assertNull(vm.overlay.value)
    }

    @Test
    fun openSeeAllFavoritesThenFavoriteFile_backReturnsToSeeAllFavoritesThenStorageHome() = runBlocking {
        val vm = createViewModel()
        val pdfFile = File(primaryRootFile, "Docs/Sub/manual.pdf")
        val zipFile = File(primaryRootFile, "Releases/v1/bundle.zip")
        val txtFile = File(primaryRootFile, "Logs/app/readme.md")

        val pdfFav = addFileFavorite(vm, "Manual", pdfFile, "%PDF-1.4")
        val zipFav = addFileFavorite(vm, "Bundle", zipFile, "PK\u0005\u0006" + "\u0000".repeat(18))
        val txtFav = addFileFavorite(vm, "Readme", txtFile, "# Hello\n")

        for ((fav, expectedOverlay) in listOf(
            pdfFav to OverlayScreen.PdfViewer(VfsPath.Local(pdfFile.absolutePath)),
            zipFav to OverlayScreen.ArchivePreview(VfsPath.Local(zipFile.absolutePath)),
            txtFav to OverlayScreen.TextEditor(VfsPath.Local(txtFile.absolutePath)),
        )) {
            vm.openOverlay(OverlayScreen.Favorites)
            assertEquals(OverlayScreen.Favorites, vm.overlay.value)
            assertNull(vm.browser.value.currentDir)

            vm.openFavoriteInBrowse(fav)
            assertNull(vm.browser.value.currentDir)
            assertEquals(expectedOverlay, vm.overlay.value)

            // First Back -> returns directly to OverlayScreen.Favorites (See all)
            vm.handleBack()
            assertNull(vm.browser.value.currentDir)
            assertEquals(OverlayScreen.Favorites, vm.overlay.value)

            // Second Back -> returns to StorageHomeScreen
            vm.handleBack()
            assertNull(vm.browser.value.currentDir)
            assertNull(vm.overlay.value)
        }
    }

    @Test
    fun breadcrumbAncestorTruncationAndColdProcessRestoreFallbackWorkCleanly() = runBlocking {
        val vm = createViewModel()
        val folderA = File(primaryRootFile, "FolderA").apply { mkdirs() }
        val folderB = File(folderA, "FolderB").apply { mkdirs() }
        val pathA = VfsPath.Local(folderA.absolutePath)
        val pathB = VfsPath.Local(folderB.absolutePath)

        // Normal browse: StorageHome -> Internal -> FolderA -> FolderB
        vm.openVolume(vm.volumes.value.single())
        assertEquals(primaryRootPath, vm.browser.value.currentDir)
        vm.navigateToDirectory(pathA)
        vm.navigateToDirectory(pathB)
        assertEquals(pathB, vm.browser.value.currentDir)

        // User taps Internal root breadcrumb -> truncates history so Back goes straight to StorageHome
        vm.navigateToDirectory(primaryRootPath)
        assertEquals(primaryRootPath, vm.browser.value.currentDir)
        vm.handleBack()
        assertNull(vm.browser.value.currentDir)

        // Cold process-death restore into deep folder -> browserBackStack starts empty and walks up parents
        val restoredHandle = SavedStateHandle(
            mapOf(
                "saved_dir" to pathB.serialize(),
                "saved_root_name" to "Internal",
                "saved_root_base" to primaryRootPath.serialize(),
            ),
        )
        val restoredVm = createViewModel(savedStateHandle = restoredHandle)
        assertEquals(pathB, restoredVm.browser.value.currentDir)

        restoredVm.handleBack()
        assertEquals(pathA, restoredVm.browser.value.currentDir)

        restoredVm.handleBack()
        assertEquals(primaryRootPath, restoredVm.browser.value.currentDir)

        restoredVm.handleBack()
        assertNull(restoredVm.browser.value.currentDir)
    }
}
