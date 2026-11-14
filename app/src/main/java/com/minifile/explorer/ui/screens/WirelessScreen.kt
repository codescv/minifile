package com.minifile.explorer.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WifiTethering
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.minifile.explorer.core.model.OperationKind
import com.minifile.explorer.core.peer.PeerConflictPolicy
import com.minifile.explorer.core.peer.PeerInfo
import com.minifile.explorer.core.peer.PeerPathPolicy
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.service.FtpServerHolder
import com.minifile.explorer.service.FtpServerService
import com.minifile.explorer.service.HttpServerHolder
import com.minifile.explorer.service.OperationRegistry
import com.minifile.explorer.ui.MainViewModel
import com.minifile.explorer.ui.components.JoinHotspotDialog
import com.minifile.explorer.ui.components.NearbyAccessRow
import com.minifile.explorer.ui.components.PeerDeviceRow
import com.minifile.explorer.ui.util.NearbyStatusCopy
import com.minifile.explorer.ui.util.QrCodeGenerator
import java.io.File
import kotlin.math.floor

private data class TransferLogRow(
    val key: String,
    val timestampMs: Long,
    val formattedTime: String,
    val protocol: String,
    val clientIp: String,
    val summary: String,
)

/** Max activity rows rendered when the log is expanded (iOS `services.log.prefix(100)`). */
private const val ACTIVITY_LOG_LIMIT = 100

internal fun conflictPolicyLabel(policy: PeerConflictPolicy): String = when (policy) {
    PeerConflictPolicy.RENAME -> "Rename"
    PeerConflictPolicy.OVERWRITE -> "Overwrite"
    PeerConflictPolicy.SKIP -> "Skip"
}

