package com.minifile.explorer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.data.update.UpdateUiState
import com.minifile.explorer.service.FtpServerHolder
import com.minifile.explorer.service.VolumeChangeReceiver
import com.minifile.explorer.ui.MainTab
import com.minifile.explorer.ui.MainViewModel
import com.minifile.explorer.ui.OverlayScreen
import com.minifile.explorer.ui.components.ActiveOperationsSheet
import com.minifile.explorer.ui.components.ConflictDialog
import com.minifile.explorer.ui.screens.ArchivePreviewScreen
import com.minifile.explorer.ui.screens.BrowserScreen
import com.minifile.explorer.ui.screens.FavoritesScreen
import com.minifile.explorer.ui.screens.InstalledAppsScreen
import com.minifile.explorer.ui.screens.TextEditorScreen
import com.minifile.explorer.ui.screens.ToolsScreen
import com.minifile.explorer.ui.screens.WirelessScreen
import com.minifile.explorer.ui.theme.MiniFileTheme
import com.minifile.explorer.ui.util.UiFormatters
import kotlinx.coroutines.delay

private data class SafMountRequest(
    val key: String,
    val expectedDocIdPrefix: String?,
    val label: String?,
    val navigateOnMount: Boolean,
)

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory((application as MiniFileApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleLaunchIntent(intent)

        setContent {
            val settings by viewModel.settings.collectAsState()
            val ftpState by FtpServerHolder.state.collectAsState()

            DisposableEffect(ftpState.isRunning) {
                if (ftpState.isRunning) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            MiniFileTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                MiniFileRoot(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val container = (application as MiniFileApplication).container
        container.updateManager.onHostResume(this)
        viewModel.refreshVolumes()
        if (viewModel.browser.value.currentDir != null) {
            viewModel.reloadCurrentDirectory(silent = true)
        }
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        val container = (application as MiniFileApplication).container

        if (BuildConfig.DEBUG) {
            val port = readOptionalIntExtra(intent, "ftp_port")
            val pasvMin = readOptionalIntExtra(intent, "pasv_min")
            val pasvMax = readOptionalIntExtra(intent, "pasv_max")
            if (port != null || pasvMin != null || pasvMax != null) {
                container.settingsRepository.setDebugFtpOverrides(
                    port = port,
                    pasvMin = pasvMin,
                    pasvMax = pasvMax,
                )
            }
        }

        intent.getStringExtra("open_tab")?.trim()?.takeIf { it.isNotEmpty() }?.let { rawTab ->
            MainTab.entries.firstOrNull {
                it.name.equals(rawTab, ignoreCase = true) ||
                    it.label.equals(rawTab, ignoreCase = true)
            }?.let { tab ->
                viewModel.selectTab(tab)
            }
        }
    }

    private fun readOptionalIntExtra(intent: Intent, key: String): Int? {
        if (!intent.hasExtra(key)) return null
        val extras = intent.extras ?: return null
        @Suppress("DEPRECATION")
        val raw = extras.get(key)
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
    }
}

@Composable
private fun MiniFileRoot(
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val container = viewModel.container

    val activeTab by viewModel.activeTab.collectAsState()
    val overlay by viewModel.overlay.collectAsState()
    val browser by viewModel.browser.collectAsState()
    val conflictPrompt by viewModel.conflictPrompt.collectAsState()
    val activeOps by container.operationRegistry.active.collectAsState()
    val ftpState by FtpServerHolder.state.collectAsState()
    val updateState by container.updateManager.state.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingSafMount by remember { mutableStateOf<SafMountRequest?>(null) }
    var unknownSourcesIntent by remember { mutableStateOf<Intent?>(null) }

    var showActiveOpsSheet by remember { mutableStateOf(false) }
    var dismissedOpIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val canHandleBack = overlay != null ||
        activeTab != MainTab.BROWSE ||
        browser.isSelectionMode ||
        browser.searchActive ||
        browser.currentDir != null

    BackHandler(enabled = canHandleBack) {
        viewModel.handleBack()
    }

    DisposableEffect(context) {
        val unregister = VolumeChangeReceiver.register(context) {
            viewModel.refreshVolumes()
        }
        onDispose { unregister() }
    }

    LaunchedEffect(Unit) {
        container.updateManager.checkOnStartupIfDue()
    }

    // Auto-present ActiveOperationsSheet if an operation runs > 500 ms (DESIGN.md §2.5)
    val primaryOpId = activeOps.firstOrNull()?.id
    LaunchedEffect(primaryOpId, activeOps.isEmpty()) {
        if (activeOps.isEmpty()) {
            showActiveOpsSheet = false
            dismissedOpIds = emptySet()
        } else if (primaryOpId != null && primaryOpId !in dismissedOpIds) {
            delay(500L)
            if (container.operationRegistry.active.value.any { it.id == primaryOpId } &&
                primaryOpId !in dismissedOpIds
            ) {
                showActiveOpsSheet = true
            }
        }
    }

    // Operation completion & general UI snackbars
    LaunchedEffect(Unit) {
        viewModel.snackbarMessages.collect { event ->
            val dest = event.destinationToOpen
            val canRetry = event.error != null && container.operationRegistry.canRetry(event.id)
            val actionLabel = when {
                dest != null -> event.openLabel
                canRetry -> "Retry"
                else -> null
            }
            val result = snackbarHostState.showSnackbar(
                message = event.snackbarMessage,
                actionLabel = actionLabel,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                if (dest != null) {
                    if (event.kind == OperationKind.COMPRESS &&
                        PathUtils.extensionOf(dest.displayName) == "zip"
                    ) {
                        viewModel.openOverlay(OverlayScreen.ArchivePreview(dest))
                    } else {
                        viewModel.closeOverlay()
                        viewModel.selectTab(MainTab.BROWSE)
                        viewModel.navigateToDirectory(dest)
                    }
                } else if (canRetry) {
                    container.operationRegistry.retry(event.id)
                }
            }
        }
    }

    // Silent startup update notice (DESIGN.md §7.1 / ARCHITECTURE.md §10.2)
    LaunchedEffect(Unit) {
        container.updateManager.silentAvailablePrompt.collect { release ->
            val result = snackbarHostState.showSnackbar(
                message = "MiniFile ${release.tagName} is available",
                actionLabel = "View",
                duration = SnackbarDuration.Long,
            )
            when (result) {
                SnackbarResult.ActionPerformed -> {
                    viewModel.closeOverlay()
                    viewModel.selectTab(MainTab.TOOLS)
                }

                SnackbarResult.Dismissed -> {
                    container.updateManager.dismissSilentNotice(release.tagName)
                }
            }
        }
    }

    // SAF OpenDocumentTree result handler
    val safTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        val req = pendingSafMount
        pendingSafMount = null
        val uri = res.data?.data
        if (res.resultCode == Activity.RESULT_OK && uri != null && req != null) {
            val pickedDocId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            val expectedPrefix = req.expectedDocIdPrefix
            val mismatchedPick = expectedPrefix != null &&
                pickedDocId != null &&
                !pickedDocId.equals(expectedPrefix, ignoreCase = false) &&
                !pickedDocId.startsWith(expectedPrefix)

            val grant = if (mismatchedPick) {
                container.persistedTreeStore.recordGenericMountedFolder(uri)
            } else {
                container.persistedTreeStore.recordTreeGrant(req.key, uri, req.label)
            }
            viewModel.refreshVolumes()
            if (req.navigateOnMount) {
                val vfsRoot = grant.toVfsPath()
                viewModel.closeOverlay()
                viewModel.selectTab(MainTab.BROWSE)
                viewModel.navigateToDirectory(
                    dir = vfsRoot,
                    rootVolumeName = grant.label,
                    rootVolumeBase = vfsRoot,
                )
            }
        }
    }

    val allFilesSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshVolumes()
        viewModel.reloadCurrentDirectory(silent = false)
    }

    val legacyPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refreshVolumes()
        viewModel.reloadCurrentDirectory(silent = false)
    }

    val requestAllFilesAccess: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                allFilesSettingsLauncher.launch(UiFormatters.buildAllFilesAccessIntent(context))
            }.onFailure {
                runCatching {
                    allFilesSettingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else {
            legacyPermissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
            )
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (overlay == null) {
                Column {
                    if (activeOps.isNotEmpty() && !showActiveOpsSheet) {
                        ActiveOperationPill(
                            title = activeOps.first().title,
                            count = activeOps.size,
                            onClick = { showActiveOpsSheet = true },
                        )
                    }
                    NavigationBar {
                        val hasUpdateDot = updateState is UpdateUiState.Available ||
                            updateState is UpdateUiState.Ready

                        NavigationBarItem(
                            selected = activeTab == MainTab.BROWSE,
                            onClick = { viewModel.selectTab(MainTab.BROWSE) },
                            icon = { Icon(Icons.Filled.Folder, contentDescription = "Browse") },
                            label = { Text("Browse") },
                        )
                        NavigationBarItem(
                            selected = activeTab == MainTab.FAVORITES,
                            onClick = { viewModel.selectTab(MainTab.FAVORITES) },
                            icon = { Icon(Icons.Filled.Star, contentDescription = "Favorites") },
                            label = { Text("Favorites") },
                        )
                        NavigationBarItem(
                            selected = activeTab == MainTab.WIRELESS,
                            onClick = { viewModel.selectTab(MainTab.WIRELESS) },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (ftpState.isRunning) {
                                            Badge()
                                        }
                                    },
                                ) {
                                    Icon(Icons.Filled.Wifi, contentDescription = "Wireless")
                                }
                            },
                            label = { Text("Wireless") },
                        )
                        NavigationBarItem(
                            selected = activeTab == MainTab.TOOLS,
                            onClick = { viewModel.selectTab(MainTab.TOOLS) },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (hasUpdateDot) {
                                            Badge()
                                        }
                                    },
                                ) {
                                    Icon(Icons.Filled.Build, contentDescription = "Tools")
                                }
                            },
                            label = { Text("Tools") },
                        )
                    }
                }
            }
        },
    ) { outerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(outerPadding),
        ) {
            when (val currentOverlay = overlay) {
                is OverlayScreen.TextEditor -> {
                    TextEditorScreen(
                        initialPath = currentOverlay.path,
                        forceEditInitial = currentOverlay.forceEdit,
                        viewModel = viewModel,
                        onClose = { viewModel.closeOverlay() },
                    )
                }

                is OverlayScreen.ArchivePreview -> {
                    ArchivePreviewScreen(
                        zipPath = currentOverlay.path,
                        viewModel = viewModel,
                        onClose = { viewModel.closeOverlay() },
                    )
                }

                OverlayScreen.InstalledApps -> {
                    InstalledAppsScreen(
                        viewModel = viewModel,
                        onClose = { viewModel.closeOverlay() },
                    )
                }

                null -> {
                    when (activeTab) {
                        MainTab.BROWSE -> {
                            BrowserScreen(
                                viewModel = viewModel,
                                onGrantAllFilesAccess = requestAllFilesAccess,
                                onMountVolumeSaf = { volume ->
                                    pendingSafMount = SafMountRequest(
                                        key = volume.id,
                                        expectedDocIdPrefix = volume.safInitialDocId,
                                        label = volume.name,
                                        navigateOnMount = true,
                                    )
                                    runCatching {
                                        safTreeLauncher.launch(
                                            container.volumeProvider.buildOpenTreeIntent(volume = volume),
                                        )
                                    }.onFailure { e ->
                                        pendingSafMount = null
                                        viewModel.emitSimpleMessage("Could not open system folder picker: ${e.message}")
                                    }
                                },
                                onMountPackageSaf = { safKey, initialDocId, label ->
                                    pendingSafMount = SafMountRequest(
                                        key = safKey,
                                        expectedDocIdPrefix = initialDocId,
                                        label = label,
                                        navigateOnMount = true,
                                    )
                                    runCatching {
                                        safTreeLauncher.launch(
                                            container.volumeProvider.buildOpenTreeIntent(initialDocId = initialDocId),
                                        )
                                    }.onFailure { e ->
                                        pendingSafMount = null
                                        viewModel.emitSimpleMessage("Could not open system folder picker: ${e.message}")
                                    }
                                },
                            )
                        }

                        MainTab.FAVORITES -> {
                            FavoritesScreen(viewModel = viewModel)
                        }

                        MainTab.WIRELESS -> {
                            WirelessScreen(viewModel = viewModel)
                        }

                        MainTab.TOOLS -> {
                            ToolsScreen(
                                viewModel = viewModel,
                                onGrantAllFilesAccess = requestAllFilesAccess,
                                onRequestUnknownSourcesPermission = { intent ->
                                    unknownSourcesIntent = intent
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (conflictPrompt != null) {
        ConflictDialog(
            prompt = conflictPrompt!!,
            onResolve = { resolution, applyAll ->
                viewModel.answerConflict(resolution, applyAll)
            },
        )
    }

    if (showActiveOpsSheet && activeOps.isNotEmpty()) {
        ActiveOperationsSheet(
            operations = activeOps,
            onCancel = { opId -> container.operationRegistry.cancel(opId) },
            onDismiss = {
                dismissedOpIds = dismissedOpIds + activeOps.map { it.id }
                showActiveOpsSheet = false
            },
        )
    }

    if (unknownSourcesIntent != null) {
        val targetIntent = unknownSourcesIntent!!
        AlertDialog(
            onDismissRequest = { unknownSourcesIntent = null },
            title = { Text("Allow installing updates") },
            text = {
                Text(
                    "To install verified MiniFile updates directly, allow 'Install unknown apps' for MiniFile in Android Settings, then return here.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        unknownSourcesIntent = null
                        runCatching { context.startActivity(targetIntent) }
                    },
                ) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { unknownSourcesIntent = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ActiveOperationPill(
    title: String,
    count: Int,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (count > 1) "$title (+${count - 1} more)" else title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Details",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
