package com.minifile.explorer.ui.components

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minifile.explorer.core.peer.PeerInfo
import com.minifile.explorer.core.peer.PeerPathPolicy
import com.minifile.explorer.core.peer.PeerSource
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.ui.MainViewModel
import com.minifile.explorer.ui.util.NearbyPermissions
import com.minifile.explorer.ui.util.UiFormatters
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendToDeviceSheet(
    items: List<VfsNode>,
    viewModel: MainViewModel,
    onSelectPeer: (PeerInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val peers by viewModel.peers.collectAsState()
    val isScanning by viewModel.isScanningPeers.collectAsState()
    var showAddByAddressDialog by remember { mutableStateOf(false) }
    var showScanButton by remember { mutableStateOf(false) }
    var showHotspotSheet by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    val joinState by viewModel.joinState.collectAsState()

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

    val headerTitle = remember(items, context) {
        val single = items.singleOrNull()
        if (single != null) {
            if (!single.isDirectory && single.size >= 0L) {
                "Send ${single.name} · ${UiFormatters.formatSize(context, single.size)}"
            } else {
                "Send ${single.name}"
            }
        } else {
            val fileBytes = items.filter { !it.isDirectory && it.size > 0L }.sumOf { it.size }
            if (fileBytes > 0L) {
                "Send ${items.size} items · ${UiFormatters.formatSize(context, fileBytes)}"
            } else {
                "Send ${items.size} items"
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (joinState != null) viewModel.cancelJoin()
            onDismiss()
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                text = headerTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Files are saved in the receiver's Received folder",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            if (peers.isEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = if (isScanning) "Scanning local network…" else "Looking for nearby devices…",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Text(
                        text = "No devices yet — open Files ▸ Transfer on the other phone, or use Hotspot Direct / Scan QR below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    items(peers, key = { "${it.id}-${it.source}-${it.endpoint}" }) { peer ->
                        PeerDeviceRow(
                            peer = peer,
                            onClick = {
                                val displayName = PeerPathPolicy.sanitizeDisplayName(peer.name, 64)
                                    .ifEmpty { peer.host }
                                if (peer.readOnly) {
                                    viewModel.emitSimpleMessage("$displayName is read-only (Transfer ▸ Settings)")
                                } else {
                                    onSelectPeer(peer)
                                }
                            },
                        )
                    }
                }
            }

            NearbyAccessRow(viewModel = viewModel)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            DirectHotspotButtons(
                onHotspotDirect = { showHotspotSheet = true },
                onScanQr = { showQrScanner = true },
            )

            Spacer(Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = { showAddByAddressDialog = true },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add by address…")
                }

                if (showScanButton || isScanning) {
                    TextButton(
                        onClick = { viewModel.scanPeersSubnet() },
                        enabled = !isScanning,
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Scanning…")
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Scan")
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }

    if (showAddByAddressDialog) {
        AddPeerByAddressDialog(
            viewModel = viewModel,
            onAdded = { showAddByAddressDialog = false },
            onDismiss = { showAddByAddressDialog = false },
        )
    }

    if (showHotspotSheet) {
        HotspotDirectSheet(
            viewModel = viewModel,
            onDismiss = { showHotspotSheet = false },
            onSendToPeer = { peer ->
                showHotspotSheet = false
                onSelectPeer(peer)
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
                onSelectPeer(peer)
            },
            onCancel = { viewModel.cancelJoin() },
            onClose = { viewModel.dismissJoinDialog() },
        )
    }
}

/** "Hotspot Direct (热点直连)" + "Scan QR (扫码直连)" — Scheme B entry points (spec §3.7). */
@Composable
fun DirectHotspotButtons(
    onHotspotDirect: () -> Unit,
    onScanQr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedButton(
            onClick = onHotspotDirect,
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Filled.WifiTethering, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Column {
                Text("Hotspot Direct", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Text("热点直连", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedButton(
            onClick = onScanQr,
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Column {
                Text("Scan QR", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Text("扫码直连", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Hint + one-tap fix when Bluetooth discovery can't run (permissions, Bluetooth off, or Location off on
 * Android ≤ 12). Hidden when everything is ready or the device has no BLE.
 */
@Composable
fun NearbyAccessRow(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    val hint = remember(refreshTick) {
        if (!NearbyPermissions.hasBleHardware(context)) null else NearbyPermissions.nearbyHint(context)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshTick++
        viewModel.onNearbyPermissionsChanged()
    }
    val enableBtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshTick++
        viewModel.onNearbyPermissionsChanged()
    }
    if (hint == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Icon(
            Icons.Filled.Bluetooth,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            when {
                !NearbyPermissions.hasAllBlePermissions(context) ->
                    permLauncher.launch(NearbyPermissions.allNearbyPermissions())
                !NearbyPermissions.isBluetoothOn(context) -> try {
                    enableBtLauncher.launch(NearbyPermissions.bluetoothEnableIntent())
                } catch (_: Exception) {
                    refreshTick++
                }
                else -> try {
                    context.startActivity(NearbyPermissions.locationSettingsIntent())
                } catch (_: ActivityNotFoundException) {
                }
            }
        }) {
            Text(
                when {
                    !NearbyPermissions.hasAllBlePermissions(context) -> "Allow"
                    !NearbyPermissions.isBluetoothOn(context) -> "Turn on"
                    else -> "Settings"
                },
            )
        }
    }
}

@Composable
fun PeerDeviceRow(
    peer: PeerInfo,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailingActionLabel: String? = null,
    onTrailingAction: (() -> Unit)? = null,
) {
    val cleanName = remember(peer.name, peer.host) {
        PeerPathPolicy.sanitizeDisplayName(peer.name, 64).ifEmpty { peer.host }
    }
    val subtitle = remember(peer.displayEndpoint, peer.readOnly, peer.source, peer.platform) {
        val base = when {
            peer.source == PeerSource.BLE -> "Bluetooth · Direct" + when (peer.platform) {
                "ios" -> " · iPhone"
                "android" -> " · Android"
                else -> ""
            }
            peer.source == PeerSource.QR || peer.hotspot -> "${peer.displayEndpoint} · Hotspot"
            else -> peer.displayEndpoint
        }
        if (peer.readOnly) "$base · Read-only" else base
    }
    val leadingIcon = when {
        peer.source == PeerSource.BLE -> Icons.Filled.Bluetooth
        peer.platform == "ios" -> Icons.Filled.PhoneIphone
        else -> Icons.Filled.PhoneAndroid
    }

    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (peer.readOnly) {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                    ),
            ) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = if (peer.source == PeerSource.BLE) "Bluetooth" else null,
                    tint = if (peer.readOnly) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
        },
        headlineContent = {
            Text(
                text = cleanName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            when {
                trailingActionLabel != null && onTrailingAction != null -> {
                    if (peer.readOnly) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = "Read-only",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Read-only",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Button(
                            onClick = onTrailingAction,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(trailingActionLabel)
                        }
                    }
                }

                peer.readOnly -> {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Read-only",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (peer.readOnly && trailingActionLabel == null) 0.55f else 1f)
            .let { base ->
                if (onClick != null) base.clickable(onClick = onClick) else base
            },
    )
}

@Composable
fun AddPeerByAddressDialog(
    viewModel: MainViewModel,
    onAdded: (PeerInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    var addressInput by remember { mutableStateOf("") }
    var isProbing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (isProbing) return
        errorMessage = null
        isProbing = true
        viewModel.addPeerByAddress(
            rawAddress = addressInput,
            onSuccess = { peer ->
                isProbing = false
                onAdded(peer)
            },
            onError = { err ->
                isProbing = false
                errorMessage = err
            },
        )
    }

    AlertDialog(
        onDismissRequest = { if (!isProbing) onDismiss() },
        title = { Text("Add device by address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Enter the other device's IP address and port (default port 8080).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = addressInput,
                    onValueChange = {
                        addressInput = it
                        errorMessage = null
                    },
                    label = { Text("Address (host[:port])") },
                    placeholder = { Text("192.168.1.42:8080") },
                    singleLine = true,
                    enabled = !isProbing,
                    isError = errorMessage != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { submit() },
                enabled = !isProbing && addressInput.isNotBlank(),
            ) {
                if (isProbing) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Connecting…")
                } else {
                    Text("Add")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isProbing,
            ) {
                Text("Cancel")
            }
        },
    )
}
