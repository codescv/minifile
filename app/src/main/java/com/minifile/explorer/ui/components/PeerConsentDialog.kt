package com.minifile.explorer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.minifile.explorer.service.BleLinkRole
import com.minifile.explorer.service.BleLinkUiState
import com.minifile.explorer.service.IncomingBleRequest
import com.minifile.explorer.ui.util.NearbyStatusCopy
import com.minifile.explorer.ui.util.UiFormatters
import kotlinx.coroutines.delay

/**
 * Incoming BLE transfer request (spec §2.3 step 2): "<name> wants to send 3 files (1.2 MB)" with a 60 s
 * countdown. Hosted at MainActivity level so it appears from any tab.
 */
@Composable
fun PeerConsentDialog(
    request: IncomingBleRequest,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val context = LocalContext.current
    var nowMs by remember(request.requestId) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(request.requestId) {
        while (true) {
            nowMs = System.currentTimeMillis()
            if (nowMs >= request.deadlineMs) break
            delay(500)
        }
    }
    val remainingSec = ((request.deadlineMs - nowMs) / 1000L).coerceAtLeast(0L)
    val totalMs = 60_000f
    val fraction = ((request.deadlineMs - nowMs) / totalMs).coerceIn(0f, 1f)
    val what = buildString {
        append(if (request.files == 1) "1 file" else "${request.files} files")
        if (request.bytes > 0L) append(" (").append(UiFormatters.formatSize(context, request.bytes)).append(")")
    }
    val platform = when (request.os) {
        "ios" -> "iPhone"
        "android" -> "Android"
        else -> null
    }

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        icon = { Icon(Icons.Filled.Bluetooth, contentDescription = null) },
        title = { Text("${request.fromName} wants to send $what") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = buildString {
                        append("Nearby")
                        if (platform != null) append(" ").append(platform)
                        append(" via Bluetooth. Files are saved to Received. ")
                        append("A temporary Wi-Fi link may be created if you are not on the same network.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    text = "Auto-declines in $remainingSec s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = onAccept) { Text("Accept") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Decline") } },
    )
}

/** Progress (and, for iPhone peers, the `WIFI:` QR) while a BLE-negotiated hotspot link is set up. */
@Composable
fun HotspotLinkDialog(
    state: BleLinkUiState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val done = state.finished || state.error != null
    AlertDialog(
        onDismissRequest = { if (done) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = done, dismissOnClickOutside = false),
        title = {
            Text(
                text = when (state.role) {
                    BleLinkRole.SENDER -> "Sending to ${state.peerName}"
                    BleLinkRole.RECEIVER -> "Receiving from ${state.peerName}"
                },
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!done) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        text = state.error ?: state.phase,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
                val hs = state.hotspot
                // Android ↔ Android joins automatically (BLE + WifiNetworkSpecifier): QR-free by default, with a
                // one-tap escape hatch for OEM ROMs that reject the specifier request.
                var qrRequested by remember(hs?.ssid) { mutableStateOf(false) }
                if (hs != null && (state.showQr || (qrRequested && state.qrFallback && !done))) {
                    HotspotCredentialsPanel(info = hs)
                } else if (hs != null && state.qrFallback && !done) {
                    TextButton(onClick = { qrRequested = true }) {
                        Text(NearbyStatusCopy.SHOW_QR_INSTEAD)
                    }
                }
            }
        },
        confirmButton = {
            if (done) {
                TextButton(onClick = onDismiss) { Text("Close") }
            } else {
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}
