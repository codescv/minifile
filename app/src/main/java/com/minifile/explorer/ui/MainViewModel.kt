package com.minifile.explorer.ui

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.minifile.explorer.core.gallery.BrowseLens
import com.minifile.explorer.core.gallery.FolderLensOverride
import com.minifile.explorer.core.gallery.GalleryMutation
import com.minifile.explorer.core.gallery.GalleryRepository
import com.minifile.explorer.core.gallery.GalleryScope
import com.minifile.explorer.core.gallery.GalleryScopePolicy
import com.minifile.explorer.core.gdrive.GDriveAuthException
import com.minifile.explorer.core.gdrive.revokeOAuthTokenBestEffort
import com.minifile.explorer.core.i18n.AppI18n
import com.minifile.explorer.core.i18n.Str
import com.minifile.explorer.core.model.AppSettings
import com.minifile.explorer.core.model.Favorite
import com.minifile.explorer.core.model.FtpServerBookmark
import com.minifile.explorer.core.model.GDriveAuthMode
import com.minifile.explorer.core.model.GDriveServerBookmark
import com.minifile.explorer.core.model.MediaScanNotifier
import com.minifile.explorer.core.model.OperationKind
import com.minifile.explorer.core.model.SortSpec
import com.minifile.explorer.core.model.TransferMode
import com.minifile.explorer.core.model.ViewMode
import com.minifile.explorer.ui.gallery.GalleryController
import com.minifile.explorer.core.ops.ConflictResolution
import com.minifile.explorer.core.ops.ConflictResolver
import com.minifile.explorer.core.ops.FileOperations
import com.minifile.explorer.core.ops.NomediaManager
import com.minifile.explorer.core.ops.ProgressSink
import com.minifile.explorer.core.ops.SearchHit
import com.minifile.explorer.core.ops.ZipArchiver
import com.minifile.explorer.core.peer.HostDecision
import com.minifile.explorer.core.peer.HotspotLinkCodec
import com.minifile.explorer.core.peer.PeerHttpClient
import com.minifile.explorer.core.peer.PeerInfo
import com.minifile.explorer.core.peer.PeerMerge
import com.minifile.explorer.core.peer.PeerSource
import com.minifile.explorer.core.qr.QrPayload
import com.minifile.explorer.core.qr.QrPayloads
import com.minifile.explorer.core.peer.PeerConflictPolicy
import com.minifile.explorer.core.peer.PeerPathPolicy
import com.minifile.explorer.core.peer.PeerSendItem
import com.minifile.explorer.core.peer.PeerSendFallback
import com.minifile.explorer.core.peer.PeerSendResult
import com.minifile.explorer.core.peer.PeerTransferEngine
import com.minifile.explorer.core.peer.PeerTransferException
import com.minifile.explorer.core.peer.ReannounceRetry
import com.minifile.explorer.core.share.ShareImportEngine
import com.minifile.explorer.core.share.ShareImportProgress
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.NameValidation
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.core.vfs.Volume
import com.minifile.explorer.core.vfs.VolumeKind
import com.minifile.explorer.data.FavoritesRepository
import com.minifile.explorer.data.FtpServersRepository
import com.minifile.explorer.data.GDriveServersRepository
import com.minifile.explorer.data.SettingsRepository
import com.minifile.explorer.data.gdrive.AndroidAccountTokenProvider
import com.minifile.explorer.data.gdrive.GDriveSignInState
import com.minifile.explorer.data.share.IncomingSharePayload
import com.minifile.explorer.data.share.RawShareIntent
import com.minifile.explorer.data.share.ShareIntentExtractor
import com.minifile.explorer.di.AppContainer
import com.minifile.explorer.service.BleAvailability
import com.minifile.explorer.service.BleLinkUiState
import com.minifile.explorer.service.HotspotException
import com.minifile.explorer.service.HttpServerHolder
import com.minifile.explorer.service.HotspotJoinController
import com.minifile.explorer.service.HotspotPurpose
import com.minifile.explorer.service.HotspotState
import com.minifile.explorer.service.IncomingBleRequest
import com.minifile.explorer.service.JoinException
import com.minifile.explorer.service.LinkResult
import com.minifile.explorer.service.OperationCompletionEvent
import com.minifile.explorer.service.OperationRegistry
import com.minifile.explorer.service.OperationRunOutcome
import com.minifile.explorer.service.PeerOfferApprovals
import com.minifile.explorer.ui.util.UiFormatters
import java.io.FileNotFoundException
import java.net.InetAddress
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MainTab(val label: String) {
    BROWSE("Browse"),
    WIRELESS("Transfer"),
    TOOLS("Settings"),
}

sealed interface OverlayScreen {
    data object Favorites : OverlayScreen
    data class TextEditor(val path: VfsPath, val forceEdit: Boolean = false) : OverlayScreen
    data class ArchivePreview(val path: VfsPath) : OverlayScreen
    data class PdfViewer(val path: VfsPath) : OverlayScreen
    data class MediaViewer(val scope: GalleryScope, val startPath: VfsPath) : OverlayScreen
    data class MediaEditor(val path: VfsPath) : OverlayScreen
}

data class BreadcrumbCrumb(
    val label: String,
    val path: VfsPath?, // null = Storage Home
)

data class ConflictPrompt(
    val source: VfsNode,
    val existing: VfsNode,
    val deferred: CompletableDeferred<Pair<ConflictResolution, Boolean>>,
)

data class BrowserUiState(
    val currentDir: VfsPath? = null, // null = Storage Home
    val currentDirNode: VfsNode? = null,
    val rootVolumeName: String = "Internal",
    val rootVolumeBase: VfsPath? = null,
    val breadcrumbs: List<BreadcrumbCrumb> = emptyList(),
    val rawEntries: List<VfsNode> = emptyList(),
    val visibleEntries: List<VfsNode> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val permissionRequired: Boolean = false,
    val searchActive: Boolean = false,
    val searchQuery: String = "",
    val recursiveSearch: Boolean = false,
    val isRecursiveSearching: Boolean = false,
    val recursiveHits: List<SearchHit> = emptyList(),
    val selectedPaths: Set<VfsPath> = emptySet(),
    val lastToggledPath: VfsPath? = null,
    val lens: BrowseLens = BrowseLens.FILES,
    val galleryScope: GalleryScope? = null,
    val isMediaRootCandidate: Boolean = false,
) {
    val isSelectionMode: Boolean get() = selectedPaths.isNotEmpty()
    val isGalleryLens: Boolean get() = lens == BrowseLens.GALLERY && galleryScope != null
}

private data class BrowserBackEntry(
    val dir: VfsPath?,
    val rootVolumeName: String,
    val rootVolumeBase: VfsPath?,
    val overlay: OverlayScreen?,
    val tab: MainTab,
)

internal class MainViewModelDependencies(
    val settingsRepository: SettingsRepository,
    val favoritesRepository: FavoritesRepository,
    val ftpServersRepository: FtpServersRepository,
    val gdriveServersRepository: GDriveServersRepository,
    val fileSystem: FileSystem,
    val fileOperations: FileOperations,
    val zipArchiver: ZipArchiver,
    val nomediaManager: NomediaManager,
    val operationRegistry: OperationRegistry,
    val volumes: StateFlow<List<Volume>>,
    val refreshVolumesAction: suspend () -> Unit,
    val gdriveSignInState: StateFlow<GDriveSignInState> = MutableStateFlow(GDriveSignInState.Idle),
    val peers: StateFlow<List<PeerInfo>> = MutableStateFlow(emptyList()),
    val isScanningPeers: StateFlow<Boolean> = MutableStateFlow(false),
    val invalidateGDriveServer: (String) -> Unit = {},
    val invalidateGDrivePath: (VfsPath.GDrive) -> Unit = {},
    val peekGDriveCachedToken: (String) -> String? = { null },
    val cancelGDriveSignIn: () -> Unit = {},
    val resetGDriveSignIn: () -> Unit = {},
    val findSafGrantRoot: (String) -> VfsPath.Saf? = { null },
    val hotspotJoinController: HotspotJoinController = HotspotJoinController.NOOP,
    val peerSocketBinder: (Socket, InetAddress) -> Unit = { _, _ -> },
    val probePeer: suspend (host: String, port: Int, source: PeerSource, timeoutMs: Int) -> PeerInfo? =
        { _, _, _, _ -> null },
    val upsertPeer: (PeerInfo) -> Unit = {},
    val scanPeersSubnetAction: () -> Unit = {},
    val cancelBleLinkAction: () -> Unit = {},
    /** Shows the BLE sender dialog at t = 0 ("Preparing files…") before files are enumerated. */
    val beginBleSendAction: (peerName: String) -> Unit = {},
    /** Ends the "Preparing files…" dialog when sending stopped before negotiation (error ⇒ visible + Close). */
    val endBleSendPreparingAction: (peerName: String, error: String?) -> Unit = { _, _ -> },
    /** `true` when the same device as a LAN peer is in Bluetooth range (automatic LAN → BLE fallback). */
    val hasBleRouteTo: (PeerInfo) -> Boolean = { false },
    /** Negotiates a BLE link to a peer (`lanHttpBlocked` = LAN HTTP was just intercepted / unreachable). */
    val negotiateBleLink: suspend (peer: PeerInfo, files: Int, bytes: Long, lanHttpBlocked: Boolean) -> LinkResult =
        { _, _, _, _ -> throw PeerTransferException(null, AppI18n.current()[Str.BLUETOOTH_UNAVAILABLE]) },
    /** Registers (`null` clears) the abort hook the link dialog's Cancel uses while an HTTP send runs over a link. */
    val setLinkSendAbort: ((() -> Unit)?) -> Unit = {},
    /** Wraps a sink so HTTP byte progress over a negotiated link is mirrored into the link dialog. */
    val linkProgressSink: (ProgressSink) -> ProgressSink = { it },
    /** Updates the sender link dialog's status line. */
    val linkPhase: (String) -> Unit = {},
    /** `true` when a VPN (`TRANSPORT_VPN`) is active — words interception errors. */
    val isVpnActive: () -> Boolean = { false },
    val primaryStoragePathProvider: () -> String = {
        Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
    },
    val shareImportEngine: ShareImportEngine = ShareImportEngine(fileSystem),
    val mediaScanNotifier: MediaScanNotifier = MediaScanNotifier.NOOP,
    val galleryRepository: GalleryRepository = GalleryRepository(fileSystem, mediaScanNotifier = mediaScanNotifier),
) {
    companion object {
        fun fromContainer(container: AppContainer): MainViewModelDependencies =
            MainViewModelDependencies(
                settingsRepository = container.settingsRepository,
                favoritesRepository = container.favoritesRepository,
                ftpServersRepository = container.ftpServersRepository,
                gdriveServersRepository = container.gdriveServersRepository,
                fileSystem = container.fileSystem,
                fileOperations = container.fileOperations,
                zipArchiver = container.zipArchiver,
                nomediaManager = container.nomediaManager,
                operationRegistry = container.operationRegistry,
                volumes = container.volumeProvider.volumes,
                refreshVolumesAction = { container.volumeProvider.refresh() },
                gdriveSignInState = container.gdriveSignInController.state,
                peers = container.peerDiscoveryHolder.peers,
                isScanningPeers = container.peerDiscoveryHolder.isScanning,
                invalidateGDriveServer = { container.googleDriveFileSystem.invalidateServer(it) },
                invalidateGDrivePath = { container.googleDriveFileSystem.invalidate(it) },
                peekGDriveCachedToken = { container.googleDriveFileSystem.peekCachedToken(it) },
                cancelGDriveSignIn = { container.gdriveSignInController.cancel() },
                resetGDriveSignIn = { container.gdriveSignInController.reset() },
                findSafGrantRoot = { treeUri ->
                    container.persistedTreeStore.findGrantByTreeUri(treeUri)?.toVfsPath()
                },
                hotspotJoinController = container.wifiSpecifierConnector,
                peerSocketBinder = container.peerDiscoveryHolder::bindTcpSocket,
                probePeer = { host, port, source, timeoutMs ->
                    container.peerDiscoveryHolder.engine.probe(host = host, port = port, source = source, timeoutMs = timeoutMs)
                },
                upsertPeer = { container.peerDiscoveryHolder.engine.upsert(it) },
                scanPeersSubnetAction = { container.peerDiscoveryHolder.scanSubnet() },
                cancelBleLinkAction = { container.blePeerCoordinator.cancelLink() },
                beginBleSendAction = { container.blePeerCoordinator.beginSenderPreparing(it) },
                endBleSendPreparingAction = { name, error -> container.blePeerCoordinator.endSenderPreparing(name, error) },
                hasBleRouteTo = { container.blePeerCoordinator.hasBleRouteTo(it) },
                negotiateBleLink = { peer, files, bytes, lanHttpBlocked ->
                    container.blePeerCoordinator.negotiate(peer, files, bytes, lanHttpBlocked)
                },
                setLinkSendAbort = { container.blePeerCoordinator.setActiveSendAbort(it) },
                linkProgressSink = { container.blePeerCoordinator.linkProgressSink(it) },
                linkPhase = { container.blePeerCoordinator.updateSenderPhase(it) },
                isVpnActive = { container.peerDiscoveryHolder.isVpnActive() },
                shareImportEngine = container.shareImportEngine,
                mediaScanNotifier = container.mediaScanNotifier,
                galleryRepository = container.galleryRepository,
            )
    }
}

