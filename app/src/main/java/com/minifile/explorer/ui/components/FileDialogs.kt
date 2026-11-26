package com.minifile.explorer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.minifile.explorer.core.i18n.Str
import com.minifile.explorer.core.model.Favorite
import com.minifile.explorer.core.model.FolderStats
import com.minifile.explorer.core.model.OperationProgress
import com.minifile.explorer.core.ops.ChecksumCalculator
import com.minifile.explorer.core.ops.ConflictResolution
import com.minifile.explorer.core.ops.FileOperations
import com.minifile.explorer.core.ops.ProgressSink
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.data.SafFileSystem
import com.minifile.explorer.ui.ConflictPrompt
import com.minifile.explorer.ui.i18n.LocalAppStrings
import com.minifile.explorer.ui.util.UiFormatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.Deflater

@Composable
fun CreateFolderDialog(
    existingNames: Set<String>,
    onConfirm: (name: String, onError: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    val initial = remember(existingNames, s) {
        PathUtils.uniqueName(s[Str.NEW_FOLDER], existingNames, isDirectory = true)
    }
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[Str.NEW_FOLDER]) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text(s[Str.FOLDER_NAME]) },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(name) { err -> error = err }
                    if (error == null) onDismiss()
                },
            ) {
                Text(s[Str.CREATE])
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[Str.CANCEL]) }
        },
    )
}

@Composable
fun CreateFileDialog(
    existingNames: Set<String>,
    onConfirm: (name: String, openInEditor: Boolean, onError: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    val initial = remember(existingNames) {
        PathUtils.uniqueName("untitled.txt", existingNames, isDirectory = false)
    }
    var name by remember { mutableStateOf(initial) }
    var openInEditor by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[Str.NEW_FILE]) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text(s[Str.FILE_NAME]) },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openInEditor = !openInEditor },
                ) {
                    Checkbox(checked = openInEditor, onCheckedChange = { openInEditor = it })
                    Spacer(Modifier.width(6.dp))
                    Text(s[Str.OPEN_IN_EDITOR])
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    var failed = false
                    onConfirm(name, openInEditor) { err ->
                        error = err
                        failed = true
                    }
                    if (!failed) onDismiss()
                },
            ) {
                Text(s[Str.CREATE])
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[Str.CANCEL]) }
        },
    )
}

@Composable
fun RenameDialog(
    node: VfsNode,
    onConfirm: (newName: String, onError: (String) -> Unit, onSuccess: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    var name by remember(node.path) { mutableStateOf(node.name) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[Str.RENAME]) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text(s[Str.NAME]) },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.trim() == node.name) {
                        onDismiss()
                    } else {
                        onConfirm(
                            name,
                            { err -> error = err },
                            { onDismiss() },
                        )
                    }
                },
            ) {
                Text(s[Str.RENAME])
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[Str.CANCEL]) }
        },
    )
}

@Composable
fun DeleteConfirmDialog(
    nodes: List<VfsNode>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    val allGDrive = nodes.isNotEmpty() && nodes.all { it.path is VfsPath.GDrive }
    val title = if (nodes.size == 1) {
        s.format(Str.DELETE_SINGLE_TITLE, nodes.first().name)
    } else {
        s.format(Str.DELETE_MULTI_TITLE, nodes.size)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    if (allGDrive) s[Str.DELETE_GDRIVE_NOTE] else s[Str.DELETE_PERMANENT_NOTE],
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                val shown = nodes.take(5)
                for (n in shown) {
                    val tag = if (n.isDirectory) s[Str.FOLDER_TAG] else ""
                    Text("• ${n.name}$tag", style = MaterialTheme.typography.bodySmall)
                }
                if (nodes.size > 5) {
                    Text(
                        s.format(Str.AND_MORE_ITEMS, nodes.size - 5),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(s[Str.DELETE])
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[Str.CANCEL]) }
        },
    )
}

