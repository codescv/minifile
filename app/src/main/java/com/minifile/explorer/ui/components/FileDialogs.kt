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
    val initial = remember(existingNames) {
        PathUtils.uniqueName("New folder", existingNames, isDirectory = true)
    }
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New folder") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text("Folder name") },
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
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun CreateFileDialog(
    existingNames: Set<String>,
    onConfirm: (name: String, openInEditor: Boolean, onError: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = remember(existingNames) {
        PathUtils.uniqueName("untitled.txt", existingNames, isDirectory = false)
    }
    var name by remember { mutableStateOf(initial) }
    var openInEditor by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New file") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text("File name") },
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
                    Text("Open in editor")
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
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun RenameDialog(
    node: VfsNode,
    onConfirm: (newName: String, onError: (String) -> Unit, onSuccess: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(node.path) { mutableStateOf(node.name) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text("Name") },
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
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun DeleteConfirmDialog(
    nodes: List<VfsNode>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val allGDrive = nodes.isNotEmpty() && nodes.all { it.path is VfsPath.GDrive }
    val title = if (nodes.size == 1) {
        "Delete '${nodes.first().name}'?"
    } else {
        "Delete ${nodes.size} items?"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    if (allGDrive) {
                        "Selected items will be moved to Google Drive trash."
                    } else {
                        "This can't be undone."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                val shown = nodes.take(5)
                for (n in shown) {
                    val tag = if (n.isDirectory) " (folder)" else ""
                    Text("• ${n.name}$tag", style = MaterialTheme.typography.bodySmall)
                }
                if (nodes.size > 5) {
                    Text(
                        "…and ${nodes.size - 5} more",
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
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun ConflictDialog(
    prompt: ConflictPrompt,
    onResolve: (ConflictResolution, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var applyToAll by remember(prompt) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { onResolve(ConflictResolution.SKIP, applyToAll) },
        title = { Text("Item already exists") },
        text = {
            Column {
                Text(
                    "'${prompt.existing.name}' already exists in the destination.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Source: ${UiFormatters.formatSize(context, prompt.source.size)} · ${UiFormatters.formatShortDate(prompt.source.lastModified)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Existing: ${UiFormatters.formatSize(context, prompt.existing.size)} · ${UiFormatters.formatShortDate(prompt.existing.lastModified)}",
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
                    Text("Apply to all conflicts")
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { onResolve(ConflictResolution.SKIP, applyToAll) }) {
                    Text("Skip")
                }
                OutlinedButton(onClick = { onResolve(ConflictResolution.KEEP_BOTH, applyToAll) }) {
                    Text("Keep both")
                }
                Button(onClick = { onResolve(ConflictResolution.OVERWRITE, applyToAll) }) {
                    Text("Overwrite")
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
    var name by remember(defaultName) { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to Favorites") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Bookmark name") },
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
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
    var selectedDestLabel by remember { mutableStateOf("Current folder (${currentDir.displayName})") }
    var destExpanded by remember { mutableStateOf(false) }
    var level by remember { mutableIntStateOf(Deflater.DEFAULT_COMPRESSION) }

    val levels = listOf(
        Deflater.NO_COMPRESSION to "Store",
        Deflater.BEST_SPEED to "Fast",
        Deflater.DEFAULT_COMPRESSION to "Normal",
        Deflater.BEST_COMPRESSION to "Best",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compress to ZIP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = archiveName,
                    onValueChange = { archiveName = it },
                    label = { Text("Archive name") },
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
                        label = { Text("Destination") },
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
                            text = { Text("Current folder (${currentDir.displayName})") },
                            onClick = {
                                selectedDest = currentDir
                                selectedDestLabel = "Current folder (${currentDir.displayName})"
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

                Text("Compression level", style = MaterialTheme.typography.labelMedium)
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
                Text("Compress")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
    val stem = remember(zipPath) {
        PathUtils.splitBaseAndExtension(zipPath.displayName, isDirectory = false).first.ifEmpty { "extracted" }
    }
    var createSubfolder by remember { mutableStateOf(true) }
    var subfolderName by remember { mutableStateOf(stem) }
    var overwrite by remember { mutableStateOf(false) }
    var selectedDest by remember { mutableStateOf(defaultParentDir) }
    var selectedDestLabel by remember { mutableStateOf("Current folder (${defaultParentDir.displayName})") }
    var destExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Extract ${zipPath.displayName}") },
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
                        label = { Text("Extract into") },
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
                            text = { Text("Current folder (${defaultParentDir.displayName})") },
                            onClick = {
                                selectedDest = defaultParentDir
                                selectedDestLabel = "Current folder (${defaultParentDir.displayName})"
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
                    Text("Extract into new folder")
                }

                if (createSubfolder) {
                    OutlinedTextField(
                        value = subfolderName,
                        onValueChange = { subfolderName = it },
                        label = { Text("Folder name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Overwrite existing files")
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
                Text("Extract")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
                    Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = "Rename")
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
                    Text("Install APK")
                }
            }

            PropertyRow("Type", node.resolvedMimeType)

            if (node.isDirectory) {
                if (computingStats) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Calculating folder size…", style = MaterialTheme.typography.bodySmall)
                    }
                } else if (folderStats != null) {
                    val st = folderStats!!
                    PropertyRow(
                        "Contents",
                        "${UiFormatters.formatExactSize(context, st.totalBytes)} · ${st.fileCount} files, ${st.dirCount} folders",
                    )
                }
            } else {
                PropertyRow("Size", UiFormatters.formatExactSize(context, node.size))
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
                        UiFormatters.copyToSystemClipboard(context, "Path", fullLoc)
                        onCopiedMessage("Location copied to clipboard")
                    },
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Location", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(fullLoc, style = MaterialTheme.typography.bodyMedium)
                }
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy path", modifier = Modifier.size(18.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            val iso = UiFormatters.formatIsoDate(node.lastModified)
                            UiFormatters.copyToSystemClipboard(context, "ISO Timestamp", iso)
                            onCopiedMessage("Copied ISO timestamp: $iso")
                        },
                    ),
            ) {
                Text("Last modified", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(UiFormatters.formatFullDate(node.lastModified), style = MaterialTheme.typography.bodyMedium)
            }

            val flagsText = buildList {
                add(if (node.canRead) "Readable" else "Not readable")
                add(if (node.canWrite) "Writable" else "Read-only")
                if (node.isHidden) add("Hidden")
                if (node.isSymlink) add("Symlink")
            }.joinToString(" · ")
            PropertyRow("Attributes", flagsText)
            PropertyRow("Permissions", node.posixPermissions ?: "—")
            if (node.symlinkTarget != null) {
                PropertyRow("Symlink target", node.symlinkTarget)
            }

            HorizontalDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Favorite")
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
                    Text("Hide from gallery (.nomedia)")
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
                Text("Checksums", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

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
                            onCopiedMessage("MD5 copied")
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
                            onCopiedMessage("SHA-256 copied")
                        }
                    },
                )

                OutlinedTextField(
                    value = expectedHash,
                    onValueChange = { expectedHash = it },
                    label = { Text("Verify expected hash (MD5 or SHA-256)") },
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
                            text = if (matched) "✓ Checksum matches" else "✗ Checksum does not match",
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
                Text("Compute $algorithm")
            }
        } else {
            IconButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy $algorithm")
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text("Active Operations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                                Text("Cancel", color = MaterialTheme.colorScheme.error)
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
