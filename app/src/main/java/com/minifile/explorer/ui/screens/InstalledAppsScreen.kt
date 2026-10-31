package com.minifile.explorer.ui.screens

import android.os.Environment
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.minifile.explorer.core.model.OperationKind
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.data.InstalledAppInfo
import com.minifile.explorer.service.OperationRunOutcome
import com.minifile.explorer.ui.MainTab
import com.minifile.explorer.ui.MainViewModel
import com.minifile.explorer.ui.util.UiFormatters
import java.io.File

private enum class AppFilter { USER, SYSTEM, ALL }
private enum class AppSort { NAME, SIZE, UPDATED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstalledAppsScreen(
    viewModel: MainViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val favorites by viewModel.favorites.collectAsState()
    val browser by viewModel.browser.collectAsState()

    var allApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(AppFilter.USER) }
    var sort by remember { mutableStateOf(AppSort.NAME) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    var selectedApp by remember { mutableStateOf<InstalledAppInfo?>(null) }
    var exportAppTarget by remember { mutableStateOf<InstalledAppInfo?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        allApps = viewModel.container.installedAppsRepository.loadInstalledApps()
        loading = false
    }

    val visibleApps = remember(allApps, query, filter, sort) {
        val q = query.trim()
        allApps.asSequence()
            .filter {
                when (filter) {
                    AppFilter.USER -> !it.isSystemApp
                    AppFilter.SYSTEM -> it.isSystemApp
                    AppFilter.ALL -> true
                }
            }
            .filter {
                q.isEmpty() ||
                    it.label.contains(q, ignoreCase = true) ||
                    it.packageName.contains(q, ignoreCase = true)
            }
            .sortedWith(
                when (sort) {
                    AppSort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }
                    AppSort.SIZE -> compareByDescending<InstalledAppInfo> { it.apkSizeBytes }
                    AppSort.UPDATED -> compareByDescending<InstalledAppInfo> { it.lastUpdateTime }
                },
            )
            .toList()
    }

