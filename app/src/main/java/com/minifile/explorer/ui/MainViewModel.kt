package com.minifile.explorer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Environment
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.minifile.explorer.core.model.AppSettings
import com.minifile.explorer.core.model.Favorite
import com.minifile.explorer.core.model.OperationKind
import com.minifile.explorer.core.model.SortSpec
import com.minifile.explorer.core.model.TransferMode
import com.minifile.explorer.core.model.ViewMode
import com.minifile.explorer.core.ops.ConflictResolution
import com.minifile.explorer.core.ops.ConflictResolver
import com.minifile.explorer.core.ops.ProgressSink
import com.minifile.explorer.core.ops.SearchHit
import com.minifile.explorer.core.vfs.NameValidation
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.core.vfs.Volume
import com.minifile.explorer.data.PersistedTreeStore
import com.minifile.explorer.data.shizuku.PrivilegedShellBridge
import com.minifile.explorer.di.AppContainer
import com.minifile.explorer.service.OperationCompletionEvent
import com.minifile.explorer.service.OperationRunOutcome
import java.io.File
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
    WIRELESS("Wireless"),
    TOOLS("Tools"),
}

sealed interface OverlayScreen {
    data class TextEditor(val path: VfsPath, val forceEdit: Boolean = false) : OverlayScreen
    data class ArchivePreview(val path: VfsPath) : OverlayScreen
    data object InstalledApps : OverlayScreen
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

enum class RestrictedAndroidDirKind(
    val safKey: String,
    val pkgGrantPrefix: String,
    val defaultSafDocId: String,
    val displaySubpath: String,
) {
    DATA(
        safKey = PersistedTreeStore.KEY_ANDROID_DATA,
        pkgGrantPrefix = "pkg_data:",
        defaultSafDocId = "primary:Android/data",
        displaySubpath = "Android/data",
    ),
    OBB(
        safKey = PersistedTreeStore.KEY_ANDROID_OBB,
        pkgGrantPrefix = "pkg_obb:",
        defaultSafDocId = "primary:Android/obb",
        displaySubpath = "Android/obb",
    ),
}

data class RestrictedPackageTarget(
    val kind: RestrictedAndroidDirKind,
    val packageName: String,
    val appLabel: String,
) {
    val safKey: String get() = "${kind.pkgGrantPrefix}$packageName"
    val safInitialDocId: String get() = "${kind.defaultSafDocId}/$packageName"
    val safMountLabel: String
        get() = if (kind == RestrictedAndroidDirKind.OBB) "$appLabel obb" else "$appLabel data"
}

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
    val isRestrictedAndroidDataDir: Boolean = false,
    val restrictedPackageTarget: RestrictedPackageTarget? = null,
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

        fun classifyRestrictedAndroidDir(path: VfsPath): RestrictedAndroidDirKind? {
            val local = path as? VfsPath.Local ?: return null
            val norm = local.normalized
            return when {
                norm.endsWith("/Android/data", ignoreCase = true) -> RestrictedAndroidDirKind.DATA
                norm.endsWith("/Android/obb", ignoreCase = true) -> RestrictedAndroidDirKind.OBB
                else -> null
            }
        }

