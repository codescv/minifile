package com.minifile.explorer.ui.screens

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.minifile.explorer.core.model.ClipboardState
import com.minifile.explorer.core.model.Favorite
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
    hasAllFilesAccess: Boolean,
    onboardingDismissed: Boolean,
    clipboard: ClipboardState?,
    onGrantAllFilesAccess: () -> Unit,
    onDismissOnboarding: () -> Unit,
    onMountVolumeSaf: (Volume) -> Unit,
    onOpenVolume: (Volume) -> Unit,
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

            // 2. Quick Access
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

            // 3. Recent Favorites
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
                                        Icons.Filled.Star,
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
