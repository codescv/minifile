package com.minifile.explorer.ui.screens

import android.os.Environment
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minifile.explorer.core.ops.EncryptedZipException
import com.minifile.explorer.core.ops.ZipArchiver
import com.minifile.explorer.core.ops.ZipEntryInfo
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.ui.MainViewModel
import com.minifile.explorer.ui.components.ExtractZipDialog
import com.minifile.explorer.ui.util.UiFormatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ArchivePreviewScreen(
    zipPath: VfsPath,
    viewModel: MainViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val favorites by viewModel.favorites.collectAsState()
    val browser by viewModel.browser.collectAsState()

    var entries by remember(zipPath) { mutableStateOf<List<ZipEntryInfo>>(emptyList()) }
    var parentDir by remember(zipPath) { mutableStateOf<VfsPath?>(null) }
    var loading by remember(zipPath) { mutableStateOf(true) }
    var errorMessage by remember(zipPath) { mutableStateOf<String?>(null) }
    var selectedNames by remember(zipPath) { mutableStateOf<Set<String>>(emptySet()) }
    var showExtractDialog by remember { mutableStateOf(false) }
    var bombWarningPending by remember { mutableStateOf<Pair<VfsPath, String?>?>(null) }

    val ext = PathUtils.extensionOf(zipPath.displayName)
    val isZipArchive = ext == "zip"

    LaunchedEffect(zipPath) {
        loading = true
        errorMessage = null
        try {
            val (loadedParent, loadedEntries) = withContext(Dispatchers.IO) {
                val p = viewModel.container.fileSystem.parent(zipPath)
                val list = viewModel.container.zipArchiver.listEntries(zipPath)
                p to list
            }
            parentDir = loadedParent
            entries = loadedEntries
        } catch (e: EncryptedZipException) {
            errorMessage = "Password-protected ZIP archives are not supported in v1."
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to read archive"
        } finally {
            loading = false
        }
    }

    val unsafeCount = remember(entries) {
        entries.count { ZipArchiver.sanitizeEntrySegments(it.name) == null }
    }
    val totalUncompressed = remember(entries) {
        entries.sumOf { it.size.coerceAtLeast(0L) }
    }
    val defaultParent = parentDir
        ?: browser.currentDir
        ?: VfsPath.Local(File(Environment.getExternalStorageDirectory(), "Download").absolutePath)
    val defaultFolderStem = remember(zipPath) {
        PathUtils.splitBaseAndExtension(zipPath.displayName, false).first.ifEmpty { "extracted" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = zipPath.displayName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!loading && errorMessage == null) {
                            Text(
                                text = "${entries.size} entries · ${UiFormatters.formatSize(context, totalUncompressed)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (selectedNames.isNotEmpty()) {
                        TextButton(onClick = { selectedNames = emptySet() }) {
                            Text("Clear (${selectedNames.size})")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!loading && errorMessage == null && isZipArchive) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        if (selectedNames.isNotEmpty()) {
                            Button(
                                onClick = {
                                    viewModel.extractArchive(
                                        context = context,
                                        zipPath = zipPath,
                                        destinationDir = defaultParent,
                                        createSubfolderName = defaultFolderStem,
                                        selectedEntries = selectedNames,
                                    )
                                    onClose()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.Unarchive, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Extract selected (${selectedNames.size})")
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    viewModel.extractArchive(
                                        context = context,
                                        zipPath = zipPath,
                                        destinationDir = defaultParent,
                                        createSubfolderName = null,
                                    )
                                    onClose()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Extract here")
                            }
                            Button(
                                onClick = {
                                    viewModel.extractArchive(
                                        context = context,
                                        zipPath = zipPath,
                                        destinationDir = defaultParent,
                                        createSubfolderName = defaultFolderStem,
                                    )
                                    onClose()
                                },
                                modifier = Modifier.weight(1.3f),
                            ) {
                                Text("Extract to new folder")
                            }
                            TextButton(onClick = { showExtractDialog = true }) {
                                Text("More…")
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (unsafeCount > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "$unsafeCount unsafe path entr${if (unsafeCount == 1) "y" else "ies"} detected (zip-slip guard will skip automatically).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            when {
                loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                errorMessage != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(errorMessage!!, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(entries, key = { it.name }) { entry ->
                            val isSafe = ZipArchiver.sanitizeEntrySegments(entry.name) != null
                            val isSelected = entry.name in selectedNames

                            ListItem(
                                leadingContent = {
                                    if (selectedNames.isNotEmpty() && isSafe) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = {
                                                selectedNames = if (isSelected) {
                                                    selectedNames - entry.name
                                                } else {
                                                    selectedNames + entry.name
                                                }
                                            },
                                        )
                                    } else {
                                        Icon(
                                            imageVector = when {
                                                !isSafe -> Icons.Filled.Warning
                                                entry.isDirectory -> Icons.Filled.Folder
                                                else -> Icons.AutoMirrored.Filled.InsertDriveFile
                                            },
                                            contentDescription = null,
                                            tint = if (!isSafe) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            },
                                        )
                                    }
                                },
                                headlineContent = {
                                    Text(
                                        text = entry.name,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = {
                                    val sizeLabel = if (entry.isDirectory) {
                                        "Directory"
                                    } else {
                                        val rawSz = UiFormatters.formatSize(context, entry.size)
                                        val cmpSz = if (entry.compressedSize >= 0) {
                                            " (${UiFormatters.formatSize(context, entry.compressedSize)} compressed)"
                                        } else {
                                            ""
                                        }
                                        "$rawSz$cmpSz"
                                    }
                                    val warn = if (!isSafe) " · Unsafe path (skipped)" else ""
                                    Text("$sizeLabel · ${UiFormatters.formatShortDate(entry.lastModified)}$warn")
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (selectedNames.isNotEmpty() && isSafe && isZipArchive) {
                                                selectedNames = if (isSelected) selectedNames - entry.name else selectedNames + entry.name
                                            }
                                        },
                                        onLongClick = {
                                            if (isSafe && isZipArchive) {
                                                selectedNames = if (isSelected) selectedNames - entry.name else selectedNames + entry.name
                                            }
                                        },
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showExtractDialog) {
        ExtractZipDialog(
            zipPath = zipPath,
            defaultParentDir = defaultParent,
            favorites = favorites,
            onConfirm = { dst, sub, overwrite ->
                if (entries.size > 200_000) {
                    bombWarningPending = dst to sub
                } else {
                    viewModel.extractArchive(
                        context = context,
                        zipPath = zipPath,
                        destinationDir = dst,
                        createSubfolderName = sub,
                        overwrite = overwrite,
                    )
                    onClose()
                }
            },
            onDismiss = { showExtractDialog = false },
        )
    }

    bombWarningPending?.let { (dst, sub) ->
        AlertDialog(
            onDismissRequest = { bombWarningPending = null },
            title = { Text("Large archive warning") },
            text = { Text("This archive exceeds normal entry/expansion thresholds. Extract anyway?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.extractArchive(
                            context = context,
                            zipPath = zipPath,
                            destinationDir = dst,
                            createSubfolderName = sub,
                            allowBombOverride = true,
                        )
                        bombWarningPending = null
                        onClose()
                    },
                ) {
                    Text("Extract anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { bombWarningPending = null }) { Text("Cancel") }
            },
        )
    }
}