        fun classifyRestrictedPackageDir(path: VfsPath): Pair<RestrictedAndroidDirKind, String>? {
            val local = path as? VfsPath.Local ?: return null
            val segs = local.normalized.split('/').filter { it.isNotEmpty() }
            if (segs.size < 4) return null
            val androidIdx = segs.indexOfFirst { it.equals("Android", ignoreCase = true) }
            if (androidIdx < 1 || androidIdx + 2 != segs.size - 1) return null
            val kindSeg = segs[androidIdx + 1]
            val pkgSeg = segs[androidIdx + 2]
            if (pkgSeg.isBlank() || pkgSeg == "." || pkgSeg == "..") return null
            val kind = when {
                kindSeg.equals("data", ignoreCase = true) -> RestrictedAndroidDirKind.DATA
                kindSeg.equals("obb", ignoreCase = true) -> RestrictedAndroidDirKind.OBB
                else -> return null
            }
            return kind to pkgSeg
        }

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
    val volumes: StateFlow<List<Volume>> = container.volumeProvider.volumes
    val clipboard = ClipboardHolder.state

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
        var lastPrivilegedActive = PrivilegedShellBridge.state.value.isActive
        viewModelScope.launch {
            PrivilegedShellBridge.state.collect { st ->
                if (st.isActive != lastPrivilegedActive) {
                    lastPrivilegedActive = st.isActive
                    if (_browser.value.currentDir != null) {
                        reloadCurrentDirectory(silent = false)
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
            if (cur != null) {
                val keepOnRestrictedPrimary = cur is VfsPath.Local &&
                    isPrimaryStorageAndroidDir(cur) &&
                    PathUtils.isRestrictedAndroidSubtree(cur.normalized) &&
                    (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED ||
                        Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED_READ_ONLY)
                val stillExists = if (keepOnRestrictedPrimary) {
                    true
                } else {
                    withContext(Dispatchers.IO) {
                        runCatching { container.fileSystem.exists(cur) }.getOrDefault(false)
                    }
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

    fun openFavoriteInBrowse(fav: Favorite) {
        viewModelScope.launch {
            container.favoritesRepository.touch(fav.id)
        }
        _overlay.value = null
        _activeTab.value = MainTab.BROWSE
        savedStateHandle[STATE_TAB] = MainTab.BROWSE.name
        navigateToDirectory(
            dir = fav.path,
            rootVolumeName = inferRootName(fav.path),
            rootVolumeBase = inferRootBase(fav.path),
        )
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
            isRestrictedAndroidDataDir = false,
            restrictedPackageTarget = null,
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
        loadDirectoryInternal(dir, showSpinner = !silent)
    }

    @SuppressLint("SdCardPath")
    private fun isPrimaryStorageAndroidDir(local: VfsPath.Local): Boolean {
        val primaryRoot = runCatching {
            PathUtils.normalizeLocalPath(Environment.getExternalStorageDirectory().absolutePath)
        }.getOrDefault("/storage/emulated/0")
        val norm = local.normalized
        return norm.startsWith("$primaryRoot/", ignoreCase = true) ||
            norm.startsWith("/storage/emulated/0/", ignoreCase = true) ||
            norm.startsWith("/sdcard/", ignoreCase = true)
    }

    private fun loadDirectoryInternal(dir: VfsPath, showSpinner: Boolean) {
        directoryLoadJob?.cancel()
        directoryLoadJob = viewModelScope.launch {
            if (showSpinner) {
                _browser.value = _browser.value.copy(isLoading = true, errorMessage = null)
            }
            try {
                val restrictedKind = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    classifyRestrictedAndroidDir(dir)
                } else {
                    null
                }

                // Case 1: Browsing /Android/data or /Android/obb parent directory on Android 11+
                if (restrictedKind != null && dir is VfsPath.Local) {
                    val isPrimary = isPrimaryStorageAndroidDir(dir)
                    val privilegedActive = withContext(Dispatchers.IO) {
                        PrivilegedShellBridge.hasPrivilegedAccess(container.appContext)
                    }

                    // Scheme 2: Privileged Shell (Shizuku / Root) lists real directories directly
                    if (privilegedActive) {
                        val (privDirStat, privChildren) = withContext(Dispatchers.IO) {
                            val byName = LinkedHashMap<String, VfsNode>()
                            val shellChildren = PrivilegedShellBridge.listDirectory(dir.normalized, includeHidden = true)
                                .orEmpty()
                            for (child in shellChildren) {
                                byName[child.name] = child
                            }
                            ensureOwnPackageEntry(byName, dir, restrictedKind, isPrimary)
                            val st = PrivilegedShellBridge.stat(dir.normalized)
                                ?: VfsNode(
                                    path = dir,
                                    name = dir.displayName,
                                    isDirectory = true,
                                    size = 0L,
                                    lastModified = 0L,
                                    canRead = true,
                                    canWrite = true,
                                )
                            st.copy(canWrite = true) to byName.values.toList()
                        }
                        val validSelected = _browser.value.selectedPaths.intersect(privChildren.map { it.path }.toSet())
                        _browser.value = _browser.value.copy(
                            currentDirNode = privDirStat,
                            rawEntries = privChildren,
                            isLoading = false,
                            errorMessage = null,
                            permissionRequired = false,
                            isRestrictedAndroidDataDir = false,
                            restrictedPackageTarget = null,
                            selectedPaths = validSelected,
                        )
                        recomputeVisibleEntries(settings.value)
                        return@launch
                    }

                    // Existing whole-tree SAF grant (Android 11–12)
                    val existingTreeGrant = if (isPrimary) {
                        container.persistedTreeStore.getGrant(restrictedKind.safKey)
                    } else {
                        null
                    }
                    if (existingTreeGrant != null) {
                        val safRoot = existingTreeGrant.toVfsPath()
                        savedStateHandle[STATE_DIR] = safRoot.serialize()
                        savedStateHandle[STATE_ROOT_NAME] = existingTreeGrant.label
                        savedStateHandle[STATE_ROOT_BASE] = safRoot.serialize()
                        container.settingsRepository.update { s ->
                            if (s.rememberLastFolder) s.copy(lastFolder = safRoot.serialize()) else s
                        }
                        val (safStat, safChildren) = withContext(Dispatchers.IO) {
                            val st = container.fileSystem.stat(safRoot)
                            val list = container.fileSystem.list(safRoot, includeHidden = true)
                            st to list
                        }
                        _browser.value = _browser.value.copy(
                            currentDir = safRoot,
                            currentDirNode = safStat,
                            rootVolumeName = existingTreeGrant.label,
                            rootVolumeBase = safRoot,
                            breadcrumbs = buildBreadcrumbs(safRoot, existingTreeGrant.label, safRoot),
                            rawEntries = safChildren,
                            isLoading = false,
                            errorMessage = null,
                            permissionRequired = false,
                            isRestrictedAndroidDataDir = false,
                            restrictedPackageTarget = null,
                            selectedPaths = emptySet(),
                        )
                        recomputeVisibleEntries(settings.value)
                        return@launch
                    }

                    // Scheme 1: Synthesize installed app folders via QUERY_ALL_PACKAGES so Android/data is never empty
                    val includeSystemApps = settings.value.showHidden
                    val (restrictedDirNode, mergedChildren) = withContext(Dispatchers.IO) {
                        val byName = LinkedHashMap<String, VfsNode>()
                        val directList = runCatching {
                            container.fileSystem.list(dir, includeHidden = true)
                        }.getOrDefault(emptyList())
                        for (child in directList) {
                            byName[child.name] = child
                        }

                        ensureOwnPackageEntry(byName, dir, restrictedKind, isPrimary)

                        // Include any per-package SAF grants (pkg_data:<pkg> / pkg_obb:<pkg>)
                        if (isPrimary) {
                            for ((key, grant) in container.persistedTreeStore.grants.value) {
                                val matchesPkgKey = key.startsWith(restrictedKind.pkgGrantPrefix)
                                val matchesDocId = grant.rootDocumentId.startsWith(
                                    "${restrictedKind.defaultSafDocId}/",
                                    ignoreCase = true,
                                )
                                if (!matchesPkgKey && !matchesDocId) continue
                                val pkgFolderName = if (matchesPkgKey) {
                                    key.removePrefix(restrictedKind.pkgGrantPrefix)
                                } else {
                                    grant.rootDocumentId.substringAfterLast('/')
                                }.trim()
                                if (pkgFolderName.isEmpty() || pkgFolderName in byName) continue
                                val grantPath = grant.toVfsPath()
                                val grantNode = runCatching { container.fileSystem.stat(grantPath) }.getOrNull()
                                    ?: VfsNode(
                                        path = grantPath,
                                        name = pkgFolderName,
                                        isDirectory = true,
                                        size = 0L,
                                        lastModified = 0L,
                                        canRead = true,
                                        canWrite = grant.isWritable,
                                    )
                                byName[pkgFolderName] = grantNode.copy(name = pkgFolderName)
                            }
                        }

                        // Synthesize virtual package folders via PackageManager (QUERY_ALL_PACKAGES)
                        if (isPrimary) {
                            val pm = container.appContext.packageManager
                            @Suppress("DEPRECATION")
                            val installedApps = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
                            val baseDirFile = File(dir.normalized)
                            for (appInfo in installedApps) {
                                val pkg = appInfo.packageName ?: continue
                                if (pkg in byName) continue
                                val isUpdatedSys = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                                val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && !isUpdatedSys
                                val existsLocally = runCatching { File(baseDirFile, pkg).exists() }.getOrDefault(false)
                                if (isSys && !includeSystemApps && !existsLocally) continue
                                val lastUpdate = runCatching {
                                    @Suppress("DEPRECATION")
                                    pm.getPackageInfo(pkg, 0).lastUpdateTime
                                }.getOrDefault(0L)
                                byName[pkg] = VfsNode(
                                    path = VfsPath.Local("${dir.normalized}/$pkg"),
                                    name = pkg,
                                    isDirectory = true,
                                    size = 0L,
                                    lastModified = lastUpdate,
                                    mimeType = "inode/directory",
                                    isHidden = false,
                                    canRead = true,
                                    canWrite = false,
                                    childCount = -1,
                                )
                            }
                        }

                        val rawDirStat = runCatching { container.fileSystem.stat(dir) }.getOrNull()
                        val effectiveDirStat = (
                            rawDirStat ?: VfsNode(
                                path = dir,
                                name = dir.displayName,
                                isDirectory = true,
                                size = 0L,
                                lastModified = 0L,
                                canRead = true,
                                canWrite = false,
                            )
                            ).copy(canWrite = false)
                        effectiveDirStat to byName.values.toList()
                    }

                    val validSelected = _browser.value.selectedPaths.intersect(mergedChildren.map { it.path }.toSet())
                    _browser.value = _browser.value.copy(
                        currentDirNode = restrictedDirNode,
                        rawEntries = mergedChildren,
                        isLoading = false,
                        errorMessage = null,
                        permissionRequired = false,
                        isRestrictedAndroidDataDir = true,
                        restrictedPackageTarget = null,
                        selectedPaths = validSelected,
                    )
                    recomputeVisibleEntries(settings.value)
                    return@launch
                }

                // Case 2: Browsing a specific app's /Android/(data|obb)/<pkg> on Android 11+
                val restrictedPkgTarget = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    classifyRestrictedPackageDir(dir)
                } else {
                    null
                }
                if (restrictedPkgTarget != null && dir is VfsPath.Local) {
                    val (pkgKind, pkgName) = restrictedPkgTarget
                    val isPrimary = isPrimaryStorageAndroidDir(dir)
                    val ownPkg = container.appContext.packageName

                    // Priority 1a: Own package or directly readable via File
                    if (pkgName == ownPkg) {
                        withContext(Dispatchers.IO) {
                            when (pkgKind) {
                                RestrictedAndroidDirKind.DATA -> runCatching { container.appContext.getExternalFilesDir(null) }
                                RestrictedAndroidDirKind.OBB -> runCatching { container.appContext.obbDir }
                            }
                        }
                    }
                    val directLocalResult: Pair<VfsNode?, List<VfsNode>>? = withContext(Dispatchers.IO) {
                        val localFile = File(dir.normalized)
                        if (pkgName == ownPkg && localFile.exists() && localFile.isDirectory && localFile.canRead()) {
                            val st = runCatching { container.fileSystem.stat(dir) }.getOrNull()
                            val kids = runCatching { container.fileSystem.list(dir, includeHidden = true) }.getOrNull()
                            if (kids != null) st to kids else null
                        } else if (localFile.exists() && localFile.isDirectory && localFile.canRead()) {
                            val rawFiles = localFile.listFiles()
                            if (rawFiles != null && rawFiles.isNotEmpty()) {
                                val st = runCatching { container.fileSystem.stat(dir) }.getOrNull()
                                val kids = runCatching { container.fileSystem.list(dir, includeHidden = true) }.getOrNull()
                                if (kids != null) st to kids else null
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    }
                    if (directLocalResult != null) {
                        val (dirStat, allChildren) = directLocalResult
                        val validSelected = _browser.value.selectedPaths.intersect(allChildren.map { it.path }.toSet())
                        _browser.value = _browser.value.copy(
                            currentDirNode = dirStat,
                            rawEntries = allChildren,
                            isLoading = false,
                            errorMessage = null,
                            permissionRequired = false,
                            isRestrictedAndroidDataDir = false,
                            restrictedPackageTarget = null,
                            selectedPaths = validSelected,
                        )
                        recomputeVisibleEntries(settings.value)
                        return@launch
                    }

                    // Priority 1b: Readable via PrivilegedShellBridge (Shizuku / Root)
                    val privilegedActive = withContext(Dispatchers.IO) {
                        PrivilegedShellBridge.hasPrivilegedAccess(container.appContext)
                    }
                    if (privilegedActive) {
                        val (privStat, privKids) = withContext(Dispatchers.IO) {
                            val st = PrivilegedShellBridge.stat(dir.normalized)
                                ?: VfsNode(
                                    path = dir,
                                    name = pkgName,
                                    isDirectory = true,
                                    size = 0L,
                                    lastModified = 0L,
                                    canRead = true,
                                    canWrite = true,
                                )
                            val kids = PrivilegedShellBridge.listDirectory(dir.normalized, includeHidden = true)
                                ?: emptyList()
                            st.copy(canWrite = true) to kids
                        }
                        val validSelected = _browser.value.selectedPaths.intersect(privKids.map { it.path }.toSet())
                        _browser.value = _browser.value.copy(
                            currentDirNode = privStat,
                            rawEntries = privKids,
                            isLoading = false,
                            errorMessage = null,
                            permissionRequired = false,
                            isRestrictedAndroidDataDir = false,
                            restrictedPackageTarget = null,
                            selectedPaths = validSelected,
                        )
                        recomputeVisibleEntries(settings.value)
                        return@launch
                    }

                    // Priority 2: Per-package SAF grant (`pkg_data:<pkg>` / `pkg_obb:<pkg>`) or whole-tree SAF grant
                    if (isPrimary) {
                        val expectedDocId = "${pkgKind.defaultSafDocId}/$pkgName"
                        val pkgGrant = container.persistedTreeStore.getGrant("${pkgKind.pkgGrantPrefix}$pkgName")
                            ?: container.persistedTreeStore.grants.value.values.firstOrNull {
                                it.rootDocumentId.equals(expectedDocId, ignoreCase = true)
                            }
                        val safTarget: Pair<VfsPath.Saf, String>? = if (pkgGrant != null) {
                            pkgGrant.toVfsPath() to pkgGrant.label
                        } else {
                            val parentTreeGrant = container.persistedTreeStore.getGrant(pkgKind.safKey)
                            if (parentTreeGrant != null) {
                                val candidateSaf = VfsPath.Saf(parentTreeGrant.treeUri, expectedDocId)
                                val existsInTree = withContext(Dispatchers.IO) {
                                    runCatching { container.fileSystem.stat(candidateSaf) != null }.getOrDefault(false)
                                }
                                if (existsInTree) candidateSaf to pkgName else null
                            } else {
                                null
                            }
                        }

                        if (safTarget != null) {
                            val (safDir, safLabel) = safTarget
                            savedStateHandle[STATE_DIR] = safDir.serialize()
                            savedStateHandle[STATE_ROOT_NAME] = safLabel
                            savedStateHandle[STATE_ROOT_BASE] = safDir.serialize()
                            container.settingsRepository.update { s ->
                                if (s.rememberLastFolder) s.copy(lastFolder = safDir.serialize()) else s
                            }
                            val (safStat, safKids) = withContext(Dispatchers.IO) {
                                val st = container.fileSystem.stat(safDir)
                                val list = container.fileSystem.list(safDir, includeHidden = true)
                                st to list
                            }
                            _browser.value = _browser.value.copy(
                                currentDir = safDir,
                                currentDirNode = safStat,
                                rootVolumeName = safLabel,
                                rootVolumeBase = safDir,
                                breadcrumbs = buildBreadcrumbs(safDir, safLabel, safDir),
                                rawEntries = safKids,
                                isLoading = false,
                                errorMessage = null,
                                permissionRequired = false,
                                isRestrictedAndroidDataDir = false,
                                restrictedPackageTarget = null,
                                selectedPaths = emptySet(),
                            )
                            recomputeVisibleEntries(settings.value)
                            return@launch
                        }
                    }

                    // Priority 3: Show App Data Access & Wireless Debugging Card inside /Android/(data|obb)/<pkg>
                    val appLabel = withContext(Dispatchers.IO) {
                        runCatching {
                            @Suppress("DEPRECATION")
                            val ai = container.appContext.packageManager.getApplicationInfo(pkgName, 0)
                            container.appContext.packageManager.getApplicationLabel(ai).toString()
                        }.getOrDefault(pkgName)
                    }
                    val placeholderDirNode = VfsNode(
                        path = dir,
                        name = pkgName,
                        isDirectory = true,
                        size = 0L,
                        lastModified = 0L,
                        canRead = false,
                        canWrite = false,
                    )
                    _browser.value = _browser.value.copy(
                        currentDirNode = placeholderDirNode,
                        rawEntries = emptyList(),
                        visibleEntries = emptyList(),
                        isLoading = false,
                        errorMessage = null,
                        permissionRequired = false,
                        isRestrictedAndroidDataDir = false,
                        restrictedPackageTarget = RestrictedPackageTarget(
                            kind = pkgKind,
                            packageName = pkgName,
                            appLabel = appLabel,
                        ),
                        selectedPaths = emptySet(),
                    )
                    return@launch
                }

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
                    isRestrictedAndroidDataDir = false,
                    restrictedPackageTarget = null,
                    selectedPaths = validSelected,
                )
                recomputeVisibleEntries(settings.value)
            } catch (e: Exception) {
                val msg = e.message ?: "Unable to read folder"
                val needsPerm = dir is VfsPath.Local &&
                    (msg.contains("Permission", ignoreCase = true) || msg.contains("Cannot list", ignoreCase = true))
                _browser.value = _browser.value.copy(
                    rawEntries = emptyList(),
                    visibleEntries = emptyList(),
                    isLoading = false,
                    errorMessage = msg,
                    permissionRequired = needsPerm,
                    isRestrictedAndroidDataDir = false,
                    restrictedPackageTarget = null,
                )
            }
        }
    }

    private suspend fun ensureOwnPackageEntry(
        byName: MutableMap<String, VfsNode>,
        dir: VfsPath.Local,
        restrictedKind: RestrictedAndroidDirKind,
        isPrimary: Boolean,
    ) {
        val ownPkgName = container.appContext.packageName
        if (ownPkgName in byName) return
        val candidateUnderDir = File(File(dir.normalized), ownPkgName)
        val fallbackOwnDir = if (isPrimary) {
            when (restrictedKind) {
                RestrictedAndroidDirKind.DATA ->
                    runCatching { container.appContext.getExternalFilesDir(null)?.parentFile }.getOrNull()
                RestrictedAndroidDirKind.OBB ->
                    runCatching { container.appContext.obbDir }.getOrNull()
            }
        } else {
            null
        }
        val resolvedOwnDir = when {
            candidateUnderDir.exists() && candidateUnderDir.canRead() -> candidateUnderDir
            fallbackOwnDir != null && fallbackOwnDir.exists() && fallbackOwnDir.canRead() -> fallbackOwnDir
            else -> null
        }
        if (resolvedOwnDir != null) {
            val ownPath = VfsPath.Local(File(File(dir.normalized), ownPkgName).path)
                .takeIf { File(it.normalized).exists() }
                ?: VfsPath.Local(resolvedOwnDir.path)
            val st = runCatching { container.fileSystem.stat(ownPath) }.getOrNull()
            if (st != null) {
                val count = resolvedOwnDir.list()?.size ?: -1
                byName[ownPkgName] = st.copy(name = ownPkgName, childCount = count)
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
        }
        return crumbs
    }

    private fun inferRootName(path: VfsPath?): String {
        if (path == null) return "Internal"
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
            val summary = buildString {
                append("$verbPast ${res.succeeded} item${if (res.succeeded == 1) "" else "s"} to $destinationLabel")
                if (res.skipped > 0) append(" (${res.skipped} skipped)")
                if (res.failed.isNotEmpty()) append(" · ${res.failed.size} failed: ${res.failed.first().second}")
            }
            OperationRunOutcome(
                message = summary,
                destinationToOpen = if (destinationDir != _browser.value.currentDir) destinationDir else null,
                openLabel = "Open",
                errorSummary = res.failed.firstOrNull()?.second,
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
        val label = if (items.size == 1) items.first().displayName else "${items.size} items"
        container.operationRegistry.enqueue(
            context = context,
            kind = OperationKind.DELETE,
            title = "Deleting $label",
        ) { _, sink ->
            val res = container.fileOperations.delete(items, sink)
            val msg = buildString {
                append("Deleted ${res.succeeded} item${if (res.succeeded == 1) "" else "s"}")
                if (res.failed.isNotEmpty()) append(" (${res.failed.size} failed)")
            }
            OperationRunOutcome(
                message = msg,
                errorSummary = res.failed.firstOrNull()?.second,
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

    fun addOrUpdateFavorite(name: String, path: VfsPath, color: Int? = null) {
        viewModelScope.launch {
            container.favoritesRepository.add(name, path, color)
            emitSimpleMessage("Added '${name.ifBlank { path.displayName }}' to Favorites")
        }
    }

    fun removeFavoriteByPath(path: VfsPath) {
        val existing = container.favoritesRepository.findByPath(path) ?: return
        viewModelScope.launch {
            container.favoritesRepository.remove(existing.id)
            emitSimpleMessage("Removed '${existing.name}' from Favorites")
        }
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
