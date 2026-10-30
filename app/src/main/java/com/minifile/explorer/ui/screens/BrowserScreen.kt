package com.minifile.explorer.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minifile.explorer.core.model.TransferMode
import com.minifile.explorer.core.model.ViewMode
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.core.vfs.Volume
import com.minifile.explorer.data.AndroidVolumeProvider
import com.minifile.explorer.ui.ClipboardHolder
import com.minifile.explorer.ui.MainTab
import com.minifile.explorer.ui.MainViewModel
import com.minifile.explorer.ui.OverlayScreen
import com.minifile.explorer.ui.components.AddFavoriteDialog
import com.minifile.explorer.ui.components.BreadcrumbBar
import com.minifile.explorer.ui.components.ClipboardBar
import com.minifile.explorer.ui.components.CompressZipDialog
import com.minifile.explorer.ui.components.CreateFileDialog
import com.minifile.explorer.ui.components.CreateFolderDialog
import com.minifile.explorer.ui.components.DeleteConfirmDialog
import com.minifile.explorer.ui.components.ExtractZipDialog
import com.minifile.explorer.ui.components.FavoritePickerSheet
import com.minifile.explorer.ui.components.FileGridCell
import com.minifile.explorer.ui.components.FileListRow
import com.minifile.explorer.ui.components.PropertiesSheet
import com.minifile.explorer.ui.components.RenameDialog
import com.minifile.explorer.ui.components.RowAction
import com.minifile.explorer.ui.components.SelectionBottomBar
import com.minifile.explorer.ui.components.SelectionMoreAction
import com.minifile.explorer.ui.components.SelectionTopBar
import com.minifile.explorer.ui.components.SortBottomSheet
import com.minifile.explorer.ui.util.UiFormatters
import kotlinx.coroutines.launch

