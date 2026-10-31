package com.minifile.explorer.ui.components

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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
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
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.ui.MainViewModel
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
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
                        text = "No devices yet — on the other phone open Transfer and start HTTP Share.",
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
                    items(peers, key = { "${it.id}-${it.endpoint}" }) { peer ->
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

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

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
}

@Composable
fun PeerDeviceRow(
    peer: PeerInfo,
    onClick: (() -> Unit)? = null,
    trailingActionLabel: String? = null,
    onTrailingAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val cleanName = remember(peer.name, peer.host) {
        PeerPathPolicy.sanitizeDisplayName(peer.name, 64).ifEmpty { peer.host }
    }
    val subtitle = remember(peer.endpoint, peer.readOnly) {
        if (peer.readOnly) "${peer.endpoint} · Read-only" else peer.endpoint
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
                    imageVector = Icons.Filled.PhoneAndroid,
                    contentDescription = null,
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
