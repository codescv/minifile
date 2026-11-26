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
import com.minifile.explorer.core.i18n.Str
import com.minifile.explorer.service.BleLinkRole
import com.minifile.explorer.service.BleLinkUiState
import com.minifile.explorer.service.IncomingBleRequest
import com.minifile.explorer.ui.i18n.LocalAppStrings
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
    val s = LocalAppStrings.current
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
        append(s.filesCount(request.files))
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
        title = { Text(s.format(Str.PEER_WANTS_TO_SEND, request.fromName, what)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (platform != null) {
                        s.format(Str.PEER_CONSENT_DESC_PLATFORM, platform)
                    } else {
                        s[Str.PEER_CONSENT_DESC_GENERIC]
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    text = s.format(Str.AUTO_DECLINES_IN, remainingSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = onAccept) { Text(s[Str.ACCEPT]) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(s[Str.DECLINE]) } },
    )
}

/** Progress (and, for iPhone peers, the `WIFI:` QR) while a BLE-negotiated hotspot link is set up. */
@Composable
fun HotspotLinkDialog(
    state: BleLinkUiState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    val done = state.finished || state.error != null
    AlertDialog(
        onDismissRequest = { if (done) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = done, dismissOnClickOutside = false),
        title = {
            Text(
                text = when (state.role) {
                    BleLinkRole.SENDER -> s.format(Str.SENDING_TO, state.peerName)
                    BleLinkRole.RECEIVER -> s.format(Str.RECEIVING_FROM, state.peerName)
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
                val progress = state.progress
                if (progress != null && !done) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
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
                TextButton(onClick = onDismiss) { Text(s[Str.CLOSE]) }
            } else {
                TextButton(onClick = onCancel) { Text(s[Str.CANCEL]) }
            }
        },
    )
}
