package com.minifile.explorer.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.media.MediaPlayer
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.exifinterface.media.ExifInterface
import com.minifile.explorer.core.gallery.CoreCropMath
import com.minifile.explorer.core.gallery.NormalizedRect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.minifile.explorer.core.gallery.GalleryScope
import com.minifile.explorer.core.gallery.MediaItem
import com.minifile.explorer.core.gallery.MediaKind
import com.minifile.explorer.core.gallery.TimelineBuilder
import com.minifile.explorer.core.i18n.Str
import com.minifile.explorer.core.model.TransferMode
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.data.gallery.GalleryThumbnailCache
import com.minifile.explorer.ui.MainViewModel
import com.minifile.explorer.ui.components.DeleteConfirmDialog
import com.minifile.explorer.ui.components.FavoritePickerSheet
import com.minifile.explorer.ui.components.MediaExportOptionsSheet
import com.minifile.explorer.ui.components.PropertiesSheet
import com.minifile.explorer.ui.components.SendToDeviceSheet
import com.minifile.explorer.ui.i18n.LocalAppStrings
import com.minifile.explorer.ui.util.UiFormatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun MediaViewerScreen(
    galleryScope: GalleryScope,
    startPath: VfsPath,
    viewModel: MainViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val s = LocalAppStrings.current
    val coroutineScope = rememberCoroutineScope()
    val container = viewModel.container

    val galleryState by viewModel.galleryController.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val volumes by viewModel.volumes.collectAsState()

    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loadingList by remember { mutableStateOf(true) }

    LaunchedEffect(galleryScope, startPath, galleryState.flatItems) {
        val fromCtrl = if (galleryState.scope == galleryScope && galleryState.flatItems.isNotEmpty()) {
            galleryState.flatItems
        } else {
            emptyList()
        }
        if (fromCtrl.isNotEmpty()) {
            items = fromCtrl
            loadingList = false
        } else {
            val loaded = withContext(Dispatchers.IO) {
                val snap = runCatching { container.galleryRepository.loadAndReconcile(galleryScope, settings.showHidden) }.getOrNull()
                snap?.items.orEmpty()
            }
            if (loaded.isNotEmpty()) {
                items = loaded
            } else {
                // Single-item fallback if opened directly
                val st = withContext(Dispatchers.IO) { runCatching { container.fileSystem.stat(startPath) }.getOrNull() }
                if (st != null) {
                    val kind = com.minifile.explorer.core.gallery.MediaExtensionRegistry.classify(st.name, st.mimeType)
                        ?: MediaKind.IMAGE
                    items = listOf(
                        MediaItem(
                            path = st.path,
                            name = st.name,
                            kind = kind,
                            mime = st.resolvedMimeType,
                            sizeBytes = st.size.coerceAtLeast(0L),
                            bestDateMs = st.lastModified,
                            modifiedMs = st.lastModified,
                        ),
                    )
                }
            }
            loadingList = false
        }
    }

    val initialPage = remember(items, startPath) {
        items.indexOfFirst { it.path == startPath }.coerceAtLeast(0)
    }

    BackHandler(onBack = onClose)

    var showChrome by remember { mutableStateOf(true) }
    var moreExpanded by remember { mutableStateOf(false) }
    var showExportOptions by remember { mutableStateOf(false) }
    var propertiesNode by remember { mutableStateOf<VfsNode?>(null) }
    var deleteNode by remember { mutableStateOf<VfsNode?>(null) }
    var sendNodes by remember { mutableStateOf<List<VfsNode>?>(null) }
    var copyToPath by remember { mutableStateOf<VfsPath?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (loadingList) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        } else if (items.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(s[Str.GALLERY_ITEM_UNAVAILABLE], color = Color.White)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onClose) {
                    Text(s[Str.CLOSE], color = Color.White)
                }
            }
        } else {
            val pagerState = rememberPagerState(initialPage = initialPage) { items.size }
            val currentItem = items.getOrNull(pagerState.currentPage) ?: items.first()

            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                key = { idx -> items[idx].path.serialize() },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val item = items[page]
                if (item.kind == MediaKind.VIDEO) {
                    VideoViewerPage(
                        item = item,
                        isCurrentPage = pagerState.currentPage == page,
                        thumbnailCache = container.galleryThumbnailCache,
                        onToggleChrome = { showChrome = !showChrome },
                        onOpenWith = {
                            coroutineScope.launch {
                                val node = withContext(Dispatchers.IO) { container.fileSystem.stat(item.path) }
                                    ?: item.toFallbackNode()
                                UiFormatters.openExternallyAsync(
                                    context = context,
                                    safFs = container.safFileSystem,
                                    fs = container.fileSystem,
                                    node = node,
                                    forceChooser = true,
                                )
                            }
                        },
                    )
                } else {
                    ZoomablePhotoPage(
                        item = item,
                        thumbnailCache = container.galleryThumbnailCache,
                        onToggleChrome = { showChrome = !showChrome },
                    )
                }
            }

            // Top translucent scrim + bar
            if (showChrome) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = s[Str.BACK],
                            tint = Color.White,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = UiFormatters.formatFullDate(currentItem.bestDateMs),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = currentItem.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                val st = withContext(Dispatchers.IO) {
                                    runCatching { container.fileSystem.stat(currentItem.path) }.getOrNull()
                                } ?: currentItem.toFallbackNode()
                                propertiesNode = st
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = s[Str.PROPERTIES], tint = Color.White)
                    }
                }

                // Bottom action bar: Send · Share · Edit · Delete · More
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.65f))
                        .navigationBarsPadding()
                        .padding(vertical = 8.dp),
                ) {
                    ViewerActionButton(
                        icon = Icons.AutoMirrored.Filled.Send,
                        label = s[Str.SEND],
                        onClick = {
                            coroutineScope.launch {
                                val st = withContext(Dispatchers.IO) {
                                    runCatching { container.fileSystem.stat(currentItem.path) }.getOrNull()
                                } ?: currentItem.toFallbackNode()
                                sendNodes = listOf(st)
                            }
                        },
                    )
                    ViewerActionButton(
                        icon = Icons.Filled.Share,
                        label = s[Str.SHARE],
                        onClick = {
                            coroutineScope.launch {
                                val sVal = settings
                                val prepared = if (sVal.galleryExportFormat != com.minifile.explorer.core.gallery.MediaExportFormat.ORIGINAL || sVal.galleryRemoveLocationOnExport) {
                                    container.imageEditorEngine.prepareForExport(
                                        paths = listOf(currentItem.path),
                                        format = sVal.galleryExportFormat,
                                        removeLocation = sVal.galleryRemoveLocationOnExport,
                                    ).first
                                } else {
                                    listOf(currentItem.path)
                                }
                                val node = withContext(Dispatchers.IO) {
                                    runCatching { container.fileSystem.stat(prepared.first()) }.getOrNull()
                                } ?: currentItem.toFallbackNode()
                                UiFormatters.shareItemsAsync(
                                    context = context,
                                    safFs = container.safFileSystem,
                                    fs = container.fileSystem,
                                    nodes = listOf(node),
                                ).onFailure { viewModel.emitSimpleMessage(it.message ?: s[Str.CANNOT_SHARE_SELECTION]) }
                            }
                        },
                    )
                    ViewerActionButton(
                        icon = Icons.Filled.Edit,
                        label = s[Str.EDIT],
                        onClick = { viewModel.openMediaEditor(currentItem.path) },
                    )
                    ViewerActionButton(
                        icon = Icons.Filled.Delete,
                        label = s[Str.DELETE],
                        onClick = {
                            coroutineScope.launch {
                                val st = withContext(Dispatchers.IO) {
                                    runCatching { container.fileSystem.stat(currentItem.path) }.getOrNull()
                                } ?: currentItem.toFallbackNode()
                                deleteNode = st
                            }
                        },
                    )
                    Box {
                        ViewerActionButton(
                            icon = Icons.Filled.MoreVert,
                            label = s[Str.MORE],
                            onClick = { moreExpanded = true },
                        )
                        DropdownMenu(
                            expanded = moreExpanded,
                            onDismissRequest = { moreExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(s[Str.GALLERY_EXPORT_OPTIONS]) },
                                onClick = {
                                    moreExpanded = false
                                    showExportOptions = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(s[Str.COPY_TO_FAVORITE]) },
                                onClick = {
                                    moreExpanded = false
                                    copyToPath = currentItem.path
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(s[Str.GALLERY_SHOW_IN_FOLDER]) },
                                onClick = {
                                    moreExpanded = false
                                    viewModel.showMediaInFolder(currentItem.path)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(s[Str.OPEN_WITH]) },
                                onClick = {
                                    moreExpanded = false
                                    coroutineScope.launch {
                                        val st = withContext(Dispatchers.IO) {
                                            runCatching { container.fileSystem.stat(currentItem.path) }.getOrNull()
                                        } ?: currentItem.toFallbackNode()
                                        UiFormatters.openExternallyAsync(
                                            context = context,
                                            safFs = container.safFileSystem,
                                            fs = container.fileSystem,
                                            node = st,
                                            forceChooser = true,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showExportOptions) {
        MediaExportOptionsSheet(
            format = settings.galleryExportFormat,
            removeLocation = settings.galleryRemoveLocationOnExport,
            onSelectFormat = { viewModel.galleryController.setExportFormat(it) },
            onToggleRemoveLocation = { viewModel.galleryController.setRemoveLocationOnExport(it) },
            onDismiss = { showExportOptions = false },
        )
    }

    propertiesNode?.let { node ->
        PropertiesSheet(
            node = node,
            isFavorite = favorites.any { it.path == node.path },
            fileOps = container.fileOperations,
            checksumCalculator = container.checksumCalculator,
            safFs = container.safFileSystem,
            onRequestRename = {},
            onToggleFavorite = {},
            onToggleNomedia = {},
            onCopiedMessage = { viewModel.emitSimpleMessage(it) },
            onDismiss = { propertiesNode = null },
        )
    }

    deleteNode?.let { target ->
        DeleteConfirmDialog(
            nodes = listOf(target),
            onConfirm = {
                viewModel.deleteItems(context, listOf(target.path))
                items = items.filterNot { it.path == target.path }
                if (items.isEmpty()) onClose()
            },
            onDismiss = { deleteNode = null },
        )
    }

    sendNodes?.let { nodes ->
        SendToDeviceSheet(
            items = nodes,
            viewModel = viewModel,
            onSelectPeer = { peer ->
                sendNodes = null
                viewModel.sendMediaToPeer(
                    context = context,
                    peer = peer,
                    paths = nodes.map { it.path },
                )
            },
            onDismiss = { sendNodes = null },
        )
    }

    copyToPath?.let { path ->
        FavoritePickerSheet(
            items = listOf(path),
            initialMode = TransferMode.COPY,
            favorites = favorites,
            sortByRecency = settings.favoritePickerSortByRecency,
            volumes = volumes,
            currentDir = null,
            fs = container.fileSystem,
            onSelectFavorite = { fav, mode ->
                copyToPath = null
                viewModel.executeTransferToFavorite(context, listOf(path), mode, fav)
            },
            onSelectCustomFolder = { dir, label, mode ->
                copyToPath = null
                viewModel.executeTransfer(context, listOf(path), mode, dir, label)
            },
            onAddCurrentFolderToFavorites = {},
            onDismiss = { copyToPath = null },
        )
    }
}

@Composable
private fun ViewerActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

private data class HdTileOverlay(
    val bitmap: Bitmap,
    val rect: NormalizedRect,
)

@Composable
private fun ZoomablePhotoPage(
    item: MediaItem,
    thumbnailCache: GalleryThumbnailCache,
    onToggleChrome: () -> Unit,
) {
    var bitmap by remember(item.path, item.modifiedMs) {
        mutableStateOf<Bitmap?>(thumbnailCache.peekMemory(item, 256))
    }
    var hdTile by remember(item.path, item.modifiedMs) { mutableStateOf<HdTileOverlay?>(null) }
    var scale by remember(item.path) { mutableFloatStateOf(1f) }
    var offset by remember(item.path) { mutableStateOf(Offset.Zero) }
    var containerSize by remember(item.path) { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(item.path, item.modifiedMs) {
        val full = thumbnailCache.loadViewerBitmap(item, maxSidePx = 2048)
        if (full != null) bitmap = full
    }

    // H4 + R2-1: Local visible-region HD tile (> 2.2x) mapped through EXIF orientation and pixel-budgeted,
    // positioned in the SAME graphicsLayer transform as the base image so pan/pinch never freezes or jumps.
    val tileBucketX = (offset.x / 64f).toInt()
    val tileBucketY = (offset.y / 64f).toInt()
    val tileScaleBucket = (scale * 2f).toInt()
    LaunchedEffect(item.path, scale > 2.2f, tileBucketX, tileBucketY, tileScaleBucket, containerSize) {
        if (scale <= 2.2f || containerSize.width <= 0 || containerSize.height <= 0) {
            hdTile = null
            return@LaunchedEffect
        }
        val local = item.path as? VfsPath.Local ?: return@LaunchedEffect
        val loadedTile = withContext(Dispatchers.IO) {
            runCatching {
                val f = File(local.normalized)
                if (!f.isFile) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, bounds)
                if (bounds.outWidth <= 2048 && bounds.outHeight <= 2048) return@runCatching null
                val orient = runCatching {
                    ExifInterface(f.absolutePath).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

                val (dispW, dispH) = CoreCropMath.displayDimensions(bounds.outWidth, bounds.outHeight, orient)
                val vpW = containerSize.width.toFloat()
                val vpH = containerSize.height.toFloat()
                val fitScale = minOf(vpW / dispW.coerceAtLeast(1), vpH / dispH.coerceAtLeast(1))
                val fitW = (dispW * fitScale).coerceAtLeast(1f)
                val fitH = (dispH * fitScale).coerceAtLeast(1f)
                val renderedW = fitW * scale
                val renderedH = fitH * scale

                // Exact visible rectangle in normalized display coordinates [0, 1]^2 (with 10% overscan margin)
                val rawLeft = 0.5f + (-vpW / 2f - offset.x) / renderedW
                val rawRight = 0.5f + (vpW / 2f - offset.x) / renderedW
                val rawTop = 0.5f + (-vpH / 2f - offset.y) / renderedH
                val rawBottom = 0.5f + (vpH / 2f - offset.y) / renderedH
                val padU = ((rawRight - rawLeft) * 0.1f).coerceAtLeast(0.01f)
                val padV = ((rawBottom - rawTop) * 0.1f).coerceAtLeast(0.01f)
                val left = (rawLeft - padU).coerceIn(0f, 1f)
                val right = (rawRight + padU).coerceIn(0f, 1f)
                val top = (rawTop - padV).coerceIn(0f, 1f)
                val bottom = (rawBottom + padV).coerceIn(0f, 1f)
                if (right - left < 0.005f || bottom - top < 0.005f) return@runCatching null
                val visibleDisp = NormalizedRect(left = left, top = top, right = right, bottom = bottom)

                val rawRect = CoreCropMath.computeRawCropRect(
                    displayCrop = visibleDisp,
                    rawWidth = bounds.outWidth,
                    rawHeight = bounds.outHeight,
                    exifOrientation = orient,
                    enforceEven2Px = false,
                    minSizePx = 64,
                )
                val maxTilePixels = (containerSize.width.toLong() * containerSize.height.toLong() * 2L)
                    .coerceIn(1_000_000L, 8_000_000L)
                var sample = 1
                while ((rawRect.width.toLong() / sample) * (rawRect.height.toLong() / sample) > maxTilePixels && sample < 8) {
                    sample *= 2
                }
                val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    BitmapRegionDecoder.newInstance(f.absolutePath)
                } else {
                    @Suppress("DEPRECATION")
                    BitmapRegionDecoder.newInstance(f.absolutePath, false)
                }
                try {
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val regionRaw = decoder.decodeRegion(
                        Rect(rawRect.left, rawRect.top, rawRect.right, rawRect.bottom),
                        opts,
                    ) ?: return@runCatching null
                    val oriented = GalleryThumbnailCache.applyExifOrientation(regionRaw, orient)
                    HdTileOverlay(bitmap = oriented, rect = visibleDisp)
                } finally {
                    decoder.recycle()
                }
            }.getOrNull()
        }
        if (loadedTile != null) hdTile = loadedTile
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(item.path) {
                detectTapGestures(
                    onTap = { onToggleChrome() },
                    onDoubleTap = {
                        if (scale > 1.2f) {
                            scale = 1f
                            offset = Offset.Zero
                            hdTile = null
                        } else {
                            scale = 3f
                        }
                    },
                )
            }
            // H3: Only consume transform gestures when >= 2 pointers (pinch) OR zoomed in (scale > 1.01f),
            // so 1x single-finger horizontal swipes pass cleanly to HorizontalPager for photo paging!
            .pointerInput(item.path) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount >= 2 || scale > 1.01f) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val nextScale = (scale * zoomChange).coerceIn(1f, 8f)
                            scale = nextScale
                            offset = if (nextScale <= 1.01f) {
                                Offset.Zero
                            } else {
                                val maxPanX = (containerSize.width * (nextScale - 1f)) / 2f
                                val maxPanY = (containerSize.height * (nextScale - 1f)) / 2f
                                Offset(
                                    x = (offset.x + panChange.x).coerceIn(-maxPanX, maxPanX),
                                    y = (offset.y + panChange.y).coerceIn(-maxPanY, maxPanY),
                                )
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val baseBmp = bitmap
        val tileOverlay = hdTile
        if (baseBmp != null && !baseBmp.isRecycled) {
            val density = LocalDensity.current
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            ) {
                if (containerSize.width > 0 && containerSize.height > 0 && baseBmp.width > 0 && baseBmp.height > 0) {
                    val fitScale = minOf(
                        containerSize.width.toFloat() / baseBmp.width,
                        containerSize.height.toFloat() / baseBmp.height,
                    )
                    val fitW = baseBmp.width * fitScale
                    val fitH = baseBmp.height * fitScale
                    val fitWDp = with(density) { fitW.toDp() }
                    val fitHDp = with(density) { fitH.toDp() }
                    Box(modifier = Modifier.size(fitWDp, fitHDp)) {
                        Image(
                            bitmap = baseBmp.asImageBitmap(),
                            contentDescription = item.name,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (tileOverlay != null && !tileOverlay.bitmap.isRecycled && scale > 2.2f) {
                            val r = tileOverlay.rect
                            val leftDp = with(density) { (r.left * fitW).toDp() }
                            val topDp = with(density) { (r.top * fitH).toDp() }
                            val widthDp = with(density) { ((r.right - r.left) * fitW).toDp() }
                            val heightDp = with(density) { ((r.bottom - r.top) * fitH).toDp() }
                            Image(
                                bitmap = tileOverlay.bitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier
                                    .offset(x = leftDp, y = topDp)
                                    .size(widthDp, heightDp),
                            )
                        }
                    }
                } else {
                    Image(
                        bitmap = baseBmp.asImageBitmap(),
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        } else {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

@Composable
private fun VideoViewerPage(
    item: MediaItem,
    isCurrentPage: Boolean,
    thumbnailCache: GalleryThumbnailCache,
    onToggleChrome: () -> Unit,
    onOpenWith: () -> Unit,
) {
    val context = LocalContext.current
    val s = LocalAppStrings.current

    var poster by remember(item.path) { mutableStateOf<Bitmap?>(thumbnailCache.peekMemory(item, 512)) }
    var playbackStarted by remember(item.path) { mutableStateOf(false) }
    var isPlaying by remember(item.path) { mutableStateOf(false) }
    var playbackError by remember(item.path) { mutableStateOf(false) }
    var positionMs by remember(item.path) { mutableLongStateOf(0L) }
    var durationMs by remember(item.path) { mutableLongStateOf(item.durationMs.coerceAtLeast(1L)) }
    var videoAspectRatio by remember(item.path) {
        val initialRatio = if (item.effectiveWidth > 0 && item.effectiveHeight > 0) {
            item.effectiveWidth.toFloat() / item.effectiveHeight.toFloat()
        } else {
            16f / 9f
        }
        mutableFloatStateOf(initialRatio.coerceIn(0.25f, 4f))
    }
    var playerRef by remember(item.path) { mutableStateOf<MediaPlayer?>(null) }

    LaunchedEffect(item.path) {
        if (poster == null) {
            poster = thumbnailCache.loadThumbnail(item, 512)
        }
    }

    // Pause when swiped away
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage && isPlaying) {
            runCatching { playerRef?.pause() }
            isPlaying = false
        }
    }

    LaunchedEffect(isPlaying, playerRef) {
        while (isActive && isPlaying) {
            val p = playerRef ?: break
            runCatching {
                positionMs = p.currentPosition.toLong().coerceAtLeast(0L)
                if (p.duration > 0) durationMs = p.duration.toLong()
            }
            delay(250L)
        }
    }

    DisposableEffect(item.path) {
        onDispose {
            runCatching { playerRef?.release() }
            playerRef = null
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .clickable { onToggleChrome() },
    ) {
        if (playbackError) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
            ) {
                Text(s[Str.GALLERY_VIDEO_PLAYBACK_ERROR], color = Color.White)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onOpenWith) {
                    Text(s[Str.OPEN_WITH], color = Color.White)
                }
            }
        } else if (!playbackStarted) {
            val bmp = poster
            if (bmp != null && !bmp.isRecycled) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = item.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.65f),
                modifier = Modifier
                    .size(64.dp)
                    .clickable { playbackStarted = true },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = s[Str.GALLERY_PLAY_VIDEO],
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        } else {
            BoxWithConstraints(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                val boxRatio = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 16f / 9f
                val surfaceModifier = if (videoAspectRatio > boxRatio) {
                    Modifier.fillMaxWidth().aspectRatio(videoAspectRatio)
                } else {
                    Modifier.fillMaxHeight().aspectRatio(videoAspectRatio, matchHeightConstraintsFirst = true)
                }
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    val mp = MediaPlayer()
                                    runCatching {
                                        when (val p = item.path) {
                                            is VfsPath.Local -> mp.setDataSource(p.normalized)
                                            is VfsPath.Saf -> {
                                                val safFs = (ctx.applicationContext as com.minifile.explorer.MiniFileApplication)
                                                    .container.safFileSystem
                                                mp.setDataSource(ctx, safFs.buildDocumentUri(p))
                                            }
                                            else -> throw UnsupportedOperationException("Unsupported video source")
                                        }
                                        mp.setDisplay(holder)
                                        mp.setOnVideoSizeChangedListener { _, vw, vh ->
                                            if (vw > 0 && vh > 0) {
                                                videoAspectRatio = (vw.toFloat() / vh.toFloat()).coerceIn(0.25f, 4f)
                                            }
                                        }
                                        mp.setOnPreparedListener { ready ->
                                            durationMs = ready.duration.toLong().coerceAtLeast(1L)
                                            if (ready.videoWidth > 0 && ready.videoHeight > 0) {
                                                videoAspectRatio = (ready.videoWidth.toFloat() / ready.videoHeight.toFloat()).coerceIn(0.25f, 4f)
                                            }
                                            ready.start()
                                            isPlaying = true
                                        }
                                        mp.setOnCompletionListener {
                                            isPlaying = false
                                            positionMs = durationMs
                                        }
                                        mp.setOnErrorListener { _, _, _ ->
                                            playbackError = true
                                            isPlaying = false
                                            true
                                        }
                                        mp.prepareAsync()
                                        playerRef = mp
                                    }.onFailure {
                                        runCatching { mp.release() }
                                        playbackError = true
                                    }
                                }

                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    runCatching { playerRef?.release() }
                                    playerRef = null
                                    isPlaying = false
                                }
                            })
                        }
                    },
                    modifier = surfaceModifier,
                )
            }

            // Video transport controls above bottom action bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 84.dp)
                    .background(Color.Black.copy(alpha = 0.55f), shape = CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                IconButton(
                    onClick = {
                        val p = playerRef ?: return@IconButton
                        runCatching {
                            if (p.isPlaying) {
                                p.pause()
                                isPlaying = false
                            } else {
                                p.start()
                                isPlaying = true
                            }
                        }
                    },
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) s[Str.GALLERY_PAUSE_VIDEO] else s[Str.GALLERY_PLAY_VIDEO],
                        tint = Color.White,
                    )
                }
                Text(
                    text = TimelineBuilder.formatDuration(positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
                Slider(
                    value = (positionMs.toFloat() / durationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f),
                    onValueChange = { frac ->
                        val targetMs = (frac * durationMs).toLong()
                        positionMs = targetMs
                        playerRef?.let { mp ->
                            runCatching {
                                mp.seekTo(targetMs, MediaPlayer.SEEK_CLOSEST)
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                Text(
                    text = TimelineBuilder.formatDuration(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}

private fun MediaItem.toFallbackNode(): VfsNode = VfsNode(
    path = path,
    name = name,
    isDirectory = false,
    size = sizeBytes,
    lastModified = modifiedMs,
    mimeType = mime,
)