private data class FavoriteTransferRequest(
    val items: List<VfsPath>,
    val initialMode: TransferMode,
    val fromClipboard: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    viewModel: MainViewModel,
    onGrantAllFilesAccess: () -> Unit,
    onMountGenericFolder: () -> Unit,
    onMountVolumeSaf: (Volume) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val browser by viewModel.browser.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val volumes by viewModel.volumes.collectAsState()
    val clipboard by viewModel.clipboard.collectAsState()

    var showSortSheet by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<VfsNode?>(null) }
    var deleteTargets by remember { mutableStateOf<List<VfsNode>?>(null) }
    var propertiesTarget by remember { mutableStateOf<VfsNode?>(null) }
    var addFavoriteTarget by remember { mutableStateOf<Pair<String, VfsPath>?>(null) }
    var compressTargets by remember { mutableStateOf<List<VfsNode>?>(null) }
    var extractTarget by remember { mutableStateOf<VfsPath?>(null) }
    var favoritePickerRequest by remember { mutableStateOf<FavoriteTransferRequest?>(null) }

    // Storage Home mode when currentDir == null
    val curDir = browser.currentDir
    if (curDir == null) {
        StorageHomeScreen(
            volumes = volumes,
            favorites = favorites,
            hasAllFilesAccess = AndroidVolumeProvider.hasAllFilesAccess(context),
            onboardingDismissed = settings.onboardingDone,
            clipboard = clipboard,
            onGrantAllFilesAccess = onGrantAllFilesAccess,
            onDismissOnboarding = {
                viewModel.container.settingsRepository.update { it.copy(onboardingDone = true) }
            },
            onMountGenericFolder = onMountGenericFolder,
            onMountVolumeSaf = onMountVolumeSaf,
            onUnmountSafKey = { key ->
                viewModel.container.persistedTreeStore.revoke(key)
                viewModel.refreshVolumes()
            },
            onOpenVolume = { viewModel.openVolume(it) },
            onOpenQuickPath = { path, rootLabel ->
                viewModel.navigateToDirectory(path, rootVolumeName = rootLabel)
            },
            onOpenFavorite = { viewModel.openFavoriteInBrowse(it) },
            onSeeAllFavorites = { viewModel.selectTab(MainTab.FAVORITES) },
            onOpenInstalledApps = { viewModel.openOverlay(OverlayScreen.InstalledApps) },
            onClipboardToFavorite = {
                val clip = clipboard ?: return@StorageHomeScreen
                favoritePickerRequest = FavoriteTransferRequest(
                    items = clip.items,
                    initialMode = clip.mode,
                    fromClipboard = true,
                )
            },
            onClearClipboard = { ClipboardHolder.clear() },
        )

        favoritePickerRequest?.let { req ->
            FavoritePickerSheet(
                items = req.items,
                initialMode = req.initialMode,
                favorites = favorites,
                sortByRecency = settings.favoritePickerSortByRecency,
                volumes = volumes,
                currentDir = null,
                fs = viewModel.container.fileSystem,
                onSelectFavorite = { fav, chosenMode ->
                    favoritePickerRequest = null
                    viewModel.executeTransferToFavorite(
                        context = context,
                        items = req.items,
                        mode = chosenMode,
                        favorite = fav,
                        clearClipboardOnComplete = req.fromClipboard,
                    )
                },
                onSelectCustomFolder = { dir, label, chosenMode ->
                    favoritePickerRequest = null
                    viewModel.executeTransfer(
                        context = context,
                        items = req.items,
                        mode = chosenMode,
                        destinationDir = dir,
                        destinationLabel = label,
                        clearClipboardOnComplete = req.fromClipboard,
                    )
                },
                onAddCurrentFolderToFavorites = {},
                onDismiss = { favoritePickerRequest = null },
            )
        }
        return
    }

    // Directory Browser mode
    var overflowExpanded by remember { mutableStateOf(false) }
    var fabMenuExpanded by remember { mutableStateOf(false) }

    val favoritePaths = remember(favorites) { favorites.map { it.path }.toSet() }
    val isCurrentDirFavorite = curDir in favoritePaths
    val cutSet = remember(clipboard) {
        if (clipboard?.mode == TransferMode.MOVE) clipboard!!.items.toSet() else emptySet()
    }
    val existingNames = remember(browser.rawEntries) { browser.rawEntries.map { it.name }.toSet() }
    val canWriteHere = browser.currentDirNode?.canWrite != false

    fun handleNodeTap(node: VfsNode) {
        if (browser.isSelectionMode) {
            viewModel.toggleSelection(node.path)
            return
        }
        if (node.isDirectory) {
            viewModel.navigateToDirectory(node.path)
            return
        }
        val ext = PathUtils.extensionOf(node.name)
        when {
            ext == "zip" || ext == "jar" -> {
                viewModel.openOverlay(OverlayScreen.ArchivePreview(node.path))
            }

            ext == "apk" -> {
                propertiesTarget = node
            }

            else -> {
                scope.launch {
                    val isText = settings.openTextInBuiltInEditor &&
                        UiFormatters.shouldOpenAsText(node, viewModel.container.fileSystem)
                    if (isText) {
                        viewModel.openOverlay(OverlayScreen.TextEditor(node.path))
                    } else {
                        UiFormatters.openExternally(context, viewModel.container.safFileSystem, node)
                            .onFailure { err ->
                                viewModel.emitSimpleMessage("Cannot open ${node.name}: ${err.message}")
                            }
                    }
                }
            }
        }
    }

    fun handleRowAction(node: VfsNode, action: RowAction) {
        when (action) {
            RowAction.OPEN_WITH -> {
                UiFormatters.openExternally(context, viewModel.container.safFileSystem, node, forceChooser = true)
                    .onFailure { viewModel.emitSimpleMessage("No app found: ${it.message}") }
            }

            RowAction.OPEN_AS_TEXT -> {
                viewModel.openOverlay(OverlayScreen.TextEditor(node.path, forceEdit = false))
            }

            RowAction.RENAME -> renameTarget = node
            RowAction.COPY -> viewModel.copyPathsToClipboard(listOf(node.path), TransferMode.COPY)
            RowAction.CUT -> viewModel.copyPathsToClipboard(listOf(node.path), TransferMode.MOVE)
            RowAction.COPY_TO_FAVORITE -> {
                favoritePickerRequest = FavoriteTransferRequest(
                    items = listOf(node.path),
                    initialMode = TransferMode.COPY,
                    fromClipboard = false,
                )
            }

            RowAction.MOVE_TO_FAVORITE -> {
                favoritePickerRequest = FavoriteTransferRequest(
                    items = listOf(node.path),
                    initialMode = TransferMode.MOVE,
                    fromClipboard = false,
                )
            }

            RowAction.COMPRESS_ZIP -> compressTargets = listOf(node)
            RowAction.TOGGLE_FAVORITE -> {
                if (node.path in favoritePaths) {
                    viewModel.removeFavoriteByPath(node.path)
                } else {
                    addFavoriteTarget = node.name to node.path
                }
            }

            RowAction.TOGGLE_NOMEDIA -> viewModel.toggleNomedia(node.path)
            RowAction.PROPERTIES -> propertiesTarget = node
            RowAction.DELETE -> deleteTargets = listOf(node)
        }
    }

    Scaffold(
        topBar = {
            when {
                browser.isSelectionMode -> {
                    SelectionTopBar(
                        selectedCount = browser.selectedPaths.size,
                        onExitSelection = { viewModel.clearSelection() },
                        onSelectAll = { viewModel.selectAll() },
                        onInvertSelection = { viewModel.invertSelection() },
                    )
                }

                browser.searchActive -> {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { viewModel.closeSearch() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                            }
                        },
                        title = {
                            OutlinedTextField(
                                value = browser.searchQuery,
                                onValueChange = { viewModel.updateSearchQuery(it) },
                                placeholder = { Text("Filter files…") },
                                singleLine = true,
                                trailingIcon = {
                                    if (browser.searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                    )
                }

                else -> {
                    val titleLabel = browser.breadcrumbs.lastOrNull()?.label ?: curDir.displayName
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { viewModel.navigateUpOrHome() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate up")
                            }
                        },
                        title = {
                            Text(
                                text = titleLabel,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        actions = {
                            IconButton(onClick = { viewModel.openSearch() }) {
                                Icon(Icons.Filled.Search, contentDescription = "Search")
                            }
                            IconButton(onClick = { viewModel.toggleViewMode() }) {
                                Icon(
                                    imageVector = if (settings.viewMode == ViewMode.LIST) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList,
                                    contentDescription = "Toggle view mode",
                                )
                            }
                            IconButton(onClick = { showSortSheet = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                            }
                            IconButton(
                                onClick = {
                                    if (isCurrentDirFavorite) {
                                        viewModel.removeFavoriteByPath(curDir)
                                    } else {
                                        addFavoriteTarget = titleLabel to curDir
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = if (isCurrentDirFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                    contentDescription = if (isCurrentDirFavorite) "Remove from Favorites" else "Add to Favorites",
                                    tint = if (isCurrentDirFavorite) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            Box {
                                IconButton(onClick = { overflowExpanded = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                                }
                                DropdownMenu(
                                    expanded = overflowExpanded,
                                    onDismissRequest = { overflowExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Sort…") },
                                        onClick = {
                                            overflowExpanded = false
                                            showSortSheet = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (settings.viewMode == ViewMode.LIST) "View: Grid" else "View: List")
                                        },
                                        onClick = {
                                            overflowExpanded = false
                                            viewModel.toggleViewMode()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Checkbox(checked = settings.showHidden, onCheckedChange = null)
                                                Spacer(Modifier.width(8.dp))
                                                Text("Show hidden files")
                                            }
                                        },
                                        onClick = {
                                            overflowExpanded = false
                                            viewModel.toggleShowHidden()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Select all") },
                                        onClick = {
                                            overflowExpanded = false
                                            viewModel.selectAll()
                                        },
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("New folder") },
                                        onClick = {
                                            overflowExpanded = false
                                            showCreateFolderDialog = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("New file") },
                                        onClick = {
                                            overflowExpanded = false
                                            showCreateFileDialog = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (isCurrentDirFavorite) "Remove from Favorites" else "Add to Favorites")
                                        },
                                        onClick = {
                                            overflowExpanded = false
                                            if (isCurrentDirFavorite) {
                                                viewModel.removeFavoriteByPath(curDir)
                                            } else {
                                                addFavoriteTarget = titleLabel to curDir
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Hide from gallery (.nomedia)") },
                                        onClick = {
                                            overflowExpanded = false
                                            viewModel.toggleNomedia(curDir)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Properties") },
                                        onClick = {
                                            overflowExpanded = false
                                            browser.currentDirNode?.let { propertiesTarget = it }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Refresh") },
                                        onClick = {
                                            overflowExpanded = false
                                            viewModel.reloadCurrentDirectory(silent = false)
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        },
        floatingActionButton = {
            if (!browser.isSelectionMode && canWriteHere && !browser.permissionRequired) {
                Box {
                    FloatingActionButton(
                        onClick = { fabMenuExpanded = true },
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Create")
                    }
                    DropdownMenu(
                        expanded = fabMenuExpanded,
                        onDismissRequest = { fabMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                            text = { Text("New folder") },
                            onClick = {
                                fabMenuExpanded = false
                                showCreateFolderDialog = true
                            },
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null) },
                            text = { Text("New file") },
                            onClick = {
                                fabMenuExpanded = false
                                showCreateFileDialog = true
                            },
                        )
                        if (clipboard != null) {
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Paste here") },
                                onClick = {
                                    fabMenuExpanded = false
                                    viewModel.pasteClipboardInto(context, curDir)
                                },
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            when {
                browser.isSelectionMode -> {
                    val selectedNodes = viewModel.selectedNodes()
                    SelectionBottomBar(
                        selectedNodes = selectedNodes,
                        onCopy = { viewModel.copyPathsToClipboard(selectedNodes.map { it.path }, TransferMode.COPY) },
                        onCut = { viewModel.copyPathsToClipboard(selectedNodes.map { it.path }, TransferMode.MOVE) },
                        onDelete = { deleteTargets = selectedNodes },
                        onShare = {
                            UiFormatters.shareItems(context, viewModel.container.safFileSystem, selectedNodes)
                                .onFailure { viewModel.emitSimpleMessage(it.message ?: "Cannot share selection") }
                        },
                        onMoreAction = { action ->
                            when (action) {
                                SelectionMoreAction.RENAME -> selectedNodes.singleOrNull()?.let { renameTarget = it }
                                SelectionMoreAction.COPY_TO_FAVORITE -> {
                                    favoritePickerRequest = FavoriteTransferRequest(
                                        items = selectedNodes.map { it.path },
                                        initialMode = TransferMode.COPY,
                                        fromClipboard = false,
                                    )
                                }

                                SelectionMoreAction.MOVE_TO_FAVORITE -> {
                                    favoritePickerRequest = FavoriteTransferRequest(
                                        items = selectedNodes.map { it.path },
                                        initialMode = TransferMode.MOVE,
                                        fromClipboard = false,
                                    )
                                }

                                SelectionMoreAction.COMPRESS_ZIP -> compressTargets = selectedNodes
                                SelectionMoreAction.EXTRACT_ZIP -> selectedNodes.singleOrNull()?.let { extractTarget = it.path }
                                SelectionMoreAction.ADD_TO_FAVORITES -> selectedNodes.singleOrNull()?.let {
                                    addFavoriteTarget = it.name to it.path
                                }

                                SelectionMoreAction.TOGGLE_NOMEDIA -> {
                                    selectedNodes.filter { it.isDirectory }.forEach { viewModel.toggleNomedia(it.path) }
                                    viewModel.clearSelection()
                                }

                                SelectionMoreAction.PROPERTIES -> selectedNodes.singleOrNull()?.let { propertiesTarget = it }
                            }
                        },
                    )
                }

                clipboard != null -> {
                    ClipboardBar(
                        clipboard = clipboard!!,
                        canPasteHere = canWriteHere,
                        disabledPasteReason = if (!canWriteHere) "Read-only folder" else null,
                        onPasteHere = { viewModel.pasteClipboardInto(context, curDir) },
                        onPickFavorite = {
                            favoritePickerRequest = FavoriteTransferRequest(
                                items = clipboard!!.items,
                                initialMode = clipboard!!.mode,
                                fromClipboard = true,
                            )
                        },
                        onClear = { ClipboardHolder.clear() },
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            BreadcrumbBar(
                crumbs = browser.breadcrumbs,
                onHomeClick = { viewModel.navigateToStorageHome() },
                onCrumbClick = { target ->
                    if (target != curDir) viewModel.navigateToDirectory(target)
                },
                onCrumbLongPress = { target ->
                    val raw = when (target) {
                        is VfsPath.Local -> target.normalized
                        is VfsPath.Saf -> target.documentId
                    }
                    UiFormatters.copyToSystemClipboard(context, "Path", raw)
                    viewModel.emitSimpleMessage("Copied path: $raw")
                },
            )

            if (browser.searchActive) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    FilterChip(
                        selected = browser.recursiveSearch,
                        onClick = { viewModel.toggleRecursiveSearch(!browser.recursiveSearch) },
                        label = { Text("Search subfolders") },
                    )
                    if (browser.recursiveSearch) {
                        Text(
                            text = "${browser.recursiveHits.size} matches",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (browser.isRecursiveSearching) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            when {
                browser.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                browser.permissionRequired -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Allow MiniFile to manage storage", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Grant All-files access to read this directory, or mount a folder with Android's document picker.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = onGrantAllFilesAccess) {
                                Text("Grant access")
                            }
                            OutlinedButton(onClick = onMountGenericFolder) {
                                Text("Mount a folder…")
                            }
                        }
                    }
                }

                browser.errorMessage != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                    ) {
                        Text(browser.errorMessage!!, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { viewModel.reloadCurrentDirectory(silent = false) }) {
                            Text("Retry")
                        }
                    }
                }

                browser.searchActive && browser.recursiveSearch -> {
                    if (browser.recursiveHits.isEmpty() && !browser.isRecursiveSearching) {
                        EmptyFolderView(
                            title = "No matching files",
                            subtitle = "Try another search query",
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 88.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(browser.recursiveHits, key = { it.node.path.serialize() }) { hit ->
                                FileListRow(
                                    node = hit.node,
                                    selected = hit.node.path in browser.selectedPaths,
                                    selectionMode = browser.isSelectionMode,
                                    isFavorite = hit.node.path in favoritePaths,
                                    isCutPending = hit.node.path in cutSet,
                                    showThumbnails = settings.showThumbnails,
                                    safFs = viewModel.container.safFileSystem,
                                    subtitleOverride = "${hit.relativePath} · ${UiFormatters.formatSize(context, hit.node.size)}",
                                    onClick = { handleNodeTap(hit.node) },
                                    onLongClick = { viewModel.toggleSelection(hit.node.path, rangeSelect = true) },
                                    onToggleSelect = { viewModel.toggleSelection(hit.node.path) },
                                    onAction = { handleRowAction(hit.node, it) },
                                )
                            }
                        }
                    }
                }

                browser.visibleEntries.isEmpty() -> {
                    EmptyFolderView(
                        title = if (browser.searchQuery.isNotEmpty()) "No matching items" else "Nothing here yet",
                        subtitle = if (browser.searchQuery.isNotEmpty()) {
                            "Clear filter or search subfolders"
                        } else {
                            "Tap + to create a folder or file"
                        },
                    )
                }

                settings.viewMode == ViewMode.GRID -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 108.dp),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 92.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(browser.visibleEntries, key = { it.path.serialize() }) { node ->
                            FileGridCell(
                                node = node,
                                selected = node.path in browser.selectedPaths,
                                isFavorite = node.path in favoritePaths,
                                isCutPending = node.path in cutSet,
                                showThumbnails = settings.showThumbnails,
                                safFs = viewModel.container.safFileSystem,
                                onClick = { handleNodeTap(node) },
                                onLongClick = { viewModel.toggleSelection(node.path, rangeSelect = browser.isSelectionMode) },
                                onAction = { handleRowAction(node, it) },
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 92.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(browser.visibleEntries, key = { it.path.serialize() }) { node ->
                            FileListRow(
                                node = node,
                                selected = node.path in browser.selectedPaths,
                                selectionMode = browser.isSelectionMode,
                                isFavorite = node.path in favoritePaths,
                                isCutPending = node.path in cutSet,
                                showThumbnails = settings.showThumbnails,
                                safFs = viewModel.container.safFileSystem,
                                onClick = { handleNodeTap(node) },
                                onLongClick = { viewModel.toggleSelection(node.path, rangeSelect = browser.isSelectionMode) },
                                onToggleSelect = { viewModel.toggleSelection(node.path) },
                                onAction = { handleRowAction(node, it) },
                            )
                        }
                    }
                }
            }
        }
    }

    // Sheets & Dialogs
    if (showSortSheet) {
        SortBottomSheet(
            currentSort = settings.sort,
            viewMode = settings.viewMode,
            showHidden = settings.showHidden,
            onUpdateSort = { viewModel.updateSort(it) },
            onToggleViewMode = { viewModel.toggleViewMode() },
            onToggleShowHidden = { viewModel.toggleShowHidden() },
            onDismiss = { showSortSheet = false },
        )
    }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            existingNames = existingNames,
            onConfirm = { name, onErr -> viewModel.createNewFolder(name, onErr) },
            onDismiss = { showCreateFolderDialog = false },
        )
    }

    if (showCreateFileDialog) {
        CreateFileDialog(
            existingNames = existingNames,
            onConfirm = { name, openEd, onErr -> viewModel.createNewFile(name, openEd, onErr) },
            onDismiss = { showCreateFileDialog = false },
        )
    }

    renameTarget?.let { target ->
        RenameDialog(
            node = target,
            onConfirm = { newName, onErr, onSuccess ->
                viewModel.renameItem(target.path, newName, onErr, onSuccess)
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTargets?.let { list ->
        DeleteConfirmDialog(
            nodes = list,
            onConfirm = { viewModel.deleteItems(context, list.map { it.path }) },
            onDismiss = { deleteTargets = null },
        )
    }

    propertiesTarget?.let { node ->
        PropertiesSheet(
            node = node,
            isFavorite = node.path in favoritePaths,
            fileOps = viewModel.container.fileOperations,
            checksumCalculator = viewModel.container.checksumCalculator,
            safFs = viewModel.container.safFileSystem,
            onRequestRename = { renameTarget = node },
            onToggleFavorite = {
                if (node.path in favoritePaths) {
                    viewModel.removeFavoriteByPath(node.path)
                } else {
                    addFavoriteTarget = node.name to node.path
                }
            },
            onToggleNomedia = { viewModel.toggleNomedia(node.path) },
            onCopiedMessage = { viewModel.emitSimpleMessage(it) },
            onDismiss = { propertiesTarget = null },
        )
    }

    addFavoriteTarget?.let { (defName, path) ->
        AddFavoriteDialog(
            defaultName = defName,
            onConfirm = { chosenName -> viewModel.addOrUpdateFavorite(chosenName, path) },
            onDismiss = { addFavoriteTarget = null },
        )
    }

    compressTargets?.let { nodes ->
        CompressZipDialog(
            items = nodes,
            currentDir = curDir,
            existingNames = existingNames,
            favorites = favorites,
            onConfirm = { name, dst, lvl ->
                viewModel.compressItemsToZip(
                    context = context,
                    items = nodes.map { it.path },
                    destinationDir = dst,
                    archiveName = name,
                    compressionLevel = lvl,
                )
            },
            onDismiss = { compressTargets = null },
        )
    }

    extractTarget?.let { zipPath ->
        ExtractZipDialog(
            zipPath = zipPath,
            defaultParentDir = curDir,
            favorites = favorites,
            onConfirm = { dst, sub, overwrite ->
                viewModel.extractArchive(
                    context = context,
                    zipPath = zipPath,
                    destinationDir = dst,
                    createSubfolderName = sub,
                    overwrite = overwrite,
                )
            },
            onDismiss = { extractTarget = null },
        )
    }

    favoritePickerRequest?.let { req ->
        FavoritePickerSheet(
            items = req.items,
            initialMode = req.initialMode,
            favorites = favorites,
            sortByRecency = settings.favoritePickerSortByRecency,
            volumes = volumes,
            currentDir = curDir,
            fs = viewModel.container.fileSystem,
            onSelectFavorite = { fav, chosenMode ->
                favoritePickerRequest = null
                viewModel.executeTransferToFavorite(
                    context = context,
                    items = req.items,
                    mode = chosenMode,
                    favorite = fav,
                    clearClipboardOnComplete = req.fromClipboard,
                )
            },
            onSelectCustomFolder = { dir, label, chosenMode ->
                favoritePickerRequest = null
                viewModel.executeTransfer(
                    context = context,
                    items = req.items,
                    mode = chosenMode,
                    destinationDir = dir,
                    destinationLabel = label,
                    clearClipboardOnComplete = req.fromClipboard,
                )
            },
            onAddCurrentFolderToFavorites = {
                addFavoriteTarget = (browser.breadcrumbs.lastOrNull()?.label ?: curDir.displayName) to curDir
            },
            onDismiss = { favoritePickerRequest = null },
        )
    }
}

@Composable
private fun EmptyFolderView(title: String, subtitle: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(52.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