class MainViewModel internal constructor(
    private val _container: AppContainer?,
    private val savedStateHandle: SavedStateHandle,
    private val deps: MainViewModelDependencies,
    private val scopeOverride: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    constructor(
        container: AppContainer,
        savedStateHandle: SavedStateHandle,
    ) : this(
        _container = container,
        savedStateHandle = savedStateHandle,
        deps = MainViewModelDependencies.fromContainer(container),
    )

    val container: AppContainer
        get() = checkNotNull(_container) { "AppContainer is not attached in headless unit test mode" }

    private val vmScope: CoroutineScope
        get() = scopeOverride ?: viewModelScope

    companion object {
        private const val STATE_TAB = "saved_tab"
        private const val STATE_DIR = "saved_dir"
        private const val STATE_ROOT_NAME = "saved_root_name"
        private const val STATE_ROOT_BASE = "saved_root_base"
        private const val STATE_LENS = "saved_lens"
        private const val STATE_GALLERY_SCOPE = "saved_gallery_scope"

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    val handle = extras.createSavedStateHandle()
                    return MainViewModel(container, handle) as T
                }
            }
    }

    private val overlayBackStack = ArrayDeque<OverlayScreen>()
    private val browserBackStack = ArrayDeque<BrowserBackEntry>()

    private val _activeTab = MutableStateFlow(restoreInitialTab())
    val activeTab: StateFlow<MainTab> = _activeTab.asStateFlow()

    private val _overlay = MutableStateFlow<OverlayScreen?>(null)
    val overlay: StateFlow<OverlayScreen?> = _overlay.asStateFlow()

    private val _browser = MutableStateFlow(BrowserUiState())
    val browser: StateFlow<BrowserUiState> = _browser.asStateFlow()

    private val _conflictPrompt = MutableStateFlow<ConflictPrompt?>(null)
    val conflictPrompt: StateFlow<ConflictPrompt?> = _conflictPrompt.asStateFlow()

    private val _snackbarMessages = MutableSharedFlow<OperationCompletionEvent>(extraBufferCapacity = 16)
    val snackbarMessages: SharedFlow<OperationCompletionEvent> = _snackbarMessages.asSharedFlow()

    val settings: StateFlow<AppSettings> = deps.settingsRepository.settings
    val favorites: StateFlow<List<Favorite>> = deps.favoritesRepository.favorites

    val galleryController: GalleryController by lazy {
        GalleryController(
            scope = vmScope,
            settingsRepository = deps.settingsRepository,
            repository = deps.galleryRepository,
            mediaStoreIndexSource = _container?.mediaStoreIndexSource,
            mediaStoreObserver = _container?.mediaStoreObserver,
            geoIndexStore = _container?.geoIndexStore,
            exifGeoReader = _container?.exifGeoReader,
            ioDispatcher = ioDispatcher,
        )
    }
    val ftpServers: StateFlow<List<FtpServerBookmark>> = deps.ftpServersRepository.servers
    val gdriveServers: StateFlow<List<GDriveServerBookmark>> = deps.gdriveServersRepository.servers
    val gdriveSignInState: StateFlow<GDriveSignInState> = deps.gdriveSignInState
    val volumes: StateFlow<List<Volume>> = deps.volumes
    val clipboard = ClipboardHolder.state
    /** LAN (UDP/NSD/probe) peers merged with passive BLE peers, deduped by `id6` with LAN winning (spec §3.7). */
    val peers: StateFlow<List<PeerInfo>> = _container?.let { c ->
        combine(deps.peers, c.blePeerCoordinator.blePeers) { lan, ble -> PeerMerge.merge(lan, ble) }
            .stateIn(vmScope, SharingStarted.Eagerly, deps.peers.value)
    } ?: deps.peers
    val isScanningPeers: StateFlow<Boolean> = deps.isScanningPeers

    // --- v1.0.18 Direct Hotspot / BLE / QR state ---
    val hotspotState: StateFlow<HotspotState> =
        _container?.localOnlyHotspotManager?.state ?: MutableStateFlow(HotspotState.Idle)
    /** Pending incoming offer for the consent dialog: BLE hello first, else a LAN `begin` (Require Approval). */
    val pendingIncomingBle: StateFlow<IncomingBleRequest?> = _container?.let { c ->
        combine(c.blePeerCoordinator.pendingIncoming, PeerOfferApprovals.instance.pendingRequest) { ble, lan -> ble ?: lan }
            .stateIn(vmScope, SharingStarted.Eagerly, c.blePeerCoordinator.pendingIncoming.value)
    } ?: MutableStateFlow(null)
    val bleLinkUi: StateFlow<BleLinkUiState?> =
        _container?.blePeerCoordinator?.linkUi ?: MutableStateFlow(null)

    /** Bluetooth QR-free discovery readiness (UNSUPPORTED in headless unit tests without an AppContainer). */
    val bleAvailability: StateFlow<BleAvailability> =
        _container?.blePeerCoordinator?.availability ?: MutableStateFlow(BleAvailability.UNSUPPORTED)

    private val _joinState = MutableStateFlow<JoinUiState?>(null)
    val joinState: StateFlow<JoinUiState?> = _joinState.asStateFlow()
    private var joinJob: Job? = null
    private var peerBrowseCount = 0

    private val _reauthGDriveBookmark = MutableStateFlow<GDriveServerBookmark?>(null)
    val reauthGDriveBookmark: StateFlow<GDriveServerBookmark?> = _reauthGDriveBookmark.asStateFlow()

    private val _saveSharedState = MutableStateFlow<SaveSharedUiState?>(null)
    val saveSharedState: StateFlow<SaveSharedUiState?> = _saveSharedState.asStateFlow()
    private val pendingIncomingShares = ArrayDeque<PendingIncomingShare>()
    private var shareResolveJob: Job? = null
    private var shareSaveJob: Job? = null
    private var shareSaveCancelled = java.util.concurrent.atomic.AtomicBoolean(false)

    private sealed interface PendingIncomingShare {
        data class Raw(val appContext: Context, val raw: RawShareIntent) : PendingIncomingShare
        data class Resolved(val payload: IncomingSharePayload) : PendingIncomingShare
    }

    private var directoryLoadJob: Job? = null
    private var recursiveSearchJob: Job? = null
    private var favoriteOpenJob: Job? = null

    init {
        vmScope.launch {
            deps.refreshVolumesAction()
        }
        var lastShowHidden = settings.value.showHidden
        var lastAppLanguage = settings.value.appLanguage
        vmScope.launch {
            settings.collect { s ->
                recomputeVisibleEntries(s)
                if (s.showHidden != lastShowHidden) {
                    lastShowHidden = s.showHidden
                    if (_browser.value.currentDir != null) {
                        reloadCurrentDirectory(silent = true)
                    }
                }
                if (s.appLanguage != lastAppLanguage) {
                    lastAppLanguage = s.appLanguage
                    deps.refreshVolumesAction()
                }
            }
        }
        vmScope.launch {
            deps.operationRegistry.completions.collect { event ->
                _snackbarMessages.tryEmit(event)
                val b = _browser.value
                if (b.isGalleryLens) {
                    val sc = b.galleryScope
                    if (sc != null) {
                        galleryController.openScope(
                            galleryScope = sc,
                            isMediaRootAggregate = galleryController.state.value.isMediaRootAggregate,
                            preserveFilter = true,
                            silent = true,
                        )
                    }
                } else if (b.currentDir != null) {
                    reloadCurrentDirectory(silent = true)
                }
                galleryController.refreshRecentHome()
                deps.refreshVolumesAction()
            }
        }

        // Restore directory or Library gallery scope from SavedStateHandle (process death E15) or rememberLastFolder
        val savedLensRaw = savedStateHandle.get<String>(STATE_LENS)
        val savedGalleryScope = GalleryScope.parse(savedStateHandle.get<String>(STATE_GALLERY_SCOPE))
        val savedDirRaw = savedStateHandle.get<String>(STATE_DIR)
            ?: settings.value.takeIf { it.rememberLastFolder }?.lastFolder
        if (savedLensRaw == BrowseLens.GALLERY.name && savedGalleryScope is GalleryScope.Library) {
            openGalleryLibrary(recordHistory = false)
        } else if (savedDirRaw != null) {
            val parsed = runCatching { VfsPath.parse(savedDirRaw) }.getOrNull()
            val rootName = savedStateHandle.get<String>(STATE_ROOT_NAME) ?: inferRootName(parsed)
            val rootBase = savedStateHandle.get<String>(STATE_ROOT_BASE)
                ?.let { runCatching { VfsPath.parse(it) }.getOrNull() }
                ?: inferRootBase(parsed)
            if (parsed != null) {
                navigateToDirectory(
                    parsed,
                    rootVolumeName = rootName,
                    rootVolumeBase = rootBase,
                    recordHistory = false,
                )
            }
        }
        galleryController.refreshRecentHome()
    }

    private fun restoreInitialTab(): MainTab {
        val raw = savedStateHandle.get<String>(STATE_TAB) ?: return MainTab.BROWSE
        return MainTab.entries.firstOrNull { it.name == raw } ?: MainTab.BROWSE
    }

    fun selectTab(tab: MainTab) {
        overlayBackStack.clear()
        _overlay.value = null
        _activeTab.value = tab
        savedStateHandle[STATE_TAB] = tab.name
        if (tab == MainTab.BROWSE && _browser.value.currentDir == null) {
            vmScope.launch { deps.refreshVolumesAction() }
        }
    }

    fun openOverlay(screen: OverlayScreen) {
        val current = _overlay.value
        if (current != null && current != screen) {
            val existingIdx = overlayBackStack.indexOf(screen)
            if (existingIdx >= 0) {
                while (overlayBackStack.size > existingIdx) {
                    overlayBackStack.removeLast()
                }
            } else {
                overlayBackStack.addLast(current)
            }
        }
        _overlay.value = screen
    }

    fun closeOverlay() {
        val prev = overlayBackStack.removeLastOrNull()
        _overlay.value = prev
        if (prev == null && _browser.value.currentDir != null) {
            reloadCurrentDirectory(silent = true)
        }
    }

    fun refreshVolumes() {
        vmScope.launch {
            deps.refreshVolumesAction()
            // If currently browsing an unmounted volume, step back to Storage Home gracefully
            val cur = _browser.value.currentDir
            if (cur is VfsPath.Ftp) {
                if (deps.ftpServersRepository.findById(cur.serverId) == null) {
                    navigateToStorageHome()
                }
            } else if (cur is VfsPath.GDrive) {
                if (deps.gdriveServersRepository.findById(cur.serverId) == null) {
                    navigateToStorageHome()
                }
            } else if (cur != null) {
                val stillExists = withContext(ioDispatcher) {
                    runCatching { deps.fileSystem.exists(cur) }.getOrDefault(false)
                }
                if (!stillExists) {
                    navigateToStorageHome()
                    emitSimpleMessage(AppI18n.current()[Str.VOLUME_DISCONNECTED])
                }
            }
        }
    }

    fun navigateToStorageHome(clearBackStack: Boolean = true) {
        if (clearBackStack) {
            browserBackStack.clear()
        }
        directoryLoadJob?.cancel()
        recursiveSearchJob?.cancel()
        val hadSelection = _browser.value.selectedPaths.isNotEmpty() || galleryController.state.value.isSelectionMode
        galleryController.clearScope()
        _browser.value = BrowserUiState()
        savedStateHandle.remove<String>(STATE_DIR)
        savedStateHandle.remove<String>(STATE_ROOT_NAME)
        savedStateHandle.remove<String>(STATE_ROOT_BASE)
        savedStateHandle.remove<String>(STATE_LENS)
        savedStateHandle.remove<String>(STATE_GALLERY_SCOPE)
        if (hadSelection) {
            emitSimpleMessage(AppI18n.current()[Str.SELECTION_CLEARED])
        }
    }

    // --- Gallery Lens navigation & actions ---

    fun openGalleryLibrary(recordHistory: Boolean = true) {
        if (recordHistory) {
            browserBackStack.clear()
        }
        directoryLoadJob?.cancel()
        recursiveSearchJob?.cancel()
        overlayBackStack.clear()
        _overlay.value = null
        _activeTab.value = MainTab.BROWSE
        savedStateHandle[STATE_TAB] = MainTab.BROWSE.name
        savedStateHandle.remove<String>(STATE_DIR)
        savedStateHandle[STATE_LENS] = BrowseLens.GALLERY.name
        savedStateHandle[STATE_GALLERY_SCOPE] = GalleryScope.Library.serialize()

        _browser.value = BrowserUiState(
            currentDir = null,
            lens = BrowseLens.GALLERY,
            galleryScope = GalleryScope.Library,
        )
        galleryController.openScope(GalleryScope.Library, isMediaRootAggregate = false)
    }

    fun switchBrowseLens(target: BrowseLens) {
        val b = _browser.value
        val dir = b.currentDir ?: return
        galleryController.clearSelection()
        clearSelection()
        if (target == BrowseLens.FILES) {
            galleryController.recordFolderMode(dir, FolderLensOverride.FILES)
            galleryController.clearScope()
            savedStateHandle[STATE_LENS] = BrowseLens.FILES.name
            savedStateHandle.remove<String>(STATE_GALLERY_SCOPE)
            _browser.value = b.copy(lens = BrowseLens.FILES, galleryScope = null)
            // R2-2: Refresh raw directory entries so deletes/moves/edits performed in Gallery lens show immediately
            reloadCurrentDirectory(silent = true)
        } else {
            galleryController.recordFolderMode(dir, FolderLensOverride.GALLERY)
            val volRoots = knownVolumeRoots()
            val isRootAgg = GalleryScopePolicy.isMediaRoot(dir, volRoots)
            val folderScope = GalleryScope.Folder(dir = dir, includeSubtree = true)
            savedStateHandle[STATE_LENS] = BrowseLens.GALLERY.name
            savedStateHandle[STATE_GALLERY_SCOPE] = folderScope.serialize()
            _browser.value = b.copy(
                lens = BrowseLens.GALLERY,
                galleryScope = folderScope,
                searchActive = false,
                searchQuery = "",
            )
            galleryController.openScope(folderScope, isMediaRootAggregate = isRootAgg)
        }
    }

    fun alwaysOpenCurrentFolderAsFiles() {
        val dir = _browser.value.currentDir ?: return
        galleryController.recordFolderMode(dir, FolderLensOverride.FILES, alwaysFiles = true)
        galleryController.clearSelection()
        galleryController.clearScope()
        savedStateHandle[STATE_LENS] = BrowseLens.FILES.name
        savedStateHandle.remove<String>(STATE_GALLERY_SCOPE)
        _browser.value = _browser.value.copy(lens = BrowseLens.FILES, galleryScope = null)
        reloadCurrentDirectory(silent = true)
    }

    fun openMediaViewer(scope: GalleryScope, startPath: VfsPath) {
        openOverlay(OverlayScreen.MediaViewer(scope = scope, startPath = startPath))
    }

    fun openMediaEditor(path: VfsPath) {
        openOverlay(OverlayScreen.MediaEditor(path = path))
    }

    fun showMediaInFolder(path: VfsPath) {
        vmScope.launch {
            val parent = withContext(ioDispatcher) { runCatching { deps.fileSystem.parent(path) }.getOrNull() }
                ?: return@launch
            overlayBackStack.clear()
            _overlay.value = null
            _activeTab.value = MainTab.BROWSE
            savedStateHandle[STATE_TAB] = MainTab.BROWSE.name
            // Open directly in Files lens so the user sees the physical file in its folder
            galleryController.recordFolderMode(parent, FolderLensOverride.FILES)
            navigateToDirectory(parent)
        }
    }

    /**
     * Prepares media according to user export options (`Original` / `Most compatible` + `Remove location`)
     * via `OperationRegistry` (`OperationKind.EXPORT`) so exact file sizes exist on disk before
     * invoking [sendToPeer], with OOM / Throwable protection and LOHS network release on failure (H7/M5).
     */
    fun sendMediaToPeer(
        context: Context,
        peer: PeerInfo,
        paths: List<VfsPath>,
    ) {
        if (paths.isEmpty()) {
            releaseJoinedNetworkIfTarget(peer, immediate = true)
            return
        }
        val s = settings.value
        val engine = _container?.imageEditorEngine
        if (engine == null || (s.galleryExportFormat == com.minifile.explorer.core.gallery.MediaExportFormat.ORIGINAL && !s.galleryRemoveLocationOnExport)) {
            sendToPeer(context, peer, paths)
            return
        }
        galleryController.clearSelection()
        val appContext = context.applicationContext ?: context
        val sStart = AppI18n.current()
        deps.operationRegistry.enqueue(
            context = appContext,
            kind = OperationKind.EXPORT,
            title = sStart[Str.GALLERY_PREPARING_EXPORT],
        ) { _, sink ->
            try {
                val (preparedPaths, _) = engine.prepareForExport(
                    paths = paths,
                    format = s.galleryExportFormat,
                    removeLocation = s.galleryRemoveLocationOnExport,
                )
                if (sink.isCancelled()) {
                    releaseJoinedNetworkIfTarget(peer, immediate = true)
                    OperationRunOutcome(message = AppI18n.current().format(Str.SENDING_CANCELLED, peer.name))
                } else {
                    sendToPeer(appContext, peer, preparedPaths)
                    OperationRunOutcome(message = AppI18n.current().format(Str.SENDING_TO_ELLIPSIS, peer.name))
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                releaseJoinedNetworkIfTarget(peer, immediate = true)
                val sEnd = AppI18n.current()
                val msg = sEnd.format(Str.GALLERY_EXPORT_FAILED_FMT, localizeMediaEditError(t, sEnd))
                OperationRunOutcome(message = msg, errorSummary = msg)
            }
        }
    }

    /**
     * Persists an image edit via `OperationRegistry` (`OperationKind.EDIT`) so saves survive
     * editor screen disposal, refresh thumbnails/SAF/.nomedia snapshots immediately, and report status (M5).
     */
    fun saveImageEdit(
        context: Context,
        sourcePath: VfsPath,
        recipe: com.minifile.explorer.core.gallery.EditRecipe,
        replaceOriginal: Boolean,
        onComplete: (Boolean) -> Unit = {},
    ) {
        val c = _container ?: return
        val appContext = context.applicationContext ?: context
        deps.operationRegistry.enqueue(
            context = appContext,
            kind = OperationKind.EDIT,
            title = AppI18n.current()[Str.SAVING],
        ) { _, _ ->
            val res = c.imageEditorEngine.applyEdit(
                sourcePath = sourcePath,
                recipe = recipe,
                replaceOriginal = replaceOriginal,
            )
            val sEnd = AppI18n.current()
            res.fold(
                onSuccess = { out ->
                    if (out.replacedOriginal) {
                        c.galleryThumbnailCache.clearMemoryCache()
                    }
                    val stat = runCatching { deps.fileSystem.stat(out.savedPath) }.getOrNull()
                    if (stat != null) {
                        val item = com.minifile.explorer.core.gallery.MediaItem(
                            path = stat.path,
                            name = stat.name,
                            kind = com.minifile.explorer.core.gallery.MediaKind.IMAGE,
                            mime = stat.resolvedMimeType,
                            sizeBytes = stat.size.coerceAtLeast(0L),
                            bestDateMs = stat.lastModified,
                            modifiedMs = stat.lastModified,
                        )
                        galleryController.onMutation(GalleryMutation.Upserted(listOf(item)))
                    }
                    val msg = when {
                        out.reducedResolution -> sEnd.format(Str.GALLERY_SAVED_REDUCED_RES_FMT, out.fileName)
                        out.replacedOriginal -> sEnd.format(Str.GALLERY_REPLACED_ORIGINAL_FMT, out.fileName)
                        else -> sEnd.format(Str.GALLERY_SAVED_COPY_FMT, out.fileName)
                    }
                    onComplete(true)
                    OperationRunOutcome(message = msg)
                },
                onFailure = { err ->
                    val msg = sEnd.format(Str.GALLERY_EDIT_FAILED_FMT, localizeMediaEditError(err, sEnd))
                    onComplete(false)
                    OperationRunOutcome(message = msg, errorSummary = msg)
                },
            )
        }
    }

    /**
     * Persists a lossless video remux edit via `OperationRegistry` (`OperationKind.EDIT`) (M5).
     */
    fun saveVideoEdit(
        context: Context,
        sourcePath: VfsPath,
        recipe: com.minifile.explorer.core.gallery.EditRecipe,
        probe: com.minifile.explorer.data.media.VideoProbeInfo?,
        replaceOriginal: Boolean,
        onComplete: (Boolean) -> Unit = {},
    ) {
        val c = _container ?: return
        val appContext = context.applicationContext ?: context
        deps.operationRegistry.enqueue(
            context = appContext,
            kind = OperationKind.EDIT,
            title = AppI18n.current()[Str.SAVING],
        ) { _, _ ->
            val res = c.losslessMp4Remuxer.applyEdit(
                sourcePath = sourcePath,
                recipe = recipe,
                probe = probe,
                replaceOriginal = replaceOriginal,
            )
            val sEnd = AppI18n.current()
            res.fold(
                onSuccess = { out ->
                    if (out.replacedOriginal) {
                        c.galleryThumbnailCache.clearMemoryCache()
                    }
                    val stat = runCatching { deps.fileSystem.stat(out.savedPath) }.getOrNull()
                    if (stat != null) {
                        val item = com.minifile.explorer.core.gallery.MediaItem(
                            path = stat.path,
                            name = stat.name,
                            kind = com.minifile.explorer.core.gallery.MediaKind.VIDEO,
                            mime = stat.resolvedMimeType,
                            sizeBytes = stat.size.coerceAtLeast(0L),
                            bestDateMs = stat.lastModified,
                            modifiedMs = stat.lastModified,
                            durationMs = out.outputDurationMs,
                        )
                        galleryController.onMutation(GalleryMutation.Upserted(listOf(item)))
                    }
                    val msg = if (out.replacedOriginal) {
                        sEnd.format(Str.GALLERY_REPLACED_ORIGINAL_FMT, out.fileName)
                    } else {
                        sEnd.format(Str.GALLERY_SAVED_COPY_FMT, out.fileName)
                    }
                    onComplete(true)
                    OperationRunOutcome(message = msg)
                },
                onFailure = { err ->
                    val msg = sEnd.format(Str.GALLERY_EDIT_FAILED_FMT, localizeMediaEditError(err, sEnd))
                    onComplete(false)
                    OperationRunOutcome(message = msg, errorSummary = msg)
                },
            )
        }
    }

    private fun localizeMediaEditError(err: Throwable, s: com.minifile.explorer.core.i18n.AppStrings): String {
        val code = (err as? com.minifile.explorer.core.gallery.MediaEditException)?.code
            ?: (err.cause as? com.minifile.explorer.core.gallery.MediaEditException)?.code
        return when (code) {
            com.minifile.explorer.core.gallery.MediaEditErrorCode.NO_EDITS -> s[Str.GALLERY_ERR_NO_EDITS]
            com.minifile.explorer.core.gallery.MediaEditErrorCode.UNSUPPORTED_FORMAT -> s[Str.GALLERY_ERR_UNSUPPORTED_FORMAT]
            com.minifile.explorer.core.gallery.MediaEditErrorCode.CLIP_TOO_SHORT -> s[Str.GALLERY_CLIP_TOO_SHORT_LOSSLESS]
            com.minifile.explorer.core.gallery.MediaEditErrorCode.INSUFFICIENT_SPACE -> s[Str.GALLERY_ERR_INSUFFICIENT_SPACE]
            com.minifile.explorer.core.gallery.MediaEditErrorCode.READ_FAILED -> s[Str.GALLERY_ERR_READ_FAILED]
            com.minifile.explorer.core.gallery.MediaEditErrorCode.WRITE_FAILED -> s[Str.GALLERY_ERR_WRITE_FAILED]
            null -> when (err) {
                is java.io.IOException -> s[Str.GALLERY_ERR_WRITE_FAILED]
                else -> s[Str.OP_FAILED_GENERIC]
            }
        }
    }

    private fun knownVolumeRoots(): List<VfsPath> {
        val list = ArrayList<VfsPath>()
        volumes.value.mapNotNullTo(list) { it.root }
        val primary = VfsPath.Local(deps.primaryStoragePathProvider())
        if (primary !in list) list.add(primary)
        return list
    }

    fun openVolume(volume: Volume) {
        val root = volume.root ?: return
        _activeTab.value = MainTab.BROWSE
        savedStateHandle[STATE_TAB] = MainTab.BROWSE.name
        navigateToDirectory(
            dir = root,
            rootVolumeName = volume.name,
            rootVolumeBase = root,
        )
    }

    fun openFtpServer(server: FtpServerBookmark) {
        overlayBackStack.clear()
        _overlay.value = null
        _activeTab.value = MainTab.BROWSE
        savedStateHandle[STATE_TAB] = MainTab.BROWSE.name
        val root = server.rootPath
        navigateToDirectory(
            dir = root,
            rootVolumeName = server.name,
            rootVolumeBase = root,
        )
    }

    fun addFtpServer(
        name: String,
        host: String,
        port: Int,
        anonymous: Boolean,
        username: String,
        password: String,
        initialPath: String,
        openAfterAdd: Boolean = true,
    ) {
        vmScope.launch {
            val created = deps.ftpServersRepository.add(
                name = name,
                host = host,
                port = port,
                anonymous = anonymous,
                username = username,
                password = password,
                initialPath = initialPath,
            )
            emitSimpleMessage(AppI18n.current().format(Str.ADDED_FTP_SERVER, created.name))
            if (openAfterAdd) {
                openFtpServer(created)
            }
        }
    }

    fun updateFtpServer(server: FtpServerBookmark) {
        vmScope.launch {
            deps.ftpServersRepository.update(server)
            emitSimpleMessage(AppI18n.current().format(Str.UPDATED_FTP_SERVER, server.name))
            val cur = _browser.value.currentDir
            if (cur is VfsPath.Ftp && cur.serverId == server.id) {
                val root = server.rootPath
                navigateToDirectory(
                    dir = root,
                    rootVolumeName = server.name,
                    rootVolumeBase = root,
                )
            }
        }
    }

    fun removeFtpServer(id: String) {
        val existing = deps.ftpServersRepository.findById(id)
        vmScope.launch {
            deps.ftpServersRepository.remove(id)
            val cur = _browser.value.currentDir
            if (cur is VfsPath.Ftp && cur.serverId == id) {
                navigateToStorageHome()
            }
            if (existing != null) {
                emitSimpleMessage(AppI18n.current().format(Str.REMOVED_FTP_SERVER, existing.name))
            }
        }
    }

    fun openGDriveServer(server: GDriveServerBookmark) {
        overlayBackStack.clear()
        _overlay.value = null
        _activeTab.value = MainTab.BROWSE
        savedStateHandle[STATE_TAB] = MainTab.BROWSE.name
        val root = server.rootPath
        navigateToDirectory(
            dir = root,
            rootVolumeName = server.name,
            rootVolumeBase = root,
        )
    }

    fun addGDriveServer(bookmark: GDriveServerBookmark, openAfterAdd: Boolean = true) {
        vmScope.launch {
            val created = deps.gdriveServersRepository.add(bookmark)
            deps.invalidateGDriveServer(created.id)
            emitSimpleMessage(AppI18n.current().format(Str.ADDED_GDRIVE_MSG, created.name))
            if (openAfterAdd) {
                openGDriveServer(created)
            }
        }
    }

    fun updateGDriveServer(server: GDriveServerBookmark) {
        vmScope.launch {
            deps.gdriveServersRepository.update(server)
            deps.invalidateGDriveServer(server.id)
            emitSimpleMessage(AppI18n.current().format(Str.UPDATED_GDRIVE, server.name))
            val cur = _browser.value.currentDir
            if (cur is VfsPath.GDrive && cur.serverId == server.id) {
                val root = server.rootPath
                navigateToDirectory(
                    dir = root,
                    rootVolumeName = server.name,
                    rootVolumeBase = root,
                )
            }
        }
    }

    fun removeGDriveServer(id: String) {
        val existing = deps.gdriveServersRepository.findById(id)
        val cachedToken = deps.peekGDriveCachedToken(id)
        vmScope.launch {
            deps.gdriveServersRepository.remove(id)
            deps.invalidateGDriveServer(id)
            val cur = _browser.value.currentDir
            if (cur is VfsPath.GDrive && cur.serverId == id) {
                navigateToStorageHome()
            }
            if (existing != null) {
                emitSimpleMessage(AppI18n.current().format(Str.REMOVED_GDRIVE, existing.name))
                withContext(ioDispatcher) {
                    if (existing.authMode == GDriveAuthMode.SYSTEM_ACCOUNT) {
                        _container?.appContext?.let { ctx ->
                            AndroidAccountTokenProvider.invalidateTokenBestEffort(
                                ctx,
                                cachedToken,
                            )
                        }
                    } else {
                        val tokenToRevoke = existing.refreshToken.ifBlank { existing.accessToken }
                        revokeOAuthTokenBestEffort(tokenToRevoke)
                    }
                }
            }
        }
    }

    fun startGDriveBrowserSignIn(
        clientId: String,
        clientSecret: String,
        displayName: String = "",
        initialPath: String = "/",
        existingBookmark: GDriveServerBookmark? = null,
    ) {
        container.gdriveSignInController.startBrowserSignIn(
            scope = vmScope,
            clientId = clientId,
            clientSecret = clientSecret,
            displayName = displayName,
            initialPath = initialPath,
            existingBookmark = existingBookmark,
        )
    }

    fun cancelGDriveSignIn() {
        deps.cancelGDriveSignIn()
    }

    fun resetGDriveSignIn() {
        deps.resetGDriveSignIn()
    }

    fun submitGDrivePastedCredentials(
        rawText: String,
        overrideClientId: String = "",
        overrideClientSecret: String = "",
        displayName: String = "",
        initialPath: String = "/",
        existingBookmark: GDriveServerBookmark? = null,
    ) {
        container.gdriveSignInController.submitPastedCredentials(
            scope = vmScope,
            rawText = rawText,
            overrideClientId = overrideClientId,
            overrideClientSecret = overrideClientSecret,
            displayName = displayName,
            initialPath = initialPath,
            existingBookmark = existingBookmark,
        )
    }

    fun completeGDriveSystemAccount(
        activity: Activity,
        accountName: String,
        displayName: String = "",
        initialPath: String = "/",
        existingBookmark: GDriveServerBookmark? = null,
    ) {
        container.gdriveSignInController.completeSystemAccountSignIn(
            scope = vmScope,
            activity = activity,
            accountName = accountName,
            displayName = displayName,
            initialPath = initialPath,
            existingBookmark = existingBookmark,
        )
    }

    fun openReauthGDriveDialog(serverId: String) {
        _reauthGDriveBookmark.value = deps.gdriveServersRepository.findById(serverId)
    }

    fun dismissReauthGDriveDialog() {
        deps.cancelGDriveSignIn()
        _reauthGDriveBookmark.value = null
    }

    override fun onCleared() {
        galleryController.stop()
        deps.cancelGDriveSignIn()
        shareResolveJob?.cancel()
        shareSaveCancelled.set(true)
        shareSaveJob?.cancel()
        pendingIncomingShares.clear()
        joinJob?.cancel()
        joinJob = null
        // Spec §3.3 [v2]: never leave the user's Wi-Fi pinned to a peer hotspot.
        runCatching { deps.hotspotJoinController.release() }
        super.onCleared()
    }

    fun openFavoriteInBrowse(fav: Favorite, context: Context? = null) {
        favoriteOpenJob?.cancel()
        favoriteOpenJob = vmScope.launch {
            deps.favoritesRepository.touch(fav.id)
            val node = withContext(ioDispatcher) {
                runCatching { deps.fileSystem.stat(fav.path) }.getOrNull()
            }
            if (node != null && node.isDirectory != fav.isDirectory) {
                deps.favoritesRepository.add(
                    name = fav.name,
                    path = fav.path,
                    color = fav.color,
                    isDirectory = node.isDirectory,
                )
            }
            val isFileFav = if (node != null) !node.isDirectory else !fav.isDirectory
            if (isFileFav) {
                val targetPath = node?.path ?: fav.path
                val targetName = node?.name ?: fav.path.displayName
                val ext = PathUtils.extensionOf(targetName)
                when {
                    ext == "pdf" -> {
                        openOverlay(OverlayScreen.PdfViewer(targetPath))
                    }

                    ext == "zip" || ext == "jar" -> {
                        openOverlay(OverlayScreen.ArchivePreview(targetPath))
                    }

                    node != null && settings.value.openTextInBuiltInEditor &&
                        withContext(ioDispatcher) { UiFormatters.shouldOpenAsText(node, deps.fileSystem) } -> {
                        openOverlay(OverlayScreen.TextEditor(targetPath))
                    }

                    else -> {
                        if (context != null && node != null) {
                            UiFormatters.openExternallyAsync(
                                context = context,
                                safFs = container.safFileSystem,
                                fs = deps.fileSystem,
                                node = node,
                            ).onFailure { err ->
                                emitSimpleMessage(AppI18n.current().format(Str.CANNOT_OPEN_FILE, node.name, err.message.orEmpty()))
                            }
                        }
                    }
                }
            } else {
                val originEntry = BrowserBackEntry(
                    dir = _browser.value.currentDir,
                    rootVolumeName = _browser.value.rootVolumeName,
                    rootVolumeBase = _browser.value.rootVolumeBase,
                    overlay = _overlay.value,
                    tab = _activeTab.value,
                )
                if (originEntry.overlay != null ||
                    originEntry.tab != MainTab.BROWSE ||
                    originEntry.dir != fav.path
                ) {
                    browserBackStack.addLast(originEntry)
                }
                overlayBackStack.clear()
                _overlay.value = null
                _activeTab.value = MainTab.BROWSE
                savedStateHandle[STATE_TAB] = MainTab.BROWSE.name
                navigateToDirectory(
                    dir = fav.path,
                    rootVolumeName = inferRootName(fav.path),
                    rootVolumeBase = inferRootBase(fav.path),
                    recordHistory = false,
                )
            }
        }
    }

    fun navigateToDirectory(
        dir: VfsPath,
        rootVolumeName: String? = null,
        rootVolumeBase: VfsPath? = null,
        recordHistory: Boolean = true,
    ) {
        val prev = _browser.value
        if (recordHistory && prev.currentDir != dir) {
            val existingIdx = browserBackStack.indexOfFirst { it.dir == dir && it.overlay == null }
            if (existingIdx >= 0) {
                while (browserBackStack.size > existingIdx) {
                    browserBackStack.removeLast()
                }
            } else {
                browserBackStack.addLast(
                    BrowserBackEntry(
                        dir = prev.currentDir,
                        rootVolumeName = prev.rootVolumeName,
                        rootVolumeBase = prev.rootVolumeBase,
                        overlay = null,
                        tab = _activeTab.value,
                    ),
                )
            }
        }

        val hadSelection = prev.selectedPaths.isNotEmpty() && prev.currentDir != null && prev.currentDir != dir

        val effectiveRootName = rootVolumeName ?: prev.rootVolumeName.takeIf {
            prev.rootVolumeBase != null && PathUtils.isAncestor(prev.rootVolumeBase, dir)
        } ?: inferRootName(dir)

        val effectiveRootBase = rootVolumeBase ?: prev.rootVolumeBase?.takeIf {
            PathUtils.isAncestor(it, dir)
        } ?: inferRootBase(dir)

        savedStateHandle[STATE_DIR] = dir.serialize()
        savedStateHandle[STATE_ROOT_NAME] = effectiveRootName
        effectiveRootBase?.let { savedStateHandle[STATE_ROOT_BASE] = it.serialize() }

        deps.settingsRepository.update { s ->
            if (s.rememberLastFolder) s.copy(lastFolder = dir.serialize()) else s
        }

        val volRoots = knownVolumeRoots()
        val isMediaCandidate = GalleryScopePolicy.isInsideMediaRoot(dir, volRoots) ||
            galleryController.folderModePreferences().resolveNearestAncestor(dir)?.second?.mode == FolderLensOverride.GALLERY

        recursiveSearchJob?.cancel()
        galleryController.clearSelection()
        _browser.value = prev.copy(
            currentDir = dir,
            rootVolumeName = effectiveRootName,
            rootVolumeBase = effectiveRootBase,
            breadcrumbs = buildBreadcrumbs(dir, effectiveRootName, effectiveRootBase),
            isLoading = true,
            errorMessage = null,
            permissionRequired = false,
            searchActive = false,
            searchQuery = "",
            recursiveSearch = false,
            isRecursiveSearching = false,
            recursiveHits = emptyList(),
            selectedPaths = if (prev.currentDir == dir) prev.selectedPaths else emptySet(),
            lastToggledPath = null,
            lens = if (prev.currentDir == dir) prev.lens else BrowseLens.FILES,
            galleryScope = if (prev.currentDir == dir) prev.galleryScope else null,
            isMediaRootCandidate = isMediaCandidate,
        )

        if (hadSelection) {
            emitSimpleMessage(AppI18n.current()[Str.SELECTION_CLEARED])
        }

        loadDirectoryInternal(dir, showSpinner = true)
    }

    fun reloadCurrentDirectory(silent: Boolean = false) {
        val dir = _browser.value.currentDir ?: return
        if (!silent && dir is VfsPath.GDrive) {
            deps.invalidateGDrivePath(dir)
        }
        loadDirectoryInternal(dir, showSpinner = !silent)
    }

    private fun loadDirectoryInternal(dir: VfsPath, showSpinner: Boolean) {
        directoryLoadJob?.cancel()
        directoryLoadJob = vmScope.launch {
            if (showSpinner) {
                _browser.value = _browser.value.copy(isLoading = true, errorMessage = null)
            }
            try {
                // Always fetch hidden entries into rawEntries so toggling show_hidden is instant,
                // and filter according to settings in recomputeVisibleEntries.
                val (dirStat, allChildren) = withContext(ioDispatcher) {
                    val st = deps.fileSystem.stat(dir)
                    val list = deps.fileSystem.list(dir, includeHidden = true)
                    st to list
                }
                val validSelected = _browser.value.selectedPaths.intersect(allChildren.map { it.path }.toSet())

                // Evaluate GalleryScopePolicy (automatic DCIM / Pictures lens for Local & SAF)
                val volRoots = knownVolumeRoots()
                val overrides = galleryController.folderModePreferences()
                val mediaRoot = GalleryScopePolicy.mediaRootFor(dir, volRoots)
                val isCandidate = mediaRoot != null ||
                    overrides.resolveNearestAncestor(dir)?.second?.mode == FolderLensOverride.GALLERY
                val hasNomedia = if (isCandidate) {
                    withContext(ioDispatcher) {
                        deps.galleryRepository.vfsSource.hasNomediaUpTo(dir, mediaRoot)
                    }
                } else {
                    false
                }
                val directOrSubMediaCount = if (isCandidate) {
                    val hasAny = withContext(ioDispatcher) {
                        deps.galleryRepository.vfsSource.hasAnyMedia(
                            dir = dir,
                            preloadedRootEntries = allChildren,
                            includeSubtree = true,
                            includeHidden = settings.value.showHidden,
                        )
                    }
                    if (hasAny) 1 else 0
                } else {
                    0
                }
                if (overrides.resolveNearestAncestor(dir) != null) {
                    galleryController.touchFolderMode(dir)
                }
                val decision = GalleryScopePolicy.decide(
                    dir = dir,
                    volumeRoots = volRoots,
                    autoOpenMediaFolders = settings.value.galleryAutoOpenMediaFolders,
                    overrides = overrides,
                    hasNomediaInDirOrAncestorToMediaRoot = hasNomedia,
                    mediaCountInSubtree = directOrSubMediaCount,
                )
                val effectiveLens = if (_browser.value.searchActive) BrowseLens.FILES else decision.lens
                val effectiveScope = if (effectiveLens == BrowseLens.GALLERY) decision.scope else null

                savedStateHandle[STATE_LENS] = effectiveLens.name
                if (effectiveScope != null) {
                    savedStateHandle[STATE_GALLERY_SCOPE] = effectiveScope.serialize()
                } else {
                    savedStateHandle.remove<String>(STATE_GALLERY_SCOPE)
                }

                _browser.value = _browser.value.copy(
                    currentDirNode = dirStat,
                    rawEntries = allChildren,
                    isLoading = false,
                    errorMessage = null,
                    permissionRequired = false,
                    selectedPaths = validSelected,
                    lens = effectiveLens,
                    galleryScope = effectiveScope,
                    isMediaRootCandidate = isCandidate,
                )
                recomputeVisibleEntries(settings.value)

                if (effectiveScope != null) {
                    galleryController.openScope(
                        galleryScope = effectiveScope,
                        isMediaRootAggregate = decision.isMediaRootAggregate,
                        preserveFilter = !showSpinner,
                        silent = !showSpinner,
                    )
                    if (decision.isAutoActivated && !settings.value.galleryAutoEntryHintShown) {
                        deps.settingsRepository.update { it.copy(galleryAutoEntryHintShown = true) }
                        emitSimpleMessage(AppI18n.current().format(Str.GALLERY_AUTO_SNACKBAR_FMT, dir.displayName))
                    }
                } else {
                    galleryController.clearScope()
                }
            } catch (e: Exception) {
                val str = AppI18n.current()
                val msg = e.message ?: str[Str.UNABLE_READ_FOLDER]
                if (e is GDriveAuthException && e.needsInteraction && dir is VfsPath.GDrive) {
                    _snackbarMessages.tryEmit(
                        OperationCompletionEvent(
                            id = "gdrive-auth-${System.currentTimeMillis()}",
                            kind = OperationKind.COPY,
                            title = "Google Drive",
                            snackbarMessage = str[Str.GDRIVE_SIGNIN_EXPIRED],
                            reauthGDriveServerId = dir.serverId,
                        ),
                    )
                }
                val needsPerm = dir is VfsPath.Local &&
                    (msg.contains("Permission", ignoreCase = true) || msg.contains("Cannot list", ignoreCase = true))
                _browser.value = _browser.value.copy(
                    rawEntries = emptyList(),
                    visibleEntries = emptyList(),
                    isLoading = false,
                    errorMessage = msg,
                    permissionRequired = needsPerm,
                )
            }
        }
    }

    private fun recomputeVisibleEntries(s: AppSettings) {
        val b = _browser.value
        if (b.currentDir == null) return
        val q = b.searchQuery.trim()
        val filtered = b.rawEntries.asSequence()
            .filter { s.showHidden || !it.isHidden }
            .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
            .sortedWith(s.sort.comparator())
            .toList()
        _browser.value = b.copy(visibleEntries = filtered)
    }

    private fun buildBreadcrumbs(
        dir: VfsPath,
        rootName: String,
        rootBase: VfsPath?,
    ): List<BreadcrumbCrumb> {
        val crumbs = ArrayList<BreadcrumbCrumb>()
        when (dir) {
            is VfsPath.Local -> {
                val baseLocal = (rootBase as? VfsPath.Local)?.normalized ?: "/storage/emulated/0"
                if (dir.normalized == baseLocal || !dir.normalized.startsWith("$baseLocal/")) {
                    crumbs.add(BreadcrumbCrumb(rootName, VfsPath.Local(dir.normalized)))
                } else {
                    crumbs.add(BreadcrumbCrumb(rootName, VfsPath.Local(baseLocal)))
                    val rel = dir.normalized.removePrefix("$baseLocal/")
                    var running = baseLocal
                    for (seg in rel.split('/').filter { it.isNotEmpty() }) {
                        running = "$running/$seg"
                        crumbs.add(BreadcrumbCrumb(seg, VfsPath.Local(running)))
                    }
                }
            }

            is VfsPath.Saf -> {
                val baseSaf = (rootBase as? VfsPath.Saf) ?: dir
                crumbs.add(BreadcrumbCrumb(rootName, baseSaf))
                if (dir.documentId != baseSaf.documentId && dir.documentId.startsWith("${baseSaf.documentId}/")) {
                    val rel = dir.documentId.removePrefix("${baseSaf.documentId}/")
                    var runningDoc = baseSaf.documentId
                    for (seg in rel.split('/').filter { it.isNotEmpty() }) {
                        runningDoc = "$runningDoc/$seg"
                        crumbs.add(BreadcrumbCrumb(seg, VfsPath.Saf(dir.treeUri, runningDoc)))
                    }
                } else if (dir.documentId != baseSaf.documentId) {
                    crumbs.add(BreadcrumbCrumb(dir.displayName, dir))
                }
            }

            is VfsPath.Ftp -> {
                val baseFtp = (rootBase as? VfsPath.Ftp)?.takeIf { it.serverId == dir.serverId }
                    ?: deps.ftpServersRepository.findById(dir.serverId)?.rootPath
                    ?: VfsPath.Ftp(dir.serverId, "/")
                val baseNorm = baseFtp.normalizedRemotePath
                val dirNorm = dir.normalizedRemotePath
                val prefix = if (baseNorm == "/") "/" else "$baseNorm/"
                if (dirNorm == baseNorm || (baseNorm != "/" && !dirNorm.startsWith(prefix))) {
                    crumbs.add(BreadcrumbCrumb(rootName, dir))
                } else {
                    crumbs.add(BreadcrumbCrumb(rootName, baseFtp))
                    val rel = if (baseNorm == "/") dirNorm.removePrefix("/") else dirNorm.removePrefix(prefix)
                    var running = if (baseNorm == "/") "" else baseNorm
                    for (seg in rel.split('/').filter { it.isNotEmpty() }) {
                        running = "$running/$seg"
                        crumbs.add(BreadcrumbCrumb(seg, VfsPath.Ftp(dir.serverId, running)))
                    }
                }
            }

            is VfsPath.GDrive -> {
                val baseGd = (rootBase as? VfsPath.GDrive)?.takeIf { it.serverId == dir.serverId }
                    ?: deps.gdriveServersRepository.findById(dir.serverId)?.rootPath
                    ?: VfsPath.GDrive(dir.serverId, "/")
                val baseNorm = baseGd.normalizedRemotePath
                val dirNorm = dir.normalizedRemotePath
                val prefix = if (baseNorm == "/") "/" else "$baseNorm/"
                if (dirNorm == baseNorm || (baseNorm != "/" && !dirNorm.startsWith(prefix))) {
                    crumbs.add(BreadcrumbCrumb(rootName, dir))
                } else {
                    crumbs.add(BreadcrumbCrumb(rootName, baseGd))
                    val rel = if (baseNorm == "/") dirNorm.removePrefix("/") else dirNorm.removePrefix(prefix)
                    var running = if (baseNorm == "/") "" else baseNorm
                    for (seg in rel.split('/').filter { it.isNotEmpty() }) {
                        running = "$running/$seg"
                        crumbs.add(BreadcrumbCrumb(seg, VfsPath.GDrive(dir.serverId, running)))
                    }
                }
            }
        }
        return crumbs
    }

    private fun inferRootName(path: VfsPath?): String {
        if (path == null) return AppI18n.current()[Str.INTERNAL]
        if (path is VfsPath.Ftp) {
            return deps.ftpServersRepository.findById(path.serverId)?.name ?: "FTP (${path.serverId})"
        }
        if (path is VfsPath.GDrive) {
            return deps.gdriveServersRepository.findById(path.serverId)?.name ?: "Google Drive (${path.serverId})"
        }
        for (v in volumes.value) {
            val r = v.root ?: continue
            if (PathUtils.isAncestor(r, path)) return v.name
        }
        val primary = deps.primaryStoragePathProvider()
        if (path is VfsPath.Local && PathUtils.isAncestor(VfsPath.Local(primary), path)) {
            return AppI18n.current()[Str.INTERNAL]
        }
        return path.displayName
    }

    private fun inferRootBase(path: VfsPath?): VfsPath? {
        if (path == null) return null
        for (v in volumes.value) {
            val r = v.root ?: continue
            if (PathUtils.isAncestor(r, path)) return r
        }
        val primary = VfsPath.Local(deps.primaryStoragePathProvider())
        if (path is VfsPath.Local && PathUtils.isAncestor(primary, path)) return primary
        return when (path) {
            is VfsPath.Local -> VfsPath.Local("/")
            is VfsPath.Saf -> deps.findSafGrantRoot(path.treeUri) ?: path
            is VfsPath.Ftp -> deps.ftpServersRepository.findById(path.serverId)?.rootPath
                ?: VfsPath.Ftp(path.serverId, "/")
            is VfsPath.GDrive -> deps.gdriveServersRepository.findById(path.serverId)?.rootPath
                ?: VfsPath.GDrive(path.serverId, "/")
        }
    }

    // --- Back button state machine (DESIGN.md §1.1) ---
    fun canHandleBack(): Boolean {
        if (_overlay.value != null) return true
        if (_activeTab.value == MainTab.BROWSE) {
            val b = _browser.value
            if (galleryController.state.value.isSelectionMode) return true
            if (b.isSelectionMode) return true
            if (b.searchActive) return true
            if (b.galleryScope is GalleryScope.Library) return true
            if (b.currentDir != null) return true
        } else {
            return true
        }
        return false
    }

    fun handleBack() {
        if (_overlay.value != null) {
            closeOverlay()
            return
        }
        if (_activeTab.value != MainTab.BROWSE) {
            selectTab(MainTab.BROWSE)
            return
        }
        val b = _browser.value
        when {
            galleryController.state.value.isSelectionMode -> galleryController.clearSelection()
            b.isSelectionMode -> clearSelection()
            b.searchActive -> closeSearch()
            b.galleryScope is GalleryScope.Library -> navigateToStorageHome()
            b.currentDir != null -> navigateUpOrHome()
        }
    }

    fun navigateUpOrHome() {
        val b = _browser.value
        val cur = b.currentDir ?: return
        if (browserBackStack.isNotEmpty()) {
            val prev = browserBackStack.removeLast()
            if (prev.tab != _activeTab.value) {
                _activeTab.value = prev.tab
                savedStateHandle[STATE_TAB] = prev.tab.name
            }
            if (prev.dir == null) {
                navigateToStorageHome(clearBackStack = false)
                _overlay.value = prev.overlay
            } else {
                navigateToDirectory(
                    dir = prev.dir,
                    rootVolumeName = prev.rootVolumeName,
                    rootVolumeBase = prev.rootVolumeBase,
                    recordHistory = false,
                )
                _overlay.value = prev.overlay
            }
            return
        }
        val base = b.rootVolumeBase
        if (base != null && cur == base) {
            navigateToStorageHome()
            return
        }
        vmScope.launch {
            val parent = withContext(ioDispatcher) { deps.fileSystem.parent(cur) }
            if (parent == null || (base != null && !PathUtils.isAncestor(base, parent))) {
                navigateToStorageHome()
            } else {
                navigateToDirectory(parent, b.rootVolumeName, b.rootVolumeBase, recordHistory = false)
            }
        }
    }

    // --- Search ---
    fun openSearch() {
        val b = _browser.value
        if (b.lens == BrowseLens.GALLERY && b.currentDir != null) {
            galleryController.clearSelection()
            _browser.value = b.copy(lens = BrowseLens.FILES, galleryScope = null, searchActive = true)
        } else {
            _browser.value = b.copy(searchActive = true)
        }
    }

    fun closeSearch() {
        recursiveSearchJob?.cancel()
        _browser.value = _browser.value.copy(
            searchActive = false,
            searchQuery = "",
            recursiveSearch = false,
            isRecursiveSearching = false,
            recursiveHits = emptyList(),
        )
        recomputeVisibleEntries(settings.value)
    }

    fun updateSearchQuery(query: String) {
        _browser.value = _browser.value.copy(searchQuery = query)
        recomputeVisibleEntries(settings.value)
        if (_browser.value.recursiveSearch) {
            triggerRecursiveSearch(query)
        }
    }

    fun toggleRecursiveSearch(enabled: Boolean) {
        _browser.value = _browser.value.copy(recursiveSearch = enabled, recursiveHits = emptyList())
        if (enabled) {
            triggerRecursiveSearch(_browser.value.searchQuery)
        } else {
            recursiveSearchJob?.cancel()
            _browser.value = _browser.value.copy(isRecursiveSearching = false)
        }
    }

    private fun triggerRecursiveSearch(query: String) {
        recursiveSearchJob?.cancel()
        val root = _browser.value.currentDir ?: return
        val q = query.trim()
        if (q.isEmpty()) {
            _browser.value = _browser.value.copy(isRecursiveSearching = false, recursiveHits = emptyList())
            return
        }
        val includeHidden = settings.value.showHidden
        _browser.value = _browser.value.copy(isRecursiveSearching = true, recursiveHits = emptyList())
        recursiveSearchJob = vmScope.launch {
            val buffer = ArrayList<SearchHit>()
            var cancelled = false
            val sink = object : ProgressSink {
                override fun onProgress(p: com.minifile.explorer.core.model.OperationProgress) = Unit
                override fun isCancelled(): Boolean = cancelled
            }
            try {
                withContext(ioDispatcher) {
                    deps.fileOperations.searchRecursive(
                        root = root,
                        query = q,
                        includeHidden = includeHidden,
                        maxDepth = 12,
                        sink = sink,
                        onHit = { hit ->
                            synchronized(buffer) {
                                if (buffer.size < 500) buffer.add(hit)
                            }
                            _browser.value = _browser.value.copy(recursiveHits = synchronized(buffer) { buffer.toList() })
                        },
                    )
                }
            } finally {
                cancelled = true
                _browser.value = _browser.value.copy(isRecursiveSearching = false)
            }
        }
    }

    // --- Selection ---
    fun toggleSelection(path: VfsPath, rangeSelect: Boolean = false) {
        val b = _browser.value
        val curSet = b.selectedPaths.toMutableSet()
        if (rangeSelect && b.lastToggledPath != null && b.lastToggledPath != path) {
            val paths = b.visibleEntries.map { it.path }
            val i1 = paths.indexOf(b.lastToggledPath)
            val i2 = paths.indexOf(path)
            if (i1 >= 0 && i2 >= 0) {
                val lo = minOf(i1, i2)
                val hi = maxOf(i1, i2)
                for (i in lo..hi) {
                    curSet.add(paths[i])
                }
                _browser.value = b.copy(selectedPaths = curSet, lastToggledPath = path)
                return
            }
        }
        if (!curSet.add(path)) {
            curSet.remove(path)
        }
        _browser.value = b.copy(selectedPaths = curSet, lastToggledPath = path)
    }

    fun selectAll() {
        val b = _browser.value
        val all = b.visibleEntries.map { it.path }.toSet()
        _browser.value = b.copy(selectedPaths = all)
    }

    fun invertSelection() {
        val b = _browser.value
        val visible = b.visibleEntries.map { it.path }.toSet()
        val inverted = visible - b.selectedPaths
        _browser.value = b.copy(selectedPaths = inverted)
    }

    fun clearSelection() {
        _browser.value = _browser.value.copy(selectedPaths = emptySet(), lastToggledPath = null)
    }

    fun selectedNodes(): List<VfsNode> {
        val b = _browser.value
        val map = b.rawEntries.associateBy { it.path }
        return b.selectedPaths.mapNotNull { map[it] }
    }

    // --- Settings quick actions ---
    fun toggleShowHidden() {
        deps.settingsRepository.update { it.copy(showHidden = !it.showHidden) }
    }

    fun toggleViewMode() {
        deps.settingsRepository.update {
            it.copy(viewMode = if (it.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST)
        }
    }

    fun updateSort(sortSpec: SortSpec) {
        deps.settingsRepository.update { it.copy(sort = sortSpec) }
    }

    // --- Clipboard & Transfers ---
    fun copyPathsToClipboard(paths: List<VfsPath>, mode: TransferMode) {
        ClipboardHolder.set(paths, mode)
        clearSelection()
    }

    fun pasteClipboardInto(context: Context, destinationDir: VfsPath, destinationLabel: String? = null) {
        val clip = ClipboardHolder.state.value ?: return
        executeTransfer(
            context = context,
            items = clip.items,
            mode = clip.mode,
            destinationDir = destinationDir,
            destinationLabel = destinationLabel ?: destinationDir.displayName,
            clearClipboardOnComplete = true,
        )
    }

    fun executeTransferToFavorite(
        context: Context,
        items: List<VfsPath>,
        mode: TransferMode,
        favorite: Favorite,
        clearClipboardOnComplete: Boolean = false,
    ) {
        vmScope.launch {
            deps.favoritesRepository.touch(favorite.id)
        }
        clearSelection()
        executeTransfer(
            context = context,
            items = items,
            mode = mode,
            destinationDir = favorite.path,
            destinationLabel = favorite.name,
            clearClipboardOnComplete = clearClipboardOnComplete,
        )
    }

    fun executeTransfer(
        context: Context,
        items: List<VfsPath>,
        mode: TransferMode,
        destinationDir: VfsPath,
        destinationLabel: String = destinationDir.displayName,
        clearClipboardOnComplete: Boolean = false,
    ) {
        if (items.isEmpty()) return
        val opKind = if (mode == TransferMode.COPY) OperationKind.COPY else OperationKind.MOVE
        val sStart = AppI18n.current()
        val countLabel = if (items.size == 1) items.first().displayName else sStart.itemsCount(items.size)
        val opTitle = if (mode == TransferMode.COPY) {
            sStart.format(Str.COPYING_TO, countLabel, destinationLabel)
        } else {
            sStart.format(Str.MOVING_TO, countLabel, destinationLabel)
        }

        deps.operationRegistry.enqueue(
            context = context,
            kind = opKind,
            title = opTitle,
            retryAction = {
                executeTransfer(context, items, mode, destinationDir, destinationLabel, clearClipboardOnComplete)
            },
        ) { _, sink ->
            val resolver = buildInteractiveConflictResolver()
            val res = if (mode == TransferMode.COPY) {
                deps.fileOperations.copy(items, destinationDir, resolver, sink)
            } else {
                deps.fileOperations.move(items, destinationDir, resolver, sink)
            }
            // Rescan destination (and source items on move) so MediaStore stays in sync
            val scanTargets = buildList {
                add(destinationDir)
                for (item in items) {
                    if (mode == TransferMode.MOVE) add(item)
                    runCatching { deps.fileSystem.child(destinationDir, item.displayName) }.getOrNull()?.let(::add)
                }
            }
            deps.mediaScanNotifier.rescanFiles(scanTargets)
            if (mode == TransferMode.MOVE && res.succeeded > 0) {
                galleryController.onMutation(GalleryMutation.Removed(items))
            }
            if (clearClipboardOnComplete && !res.cancelled && res.failed.isEmpty()) {
                ClipboardHolder.clear()
            } else if (clearClipboardOnComplete && mode == TransferMode.COPY) {
                ClipboardHolder.clear()
            }
            val firstFailed = res.failed.firstOrNull()
            val expiredGDriveServerId = when {
                (firstFailed != null && AppI18n.isSignInExpiredMessage(firstFailed.second)) -> {
                    (firstFailed.first as? VfsPath.GDrive)?.serverId
                        ?: (destinationDir as? VfsPath.GDrive)?.serverId
                }
                else -> null
            }
            val sEnd = AppI18n.current()
            val summary = if (expiredGDriveServerId != null && res.succeeded == 0) {
                sEnd[Str.GDRIVE_SIGNIN_EXPIRED]
            } else {
                buildString {
                    append(
                        sEnd.format(
                            if (mode == TransferMode.COPY) Str.COPIED_ITEMS_TO else Str.MOVED_ITEMS_TO,
                            sEnd.itemsCount(res.succeeded),
                            destinationLabel,
                        ),
                    )
                    if (res.skipped > 0) append(sEnd.format(Str.SKIPPED_SUFFIX, res.skipped))
                    if (res.failed.isNotEmpty()) append(sEnd.format(Str.FAILED_SUFFIX, res.failed.size, res.failed.first().second))
                }
            }
            OperationRunOutcome(
                message = summary,
                destinationToOpen = if (expiredGDriveServerId == null && destinationDir != _browser.value.currentDir) {
                    destinationDir
                } else {
                    null
                },
                openLabel = sEnd[Str.OPEN],
                errorSummary = firstFailed?.second,
                reauthGDriveServerId = expiredGDriveServerId,
            )
        }
    }

    private fun buildInteractiveConflictResolver(): ConflictResolver {
        var stickyResolution: ConflictResolution? = null
        return ConflictResolver { src, existing ->
            if (!settings.value.confirmOverwrite) {
                return@ConflictResolver ConflictResolution.OVERWRITE
            }
            stickyResolution?.let { return@ConflictResolver it }
            val def = CompletableDeferred<Pair<ConflictResolution, Boolean>>()
            _conflictPrompt.value = ConflictPrompt(src, existing, def)
            try {
                val (choice, applyAll) = def.await()
                if (applyAll) {
                    stickyResolution = choice
                }
                choice
            } finally {
                _conflictPrompt.value = null
            }
        }
    }

    fun answerConflict(resolution: ConflictResolution, applyToAll: Boolean) {
        _conflictPrompt.value?.deferred?.complete(resolution to applyToAll)
    }

    // --- Direct Mutations ---
    fun createNewFolder(name: String, onError: (String) -> Unit) {
        val dir = _browser.value.currentDir ?: return
        val v = PathUtils.validateName(name.trim(), fatSafe = dir is VfsPath.Saf)
        if (v is NameValidation.Invalid) {
            onError(v.reason)
            return
        }
        vmScope.launch {
            try {
                withContext(ioDispatcher) {
                    deps.fileOperations.createFolder(dir, name.trim())
                }
                reloadCurrentDirectory(silent = true)
            } catch (e: Exception) {
                onError(e.message ?: AppI18n.current()[Str.FAILED_CREATE_FOLDER])
            }
        }
    }

    fun createNewFile(
        name: String,
        openInEditor: Boolean,
        onError: (String) -> Unit,
    ) {
        val dir = _browser.value.currentDir ?: return
        val v = PathUtils.validateName(name.trim(), fatSafe = dir is VfsPath.Saf)
        if (v is NameValidation.Invalid) {
            onError(v.reason)
            return
        }
        vmScope.launch {
            try {
                val created = withContext(ioDispatcher) {
                    deps.fileOperations.createFile(dir, name.trim())
                }
                reloadCurrentDirectory(silent = true)
                if (openInEditor) {
                    openOverlay(OverlayScreen.TextEditor(created, forceEdit = true))
                }
            } catch (e: Exception) {
                onError(e.message ?: AppI18n.current()[Str.FAILED_CREATE_FILE])
            }
        }
    }

    fun renameItem(item: VfsPath, newName: String, onError: (String) -> Unit, onSuccess: () -> Unit) {
        val v = PathUtils.validateName(newName.trim(), fatSafe = item is VfsPath.Saf)
        if (v is NameValidation.Invalid) {
            onError(v.reason)
            return
        }
        vmScope.launch {
            try {
                val renamed = withContext(ioDispatcher) {
                    deps.fileOperations.rename(item, newName.trim())
                }
                deps.mediaScanNotifier.rescanFiles(listOf(item, renamed))
                galleryController.onMutation(GalleryMutation.Renamed(item, renamed, newName.trim()))
                clearSelection()
                reloadCurrentDirectory(silent = true)
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: AppI18n.current()[Str.RENAME_FAILED])
            }
        }
    }

    fun deleteItems(context: Context, items: List<VfsPath>) {
        if (items.isEmpty()) return
        clearSelection()
        val allGDrive = items.all { it is VfsPath.GDrive }
        val sStart = AppI18n.current()
        val label = if (items.size == 1) items.first().displayName else sStart.itemsCount(items.size)
        deps.operationRegistry.enqueue(
            context = context,
            kind = OperationKind.DELETE,
            title = if (allGDrive) sStart.format(Str.MOVING_TO_TRASH, label) else sStart.format(Str.DELETING_ITEMS, label),
        ) { _, sink ->
            val res = deps.fileOperations.delete(items, sink)
            deps.mediaScanNotifier.rescanFiles(items)
            if (res.succeeded > 0) {
                galleryController.onMutation(GalleryMutation.Removed(items))
            }
            val firstFailed = res.failed.firstOrNull()
            val expiredServerId = if ((firstFailed != null && AppI18n.isSignInExpiredMessage(firstFailed.second))) {
                (firstFailed.first as? VfsPath.GDrive)?.serverId
            } else {
                null
            }
            val sEnd = AppI18n.current()
            val msg = when {
                expiredServerId != null && res.succeeded == 0 -> sEnd[Str.GDRIVE_SIGNIN_EXPIRED]
                allGDrive && res.succeeded > 0 -> buildString {
                    append(sEnd[Str.MOVED_GDRIVE_TRASH])
                    if (items.size > 1) append(sEnd.format(Str.ITEMS_PARENS, sEnd.itemsCount(res.succeeded)))
                    if (res.failed.isNotEmpty()) append(sEnd.format(Str.FAILED_PARENS, res.failed.size))
                }
                else -> buildString {
                    append(sEnd.format(Str.DELETED_ITEMS, sEnd.itemsCount(res.succeeded)))
                    if (res.failed.isNotEmpty()) append(sEnd.format(Str.FAILED_PARENS, res.failed.size))
                }
            }
            OperationRunOutcome(
                message = msg,
                errorSummary = firstFailed?.second,
                reauthGDriveServerId = expiredServerId,
            )
        }
    }

    fun compressItemsToZip(
        context: Context,
        items: List<VfsPath>,
        destinationDir: VfsPath,
        archiveName: String,
        compressionLevel: Int,
    ) {
        if (items.isEmpty()) return
        clearSelection()
        val finalZipName = if (archiveName.endsWith(".zip", ignoreCase = true)) archiveName else "$archiveName.zip"
        val sStart = AppI18n.current()
        deps.operationRegistry.enqueue(
            context = context,
            kind = OperationKind.COMPRESS,
            title = sStart.format(Str.COMPRESSING_ZIP, finalZipName),
            retryAction = { compressItemsToZip(context, items, destinationDir, archiveName, compressionLevel) },
        ) { _, sink ->
            val created = deps.zipArchiver.compress(
                items = items,
                dstDir = destinationDir,
                archiveName = finalZipName,
                level = compressionLevel,
                sink = sink,
            )
            val sEnd = AppI18n.current()
            OperationRunOutcome(
                message = sEnd.format(Str.CREATED_ARCHIVE, created.displayName),
                destinationToOpen = created,
                openLabel = sEnd[Str.OPEN],
            )
        }
    }

    fun extractArchive(
        context: Context,
        zipPath: VfsPath,
        destinationDir: VfsPath,
        createSubfolderName: String?,
        selectedEntries: Set<String>? = null,
        overwrite: Boolean = false,
        allowBombOverride: Boolean = false,
    ) {
        val sStart = AppI18n.current()
        deps.operationRegistry.enqueue(
            context = context,
            kind = OperationKind.EXTRACT,
            title = sStart.format(Str.EXTRACTING_ZIP, zipPath.displayName),
            retryAction = {
                extractArchive(context, zipPath, destinationDir, createSubfolderName, selectedEntries, overwrite, allowBombOverride)
            },
        ) { _, sink ->
            val actualDest = if (!createSubfolderName.isNullOrBlank()) {
                val existingNames = runCatching {
                    deps.fileSystem.list(destinationDir, includeHidden = true).map { it.name }.toSet()
                }.getOrDefault(emptySet())
                val folderName = if (overwrite) createSubfolderName else PathUtils.uniqueName(createSubfolderName, existingNames, isDirectory = true)
                val existingChild = deps.fileSystem.child(destinationDir, folderName)
                if (deps.fileSystem.stat(existingChild)?.isDirectory == true) {
                    existingChild
                } else {
                    deps.fileSystem.createDirectory(destinationDir, folderName)
                }
            } else {
                destinationDir
            }

            val res = deps.zipArchiver.extract(
                zip = zipPath,
                dstDir = actualDest,
                entries = selectedEntries,
                overwrite = overwrite,
                allowLargeArchive = allowBombOverride,
                sink = sink,
            )
            deps.mediaScanNotifier.rescanFiles(listOf(actualDest))
            val sEnd = AppI18n.current()
            val unsafeNote = when (res.unsafeEntriesSkipped) {
                0 -> ""
                1 -> sEnd[Str.UNSAFE_SKIPPED_SINGLE]
                else -> sEnd.format(Str.UNSAFE_SKIPPED_PLURAL, res.unsafeEntriesSkipped)
            }
            OperationRunOutcome(
                message = sEnd.format(Str.EXTRACTED_FILES, sEnd.filesCount(res.extractedFiles), unsafeNote),
                destinationToOpen = actualDest,
                openLabel = sEnd[Str.OPEN],
                unsafeSkipped = res.unsafeEntriesSkipped,
            )
        }
    }

    fun toggleNomedia(dirPath: VfsPath) {
        vmScope.launch {
            val s = AppI18n.current()
            try {
                val currentlyHas = withContext(ioDispatcher) {
                    deps.nomediaManager.has(dirPath)
                }
                withContext(ioDispatcher) {
                    deps.nomediaManager.set(dirPath, !currentlyHas)
                }
                reloadCurrentDirectory(silent = true)
                val msg = if (!currentlyHas) {
                    s.format(Str.HIDDEN_FROM_GALLERY_CREATED, dirPath.displayName)
                } else {
                    s.format(Str.RESTORED_TO_GALLERY_REMOVED, dirPath.displayName)
                }
                emitSimpleMessage(msg)
            } catch (e: Exception) {
                emitSimpleMessage(s.format(Str.COULD_NOT_UPDATE_NOMEDIA, e.message.orEmpty()))
            }
        }
    }

    fun addOrUpdateFavorite(
        name: String,
        path: VfsPath,
        color: Int? = null,
        isDirectory: Boolean = true,
    ) {
        vmScope.launch {
            deps.favoritesRepository.add(name, path, color, isDirectory = isDirectory)
            emitSimpleMessage(AppI18n.current().format(Str.ADDED_TO_FAVORITES, name.ifBlank { path.displayName }))
        }
    }

    fun addFavorite(
        name: String,
        path: VfsPath,
        isDirectory: Boolean = true,
        color: Int? = null,
    ) {
        addOrUpdateFavorite(name = name, path = path, color = color, isDirectory = isDirectory)
    }

    fun removeFavoriteByPath(path: VfsPath) {
        val existing = deps.favoritesRepository.findByPath(path) ?: return
        vmScope.launch {
            deps.favoritesRepository.remove(existing.id)
            emitSimpleMessage(AppI18n.current().format(Str.REMOVED_FROM_FAVORITES, existing.name))
        }
    }

    fun renameFavorite(id: String, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty()) return
        vmScope.launch {
            deps.favoritesRepository.rename(id, clean)
            emitSimpleMessage(AppI18n.current().format(Str.RENAMED_FAVORITE, clean))
        }
    }

    fun removeFavorite(fav: Favorite) {
        vmScope.launch {
            deps.favoritesRepository.remove(fav.id)
            emitSimpleMessage(AppI18n.current().format(Str.REMOVED_FROM_FAVORITES, fav.name))
        }
    }

    fun moveFavorite(id: String, delta: Int) {
        vmScope.launch {
            val ids = favorites.value.map { it.id }.toMutableList()
            val idx = ids.indexOf(id)
            if (idx < 0) return@launch
            val targetIdx = (idx + delta).coerceIn(0, ids.lastIndex)
            if (targetIdx == idx) return@launch
            val moved = ids.removeAt(idx)
            ids.add(targetIdx, moved)
            deps.favoritesRepository.reorder(ids)
        }
    }

    // --- App-to-App Peer Discovery & Transfer ---
    fun startPeerBrowsing() {
        container.peerDiscoveryHolder.acquireBrowser()
        container.blePeerCoordinator.acquire()
        peerBrowseCount++
        if (peerBrowseCount == 1) runCatching { container.nsdAdvertiser.startBrowse() }
    }

    fun stopPeerBrowsing() {
        container.peerDiscoveryHolder.releaseBrowser()
        container.blePeerCoordinator.release()
        if (peerBrowseCount > 0) peerBrowseCount--
        if (peerBrowseCount == 0) runCatching { container.nsdAdvertiser.stopBrowse() }
    }

    /** Re-evaluate BLE radios after a runtime permission / Bluetooth state change. */
    fun onNearbyPermissionsChanged() {
        _container?.blePeerCoordinator?.restartRadios()
    }

    /** The user started HTTP Share explicitly: it must not stay confined to an ephemeral hotspot. */
    fun onUserStartedHttp() {
        _container?.localOnlyHotspotManager?.onUserStartedHttp()
    }

    // --- Scheme B host: Hotspot Direct ---
    fun startHotspotDirect(filesOnlyQr: Boolean = false) {
        vmScope.launch {
            try {
                container.localOnlyHotspotManager.start(
                    purpose = HotspotPurpose.DIRECT,
                    token = if (filesOnlyQr) HotspotLinkCodec.generateToken() else null,
                )
            } catch (e: HotspotException) {
                emitSimpleMessage(e.message ?: AppI18n.current()[Str.COULD_NOT_START_HOTSPOT])
            } catch (e: Exception) {
                emitSimpleMessage(e.message ?: AppI18n.current()[Str.COULD_NOT_START_HOTSPOT])
            }
        }
    }

    fun stopHotspotDirect() {
        container.localOnlyHotspotManager.stop()
    }

    // --- Consent / link dialog (MainActivity level) ---
    fun respondToIncomingBle(requestId: Long, accept: Boolean) {
        // Request ids are disjoint (LAN ids start at PeerOfferApprovals.REQUEST_ID_BASE): each side ignores the other's.
        PeerOfferApprovals.instance.respond(requestId, accept)
        container.blePeerCoordinator.respondToIncoming(requestId, accept)
    }

    /**
     * Transfer ▸ Nearby Devices ▸ Discoverable (iOS `setDiscoverable(_:)`): persists the flag, then gates BLE
     * advertising / GATT receiver (via [com.minifile.explorer.service.BlePeerCoordinator.onDiscoverableChanged]),
     * the LAN UDP receiver role + Bonjour advertisement while HTTP runs, and incoming peer `begin` offers.
     */
    fun setDiscoverable(on: Boolean) {
        val c = _container ?: return
        c.settingsRepository.setPeerDiscoverable(on)
        c.blePeerCoordinator.onDiscoverableChanged()
        val http = HttpServerHolder.state.value
        if (http.isRunning) {
            c.peerDiscoveryHolder.setReceiverRole(on)
            if (on) {
                runCatching { c.nsdAdvertiser.register(http.boundPort) }
            } else {
                runCatching { c.nsdAdvertiser.unregister() }
            }
        }
        if (!on) PeerOfferApprovals.instance.declinePending()
    }

    fun cancelBleLink() {
        container.blePeerCoordinator.cancelLink()
    }

    fun dismissBleLinkUi() {
        container.blePeerCoordinator.dismissLinkUi()
    }

    // --- Scheme B joiner: Scan QR to Join ---
    /** Handles any scanned / typed QR text: `WIFI:`, `minifile://hotspot|peer`, or `http(s)://host[:port]/`. */
    fun joinFromQr(raw: String) {
        // Always cancel/release any prior join before starting or failing a new QR action (Spec §3.3 [v2]).
        joinJob?.cancel()
        joinJob = null
        runCatching { deps.hotspotJoinController.release() }

        val payload = QrPayloads.parse(raw.trim())
        if (payload == null) {
            _joinState.value = JoinUiState.Failed(AppI18n.current()[Str.NOT_FILES_OR_WIFI_QR])
            return
        }
        joinJob = vmScope.launch {
            var joinedHere = false
            var completed = false
            var wifiCreds: WifiJoinFallback? = null
            var wifiJoined = false
            try {
                val peer: PeerInfo = when (payload) {
                    is QrPayload.Wifi -> {
                        _joinState.value = JoinUiState.Joining(payload.ssid)
                        joinedHere = true
                        wifiCreds = WifiJoinFallback(payload.ssid, payload.password, payload.sec)
                        val joined = deps.hotspotJoinController.join(
                            ssid = payload.ssid,
                            pass = payload.password,
                            sec = payload.sec,
                        )
                        wifiJoined = true
                        _joinState.value = JoinUiState.FindingHost(payload.ssid)
                        findHotspotHost(joined.gateway, joined.ownIp, joined.prefixLength)
                            ?: throw JoinException(AppI18n.current().format(Str.JOINED_NO_DEVICE, payload.ssid))
                    }
                    is QrPayload.Hotspot -> {
                        _joinState.value = JoinUiState.Joining(payload.ssid)
                        joinedHere = true
                        wifiCreds = WifiJoinFallback(payload.ssid, payload.password, payload.sec)
                        val joined = deps.hotspotJoinController.join(
                            ssid = payload.ssid,
                            pass = payload.password,
                            sec = payload.sec,
                            expectedHostIp = payload.ip,
                        )
                        wifiJoined = true
                        _joinState.value = JoinUiState.FindingHost(payload.ssid)
                        val direct = probeRetrying(listOf(payload.ip), listOf(payload.port), attempts = 10)
                            ?: findHotspotHost(joined.gateway, joined.ownIp, joined.prefixLength)
                            ?: throw JoinException(
                                AppI18n.current().format(
                                    Str.JOINED_HOST_NO_ANSWER,
                                    payload.ssid,
                                    payload.name ?: AppI18n.current()[Str.THE_HOST],
                                ),
                            )
                        val withTok = direct.copy(linkToken = payload.tok, source = PeerSource.QR)
                        deps.upsertPeer(withTok)
                        withTok
                    }
                    is QrPayload.Peer -> {
                        _joinState.value = JoinUiState.FindingHost(payload.ip)
                        probeRetrying(listOf(payload.ip), listOf(payload.port), attempts = 3)
                            ?: throw JoinException(AppI18n.current().format(Str.PEER_NOT_REACHABLE_WIFI, payload.ip, payload.port))
                    }
                    is QrPayload.Http -> {
                        _joinState.value = JoinUiState.FindingHost(payload.host)
                        probeRetrying(listOf(payload.host), listOf(payload.port), attempts = 3)
                            ?: throw JoinException(AppI18n.current().format(Str.NOT_A_FILES_DEVICE, payload.host, payload.port))
                    }
                }
                _joinState.value = JoinUiState.Found(peer)
                completed = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = (e as? PeerTransferException)?.userMessage ?: e.message ?: AppI18n.current()[Str.COULD_NOT_CONNECT]
                _joinState.value = JoinUiState.Failed(msg, wifiFallback = wifiCreds?.takeIf { !wifiJoined })
            } finally {
                if (joinedHere && !completed) {
                    runCatching { deps.hotspotJoinController.release() }
                }
            }
        }
    }

    /** Close the join dialog but keep the joined network for an upcoming send. */
    fun dismissJoinDialog() {
        _joinState.value = null
    }

    /** Abort joining and restore the previous Wi-Fi. */
    fun cancelJoin() {
        joinJob?.cancel()
        joinJob = null
        _joinState.value = null
        runCatching { deps.hotspotJoinController.release() }
    }

    val isJoinedToPeerHotspot: Boolean
        get() = deps.hotspotJoinController.isJoined

    /** Releases the joined hotspot when a pending UI flow (e.g. cancelled document picker) aborts before sending. */
    fun releaseJoinedHotspotForPeer(peer: PeerInfo, immediate: Boolean = true) {
        releaseJoinedNetworkIfTarget(peer, immediate = immediate)
    }

    /** Host discovery after a `WIFI:` join (spec §2.5): gateway → subnet `.1` → MFP1 beacons → `/24` sweep. */
    private suspend fun findHotspotHost(gateway: String?, ownIp: String?, prefixLength: Int): PeerInfo? {
        val ports = (listOf(settings.value.httpPort) + (8080..8090)).distinct()
        val candidates = buildList {
            gateway?.takeIf { it.isNotBlank() }?.let(::add)
            ownIp?.let { HostDecision.parseIpv4(it) }?.let { b -> add("${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}.1") }
        }.distinct().filter { it != ownIp }
        probeRetrying(candidates, ports, attempts = 3)?.let { return it }
        // MFP1 beacons over the pinned network (browser role is active while the dialog is open).
        val anchor = ownIp?.takeIf { it.isNotBlank() } ?: gateway?.takeIf { it.isNotBlank() }
        if (anchor != null) {
            repeat(10) {
                peers.value.firstOrNull { p ->
                    !p.needsLink && HostDecision.inSubnet(p.host, anchor, prefixLength)
                }?.let { return it }
                delay(500)
            }
            deps.scanPeersSubnetAction()
            repeat(30) {
                peers.value.firstOrNull { p ->
                    !p.needsLink && HostDecision.inSubnet(p.host, anchor, prefixLength)
                }?.let { return it }
                delay(500)
            }
        }
        return null
    }

    private suspend fun probeRetrying(hosts: List<String>, ports: List<Int>, attempts: Int): PeerInfo? {
        repeat(attempts) { attempt ->
            for (h in hosts) {
                for (p in ports) {
                    val res = runCatching {
                        deps.probePeer(h, p, PeerSource.QR, if (ports.size > 1) 400 else 1_500)
                    }.getOrNull()
                    if (res != null) return res
                }
            }
            if (attempt < attempts - 1) delay(1_000)
        }
        return null
    }

    /** Sends over an already-routable peer, or negotiates a BLE hotspot link first (spec §3.7). */
    private suspend fun runPeerSend(
        peer: PeerInfo,
        sendItems: List<PeerSendItem>,
        client: PeerHttpClient,
        sink: ProgressSink,
        bleFallbackActive: java.util.concurrent.atomic.AtomicBoolean? = null,
        /** Link dialog Cancel during the HTTP phase (cancels the whole operation). */
        onLinkCancel: () -> Unit = { client.close() },
    ): PeerSendResult {
        val engine = PeerTransferEngine(
            client = client,
            senderDeviceNameProvider = { settings.value.peerDeviceName },
        )
        val files = sendItems.count { !it.isDirectory }
        val bytes = sendItems.sumOf { if (it.isDirectory) 0L else it.size.coerceAtLeast(0L) }
        if (!peer.needsLink) {
            var ok = false
            try {
                // LAN / hotspot first; a VPN-intercepted (301..308) or unreachable `begin` is retried over Bluetooth
                // when the same device is in BLE range (<= 50 MB), e.g. iPhone on this phone's manual hotspot + VPN.
                val res = PeerSendFallback.sendWithBleFallback(
                    peer = peer,
                    bytes = bytes,
                    isCancelled = { sink.isCancelled() },
                    blePeerAvailable = { deps.hasBleRouteTo(peer) },
                    vpnActive = { deps.isVpnActive() },
                    lanSend = { engine.send(peer = peer, items = sendItems, sink = sink, conflict = settings.value.peerConflictPolicy) },
                    onFallback = {
                        bleFallbackActive?.set(true)
                        deps.beginBleSendAction(PeerPathPolicy.sanitizeDisplayName(peer.name, 64).ifEmpty { peer.host })
                    },
                    bleSend = { lanHttpBlocked ->
                        sendOverNegotiatedLink(peer, sendItems, engine, client, sink, files, bytes, lanHttpBlocked, onLinkCancel)
                    },
                )
                ok = !res.cancelled && !sink.isCancelled() && res.errors.isEmpty()
                return res
            } finally {
                releaseJoinedNetworkIfTarget(peer, immediate = !ok)
            }
        }
        return sendOverNegotiatedLink(peer, sendItems, engine, client, sink, files, bytes, lanHttpBlocked = false, onLinkCancel)
    }

    /**
     * Negotiates a BLE link (L2CAP / hotspot / LAN) to [peer] and sends over it (spec §3.7). HTTP sends mirror their
     * progress into the link dialog, can be aborted from its Cancel button, and (for `lohs-s`) retry `begin` on the
     * endpoint an iPhone re-announces after iOS suspended / restarted its listener.
     */
    private suspend fun sendOverNegotiatedLink(
        peer: PeerInfo,
        sendItems: List<PeerSendItem>,
        engine: PeerTransferEngine,
        client: PeerHttpClient,
        sink: ProgressSink,
        files: Int,
        bytes: Long,
        lanHttpBlocked: Boolean,
        onLinkCancel: () -> Unit,
    ): PeerSendResult {
        val link = deps.negotiateBleLink(peer, files, bytes, lanHttpBlocked)
        var ok = false
        deps.setLinkSendAbort(onLinkCancel)
        try {
            // L2CAP links stream over the open Bluetooth channel; hotspot / LAN links use HTTP.
            val direct = link.directSend
            val res = if (direct != null) {
                direct(sendItems, sink)
            } else {
                val uiSink = deps.linkProgressSink(sink)
                val name = PeerPathPolicy.sanitizeDisplayName(link.peer.name, 64).ifEmpty { AppI18n.current()[Str.THE_IPHONE] }
                ReannounceRetry.run(
                    initial = link.peer,
                    reannounces = link.reannounces,
                    abortActive = { client.cancelActive() },
                    isCancelled = { sink.isCancelled() },
                    onWaiting = { deps.linkPhase(AppI18n.current().format(Str.WAITING_FOR_PEER_OPEN_FILES, name)) },
                    onRetry = { deps.linkPhase(AppI18n.current().format(Str.SENDING_TO_ELLIPSIS, name)) },
                ) { target, onSessionStarted ->
                    engine.send(
                        peer = target,
                        items = sendItems,
                        sink = uiSink,
                        conflict = settings.value.peerConflictPolicy,
                        onSessionStarted = { onSessionStarted() },
                    )
                }
            }
            ok = !res.cancelled && !sink.isCancelled() && res.errors.isEmpty()
            return res
        } finally {
            deps.setLinkSendAbort(null)
            link.teardown(ok)
        }
    }

    private fun releaseJoinedNetworkIfTarget(peer: PeerInfo, immediate: Boolean) {
        val connector = deps.hotspotJoinController
        val joined = connector.joined ?: return
        val anchor = joined.ownIp?.takeIf { it.isNotBlank() } ?: joined.gateway?.takeIf { it.isNotBlank() }
        val matches = when {
            anchor == null -> true // DHCP lagged past LINK_WAIT_MS while isJoined: never leak
            peer.needsLink -> false
            peer.source == PeerSource.QR -> true
            else -> HostDecision.inSubnet(peer.host, anchor, joined.prefixLength)
        }
        if (matches) {
            if (immediate) connector.release() else connector.releaseAfter()
        }
    }

    fun scanPeersSubnet() {
        deps.scanPeersSubnetAction()
    }

    fun addPeerByAddress(
        rawAddress: String,
        onSuccess: (PeerInfo) -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        val trimmed = rawAddress.trim()
        if (trimmed.isEmpty()) {
            onError(AppI18n.current()[Str.ENTER_HOST_OR_IP])
            return
        }
        vmScope.launch {
            try {
                val peer = withContext(ioDispatcher) {
                    container.peerDiscoveryHolder.probeAddress(trimmed)
                }
                onSuccess(peer)
            } catch (e: PeerTransferException) {
                onError(e.userMessage)
            } catch (e: Exception) {
                onError(e.message ?: AppI18n.current().format(Str.COULD_NOT_REACH, trimmed))
            }
        }
    }

    fun sendToPeer(
        context: Context,
        peer: PeerInfo,
        items: List<VfsPath>,
    ) {
        if (items.isEmpty()) {
            releaseJoinedNetworkIfTarget(peer, immediate = true)
            return
        }
        val appContext = context.applicationContext ?: context
        val peerName = PeerPathPolicy.sanitizeDisplayName(peer.name, 64).ifEmpty { peer.host }
        val sStart = AppI18n.current()
        if (peer.readOnly) {
            releaseJoinedNetworkIfTarget(peer, immediate = true)
            emitSimpleMessage(sStart.format(Str.PEER_IS_READ_ONLY_HINT, peerName))
            return
        }
        clearSelection()
        emitSimpleMessage(sStart.format(Str.SENDING_TO_ELLIPSIS, peerName))
        // Bluetooth peers: the link dialog appears at t = 0 ms, before files are enumerated or Bluetooth connects.
        if (peer.needsLink) deps.beginBleSendAction(peerName)

        val countLabel = if (items.size == 1) items.first().displayName else sStart.itemsCount(items.size)
        val txClient = PeerHttpClient(socketBinder = deps.peerSocketBinder)
        val bleFallback = java.util.concurrent.atomic.AtomicBoolean(false)

        deps.operationRegistry.enqueue(
            context = appContext,
            kind = OperationKind.SEND,
            title = sStart.format(Str.SENDING_ITEMS_TO, countLabel, peerName),
            retryAction = { sendToPeer(appContext, peer, items) },
            onCancel = {
                txClient.close()
                if (peer.needsLink || bleFallback.get()) {
                    deps.cancelBleLinkAction()
                }
                if (!peer.needsLink) {
                    releaseJoinedNetworkIfTarget(peer, immediate = true)
                }
            },
        ) { opId, sink ->
            var enteredRunPeerSend = false
            var preparingError: String? = null
            try {
                txClient.use { client ->
                    try {
                        val sendItems = PeerTransferEngine.collectSendItems(
                            fs = deps.fileSystem,
                            roots = items,
                            includeHidden = settings.value.showHidden,
                        )
                        val sRun = AppI18n.current()
                        if (sink.isCancelled()) {
                            return@use OperationRunOutcome(message = sRun.format(Str.SENDING_CANCELLED, peerName))
                        }
                        if (sendItems.isEmpty()) {
                            val nothingMsg = sRun.format(Str.NOTHING_TO_SEND, peerName)
                            preparingError = nothingMsg
                            return@use OperationRunOutcome(message = nothingMsg)
                        }
                        enteredRunPeerSend = true
                        val res = runPeerSend(peer, sendItems, client, sink, bleFallback) { deps.operationRegistry.cancel(opId) }
                        formatPeerSendOutcome(peerName, res, sink.isCancelled())
                    } catch (e: PeerTransferException) {
                        if (sink.isCancelled()) {
                            OperationRunOutcome(message = AppI18n.current().format(Str.SENDING_CANCELLED, peerName))
                        } else {
                            if (!enteredRunPeerSend) preparingError = e.userMessage
                            OperationRunOutcome(
                                message = e.userMessage,
                                errorSummary = e.userMessage,
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                if (!enteredRunPeerSend && e !is kotlinx.coroutines.CancellationException) {
                    preparingError = e.message ?: AppI18n.current()[Str.COULD_NOT_READ_SELECTED]
                }
                throw e
            } finally {
                if (!enteredRunPeerSend) {
                    if (peer.needsLink) deps.endBleSendPreparingAction(peerName, preparingError)
                    releaseJoinedNetworkIfTarget(peer, immediate = true)
                }
            }
        }
    }

    fun sendUrisToPeer(
        context: Context,
        peer: PeerInfo,
        uris: List<Uri>,
    ) {
        if (uris.isEmpty()) {
            releaseJoinedNetworkIfTarget(peer, immediate = true)
            return
        }
        val appContext = context.applicationContext ?: context
        val peerName = PeerPathPolicy.sanitizeDisplayName(peer.name, 64).ifEmpty { peer.host }
        val sStart = AppI18n.current()
        if (peer.readOnly) {
            releaseJoinedNetworkIfTarget(peer, immediate = true)
            emitSimpleMessage(sStart.format(Str.PEER_IS_READ_ONLY_HINT, peerName))
            return
        }
        emitSimpleMessage(sStart.format(Str.SENDING_TO_ELLIPSIS, peerName))
        // Bluetooth peers: the link dialog appears at t = 0 ms, before files are enumerated or Bluetooth connects.
        if (peer.needsLink) deps.beginBleSendAction(peerName)

        val countLabel = sStart.filesCount(uris.size)
        val txClient = PeerHttpClient(socketBinder = deps.peerSocketBinder)
        val bleFallback = java.util.concurrent.atomic.AtomicBoolean(false)

        deps.operationRegistry.enqueue(
            context = appContext,
            kind = OperationKind.SEND,
            title = sStart.format(Str.SENDING_ITEMS_TO, countLabel, peerName),
            retryAction = { sendUrisToPeer(appContext, peer, uris) },
            onCancel = {
                txClient.close()
                if (peer.needsLink || bleFallback.get()) {
                    deps.cancelBleLinkAction()
                }
                if (!peer.needsLink) {
                    releaseJoinedNetworkIfTarget(peer, immediate = true)
                }
            },
        ) { opId, sink ->
            var enteredRunPeerSend = false
            var preparingError: String? = null
            try {
                txClient.use { client ->
                    try {
                        val sendItems = resolveUriSendItems(appContext, uris, sink)
                        val sRun = AppI18n.current()
                        if (sink.isCancelled()) {
                            return@use OperationRunOutcome(message = sRun.format(Str.SENDING_CANCELLED, peerName))
                        }
                        if (sendItems.isEmpty()) {
                            val nothingMsg = sRun.format(Str.NOTHING_TO_SEND, peerName)
                            preparingError = nothingMsg
                            return@use OperationRunOutcome(message = nothingMsg)
                        }
                        enteredRunPeerSend = true
                        val res = runPeerSend(peer, sendItems, client, sink, bleFallback) { deps.operationRegistry.cancel(opId) }
                        formatPeerSendOutcome(peerName, res, sink.isCancelled())
                    } catch (e: PeerTransferException) {
                        if (sink.isCancelled()) {
                            OperationRunOutcome(message = AppI18n.current().format(Str.SENDING_CANCELLED, peerName))
                        } else {
                            if (!enteredRunPeerSend) preparingError = e.userMessage
                            OperationRunOutcome(
                                message = e.userMessage,
                                errorSummary = e.userMessage,
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                if (!enteredRunPeerSend && e !is kotlinx.coroutines.CancellationException) {
                    preparingError = e.message ?: AppI18n.current()[Str.COULD_NOT_READ_SELECTED]
                }
                throw e
            } finally {
                if (!enteredRunPeerSend) {
                    if (peer.needsLink) deps.endBleSendPreparingAction(peerName, preparingError)
                    releaseJoinedNetworkIfTarget(peer, immediate = true)
                }
            }
        }
    }

    private fun resolveUriSendItems(
        appContext: Context,
        uris: List<Uri>,
        sink: ProgressSink,
    ): List<PeerSendItem> {
        val resolver = appContext.contentResolver
        val usedNames = HashSet<String>()
        val items = ArrayList<PeerSendItem>(uris.size)

        for (uri in uris) {
            if (sink.isCancelled()) break
            var rawName: String? = null
            var knownSize = -1L
            var lastMod = 0L

            runCatching {
                resolver.query(
                    uri,
                    arrayOf(
                        OpenableColumns.DISPLAY_NAME,
                        OpenableColumns.SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    ),
                    null,
                    null,
                    null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && !c.isNull(nameIdx)) {
                            rawName = c.getString(nameIdx)
                        }
                        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) {
                            knownSize = c.getLong(sizeIdx)
                        }
                        val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        if (modIdx >= 0 && !c.isNull(modIdx)) {
                            lastMod = c.getLong(modIdx)
                        }
                    }
                }
            }

            if (knownSize < 0L) {
                knownSize = runCatching {
                    resolver.openAssetFileDescriptor(uri, "r")?.use { afd -> afd.length } ?: -1L
                }.getOrDefault(-1L)
            }

            if (knownSize < 0L) {
                knownSize = resolver.openInputStream(uri)?.use { stream ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        if (sink.isCancelled()) return emptyList()
                        val n = stream.read(buf)
                        if (n < 0) break
                        total += n
                    }
                    total
                } ?: throw FileNotFoundException(AppI18n.current()[Str.CANNOT_OPEN_DOCUMENT])
            }

            val fallbackName = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringAfterLast(':')
                .orEmpty()
            val cleanedCandidate = (rawName ?: fallbackName)
                .replace('\u0000', '_')
                .replace('/', '_')
                .replace('\\', '_')
                .trim()
                .ifEmpty { "file" }
            val validatedCandidate = if (PathUtils.validateName(cleanedCandidate) is NameValidation.Valid) {
                cleanedCandidate
            } else {
                "file"
            }
            val uniqueRelName = PathUtils.uniqueName(
                desired = validatedCandidate,
                existingNames = usedNames,
                isDirectory = false,
            )
            usedNames.add(uniqueRelName)

            items.add(
                PeerSendItem(
                    relPath = uniqueRelName,
                    size = knownSize.coerceAtLeast(0L),
                    mtime = if (lastMod > 0L) lastMod else System.currentTimeMillis(),
                    isDirectory = false,
                    open = {
                        resolver.openInputStream(uri)
                            ?: throw FileNotFoundException(AppI18n.current().format(Str.CANNOT_OPEN_ITEM, uniqueRelName))
                    },
                ),
            )
        }
        return items
    }

    private fun formatPeerSendOutcome(
        peerName: String,
        res: PeerSendResult,
        cancelled: Boolean,
    ): OperationRunOutcome {
        val s = AppI18n.current()
        if (cancelled || res.cancelled) {
            return OperationRunOutcome(message = s.format(Str.SENDING_CANCELLED, peerName))
        }
        val summary = buildString {
            when {
                res.files > 0 -> {
                    append(s.format(Str.SENT_FILES_TO, s.filesCount(res.files), peerName))
                }
                res.dirs > 0 -> {
                    append(s.format(Str.SENT_FOLDERS_TO, s.foldersCount(res.dirs), peerName))
                }
                else -> {
                    append(s.format(Str.SENT_ZERO_FILES_TO, peerName))
                }
            }
            if (res.skipped > 0) {
                append(s.format(Str.SKIPPED_SUFFIX, res.skipped))
            }
        }
        return OperationRunOutcome(message = summary)
    }

    // --- Incoming External Share ("Save to Files") ---

    fun defaultShareReceivedPath(): VfsPath {
        val st = settings.value
        val rootRaw = st.ftpRoot ?: st.httpRoot
        val baseRoot = rootRaw?.let { runCatching { VfsPath.parse(it) }.getOrNull() }
            ?: VfsPath.Local(deps.primaryStoragePathProvider())
        return when (baseRoot) {
            is VfsPath.Local -> {
                if (baseRoot.displayName.equals("Received", ignoreCase = true)) {
                    baseRoot
                } else {
                    VfsPath.Local("${baseRoot.normalized.trimEnd('/')}/Received")
                }
            }

            is VfsPath.Ftp -> {
                if (baseRoot.displayName.equals("Received", ignoreCase = true)) {
                    baseRoot
                } else {
                    val p = if (baseRoot.normalizedRemotePath == "/") {
                        "/Received"
                    } else {
                        "${baseRoot.normalizedRemotePath}/Received"
                    }
                    VfsPath.Ftp(baseRoot.serverId, p)
                }
            }

            is VfsPath.GDrive -> {
                if (baseRoot.displayName.equals("Received", ignoreCase = true)) {
                    baseRoot
                } else {
                    val p = if (baseRoot.normalizedRemotePath == "/") {
                        "/Received"
                    } else {
                        "${baseRoot.normalizedRemotePath}/Received"
                    }
                    VfsPath.GDrive(baseRoot.serverId, p)
                }
            }

            is VfsPath.Saf -> baseRoot
        }
    }

    fun defaultDownloadsPath(): VfsPath.Local =
        VfsPath.Local("${deps.primaryStoragePathProvider().trimEnd('/')}/Download")

    fun friendlyShareDestinationLabel(path: VfsPath): String {
        val s = AppI18n.current()
        if (path == defaultShareReceivedPath()) return s[Str.RECEIVED]
        if (path == defaultDownloadsPath()) return s[Str.DOWNLOADS]
        return when (path) {
            is VfsPath.Ftp -> {
                val serverName = deps.ftpServersRepository.findById(path.serverId)?.name ?: "FTP"
                if (path.normalizedRemotePath == "/") serverName else "$serverName / ${path.displayName}"
            }

            is VfsPath.GDrive -> {
                val serverName = deps.gdriveServersRepository.findById(path.serverId)?.name ?: "Google Drive"
                if (path.normalizedRemotePath == "/") serverName else "$serverName / ${path.displayName}"
            }

            is VfsPath.Local, is VfsPath.Saf -> path.displayName
        }
    }

    fun buildShareQuickDestinations(): List<ShareQuickDestination> {
        val s = AppI18n.current()
        val result = ArrayList<ShareQuickDestination>()
        val seen = HashSet<VfsPath>()
        fun addUnique(id: String, label: String, path: VfsPath, kind: ShareDestinationKind) {
            if (seen.add(path)) {
                result.add(ShareQuickDestination(id = id, label = label, path = path, kind = kind))
            }
        }

        val received = defaultShareReceivedPath()
        addUnique("received", s[Str.RECEIVED], received, ShareDestinationKind.RECEIVED)

        val downloads = defaultDownloadsPath()
        addUnique("downloads", s[Str.DOWNLOADS], downloads, ShareDestinationKind.DOWNLOADS)

        val curDir = _browser.value.currentDir
        val curWritable = _browser.value.currentDirNode?.canWrite != false
        if (curDir != null && curWritable) {
            val curLabel = s.format(Str.CURRENT_DEST_FMT, friendlyShareDestinationLabel(curDir))
            addUnique("current", curLabel, curDir, ShareDestinationKind.CURRENT)
        }

        for (gd in gdriveServers.value) {
            addUnique("gdrive:${gd.id}", gd.name, gd.rootPath, ShareDestinationKind.GDRIVE)
        }

        for (ftp in ftpServers.value) {
            addUnique("ftp:${ftp.id}", ftp.name, ftp.rootPath, ShareDestinationKind.FTP)
        }

        for (vol in volumes.value) {
            val root = vol.root ?: continue
            if (vol.kind == VolumeKind.PRIMARY) continue
            addUnique("vol:${vol.id}", vol.name, root, ShareDestinationKind.VOLUME)
        }

        for (fav in favorites.value) {
            if (!fav.isDirectory) continue
            addUnique("fav:${fav.id}", fav.name, fav.path, ShareDestinationKind.FAVORITE)
        }

        return result
    }

    fun handleIncomingShare(context: Context, raw: RawShareIntent) {
        val appContext = context.applicationContext ?: context
        if (_saveSharedState.value?.isSaving == true) {
            pendingIncomingShares.addLast(PendingIncomingShare.Raw(appContext, raw))
            emitSimpleMessage(AppI18n.current()[Str.QUEUED_INCOMING_SHARE])
            return
        }
        shareResolveJob?.cancel()
        shareResolveJob = vmScope.launch {
            val payload = ShareIntentExtractor.resolvePayload(appContext, raw) ?: return@launch
            presentOrQueueIncomingPayload(payload)
        }
    }

    internal fun presentOrQueueIncomingPayload(payload: IncomingSharePayload) {
        if (payload is IncomingSharePayload.Files && payload.items.isEmpty()) {
            if (payload.skippedBlockedCount > 0) {
                emitSimpleMessage(AppI18n.current()[Str.BLOCKED_UNSAFE_URI])
            }
            return
        }
        if (_saveSharedState.value?.isSaving == true) {
            pendingIncomingShares.addLast(PendingIncomingShare.Resolved(payload))
            emitSimpleMessage(AppI18n.current()[Str.QUEUED_INCOMING_SHARE])
            return
        }
        val defaultDest = defaultShareReceivedPath()
        val editableName = when (payload) {
            is IncomingSharePayload.Text -> payload.suggestedFileName
            is IncomingSharePayload.Files -> if (payload.items.size == 1) payload.items.first().displayName else ""
        }
        _saveSharedState.value = SaveSharedUiState(
            payload = payload,
            editableFileName = editableName,
            selectedDestination = defaultDest,
            selectedDestinationLabel = friendlyShareDestinationLabel(defaultDest),
            conflictPolicy = PeerConflictPolicy.RENAME,
        )
    }

    private fun drainNextPendingShare() {
        if (_saveSharedState.value != null) return
        val next = pendingIncomingShares.removeFirstOrNull() ?: return
        when (next) {
            is PendingIncomingShare.Raw -> handleIncomingShare(next.appContext, next.raw)
            is PendingIncomingShare.Resolved -> presentOrQueueIncomingPayload(next.payload)
        }
    }

    fun selectShareDestination(path: VfsPath, label: String? = null) {
        val cur = _saveSharedState.value ?: return
        if (cur.isSaving) return
        _saveSharedState.value = cur.copy(
            selectedDestination = path,
            selectedDestinationLabel = label ?: friendlyShareDestinationLabel(path),
            errorMessage = null,
            reauthGDriveServerId = null,
        )
    }

    fun updateShareFileName(name: String) {
        val cur = _saveSharedState.value ?: return
        if (cur.isSaving) return
        _saveSharedState.value = cur.copy(
            editableFileName = name,
            errorMessage = null,
        )
    }

    fun updateShareConflictPolicy(policy: PeerConflictPolicy) {
        val cur = _saveSharedState.value ?: return
        if (cur.isSaving) return
        _saveSharedState.value = cur.copy(conflictPolicy = policy)
    }

    fun clearShareSaveError() {
        val cur = _saveSharedState.value ?: return
        _saveSharedState.value = cur.copy(errorMessage = null, reauthGDriveServerId = null)
    }

    fun startSaveSharedFiles(context: Context) {
        val cur = _saveSharedState.value ?: return
        if (cur.isSaving) return

        val appContext = context.applicationContext ?: context
        val dest = cur.selectedDestination
        val destLabel = cur.selectedDestinationLabel
        val fatSafe = dest is VfsPath.Saf
        val isSingleEditable = cur.payload is IncomingSharePayload.Text ||
            (cur.payload is IncomingSharePayload.Files && cur.payload.items.size == 1)

        if (isSingleEditable) {
            val trimmed = cur.editableFileName.trim()
            val v = PathUtils.validateName(trimmed, fatSafe = fatSafe)
            if (v is NameValidation.Invalid) {
                _saveSharedState.value = cur.copy(errorMessage = v.reason)
                return
            }
        }

        val sources = ShareIntentExtractor.buildImportSources(
            context = appContext,
            payload = cur.payload,
            editedSingleName = if (isSingleEditable) cur.editableFileName.trim() else null,
            fatSafe = fatSafe,
        )
        startSaveSharedSources(sources)
    }

    internal fun startSaveSharedSources(sources: List<com.minifile.explorer.core.share.ShareImportSource>) {
        val cur = _saveSharedState.value ?: return
        if (cur.isSaving) return
        val dest = cur.selectedDestination
        if (sources.isEmpty()) {
            _saveSharedState.value = null
            drainNextPendingShare()
            return
        }

        val knownTotal = if (sources.all { it.sizeBytes >= 0L }) sources.sumOf { it.sizeBytes } else -1L
        val cancelFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        shareSaveCancelled = cancelFlag
        _saveSharedState.value = cur.copy(
            isSaving = true,
            errorMessage = null,
            reauthGDriveServerId = null,
            progress = ShareImportProgress(
                processedItems = 0,
                totalItems = sources.size,
                currentItemName = sources.first().desiredName,
                processedBytes = 0L,
                totalBytes = knownTotal,
            ),
        )

        shareSaveJob = vmScope.launch {
            val res = withContext(ioDispatcher) {
                deps.shareImportEngine.importItems(
                    sources = sources,
                    destinationDir = dest,
                    conflictPolicy = cur.conflictPolicy,
                    isCancelled = { cancelFlag.get() },
                    onProgress = { progress ->
                        val latest = _saveSharedState.value
                        if (latest != null && latest.isSaving) {
                            _saveSharedState.value = latest.copy(progress = progress)
                        }
                    },
                )
            }

            val finalDest = res.destinationDir
            val finalLabel = friendlyShareDestinationLabel(finalDest)
            if (_browser.value.currentDir == finalDest || _browser.value.currentDir == dest) {
                reloadCurrentDirectory(silent = true)
            }
            deps.refreshVolumesAction()

            val sEnd = AppI18n.current()
            when {
                res.cancelled -> {
                    _saveSharedState.value = null
                    val msg = if (res.saved.isNotEmpty()) {
                        sEnd.format(Str.SAVE_CANCELLED_PARTIAL, res.saved.size, finalLabel)
                    } else {
                        sEnd[Str.SAVE_CANCELLED]
                    }
                    emitShareSnackbar(
                        message = msg,
                        destinationToOpen = if (res.saved.isNotEmpty()) finalDest else null,
                    )
                    drainNextPendingShare()
                }

                res.saved.isEmpty() && res.failed.isNotEmpty() -> {
                    val err = res.failed.first().second
                    val latest = _saveSharedState.value
                    if (latest != null) {
                        _saveSharedState.value = latest.copy(
                            isSaving = false,
                            progress = null,
                            errorMessage = err,
                            reauthGDriveServerId = res.reauthGDriveServerId,
                        )
                    } else {
                        emitShareSnackbar(
                            message = sEnd.format(Str.SAVE_FAILED_FMT, err),
                            destinationToOpen = null,
                            reauthGDriveServerId = res.reauthGDriveServerId,
                        )
                        drainNextPendingShare()
                    }
                }

                else -> {
                    _saveSharedState.value = null
                    val msg = when {
                        res.saved.size == 1 && res.skipped.isEmpty() && res.failed.isEmpty() ->
                            sEnd.format(Str.SAVED_SINGLE_TO, res.saved.first().name, finalLabel)

                        res.saved.isNotEmpty() -> buildString {
                            append(sEnd.format(Str.SAVED_MULTI_TO, sEnd.filesCount(res.saved.size), finalLabel))
                            if (res.skipped.isNotEmpty()) append(sEnd.format(Str.SKIPPED_SUFFIX, res.skipped.size))
                            if (res.failed.isNotEmpty()) append(sEnd.format(Str.FAILED_COUNT_SUFFIX, res.failed.size))
                        }

                        else -> sEnd.format(Str.SKIPPED_EXISTING_IN, sEnd.filesCount(res.skipped.size), finalLabel)
                    }
                    emitShareSnackbar(
                        message = msg,
                        destinationToOpen = finalDest,
                    )
                    drainNextPendingShare()
                }
            }
        }
    }

    fun cancelOrDismissShareSave() {
        val cur = _saveSharedState.value ?: return
        if (cur.isSaving) {
            shareSaveCancelled.set(true)
        } else {
            shareResolveJob?.cancel()
            _saveSharedState.value = null
            drainNextPendingShare()
        }
    }

    private fun emitShareSnackbar(
        message: String,
        destinationToOpen: VfsPath?,
        reauthGDriveServerId: String? = null,
    ) {
        val s = AppI18n.current()
        _snackbarMessages.tryEmit(
            OperationCompletionEvent(
                id = "share-save-${System.currentTimeMillis()}",
                kind = OperationKind.RECEIVE,
                title = s[Str.SAVE_TO_FILES],
                snackbarMessage = message,
                destinationToOpen = destinationToOpen,
                openLabel = s[Str.OPEN],
                reauthGDriveServerId = reauthGDriveServerId,
            ),
        )
    }

    fun emitSimpleMessage(text: String, destinationToOpen: VfsPath? = null) {
        _snackbarMessages.tryEmit(
            OperationCompletionEvent(
                id = "info-${System.currentTimeMillis()}",
                kind = OperationKind.COPY,
                title = "MiniFile",
                snackbarMessage = text,
                destinationToOpen = destinationToOpen,
            ),
        )
    }
}

/** Scheme B "Scan QR to Join" progress (spec §3.7 `JoinHotspotDialog`). */
sealed interface JoinUiState {
    data class Joining(val ssid: String) : JoinUiState
    data class FindingHost(val label: String) : JoinUiState
    data class Found(val peer: PeerInfo) : JoinUiState
    /**
     * [wifiFallback] is set when joining the scanned Wi-Fi itself failed (e.g. OxygenOS / ColorOS rejected the
     * `WifiNetworkSpecifier` request): the dialog then offers "Join via System Wi-Fi" + "Copy password".
     */
    data class Failed(val message: String, val wifiFallback: WifiJoinFallback? = null) : JoinUiState
}

/** Credentials of a scanned `WIFI:` / `minifile://hotspot` QR, kept for the system Wi-Fi fallback. */
data class WifiJoinFallback(val ssid: String, val password: String, val sec: String) {
    override fun toString(): String = "WifiJoinFallback(ssid=$ssid, password=***, sec=$sec)"
}
