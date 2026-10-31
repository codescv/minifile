package com.minifile.explorer.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.minifile.explorer.BuildConfig
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.data.AndroidVolumeProvider
import com.minifile.explorer.data.update.InstallLaunchResult
import com.minifile.explorer.data.update.UpdateUiState
import com.minifile.explorer.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    viewModel: MainViewModel,
    onGrantAllFilesAccess: () -> Unit,
    onRequestUnknownSourcesPermission: (Intent) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val updateState by viewModel.container.updateManager.state.collectAsState()
    var showNomediaScanner by remember { mutableStateOf(false) }

    val hasAllFiles = AndroidVolumeProvider.hasAllFilesAccess(context)

    fun triggerInstall() {
        when (val res = viewModel.container.updateManager.launchInstaller(context)) {
            InstallLaunchResult.Launched -> Unit
            is InstallLaunchResult.NeedsUnknownSourcesPermission -> onRequestUnknownSourcesPermission(res.settingsIntent)
            is InstallLaunchResult.Failed -> viewModel.emitSimpleMessage(res.message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // 1. Storage Access (All-files access only)
            item(key = "storage-access") {
                SectionLabel("Storage access")
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("All-files access") },
                            supportingContent = {
                                Text(if (hasAllFiles) "Granted" else "Not granted")
                            },
                            trailingContent = {
                                OutlinedButton(onClick = onGrantAllFilesAccess) {
                                    Text(if (hasAllFiles) "Manage" else "Grant")
                                }
                            },
                        )
                    }
                }
            }

            // 2. Utilities (.nomedia Overview only)
            item(key = "utilities") {
                SectionLabel("Utilities")
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(".nomedia Overview") },
                            supportingContent = { Text("Scan folders hidden from gallery and toggle in bulk") },
                            modifier = Modifier.clickable { showNomediaScanner = true },
                        )
                    }
                }
            }

            // 3. About (with integrated Update check & install)
            item(key = "about") {
                SectionLabel("About")
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Text(
                            "Files ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Minimalist file manager with wireless HTTP/FTP transfer, PDF viewer, and PC Companion.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = {
                                val open = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/codescv/minifile"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(open) }
                            },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
                        ) {
                            Text("github.com/codescv/minifile")
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        }

                        when (val st = updateState) {
                            UpdateUiState.Idle -> Unit

                            UpdateUiState.Checking -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Checking…", style = MaterialTheme.typography.bodySmall)
                                }
                            }

                            is UpdateUiState.UpToDate -> {
                                Text(
                                    "Up to date",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                            }

                            is UpdateUiState.Available -> {
                                val rawTag = st.release.tagName.trim()
                                val tagLabel = if (rawTag.startsWith("v", ignoreCase = true)) rawTag else "v$rawTag"
                                Text(
                                    "New version $tagLabel available",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }

                            is UpdateUiState.Downloading -> {
                                val frac = if (st.totalBytes > 0L) {
                                    (st.downloadedBytes.toFloat() / st.totalBytes.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    null
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Downloading…", style = MaterialTheme.typography.bodySmall)
                                        Spacer(Modifier.height(4.dp))
                                        if (frac != null) {
                                            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                                        } else {
                                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                        }
                                    }
                                    IconButton(onClick = { viewModel.container.updateManager.cancel() }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Cancel download")
                                    }
                                }
                            }

                            is UpdateUiState.Verifying -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Verifying…", style = MaterialTheme.typography.bodySmall)
                                }
                            }

                            is UpdateUiState.Ready -> {
                                val rawTag = st.release.tagName.trim()
                                val tagLabel = if (rawTag.startsWith("v", ignoreCase = true)) rawTag else "v$rawTag"
                                Text(
                                    "New version $tagLabel available",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }

                            is UpdateUiState.Error -> {
                                Text(
                                    st.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.container.updateManager.check(force = true) },
                                enabled = updateState !is UpdateUiState.Checking &&
                                    updateState !is UpdateUiState.Downloading &&
                                    updateState !is UpdateUiState.Verifying,
                            ) {
                                Text("Check for updates", maxLines = 1, softWrap = false)
                            }

                            if (updateState is UpdateUiState.Available || updateState is UpdateUiState.Ready) {
                                Button(
                                    onClick = {
                                        when (updateState) {
                                            is UpdateUiState.Available -> {
                                                viewModel.container.updateManager.download(
                                                    onReadyToInstall = { triggerInstall() },
                                                )
                                            }
                                            is UpdateUiState.Ready -> triggerInstall()
                                            else -> Unit
                                        }
                                    },
                                ) {
                                    Text("Download & Install", maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNomediaScanner) {
        var scanning by remember { mutableStateOf(true) }
        var foundDirs by remember { mutableStateOf<List<VfsPath>>(emptyList()) }

        LaunchedEffect(Unit) {
            scanning = true
            val root = VfsPath.Local(Environment.getExternalStorageDirectory().absolutePath)
            foundDirs = withContext(Dispatchers.IO) {
                runCatching { viewModel.container.nomediaManager.scan(root, maxDepth = 5) }.getOrDefault(emptyList())
            }
            scanning = false
        }

        AlertDialog(
            onDismissRequest = { showNomediaScanner = false },
            title = { Text(".nomedia Overview") },
            text = {
                if (scanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Scanning storage for .nomedia folders…")
                    }
                } else if (foundDirs.isEmpty()) {
                    Text("No folders with .nomedia found.")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(foundDirs, key = { it.serialize() }) { dirPath ->
                            ListItem(
                                headlineContent = { Text(dirPath.displayName) },
                                supportingContent = {
                                    Text((dirPath as? VfsPath.Local)?.normalized ?: dirPath.serialize(), maxLines = 1)
                                },
                                trailingContent = {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    viewModel.container.nomediaManager.set(dirPath, false)
                                                }
                                                foundDirs = foundDirs.filterNot { it == dirPath }
                                            }
                                        },
                                    ) {
                                        Text("Unhide")
                                    }
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showNomediaScanner = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}
