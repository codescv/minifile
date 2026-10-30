package com.minifile.explorer.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.minifile.explorer.BuildConfig
import com.minifile.explorer.core.model.SortField
import com.minifile.explorer.core.model.ThemeMode
import com.minifile.explorer.core.model.ViewMode
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.data.AndroidVolumeProvider
import com.minifile.explorer.data.update.InstallLaunchResult
import com.minifile.explorer.data.update.UpdateUiState
import com.minifile.explorer.service.FtpServerHolder
import com.minifile.explorer.ui.MainTab
import com.minifile.explorer.ui.MainViewModel
import com.minifile.explorer.ui.OverlayScreen
import com.minifile.explorer.ui.util.UiFormatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    viewModel: MainViewModel,
    onGrantAllFilesAccess: () -> Unit,
    onRequestUnknownSourcesPermission: (Intent) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val settings by viewModel.settings.collectAsState()
    val grants by viewModel.container.persistedTreeStore.grants.collectAsState()
    val volumes by viewModel.volumes.collectAsState()
    val updateState by viewModel.container.updateManager.state.collectAsState()
    val opHistory by viewModel.container.operationRegistry.history.collectAsState()

    var showNomediaScanner by remember { mutableStateOf(false) }
    var showTasksHistory by remember { mutableStateOf(false) }
    var showVolumesDiag by remember { mutableStateOf(false) }

    val hasAllFiles = AndroidVolumeProvider.hasAllFilesAccess(context)

    fun triggerInstall() {
        when (val res = viewModel.container.updateManager.launchInstaller(context)) {
            InstallLaunchResult.Launched -> Unit
            is InstallLaunchResult.NeedsUnknownSourcesPermission -> onRequestUnknownSourcesPermission(res.settingsIntent)
            is InstallLaunchResult.Failed -> viewModel.emitSimpleMessage(res.message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tools", fontWeight = FontWeight.SemiBold) },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 4.dp,
                bottom = innerPadding.calculateBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // 1. Updates Card (DESIGN.md §11.1 & E14)
            item(key = "updates-card") {
                SectionLabel("Updates")
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.SystemUpdate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Updates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Current version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Text(
                            text = "MiniFile checks GitHub Releases for new versions at most every 6 hours. You can turn this off below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        when (val st = updateState) {
                            UpdateUiState.Idle -> {
                                if (settings.updateLastCheckMs > 0L) {
                                    Text(
                                        "Last checked: ${UiFormatters.formatShortDate(settings.updateLastCheckMs)}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }

                            UpdateUiState.Checking -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text("Checking GitHub Releases…", style = MaterialTheme.typography.bodyMedium)
                                }
                            }

                            is UpdateUiState.UpToDate -> {
                                Text(
                                    "Up to date — you're on the latest version (${UiFormatters.formatShortDate(st.checkedAtMs)}).",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                            }

                            is UpdateUiState.Available -> {
                                HorizontalDivider()
                                Text(
                                    "Update available: ${st.release.tagName} (${UiFormatters.formatSize(context, st.asset.size)})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                val notes = st.release.body?.trim().orEmpty()
                                if (notes.isNotEmpty()) {
                                    Text("What's new", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                    Text(notes.take(800), style = MaterialTheme.typography.bodySmall)
                                }
                            }

                            is UpdateUiState.Downloading -> {
                                val frac = if (st.totalBytes > 0L) {
                                    (st.downloadedBytes.toFloat() / st.totalBytes.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    null
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "Downloading ${st.release.tagName}… ${UiFormatters.formatSize(context, st.downloadedBytes)} / ${UiFormatters.formatSize(context, st.totalBytes)}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        if (frac != null) {
                                            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                                        } else {
                                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                        }
                                    }
                                    IconButton(onClick = { viewModel.container.updateManager.cancel() }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Cancel download")
                                    }
                                }
                            }

                            is UpdateUiState.Verifying -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text("Verifying APK package & signature…", style = MaterialTheme.typography.bodyMedium)
                                }
                            }

                            is UpdateUiState.Ready -> {
                                Text(
                                    "Verified ${st.release.tagName} is ready to install.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }

                            is UpdateUiState.Error -> {
                                Text(
                                    "Last check failed: ${st.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.container.updateManager.check(force = true) },
                                enabled = updateState !is UpdateUiState.Checking && updateState !is UpdateUiState.Downloading,
                            ) {
                                Text("Check now")
                            }

                            when (updateState) {
                                is UpdateUiState.Available -> {
                                    Button(
                                        onClick = {
                                            viewModel.container.updateManager.download(
                                                onReadyToInstall = { triggerInstall() },
                                            )
                                        },
                                    ) {
                                        Text("Update & Install")
                                    }
                                }

                                is UpdateUiState.Ready -> {
                                    Button(onClick = { triggerInstall() }) {
                                        Text("Install")
                                    }
                                }

                                else -> Unit
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Check automatically (every 6 h)", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = settings.updateAutoCheck,
                                onCheckedChange = { v ->
                                    viewModel.container.settingsRepository.update { it.copy(updateAutoCheck = v) }
                                },
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Include pre-releases", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = settings.updateIncludePre,
                                onCheckedChange = { v ->
                                    viewModel.container.settingsRepository.update { it.copy(updateIncludePre = v) }
                                },
                            )
                        }

                        TextButton(
                            onClick = {
                                val open = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/codescv/minifile/releases"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(open) }
                            },
                        ) {
                            Text("Release page")
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // 2. Appearance
            item(key = "appearance") {
                SectionLabel("Appearance")
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Theme", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (tm in ThemeMode.entries) {
                                FilterChip(
                                    selected = settings.themeMode == tm,
                                    onClick = {
                                        viewModel.container.settingsRepository.update { it.copy(themeMode = tm) }
                                    },
                                    label = { Text(tm.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                )
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            ToggleSettingRow(
                                label = "Dynamic color (Material You)",
                                checked = settings.dynamicColor,
                                onCheckedChange = { v ->
                                    viewModel.container.settingsRepository.update { it.copy(dynamicColor = v) }
                                },
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = settings.viewMode == ViewMode.LIST,
                                onClick = {
                                    viewModel.container.settingsRepository.update { it.copy(viewMode = ViewMode.LIST) }
                                },
                                label = { Text("Default: List") },
                            )
                            FilterChip(
                                selected = settings.viewMode == ViewMode.GRID,
                                onClick = {
                                    viewModel.container.settingsRepository.update { it.copy(viewMode = ViewMode.GRID) }
                                },
                                label = { Text("Default: Grid") },
                            )
                        }

                        Text("Default sort", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (sf in SortField.entries) {
                                FilterChip(
                                    selected = settings.sort.by == sf,
                                    onClick = {
                                        viewModel.updateSort(settings.sort.copy(by = sf))
                                    },
                                    label = { Text(sf.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                )
                            }
                        }

                        ToggleSettingRow(
                            label = "Show hidden files (.dotfiles)",
                            checked = settings.showHidden,
                            onCheckedChange = { v ->
                                viewModel.container.settingsRepository.update { it.copy(showHidden = v) }
                            },
                        )
                        ToggleSettingRow(
                            label = "Show image/video thumbnails",
                            checked = settings.showThumbnails,
                            onCheckedChange = { v ->
                                viewModel.container.settingsRepository.update { it.copy(showThumbnails = v) }
                            },
                        )
                        ToggleSettingRow(
                            label = "Folders first",
                            checked = settings.sort.foldersFirst,
                            onCheckedChange = { v ->
                                viewModel.updateSort(settings.sort.copy(foldersFirst = v))
                            },
                        )
                    }
                }
            }

            // 3. Behavior
            item(key = "behavior") {
                SectionLabel("Behavior")
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ToggleSettingRow(
                            label = "Confirm before overwriting files",
                            checked = settings.confirmOverwrite,
                            onCheckedChange = { v ->
                                viewModel.container.settingsRepository.update { it.copy(confirmOverwrite = v) }
                            },
                        )
                        ToggleSettingRow(
                            label = "Open text files in built-in editor",
                            checked = settings.openTextInBuiltInEditor,
                            onCheckedChange = { v ->
                                viewModel.container.settingsRepository.update { it.copy(openTextInBuiltInEditor = v) }
                            },
                        )
                        ToggleSettingRow(
                            label = "Remember last opened folder",
                            checked = settings.rememberLastFolder,
                            onCheckedChange = { v ->
                                viewModel.container.settingsRepository.update { it.copy(rememberLastFolder = v) }
                            },
                        )
                        ToggleSettingRow(
                            label = "Sort 'Copy/Move to Favorite' sheet by recency",
                            checked = settings.favoritePickerSortByRecency,
                            onCheckedChange = { v ->
                                viewModel.container.settingsRepository.update { it.copy(favoritePickerSortByRecency = v) }
                            },
                        )
                    }
                }
            }

            // 4. Storage Access
            item(key = "storage-access") {
                SectionLabel("Storage access")
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("All-files access") },
                            supportingContent = {
                                Text(if (hasAllFiles) "Granted" else "Not granted — SAF mounted folders active")
                            },
                            trailingContent = {
                                OutlinedButton(onClick = onGrantAllFilesAccess) {
                                    Text(if (hasAllFiles) "Manage" else "Grant")
                                }
                            },
                        )
                        HorizontalDivider()
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("Installed Apps Inspector") },
                            supportingContent = { Text("Browse Android/data per app & backup APKs") },
                            modifier = Modifier.clickable {
                                viewModel.openOverlay(OverlayScreen.InstalledApps)
                            },
                        )
                        HorizontalDivider()
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("Volumes diagnostics (${volumes.size})") },
                            supportingContent = { Text("View mount paths, states, and capacities") },
                            modifier = Modifier.clickable { showVolumesDiag = true },
                        )
                        if (grants.isNotEmpty()) {
                            HorizontalDivider()
                            for ((key, grant) in grants) {
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    headlineContent = { Text("SAF: ${grant.label}") },
                                    supportingContent = { Text(grant.rootDocumentId, maxLines = 1) },
                                    trailingContent = {
                                        IconButton(
                                            onClick = {
                                                viewModel.container.persistedTreeStore.revoke(key)
                                                viewModel.refreshVolumes()
                                            },
                                        ) {
                                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Revoke ${grant.label}")
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // 5. Utilities
            item(key = "utilities") {
                SectionLabel("Utilities")
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(".nomedia Overview") },
                            supportingContent = { Text("Scan folders hidden from gallery and toggle in bulk") },
                            modifier = Modifier.clickable { showNomediaScanner = true },
                        )
                        HorizontalDivider()
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("Operation tasks (${opHistory.size})") },
                            supportingContent = { Text("Recent copy, move, delete, and ZIP tasks") },
                            modifier = Modifier.clickable { showTasksHistory = true },
                        )
                        HorizontalDivider()
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("Favorites backup / restore") },
                            supportingContent = { Text("Open Favorites tab to export or import JSON") },
                            modifier = Modifier.clickable { viewModel.selectTab(MainTab.FAVORITES) },
                        )
                        HorizontalDivider()
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("Export FTP log") },
                            supportingContent = { Text("Copy or save current FTP server activity log") },
                            modifier = Modifier.clickable {
                                val text = FtpServerHolder.exportLogText()
                                if (text.isBlank()) {
                                    viewModel.emitSimpleMessage("FTP log is currently empty")
                                } else {
                                    UiFormatters.copyToSystemClipboard(context, "FTP Log", text)
                                    viewModel.emitSimpleMessage("FTP log copied to clipboard")
                                }
                            },
                        )
                    }
                }
            }

            // 6. About
            item(key = "about") {
                SectionLabel("About")
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Text("MiniFile ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Minimalist, offline-first file manager with built-in RFC 959/3659 FTP server and PC Companion.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Privacy: No ads, no analytics, and no network access except the LAN FTP server you start and the optional GitHub Releases update check.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Open-source libraries: AndroidX, Jetpack Compose Material 3, Kotlin Coroutines & Serialization (Apache 2.0).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = {
                                val open = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/codescv/minifile"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(open) }
                            },
                        ) {
                            Text("github.com/codescv/minifile")
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }

    if (showNomediaScanner) {
        var scanning by remember { mutableStateOf(true) }
        var foundDirs by remember { mutableStateOf<List<VfsPath>>(emptyList()) }

        androidx.compose.runtime.LaunchedEffect(Unit) {
            scanning = true
            val root = VfsPath.Local(Environment.getExternalStorageDirectory().absolutePath)
            foundDirs = withContext(Dispatchers.IO) {
                runCatching { viewModel.container.nomediaManager.scan(root, maxDepth = 5) }.getOrDefault(emptyList())
            }
            scanning = false
        }

        AlertDialog(
            onDismissRequest = { showNomediaScanner = false },
            title = { Text(".nomedia Overview") },
            text = {
                if (scanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Scanning storage for .nomedia folders…")
                    }
                } else if (foundDirs.isEmpty()) {
                    Text("No folders with .nomedia found.")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(foundDirs, key = { it.serialize() }) { dirPath ->
                            ListItem(
                                headlineContent = { Text(dirPath.displayName) },
                                supportingContent = {
                                    Text((dirPath as? VfsPath.Local)?.normalized ?: dirPath.serialize(), maxLines = 1)
                                },
                                trailingContent = {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    viewModel.container.nomediaManager.set(dirPath, false)
                                                }
                                                foundDirs = foundDirs.filterNot { it == dirPath }
                                            }
                                        },
                                    ) {
                                        Text("Unhide")
                                    }
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showNomediaScanner = false }) { Text("Close") }
            },
        )
    }

    if (showTasksHistory) {
        AlertDialog(
            onDismissRequest = { showTasksHistory = false },
            title = { Text("Recent Tasks") },
            text = {
                if (opHistory.isEmpty()) {
                    Text("No completed operations in this session yet.")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(opHistory, key = { it.id }) { op ->
                            ListItem(
                                headlineContent = { Text(op.title) },
                                supportingContent = {
                                    val status = when {
                                        op.cancelled -> "Cancelled"
                                        op.errorSummary != null -> "Failed: ${op.errorSummary}"
                                        op.unsafeSkipped > 0 -> "Completed (${op.unsafeSkipped} unsafe skipped)"
                                        else -> "Completed"
                                    }
                                    Text(status)
                                },
                                trailingContent = {
                                    if (viewModel.container.operationRegistry.canRetry(op.id)) {
                                        IconButton(
                                            onClick = {
                                                viewModel.container.operationRegistry.retry(op.id)
                                                showTasksHistory = false
                                            },
                                        ) {
                                            Icon(Icons.Filled.Refresh, contentDescription = "Retry")
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTasksHistory = false }) { Text("Close") }
            },
            dismissButton = {
                if (opHistory.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            viewModel.container.operationRegistry.clearHistory()
                            showTasksHistory = false
                        },
                    ) {
                        Text("Clear")
                    }
                }
            },
        )
    }

    if (showVolumesDiag) {
        AlertDialog(
            onDismissRequest = { showVolumesDiag = false },
            title = { Text("Volumes Diagnostics") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(volumes, key = { it.id }) { v ->
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text("${v.name} (${v.kind})", fontWeight = FontWeight.SemiBold)
                            Text("Root: ${v.root?.serialize() ?: "unmounted"}", style = MaterialTheme.typography.bodySmall)
                            if (v.totalBytes > 0L) {
                                Text(
                                    "Space: ${UiFormatters.formatSize(context, v.freeBytes)} free / ${UiFormatters.formatSize(context, v.totalBytes)} total",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVolumesDiag = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun ToggleSettingRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
