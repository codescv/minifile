package com.minifile.explorer.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
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
import androidx.compose.material.icons.filled.Shield
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.minifile.explorer.ui.MainViewModel
import com.minifile.explorer.ui.util.QrCodeGenerator
import com.minifile.explorer.ui.util.UiFormatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import kotlin.math.floor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WirelessScreen(
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val settings by viewModel.settings.collectAsState()
    val volumes by viewModel.volumes.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val ftpState by FtpServerHolder.state.collectAsState()
    val lanEndpoints by viewModel.container.lanAddressResolver.observeEndpoints()
        .collectAsState(initial = viewModel.container.lanAddressResolver.currentEndpoints())

    var showPortDialog by remember { mutableStateOf(false) }
    var showRootDialog by remember { mutableStateOf(false) }
    var showAuthDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Start foreground FTP service regardless of whether notification permission was granted
        FtpServerService.start(context)
    }

    fun startFtpServer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            FtpServerService.start(context)
        }
    }

    val rootDisplayLabel = remember(settings.ftpRoot, volumes, favorites) {
        val raw = settings.ftpRoot ?: return@remember "Internal"
        val parsed = runCatching { VfsPath.parse(raw) }.getOrNull() ?: return@remember "Internal"
        volumes.firstOrNull { it.root == parsed }?.name
            ?: favorites.firstOrNull { it.path == parsed }?.let { "★ ${it.name}" }
            ?: parsed.displayName
    }

    val previewUrls = if (ftpState.isRunning) {
        ftpState.urls
    } else if (lanEndpoints.isNotEmpty()) {
        lanEndpoints.map { "ftp://${it.ipv4}:${settings.ftpPort}" }
    } else {
        listOf("ftp://127.0.0.1:${settings.ftpPort}")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wireless Transfer", fontWeight = FontWeight.SemiBold) },
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
            // Status Card
            item(key = "status-card") {
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

                        for (url in previewUrls) {
                            Text(
                                text = url,
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        if (lanEndpoints.isEmpty()) {
                            Text(
                                text = "No Wi-Fi LAN IPv4 detected — server reachable via hotspot, USB tethering, or adb forward.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            val ifaceDesc = lanEndpoints.joinToString(" · ") { "${it.categoryLabel} (${it.interfaceName})" }
                            Text(
                                text = ifaceDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (ftpState.lastError != null) {
                            Text(
                                text = ftpState.lastError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        val primaryUrl = previewUrls.first()
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
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

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        UiFormatters.copyToSystemClipboard(context, "FTP URL", primaryUrl)
                                        viewModel.emitSimpleMessage("Copied $primaryUrl")
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Copy URL", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                }
                                OutlinedButton(
                                    onClick = { showQrDialog = true },
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

            // LAN-only firewall & plaintext notice
            item(key = "ftp-warning") {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Icon(
                            Icons.Filled.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Local network only — connections from cellular/public WAN are blocked. Use on trusted Wi-Fi or hotspot.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Simplified Settings card
            item(key = "ftp-settings") {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
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
                            onClick = { showPortDialog = true },
                        )
                        HorizontalDivider()
                        SettingRow(
                            title = "Root folder",
                            value = rootDisplayLabel,
                            enabled = !ftpState.isRunning,
                            onClick = { showRootDialog = true },
                        )
                        HorizontalDivider()
                        SettingRow(
                            title = "Authentication",
                            value = if (settings.ftpAnonymous) "Anonymous" else "User: ${settings.ftpUsername}",
                            enabled = !ftpState.isRunning,
                            onClick = { showAuthDialog = true },
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

            // Live Activity Log
            item(key = "ftp-log-header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Activity (${ftpState.log.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val content = FtpServerHolder.exportLogText()
                                    val saved = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val dl = File(Environment.getExternalStorageDirectory(), "Download")
                                            dl.mkdirs()
                                            val f = File(dl, "minifile-ftp.log")
                                            f.writeText(content)
                                            f.absolutePath
                                        }.getOrNull()
                                    }
                                    if (saved != null) {
                                        viewModel.emitSimpleMessage("Exported FTP log to $saved")
                                    } else {
                                        UiFormatters.copyToSystemClipboard(context, "FTP Log", content)
                                        viewModel.emitSimpleMessage("Copied FTP log to clipboard")
                                    }
                                }
                            },
                            enabled = ftpState.log.isNotEmpty(),
                        ) {
                            Text("Export")
                        }
                        TextButton(
                            onClick = { FtpServerHolder.clearLog() },
                            enabled = ftpState.log.isNotEmpty(),
                        ) {
                            Text("Clear")
                        }
                    }
                }
            }

            if (ftpState.log.isEmpty()) {
                item(key = "ftp-log-empty") {
                    Text(
                        text = "No FTP activity yet. Start the server and connect from MiniFile PC Companion or any FTP client.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(ftpState.log.asReversed(), key = { it.id }) { entry ->
                    Text(
                        text = "${entry.formattedTime}  ${entry.clientIp}  ${entry.summary}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                }
            }
        }
    }

    if (showQrDialog) {
        var selectedUrlIdx by remember(previewUrls) { mutableIntStateOf(0) }
        val chosenBaseUrl = previewUrls.getOrElse(selectedUrlIdx) { previewUrls.first() }
        val fullQrUrl = remember(chosenBaseUrl, settings.ftpAnonymous, settings.ftpUsername, settings.ftpPassword) {
            buildFullFtpUrl(
                baseUrl = chosenBaseUrl,
                anonymous = settings.ftpAnonymous,
                username = settings.ftpUsername,
                password = settings.ftpPassword,
            )
        }
        val qrResult = remember(fullQrUrl) {
            runCatching { QrCodeGenerator.encode(fullQrUrl) }
        }

        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text("FTP QR Code") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (previewUrls.size > 1) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            previewUrls.forEachIndexed { idx, u ->
                                val hostLabel = u.removePrefix("ftp://").substringBefore(':')
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
                            text = "URL is too long to encode into QR code (${fullQrUrl.length} characters).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }

                    Text(
                        text = fullQrUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                UiFormatters.copyToSystemClipboard(context, "FTP URL", fullQrUrl)
                                viewModel.emitSimpleMessage("Copied $fullQrUrl")
                            },
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showQrDialog = false }) {
                    Text("Close")
                }
            },
        )
    }

    if (showPortDialog) {
        var portText by remember { mutableStateOf(settings.ftpPort.toString()) }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showPortDialog = false },
            title = { Text("FTP Port") },
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
                            viewModel.container.settingsRepository.update { it.copy(ftpPort = p) }
                            showPortDialog = false
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showPortDialog = false }) { Text("Cancel") } },
        )
    }

    if (showRootDialog) {
        AlertDialog(
            onDismissRequest = { showRootDialog = false },
            title = { Text("FTP Root Folder") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    val readyVolumes = volumes.filter { it.root != null }
                    items(readyVolumes, key = { it.id }) { vol ->
                        ListItem(
                            headlineContent = { Text(vol.name) },
                            supportingContent = vol.subtitle?.let { { Text(it, maxLines = 1) } },
                            modifier = Modifier.clickable {
                                viewModel.container.settingsRepository.update {
                                    it.copy(ftpRoot = vol.root!!.serialize())
                                }
                                showRootDialog = false
                            },
                        )
                    }
                    items(favorites, key = { "fav-${it.id}" }) { fav ->
                        ListItem(
                            headlineContent = { Text("★ ${fav.name}") },
                            supportingContent = { Text(fav.path.displayName) },
                            modifier = Modifier.clickable {
                                viewModel.container.settingsRepository.update {
                                    it.copy(ftpRoot = fav.path.serialize())
                                }
                                showRootDialog = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRootDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showAuthDialog) {
        var anon by remember { mutableStateOf(settings.ftpAnonymous) }
        var user by remember { mutableStateOf(settings.ftpUsername) }
        var pass by remember { mutableStateOf(settings.ftpPassword) }
        var showPass by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAuthDialog = false },
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
                        showAuthDialog = false
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAuthDialog = false }) { Text("Cancel") } },
        )
    }
}

internal fun buildFullFtpUrl(
    baseUrl: String,
    anonymous: Boolean,
    username: String,
    password: String,
): String {
    val hostPort = baseUrl.trim().removePrefix("ftp://").trimEnd('/')
    if (anonymous) {
        return "ftp://$hostPort/"
    }
    val cleanUser = username.ifEmpty { "minifile" }
    val encUser = URLEncoder.encode(cleanUser, "UTF-8").replace("+", "%20")
    val encPass = URLEncoder.encode(password, "UTF-8").replace("+", "%20")
    return "ftp://$encUser:$encPass@$hostPort/"
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
