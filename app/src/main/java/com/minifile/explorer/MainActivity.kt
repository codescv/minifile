package com.minifile.explorer

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minifile.explorer.core.model.GDriveServerBookmark
import com.minifile.explorer.core.model.OperationKind
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.data.gdrive.AndroidAccountTokenProvider
import com.minifile.explorer.data.gdrive.GDriveSignInState
import com.minifile.explorer.data.update.InstallLaunchResult
import com.minifile.explorer.data.update.UpdateUiState
import com.minifile.explorer.service.FtpServerHolder
import com.minifile.explorer.service.HttpServerHolder
import com.minifile.explorer.service.VolumeChangeReceiver
import com.minifile.explorer.ui.MainTab
import com.minifile.explorer.ui.MainTabPagerCommand
import com.minifile.explorer.ui.MainTabPagerSync
import com.minifile.explorer.ui.MainViewModel
import com.minifile.explorer.ui.OverlayScreen
import com.minifile.explorer.ui.components.ActiveOperationsSheet
import com.minifile.explorer.ui.components.ConflictDialog
import com.minifile.explorer.ui.components.HotspotLinkDialog
import com.minifile.explorer.ui.components.PeerConsentDialog
import com.minifile.explorer.ui.screens.ArchivePreviewScreen
import com.minifile.explorer.ui.screens.BrowserScreen
import com.minifile.explorer.ui.screens.EditGDriveServerDialog
import com.minifile.explorer.ui.screens.FavoritesScreen
import com.minifile.explorer.ui.screens.PdfViewerScreen
import com.minifile.explorer.ui.screens.TextEditorScreen
import com.minifile.explorer.ui.screens.ToolsScreen
import com.minifile.explorer.ui.screens.WirelessScreen
import com.minifile.explorer.ui.theme.MiniFileTheme
import com.minifile.explorer.ui.util.NearbyPermissions
import com.minifile.explorer.ui.util.UiFormatters
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

private data class SafMountRequest(
    val key: String,
    val expectedDocIdPrefix: String?,
    val label: String?,
    val navigateOnMount: Boolean,
)

