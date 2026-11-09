package com.minifile.explorer.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.minifile.explorer.core.peer.PeerInfo
import com.minifile.explorer.core.peer.PeerPathPolicy
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.service.FtpServerHolder
import com.minifile.explorer.service.FtpServerService
import com.minifile.explorer.service.HttpServerHolder
import com.minifile.explorer.ui.MainViewModel
import com.minifile.explorer.ui.components.AddPeerByAddressDialog
import com.minifile.explorer.ui.components.DirectHotspotButtons
import com.minifile.explorer.ui.components.HotspotDirectSheet
import com.minifile.explorer.ui.components.JoinHotspotDialog
import com.minifile.explorer.ui.components.NearbyAccessRow
import com.minifile.explorer.ui.components.PeerDeviceRow
import com.minifile.explorer.ui.components.QrScannerSheet
import com.minifile.explorer.service.HotspotState
import com.minifile.explorer.ui.util.NearbyHeaderStatus
import com.minifile.explorer.ui.util.NearbyStatusCopy
import com.minifile.explorer.ui.util.QrCodeGenerator
import kotlin.math.floor
import kotlinx.coroutines.delay

private data class TransferLogRow(
    val key: String,
    val timestampMs: Long,
    val formattedTime: String,
    val protocol: String,
    val clientIp: String,
    val summary: String,
)

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
    val bleAvailability by viewModel.bleAvailability.collectAsState()

    var showRootDialog by remember { mutableStateOf(false) }
    var showFtpAuthDialog by remember { mutableStateOf(false) }
    var showDeviceNameDialog by remember { mutableStateOf(false) }
    var showAddPeerDialog by remember { mutableStateOf(false) }
    var pendingSendPeer by remember { mutableStateOf<PeerInfo?>(null) }
    var showScanButton by remember { mutableStateOf(false) }
    var showHotspotSheet by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    val joinState by viewModel.joinState.collectAsState()
    val hotspotState by viewModel.hotspotState.collectAsState()

    DisposableEffect(Unit) {
        viewModel.startPeerBrowsing()
        onDispose { viewModel.stopPeerBrowsing() }
    }

    LaunchedEffect(peers.isEmpty()) {
        if (peers.isEmpty()) {
            delay(3_000L)
            showScanButton = true
        } else {
            showScanButton = false
        }
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

    fun startHttpServer() {
        viewModel.onUserStartedHttp()
        launchWithNotificationCheck { FtpServerService.startHttp(context) }
    }

    fun startFtpServer() {
        launchWithNotificationCheck { FtpServerService.start(context) }
    }

    val sharedRootRaw = settings.ftpRoot ?: settings.httpRoot
    val sharedReadOnly = settings.ftpReadOnly || settings.httpReadOnly
    val isAnyRunning = httpState.isRunning || ftpState.isRunning

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
            merged.add(
                TransferLogRow(
                    key = "http-${e.id}",
                    timestampMs = e.timestampMs,
                    formattedTime = e.formattedTime,
                    protocol = "HTTP",
                    clientIp = e.clientIp,
                    summary = e.summary,
                ),
            )
        }
        for (e in ftpState.log) {
            merged.add(
                TransferLogRow(
                    key = "ftp-${e.id}",
                    timestampMs = e.timestampMs,
                    formattedTime = e.formattedTime,
                    protocol = "FTP",
                    clientIp = e.clientIp,
                    summary = e.summary,
                ),
            )
        }
        merged.sortByDescending { it.timestampMs }
        merged
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
            // 1. HTTP Share Action Section
            item(key = "http-action-card") {
                TransferSectionTitle("HTTP Share")
                if (!httpState.isRunning && !httpState.isStarting) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Button(
                            onClick = { startHttpServer() },
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(vertical = 14.dp, horizontal = 20.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "Start HTTP Share",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (httpState.lastError != null) {
                            Text(
                                text = httpState.lastError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                } else {
                    val activeHttpUrls = remember(httpState.urls, httpState.boundPort) {
                        val rawList = httpState.urls.ifEmpty {
                            listOf("http://127.0.0.1:${httpState.boundPort}")
                        }
                        rawList.map { "${it.trimEnd('/')}/" }
                    }
                    var selectedUrlIdx by remember(activeHttpUrls) { mutableIntStateOf(0) }
                    val qrHttpUrl = activeHttpUrls.getOrElse(selectedUrlIdx) { activeHttpUrls.first() }
                    val qrResult = remember(qrHttpUrl) {
                        runCatching { QrCodeGenerator.encode(qrHttpUrl) }
                    }

                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (httpState.isRunning) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline,
                                            ),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (httpState.isRunning) "HTTP Share Running" else "Starting…",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                if (httpState.isRunning) {
                                    AssistChip(
                                        onClick = {},
                                        label = {
                                            Text("↑ ${httpState.uploadsCount} · ↓ ${httpState.downloadsCount}")
                                        },
                                    )
                                }
                            }

                            if (httpState.isRunning) {
                                if (activeHttpUrls.size > 1) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        activeHttpUrls.forEachIndexed { idx, u ->
                                            val hostLabel = u.removePrefix("http://").substringBefore(':')
                                            FilterChip(
                                                selected = selectedUrlIdx == idx,
                                                onClick = { selectedUrlIdx = idx },
                                                label = { Text(hostLabel, maxLines = 1) },
                                            )
                                        }
                                    }
                                }

                                val matrix = qrResult.getOrNull()
                                if (matrix != null) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = Color.White,
                                        tonalElevation = 0.dp,
                                        shadowElevation = 2.dp,
                                        modifier = Modifier
                                            .widthIn(max = 220.dp)
                                            .fillMaxWidth(0.75f)
                                            .aspectRatio(1f),
                                    ) {
                                        Canvas(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(12.dp),
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

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    for (url in activeHttpUrls) {
                                        Text(
                                            text = url,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                }
                            }

                            Button(
                                onClick = { FtpServerService.stopHttp(context) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Stop HTTP Share", maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }

            // 2. Nearby Devices Section (App-to-App Peer Transfer)
            item(key = "nearby-devices-card") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TransferSectionTitle("Nearby Devices")
                    val headerStatus = NearbyStatusCopy.header(
                        ble = bleAvailability,
                        httpRunning = httpState.isRunning || httpState.isStarting,
                        readOnly = sharedReadOnly,
                    )
                    if (headerStatus != null) {
                        NearbyHeaderPill(status = headerStatus, modifier = Modifier.padding(bottom = 6.dp))
                    }
                }

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (peers.isEmpty()) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                            ) {
                                Text(
                                    text = NearbyStatusCopy.emptyState(bleAvailability, forSendSheet = false),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            peers.forEachIndexed { index, peer ->
                                if (index > 0) {
                                    HorizontalDivider()
                                }
                                val launchSendPicker: () -> Unit = {
                                    pendingSendPeer = peer
                                    documentPickerLauncher.launch(arrayOf("*/*"))
                                }
                                PeerDeviceRow(
                                    peer = peer,
                                    // Tapping the whole row (not only the small "Send…" button) opens the picker.
                                    onClick = if (peer.readOnly) {
                                        {
                                            val name = PeerPathPolicy.sanitizeDisplayName(peer.name, 64)
                                                .ifEmpty { peer.host }
                                            viewModel.emitSimpleMessage("$name is read-only (Transfer ▸ Settings)")
                                        }
                                    } else {
                                        launchSendPicker
                                    },
                                    trailingActionLabel = "Send…",
                                    onTrailingAction = launchSendPicker,
                                )
                            }
                        }

                        NearbyAccessRow(
                            viewModel = viewModel,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        HorizontalDivider()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            TextButton(onClick = { showAddPeerDialog = true }) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Add by address…")
                            }
                            if (showScanButton || isScanningPeers) {
                                TextButton(
                                    onClick = { viewModel.scanPeersSubnet() },
                                    enabled = !isScanningPeers,
                                ) {
                                    if (isScanningPeers) {
                                        CircularProgressIndicator(
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text("Scanning…")
                                    } else {
                                        Icon(
                                            Icons.Filled.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("Scan")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2b. Direct Hotspot (Scheme B): no shared Wi-Fi needed
            item(key = "direct-hotspot-card") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TransferSectionTitle("Direct Hotspot")
                    if (hotspotState is HotspotState.Active) {
                        Text(
                            text = "Hotspot on",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                    ) {
                        Text(
                            text = "Two phones without shared Wi-Fi: one hosts a temporary hotspot, the other scans its QR code.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        DirectHotspotButtons(
                            onHotspotDirect = { showHotspotSheet = true },
                            onScanQr = { showQrScanner = true },
                        )
                    }
                }
            }

            // 3. FTP Server Action Section
            item(key = "ftp-action-card") {
                TransferSectionTitle("FTP Server")
                if (!ftpState.isRunning && !ftpState.isStarting) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Button(
                            onClick = { startFtpServer() },
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(vertical = 14.dp, horizontal = 20.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "Start FTP Server",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (ftpState.lastError != null) {
                            Text(
                                text = ftpState.lastError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                } else {
                    val activeFtpUrls = remember(ftpState.urls, ftpState.boundPort) {
                        ftpState.urls.ifEmpty {
                            listOf("ftp://127.0.0.1:${ftpState.boundPort}")
                        }
                    }
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (ftpState.isRunning) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline,
                                            ),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (ftpState.isRunning) "FTP Server Running" else "Starting…",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                if (ftpState.isRunning) {
                                    AssistChip(
                                        onClick = {},
                                        label = {
                                            Text(
                                                if (ftpState.activeClients == 1) "1 client" else "${ftpState.activeClients} clients",
                                            )
                                        },
                                    )
                                }
                            }

                            if (ftpState.isRunning) {
                                for (url in activeFtpUrls) {
                                    Text(
                                        text = url,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }

                            Button(
                                onClick = { FtpServerService.stop(context) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Stop FTP Server", maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }

            // 3. Unified Shared Transfer Settings Card
            item(key = "shared-transfer-settings") {
                Spacer(Modifier.height(2.dp))
                TransferSectionTitle("Settings")
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SettingRow(
                            title = "Device name",
                            value = settings.peerDeviceName,
                            enabled = !httpState.isRunning,
                            onClick = { showDeviceNameDialog = true },
                        )
                        HorizontalDivider()
                        SettingRow(
                            title = "Root folder",
                            value = sharedRootDisplayLabel,
                            enabled = !isAnyRunning,
                            onClick = { showRootDialog = true },
                        )
                        HorizontalDivider()
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("Read-only") },
                            supportingContent = { Text("Disable uploads, deletes, and folder creation for HTTP & FTP") },
                            trailingContent = {
                                Switch(
                                    checked = sharedReadOnly,
                                    enabled = !isAnyRunning,
                                    onCheckedChange = { ro ->
                                        viewModel.container.settingsRepository.update {
                                            it.copy(ftpReadOnly = ro, httpReadOnly = ro)
                                        }
                                    },
                                )
                            },
                        )
                        HorizontalDivider()
                        SettingRow(
                            title = "FTP Authentication",
                            value = if (settings.ftpAnonymous) "Anonymous" else "User: ${settings.ftpUsername}",
                            enabled = !ftpState.isRunning,
                            onClick = { showFtpAuthDialog = true },
                        )
                    }
                }
            }

            // 4. Live Activity Log
            item(key = "transfer-log-header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Activity (${combinedLog.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(
                        onClick = {
                            HttpServerHolder.clearLog()
                            FtpServerHolder.clearLog()
                        },
                        enabled = combinedLog.isNotEmpty(),
                    ) {
                        Text("Clear")
                    }
                }
            }

            if (combinedLog.isEmpty()) {
                item(key = "transfer-log-empty") {
                    Text(
                        text = "No transfer activity yet. Tap Start HTTP Share to scan the QR code from another phone/browser, or Start FTP Server for PC Companion.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(combinedLog, key = { it.key }) { entry ->
                    Text(
                        text = "${entry.formattedTime}  [${entry.protocol}] ${entry.clientIp}  ${entry.summary}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                }
            }
        }
    }

    if (showRootDialog) {
        RootFolderPickerDialog(
            title = "Root Folder",
            volumes = volumes,
            favorites = favorites,
            onSelect = { path ->
                val serialized = path.serialize()
                viewModel.container.settingsRepository.update {
                    it.copy(ftpRoot = serialized, httpRoot = serialized)
                }
                showRootDialog = false
            },
            onDismiss = { showRootDialog = false },
        )
    }

    if (showFtpAuthDialog) {
        var anon by remember { mutableStateOf(settings.ftpAnonymous) }
        var user by remember { mutableStateOf(settings.ftpUsername) }
        var pass by remember { mutableStateOf(settings.ftpPassword) }
        var showPass by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showFtpAuthDialog = false },
            title = { Text("FTP Authentication") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { anon = true },
                    ) {
                        RadioButton(selected = anon, onClick = { anon = true })
                        Spacer(Modifier.width(6.dp))
                        Text("Anonymous (any login accepted)")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { anon = false },
                    ) {
                        RadioButton(selected = !anon, onClick = { anon = false })
                        Spacer(Modifier.width(6.dp))
                        Text("Username & Password")
                    }
                    if (!anon) {
                        OutlinedTextField(
                            value = user,
                            onValueChange = { user = it },
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = pass,
                            onValueChange = { pass = it },
                            label = { Text("Password") },
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
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.container.settingsRepository.update {
                            it.copy(
                                ftpAnonymous = anon,
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
            title = { Text("Device name") },
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
                        label = { Text("Device name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.container.settingsRepository.setPeerDeviceName(nameDraft)
                        showDeviceNameDialog = false
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showDeviceNameDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showAddPeerDialog) {
        AddPeerByAddressDialog(
            viewModel = viewModel,
            onAdded = { showAddPeerDialog = false },
            onDismiss = { showAddPeerDialog = false },
        )
    }

    if (showHotspotSheet) {
        HotspotDirectSheet(
            viewModel = viewModel,
            onDismiss = { showHotspotSheet = false },
            onSendToPeer = { peer ->
                pendingSendPeer = peer
                documentPickerLauncher.launch(arrayOf("*/*"))
            },
        )
    }

    if (showQrScanner) {
        QrScannerSheet(
            onDismiss = { showQrScanner = false },
            onResult = { text ->
                showQrScanner = false
                viewModel.joinFromQr(text)
            },
        )
    }

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
private fun RootFolderPickerDialog(
    title: String,
    volumes: List<com.minifile.explorer.core.vfs.Volume>,
    favorites: List<com.minifile.explorer.core.model.Favorite>,
    onSelect: (VfsPath) -> Unit,
    onDismiss: () -> Unit,
) {
    val folderFavorites = remember(favorites) { favorites.filter { it.isDirectory } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                val readyVolumes = volumes.filter { it.root != null }
                items(readyVolumes, key = { it.id }) { vol ->
                    ListItem(
                        headlineContent = { Text(vol.name) },
                        supportingContent = vol.subtitle?.let { { Text(it, maxLines = 1) } },
                        modifier = Modifier.clickable {
                            onSelect(vol.root!!)
                        },
                    )
                }
                items(folderFavorites, key = { "fav-${it.id}" }) { fav ->
                    ListItem(
                        headlineContent = { Text("★ ${fav.name}") },
                        supportingContent = { Text(fav.path.displayName) },
                        modifier = Modifier.clickable {
                            onSelect(fav.path)
                        },
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
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    )
}

/** Compact status caption beside "Nearby Devices" (e.g. Bluetooth glyph + "Bluetooth ready · No QR"). */
@Composable
private fun NearbyHeaderPill(status: NearbyHeaderStatus, modifier: Modifier = Modifier) {
    val accent = status.bluetooth
    Surface(
        shape = RoundedCornerShape(50),
        color = if (accent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = if (accent) 8.dp else 0.dp, vertical = 2.dp),
        ) {
            if (accent) {
                Icon(
                    Icons.Filled.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = status.text,
                style = MaterialTheme.typography.labelSmall,
                color = if (accent) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