@Composable
fun ConflictDialog(
    prompt: ConflictPrompt,
    onResolve: (ConflictResolution, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val s = LocalAppStrings.current
    var applyToAll by remember(prompt) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { onResolve(ConflictResolution.SKIP, applyToAll) },
        title = { Text(s[Str.ITEM_ALREADY_EXISTS]) },
        text = {
            Column {
                Text(
                    s.format(Str.ITEM_EXISTS_IN_DEST, prompt.existing.name),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    s.format(Str.CONFLICT_SOURCE, UiFormatters.formatSize(context, prompt.source.size), UiFormatters.formatShortDate(prompt.source.lastModified)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    s.format(Str.CONFLICT_EXISTING, UiFormatters.formatSize(context, prompt.existing.size), UiFormatters.formatShortDate(prompt.existing.lastModified)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { applyToAll = !applyToAll },
                ) {
                    Checkbox(checked = applyToAll, onCheckedChange = { applyToAll = it })
                    Spacer(Modifier.width(6.dp))
                    Text(s[Str.APPLY_TO_ALL_CONFLICTS])
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { onResolve(ConflictResolution.SKIP, applyToAll) }) {
                    Text(s[Str.SKIP])
                }
                OutlinedButton(onClick = { onResolve(ConflictResolution.KEEP_BOTH, applyToAll) }) {
                    Text(s[Str.KEEP_BOTH])
                }
                Button(onClick = { onResolve(ConflictResolution.OVERWRITE, applyToAll) }) {
                    Text(s[Str.OVERWRITE])
                }
            }
        },
    )
}

@Composable
fun AddFavoriteDialog(
    defaultName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    var name by remember(defaultName) { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[Str.ADD_TO_FAVORITES]) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(s[Str.BOOKMARK_NAME]) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(name.trim().ifEmpty { defaultName })
                    onDismiss()
                },
            ) {
                Text(s[Str.SAVE])
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[Str.CANCEL]) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressZipDialog(
    items: List<VfsNode>,
    currentDir: VfsPath,
    existingNames: Set<String>,
    favorites: List<Favorite>,
    onConfirm: (archiveName: String, destinationDir: VfsPath, level: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    val baseDefault = remember(items, currentDir, existingNames) {
        val stem = if (items.size == 1) {
            val single = items.first()
            if (single.isDirectory) single.name else PathUtils.splitBaseAndExtension(single.name, false).first
        } else {
            currentDir.displayName.ifEmpty { "archive" }
        }
        PathUtils.uniqueName("$stem.zip", existingNames, isDirectory = false)
    }
    var archiveName by remember { mutableStateOf(baseDefault) }
    var selectedDest by remember { mutableStateOf(currentDir) }
    val currentFolderLabel = s.format(Str.CURRENT_FOLDER_FMT, currentDir.displayName)
    var selectedDestLabel by remember(currentFolderLabel) { mutableStateOf(currentFolderLabel) }
    var destExpanded by remember { mutableStateOf(false) }
    var level by remember { mutableIntStateOf(Deflater.DEFAULT_COMPRESSION) }

    val levels = listOf(
        Deflater.NO_COMPRESSION to s[Str.COMP_STORE],
        Deflater.BEST_SPEED to s[Str.COMP_FAST],
        Deflater.DEFAULT_COMPRESSION to s[Str.COMP_NORMAL],
        Deflater.BEST_COMPRESSION to s[Str.COMP_BEST],
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[Str.COMPRESS_TO_ZIP]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = archiveName,
                    onValueChange = { archiveName = it },
                    label = { Text(s[Str.ARCHIVE_NAME]) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                ExposedDropdownMenuBox(
                    expanded = destExpanded,
                    onExpandedChange = { destExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedDestLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(s[Str.DESTINATION]) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = destExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = destExpanded,
                        onDismissRequest = { destExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(currentFolderLabel) },
                            onClick = {
                                selectedDest = currentDir
                                selectedDestLabel = currentFolderLabel
                                destExpanded = false
                            },
                        )
                        for (fav in favorites.filter { it.isDirectory }) {
                            DropdownMenuItem(
                                text = { Text("★ ${fav.name}") },
                                onClick = {
                                    selectedDest = fav.path
                                    selectedDestLabel = "★ ${fav.name}"
                                    destExpanded = false
                                },
                            )
                        }
                    }
                }

                Text(s[Str.COMPRESSION_LEVEL], style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((lvl, label) in levels) {
                        FilterChip(
                            selected = level == lvl,
                            onClick = { level = lvl },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = archiveName.trim().ifEmpty { "archive.zip" }
                    onConfirm(trimmed, selectedDest, level)
                    onDismiss()
                },
            ) {
                Text(s[Str.COMPRESS])
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[Str.CANCEL]) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractZipDialog(
    zipPath: VfsPath,
    defaultParentDir: VfsPath,
    favorites: List<Favorite>,
    onConfirm: (destinationDir: VfsPath, newSubfolderName: String?, overwrite: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    val stem = remember(zipPath) {
        PathUtils.splitBaseAndExtension(zipPath.displayName, isDirectory = false).first.ifEmpty { "extracted" }
    }
    var createSubfolder by remember { mutableStateOf(true) }
    var subfolderName by remember { mutableStateOf(stem) }
    var overwrite by remember { mutableStateOf(false) }
    var selectedDest by remember { mutableStateOf(defaultParentDir) }
    val currentFolderLabel = s.format(Str.CURRENT_FOLDER_FMT, defaultParentDir.displayName)
    var selectedDestLabel by remember(currentFolderLabel) { mutableStateOf(currentFolderLabel) }
    var destExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.format(Str.EXTRACT_TITLE, zipPath.displayName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExposedDropdownMenuBox(
                    expanded = destExpanded,
                    onExpandedChange = { destExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedDestLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(s[Str.EXTRACT_INTO]) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = destExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = destExpanded,
                        onDismissRequest = { destExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(currentFolderLabel) },
                            onClick = {
                                selectedDest = defaultParentDir
                                selectedDestLabel = currentFolderLabel
                                destExpanded = false
                            },
                        )
                        for (fav in favorites.filter { it.isDirectory }) {
                            DropdownMenuItem(
                                text = { Text("★ ${fav.name}") },
                                onClick = {
                                    selectedDest = fav.path
                                    selectedDestLabel = "★ ${fav.name}"
                                    destExpanded = false
                                },
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { createSubfolder = !createSubfolder },
                ) {
                    Checkbox(checked = createSubfolder, onCheckedChange = { createSubfolder = it })
                    Spacer(Modifier.width(6.dp))
                    Text(s[Str.EXTRACT_INTO_NEW_FOLDER])
                }

                if (createSubfolder) {
                    OutlinedTextField(
                        value = subfolderName,
                        onValueChange = { subfolderName = it },
                        label = { Text(s[Str.FOLDER_NAME]) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(s[Str.OVERWRITE_EXISTING_FILES])
                    Switch(checked = overwrite, onCheckedChange = { overwrite = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        selectedDest,
                        if (createSubfolder) subfolderName.trim().ifEmpty { stem } else null,
                        overwrite,
                    )
                    onDismiss()
                },
            ) {
                Text(s[Str.EXTRACT])
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[Str.CANCEL]) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PropertiesSheet(
    node: VfsNode,
    isFavorite: Boolean,
    fileOps: FileOperations,
    checksumCalculator: ChecksumCalculator,
    safFs: SafFileSystem,
    onRequestRename: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleNomedia: () -> Unit,
    onCopiedMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val s = LocalAppStrings.current
    val scope = rememberCoroutineScope()

    var folderStats by remember(node.path) { mutableStateOf<FolderStats?>(null) }
    var computingStats by remember(node.path) { mutableStateOf(false) }

    var md5Value by remember(node.path) { mutableStateOf<String?>(null) }
    var computingMd5 by remember(node.path) { mutableStateOf(false) }

    var sha256Value by remember(node.path) { mutableStateOf<String?>(null) }
    var computingSha256 by remember(node.path) { mutableStateOf(false) }

    var expectedHash by remember(node.path) { mutableStateOf("") }
    var hashJob by remember { mutableStateOf<Job?>(null) }
    var hashCancelled by remember { mutableStateOf(false) }

    DisposableEffect(node.path) {
        onDispose {
            hashCancelled = true
            hashJob?.cancel()
        }
    }

    LaunchedEffect(node.path) {
        if (node.isDirectory) {
            computingStats = true
            var cancelled = false
            val sink = object : ProgressSink {
                override fun onProgress(p: OperationProgress) = Unit
                override fun isCancelled(): Boolean = cancelled
            }
            folderStats = withContext(Dispatchers.IO) {
                runCatching { fileOps.sizeOf(listOf(node.path), sink) }.getOrNull()
            }
            computingStats = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        onDismiss()
                        onRequestRename()
                    },
                ) {
                    Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = s[Str.RENAME])
                }
            }

            if (!node.isDirectory && PathUtils.extensionOf(node.name) == "apk") {
                Button(
                    onClick = {
                        scope.launch {
                            UiFormatters.openExternallyAsync(context, safFs, fileOps.fileSystem, node)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(s[Str.INSTALL_APK])
                }
            }

            PropertyRow(s[Str.TYPE], node.resolvedMimeType)

            if (node.isDirectory) {
                if (computingStats) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(s[Str.CALCULATING_FOLDER_SIZE], style = MaterialTheme.typography.bodySmall)
                    }
                } else if (folderStats != null) {
                    val st = folderStats!!
                    PropertyRow(
                        s[Str.CONTENTS],
                        s.format(Str.FOLDER_CONTENTS_FMT, UiFormatters.formatExactSize(context, st.totalBytes), st.fileCount, st.dirCount),
                    )
                }
            } else {
                PropertyRow(s[Str.SIZE], UiFormatters.formatExactSize(context, node.size))
            }

            val fullLoc = when (val p = node.path) {
                is VfsPath.Local -> p.normalized
                is VfsPath.Saf -> "${p.treeUri} (${p.documentId})"
                is VfsPath.Ftp -> p.serialize()
                is VfsPath.GDrive -> p.serialize()
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        UiFormatters.copyToSystemClipboard(context, s[Str.PATH], fullLoc)
                        onCopiedMessage(s[Str.LOCATION_COPIED])
                    },
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(s[Str.LOCATION], style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(fullLoc, style = MaterialTheme.typography.bodyMedium)
                }
                Icon(Icons.Filled.ContentCopy, contentDescription = s[Str.COPY_PATH], modifier = Modifier.size(18.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            val iso = UiFormatters.formatIsoDate(node.lastModified)
                            UiFormatters.copyToSystemClipboard(context, s[Str.ISO_TIMESTAMP], iso)
                            onCopiedMessage(s.format(Str.COPIED_ISO_TIMESTAMP, iso))
                        },
                    ),
            ) {
                Text(s[Str.LAST_MODIFIED], style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(UiFormatters.formatFullDate(node.lastModified), style = MaterialTheme.typography.bodyMedium)
            }

            val flagsText = buildList {
                add(if (node.canRead) s[Str.ATTR_READABLE] else s[Str.ATTR_NOT_READABLE])
                add(if (node.canWrite) s[Str.ATTR_WRITABLE] else s[Str.READ_ONLY])
                if (node.isHidden) add(s[Str.ATTR_HIDDEN])
                if (node.isSymlink) add(s[Str.ATTR_SYMLINK])
            }.joinToString(" · ")
            PropertyRow(s[Str.ATTRIBUTES], flagsText)
            PropertyRow(s[Str.PERMISSIONS], node.posixPermissions ?: "—")
            if (node.symlinkTarget != null) {
                PropertyRow(s[Str.SYMLINK_TARGET], node.symlinkTarget)
            }

            HorizontalDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(s[Str.FAVORITE])
                Switch(
                    checked = isFavorite,
                    onCheckedChange = {
                        onToggleFavorite()
                    },
                )
            }
            if (node.isDirectory) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(s[Str.HIDE_FROM_GALLERY_NOMEDIA])
                    Switch(
                        checked = node.hasNomedia,
                        onCheckedChange = {
                            onToggleNomedia()
                            onDismiss()
                        },
                    )
                }
            } else {
                HorizontalDivider()
                Text(s[Str.CHECKSUMS], style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                ChecksumRow(
                    algorithm = "MD5",
                    value = md5Value,
                    computing = computingMd5,
                    onCompute = {
                        computingMd5 = true
                        hashCancelled = false
                        val sink = object : ProgressSink {
                            override fun onProgress(p: OperationProgress) = Unit
                            override fun isCancelled(): Boolean = hashCancelled
                        }
                        hashJob = scope.launch {
                            md5Value = withContext(Dispatchers.IO) {
                                runCatching { checksumCalculator.digest(node.path, "MD5", sink) }.getOrNull()
                            }
                            computingMd5 = false
                        }
                    },
                    onCopy = {
                        md5Value?.let {
                            UiFormatters.copyToSystemClipboard(context, "MD5", it)
                            onCopiedMessage(s[Str.MD5_COPIED])
                        }
                    },
                )

                ChecksumRow(
                    algorithm = "SHA-256",
                    value = sha256Value,
                    computing = computingSha256,
                    onCompute = {
                        computingSha256 = true
                        hashCancelled = false
                        val sink = object : ProgressSink {
                            override fun onProgress(p: OperationProgress) = Unit
                            override fun isCancelled(): Boolean = hashCancelled
                        }
                        hashJob = scope.launch {
                            sha256Value = withContext(Dispatchers.IO) {
                                runCatching { checksumCalculator.digest(node.path, "SHA-256", sink) }.getOrNull()
                            }
                            computingSha256 = false
                        }
                    },
                    onCopy = {
                        sha256Value?.let {
                            UiFormatters.copyToSystemClipboard(context, "SHA-256", it)
                            onCopiedMessage(s[Str.SHA256_COPIED])
                        }
                    },
                )

                OutlinedTextField(
                    value = expectedHash,
                    onValueChange = { expectedHash = it },
                    label = { Text(s[Str.VERIFY_EXPECTED_HASH]) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val cleanExpected = expectedHash.trim().lowercase()
                if (cleanExpected.isNotEmpty() && (md5Value != null || sha256Value != null)) {
                    val matched = cleanExpected == md5Value || cleanExpected == sha256Value
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (matched) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                            contentDescription = null,
                            tint = if (matched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (matched) s[Str.CHECKSUM_MATCHES] else s[Str.CHECKSUM_MISMATCH],
                            color = if (matched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ChecksumRow(
    algorithm: String,
    value: String?,
    computing: Boolean,
    onCompute: () -> Unit,
    onCopy: () -> Unit,
) {
    val s = LocalAppStrings.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(algorithm, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            } else if (computing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        if (value == null) {
            OutlinedButton(onClick = onCompute, enabled = !computing) {
                Text(s.format(Str.COMPUTE_ALG, algorithm))
            }
        } else {
            IconButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = s.format(Str.COPY_ALG, algorithm))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveOperationsSheet(
    operations: List<OperationProgress>,
    onCancel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val s = LocalAppStrings.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(s[Str.ACTIVE_OPERATIONS], style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(operations, key = { it.id }) { op ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(op.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onCancel(op.id) }) {
                                Text(s[Str.CANCEL], color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (op.currentItem.isNotEmpty()) {
                            Text(
                                text = op.currentItem,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        val frac = op.fraction
                        if (frac != null) {
                            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        if (op.bytesPerSecond > 0L) {
                            Text(
                                text = "${UiFormatters.formatSize(context, op.bytesPerSecond)}/s",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