private data class GoogleAccountPickRequest(
    val displayName: String,
    val initialPath: String,
    val existingBookmark: GDriveServerBookmark?,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

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
            val httpState by HttpServerHolder.state.collectAsState()
            val anyTransferRunning = ftpState.isRunning || httpState.isRunning

            DisposableEffect(anyTransferRunning) {
                if (anyTransferRunning) {
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

    override fun onStart() {
        super.onStart()
        // Foreground-wide Bluetooth presence: discoverable (and consent dialog reachable) from any tab.
        // Only activates when BLE permissions are already granted and Bluetooth is on; never prompts.
        (application as MiniFileApplication).container.blePeerCoordinator.acquirePresence()
    }

    override fun onStop() {
        (application as MiniFileApplication).container.blePeerCoordinator.releasePresence()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val container = (application as MiniFileApplication).container
        // Returning from App info / Bluetooth settings: pick up newly granted permissions immediately.
        container.blePeerCoordinator.restartRadios()
        // v1.0.16: auto-install the verified update APK once 'Install unknown apps' was granted.
        val resumedInstall = container.updateManager.onHostResume(this)
        if (resumedInstall is InstallLaunchResult.Failed) {
            viewModel.emitSimpleMessage(resumedInstall.message)
        }
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
            val httpPort = readOptionalIntExtra(intent, "http_port")
            if (httpPort != null && httpPort in 1024..65535) {
                container.settingsRepository.update { it.copy(httpPort = httpPort) }
            }
        }

        intent.getStringExtra("open_tab")?.trim()?.takeIf { it.isNotEmpty() }?.let { rawTab ->
            if (rawTab.equals("favorites", ignoreCase = true)) {
                viewModel.selectTab(MainTab.BROWSE)
                viewModel.openOverlay(OverlayScreen.Favorites)
                return@let
            }
            val matchedTab = when {
                rawTab.equals("transfer", ignoreCase = true) ||
                    rawTab.equals("wireless", ignoreCase = true) -> MainTab.WIRELESS

                rawTab.equals("settings", ignoreCase = true) ||
                    rawTab.equals("tools", ignoreCase = true) -> MainTab.TOOLS

                else -> MainTab.entries.firstOrNull {
                    it.name.equals(rawTab, ignoreCase = true) ||
                        it.label.equals(rawTab, ignoreCase = true)
                }
            }
            matchedTab?.let { tab ->
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
    val settings by viewModel.settings.collectAsState()
    val conflictPrompt by viewModel.conflictPrompt.collectAsState()
    val pendingIncomingBle by viewModel.pendingIncomingBle.collectAsState()
    val bleLinkUi by viewModel.bleLinkUi.collectAsState()
    val reauthGDriveBookmark by viewModel.reauthGDriveBookmark.collectAsState()
    val gdriveSignInState by viewModel.gdriveSignInState.collectAsState()
    val activeOps by container.operationRegistry.active.collectAsState()
    val ftpState by FtpServerHolder.state.collectAsState()
    val httpState by HttpServerHolder.state.collectAsState()
    val updateState by container.updateManager.state.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingSafMount by remember { mutableStateOf<SafMountRequest?>(null) }
    var pendingGoogleAccountPick by remember { mutableStateOf<GoogleAccountPickRequest?>(null) }
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

    // --- Main tab pager (v1.0.17: swipe left/right between Browse / Transfer / Settings) ---
    // Hoisted above the overlay switch so the page survives overlays and can be snapped to the
    // right tab while hidden (e.g. Favorite back navigation that also restores an overlay).
    val pagerState = rememberPagerState(initialPage = MainTabPagerSync.pageOf(activeTab)) {
        MainTabPagerSync.pageCount
    }
    // Keeps rememberSaveable state (list scroll positions, Settings drafts) of the tab pages while
    // an overlay replaces the pager.
    val tabsStateHolder = rememberSaveableStateHolder()
    var tabSyncState by remember { mutableStateOf(MainTabPagerSync.initialState(activeTab)) }
    val latestActiveTab by rememberUpdatedState(activeTab)
    val pagerVisible = overlay == null

    // ViewModel -> pager (NavigationBar taps, Snackbar actions, open_tab intents, Back, Favorites).
    LaunchedEffect(activeTab, pagerVisible) {
        val change = MainTabPagerSync.onActiveTabChanged(
            state = tabSyncState,
            activeTab = activeTab,
            pagerCurrentPage = pagerState.currentPage,
            pagerScrolling = pagerState.isScrollInProgress,
            pagerVisible = pagerVisible,
        )
        tabSyncState = change.state
        driveTabPager(
            pagerState = pagerState,
            command = change.command,
            isStillPending = { page -> tabSyncState.programmaticTarget == page },
            onFinished = { page -> tabSyncState = MainTabPagerSync.onProgrammaticScrollFinished(tabSyncState, page) },
        )
    }

    // A user drag takes over any programmatic jump still in flight.
    LaunchedEffect(pagerState) {
        pagerState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                tabSyncState = MainTabPagerSync.onUserDragStarted(tabSyncState)
            }
        }
    }

    // Pager -> ViewModel: commit a user swipe once it settles. Evaluated at every scroll end (not
    // only when settledPage changes): a drag that interrupts a programmatic animation and lands
    // back on the starting page leaves settledPage unchanged but must still be committed.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress to pagerState.settledPage }
            .filter { (scrolling, _) -> !scrolling }
            .collect { (_, page) ->
                val settle = MainTabPagerSync.onPageSettled(
                    state = tabSyncState,
                    settledPage = page,
                    activeTab = viewModel.activeTab.value,
                )
                tabSyncState = settle.state
                settle.tabToSelect?.let { viewModel.selectTab(it) }
            }
    }

    val selectedTab by remember(pagerState) {
        derivedStateOf {
            MainTabPagerSync.selectedTab(
                state = tabSyncState,
                activeTab = latestActiveTab,
                pagerScrolling = pagerState.isScrollInProgress,
                pagerTargetPage = pagerState.targetPage,
                pagerSettledPage = pagerState.settledPage,
            )
        }
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
            val reauthServerId = event.reauthGDriveServerId
            val dest = event.destinationToOpen
            val canRetry = event.error != null && container.operationRegistry.canRetry(event.id)
            val actionLabel = when {
                reauthServerId != null -> "Sign in"
                dest != null -> event.openLabel
                canRetry -> "Retry"
                else -> null
            }
            val result = snackbarHostState.showSnackbar(
                message = event.snackbarMessage,
                actionLabel = actionLabel,
                duration = if (reauthServerId != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                if (reauthServerId != null) {
                    viewModel.openReauthGDriveDialog(reauthServerId)
                } else if (dest != null) {
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

    LaunchedEffect(reauthGDriveBookmark?.id, gdriveSignInState) {
        val success = gdriveSignInState as? GDriveSignInState.Success
        if (reauthGDriveBookmark != null && success != null) {
            viewModel.dismissReauthGDriveDialog()
            viewModel.resetGDriveSignIn()
            viewModel.emitSimpleMessage("Signed in to Google Drive '${success.bookmark.name}'")
            if (browser.currentDir != null) {
                viewModel.reloadCurrentDirectory(silent = false)
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

    val googleAccountLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        val req = pendingGoogleAccountPick
        pendingGoogleAccountPick = null
        if (req != null && res.resultCode == Activity.RESULT_OK) {
            val accountName = res.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.trim().orEmpty()
            val activity = context.findActivity()
            if (accountName.isNotEmpty() && activity != null) {
                viewModel.completeGDriveSystemAccount(
                    activity = activity,
                    accountName = accountName,
                    displayName = req.displayName,
                    initialPath = req.initialPath,
                    existingBookmark = req.existingBookmark,
                )
            }
        }
    }

    val pickGoogleAccount: (String, String, GDriveServerBookmark?) -> Unit = { dispName, startFolder, existing ->
        pendingGoogleAccountPick = GoogleAccountPickRequest(
            displayName = dispName,
            initialPath = startFolder,
            existingBookmark = existing,
        )
        runCatching {
            googleAccountLauncher.launch(AndroidAccountTokenProvider.newChooseGoogleAccountIntent())
        }.onFailure {
            pendingGoogleAccountPick = null
            viewModel.emitSimpleMessage(AndroidAccountTokenProvider.UNAVAILABLE_MESSAGE)
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
                        val hasTransferDot = ftpState.isRunning || httpState.isRunning

                        NavigationBarItem(
                            selected = selectedTab == MainTab.BROWSE,
                            onClick = { viewModel.selectTab(MainTab.BROWSE) },
                            icon = { Icon(Icons.Filled.Folder, contentDescription = "Browse") },
                            label = { Text("Browse") },
                        )
                        NavigationBarItem(
                            selected = selectedTab == MainTab.WIRELESS,
                            onClick = { viewModel.selectTab(MainTab.WIRELESS) },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (hasTransferDot) {
                                            Badge()
                                        }
                                    },
                                ) {
                                    Icon(Icons.Filled.Wifi, contentDescription = MainTab.WIRELESS.label)
                                }
                            },
                            label = { Text(MainTab.WIRELESS.label) },
                        )
                        NavigationBarItem(
                            selected = selectedTab == MainTab.TOOLS,
                            onClick = { viewModel.selectTab(MainTab.TOOLS) },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (hasUpdateDot) {
                                            Badge()
                                        }
                                    },
                                ) {
                                    Icon(Icons.Filled.Settings, contentDescription = MainTab.TOOLS.label)
                                }
                            },
                            label = { Text(MainTab.TOOLS.label) },
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
                is OverlayScreen.Favorites -> {
                    FavoritesScreen(
                        viewModel = viewModel,
                        onClose = { viewModel.closeOverlay() },
                    )
                }

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

                is OverlayScreen.PdfViewer -> {
                    PdfViewerScreen(
                        pdfPath = currentOverlay.path,
                        viewModel = viewModel,
                        onClose = { viewModel.closeOverlay() },
                    )
                }

                null -> tabsStateHolder.SaveableStateProvider(MAIN_TABS_STATE_KEY) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        key = { page -> MainTabPagerSync.tabAt(page).name },
                    ) { page ->
                        when (MainTabPagerSync.tabAt(page)) {
                            MainTab.BROWSE -> {
                                BrowserScreen(
                                    viewModel = viewModel,
                                    isActiveTab = activeTab == MainTab.BROWSE,
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
                                    onPickGoogleAccount = pickGoogleAccount,
                                )
                            }

                            MainTab.WIRELESS -> {
                                WirelessScreen(viewModel = viewModel)
                            }

                            MainTab.TOOLS -> {
                                ToolsScreen(
                                    viewModel = viewModel,
                                    onGrantAllFilesAccess = requestAllFilesAccess,
                                    onRequestUnknownSourcesPermission = { intent ->
                                        // Open 'Install unknown apps' directly (no extra confirmation tap); the
                                        // verified APK stays pending and installs automatically on return.
                                        runCatching { context.startActivity(intent) }
                                            .onSuccess {
                                                viewModel.emitSimpleMessage(
                                                    "Allow 'Install unknown apps' for Files, then return to install the update automatically",
                                                )
                                            }
                                            .onFailure { e ->
                                                viewModel.emitSimpleMessage(
                                                    "Could not open install permission settings: ${e.message ?: e.javaClass.simpleName}",
                                                )
                                            }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // v1.0.18: incoming BLE transfer consent (60 s) + hotspot link progress / QR, visible from any tab.
    // Accepting without NEARBY_WIFI_DEVICES (Location on ≤ 12) would make the QR-free hotspot fail: ask at the
    // moment the user taps Accept (never at app start), then accept regardless of the answer — LAN may still work.
    var acceptAfterHotspotPermission by remember { mutableStateOf<Long?>(null) }
    val hotspotPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val id = acceptAfterHotspotPermission
        acceptAfterHotspotPermission = null
        viewModel.onNearbyPermissionsChanged()
        if (id != null) viewModel.respondToIncomingBle(id, accept = true)
    }
    pendingIncomingBle?.let { req ->
        PeerConsentDialog(
            request = req,
            onAccept = {
                if (!NearbyPermissions.hasHotspotPermissions(context) && acceptAfterHotspotPermission == null) {
                    acceptAfterHotspotPermission = req.requestId
                    val launched = runCatching {
                        hotspotPermissionLauncher.launch(NearbyPermissions.hotspotPermissions())
                    }.isSuccess
                    if (!launched) {
                        acceptAfterHotspotPermission = null
                        viewModel.respondToIncomingBle(req.requestId, accept = true)
                    }
                } else {
                    viewModel.respondToIncomingBle(req.requestId, accept = true)
                }
            },
            onDecline = { viewModel.respondToIncomingBle(req.requestId, accept = false) },
        )
    }
    if (pendingIncomingBle == null) {
        bleLinkUi?.let { ui ->
            HotspotLinkDialog(
                state = ui,
                onCancel = { viewModel.cancelBleLink() },
                onDismiss = { viewModel.dismissBleLinkUi() },
            )
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

    reauthGDriveBookmark?.let { target ->
        EditGDriveServerDialog(
            bookmark = target,
            startInReauthMode = true,
            hasGoogleAccountAuthenticator = remember(context) {
                AndroidAccountTokenProvider.hasGoogleAccountAuthenticator(context)
            },
            signInState = gdriveSignInState,
            defaultClientId = settings.gdriveLastClientId,
            defaultClientSecret = settings.gdriveLastClientSecret,
            onSaveMetadata = { updated ->
                viewModel.updateGDriveServer(updated)
                viewModel.dismissReauthGDriveDialog()
            },
            onRemove = {
                viewModel.removeGDriveServer(target.id)
                viewModel.dismissReauthGDriveDialog()
            },
            onPickGoogleAccount = { dispName, startFolder ->
                pickGoogleAccount(dispName, startFolder, target)
            },
            onStartBrowserSignIn = { cid, csec, dispName, startFolder ->
                viewModel.startGDriveBrowserSignIn(cid, csec, dispName, startFolder, target)
            },
            onSubmitPastedToken = { raw, cid, csec, dispName, startFolder ->
                viewModel.submitGDrivePastedCredentials(raw, cid, csec, dispName, startFolder, target)
            },
            onCancelSignIn = { viewModel.cancelGDriveSignIn() },
            onCopiedMessage = { viewModel.emitSimpleMessage(it) },
            onDismiss = { viewModel.dismissReauthGDriveDialog() },
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
}

private const val MAIN_TABS_STATE_KEY = "main_tabs_pager"

/**
 * Executes a [MainTabPagerCommand]. A scroll interrupted by a user gesture (or a higher-priority
 * scroll) is retried once the pager is idle, unless the gesture took over ([isStillPending] false).
 * Cancellation of the calling effect itself (newer tab change, pager leaving) is propagated.
 */
private suspend fun driveTabPager(
    pagerState: PagerState,
    command: MainTabPagerCommand,
    isStillPending: (Int) -> Boolean,
    onFinished: (Int) -> Unit,
) {
    var next = command
    while (true) {
        val (page, animate) = when (val c = next) {
            MainTabPagerCommand.None -> return
            is MainTabPagerCommand.AnimateTo -> c.page to true
            is MainTabPagerCommand.SnapTo -> c.page to false
        }
        try {
            if (animate) pagerState.animateScrollToPage(page) else pagerState.scrollToPage(page)
            onFinished(page)
            return
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            snapshotFlow { pagerState.isScrollInProgress }.first { !it }
            if (!isStillPending(page)) return
            if (pagerState.currentPage == page) {
                onFinished(page)
                return
            }
            next = MainTabPagerCommand.SnapTo(page)
        }
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
