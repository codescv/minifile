package com.minifile.explorer.ui.screens

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minifile.explorer.core.model.ClipboardState
import com.minifile.explorer.core.model.Favorite
import com.minifile.explorer.core.model.FtpServerBookmark
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.core.vfs.Volume
import com.minifile.explorer.core.vfs.VolumeKind
import com.minifile.explorer.ui.components.ClipboardBar
import com.minifile.explorer.ui.util.UiFormatters

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StorageHomeScreen(
    volumes: List<Volume>,
    favorites: List<Favorite>,
    ftpServers: List<FtpServerBookmark> = emptyList(),
    hasAllFilesAccess: Boolean,
    onboardingDismissed: Boolean,
    clipboard: ClipboardState?,
    onGrantAllFilesAccess: () -> Unit,
    onDismissOnboarding: () -> Unit,
    onMountVolumeSaf: (Volume) -> Unit,
    onOpenVolume: (Volume) -> Unit,
    onOpenFtpServer: (FtpServerBookmark) -> Unit = {},
    onAddFtpServer: (
        name: String,
        host: String,
        port: Int,
        anonymous: Boolean,
        username: String,
        password: String,
        initialPath: String,
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    onUpdateFtpServer: (FtpServerBookmark) -> Unit = {},
    onRemoveFtpServer: (String) -> Unit = {},
    onOpenQuickPath: (VfsPath, String) -> Unit,
    onOpenFavorite: (Favorite) -> Unit,
    onSeeAllFavorites: () -> Unit,
    onClipboardToFavorite: () -> Unit,
    onClearClipboard: () -> Unit,
) {
    val context = LocalContext.current
    val storageVolumes = volumes.filter {
        it.kind == VolumeKind.PRIMARY || it.kind == VolumeKind.SD_CARD || it.kind == VolumeKind.USB_OTG
    }
    val recentFavorites = favorites.sortedByDescending { it.lastUsedAt }.take(4)

    var showAddFtpDialog by remember { mutableStateOf(false) }
    var editingFtpServer by remember { mutableStateOf<FtpServerBookmark?>(null) }

    val primaryBase = Environment.getExternalStorageDirectory().absolutePath
    val quickAccess = listOf(
        Triple("Download", Icons.Filled.Download, "$primaryBase/Download"),
        Triple("DCIM", Icons.Filled.PhotoCamera, "$primaryBase/DCIM"),
        Triple("Pictures", Icons.Filled.Image, "$primaryBase/Pictures"),
        Triple("Documents", Icons.Filled.Description, "$primaryBase/Documents"),
        Triple("Movies", Icons.Filled.Movie, "$primaryBase/Movies"),
        Triple("Music", Icons.Filled.MusicNote, "$primaryBase/Music"),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Files", fontWeight = FontWeight.SemiBold) },
            )
        },
        bottomBar = {
            if (clipboard != null) {
                ClipboardBar(
                    clipboard = clipboard,
                    canPasteHere = false,
                    disabledPasteReason = "Open a folder to paste here",
                    onPasteHere = {},
                    onPickFavorite = onClipboardToFavorite,
                    onClear = onClearClipboard,
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 4.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (!hasAllFilesAccess && !onboardingDismissed) {
                item(key = "onboarding") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Text(
                                text = "Storage access",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = "Grant All-files access to browse and manage Internal Storage.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(onClick = onGrantAllFilesAccess) {
                                    Text("Grant access")
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = onDismissOnboarding) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }
                }
            }

            // 1. Storage Volumes
            item(key = "storage-section") {
                SectionHeader("Storage")
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        storageVolumes.forEach { vol ->
                            VolumeRow(
                                volume = vol,
                                icon = when (vol.kind) {
                                    VolumeKind.PRIMARY -> Icons.Filled.Smartphone
                                    VolumeKind.USB_OTG -> Icons.Filled.Usb
                                    else -> Icons.Filled.SdCard
                                },
                                context = context,
                                onClick = {
                                    if (vol.root != null) onOpenVolume(vol) else onMountVolumeSaf(vol)
                                },
                                onMountClick = if (vol.needsSafGrant && vol.kind != VolumeKind.PRIMARY) {
                                    { onMountVolumeSaf(vol) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }

            // 2. FTP Servers
            item(key = "ftp-servers-section") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SectionHeader("FTP Servers")
                    TextButton(onClick = { showAddFtpDialog = true }) {
                        Text("+ Add Server")
                    }
                }
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (ftpServers.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAddFtpDialog = true }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        ) {
                            Text(
                                text = "No FTP servers configured",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Tap + Add Server to connect and browse files on any remote FTP server.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            ftpServers.forEachIndexed { idx, server ->
                                if (idx > 0) {
                                    HorizontalDivider()
                                }
                                FtpServerRow(
                                    server = server,
                                    onClick = { onOpenFtpServer(server) },
                                    onEdit = { editingFtpServer = server },
                                    onRemove = { onRemoveFtpServer(server.id) },
                                )
                            }
                        }
                    }
                }
            }

            // 3. Quick Access
            item(key = "quick-access") {
                SectionHeader("Quick Access")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    for ((label, icon, pathStr) in quickAccess) {
                        AssistChip(
                            onClick = { onOpenQuickPath(VfsPath.Local(pathStr), "Internal") },
                            leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            // 4. Recent Favorites
            if (recentFavorites.isNotEmpty()) {
                item(key = "recent-favorites") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SectionHeader("Recent Favorites")
                        TextButton(onClick = onSeeAllFavorites) {
                            Text("See all →")
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        for (fav in recentFavorites) {
                            AssistChip(
                                onClick = { onOpenFavorite(fav) },
                                leadingIcon = {
                                    Icon(
                                        if (fav.isDirectory) Icons.Filled.Star else Icons.Filled.Description,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                label = { Text(fav.name) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddFtpDialog) {
        AddEditFtpServerDialog(
            existing = null,
            onSave = { name, host, port, anon, user, pass, path ->
                showAddFtpDialog = false
                onAddFtpServer(name, host, port, anon, user, pass, path)
            },
            onDismiss = { showAddFtpDialog = false },
        )
    }

    editingFtpServer?.let { target ->
        AddEditFtpServerDialog(
            existing = target,
            onSave = { name, host, port, anon, user, pass, path ->
                editingFtpServer = null
                onUpdateFtpServer(
                    target.copy(
                        name = name.ifBlank { host },
                        host = host,
                        port = port,
                        anonymous = anon,
                        username = if (anon) "anonymous" else user.ifBlank { "anonymous" },
                        password = if (anon) "" else pass,
                        initialPath = PathUtils.normalizeLocalPath(path.ifBlank { "/" }),
                    ),
                )
            },
            onDismiss = { editingFtpServer = null },
        )
    }
}

@Composable
private fun FtpServerRow(
    server: FtpServerBookmark,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Cloud,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = server.displayUrl,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More actions for ${server.name}")
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Edit") },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    },
                )
            }
        }
    }
}

@Composable
fun AddEditFtpServerDialog(
    existing: FtpServerBookmark?,
    onSave: (
        name: String,
        host: String,
        port: Int,
        anonymous: Boolean,
        username: String,
        password: String,
        initialPath: String,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var host by remember(existing?.id) { mutableStateOf(existing?.host ?: "") }
    var portText by remember(existing?.id) { mutableStateOf((existing?.port ?: 21).toString()) }
    var anonymous by remember(existing?.id) { mutableStateOf(existing?.anonymous ?: true) }
    var username by remember(existing?.id) {
        mutableStateOf(existing?.username?.takeIf { it != "anonymous" || existing.anonymous.not() } ?: "")
    }
    var password by remember(existing?.id) { mutableStateOf(existing?.password ?: "") }
    var showPassword by remember { mutableStateOf(false) }
    var initialPath by remember(existing?.id) { mutableStateOf(existing?.initialPath ?: "/") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add FTP Server" else "Edit FTP Server") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    placeholder = { Text("My NAS / PC") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = {
                        host = it
                        errorMsg = null
                    },
                    label = { Text("Host / IP") },
                    placeholder = { Text("192.168.1.100") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = {
                        portText = it
                        errorMsg = null
                    },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = initialPath,
                    onValueChange = { initialPath = it },
                    label = { Text("Remote path") },
                    placeholder = { Text("/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { anonymous = !anonymous },
                ) {
                    Text("Anonymous login")
                    Switch(checked = anonymous, onCheckedChange = { anonymous = it })
                }
                if (!anonymous) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = {
                            username = it
                            errorMsg = null
                        },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = "Toggle password visibility",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (errorMsg != null) {
                    Text(
                        text = errorMsg!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanHost = host.trim()
                        .removePrefix("ftp://")
                        .substringBefore('/')
                        .substringBefore(':')
                    if (cleanHost.isEmpty()) {
                        errorMsg = "Enter a valid host or IP address"
                        return@Button
                    }
                    val parsedPort = portText.trim().toIntOrNull()
                    if (parsedPort == null || parsedPort !in 1..65535) {
                        errorMsg = "Port must be between 1 and 65535"
                        return@Button
                    }
                    if (!anonymous && username.trim().isEmpty()) {
                        errorMsg = "Enter a username or enable Anonymous login"
                        return@Button
                    }
                    val cleanName = name.trim().ifEmpty { cleanHost }
                    val cleanPath = PathUtils.normalizeLocalPath(initialPath.trim().ifEmpty { "/" })
                    onSave(cleanName, cleanHost, parsedPort, anonymous, username.trim(), password, cleanPath)
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun VolumeRow(
    volume: Volume,
    icon: ImageVector,
    context: android.content.Context,
    onClick: () -> Unit,
    onMountClick: (() -> Unit)?,
) {
    val hasSpace = volume.totalBytes > 0L && volume.freeBytes >= 0L
    val usedBytes = (volume.totalBytes - volume.freeBytes).coerceAtLeast(0L)
    val usedRatio = if (hasSpace) (usedBytes.toFloat() / volume.totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(volume.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                val spaceText = if (hasSpace) {
                    "${UiFormatters.formatSize(context, volume.freeBytes)} free of ${UiFormatters.formatSize(context, volume.totalBytes)}"
                } else {
                    volume.subtitle ?: "Tap to browse"
                }
                Text(
                    text = spaceText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onMountClick != null) {
                OutlinedButton(onClick = onMountClick) {
                    Text("Mount")
                }
            }
        }
        if (hasSpace) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { usedRatio },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}
