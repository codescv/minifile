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
import com.minifile.explorer.core.gdrive.GDriveAuthException
import com.minifile.explorer.core.gdrive.revokeOAuthTokenBestEffort
import com.minifile.explorer.core.model.AppSettings
import com.minifile.explorer.core.model.Favorite
import com.minifile.explorer.core.model.FtpServerBookmark
import com.minifile.explorer.core.model.GDriveAuthMode
import com.minifile.explorer.core.model.GDriveServerBookmark
import com.minifile.explorer.core.model.OperationKind
import com.minifile.explorer.core.model.SortSpec
import com.minifile.explorer.core.model.TransferMode
import com.minifile.explorer.core.model.ViewMode
import com.minifile.explorer.core.ops.ConflictResolution
import com.minifile.explorer.core.ops.ConflictResolver
import com.minifile.explorer.core.ops.ProgressSink
import com.minifile.explorer.core.ops.SearchHit
import com.minifile.explorer.core.peer.PeerHttpClient
import com.minifile.explorer.core.peer.PeerInfo
import com.minifile.explorer.core.peer.PeerPathPolicy
import com.minifile.explorer.core.peer.PeerSendItem
import com.minifile.explorer.core.peer.PeerSendResult
import com.minifile.explorer.core.peer.PeerTransferEngine
import com.minifile.explorer.core.peer.PeerTransferException
import com.minifile.explorer.core.vfs.NameValidation
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.core.vfs.Volume
import com.minifile.explorer.data.gdrive.AndroidAccountTokenProvider
import com.minifile.explorer.data.gdrive.GDriveSignInState
import com.minifile.explorer.di.AppContainer
import com.minifile.explorer.service.OperationCompletionEvent
import com.minifile.explorer.service.OperationRunOutcome
import com.minifile.explorer.ui.util.UiFormatters
import java.io.FileNotFoundException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MainTab(val label: String) {
    BROWSE("Browse"),
    FAVORITES("Favorites"),
    WIRELESS("Transfer"),
    TOOLS("Settings"),
}

sealed interface OverlayScreen {
    data class TextEditor(val path: VfsPath, val forceEdit: Boolean = false) : OverlayScreen
    data class ArchivePreview(val path: VfsPath) : OverlayScreen
    data class PdfViewer(val path: VfsPath) : OverlayScreen
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
) {
    val isSelectionMode: Boolean get() = selectedPaths.isNotEmpty()
}

