package com.minifile.explorer.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minifile.explorer.BuildConfig
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.data.SafFileSystem
import com.minifile.explorer.ui.util.UiFormatters

enum class RowAction {
    OPEN_WITH,
    OPEN_AS_TEXT,
    RENAME,
    COPY,
    CUT,
    COPY_TO_FAVORITE,
    MOVE_TO_FAVORITE,
    COMPRESS_ZIP,
    TOGGLE_FAVORITE,
    TOGGLE_NOMEDIA,
    PROPERTIES,
    DELETE,
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListRow(
    node: VfsNode,
    selected: Boolean,
    selectionMode: Boolean,
    isFavorite: Boolean,
    isCutPending: Boolean,
    showThumbnails: Boolean,
    safFs: SafFileSystem,
    subtitleOverride: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onAction: (RowAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var thumbnail by remember(node.path, node.lastModified, showThumbnails) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(node.path, node.lastModified, showThumbnails) {
        if (showThumbnails && BuildConfig.THUMBNAILS && UiFormatters.isImageOrVideo(node)) {
            thumbnail = UiFormatters.loadThumbnail(context, safFs, node, 112)
        }
    }

    val rowAlpha = when {
        isCutPending -> 0.5f
        node.isHidden -> 0.72f
        else -> 1f
    }

    val supportingText = subtitleOverride ?: buildSupportingLine(context, node)

    ListItem(
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        leadingContent = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (node.isDirectory) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    )
                    .clickable(onClick = onToggleSelect),
            ) {
                if (selectionMode || selected) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelect() },
                    )
                } else if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!.asImageBitmap(),
                        contentDescription = node.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(40.dp),
                    )
                } else {
                    Icon(
                        imageVector = UiFormatters.iconForNode(node),
                        contentDescription = if (node.isDirectory) "Folder" else "File",
                        tint = if (node.isDirectory) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        },
        headlineContent = {
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (node.hasNomedia) {
                    Icon(
                        imageVector = Icons.Filled.VisibilityOff,
                        contentDescription = "Hidden from gallery",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(18.dp),
                    )
                }
                if (isFavorite) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Favorite",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(18.dp),
                    )
                }
                if (!node.canWrite) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Read-only",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(16.dp),
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "More actions for ${node.name}",
                        )
                    }
                    NodeContextMenu(
                        expanded = menuExpanded,
                        node = node,
                        isFavorite = isFavorite,
                        onDismiss = { menuExpanded = false },
                        onAction = {
                            menuExpanded = false
                            onAction(it)
                        },
                    )
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .alpha(rowAlpha)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridCell(
    node: VfsNode,
    selected: Boolean,
    isFavorite: Boolean,
    isCutPending: Boolean,
    showThumbnails: Boolean,
    safFs: SafFileSystem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAction: (RowAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var thumbnail by remember(node.path, node.lastModified, showThumbnails) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(node.path, node.lastModified, showThumbnails) {
        if (showThumbnails && BuildConfig.THUMBNAILS && UiFormatters.isImageOrVideo(node)) {
            thumbnail = UiFormatters.loadThumbnail(context, safFs, node, 160)
        }
    }

    val cellAlpha = when {
        isCutPending -> 0.5f
        node.isHidden -> 0.72f
        else -> 1f
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        modifier = modifier
            .alpha(cellAlpha)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (node.isDirectory) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        ),
                ) {
                    if (thumbnail != null) {
                        Image(
                            bitmap = thumbnail!!.asImageBitmap(),
                            contentDescription = node.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp),
                        )
                    } else {
                        Icon(
                            imageVector = UiFormatters.iconForNode(node),
                            contentDescription = if (node.isDirectory) "Folder" else "File",
                            tint = if (node.isDirectory) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    minLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (node.isDirectory) {
                        if (node.childCount >= 0) "${node.childCount} items" else "Folder"
                    } else {
                        UiFormatters.formatSize(context, node.size)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }

            // Top-left selection / compact status indicators (kept outside the centered 56.dp icon box)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 6.dp, start = 6.dp),
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    if (isFavorite) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "Favorite",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    if (node.hasNomedia) {
                        Icon(
                            imageVector = Icons.Filled.VisibilityOff,
                            contentDescription = "Hidden from gallery",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    if (!node.canWrite) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "Read-only",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }

            // Top-right context menu trigger (tucked cleanly in card corner)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "More actions for ${node.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { menuExpanded = true },
                )
                NodeContextMenu(
                    expanded = menuExpanded,
                    node = node,
                    isFavorite = isFavorite,
                    onDismiss = { menuExpanded = false },
                    onAction = {
                        menuExpanded = false
                        onAction(it)
                    },
                )
            }
        }
    }
}

@Composable
private fun NodeContextMenu(
    expanded: Boolean,
    node: VfsNode,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onAction: (RowAction) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        if (!node.isDirectory) {
            DropdownMenuItem(
                text = { Text("Open with…") },
                onClick = { onAction(RowAction.OPEN_WITH) },
            )
            DropdownMenuItem(
                text = { Text("Open as text") },
                onClick = { onAction(RowAction.OPEN_AS_TEXT) },
            )
            HorizontalDivider()
        }
        DropdownMenuItem(
            text = { Text("Rename") },
            onClick = { onAction(RowAction.RENAME) },
        )
        DropdownMenuItem(
            text = { Text("Copy") },
            onClick = { onAction(RowAction.COPY) },
        )
        DropdownMenuItem(
            text = { Text("Cut") },
            onClick = { onAction(RowAction.CUT) },
        )
        DropdownMenuItem(
            text = { Text("Copy to…") },
            onClick = { onAction(RowAction.COPY_TO_FAVORITE) },
        )
        DropdownMenuItem(
            text = { Text("Move to…") },
            onClick = { onAction(RowAction.MOVE_TO_FAVORITE) },
        )
        DropdownMenuItem(
            text = { Text("Compress to ZIP") },
            onClick = { onAction(RowAction.COMPRESS_ZIP) },
        )
        if (node.isDirectory) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (isFavorite) "Remove from Favorites" else "Add to Favorites") },
                onClick = { onAction(RowAction.TOGGLE_FAVORITE) },
            )
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Hide from gallery")
                        Spacer(Modifier.width(8.dp))
                        if (node.hasNomedia) {
                            Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                onClick = { onAction(RowAction.TOGGLE_NOMEDIA) },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Properties") },
            onClick = { onAction(RowAction.PROPERTIES) },
        )
        DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            onClick = { onAction(RowAction.DELETE) },
        )
    }
}

private fun buildSupportingLine(context: android.content.Context, node: VfsNode): String {
    val datePart = UiFormatters.formatShortDate(node.lastModified)
    return if (node.isDirectory) {
        val countPart = when {
            node.childCount < 0 -> "—"
            node.childCount == 1 -> "1 item"
            else -> "${node.childCount} items"
        }
        "$countPart · $datePart"
    } else {
        "${UiFormatters.formatSize(context, node.size)} · $datePart"
    }
}
