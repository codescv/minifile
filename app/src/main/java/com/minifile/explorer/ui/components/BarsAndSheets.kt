package com.minifile.explorer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddToDrive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minifile.explorer.core.i18n.Str
import com.minifile.explorer.core.model.ClipboardState
import com.minifile.explorer.core.model.Favorite
import com.minifile.explorer.core.model.SortField
import com.minifile.explorer.core.model.SortSpec
import com.minifile.explorer.core.model.TransferMode
import com.minifile.explorer.core.model.ViewMode
import com.minifile.explorer.core.ops.TargetValidation
import com.minifile.explorer.core.ops.TransferTargetValidator
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.core.vfs.Volume
import com.minifile.explorer.core.vfs.VolumeKind
import com.minifile.explorer.ui.i18n.LocalAppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    selectedCount: Int,
    onExitSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
) {
    val s = LocalAppStrings.current
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        navigationIcon = {
            IconButton(onClick = onExitSelection) {
                Icon(Icons.Filled.Close, contentDescription = s[Str.EXIT_SELECTION])
            }
        },
        title = {
            Text(s.format(Str.COUNT_SELECTED, selectedCount), fontWeight = FontWeight.SemiBold)
        },
        actions = {
            TextButton(onClick = onSelectAll) {
                Text(s[Str.SELECT_ALL])
            }
            TextButton(onClick = onInvertSelection) {
                Text(s[Str.INVERT])
            }
        },
    )
}

enum class SelectionMoreAction {
    RENAME,
    COPY_TO_FAVORITE,
    MOVE_TO_FAVORITE,
    COMPRESS_ZIP,
    SEND_TO_DEVICE,
    EXTRACT_ZIP,
    ADD_TO_FAVORITES,
    TOGGLE_NOMEDIA,
    PROPERTIES,
}

