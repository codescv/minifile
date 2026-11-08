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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.minifile.explorer.core.peer.PeerInfo
import com.minifile.explorer.ui.JoinUiState

/**
 * Scheme B joiner progress (spec §3.7): joining → finding host → found "<name>" → Send.
 * [sendLabel] is shown when a send can follow immediately (e.g. opened from Browse ▸ Send to device…).
 */
@Composable
fun JoinHotspotDialog(
    state: JoinUiState,
    onSend: (PeerInfo) -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    sendLabel: String = "Send files",
) {
    val busy = state is JoinUiState.Joining || state is JoinUiState.FindingHost
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = false),
        icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
        title = { Text("Scan QR to Join") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is JoinUiState.Joining -> ProgressLine("Joining Wi-Fi “${state.ssid}”… tap Connect if Android asks")
                    is JoinUiState.FindingHost -> ProgressLine("Finding the Files device on ${state.label}…")
                    is JoinUiState.Found -> {
                        Text(
                            text = "Connected to ${state.peer.name}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = state.peer.displayEndpoint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is JoinUiState.Failed -> Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            when (state) {
                is JoinUiState.Found -> Button(onClick = { onSend(state.peer) }) { Text(sendLabel) }
                is JoinUiState.Failed -> TextButton(onClick = onClose) { Text("Close") }
                else -> Unit
            }
        },
        dismissButton = {
            when (state) {
                is JoinUiState.Found -> TextButton(onClick = onCancel) { Text("Disconnect") }
                is JoinUiState.Failed -> Unit
                else -> TextButton(onClick = onCancel) { Text("Cancel") }
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