    Scaffold(
        topBar = {
            if (searchOpen) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                searchOpen = false
                                query = ""
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                        }
                    },
                    title = {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search apps or packages…") },
                            singleLine = true,
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Clear")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = { Text("Installed Apps", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        IconButton(onClick = { searchOpen = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search apps")
                        }
                        Box {
                            IconButton(onClick = { sortMenuOpen = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort apps")
                            }
                            DropdownMenu(
                                expanded = sortMenuOpen,
                                onDismissRequest = { sortMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Sort by Name") },
                                    onClick = {
                                        sort = AppSort.NAME
                                        sortMenuOpen = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Sort by APK Size") },
                                    onClick = {
                                        sort = AppSort.SIZE
                                        sortMenuOpen = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Sort by Last Updated") },
                                    onClick = {
                                        sort = AppSort.UPDATED
                                        sortMenuOpen = false
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                FilterChip(
                    selected = filter == AppFilter.USER,
                    onClick = { filter = AppFilter.USER },
                    label = { Text("User") },
                )
                FilterChip(
                    selected = filter == AppFilter.SYSTEM,
                    onClick = { filter = AppFilter.SYSTEM },
                    label = { Text("System") },
                )
                FilterChip(
                    selected = filter == AppFilter.ALL,
                    onClick = { filter = AppFilter.ALL },
                    label = { Text("All (${allApps.size})") },
                )
            }

            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(visibleApps, key = { it.packageName }) { app ->
                        ListItem(
                            leadingContent = {
                                Icon(
                                    Icons.Filled.Android,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            headlineContent = {
                                Text(app.label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                val tag = if (app.isSystemApp) "System" else "User"
                                Text(
                                    "${app.packageName} · v${app.versionName} · $tag",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                Text(
                                    UiFormatters.formatSize(context, app.apkSizeBytes),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedApp = app },
                        )
                    }
                }
            }
        }
    }

    selectedApp?.let { app ->
        var externalSize by remember(app.packageName) { mutableStateOf<Long?>(null) }
        LaunchedEffect(app.packageName) {
            externalSize = viewModel.container.installedAppsRepository.computeExternalDirSize(app.externalDataPath)
        }

        ModalBottomSheet(onDismissRequest = { selectedApp = null }) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(app.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Text(
                    "Version: ${app.versionName} (${app.versionCode}) · minSdk ${app.minSdk} · targetSdk ${app.targetSdk}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "APK size: ${UiFormatters.formatExactSize(context, app.apkSizeBytes)}${if (app.hasSplits) " (${app.splitSourceDirs.size + 1} splits)" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Updated: ${UiFormatters.formatFullDate(app.lastUpdateTime)}", style = MaterialTheme.typography.bodySmall)
                Text("Source APK: ${app.sourceDir}", style = MaterialTheme.typography.bodySmall)
                Text("Internal data (protected): ${app.internalDataDir}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "External data: ${app.externalDataPath}" + (externalSize?.let { " (${UiFormatters.formatSize(context, it)})" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )

                HorizontalDivider()

                Button(
                    onClick = {
                        selectedApp = null
                        onClose()
                        val primaryBase = VfsPath.Local(Environment.getExternalStorageDirectory().absolutePath)
                        viewModel.selectTab(MainTab.BROWSE)
                        viewModel.navigateToDirectory(
                            dir = VfsPath.Local(app.externalDataPath),
                            rootVolumeName = "Internal",
                            rootVolumeBase = primaryBase,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open external data")
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = {
                            selectedApp = null
                            onClose()
                            val primaryBase = VfsPath.Local(Environment.getExternalStorageDirectory().absolutePath)
                            viewModel.selectTab(MainTab.BROWSE)
                            viewModel.navigateToDirectory(
                                dir = VfsPath.Local(app.obbPath),
                                rootVolumeName = "Internal",
                                rootVolumeBase = primaryBase,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Open obb")
                    }

                    OutlinedButton(
                        onClick = {
                            val mediaDir = File(app.mediaPath)
                            if (mediaDir.exists() && mediaDir.canRead()) {
                                selectedApp = null
                                onClose()
                                val primaryBase = VfsPath.Local(Environment.getExternalStorageDirectory().absolutePath)
                                viewModel.selectTab(MainTab.BROWSE)
                                viewModel.navigateToDirectory(
                                    dir = VfsPath.Local(mediaDir.absolutePath),
                                    rootVolumeName = "Internal",
                                    rootVolumeBase = primaryBase,
                                )
                            } else {
                                viewModel.emitSimpleMessage("No shared Android/media folder for ${app.label}")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Open media")
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = {
                            exportAppTarget = app
                            selectedApp = null
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Export APK")
                    }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(UiFormatters.buildAppDetailsIntent(app.packageName))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("App info")
                    }
                }
                Spacer(Modifier.width(1.dp))
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    exportAppTarget?.let { app ->
        val defaultDest = browser.currentDir
            ?: VfsPath.Local(File(Environment.getExternalStorageDirectory(), "Download").absolutePath)
        var chosenDest by remember(app.packageName) { mutableStateOf(defaultDest) }
        var chosenLabel by remember(app.packageName) { mutableStateOf(defaultDest.displayName) }

        AlertDialog(
            onDismissRequest = { exportAppTarget = null },
            title = { Text("Export APK — ${app.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Destination: $chosenLabel", style = MaterialTheme.typography.bodyMedium)
                    val curBrowse = browser.currentDir
                    if (curBrowse != null) {
                        FilterChip(
                            selected = chosenDest == curBrowse,
                            onClick = {
                                chosenDest = curBrowse
                                chosenLabel = curBrowse.displayName
                            },
                            label = { Text("Current Browse folder (${curBrowse.displayName})") },
                        )
                    }
                    for (fav in favorites) {
                        FilterChip(
                            selected = chosenDest == fav.path,
                            onClick = {
                                chosenDest = fav.path
                                chosenLabel = fav.name
                            },
                            label = { Text("★ ${fav.name}") },
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targetDir = chosenDest
                        val targetName = chosenLabel
                        exportAppTarget = null
                        viewModel.container.operationRegistry.enqueue(
                            context = context,
                            kind = OperationKind.EXPORT_APK,
                            title = "Exporting ${app.label}",
                        ) { _, sink ->
                            val exported = viewModel.container.installedAppsRepository.exportApk(app, targetDir, sink)
                            OperationRunOutcome(
                                message = "Exported ${exported.displayName} to $targetName",
                                destinationToOpen = targetDir,
                                openLabel = "Open",
                            )
                        }
                    },
                ) {
                    Text("Export")
                }
            },
            dismissButton = {
                TextButton(onClick = { exportAppTarget = null }) { Text("Cancel") }
            },
        )
    }
}
