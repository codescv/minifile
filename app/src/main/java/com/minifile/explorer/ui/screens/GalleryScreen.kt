package com.minifile.explorer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.minifile.explorer.core.gallery.GalleryGroupBy
import com.minifile.explorer.core.gallery.GridDensity
import com.minifile.explorer.core.gallery.MediaExportFormat
import com.minifile.explorer.core.gallery.MediaFilter
import com.minifile.explorer.core.gallery.MediaItem
import com.minifile.explorer.core.gallery.TimelineSection
import com.minifile.explorer.core.i18n.Str
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.data.gallery.GalleryThumbnailCache
import com.minifile.explorer.ui.components.GalleryGroupSheet
import com.minifile.explorer.ui.components.MediaExportOptionsSheet
import com.minifile.explorer.ui.components.MediaGrid
import com.minifile.explorer.ui.gallery.GalleryUiState
import com.minifile.explorer.ui.i18n.LocalAppStrings

@Composable
fun GalleryLensContent(
    state: GalleryUiState,
    exportFormat: MediaExportFormat,
    removeLocationOnExport: Boolean,
    thumbnailCache: GalleryThumbnailCache,
    onSelectFilter: (MediaFilter) -> Unit,
    onSelectGroupBy: (GalleryGroupBy) -> Unit,
    onSelectDensity: (GridDensity) -> Unit,
    onSelectExportFormat: (MediaExportFormat) -> Unit,
    onToggleRemoveLocation: (Boolean) -> Unit,
    onOpenSubfolder: (VfsPath) -> Unit,
    onItemClick: (MediaItem) -> Unit,
    onToggleSelect: (VfsPath) -> Unit,
    onToggleSectionSelect: (TimelineSection) -> Unit,
    onDragSelectUpdate: (Int, Int, Set<VfsPath>, Boolean) -> Unit,
    onViewAsFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalAppStrings.current
    var showGroupSheet by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }

    val groupLabel = when (state.groupBy) {
        GalleryGroupBy.DAY -> s[Str.GALLERY_GROUP_DAY]
        GalleryGroupBy.MONTH -> s[Str.GALLERY_GROUP_MONTH]
        GalleryGroupBy.YEAR -> s[Str.GALLERY_GROUP_YEAR]
        GalleryGroupBy.PLACE -> s[Str.GALLERY_GROUP_PLACE]
        GalleryGroupBy.FOLDER -> s[Str.GALLERY_GROUP_FOLDER]
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Single-row filter bar: All / Photos / Videos + Group by trigger at end
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                FilterChip(
                    selected = state.filter == MediaFilter.ALL,
                    onClick = { onSelectFilter(MediaFilter.ALL) },
                    label = { Text(s[Str.GALLERY_FILTER_ALL], maxLines = 1, softWrap = false) },
                )
                FilterChip(
                    selected = state.filter == MediaFilter.PHOTOS,
                    onClick = { onSelectFilter(MediaFilter.PHOTOS) },
                    label = { Text(s[Str.GALLERY_FILTER_PHOTOS], maxLines = 1, softWrap = false) },
                )
                FilterChip(
                    selected = state.filter == MediaFilter.VIDEOS,
                    onClick = { onSelectFilter(MediaFilter.VIDEOS) },
                    label = { Text(s[Str.GALLERY_FILTER_VIDEOS], maxLines = 1, softWrap = false) },
                )
            }
            TextButton(
                onClick = { showGroupSheet = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(groupLabel, maxLines = 1, softWrap = false)
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = s[Str.GALLERY_GROUP_BY],
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Subfolder quick chips when viewing DCIM / Pictures root aggregate
        if (state.isMediaRootAggregate && state.directSubfolders.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(state.directSubfolders, key = { it.path.serialize() }) { sub ->
                    AssistChip(
                        onClick = { onOpenSubfolder(sub.path) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        label = { Text(sub.name, maxLines = 1, softWrap = false) },
                    )
                }
            }
        }

        if (state.hiddenByNomedia) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.VisibilityOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = s[Str.HIDDEN_FROM_GALLERY],
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.isRefreshingGeo) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // Compact export-options strip during multi-select
        if (state.isSelectionMode) {
            val fmtLabel = if (exportFormat == MediaExportFormat.ORIGINAL) {
                s[Str.GALLERY_FORMAT_ORIGINAL]
            } else {
                s[Str.GALLERY_FORMAT_COMPATIBLE]
            }
            val locLabel = if (removeLocationOnExport) {
                s[Str.GALLERY_LOCATION_REMOVED]
            } else {
                s[Str.GALLERY_LOCATION_KEPT]
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showExportSheet = true },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "$fmtLabel · $locLabel",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        when {
            state.isLoading && state.flatItems.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.flatItems.isEmpty() -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PhotoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(s[Str.GALLERY_EMPTY_TITLE], style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = s[Str.GALLERY_EMPTY_SUBTITLE],
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                MediaGrid(
                    sections = state.sections,
                    flatItems = state.flatItems,
                    density = state.density,
                    selectedPaths = state.selectedPaths,
                    isSelectionMode = state.isSelectionMode,
                    nonMediaDirectCount = state.nonMediaDirectCount,
                    truncatedByBudget = state.truncatedByBudget,
                    thumbnailCache = thumbnailCache,
                    onItemClick = onItemClick,
                    onToggleSelect = onToggleSelect,
                    onToggleSectionSelect = onToggleSectionSelect,
                    onDragSelectUpdate = onDragSelectUpdate,
                    onViewAsFiles = onViewAsFiles,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (showGroupSheet) {
        GalleryGroupSheet(
            currentGroupBy = state.groupBy,
            currentDensity = state.density,
            onSelectGroupBy = {
                onSelectGroupBy(it)
                showGroupSheet = false
            },
            onSelectDensity = { onSelectDensity(it) },
            onDismiss = { showGroupSheet = false },
        )
    }

    if (showExportSheet) {
        MediaExportOptionsSheet(
            format = exportFormat,
            removeLocation = removeLocationOnExport,
            onSelectFormat = onSelectExportFormat,
            onToggleRemoveLocation = onToggleRemoveLocation,
            onDismiss = { showExportSheet = false },
        )
    }
}
