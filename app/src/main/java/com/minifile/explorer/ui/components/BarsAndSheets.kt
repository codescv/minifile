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
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        navigationIcon = {
            IconButton(onClick = onExitSelection) {
                Icon(Icons.Filled.Close, contentDescription = "Exit selection")
            }
        },
        title = {
            Text("$selectedCount selected", fontWeight = FontWeight.SemiBold)
        },
        actions = {
            TextButton(onClick = onSelectAll) {
                Text("Select all")
            }
            TextButton(onClick = onInvertSelection) {
                Text("Invert")
            }
        },
    )
}

enum class SelectionMoreAction {
    RENAME,
    COPY_TO_FAVORITE,
    MOVE_TO_FAVORITE,
    COMPRESS_ZIP,
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
                Text("Copy")
            }
            TextButton(onClick = onCut) {
                Icon(Icons.Filled.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Cut")
            }
            TextButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Share")
            }
            Box {
                IconButton(onClick = { moreExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(
                    expanded = moreExpanded,
                    onDismissRequest = { moreExpanded = false },
                ) {
                    if (single != null) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                moreExpanded = false
                                onMoreAction(SelectionMoreAction.RENAME)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Copy to Favorite…") },
                        onClick = {
                            moreExpanded = false
                            onMoreAction(SelectionMoreAction.COPY_TO_FAVORITE)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Move to Favorite…") },
                        onClick = {
                            moreExpanded = false
                            onMoreAction(SelectionMoreAction.MOVE_TO_FAVORITE)
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Compress to ZIP") },
                        onClick = {
                            moreExpanded = false
                            onMoreAction(SelectionMoreAction.COMPRESS_ZIP)
                        },
                    )
                    if (singleIsZip) {
                        DropdownMenuItem(
                            text = { Text("Extract…") },
                            onClick = {
                                moreExpanded = false
                                onMoreAction(SelectionMoreAction.EXTRACT_ZIP)
                            },
                        )
                    }
                    if (single != null) {
                        DropdownMenuItem(
                            text = { Text("Add to Favorites") },
                            onClick = {
                                moreExpanded = false
                                onMoreAction(SelectionMoreAction.ADD_TO_FAVORITES)
                            },
                        )
                    }
                    if (allFolders) {
                        DropdownMenuItem(
                            text = { Text("Hide from gallery (.nomedia)") },
                            onClick = {
                                moreExpanded = false
                                onMoreAction(SelectionMoreAction.TOGGLE_NOMEDIA)
                            },
                        )
                    }
                    if (single != null) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Properties") },
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
    var showItemsDialog by remember { mutableStateOf(false) }
    val verb = if (clipboard.mode == TransferMode.COPY) "Copy" else "Move"
    val label = "$verb ${clipboard.items.size} item${if (clipboard.items.size == 1) "" else "s"}"

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
                Text("To Favorite ▾", style = MaterialTheme.typography.labelMedium)
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
                Text("Paste here", style = MaterialTheme.typography.labelMedium)
            }
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Clear clipboard", modifier = Modifier.size(18.dp))
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
                    Text("Close")
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text("Sort & View", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            val sortOptions = listOf(
                SortField.NAME to "Name",
                SortField.SIZE to "Size",
                SortField.DATE to "Date modified",
                SortField.TYPE to "Type",
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
                    Text("Ascending")
                }
                SegmentedButton(
                    selected = !currentSort.ascending,
                    onClick = { onUpdateSort(currentSort.copy(ascending = false)) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) {
                    Text("Descending")
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Folders first", style = MaterialTheme.typography.bodyLarge)
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
                    label = { Text("Show hidden files") },
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
                    label = { Text(if (viewMode == ViewMode.GRID) "Grid view" else "List view") },
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
            val actionVerb = if (mode == TransferMode.COPY) "Copy" else "Move"
            val itemDesc = if (items.size == 1) items.first().displayName else "${items.size} items"
            Text(
                text = "$actionVerb $itemDesc to…",
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
                    Text("Copy")
                }
                SegmentedButton(
                    selected = mode == TransferMode.MOVE,
                    onClick = { mode = TransferMode.MOVE },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) {
                    Text("Move")
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
                                            contentDescription = "External/SAF",
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
                            headlineContent = { Text("Choose another folder…") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { browsingOtherFolder = true },
                        )
                        if (currentDir != null) {
                            ListItem(
                                leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                                headlineContent = { Text("Add current folder to Favorites") },
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        text = miniBrowserDir?.displayName ?: "Select volume",
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
                            Text("$actionVerb here")
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
                                headlineContent = { Text(vol.name) },
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
