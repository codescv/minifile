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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.service.FtpServerHolder
import com.minifile.explorer.service.FtpServerService
import com.minifile.explorer.service.HttpServerHolder
import com.minifile.explorer.ui.MainViewModel
import com.minifile.explorer.ui.util.QrCodeGenerator
import com.minifile.explorer.ui.util.UiFormatters
import kotlin.math.floor

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
    val lanEndpoints by viewModel.container.lanAddressResolver.observeEndpoints()
        .collectAsState(initial = viewModel.container.lanAddressResolver.currentEndpoints())

    var showHttpPortDialog by remember { mutableStateOf(false) }
    var showHttpRootDialog by remember { mutableStateOf(false) }
    var showHttpQrDialog by remember { mutableStateOf(false) }

    var showFtpPortDialog by remember { mutableStateOf(false) }
    var showFtpRootDialog by remember { mutableStateOf(false) }
    var showFtpAuthDialog by remember { mutableStateOf(false) }

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
        launchWithNotificationCheck { FtpServerService.startHttp(context) }
    }

    fun startFtpServer() {
        launchWithNotificationCheck { FtpServerService.start(context) }
    }

    val httpRootDisplayLabel = remember(settings.httpRoot, volumes, favorites) {
        val raw = settings.httpRoot ?: return@remember "Internal"
        val parsed = runCatching { VfsPath.parse(raw) }.getOrNull() ?: return@remember "Internal"
        volumes.firstOrNull { it.root == parsed }?.name
            ?: favorites.firstOrNull { it.path == parsed }?.let { "★ ${it.name}" }
            ?: parsed.displayName
    }

    val ftpRootDisplayLabel = remember(settings.ftpRoot, volumes, favorites) {
        val raw = settings.ftpRoot ?: return@remember "Internal"
        val parsed = runCatching { VfsPath.parse(raw) }.getOrNull() ?: return@remember "Internal"
        volumes.firstOrNull { it.root == parsed }?.name
            ?: favorites.firstOrNull { it.path == parsed }?.let { "★ ${it.name}" }
            ?: parsed.displayName
    }

    val httpPreviewUrls = if (httpState.isRunning) {
        httpState.urls
    } else if (lanEndpoints.isNotEmpty()) {
        lanEndpoints.map { "http://${it.ipv4}:${settings.httpPort}" }
    } else {
        listOf("http://127.0.0.1:${settings.httpPort}")
    }

    val ftpPreviewUrls = if (ftpState.isRunning) {
        ftpState.urls
    } else if (lanEndpoints.isNotEmpty()) {
        lanEndpoints.map { "ftp://${it.ipv4}:${settings.ftpPort}" }
    } else {
        listOf("ftp://127.0.0.1:${settings.ftpPort}")
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

    val ifaceSubtitle = if (lanEndpoints.isEmpty()) {
        "No Wi-Fi LAN IPv4 detected — reachable via hotspot, USB tethering, or adb forward."
    } else {
        lanEndpoints.joinToString(" · ") { "${it.categoryLabel} (${it.interfaceName})" }
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
            // 1. HTTP Share (Browser / QR Code) Card
            item(key = "http-status-card") {
                TransferSectionTitle("HTTP Share (Browser / QR Code)")
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (httpState.isRunning) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(18.dp),
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
                                    text = when {
                                        httpState.isRunning -> "Running"
                                        httpState.isStarting -> "Starting…"
                                        else -> "Stopped"
                                    },
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

                        for (url in httpPreviewUrls) {
                            Text(
                                text = url,
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        Text(
                            text = ifaceSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (httpState.lastError != null) {
                            Text(
                                text = httpState.lastError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        val primaryHttpUrl = "${httpPreviewUrls.first().trimEnd('/')}/"
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (httpState.isRunning || httpState.isStarting) {
                                Button(
                                    onClick = { FtpServerService.stopHttp(context) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError,
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Stop", maxLines = 1, softWrap = false)
                                }
                            } else {
                                Button(
                                    onClick = { startHttpServer() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Start", maxLines = 1, softWrap = false)
                                }
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        UiFormatters.copyToSystemClipboard(context, "HTTP URL", primaryHttpUrl)
                                        viewModel.emitSimpleMessage("Copied $primaryHttpUrl")
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Copy URL", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                }
                                OutlinedButton(
                                    onClick = { showHttpQrDialog = true },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Filled.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("QR Code", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }

            // 2. HTTP Settings Card
            item(key = "http-settings") {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SettingRow(
                            title = "Port",
                            value = settings.httpPort.toString(),
                            enabled = !httpState.isRunning,
                            onClick = { showHttpPortDialog = true },
                        )
                        HorizontalDivider()
                        SettingRow(
                            title = "Root folder",
                            value = httpRootDisplayLabel,
                            enabled = !httpState.isRunning,
                            onClick = { showHttpRootDialog = true },
                        )
                        HorizontalDivider()
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("Read-only") },
                            supportingContent = { Text("Disable browser file uploads and new folder creation") },
                            trailingContent = {
                                Switch(
                                    checked = settings.httpReadOnly,
                                    enabled = !httpState.isRunning,
                                    onCheckedChange = { ro ->
                                        viewModel.container.settingsRepository.update { it.copy(httpReadOnly = ro) }
                                    },
                                )
                            },
                        )
                    }
                }
            }

            // 3. FTP Server (PC Companion) Card — Start/Stop only (no Copy URL, no QR Code)
            item(key = "ftp-status-card") {
                Spacer(Modifier.height(4.dp))
                TransferSectionTitle("FTP Server (PC Companion)")
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (ftpState.isRunning) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(18.dp),
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
                                    text = when {
                                        ftpState.isRunning -> "Running"
                                        ftpState.isStarting -> "Starting…"
                                        else -> "Stopped"
                                    },
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

                        for (url in ftpPreviewUrls) {
                            Text(
                                text = url,
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
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

                        if (ftpState.isRunning || ftpState.isStarting) {
                            Button(
                                onClick = { FtpServerService.stop(context) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Stop", maxLines = 1, softWrap = false)
                            }
                        } else {
                            Button(
                                onClick = { startFtpServer() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Start", maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }

            // 4. FTP Settings Card
            item(key = "ftp-settings") {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SettingRow(
                            title = "Port",
                            value = settings.ftpPort.toString(),
                            enabled = !ftpState.isRunning,
                            onClick = { showFtpPortDialog = true },
                        )
                        HorizontalDivider()
                        SettingRow(
                            title = "Root folder",
                            value = ftpRootDisplayLabel,
                            enabled = !ftpState.isRunning,
                            onClick = { showFtpRootDialog = true },
                        )
                        HorizontalDivider()
                        SettingRow(
                            title = "Authentication",
                            value = if (settings.ftpAnonymous) "Anonymous" else "User: ${settings.ftpUsername}",
                            enabled = !ftpState.isRunning,
                            onClick = { showFtpAuthDialog = true },
                        )
                        HorizontalDivider()
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("Read-only") },
                            supportingContent = { Text("Reject uploads, deletes, and renames with 550") },
                            trailingContent = {
                                Switch(
                                    checked = settings.ftpReadOnly,
                                    enabled = !ftpState.isRunning,
                                    onCheckedChange = { ro ->
                                        viewModel.container.settingsRepository.update { it.copy(ftpReadOnly = ro) }
                                    },
                                )
                            },
                        )
                    }
                }
            }

            // 5. Live Activity Log
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
                        text = "No transfer activity yet. Start HTTP Share to scan the QR code from another phone/browser, or start FTP Server for PC Companion.",
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

    if (showHttpQrDialog) {
        var selectedUrlIdx by remember(httpPreviewUrls) { mutableIntStateOf(0) }
        val chosenBaseUrl = httpPreviewUrls.getOrElse(selectedUrlIdx) { httpPreviewUrls.first() }
        val qrHttpUrl = remember(chosenBaseUrl) { "${chosenBaseUrl.trimEnd('/')}/" }
        val qrResult = remember(qrHttpUrl) {
            runCatching { QrCodeGenerator.encode(qrHttpUrl) }
        }

        AlertDialog(
            onDismissRequest = { showHttpQrDialog = false },
            title = { Text("HTTP Share QR Code") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Scan with any phone camera or open in a browser on the same Wi-Fi / hotspot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    if (httpPreviewUrls.size > 1) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            httpPreviewUrls.forEachIndexed { idx, u ->
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
                                .widthIn(max = 240.dp)
                                .fillMaxWidth(0.85f)
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
                    } else {
                        Text(
                            text = "URL is too long to encode into QR code (${qrHttpUrl.length} characters).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }

                    Text(
                        text = qrHttpUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                UiFormatters.copyToSystemClipboard(context, "HTTP URL", qrHttpUrl)
                                viewModel.emitSimpleMessage("Copied $qrHttpUrl")
                            },
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showHttpQrDialog = false }) {
                    Text("Close")
                }
            },
        )
    }

    if (showHttpPortDialog) {
        PortEditDialog(
            title = "HTTP Port",
            initialPort = settings.httpPort,
            onSave = { p ->
                viewModel.container.settingsRepository.update { it.copy(httpPort = p) }
                showHttpPortDialog = false
            },
            onDismiss = { showHttpPortDialog = false },
        )
    }

    if (showHttpRootDialog) {
        RootFolderPickerDialog(
            title = "HTTP Root Folder",
            volumes = volumes,
            favorites = favorites,
            onSelect = { path ->
                viewModel.container.settingsRepository.update { it.copy(httpRoot = path.serialize()) }
                showHttpRootDialog = false
            },
            onDismiss = { showHttpRootDialog = false },
        )
    }

    if (showFtpPortDialog) {
        PortEditDialog(
            title = "FTP Port",
            initialPort = settings.ftpPort,
            onSave = { p ->
                viewModel.container.settingsRepository.update { it.copy(ftpPort = p) }
                showFtpPortDialog = false
            },
            onDismiss = { showFtpPortDialog = false },
        )
    }

    if (showFtpRootDialog) {
        RootFolderPickerDialog(
            title = "FTP Root Folder",
            volumes = volumes,
            favorites = favorites,
            onSelect = { path ->
                viewModel.container.settingsRepository.update { it.copy(ftpRoot = path.serialize()) }
                showFtpRootDialog = false
            },
            onDismiss = { showFtpRootDialog = false },
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
private fun PortEditDialog(
    title: String,
    initialPort: Int,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var portText by remember { mutableStateOf(initialPort.toString()) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = portText,
                    onValueChange = {
                        portText = it
                        err = null
                    },
                    label = { Text("Port (1024–65535)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (err != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(err!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val p = portText.trim().toIntOrNull()
                    if (p == null || p !in 1024..65535) {
                        err = "Enter a valid port between 1024 and 65535"
                    } else {
                        onSave(p)
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
                items(favorites, key = { "fav-${it.id}" }) { fav ->
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
