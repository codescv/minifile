package com.minifile.explorer.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToDrive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minifile.explorer.core.model.Favorite
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.ui.MainViewModel
import com.minifile.explorer.ui.components.PropertiesSheet
import com.minifile.explorer.ui.util.UiFormatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favorites by viewModel.favorites.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val statMap = remember { mutableStateMapOf<String, VfsNode?>() }
    var renameTarget by remember { mutableStateOf<Favorite?>(null) }
    var unavailablePrompt by remember { mutableStateOf<Favorite?>(null) }
    var propertiesNode by remember { mutableStateOf<VfsNode?>(null) }
    var exportedJson by remember { mutableStateOf<String?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(favorites) {
        for (fav in favorites) {
            val st = withContext(Dispatchers.IO) {
                runCatching { viewModel.container.fileSystem.stat(fav.path) }.getOrNull()
            }
            statMap[fav.id] = st
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Favorites", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                exportedJson = viewModel.container.favoritesRepository.exportJson()
                            }
                        },
                    ) {
                        Icon(Icons.Filled.FileUpload, contentDescription = "Export Favorites JSON")
                    }
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(Icons.Filled.FileDownload, contentDescription = "Import Favorites JSON")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (favorites.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
            ) {
                Icon(
                    Icons.Filled.StarBorder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(52.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text("No favorites yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Bookmark any folder or file from Browse for one-tap access and quick transfers.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(favorites, key = { _, f -> f.id }) { idx, fav ->
                    val node = statMap[fav.id]
                    val isAvailable = !statMap.containsKey(fav.id) || node != null
                    val effectiveIsDir = node?.isDirectory ?: fav.isDirectory
                    var menuOpen by remember { mutableStateOf(false) }

                    ListItem(
                        leadingContent = {
                            Icon(
                                imageVector = when {
                                    !isAvailable -> Icons.Filled.Warning
                                    effectiveIsDir -> Icons.Filled.Star
                                    else -> Icons.Filled.Description
                                },
                                contentDescription = null,
                                tint = if (isAvailable) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        },
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = fav.name,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (node?.hasNomedia == true) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        Icons.Filled.VisibilityOff,
                                        contentDescription = "Hidden from gallery",
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                if (fav.path is VfsPath.Saf) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        Icons.Filled.Usb,
                                        contentDescription = "External/SAF",
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                                if (fav.path is VfsPath.Ftp) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        Icons.Filled.Cloud,
                                        contentDescription = "FTP",
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                                if (fav.path is VfsPath.GDrive) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        Icons.Filled.AddToDrive,
                                        contentDescription = "Google Drive",
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                        },
                        supportingContent = {
                            val sub = when (val p = fav.path) {
                                is VfsPath.Local -> p.normalized
                                is VfsPath.Saf -> p.documentId
                                is VfsPath.Ftp -> p.serialize()
                                is VfsPath.GDrive -> p.serialize()
                            }
                            Text(
                                text = if (isAvailable) sub else "Unavailable · $sub",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (idx > 0) {
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                val ids = favorites.map { it.id }.toMutableList()
                                                val moved = ids.removeAt(idx)
                                                ids.add(idx - 1, moved)
                                                viewModel.container.favoritesRepository.reorder(ids)
                                            }
                                        },
                                    ) {
                                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
                                    }
                                }
                                if (idx < favorites.lastIndex) {
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                val ids = favorites.map { it.id }.toMutableList()
                                                val moved = ids.removeAt(idx)
                                                ids.add(idx + 1, moved)
                                                viewModel.container.favoritesRepository.reorder(ids)
                                            }
                                        },
                                    ) {
                                        Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
                                    }
                                }
                                Box {
                                    IconButton(onClick = { menuOpen = true }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = "More actions for ${fav.name}")
                                    }
                                    DropdownMenu(
                                        expanded = menuOpen,
                                        onDismissRequest = { menuOpen = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Rename") },
                                            onClick = {
                                                menuOpen = false
                                                renameTarget = fav
                                            },
                                        )
                                        if (node != null) {
                                            DropdownMenuItem(
                                                text = { Text("Properties") },
                                                onClick = {
                                                    menuOpen = false
                                                    propertiesNode = node
                                                },
                                            )
                                        }
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
                                            onClick = {
                                                menuOpen = false
                                                scope.launch {
                                                    viewModel.container.favoritesRepository.remove(fav.id)
                                                    val res = snackbarHostState.showSnackbar(
                                                        message = "Removed '${fav.name}'",
                                                        actionLabel = "Undo",
                                                        duration = SnackbarDuration.Short,
                                                    )
                                                    if (res == SnackbarResult.ActionPerformed) {
                                                        viewModel.container.favoritesRepository.add(
                                                            name = fav.name,
                                                            path = fav.path,
                                                            color = fav.color,
                                                            isDirectory = fav.isDirectory,
                                                        )
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isAvailable) {
                                    viewModel.openFavoriteInBrowse(fav, context)
                                } else {
                                    unavailablePrompt = fav
                                }
                            },
                    )
                }
            }
        }
    }

    renameTarget?.let { fav ->
        var text by remember(fav.id) { mutableStateOf(fav.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Favorite") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            viewModel.container.favoritesRepository.rename(fav.id, text)
                        }
                        renameTarget = null
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }

    unavailablePrompt?.let { fav ->
        AlertDialog(
            onDismissRequest = { unavailablePrompt = null },
            title = { Text("'${fav.name}' is unavailable") },
            text = {
                Text(
                    "This ${if (fav.isDirectory) "folder" else "file"} does not exist right now or its storage/server is unreachable.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch { viewModel.container.favoritesRepository.remove(fav.id) }
                        unavailablePrompt = null
                    },
                ) {
                    Text("Remove Favorite")
                }
            },
            dismissButton = {
                TextButton(onClick = { unavailablePrompt = null }) { Text("Keep") }
            },
        )
    }

    propertiesNode?.let { node ->
        PropertiesSheet(
            node = node,
            isFavorite = favorites.any { it.path == node.path },
            fileOps = viewModel.container.fileOperations,
            checksumCalculator = viewModel.container.checksumCalculator,
            safFs = viewModel.container.safFileSystem,
            onRequestRename = {},
            onToggleFavorite = {
                if (favorites.any { it.path == node.path }) {
                    viewModel.removeFavoriteByPath(node.path)
                } else {
                    viewModel.addOrUpdateFavorite(node.name, node.path, isDirectory = node.isDirectory)
                }
            },
            onToggleNomedia = { viewModel.toggleNomedia(node.path) },
            onCopiedMessage = { viewModel.emitSimpleMessage(it) },
            onDismiss = { propertiesNode = null },
        )
    }

    exportedJson?.let { json ->
        AlertDialog(
            onDismissRequest = { exportedJson = null },
            title = { Text("Export Favorites JSON") },
            text = {
                OutlinedTextField(
                    value = json,
                    onValueChange = {},
                    readOnly = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        UiFormatters.copyToSystemClipboard(context, "Favorites JSON", json)
                        viewModel.emitSimpleMessage("Favorites JSON copied to clipboard")
                        exportedJson = null
                    },
                ) {
                    Text("Copy JSON")
                }
            },
            dismissButton = {
                TextButton(onClick = { exportedJson = null }) { Text("Close") }
            },
        )
    }

    if (showImportDialog) {
        var inputJson by remember { mutableStateOf("") }
        var merge by remember { mutableStateOf(true) }
        var importErr by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import Favorites JSON") },
            text = {
                Column {
                    OutlinedTextField(
                        value = inputJson,
                        onValueChange = {
                            inputJson = it
                            importErr = null
                        },
                        placeholder = { Text("[{\"id\": ...}]") },
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (importErr != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(importErr!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { merge = !merge },
                    ) {
                        Checkbox(checked = merge, onCheckedChange = { merge = it })
                        Spacer(Modifier.width(6.dp))
                        Text("Merge with existing favorites")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                viewModel.container.favoritesRepository.importJson(inputJson, merge)
                                showImportDialog = false
                                viewModel.emitSimpleMessage("Favorites imported")
                            } catch (e: Exception) {
                                importErr = e.message ?: "Invalid JSON"
                            }
                        }
                    },
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel") }
            },
        )
    }
}