@Composable
fun SelectionBottomBar(
    selectedNodes: List<VfsNode>,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onMoreAction: (SelectionMoreAction) -> Unit,
) {
    val s = LocalAppStrings.current
    var moreExpanded by remember { mutableStateOf(false) }
    val single = selectedNodes.singleOrNull()
    val singleIsZip = single != null && !single.isDirectory &&
        PathUtils.extensionOf(single.name) == "zip"
    val allFolders = selectedNodes.isNotEmpty() && selectedNodes.all { it.isDirectory }

    BottomAppBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(s[Str.COPY])
            }
            TextButton(onClick = onCut) {
                Icon(Icons.Filled.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(s[Str.CUT])
            }
            TextButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(s[Str.DELETE], color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(s[Str.SHARE])
            }
            Box {
                IconButton(onClick = { moreExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = s[Str.MORE])
                }
                DropdownMenu(
                    expanded = moreExpanded,
                    onDismissRequest = { moreExpanded = false },
                ) {
                    if (single != null) {
                        DropdownMenuItem(
                            text = { Text(s[Str.RENAME]) },
                            onClick = {
                                moreExpanded = false
                                onMoreAction(SelectionMoreAction.RENAME)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(s[Str.COPY_TO_FAVORITE]) },
                        onClick = {
                            moreExpanded = false
                            onMoreAction(SelectionMoreAction.COPY_TO_FAVORITE)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(s[Str.MOVE_TO_FAVORITE]) },
                        onClick = {
                            moreExpanded = false
                            onMoreAction(SelectionMoreAction.MOVE_TO_FAVORITE)
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(s[Str.COMPRESS_TO_ZIP]) },
                        onClick = {
                            moreExpanded = false
                            onMoreAction(SelectionMoreAction.COMPRESS_ZIP)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(s[Str.SEND_TO_DEVICE]) },
                        onClick = {
                            moreExpanded = false
                            onMoreAction(SelectionMoreAction.SEND_TO_DEVICE)
                        },
                    )
                    if (singleIsZip) {
                        DropdownMenuItem(
                            text = { Text(s[Str.EXTRACT_ELLIPSIS]) },
                            onClick = {
                                moreExpanded = false
                                onMoreAction(SelectionMoreAction.EXTRACT_ZIP)
                            },
                        )
                    }
                    if (single != null) {
                        DropdownMenuItem(
                            text = { Text(s[Str.ADD_TO_FAVORITES]) },
                            onClick = {
                                moreExpanded = false
                                onMoreAction(SelectionMoreAction.ADD_TO_FAVORITES)
                            },
                        )
                    }
                    if (allFolders) {
                        DropdownMenuItem(
                            text = { Text(s[Str.HIDE_FROM_GALLERY_NOMEDIA]) },
                            onClick = {
                                moreExpanded = false
                                onMoreAction(SelectionMoreAction.TOGGLE_NOMEDIA)
                            },
                        )
                    }
                    if (single != null) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(s[Str.PROPERTIES]) },
                            onClick = {
                                moreExpanded = false
                                onMoreAction(SelectionMoreAction.PROPERTIES)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ClipboardBar(
    clipboard: ClipboardState,
    canPasteHere: Boolean,
    disabledPasteReason: String?,
    onPasteHere: () -> Unit,
    onPickFavorite: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalAppStrings.current
    var showItemsDialog by remember { mutableStateOf(false) }
    val label = if (clipboard.mode == TransferMode.COPY) {
        if (clipboard.items.size == 1) s[Str.CLIPBOARD_COPY_SINGLE] else s.format(Str.CLIPBOARD_COPY_PLURAL, clipboard.items.size)
    } else {
        if (clipboard.items.size == 1) s[Str.CLIPBOARD_MOVE_SINGLE] else s.format(Str.CLIPBOARD_MOVE_PLURAL, clipboard.items.size)
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { showItemsDialog = true },
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!canPasteHere && !disabledPasteReason.isNullOrBlank()) {
                    Text(
                        text = disabledPasteReason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            OutlinedButton(
                onClick = onPickFavorite,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp),
            ) {
                Text(s[Str.TO_FAVORITE_BTN], style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = onPasteHere,
                enabled = canPasteHere,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp),
            ) {
                Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(s[Str.PASTE_HERE], style = MaterialTheme.typography.labelMedium)
            }
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = s[Str.CLEAR_CLIPBOARD], modifier = Modifier.size(18.dp))
            }
        }
    }

    if (showItemsDialog) {
        AlertDialog(
            onDismissRequest = { showItemsDialog = false },
            title = { Text(label) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                    items(clipboard.items) { p ->
                        Text(
                            text = "• ${p.displayName}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showItemsDialog = false }) {
                    Text(s[Str.CLOSE])
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortBottomSheet(
    currentSort: SortSpec,
    viewMode: ViewMode,
    showHidden: Boolean,
    onUpdateSort: (SortSpec) -> Unit,
    onToggleViewMode: () -> Unit,
    onToggleShowHidden: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(s[Str.SORT_AND_VIEW], style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            val sortOptions = listOf(
                SortField.NAME to s[Str.SORT_NAME],
                SortField.SIZE to s[Str.SORT_SIZE],
                SortField.DATE to s[Str.SORT_DATE],
                SortField.TYPE to s[Str.SORT_TYPE],
            )
            for ((field, label) in sortOptions) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onUpdateSort(currentSort.copy(by = field)) }
                        .padding(vertical = 6.dp),
                ) {
                    RadioButton(
                        selected = currentSort.by == field,
                        onClick = { onUpdateSort(currentSort.copy(by = field)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = currentSort.ascending,
                    onClick = { onUpdateSort(currentSort.copy(ascending = true)) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) {
                    Text(s[Str.ASCENDING])
                }
                SegmentedButton(
                    selected = !currentSort.ascending,
                    onClick = { onUpdateSort(currentSort.copy(ascending = false)) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) {
                    Text(s[Str.DESCENDING])
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(s[Str.FOLDERS_FIRST], style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = currentSort.foldersFirst,
                    onCheckedChange = { onUpdateSort(currentSort.copy(foldersFirst = it)) },
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilterChip(
                    selected = showHidden,
                    onClick = onToggleShowHidden,
                    label = { Text(s[Str.SHOW_HIDDEN_FILES]) },
                )
                FilterChip(
                    selected = viewMode == ViewMode.GRID,
                    onClick = onToggleViewMode,
                    leadingIcon = {
                        Icon(
                            if (viewMode == ViewMode.GRID) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    label = { Text(if (viewMode == ViewMode.GRID) s[Str.GRID_VIEW] else s[Str.LIST_VIEW]) },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritePickerSheet(
    items: List<VfsPath>,
    initialMode: TransferMode,
    favorites: List<Favorite>,
    sortByRecency: Boolean,
    volumes: List<Volume>,
    currentDir: VfsPath?,
    fs: FileSystem,
    onSelectFavorite: (Favorite, TransferMode) -> Unit,
    onSelectCustomFolder: (VfsPath, String, TransferMode) -> Unit,
    onAddCurrentFolderToFavorites: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    var mode by remember { mutableStateOf(initialMode) }
    var miniBrowserDir by remember { mutableStateOf<VfsPath?>(null) }
    var miniBrowserSubfolders by remember { mutableStateOf<List<VfsNode>>(emptyList()) }
    var browsingOtherFolder by remember { mutableStateOf(false) }
    val validations = remember { mutableStateMapOf<String, TargetValidation>() }
    val scope = rememberCoroutineScope()

    val orderedFavorites = remember(favorites, sortByRecency) {
        val folderFavorites = favorites.filter { it.isDirectory }
        if (sortByRecency) {
            folderFavorites.sortedByDescending { it.lastUsedAt }
        } else {
            folderFavorites.sortedBy { it.order }
        }
    }

    LaunchedEffect(orderedFavorites, items, mode) {
        for (fav in orderedFavorites) {
            val v = withContext(Dispatchers.IO) {
                TransferTargetValidator.validate(fs, items, mode, fav.path)
            }
            validations[fav.id] = v
        }
    }

    LaunchedEffect(miniBrowserDir) {
        val dir = miniBrowserDir ?: return@LaunchedEffect
        miniBrowserSubfolders = withContext(Dispatchers.IO) {
            runCatching {
                fs.list(dir, includeHidden = false).filter { it.isDirectory }.sortedBy { it.name.lowercase() }
            }.getOrDefault(emptyList())
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            val itemDesc = if (items.size == 1) items.first().displayName else s.itemsCount(items.size)
            Text(
                text = s.format(if (mode == TransferMode.COPY) Str.COPY_TARGET_TO else Str.MOVE_TARGET_TO, itemDesc),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(10.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == TransferMode.COPY,
                    onClick = { mode = TransferMode.COPY },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) {
                    Text(s[Str.COPY])
                }
                SegmentedButton(
                    selected = mode == TransferMode.MOVE,
                    onClick = { mode = TransferMode.MOVE },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) {
                    Text(s[Str.MOVE])
                }
            }
            Spacer(Modifier.height(10.dp))

            if (!browsingOtherFolder) {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(orderedFavorites, key = { it.id }) { fav ->
                        val validation = validations[fav.id] ?: TargetValidation.Allowed
                        val allowed = validation.isAllowed
                        val reason = validation.disabledReasonLabel()
                        val unavailable = validation is TargetValidation.TargetUnavailable

                        ListItem(
                            leadingContent = {
                                Icon(
                                    imageVector = if (unavailable) Icons.Filled.Warning else Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = when {
                                        unavailable -> MaterialTheme.colorScheme.error
                                        allowed -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                            headlineContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(fav.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (fav.path is VfsPath.Saf) {
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            Icons.Filled.Usb,
                                            contentDescription = s[Str.EXTERNAL_SAF],
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                    if (fav.path is VfsPath.GDrive) {
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            Icons.Filled.AddToDrive,
                                            contentDescription = "Google Drive",
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            },
                            supportingContent = {
                                val pathLabel = when (val p = fav.path) {
                                    is VfsPath.Local -> p.normalized
                                    is VfsPath.Saf -> p.documentId
                                    is VfsPath.Ftp -> p.serialize()
                                    is VfsPath.GDrive -> p.serialize()
                                }
                                Text(
                                    text = if (reason != null) "$reason · $pathLabel" else pathLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (allowed) 1f else 0.45f)
                                .clickable(enabled = allowed) {
                                    onSelectFavorite(fav, mode)
                                },
                        )
                    }

                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        ListItem(
                            leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                            headlineContent = { Text(s[Str.CHOOSE_ANOTHER_FOLDER]) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { browsingOtherFolder = true },
                        )
                        if (currentDir != null) {
                            ListItem(
                                leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                                headlineContent = { Text(s[Str.ADD_CURRENT_FOLDER_FAVORITES]) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAddCurrentFolderToFavorites() },
                            )
                        }
                    }
                }
            } else {
                // Mini folder browser inside sheet
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(
                        onClick = {
                            val cur = miniBrowserDir
                            if (cur == null) {
                                browsingOtherFolder = false
                            } else {
                                scope.launch {
                                    val par = withContext(Dispatchers.IO) { fs.parent(cur) }
                                    miniBrowserDir = par
                                }
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s[Str.BACK])
                    }
                    Text(
                        text = miniBrowserDir?.displayName ?: s[Str.SELECT_VOLUME],
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val targetDir = miniBrowserDir
                    if (targetDir != null) {
                        Button(
                            onClick = {
                                onSelectCustomFolder(targetDir, targetDir.displayName, mode)
                            },
                        ) {
                            Text(if (mode == TransferMode.COPY) s[Str.COPY_HERE] else s[Str.MOVE_HERE])
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    if (miniBrowserDir == null) {
                        val readyVolumes = volumes.filter { it.root != null }
                        items(readyVolumes, key = { it.id }) { vol ->
                            ListItem(
                                leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                                headlineContent = { Text(if (vol.kind == VolumeKind.PRIMARY) s[Str.INTERNAL_STORAGE] else vol.name) },
                                supportingContent = vol.subtitle?.let { { Text(it, maxLines = 1) } },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { miniBrowserDir = vol.root },
                            )
                        }
                    } else {
                        items(miniBrowserSubfolders, key = { it.path.serialize() }) { sub ->
                            ListItem(
                                leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                                headlineContent = { Text(sub.name) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { miniBrowserDir = sub.path },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