/**
 * Transfer tab, 1:1 with the iOS `TransferView`:
 * 1. Nearby Devices (Discoverable switch, peers, add by address, scan network)
 * 2. HTTP & FTP Server (one switch for both servers + shared Read-only; HTTP QR + address, FTP address only)
 * 3. Transfer Settings (Device Name, When File Exists, Require Approval, Share Root)
 * 4. Active send / receive progress
 * 5. Activity (collapsible log + Clear)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WirelessScreen(
    viewModel: MainViewModel,
) {
    val context = LocalContext.current

    val settings by viewModel.settings.collectAsState()
    val volumes by viewModel.volumes.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val httpState by HttpServerHolder.state.collectAsState()
    val ftpState by FtpServerHolder.state.collectAsState()
    val peers by viewModel.peers.collectAsState()
    val isScanningPeers by viewModel.isScanningPeers.collectAsState()
    val joinState by viewModel.joinState.collectAsState()
    val activeOps by OperationRegistry.instance.active.collectAsState()

    var showRootDialog by remember { mutableStateOf(false) }
    var showFtpAuthDialog by remember { mutableStateOf(false) }
    var showDeviceNameDialog by remember { mutableStateOf(false) }
    var showConflictDialog by remember { mutableStateOf(false) }
    var portDialog by remember { mutableStateOf<String?>(null) } // "http" | "ftp"
    var pendingSendPeer by remember { mutableStateOf<PeerInfo?>(null) }
    var manualPeer by remember { mutableStateOf("") }
    var addingPeer by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        viewModel.startPeerBrowsing()
        onDispose { viewModel.stopPeerBrowsing() }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val targetPeer = pendingSendPeer
        pendingSendPeer = null
        if (targetPeer != null) {
            if (uris.isNotEmpty()) {
                viewModel.sendUrisToPeer(context, targetPeer, uris)
            } else {
                viewModel.releaseJoinedHotspotForPeer(targetPeer, immediate = true)
            }
        }
    }

    var pendingStartAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        val action = pendingStartAction
        pendingStartAction = null
        action?.invoke()
    }

    fun launchWithNotificationCheck(startAction: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStartAction = startAction
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startAction()
        }
    }

    /** Unified "HTTP & FTP Server" switch (iOS `startFileSharing` / `stopFileSharing`). */
    fun setFileSharing(on: Boolean) {
        if (on) {
            viewModel.onUserStartedHttp()
            launchWithNotificationCheck { FtpServerService.startBoth(context) }
        } else {
            FtpServerService.stopBoth(context)
        }
    }

    fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("URL", text))
        viewModel.emitSimpleMessage("Copied")
    }

    fun addManualPeer() {
        val raw = manualPeer.trim()
        if (raw.isEmpty() || addingPeer) return
        addingPeer = true
        viewModel.addPeerByAddress(
            rawAddress = raw,
            onSuccess = {
                addingPeer = false
                manualPeer = ""
            },
            onError = { err ->
                addingPeer = false
                viewModel.emitSimpleMessage(err)
            },
        )
    }

    val repo = viewModel.container.settingsRepository
    val sharedRootRaw = settings.ftpRoot ?: settings.httpRoot
    val sharedReadOnly = settings.ftpReadOnly || settings.httpReadOnly
    val fileSharingRunning = httpState.isRunning || ftpState.isRunning
    val fileSharingStarting = (httpState.isStarting || ftpState.isStarting) && !fileSharingRunning
    val isDiscoverable = settings.peerDiscoverable

    val sharedRootDisplayLabel = remember(sharedRootRaw, volumes, favorites) {
        val raw = sharedRootRaw ?: return@remember "Internal"
        val parsed = runCatching { VfsPath.parse(raw) }.getOrNull() ?: return@remember "Internal"
        volumes.firstOrNull { it.root == parsed }?.name
            ?: favorites.firstOrNull { it.path == parsed }?.let { "★ ${it.name}" }
            ?: parsed.displayName
    }

    val combinedLog = remember(httpState.log, ftpState.log) {
        val merged = ArrayList<TransferLogRow>(httpState.log.size + ftpState.log.size)
        for (e in httpState.log) {
            merged.add(TransferLogRow("http-${e.id}", e.timestampMs, e.formattedTime, "HTTP", e.clientIp, e.summary))
        }
        for (e in ftpState.log) {
            merged.add(TransferLogRow("ftp-${e.id}", e.timestampMs, e.formattedTime, "FTP", e.clientIp, e.summary))
        }
        merged.sortByDescending { it.timestampMs }
        merged
    }

    val peerTransfers = remember(activeOps) {
        activeOps.filter { (it.kind == OperationKind.SEND || it.kind == OperationKind.RECEIVE) && !it.finished }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transfer", fontWeight = FontWeight.SemiBold) },
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // 1. Nearby Devices
            item(key = "nearby-devices") {
                TransferSectionTitle("Nearby Devices")
                TransferCard {
                    SwitchRow(
                        title = NearbyStatusCopy.DISCOVERABLE_LABEL,
                        checked = isDiscoverable,
                        onCheckedChange = { viewModel.setDiscoverable(it) },
                        leading = {
                            Icon(
                                Icons.Filled.WifiTethering,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                    NearbyAccessRow(
                        viewModel = viewModel,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    HorizontalDivider()
                    if (peers.isEmpty()) {
                        Text(
                            text = NearbyStatusCopy.transferEmptyState(isDiscoverable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                    } else {
                        peers.forEachIndexed { index, peer ->
                            if (index > 0) HorizontalDivider()
                            val launchSendPicker: () -> Unit = {
                                pendingSendPeer = peer
                                documentPickerLauncher.launch(arrayOf("*/*"))
                            }
                            PeerDeviceRow(
                                peer = peer,
                                onClick = if (peer.readOnly) {
                                    {
                                        val name = PeerPathPolicy.sanitizeDisplayName(peer.name, 64)
                                            .ifEmpty { peer.host }
                                        viewModel.emitSimpleMessage("$name is read-only")
                                    }
                                } else {
                                    launchSendPicker
                                },
                                trailingActionLabel = "Send…",
                                onTrailingAction = launchSendPicker,
                            )
                        }
                    }
                    HorizontalDivider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    ) {
                        OutlinedTextField(
                            value = manualPeer,
                            onValueChange = { manualPeer = it.take(256) },
                            placeholder = {
                                Text(
                                    NearbyStatusCopy.ADD_PEER_PLACEHOLDER,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { addManualPeer() }),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { addManualPeer() },
                            enabled = manualPeer.isNotBlank() && !addingPeer,
                        ) {
                            if (addingPeer) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                            } else {
                                Text("Add")
                            }
                        }
                    }
                    TextButton(
                        onClick = { viewModel.scanPeersSubnet() },
                        enabled = !isScanningPeers,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        if (isScanningPeers) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (isScanningPeers) "Scanning…" else "Scan this network")
                    }
                }
                SectionFooter(NearbyStatusCopy.NEARBY_FOOTER)
            }

            // 2. HTTP & FTP Server
            item(key = "file-server") {
                TransferSectionTitle(NearbyStatusCopy.FILE_SERVER_TITLE)
                TransferCard {
                    SwitchRow(
                        title = NearbyStatusCopy.FILE_SERVER_TITLE,
                        checked = fileSharingRunning || fileSharingStarting,
                        onCheckedChange = { setFileSharing(it) },
                        leading = {
                            Icon(
                                Icons.Filled.Language,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        titleTrailing = if (fileSharingStarting) {
                            { CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp)) }
                        } else {
                            null
                        },
                    )
                    HorizontalDivider()
                    SwitchRow(
                        title = "Read-only",
                        checked = sharedReadOnly,
                        enabled = !fileSharingRunning && !fileSharingStarting,
                        onCheckedChange = { repo.setTransferReadOnly(it) },
                    )

                    if (fileSharingRunning) {
                        HorizontalDivider()
                        FileServerRunningContent(
                            httpRunning = httpState.isRunning,
                            httpUrls = httpState.urls,
                            httpBoundPort = httpState.boundPort,
                            uploads = httpState.uploadsCount,
                            downloads = httpState.downloadsCount,
                            ftpRunning = ftpState.isRunning,
                            ftpUrls = ftpState.urls,
                            ftpClients = ftpState.activeClients,
                            onCopy = ::copyToClipboard,
                        )
                    } else if (!fileSharingStarting) {
                        HorizontalDivider()
                        SettingRow(
                            title = "HTTP Port",
                            value = settings.httpPort.toString(),
                            enabled = true,
                            onClick = { portDialog = "http" },
                        )
                        HorizontalDivider()
                        SettingRow(
                            title = "FTP Port",
                            value = settings.ftpPort.toString(),
                            enabled = true,
                            onClick = { portDialog = "ftp" },
                        )
                        HorizontalDivider()
                        SwitchRow(
                            title = "FTP Anonymous Login",
                            checked = settings.ftpAnonymous,
                            onCheckedChange = { anon -> repo.update { it.copy(ftpAnonymous = anon) } },
                        )
                        if (!settings.ftpAnonymous) {
                            HorizontalDivider()
                            SettingRow(
                                title = "FTP Username & Password",
                                value = settings.ftpUsername,
                                enabled = true,
                                onClick = { showFtpAuthDialog = true },
                            )
                        }
                        val err = httpState.lastError ?: ftpState.lastError
                        if (err != null) {
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
                SectionFooter(NearbyStatusCopy.FILE_SERVER_FOOTER)
            }

            // 3. Transfer Settings
            item(key = "transfer-settings") {
                TransferSectionTitle("Transfer Settings")
                TransferCard {
                    SettingRow(
                        title = "Device Name",
                        value = settings.peerDeviceName,
                        enabled = true,
                        onClick = { showDeviceNameDialog = true },
                    )
                    HorizontalDivider()
                    SettingRow(
                        title = "When File Exists",
                        value = conflictPolicyLabel(settings.peerConflictPolicy),
                        enabled = true,
                        onClick = { showConflictDialog = true },
                    )
                    HorizontalDivider()
                    SwitchRow(
                        title = "Require Approval",
                        checked = settings.peerConfirmReceive,
                        onCheckedChange = { repo.setPeerConfirmReceive(it) },
                    )
                    HorizontalDivider()
                    SettingRow(
                        title = "Share Root",
                        value = sharedRootDisplayLabel,
                        enabled = !fileSharingRunning && !fileSharingStarting,
                        onClick = { showRootDialog = true },
                    )
                }
            }

            // 4. Active peer send / receive progress
            items(peerTransfers, key = { "op-${it.id}" }) { op ->
                TransferSectionTitle(op.title)
                TransferCard {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        if (op.totalBytes > 0L) {
                            val frac = (op.processedBytes.toFloat() / op.totalBytes.toFloat()).coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                            Text(
                                text = "${op.processedItems}/${op.totalItems} files · " +
                                    "${PathUtils.formatBytes(op.processedBytes)} of ${PathUtils.formatBytes(op.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = op.currentItem.ifBlank { "Connecting…" },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(
                            onClick = { OperationRegistry.instance.cancel(op.id) },
                            enabled = !op.cancelled,
                        ) {
                            Text("Cancel", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // 5. Activity
            item(key = "activity") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TransferSectionTitle("Activity")
                    if (combinedLog.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                HttpServerHolder.clearLog()
                                FtpServerHolder.clearLog()
                            },
                        ) {
                            Text("Clear", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                TransferCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showLog = !showLog }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Text(
                            text = "Activity log (${combinedLog.size})",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (showLog) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (showLog) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (showLog) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        ) {
                            for (entry in combinedLog.take(ACTIVITY_LOG_LIMIT)) {
                                Text(
                                    text = "${entry.formattedTime}  [${entry.protocol}] ${entry.clientIp}  ${entry.summary}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRootDialog) {
        RootFolderPickerDialog(
            title = "Share Root",
            volumes = volumes,
            favorites = favorites,
            onSelect = { path ->
                repo.setTransferRoot(path.serialize())
                showRootDialog = false
            },
            onDismiss = { showRootDialog = false },
        )
    }

    if (showConflictDialog) {
        AlertDialog(
            onDismissRequest = { showConflictDialog = false },
            title = { Text("When File Exists") },
            text = {
                Column {
                    for (policy in PeerConflictPolicy.entries) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    repo.setPeerConflictPolicy(policy)
                                    showConflictDialog = false
                                },
                        ) {
                            RadioButton(
                                selected = settings.peerConflictPolicy == policy,
                                onClick = {
                                    repo.setPeerConflictPolicy(policy)
                                    showConflictDialog = false
                                },
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(conflictPolicyLabel(policy))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showConflictDialog = false }) { Text("Cancel") } },
        )
    }

    portDialog?.let { which ->
        val isHttp = which == "http"
        val current = if (isHttp) settings.httpPort else settings.ftpPort
        var draft by remember(which) { mutableStateOf(current.toString()) }
        var error by remember(which) { mutableStateOf<String?>(null) }
        fun save() {
            val port = draft.trim().toIntOrNull()
            val ok = port != null && if (isHttp) repo.setHttpPort(port) else repo.setFtpPort(port)
            if (ok) portDialog = null else error = "Enter a port between 1024 and 65535"
        }
        AlertDialog(
            onDismissRequest = { portDialog = null },
            title = { Text(if (isHttp) "HTTP Port" else "FTP Port") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { v ->
                        draft = v.filter { it.isDigit() }.take(5)
                        error = null
                    },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { msg -> { Text(msg) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { Button(onClick = { save() }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { portDialog = null }) { Text("Cancel") } },
        )
    }

    if (showFtpAuthDialog) {
        var user by remember { mutableStateOf(settings.ftpUsername) }
        var pass by remember { mutableStateOf(settings.ftpPassword) }
        var showPass by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showFtpAuthDialog = false },
            title = { Text("FTP Username & Password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text("FTP Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text("FTP Password") },
                        singleLine = true,
                        visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPass = !showPass }) {
                                Icon(
                                    if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = "Toggle password visibility",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        repo.update {
                            it.copy(
                                ftpAnonymous = false,
                                ftpUsername = user.trim().ifEmpty { "minifile" },
                                ftpPassword = pass,
                            )
                        }
                        showFtpAuthDialog = false
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showFtpAuthDialog = false }) { Text("Cancel") } },
        )
    }

    if (showDeviceNameDialog) {
        var nameDraft by remember(settings.peerDeviceName) { mutableStateOf(settings.peerDeviceName) }
        AlertDialog(
            onDismissRequest = { showDeviceNameDialog = false },
            title = { Text("Device Name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Shown to nearby phones when sharing or sending files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { if (it.length <= 64) nameDraft = it },
                        label = { Text("Device Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        repo.setPeerDeviceName(nameDraft)
                        showDeviceNameDialog = false
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showDeviceNameDialog = false }) { Text("Cancel") }
            },
        )
    }

    // QR / deep-link joins (`viewModel.joinFromQr`) still surface their progress + "Send files…" here.
    joinState?.let { js ->
        JoinHotspotDialog(
            state = js,
            onSend = { peer ->
                viewModel.dismissJoinDialog()
                pendingSendPeer = peer
                documentPickerLauncher.launch(arrayOf("*/*"))
            },
            onCancel = { viewModel.cancelJoin() },
            onClose = { viewModel.dismissJoinDialog() },
            sendLabel = "Send files…",
        )
    }
}

@Composable
private fun FileServerRunningContent(
    httpRunning: Boolean,
    httpUrls: List<String>,
    httpBoundPort: Int,
    uploads: Int,
    downloads: Int,
    ftpRunning: Boolean,
    ftpUrls: List<String>,
    ftpClients: Int,
    onCopy: (String) -> Unit,
) {
    val activeHttpUrls = remember(httpUrls, httpBoundPort) {
        httpUrls.ifEmpty { listOf("http://127.0.0.1:$httpBoundPort") }.map { "${it.trimEnd('/')}/" }
    }
    var selectedUrlIdx by remember(activeHttpUrls) { mutableIntStateOf(0) }
    val idx = selectedUrlIdx.coerceIn(0, activeHttpUrls.size - 1)
    val selectedUrl = activeHttpUrls[idx]

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2E7D32)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = NearbyStatusCopy.FILE_SERVER_RUNNING,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF2E7D32),
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            ) {
                val ftpPart = if (ftpClients > 0) " · FTP $ftpClients" else ""
                Text(
                    text = "↑ $uploads · ↓ $downloads$ftpPart",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        if (httpRunning) {
            if (activeHttpUrls.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    activeHttpUrls.forEachIndexed { i, u ->
                        val hostLabel = u.removePrefix("http://").substringBefore(':').substringBefore('/')
                        FilterChip(
                            selected = idx == i,
                            onClick = { selectedUrlIdx = i },
                            label = { Text(hostLabel, maxLines = 1) },
                        )
                    }
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                QrCodeCard(text = selectedUrl)
                Text(
                    text = selectedUrl,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(onClick = { onCopy(selectedUrl) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy URL")
                }
            }
        }

        if (ftpRunning) {
            for (ftpUrl in ftpUrls) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCopy(ftpUrl) }
                        .padding(vertical = 4.dp),
                ) {
                    Text("FTP", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        text = ftpUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy FTP address",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun QrCodeCard(text: String) {
    val matrix = remember(text) { runCatching { QrCodeGenerator.encode(text) }.getOrNull() } ?: return
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        shadowElevation = 2.dp,
        modifier = Modifier.size(180.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
        ) {
            val quietZone = 4
            val totalModules = matrix.size + quietZone * 2
            val cellPx = floor(minOf(size.width, size.height) / totalModules).coerceAtLeast(1f)
            val gridPx = cellPx * totalModules
            val originX = floor((size.width - gridPx) / 2f)
            val originY = floor((size.height - gridPx) / 2f)
            drawRect(color = Color.White, size = size)
            for (y in 0 until matrix.size) {
                for (x in 0 until matrix.size) {
                    if (matrix[x, y]) {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(
                                x = originX + (x + quietZone) * cellPx,
                                y = originY + (y + quietZone) * cellPx,
                            ),
                            size = Size(cellPx, cellPx),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun TransferSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun SectionFooter(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    titleTrailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = leading,
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (titleTrailing != null) {
                    Spacer(Modifier.width(8.dp))
                    titleTrailing()
                }
            }
        },
        trailingContent = {
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
    )
}

@Composable
private fun RootFolderPickerDialog(
    title: String,
    volumes: List<com.minifile.explorer.core.vfs.Volume>,
    favorites: List<com.minifile.explorer.core.model.Favorite>,
    onSelect: (VfsPath) -> Unit,
    onDismiss: () -> Unit,
) {
    val folderFavorites = remember(favorites) { favorites.filter { it.isDirectory } }
    val receivedDir = remember { File(Environment.getExternalStorageDirectory(), "Received") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                val readyVolumes = volumes.filter { it.root != null }
                items(readyVolumes, key = { it.id }) { vol ->
                    ListItem(
                        headlineContent = { Text(vol.name) },
                        supportingContent = vol.subtitle?.let { { Text(it, maxLines = 1) } },
                        modifier = Modifier.clickable { onSelect(vol.root!!) },
                    )
                }
                item(key = "received") {
                    ListItem(
                        headlineContent = { Text("Received") },
                        supportingContent = { Text("Incoming nearby transfers", maxLines = 1) },
                        modifier = Modifier.clickable {
                            runCatching { receivedDir.mkdirs() }
                            onSelect(VfsPath.Local(receivedDir.absolutePath))
                        },
                    )
                }
                items(folderFavorites, key = { "fav-${it.id}" }) { fav ->
                    ListItem(
                        headlineContent = { Text("★ ${fav.name}") },
                        supportingContent = { Text(fav.path.displayName) },
                        modifier = Modifier.clickable { onSelect(fav.path) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SettingRow(
    title: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = title,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Text(
                text = "$value  ›",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    )
}