class MainViewModel(
    val container: AppContainer,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    companion object {
        private const val STATE_TAB = "saved_tab"
        private const val STATE_DIR = "saved_dir"
        private const val STATE_ROOT_NAME = "saved_root_name"
        private const val STATE_ROOT_BASE = "saved_root_base"

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    val handle = extras.createSavedStateHandle()
                    return MainViewModel(container, handle) as T
                }
            }
    }

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

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
    val favorites: StateFlow<List<Favorite>> = container.favoritesRepository.favorites
    val ftpServers: StateFlow<List<FtpServerBookmark>> = container.ftpServersRepository.servers
    val gdriveServers: StateFlow<List<GDriveServerBookmark>> = container.gdriveServersRepository.servers
    val gdriveSignInState: StateFlow<GDriveSignInState> = container.gdriveSignInController.state
    val volumes: StateFlow<List<Volume>> = container.volumeProvider.volumes
    val clipboard = ClipboardHolder.state
    val peers: StateFlow<List<PeerInfo>> = container.peerDiscoveryHolder.peers
    val isScanningPeers: StateFlow<Boolean> = container.peerDiscoveryHolder.isScanning

    private val _reauthGDriveBookmark = MutableStateFlow<GDriveServerBookmark?>(null)
    val reauthGDriveBookmark: StateFlow<GDriveServerBookmark?> = _reauthGDriveBookmark.asStateFlow()

    private var directoryLoadJob: Job? = null
    private var recursiveSearchJob: Job? = null

    init {
        viewModelScope.launch {
            container.volumeProvider.refresh()
        }
        var lastShowHidden = settings.value.showHidden
        viewModelScope.launch {
            settings.collect { s ->
                recomputeVisibleEntries(s)
                if (s.showHidden != lastShowHidden) {
                    lastShowHidden = s.showHidden
                    if (_browser.value.currentDir != null) {
                        reloadCurrentDirectory(silent = true)
                    }
                }
            }
        }
        viewModelScope.launch {
            container.operationRegistry.completions.collect { event ->
                _snackbarMessages.tryEmit(event)
                if (_browser.value.currentDir != null) {
                    reloadCurrentDirectory(silent = true)
                }
                container.volumeProvider.refresh()
            }
        }

        // Restore directory from SavedStateHandle (process death E15) or rememberLastFolder setting
        val savedDirRaw = savedStateHandle.get<String>(STATE_DIR)
            ?: settings.value.takeIf { it.rememberLastFolder }?.lastFolder
        if (savedDirRaw != null) {
            val parsed = runCatching { VfsPath.parse(savedDirRaw) }.getOrNull()
            val rootName = savedStateHandle.get<String>(STATE_ROOT_NAME) ?: inferRootName(parsed)
            val rootBase = savedStateHandle.get<String>(STATE_ROOT_BASE)
                ?.let { runCatching { VfsPath.parse(it) }.getOrNull() }
                ?: inferRootBase(parsed)
            if (parsed != null) {
                navigateToDirectory(parsed, rootVolumeName = rootName, rootVolumeBase = rootBase)
            }
        }
    }

    private fun restoreInitialTab(): MainTab {
        val raw = savedStateHandle.get<String>(STATE_TAB) ?: return MainTab.BROWSE
        return MainTab.entries.firstOrNull { it.name == raw } ?: MainTab.BROWSE
    }

    fun selectTab(tab: MainTab) {
        _overlay.value = null
        _activeTab.value = tab
        savedStateHandle[STATE_TAB] = tab.name
        if (tab == MainTab.BROWSE && _browser.value.currentDir == null) {
            viewModelScope.launch { container.volumeProvider.refresh() }
        }
    }

    fun openOverlay(screen: OverlayScreen) {
        _overlay.value = screen
    }

    fun closeOverlay() {
        _overlay.value = null
        if (_browser.value.currentDir != null) {
            reloadCurrentDirectory(silent = true)
        }
    }

    fun refreshVolumes() {
        viewModelScope.launch {
            container.volumeProvider.refresh()
            // If currently browsing an unmounted volume, step back to Storage Home gracefully
            val cur = _browser.value.currentDir
            if (cur is VfsPath.Ftp) {
                if (container.ftpServersRepository.findById(cur.serverId) == null) {
                    navigateToStorageHome()
                }
            } else if (cur is VfsPath.GDrive) {
                if (container.gdriveServersRepository.findById(cur.serverId) == null) {
                    navigateToStorageHome()
                }
            } else if (cur != null) {
                val stillExists = withContext(Dispatchers.IO) {
                    runCatching { container.fileSystem.exists(cur) }.getOrDefault(false)
                }
                if (!stillExists) {
                    navigateToStorageHome()
                    emitSimpleMessage("Volume was disconnected")
                }
            }
        }
    }

    fun navigateToStorageHome() {
        directoryLoadJob?.cancel()
        recursiveSearchJob?.cancel()
        val hadSelection = _browser.value.selectedPaths.isNotEmpty()
        _browser.value = BrowserUiState()
        savedStateHandle.remove<String>(STATE_DIR)
        savedStateHandle.remove<String>(STATE_ROOT_NAME)
        savedStateHandle.remove<String>(STATE_ROOT_BASE)
        if (hadSelection) {
            emitSimpleMessage("Selection cleared")
        }
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
        viewModelScope.launch {
            val created = container.ftpServersRepository.add(
                name = name,
                host = host,
                port = port,
                anonymous = anonymous,
                username = username,
                password = password,
                initialPath = initialPath,
            )
            emitSimpleMessage("Added FTP server '${created.name}'")
            if (openAfterAdd) {
                openFtpServer(created)
            }
        }
    }

    fun updateFtpServer(server: FtpServerBookmark) {
        viewModelScope.launch {
            container.ftpServersRepository.update(server)
            emitSimpleMessage("Updated FTP server '${server.name}'")
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
        val existing = container.ftpServersRepository.findById(id)
        viewModelScope.launch {
            container.ftpServersRepository.remove(id)
            val cur = _browser.value.currentDir
            if (cur is VfsPath.Ftp && cur.serverId == id) {
                navigateToStorageHome()
            }
            if (existing != null) {
                emitSimpleMessage("Removed FTP server '${existing.name}'")
            }
        }
    }

    fun openGDriveServer(server: GDriveServerBookmark) {
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
        viewModelScope.launch {
            val created = container.gdriveServersRepository.add(bookmark)
            container.googleDriveFileSystem.invalidateServer(created.id)
            emitSimpleMessage("Added Google Drive '${created.name}'")
            if (openAfterAdd) {
                openGDriveServer(created)
            }
        }
    }

    fun updateGDriveServer(server: GDriveServerBookmark) {
        viewModelScope.launch {
            container.gdriveServersRepository.update(server)
            container.googleDriveFileSystem.invalidateServer(server.id)
            emitSimpleMessage("Updated Google Drive '${server.name}'")
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
        val existing = container.gdriveServersRepository.findById(id)
        val cachedToken = container.googleDriveFileSystem.peekCachedToken(id)
        viewModelScope.launch {
            container.gdriveServersRepository.remove(id)
            container.googleDriveFileSystem.invalidateServer(id)
            val cur = _browser.value.currentDir
            if (cur is VfsPath.GDrive && cur.serverId == id) {
                navigateToStorageHome()
            }
            if (existing != null) {
                emitSimpleMessage("Removed Google Drive '${existing.name}'")
                withContext(Dispatchers.IO) {
                    if (existing.authMode == GDriveAuthMode.SYSTEM_ACCOUNT) {
                        AndroidAccountTokenProvider.invalidateTokenBestEffort(
                            container.appContext,
                            cachedToken,
                        )
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
            scope = viewModelScope,
            clientId = clientId,
            clientSecret = clientSecret,
            displayName = displayName,
            initialPath = initialPath,
            existingBookmark = existingBookmark,
        )
    }

    fun cancelGDriveSignIn() {
        container.gdriveSignInController.cancel()
    }

    fun resetGDriveSignIn() {
        container.gdriveSignInController.reset()
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
            scope = viewModelScope,
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
            scope = viewModelScope,
            activity = activity,
            accountName = accountName,
            displayName = displayName,
            initialPath = initialPath,
            existingBookmark = existingBookmark,
        )
    }

    fun openReauthGDriveDialog(serverId: String) {
        _reauthGDriveBookmark.value = container.gdriveServersRepository.findById(serverId)
    }

    fun dismissReauthGDriveDialog() {
        container.gdriveSignInController.cancel()
        _reauthGDriveBookmark.value = null
    }

    override fun onCleared() {
        container.gdriveSignInController.cancel()
        super.onCleared()
    }

    fun openFavoriteInBrowse(fav: Favorite, context: Context? = null) {
        viewModelScope.launch {
            container.favoritesRepository.touch(fav.id)
            val node = withContext(Dispatchers.IO) {
                runCatching { container.fileSystem.stat(fav.path) }.getOrNull()
            }
            if (node != null && node.isDirectory != fav.isDirectory) {
                container.favoritesRepository.add(
                    name = fav.name,
                    path = fav.path,
                    color = fav.color,
                    isDirectory = node.isDirectory,
                )
            }
            val isFileFav = if (node != null) !node.isDirectory else !fav.isDirectory
            if (isFileFav) {
                val parentDir = withContext(Dispatchers.IO) {
                    runCatching { container.fileSystem.parent(fav.path) }.getOrNull()
                }
                _activeTab.value = MainTab.BROWSE
                savedStateHandle[STATE_TAB] = MainTab.BROWSE.name
                if (parentDir != null) {
                    navigateToDirectory(
                        dir = parentDir,
                        rootVolumeName = inferRootName(parentDir),
                        rootVolumeBase = inferRootBase(parentDir),
                    )
                }
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
                        withContext(Dispatchers.IO) { UiFormatters.shouldOpenAsText(node, container.fileSystem) } -> {
                        openOverlay(OverlayScreen.TextEditor(targetPath))
                    }

                    else -> {
                        _overlay.value = null
                        if (context != null && node != null) {
                            UiFormatters.openExternallyAsync(
                                context = context,
                                safFs = container.safFileSystem,
                                fs = container.fileSystem,
                                node = node,
                            ).onFailure { err ->
                                emitSimpleMessage("Cannot open ${node.name}: ${err.message}")
                            }
                        }
                    }
                }
            } else {
                _overlay.value = null
                _activeTab.value = MainTab.BROWSE
                savedStateHandle[STATE_TAB] = MainTab.BROWSE.name
                navigateToDirectory(
                    dir = fav.path,
                    rootVolumeName = inferRootName(fav.path),
                    rootVolumeBase = inferRootBase(fav.path),
                )
            }
        }
    }

    fun navigateToDirectory(
        dir: VfsPath,
        rootVolumeName: String? = null,
        rootVolumeBase: VfsPath? = null,
    ) {
        val prev = _browser.value
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

        container.settingsRepository.update { s ->
            if (s.rememberLastFolder) s.copy(lastFolder = dir.serialize()) else s
        }

        recursiveSearchJob?.cancel()
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
        )

        if (hadSelection) {
            emitSimpleMessage("Selection cleared")
        }

        loadDirectoryInternal(dir, showSpinner = true)
    }

    fun reloadCurrentDirectory(silent: Boolean = false) {
        val dir = _browser.value.currentDir ?: return
        if (!silent && dir is VfsPath.GDrive) {
            container.googleDriveFileSystem.invalidate(dir)
        }
        loadDirectoryInternal(dir, showSpinner = !silent)
    }

    private fun loadDirectoryInternal(dir: VfsPath, showSpinner: Boolean) {
        directoryLoadJob?.cancel()
        directoryLoadJob = viewModelScope.launch {
            if (showSpinner) {
                _browser.value = _browser.value.copy(isLoading = true, errorMessage = null)
            }
            try {
                // Always fetch hidden entries into rawEntries so toggling show_hidden is instant,
                // and filter according to settings in recomputeVisibleEntries.
                val (dirStat, allChildren) = withContext(Dispatchers.IO) {
                    val st = container.fileSystem.stat(dir)
                    val list = container.fileSystem.list(dir, includeHidden = true)
                    st to list
                }
                val validSelected = _browser.value.selectedPaths.intersect(allChildren.map { it.path }.toSet())
                _browser.value = _browser.value.copy(
                    currentDirNode = dirStat,
                    rawEntries = allChildren,
                    isLoading = false,
                    errorMessage = null,
                    permissionRequired = false,
                    selectedPaths = validSelected,
                )
                recomputeVisibleEntries(settings.value)
            } catch (e: Exception) {
                val msg = e.message ?: "Unable to read folder"
                if (e is GDriveAuthException && e.needsInteraction && dir is VfsPath.GDrive) {
                    _snackbarMessages.tryEmit(
                        OperationCompletionEvent(
                            id = "gdrive-auth-${System.currentTimeMillis()}",
                            kind = OperationKind.COPY,
                            title = "Google Drive",
                            snackbarMessage = "Google Drive sign-in expired",
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
                    ?: container.ftpServersRepository.findById(dir.serverId)?.rootPath
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
                    ?: container.gdriveServersRepository.findById(dir.serverId)?.rootPath
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
        if (path == null) return "Internal"
        if (path is VfsPath.Ftp) {
            return container.ftpServersRepository.findById(path.serverId)?.name ?: "FTP (${path.serverId})"
        }
        if (path is VfsPath.GDrive) {
            return container.gdriveServersRepository.findById(path.serverId)?.name ?: "Google Drive (${path.serverId})"
        }
        for (v in volumes.value) {
            val r = v.root ?: continue
            if (PathUtils.isAncestor(r, path)) return v.name
        }
        val primary = Environment.getExternalStorageDirectory().absolutePath
        if (path is VfsPath.Local && PathUtils.isAncestor(VfsPath.Local(primary), path)) {
            return "Internal"
        }
        return path.displayName
    }

    private fun inferRootBase(path: VfsPath?): VfsPath? {
        if (path == null) return null
        for (v in volumes.value) {
            val r = v.root ?: continue
            if (PathUtils.isAncestor(r, path)) return r
        }
        val primary = VfsPath.Local(Environment.getExternalStorageDirectory().absolutePath)
        if (path is VfsPath.Local && PathUtils.isAncestor(primary, path)) return primary
        return when (path) {
            is VfsPath.Local -> VfsPath.Local("/")
            is VfsPath.Saf -> container.persistedTreeStore.findGrantByTreeUri(path.treeUri)?.toVfsPath() ?: path
            is VfsPath.Ftp -> container.ftpServersRepository.findById(path.serverId)?.rootPath
                ?: VfsPath.Ftp(path.serverId, "/")
            is VfsPath.GDrive -> container.gdriveServersRepository.findById(path.serverId)?.rootPath
                ?: VfsPath.GDrive(path.serverId, "/")
        }
    }

    // --- Back button state machine (DESIGN.md §1.1) ---
    fun canHandleBack(): Boolean {
        if (_overlay.value != null) return true
        if (_activeTab.value == MainTab.BROWSE) {
            val b = _browser.value
            if (b.isSelectionMode) return true
            if (b.searchActive) return true
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
            b.isSelectionMode -> clearSelection()
            b.searchActive -> closeSearch()
            b.currentDir != null -> navigateUpOrHome()
        }
    }

    fun navigateUpOrHome() {
        val b = _browser.value
        val cur = b.currentDir ?: return
        val base = b.rootVolumeBase
        if (base != null && cur == base) {
            navigateToStorageHome()
            return
        }
        viewModelScope.launch {
            val parent = withContext(Dispatchers.IO) { container.fileSystem.parent(cur) }
            if (parent == null || (base != null && !PathUtils.isAncestor(base, parent))) {
                navigateToStorageHome()
            } else {
                navigateToDirectory(parent, b.rootVolumeName, b.rootVolumeBase)
            }
        }
    }

    // --- Search ---
    fun openSearch() {
        _browser.value = _browser.value.copy(searchActive = true)
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
        recursiveSearchJob = viewModelScope.launch {
            val buffer = ArrayList<SearchHit>()
            var cancelled = false
            val sink = object : ProgressSink {
                override fun onProgress(p: com.minifile.explorer.core.model.OperationProgress) = Unit
                override fun isCancelled(): Boolean = cancelled
            }
            try {
                withContext(Dispatchers.IO) {
                    container.fileOperations.searchRecursive(
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
        container.settingsRepository.update { it.copy(showHidden = !it.showHidden) }
    }

    fun toggleViewMode() {
        container.settingsRepository.update {
            it.copy(viewMode = if (it.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST)
        }
    }

    fun updateSort(sortSpec: SortSpec) {
        container.settingsRepository.update { it.copy(sort = sortSpec) }
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
        viewModelScope.launch {
            container.favoritesRepository.touch(favorite.id)
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
        val verbPresent = if (mode == TransferMode.COPY) "Copying" else "Moving"
        val verbPast = if (mode == TransferMode.COPY) "Copied" else "Moved"
        val countLabel = if (items.size == 1) items.first().displayName else "${items.size} items"

        container.operationRegistry.enqueue(
            context = context,
            kind = opKind,
            title = "$verbPresent $countLabel to $destinationLabel",
            retryAction = {
                executeTransfer(context, items, mode, destinationDir, destinationLabel, clearClipboardOnComplete)
            },
        ) { _, sink ->
            val resolver = buildInteractiveConflictResolver()
            val res = if (mode == TransferMode.COPY) {
                container.fileOperations.copy(items, destinationDir, resolver, sink)
            } else {
                container.fileOperations.move(items, destinationDir, resolver, sink)
            }
            if (clearClipboardOnComplete && !res.cancelled && res.failed.isEmpty()) {
                ClipboardHolder.clear()
            } else if (clearClipboardOnComplete && mode == TransferMode.COPY) {
                ClipboardHolder.clear()
            }
            val firstFailed = res.failed.firstOrNull()
            val expiredGDriveServerId = when {
                firstFailed?.second?.contains("sign-in expired", ignoreCase = true) == true -> {
                    (firstFailed.first as? VfsPath.GDrive)?.serverId
                        ?: (destinationDir as? VfsPath.GDrive)?.serverId
                }
                else -> null
            }
            val summary = if (expiredGDriveServerId != null && res.succeeded == 0) {
                "Google Drive sign-in expired"
            } else {
                buildString {
                    append("$verbPast ${res.succeeded} item${if (res.succeeded == 1) "" else "s"} to $destinationLabel")
                    if (res.skipped > 0) append(" (${res.skipped} skipped)")
                    if (res.failed.isNotEmpty()) append(" · ${res.failed.size} failed: ${res.failed.first().second}")
                }
            }
            OperationRunOutcome(
                message = summary,
                destinationToOpen = if (expiredGDriveServerId == null && destinationDir != _browser.value.currentDir) {
                    destinationDir
                } else {
                    null
                },
                openLabel = "Open",
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
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    container.fileOperations.createFolder(dir, name.trim())
                }
                reloadCurrentDirectory(silent = true)
            } catch (e: Exception) {
                onError(e.message ?: "Failed to create folder")
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
        viewModelScope.launch {
            try {
                val created = withContext(Dispatchers.IO) {
                    container.fileOperations.createFile(dir, name.trim())
                }
                reloadCurrentDirectory(silent = true)
                if (openInEditor) {
                    openOverlay(OverlayScreen.TextEditor(created, forceEdit = true))
                }
            } catch (e: Exception) {
                onError(e.message ?: "Failed to create file")
            }
        }
    }

    fun renameItem(item: VfsPath, newName: String, onError: (String) -> Unit, onSuccess: () -> Unit) {
        val v = PathUtils.validateName(newName.trim(), fatSafe = item is VfsPath.Saf)
        if (v is NameValidation.Invalid) {
            onError(v.reason)
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    container.fileOperations.rename(item, newName.trim())
                }
                clearSelection()
                reloadCurrentDirectory(silent = true)
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Rename failed")
            }
        }
    }

    fun deleteItems(context: Context, items: List<VfsPath>) {
        if (items.isEmpty()) return
        clearSelection()
        val allGDrive = items.all { it is VfsPath.GDrive }
        val label = if (items.size == 1) items.first().displayName else "${items.size} items"
        container.operationRegistry.enqueue(
            context = context,
            kind = OperationKind.DELETE,
            title = if (allGDrive) "Moving $label to trash" else "Deleting $label",
        ) { _, sink ->
            val res = container.fileOperations.delete(items, sink)
            val firstFailed = res.failed.firstOrNull()
            val expiredServerId = if (firstFailed?.second?.contains("sign-in expired", ignoreCase = true) == true) {
                (firstFailed.first as? VfsPath.GDrive)?.serverId
            } else {
                null
            }
            val msg = when {
                expiredServerId != null && res.succeeded == 0 -> "Google Drive sign-in expired"
                allGDrive && res.succeeded > 0 -> buildString {
                    append("Moved to Google Drive trash")
                    if (items.size > 1) append(" (${res.succeeded} items)")
                    if (res.failed.isNotEmpty()) append(" (${res.failed.size} failed)")
                }
                else -> buildString {
                    append("Deleted ${res.succeeded} item${if (res.succeeded == 1) "" else "s"}")
                    if (res.failed.isNotEmpty()) append(" (${res.failed.size} failed)")
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
        container.operationRegistry.enqueue(
            context = context,
            kind = OperationKind.COMPRESS,
            title = "Compressing $finalZipName",
            retryAction = { compressItemsToZip(context, items, destinationDir, archiveName, compressionLevel) },
        ) { _, sink ->
            val created = container.zipArchiver.compress(
                items = items,
                dstDir = destinationDir,
                archiveName = finalZipName,
                level = compressionLevel,
                sink = sink,
            )
            OperationRunOutcome(
                message = "${created.displayName} created",
                destinationToOpen = created,
                openLabel = "Open",
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
        container.operationRegistry.enqueue(
            context = context,
            kind = OperationKind.EXTRACT,
            title = "Extracting ${zipPath.displayName}",
            retryAction = {
                extractArchive(context, zipPath, destinationDir, createSubfolderName, selectedEntries, overwrite, allowBombOverride)
            },
        ) { _, sink ->
            val actualDest = if (!createSubfolderName.isNullOrBlank()) {
                val existingNames = runCatching {
                    container.fileSystem.list(destinationDir, includeHidden = true).map { it.name }.toSet()
                }.getOrDefault(emptySet())
                val folderName = if (overwrite) createSubfolderName else PathUtils.uniqueName(createSubfolderName, existingNames, isDirectory = true)
                val existingChild = container.fileSystem.child(destinationDir, folderName)
                if (container.fileSystem.stat(existingChild)?.isDirectory == true) {
                    existingChild
                } else {
                    container.fileSystem.createDirectory(destinationDir, folderName)
                }
            } else {
                destinationDir
            }

            val res = container.zipArchiver.extract(
                zip = zipPath,
                dstDir = actualDest,
                entries = selectedEntries,
                overwrite = overwrite,
                allowLargeArchive = allowBombOverride,
                sink = sink,
            )
            val unsafeNote = when (res.unsafeEntriesSkipped) {
                0 -> ""
                1 -> " · 1 unsafe entry skipped"
                else -> " · ${res.unsafeEntriesSkipped} unsafe entries skipped"
            }
            OperationRunOutcome(
                message = "Extracted ${res.extractedFiles} file${if (res.extractedFiles == 1) "" else "s"}$unsafeNote",
                destinationToOpen = actualDest,
                openLabel = "Open",
                unsafeSkipped = res.unsafeEntriesSkipped,
            )
        }
    }

    fun toggleNomedia(dirPath: VfsPath) {
        viewModelScope.launch {
            try {
                val currentlyHas = withContext(Dispatchers.IO) {
                    container.nomediaManager.has(dirPath)
                }
                withContext(Dispatchers.IO) {
                    container.nomediaManager.set(dirPath, !currentlyHas)
                }
                reloadCurrentDirectory(silent = true)
                val msg = if (!currentlyHas) {
                    "Hidden '${dirPath.displayName}' from gallery (.nomedia created)"
                } else {
                    "Restored '${dirPath.displayName}' to gallery (.nomedia removed)"
                }
                emitSimpleMessage(msg)
            } catch (e: Exception) {
                emitSimpleMessage("Could not update .nomedia: ${e.message}")
            }
        }
    }

    fun addOrUpdateFavorite(
        name: String,
        path: VfsPath,
        color: Int? = null,
        isDirectory: Boolean = true,
    ) {
        viewModelScope.launch {
            container.favoritesRepository.add(name, path, color, isDirectory = isDirectory)
            emitSimpleMessage("Added '${name.ifBlank { path.displayName }}' to Favorites")
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
        val existing = container.favoritesRepository.findByPath(path) ?: return
        viewModelScope.launch {
            container.favoritesRepository.remove(existing.id)
            emitSimpleMessage("Removed '${existing.name}' from Favorites")
        }
    }

    // --- App-to-App Peer Discovery & Transfer ---
    fun startPeerBrowsing() {
        container.peerDiscoveryHolder.acquireBrowser()
    }

    fun stopPeerBrowsing() {
        container.peerDiscoveryHolder.releaseBrowser()
    }

    fun scanPeersSubnet() {
        container.peerDiscoveryHolder.scanSubnet()
    }

    fun addPeerByAddress(
        rawAddress: String,
        onSuccess: (PeerInfo) -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        val trimmed = rawAddress.trim()
        if (trimmed.isEmpty()) {
            onError("Enter host or IP address")
            return
        }
        viewModelScope.launch {
            try {
                val peer = withContext(Dispatchers.IO) {
                    container.peerDiscoveryHolder.probeAddress(trimmed)
                }
                onSuccess(peer)
            } catch (e: PeerTransferException) {
                onError(e.userMessage)
            } catch (e: Exception) {
                onError(e.message ?: "Could not reach $trimmed")
            }
        }
    }

    fun sendToPeer(
        context: Context,
        peer: PeerInfo,
        items: List<VfsPath>,
    ) {
        if (items.isEmpty()) return
        val appContext = context.applicationContext
        val peerName = PeerPathPolicy.sanitizeDisplayName(peer.name, 64).ifEmpty { peer.host }
        if (peer.readOnly) {
            emitSimpleMessage("$peerName is read-only (Transfer ▸ Settings)")
            return
        }
        clearSelection()
        emitSimpleMessage("Sending to $peerName…")

        val countLabel = if (items.size == 1) items.first().displayName else "${items.size} items"
        val txClient = PeerHttpClient(socketBinder = container.peerDiscoveryHolder::bindTcpSocket)

        container.operationRegistry.enqueue(
            context = appContext,
            kind = OperationKind.SEND,
            title = "Sending $countLabel to $peerName",
            retryAction = { sendToPeer(appContext, peer, items) },
            onCancel = { txClient.close() },
        ) { _, sink ->
            txClient.use { client ->
                try {
                    val sendItems = PeerTransferEngine.collectSendItems(
                        fs = container.fileSystem,
                        roots = items,
                        includeHidden = settings.value.showHidden,
                    )
                    if (sendItems.isEmpty()) {
                        return@use OperationRunOutcome(message = "Nothing to send to $peerName")
                    }
                    val res = PeerTransferEngine(
                        client = client,
                        senderDeviceNameProvider = { settings.value.peerDeviceName },
                    ).send(
                        peer = peer,
                        items = sendItems,
                        sink = sink,
                    )
                    formatPeerSendOutcome(peerName, res, sink.isCancelled())
                } catch (e: PeerTransferException) {
                    if (sink.isCancelled()) {
                        OperationRunOutcome(message = "Sending to $peerName cancelled")
                    } else {
                        OperationRunOutcome(
                            message = e.userMessage,
                            errorSummary = e.userMessage,
                        )
                    }
                }
            }
        }
    }

    fun sendUrisToPeer(
        context: Context,
        peer: PeerInfo,
        uris: List<Uri>,
    ) {
        if (uris.isEmpty()) return
        val appContext = context.applicationContext
        val peerName = PeerPathPolicy.sanitizeDisplayName(peer.name, 64).ifEmpty { peer.host }
        if (peer.readOnly) {
            emitSimpleMessage("$peerName is read-only (Transfer ▸ Settings)")
            return
        }
        emitSimpleMessage("Sending to $peerName…")

        val countLabel = if (uris.size == 1) "1 file" else "${uris.size} files"
        val txClient = PeerHttpClient(socketBinder = container.peerDiscoveryHolder::bindTcpSocket)

        container.operationRegistry.enqueue(
            context = appContext,
            kind = OperationKind.SEND,
            title = "Sending $countLabel to $peerName",
            retryAction = { sendUrisToPeer(appContext, peer, uris) },
            onCancel = { txClient.close() },
        ) { _, sink ->
            txClient.use { client ->
                try {
                    val sendItems = resolveUriSendItems(appContext, uris, sink)
                    if (sink.isCancelled()) {
                        return@use OperationRunOutcome(message = "Sending to $peerName cancelled")
                    }
                    if (sendItems.isEmpty()) {
                        return@use OperationRunOutcome(message = "Nothing to send to $peerName")
                    }
                    val res = PeerTransferEngine(
                        client = client,
                        senderDeviceNameProvider = { settings.value.peerDeviceName },
                    ).send(
                        peer = peer,
                        items = sendItems,
                        sink = sink,
                    )
                    formatPeerSendOutcome(peerName, res, sink.isCancelled())
                } catch (e: PeerTransferException) {
                    if (sink.isCancelled()) {
                        OperationRunOutcome(message = "Sending to $peerName cancelled")
                    } else {
                        OperationRunOutcome(
                            message = e.userMessage,
                            errorSummary = e.userMessage,
                        )
                    }
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
                } ?: throw FileNotFoundException("Cannot open selected document")
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
                            ?: throw FileNotFoundException("Cannot open $uniqueRelName")
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
        if (cancelled || res.cancelled) {
            return OperationRunOutcome(message = "Sending to $peerName cancelled")
        }
        val summary = buildString {
            when {
                res.files > 0 -> {
                    append("Sent ${res.files} file${if (res.files == 1) "" else "s"} to $peerName")
                }
                res.dirs > 0 -> {
                    append("Sent ${res.dirs} folder${if (res.dirs == 1) "" else "s"} to $peerName")
                }
                else -> {
                    append("Sent 0 files to $peerName")
                }
            }
            if (res.skipped > 0) {
                append(" (${res.skipped} skipped)")
            }
        }
        return OperationRunOutcome(message = summary)
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
