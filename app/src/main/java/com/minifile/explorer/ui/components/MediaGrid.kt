package com.minifile.explorer.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minifile.explorer.core.gallery.GridDensity
import com.minifile.explorer.core.gallery.MediaItem
import com.minifile.explorer.core.gallery.MediaKind
import com.minifile.explorer.core.gallery.SectionKind
import com.minifile.explorer.core.gallery.TimelineBuilder
import com.minifile.explorer.core.gallery.TimelineLayout
import com.minifile.explorer.core.gallery.TimelineSection
import com.minifile.explorer.core.i18n.Str
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.data.gallery.GalleryThumbnailCache
import com.minifile.explorer.ui.i18n.LocalAppStrings
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun MediaGrid(
    sections: List<TimelineSection>,
    flatItems: List<MediaItem>,
    density: GridDensity,
    selectedPaths: Set<VfsPath>,
    isSelectionMode: Boolean,
    nonMediaDirectCount: Int,
    truncatedByBudget: Boolean,
    thumbnailCache: GalleryThumbnailCache,
    onItemClick: (MediaItem) -> Unit,
    onToggleSelect: (VfsPath) -> Unit,
    onToggleSectionSelect: (TimelineSection) -> Unit,
    onDragSelectUpdate: (anchorIdx: Int, currentIdx: Int, initialSel: Set<VfsPath>, targetSelected: Boolean) -> Unit,
    onViewAsFiles: () -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val s = LocalAppStrings.current
    val uiDensity = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    var isScrubbing by remember { mutableStateOf(false) }
    var decodeAllowed by remember { mutableStateOf(true) }
    LaunchedEffect(isScrubbing) {
        if (isScrubbing) {
            decodeAllowed = false
        } else {
            delay(60L)
            decodeAllowed = true
        }
    }

    // Auto-scroll speed during edge drag-selection
    var autoScrollVelocityPx by remember { mutableFloatStateOf(0f) }
    var dragAnchorIndex by remember { mutableIntStateOf(-1) }
    var dragInitialSelection by remember { mutableStateOf<Set<VfsPath>>(emptySet()) }
    var dragTargetSelected by remember { mutableStateOf(true) }

    LaunchedEffect(autoScrollVelocityPx) {
        if (autoScrollVelocityPx == 0f) return@LaunchedEffect
        while (isActive && autoScrollVelocityPx != 0f) {
            gridState.scrollBy(autoScrollVelocityPx)
            delay(16L)
        }
    }

    // Flat index map: key = "item:<path>" -> index in flatItems
    val flatIndexByKey = remember(flatItems) {
        HashMap<Any, Int>(flatItems.size * 2).apply {
            flatItems.forEachIndexed { idx, item -> put("item:${item.path.serialize()}", idx) }
        }
    }

    fun sectionDisplayTitle(sec: TimelineSection): String = when (sec.kind) {
        SectionKind.TODAY -> s[Str.GALLERY_TODAY]
        SectionKind.YESTERDAY -> s[Str.GALLERY_YESTERDAY]
        SectionKind.NO_LOCATION -> s[Str.GALLERY_NO_LOCATION]
        SectionKind.FLAT_VIDEO_SORT -> s[Str.GALLERY_FILTER_VIDEOS]
        else -> sec.titleHint
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val spacingDp = 2.dp
        val maxWidthPx = with(uiDensity) { maxWidth.toPx() }
        val maxHeightPx = with(uiDensity) { maxHeight.toPx() }
        val edgeZonePx = with(uiDensity) { 48.dp.toPx() }
        val minCellPx = with(uiDensity) { density.minCellDp.dp.toPx() }
        val cols = (maxWidthPx / minCellPx).toInt().coerceAtLeast(density.portraitCols.coerceAtMost(3))
        val spacingPx = with(uiDensity) { spacingDp.toPx() }
        val cellPx = ((maxWidthPx - spacingPx * (cols - 1)) / cols).coerceAtLeast(48f)
        val headerPx = with(uiDensity) { 44.dp.toPx() }

        val layoutIndex = remember(sections, cols, cellPx, headerPx, spacingPx) {
            TimelineLayout.compute(
                sections = sections,
                columns = cols,
                headerHeightPx = headerPx,
                cellHeightPx = cellPx,
                rowSpacingPx = spacingPx,
                includeHeaders = true,
            )
        }

        val latestFlatItems by rememberUpdatedState(flatItems)
        val latestSelectedPaths by rememberUpdatedState(selectedPaths)
        val latestFlatIndexByKey by rememberUpdatedState(flatIndexByKey)
        val latestOnDragSelectUpdate by rememberUpdatedState(onDragSelectUpdate)

        fun hitFlatIndex(pos: Offset): Int? {
            val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                val x = pos.x.toInt()
                val y = pos.y.toInt()
                x in item.offset.x until (item.offset.x + item.size.width) &&
                    y in item.offset.y until (item.offset.y + item.size.height)
            } ?: return null
            return latestFlatIndexByKey[info.key]
        }

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(cols),
            horizontalArrangement = Arrangement.spacedBy(spacingDp),
            verticalArrangement = Arrangement.spacedBy(spacingDp),
            contentPadding = PaddingValues(bottom = 96.dp),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(gridState) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { startOffset ->
                            val idx = hitFlatIndex(startOffset) ?: return@detectDragGesturesAfterLongPress
                            val itemsNow = latestFlatItems
                            if (idx !in itemsNow.indices) return@detectDragGesturesAfterLongPress
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val selNow = latestSelectedPaths
                            dragAnchorIndex = idx
                            dragInitialSelection = selNow
                            val startPath = itemsNow[idx].path
                            dragTargetSelected = startPath !in selNow
                            latestOnDragSelectUpdate(idx, idx, dragInitialSelection, dragTargetSelected)
                        },
                        onDragEnd = {
                            dragAnchorIndex = -1
                            autoScrollVelocityPx = 0f
                        },
                        onDragCancel = {
                            dragAnchorIndex = -1
                            autoScrollVelocityPx = 0f
                        },
                        onDrag = { change, _ ->
                            if (dragAnchorIndex < 0) return@detectDragGesturesAfterLongPress
                            // Consume pointer event so HorizontalPager never swipes tabs during drag-selection
                            change.consume()
                            val pos = change.position
                            hitFlatIndex(pos)?.let { curIdx ->
                                latestOnDragSelectUpdate(
                                    dragAnchorIndex,
                                    curIdx,
                                    dragInitialSelection,
                                    dragTargetSelected,
                                )
                            }
                            autoScrollVelocityPx = when {
                                pos.y < edgeZonePx -> -((edgeZonePx - pos.y) / edgeZonePx) * 28f
                                pos.y > maxHeightPx - edgeZonePx -> ((pos.y - (maxHeightPx - edgeZonePx)) / edgeZonePx) * 28f
                                else -> 0f
                            }
                        },
                    )
                },
        ) {
            for (sec in sections) {
                item(
                    key = "hdr:${sec.id}",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    val secSelectedAll = isSelectionMode &&
                        sec.items.isNotEmpty() &&
                        sec.items.all { it.path in selectedPaths }
                    SectionHeaderRow(
                        title = sectionDisplayTitle(sec),
                        countText = s.itemsCount(sec.items.size),
                        showSelectToggle = isSelectionMode,
                        allSelected = secSelectedAll,
                        onToggleSelect = { onToggleSectionSelect(sec) },
                    )
                }

                items(
                    count = sec.items.size,
                    key = { idx -> "item:${sec.items[idx].path.serialize()}" },
                ) { idx ->
                    val item = sec.items[idx]
                    val isSelected = item.path in selectedPaths
                    MediaGridTile(
                        item = item,
                        selected = isSelected,
                        selectionMode = isSelectionMode,
                        decodeAllowed = decodeAllowed,
                        thumbnailCache = thumbnailCache,
                        onClick = {
                            if (isSelectionMode) onToggleSelect(item.path) else onItemClick(item)
                        },
                    )
                }
            }

            if (truncatedByBudget) {
                item(key = "truncated-hint", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = s[Str.GALLERY_TRUNCATED_SUBTREE_HINT],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            if (nonMediaDirectCount > 0) {
                item(key = "non-media-footer", span = { GridItemSpan(maxLineSpan) }) {
                    val label = if (nonMediaDirectCount == 1) {
                        s[Str.GALLERY_OTHER_ITEMS_NOT_SHOWN_SINGLE]
                    } else {
                        s.format(Str.GALLERY_OTHER_ITEMS_NOT_SHOWN_PLURAL, nonMediaDirectCount)
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                            .clickable(onClick = onViewAsFiles),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onViewAsFiles) {
                                Text(s[Str.GALLERY_VIEW_AS_FILES])
                            }
                        }
                    }
                }
            }
        }

        TimelineScrubber(
            gridState = gridState,
            layoutIndex = layoutIndex,
            sections = sections,
            sectionLabelProvider = ::sectionDisplayTitle,
            totalItemCount = flatItems.size,
            onScrubbingChanged = { isScrubbing = it },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun SectionHeaderRow(
    title: String,
    countText: String,
    showSelectToggle: Boolean,
    allSelected: Boolean,
    onToggleSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = countText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showSelectToggle) {
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = onToggleSelect,
                modifier = Modifier.size(32.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            if (allSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        )
                        .border(
                            width = 1.5.dp,
                            color = if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            shape = CircleShape,
                        ),
                ) {
                    if (allSelected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaGridTile(
    item: MediaItem,
    selected: Boolean,
    selectionMode: Boolean,
    decodeAllowed: Boolean,
    thumbnailCache: GalleryThumbnailCache,
    onClick: () -> Unit,
) {
    var bitmap by remember(item.path, item.modifiedMs) {
        mutableStateOf<Bitmap?>(thumbnailCache.peekMemory(item, 256))
    }
    var loadFailed by remember(item.path, item.modifiedMs) { mutableStateOf(false) }

    LaunchedEffect(item.path, item.modifiedMs, decodeAllowed) {
        if (bitmap == null && decodeAllowed && !loadFailed) {
            val loaded = thumbnailCache.loadThumbnail(item, 256)
            if (loaded != null) {
                bitmap = loaded
            } else {
                loadFailed = true
            }
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (selected) 0.86f else 1f,
        label = "media-tile-scale",
    )
    val a11yDesc = remember(item) {
        if (item.kind == MediaKind.VIDEO && item.durationMs > 0L) {
            "${item.name}, ${TimelineBuilder.formatDuration(item.durationMs)}"
        } else {
            item.name
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .aspectRatio(1f)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = a11yDesc },
    ) {
        val bmp = bitmap
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .scale(scale)
                .clip(if (selected) RoundedCornerShape(8.dp) else RoundedCornerShape(0.dp)),
        ) {
            if (bmp != null && !bmp.isRecycled) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (loadFailed) {
                Icon(
                    imageVector = Icons.Filled.BrokenImage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(24.dp),
                )
            }

            if (item.kind == MediaKind.VIDEO) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.68f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.size(12.dp),
                        )
                        if (item.durationMs > 0L) {
                            Text(
                                text = TimelineBuilder.formatDuration(item.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                            )
                        }
                    }
                }
            }
        }

        if (selectionMode) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f),
                    )
                    .border(
                        width = 1.5.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.inverseOnSurface,
                        shape = CircleShape,
                    ),
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}
