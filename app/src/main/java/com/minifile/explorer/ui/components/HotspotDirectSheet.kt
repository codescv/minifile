package com.minifile.explorer.ui.components

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minifile.explorer.core.peer.HostDecision
import com.minifile.explorer.core.peer.PeerInfo
import com.minifile.explorer.core.qr.QrPayload
import com.minifile.explorer.core.qr.QrPayloads
import com.minifile.explorer.service.HotspotInfo
import com.minifile.explorer.service.HotspotState
import com.minifile.explorer.ui.MainViewModel
import com.minifile.explorer.ui.util.NearbyPermissions
import com.minifile.explorer.ui.util.NearbyStatusCopy
import com.minifile.explorer.ui.util.QrCodeGenerator
import com.minifile.explorer.ui.util.UiFormatters
import kotlin.math.floor

/**
 * Scheme B host: "Hotspot Direct" — starts a LocalOnlyHotspot + HTTP share and shows the universal
 * `WIFI:` QR (iPhone Camera / Android camera / Files scanner), SSID, password (copy), `http://<ip>:<port>/`,
 * devices that joined, and Stop (spec §2.5, §3.7).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotspotDirectSheet(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onSendToPeer: ((PeerInfo) -> Unit)? = null,
) {
    val context = LocalContext.current
    val state by viewModel.hotspotState.collectAsState()
    val peers by viewModel.peers.collectAsState()
    var filesOnly by rememberSaveable { mutableStateOf(false) }
    var permissionHint by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        viewModel.startPeerBrowsing()
        onDispose { viewModel.stopPeerBrowsing() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        if (NearbyPermissions.hasHotspotPermissions(context)) {
            permissionHint = null
            viewModel.startHotspotDirect(filesOnly)
        } else {
            permissionHint = "Permission needed to start a hotspot"
        }
    }

    fun start() {
        when {
            !NearbyPermissions.hasHotspotPermissions(context) ->
                permissionLauncher.launch(NearbyPermissions.hotspotPermissions())
            NearbyPermissions.needsLocationServices(context) ->
                permissionHint = "Location must be on for Android ≤12 to start a hotspot"
            else -> {
                permissionHint = null
                viewModel.startHotspotDirect(filesOnly)
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.WifiTethering, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Hotspot Direct", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        NearbyStatusCopy.HOTSPOT_DIRECT_SUBTITLE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when (val st = state) {
                is HotspotState.Active -> {
                    HotspotCredentialsPanel(info = st.info, filesOnlyQr = filesOnly)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("Files-only QR", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Adds a one-time token; only Files can scan it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = filesOnly,
                            onCheckedChange = {
                                filesOnly = it
                                viewModel.startHotspotDirect(it)
                            },
                        )
                    }
                    val joined = remember(peers, st.info) {
                        peers.filter { p ->
                            !p.needsLink && st.info.hostIp.isNotEmpty() &&
                                HostDecision.inSubnet(p.host, st.info.hostIp, st.info.prefixLength)
                        }
                    }
                    HorizontalDivider()
                    Text(
                        text = if (joined.isEmpty()) "Waiting for devices to join…" else "Joined devices",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (p in joined) {
                        PeerDeviceRow(
                            peer = p,
                            trailingActionLabel = if (onSendToPeer != null) "Send" else null,
                            onTrailingAction = onSendToPeer?.let { cb -> { cb(p) } },
                        )
                    }
                    Button(
                        onClick = { viewModel.stopHotspotDirect() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Stop hotspot") }
                }

                HotspotState.Starting -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 24.dp)) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Starting hotspot…")
                    }
                }

                is HotspotState.Failed, HotspotState.Idle -> {
                    Text(
                        "This phone creates a temporary Wi-Fi network. The other phone scans the QR code " +
                            "(iPhone: Camera app) to join, then sends or browses files — even without the app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val err = (st as? HotspotState.Failed)?.message ?: permissionHint
                    if (err != null) {
                        Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (NearbyPermissions.needsLocationServices(context)) {
                            OutlinedButton(onClick = {
                                try {
                                    context.startActivity(NearbyPermissions.locationSettingsIntent())
                                } catch (_: ActivityNotFoundException) {
                                }
                            }) { Text("Open Location settings") }
                        }
                    }
                    Button(
                        onClick = { start() },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Start hotspot", fontWeight = FontWeight.SemiBold) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** QR + SSID + password (copy) + URL for an active hotspot (also used by the BLE link dialog). */
@Composable
fun HotspotCredentialsPanel(
    info: HotspotInfo,
    modifier: Modifier = Modifier,
    filesOnlyQr: Boolean = false,
) {
    val context = LocalContext.current
    val qrText = remember(info, filesOnlyQr) { hotspotQrText(info, filesOnlyQr) }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            QrMatrixView(
                text = qrText,
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .fillMaxWidth(0.8f),
            )
            Text(
                text = if (filesOnlyQr) "Scan with Files on the other phone" else "Scan with the Camera app (iPhone / Android)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            CredentialRow(label = "Wi-Fi", value = info.ssid, onCopy = {
                UiFormatters.copyToSystemClipboard(context, "Wi-Fi name", info.ssid)
            })
            if (info.pass.isNotEmpty()) {
                CredentialRow(label = "Password", value = info.pass, onCopy = {
                    UiFormatters.copyToSystemClipboard(context, "Wi-Fi password", info.pass)
                })
            }
            info.url?.let { url ->
                CredentialRow(label = "Browser", value = url, onCopy = {
                    UiFormatters.copyToSystemClipboard(context, "URL", url)
                })
            }
        }
    }
}

@Composable
private fun CredentialRow(label: String, value: String, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCopy) {
            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy $label", modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Universal `WIFI:` QR (default) or the Files-only `minifile://hotspot` URI (spec §2.5). The Camera QR always uses
 * `T:WPA` for WPA2/WPA3 hotspots ([QrPayloads.buildCameraWifi]) — iOS Camera rejects `T:SAE`; the Files-only URI keeps
 * the exact `sec`.
 */
fun hotspotQrText(info: HotspotInfo, filesOnly: Boolean): String {
    if (filesOnly && info.token != null && info.hostIp.isNotEmpty()) {
        return QrPayloads.buildHotspotUri(
            QrPayload.Hotspot(
                ssid = info.ssid,
                password = info.pass,
                sec = if (info.sec == "open") "wpa2" else info.sec,
                ip = info.hostIp,
                port = info.httpPort,
                id = null,
                name = null,
                tok = info.token,
            ),
        )
    }
    return QrPayloads.buildCameraWifi(info.ssid, info.pass, info.sec)
}

/** Renders [text] as a black-on-white QR code with a 4-module quiet zone. */
@Composable
fun QrMatrixView(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { runCatching { QrCodeGenerator.encode(text) }.getOrNull() }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        shadowElevation = 2.dp,
        modifier = modifier.aspectRatio(1f),
    ) {
        if (matrix == null) {
            Text("QR too long", color = Color.Black, modifier = Modifier.padding(16.dp))
            return@Surface
        }
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
                            topLeft = Offset(originX + (x + quietZone) * cellPx, originY + (y + quietZone) * cellPx),
                            size = Size(cellPx, cellPx),
                        )
                    }
                }
            }
        }
    }
}
