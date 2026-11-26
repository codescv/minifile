package com.minifile.explorer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.minifile.explorer.core.i18n.Str
import com.minifile.explorer.core.peer.PeerInfo
import com.minifile.explorer.ui.JoinUiState
import com.minifile.explorer.ui.i18n.LocalAppStrings
import com.minifile.explorer.ui.util.SystemWifiJoin

/**
 * Scheme B joiner progress (spec §3.7): joining → finding host → found "<name>" → Send.
 * [sendLabel] is shown when a send can follow immediately (e.g. opened from Browse ▸ Send to device…).
 * When the in-app Wi-Fi join itself failed ([JoinUiState.Failed.wifiFallback]), offers "Join via System Wi-Fi"
 * (Android 11+ add-network sheet) and "Copy password"; Files finds the host automatically once Wi-Fi connects.
 */
@Composable
fun JoinHotspotDialog(
    state: JoinUiState,
    onSend: (PeerInfo) -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    sendLabel: String? = null,
) {
    val s = LocalAppStrings.current
    val effectiveSendLabel = sendLabel ?: s[Str.SEND_FILES]
    val busy = state is JoinUiState.Joining || state is JoinUiState.FindingHost
    val context = LocalContext.current
    val fallback = (state as? JoinUiState.Failed)?.wifiFallback
    AlertDialog(
        onDismissRequest = {
            when (state) {
                // Spec §3.3 [v2]: Back on Found must disconnect rather than stranding Wi-Fi on the hotspot.
                is JoinUiState.Found -> onCancel()
                is JoinUiState.Failed -> onClose()
                is JoinUiState.Joining, is JoinUiState.FindingHost -> Unit
            }
        },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = false),
        icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
        title = { Text(s[Str.SCAN_QR_TO_JOIN]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is JoinUiState.Joining -> ProgressLine(s.format(Str.JOINING_WIFI_FMT, state.ssid))
                    is JoinUiState.FindingHost -> ProgressLine(s.format(Str.FINDING_DEVICE_FMT, state.label))
                    is JoinUiState.Found -> {
                        Text(
                            text = s.format(Str.CONNECTED_TO_FMT, state.peer.name),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = state.peer.displayEndpoint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is JoinUiState.Failed -> {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (fallback != null) {
                            Text(
                                text = s.format(Str.JOIN_FALLBACK_HINT, fallback.ssid),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (fallback.password.isNotEmpty()) {
                                TextButton(onClick = { SystemWifiJoin.copyPassword(context, fallback.password) }) {
                                    Text(s[Str.COPY_PASSWORD])
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is JoinUiState.Found -> Button(onClick = { onSend(state.peer) }) { Text(effectiveSendLabel) }
                is JoinUiState.Failed -> if (fallback != null) {
                    Button(
                        onClick = {
                            SystemWifiJoin.launch(context, fallback.ssid, fallback.password, fallback.sec)
                            onClose()
                        },
                    ) { Text(if (SystemWifiJoin.isAddNetworkSupported) s[Str.JOIN_VIA_SYSTEM_WIFI] else s[Str.OPEN_WIFI_SETTINGS]) }
                } else {
                    TextButton(onClick = onClose) { Text(s[Str.CLOSE]) }
                }
                else -> Unit
            }
        },
        dismissButton = {
            when (state) {
                is JoinUiState.Found -> TextButton(onClick = onCancel) { Text(s[Str.DISCONNECT]) }
                is JoinUiState.Failed -> if (fallback != null) TextButton(onClick = onClose) { Text(s[Str.CLOSE]) }
                else -> TextButton(onClick = onCancel) { Text(s[Str.CANCEL]) }
            }
        },
    )
}

@Composable
private fun ProgressLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
