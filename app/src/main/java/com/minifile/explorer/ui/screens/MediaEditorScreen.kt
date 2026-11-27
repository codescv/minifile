package com.minifile.explorer.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.minifile.explorer.core.gallery.CoreCropMath
import com.minifile.explorer.core.gallery.CropAspectRatio
import com.minifile.explorer.core.gallery.CropCorner
import com.minifile.explorer.core.gallery.EditRecipe
import com.minifile.explorer.core.gallery.EditSourceInfo
import com.minifile.explorer.core.gallery.MediaExtensionRegistry
import com.minifile.explorer.core.gallery.MediaItem
import com.minifile.explorer.core.gallery.MediaKind
import com.minifile.explorer.core.gallery.NormalizedRect
import com.minifile.explorer.core.gallery.RemuxPlanResult
import com.minifile.explorer.core.gallery.RemuxPlanner
import com.minifile.explorer.core.gallery.TimelineBuilder
import com.minifile.explorer.core.gallery.VideoEditSpec
import com.minifile.explorer.core.i18n.Str
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.data.media.VideoProbeInfo
import com.minifile.explorer.ui.MainViewModel
import com.minifile.explorer.ui.i18n.LocalAppStrings
import com.minifile.explorer.ui.util.UiFormatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MediaEditorScreen(
    sourcePath: VfsPath,
    viewModel: MainViewModel,
    onClose: () -> Unit,
) {
    val s = LocalAppStrings.current
    val kind = remember(sourcePath) {
        MediaExtensionRegistry.classify(sourcePath.displayName) ?: MediaKind.IMAGE
    }

    if (kind == MediaKind.VIDEO) {
        VideoEditorScreen(sourcePath = sourcePath, viewModel = viewModel, onClose = onClose)
    } else {
        ImageEditorScreen(sourcePath = sourcePath, viewModel = viewModel, onClose = onClose)
    }
}

@Composable
private fun ImageEditorScreen(
    sourcePath: VfsPath,
    viewModel: MainViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val s = LocalAppStrings.current
    val container = viewModel.container

    var sourceInfo by remember(sourcePath) { mutableStateOf<EditSourceInfo?>(null) }
    var previewBitmap by remember(sourcePath) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(sourcePath) { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }

    var rotateDegrees by remember(sourcePath) { mutableIntStateOf(0) }
    var flipH by remember(sourcePath) { mutableStateOf(false) }
    var cropRect by remember(sourcePath) { mutableStateOf(NormalizedRect.FULL) }
    var selectedAspect by remember(sourcePath) { mutableStateOf(CropAspectRatio.FREE) }

    var showSaveMenu by remember { mutableStateOf(false) }
    var showReplaceConfirm by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    val recipe = EditRecipe(
        kind = MediaKind.IMAGE,
        rotateDegrees = rotateDegrees,
        flipHorizontal = flipH,
        crop = cropRect.takeUnless { it.isFullImage() },
    )
    val hasChanges = !recipe.isIdentity()
    val isHeic = remember(sourcePath) { MediaExtensionRegistry.isHeic(sourcePath.displayName) }

    LaunchedEffect(sourcePath) {
        loading = true
        val info = container.imageEditorEngine.probeImage(sourcePath)
        sourceInfo = info
        val dummyItem = MediaItem(
            path = sourcePath,
            name = sourcePath.displayName,
            kind = MediaKind.IMAGE,
            mime = info?.mime ?: "image/jpeg",
            sizeBytes = 0L,
            bestDateMs = 0L,
            modifiedMs = System.currentTimeMillis(),
        )
        previewBitmap = container.galleryThumbnailCache.loadViewerBitmap(dummyItem, maxSidePx = 2048)
        loading = false
    }

    fun requestClose() {
        if (saving) return
        if (hasChanges) showDiscardDialog = true else onClose()
    }

    fun performSave(replaceOriginal: Boolean) {
        if (saving) return
        saving = true
        viewModel.saveImageEdit(
            context = context,
            sourcePath = sourcePath,
            recipe = recipe,
            replaceOriginal = replaceOriginal && sourcePath is VfsPath.Local,
        ) { ok ->
            saving = false
            if (ok) onClose()
        }
    }

    BackHandler(onBack = ::requestClose)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Top bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            IconButton(onClick = ::requestClose, enabled = !saving) {
                Icon(Icons.Filled.Close, contentDescription = s[Str.CLOSE], tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            if (isHeic) {
                Text(
                    text = s[Str.GALLERY_HEIC_SAVED_AS_JPEG_HINT],
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Box {
                Button(
                    enabled = hasChanges && !saving && !loading,
                    onClick = { showSaveMenu = true },
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(s[Str.SAVE])
                    }
                }
                DropdownMenu(expanded = showSaveMenu, onDismissRequest = { showSaveMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(s[Str.GALLERY_SAVE_COPY]) },
                        onClick = {
                            showSaveMenu = false
                            performSave(replaceOriginal = false)
                        },
                    )
                    if (!isHeic && sourcePath is VfsPath.Local) {
                        DropdownMenuItem(
                            text = { Text(s[Str.GALLERY_REPLACE_ORIGINAL]) },
                            onClick = {
                                showSaveMenu = false
                                showReplaceConfirm = true
                            },
                        )
                    }
                }
            }
        }

        // Canvas + Rule-of-thirds crop frame
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            val bmp = previewBitmap
            if (loading || bmp == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                val displayRatio = remember(bmp, rotateDegrees) {
                    val swapped = rotateDegrees == 90 || rotateDegrees == 270
                    val w = if (swapped) bmp.height else bmp.width
                    val h = if (swapped) bmp.width else bmp.height
                    (w.toFloat() / h.coerceAtLeast(1).toFloat()).coerceAtLeast(0.1f)
                }
                CropImageCanvas(
                    bitmap = bmp,
                    rotateDegrees = rotateDegrees,
                    flipHorizontal = flipH,
                    cropRect = cropRect,
                    aspectRatio = when (selectedAspect) {
                        CropAspectRatio.FREE -> null
                        CropAspectRatio.ORIGINAL -> displayRatio
                        else -> selectedAspect.ratio
                    },
                    displayAspectRatio = displayRatio,
                    onCropChange = { cropRect = it },
                )
            }
        }

        // Aspect ratio chip row
        val aspectOptions = listOf(
            CropAspectRatio.FREE to s[Str.GALLERY_CROP_FREE],
            CropAspectRatio.ORIGINAL to s[Str.GALLERY_CROP_ORIGINAL],
            CropAspectRatio.SQUARE_1_1 to "1:1",
            CropAspectRatio.RATIO_4_3 to "4:3",
            CropAspectRatio.RATIO_3_4 to "3:4",
            CropAspectRatio.RATIO_16_9 to "16:9",
            CropAspectRatio.RATIO_9_16 to "9:16",
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(aspectOptions) { (ratio, label) ->
                FilterChip(
                    selected = selectedAspect == ratio,
                    onClick = {
                        selectedAspect = ratio
                        val bmp = previewBitmap
                        val dispRatio = if (bmp != null) {
                            val swapped = rotateDegrees == 90 || rotateDegrees == 270
                            val w = if (swapped) bmp.height else bmp.width
                            val h = if (swapped) bmp.width else bmp.height
                            w.toFloat() / h.coerceAtLeast(1).toFloat()
                        } else {
                            1f
                        }
                        val target = when (ratio) {
                            CropAspectRatio.FREE -> null
                            CropAspectRatio.ORIGINAL -> dispRatio
                            else -> ratio.ratio
                        }
                        cropRect = CoreCropMath.centeredCropForAspectRatio(target, dispRatio)
                    },
                    label = { Text(label) },
                )
            }
        }

        // Bottom tools: Rotate 90°, Flip, Reset
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
        ) {
            EditorToolButton(Icons.AutoMirrored.Filled.RotateRight, s[Str.GALLERY_ROTATE]) {
                rotateDegrees = (rotateDegrees + 90) % 360
                cropRect = NormalizedRect.FULL
            }
            EditorToolButton(Icons.Filled.Flip, s[Str.GALLERY_FLIP]) {
                flipH = !flipH
            }
            EditorToolButton(Icons.Filled.Refresh, s[Str.GALLERY_RESET]) {
                rotateDegrees = 0
                flipH = false
                cropRect = NormalizedRect.FULL
                selectedAspect = CropAspectRatio.FREE
            }
        }
    }

    if (showReplaceConfirm) {
        AlertDialog(
            onDismissRequest = { showReplaceConfirm = false },
            title = { Text(s[Str.GALLERY_REPLACE_ORIGINAL_CONFIRM_TITLE]) },
            text = { Text(s[Str.GALLERY_REPLACE_ORIGINAL_CONFIRM_DESC]) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showReplaceConfirm = false
                        performSave(replaceOriginal = true)
                    },
                ) {
                    Text(s[Str.GALLERY_REPLACE_ORIGINAL], color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReplaceConfirm = false }) { Text(s[Str.CANCEL]) }
            },
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(s[Str.GALLERY_DISCARD_EDITS_TITLE]) },
            text = { Text(s[Str.GALLERY_DISCARD_EDITS_DESC]) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onClose()
                    },
                ) {
                    Text(s[Str.DISCARD], color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text(s[Str.KEEP]) }
            },
        )
    }
}

@Composable
private fun CropImageCanvas(
    bitmap: Bitmap,
    rotateDegrees: Int,
    flipHorizontal: Boolean,
    cropRect: NormalizedRect,
    aspectRatio: Float?,
    displayAspectRatio: Float,
    onCropChange: (NormalizedRect) -> Unit,
) {
    val density = LocalDensity.current
    val hitRadiusPx = with(density) { 48.dp.toPx() }
    var activeCorner by remember { mutableStateOf<CropCorner?>(null) }
    var isDraggingOverlay by remember { mutableStateOf(false) }

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        val boxW = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val boxH = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val (imgW, imgH) = if (boxW / boxH > displayAspectRatio) {
            (boxH * displayAspectRatio) to boxH
        } else {
            boxW to (boxW / displayAspectRatio)
        }
        val imgWDp = with(density) { imgW.toDp() }
        val imgHDp = with(density) { imgH.toDp() }

        Box(
            modifier = Modifier
                .width(imgWDp)
                .height(imgHDp),
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = rotateDegrees.toFloat()
                        scaleX = if (flipHorizontal) -1f else 1f
                    },
            )

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(cropRect, aspectRatio, displayAspectRatio, imgW, imgH) {
                        detectDragGestures(
                            onDragStart = { start ->
                                isDraggingOverlay = true
                                val tl = Offset(cropRect.left * imgW, cropRect.top * imgH)
                                val tr = Offset(cropRect.right * imgW, cropRect.top * imgH)
                                val bl = Offset(cropRect.left * imgW, cropRect.bottom * imgH)
                                val br = Offset(cropRect.right * imgW, cropRect.bottom * imgH)
                                val candidates = listOf(
                                    CropCorner.TOP_LEFT to (start - tl).getDistance(),
                                    CropCorner.TOP_RIGHT to (start - tr).getDistance(),
                                    CropCorner.BOTTOM_LEFT to (start - bl).getDistance(),
                                    CropCorner.BOTTOM_RIGHT to (start - br).getDistance(),
                                )
                                val nearest = candidates.minByOrNull { it.second }
                                activeCorner = if (nearest != null && nearest.second <= hitRadiusPx) {
                                    nearest.first
                                } else {
                                    null
                                }
                            },
                            onDragEnd = {
                                isDraggingOverlay = false
                                activeCorner = null
                            },
                            onDragCancel = {
                                isDraggingOverlay = false
                                activeCorner = null
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                val dx = drag.x / imgW
                                val dy = drag.y / imgH
                                val corner = activeCorner
                                val next = if (corner != null) {
                                    val raw = when (corner) {
                                        CropCorner.TOP_LEFT -> cropRect.copy(left = cropRect.left + dx, top = cropRect.top + dy)
                                        CropCorner.TOP_RIGHT -> cropRect.copy(right = cropRect.right + dx, top = cropRect.top + dy)
                                        CropCorner.BOTTOM_LEFT -> cropRect.copy(left = cropRect.left + dx, bottom = cropRect.bottom + dy)
                                        CropCorner.BOTTOM_RIGHT -> cropRect.copy(right = cropRect.right + dx, bottom = cropRect.bottom + dy)
                                    }
                                    CoreCropMath.constrainCropRect(
                                        rect = raw,
                                        targetAspectRatio = aspectRatio,
                                        displayAspectRatio = displayAspectRatio,
                                        draggedCorner = corner,
                                    )
                                } else {
                                    // Pan crop box inside [0, 1]^2
                                    val w = cropRect.width
                                    val h = cropRect.height
                                    val nl = (cropRect.left + dx).coerceIn(0f, 1f - w)
                                    val nt = (cropRect.top + dy).coerceIn(0f, 1f - h)
                                    NormalizedRect(nl, nt, nl + w, nt + h)
                                }
                                onCropChange(next)
                            },
                        )
                    },
            ) {
                val l = cropRect.left * size.width
                val t = cropRect.top * size.height
                val r = cropRect.right * size.width
                val b = cropRect.bottom * size.height
                val scrim = Color.Black.copy(alpha = 0.55f)

                // Dark scrim outside crop box
                drawRect(scrim, topLeft = Offset.Zero, size = Size(size.width, t))
                drawRect(scrim, topLeft = Offset(0f, b), size = Size(size.width, size.height - b))
                drawRect(scrim, topLeft = Offset(0f, t), size = Size(l, b - t))
                drawRect(scrim, topLeft = Offset(r, t), size = Size(size.width - r, b - t))

                // Rule-of-thirds grid lines (highlighted while dragging)
                val gridAlpha = if (isDraggingOverlay) 0.7f else 0.3f
                val thirdW = (r - l) / 3f
                val thirdH = (b - t) / 3f
                for (i in 1..2) {
                    drawLine(
                        color = Color.White.copy(alpha = gridAlpha),
                        start = Offset(l + thirdW * i, t),
                        end = Offset(l + thirdW * i, b),
                        strokeWidth = 1f,
                    )
                    drawLine(
                        color = Color.White.copy(alpha = gridAlpha),
                        start = Offset(l, t + thirdH * i),
                        end = Offset(r, t + thirdH * i),
                        strokeWidth = 1f,
                    )
                }

                // Crop border + corner handles
                drawRect(
                    color = Color.White,
                    topLeft = Offset(l, t),
                    size = Size(r - l, b - t),
                    style = Stroke(width = 2.dp.toPx()),
                )
                val handleLen = 18.dp.toPx()
                val handleStroke = 4.dp.toPx()
                // Top-left
                drawLine(Color.White, Offset(l, t), Offset(l + handleLen, t), handleStroke)
                drawLine(Color.White, Offset(l, t), Offset(l, t + handleLen), handleStroke)
                // Top-right
                drawLine(Color.White, Offset(r, t), Offset(r - handleLen, t), handleStroke)
                drawLine(Color.White, Offset(r, t), Offset(r, t + handleLen), handleStroke)
                // Bottom-left
                drawLine(Color.White, Offset(l, b), Offset(l + handleLen, b), handleStroke)
                drawLine(Color.White, Offset(l, b), Offset(l, b - handleLen), handleStroke)
                // Bottom-right
                drawLine(Color.White, Offset(r, b), Offset(r - handleLen, b), handleStroke)
                drawLine(Color.White, Offset(r, b), Offset(r, b - handleLen), handleStroke)
            }
        }
    }
}

@Composable
private fun VideoEditorScreen(
    sourcePath: VfsPath,
    viewModel: MainViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = LocalAppStrings.current
    val container = viewModel.container

    var probe by remember(sourcePath) { mutableStateOf<VideoProbeInfo?>(null) }
    var filmstrip by remember(sourcePath) { mutableStateOf<List<Bitmap>>(emptyList()) }
    var loading by remember(sourcePath) { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }

    var trimRange by remember(sourcePath) { mutableStateOf(0f..1f) }
    var middleCutRange by remember(sourcePath) { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    var mute by remember(sourcePath) { mutableStateOf(false) }
    var rotateDegrees by remember(sourcePath) { mutableIntStateOf(0) }

    var showSaveMenu by remember { mutableStateOf(false) }
    var showReplaceConfirm by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    LaunchedEffect(sourcePath) {
        loading = true
        val p = container.losslessMp4Remuxer.probeVideo(sourcePath)
        probe = p
        filmstrip = container.losslessMp4Remuxer.extractFilmstrip(sourcePath, count = 10)
        loading = false
    }

    val durationMs = probe?.sourceInfo?.durationMs ?: 0L
    val isSupportedContainer = remember(sourcePath, probe) {
        MediaExtensionRegistry.isLosslessEditableVideo(sourcePath.displayName, probe?.sourceInfo?.mime)
    }

    val keepRangesMs: List<LongRange> = remember(trimRange, middleCutRange, durationMs) {
        if (durationMs <= 0L) return@remember emptyList()
        val tStart = (trimRange.start * durationMs).toLong().coerceIn(0L, durationMs)
        val tEnd = (trimRange.endInclusive * durationMs).toLong().coerceIn(tStart, durationMs)
        val cut = middleCutRange
        if (cut == null) {
            listOf(tStart..tEnd)
        } else {
            val cStart = (cut.start * durationMs).toLong().coerceIn(tStart, tEnd)
            val cEnd = (cut.endInclusive * durationMs).toLong().coerceIn(cStart, tEnd)
            listOf(tStart..cStart, cEnd..tEnd)
        }
    }

    val remuxPlanResult = remember(probe, keepRangesMs, mute, rotateDegrees) {
        val info = probe?.sourceInfo ?: return@remember null
        RemuxPlanner.plan(
            durationMs = info.durationMs,
            keyframeTimestampsUs = info.keyframeTimestampsUs,
            keepRangesMs = keepRangesMs,
            mute = mute,
            rotationDeltaDegrees = rotateDegrees,
        )
    }

    val recipe = EditRecipe(
        kind = MediaKind.VIDEO,
        rotateDegrees = rotateDegrees,
        video = VideoEditSpec(keepRangesMs = keepRangesMs, mute = mute),
    )
    val hasChanges = durationMs > 0L && !recipe.isIdentity(durationMs)
    val canSave = isSupportedContainer && hasChanges && remuxPlanResult is RemuxPlanResult.Ready && !saving

    fun requestClose() {
        if (saving) return
        if (hasChanges) showDiscardDialog = true else onClose()
    }

    fun performSave(replaceOriginal: Boolean) {
        if (!canSave) return
        saving = true
        viewModel.saveVideoEdit(
            context = context,
            sourcePath = sourcePath,
            recipe = recipe,
            probe = probe,
            replaceOriginal = replaceOriginal && sourcePath is VfsPath.Local,
        ) { ok ->
            saving = false
            if (ok) onClose()
        }
    }

    BackHandler(onBack = ::requestClose)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            IconButton(onClick = ::requestClose, enabled = !saving) {
                Icon(Icons.Filled.Close, contentDescription = s[Str.CLOSE], tint = Color.White)
            }
            Text(
                text = sourcePath.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Box {
                Button(enabled = canSave, onClick = { showSaveMenu = true }) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(s[Str.SAVE])
                    }
                }
                DropdownMenu(expanded = showSaveMenu, onDismissRequest = { showSaveMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(s[Str.GALLERY_SAVE_COPY]) },
                        onClick = {
                            showSaveMenu = false
                            performSave(replaceOriginal = false)
                        },
                    )
                    if (sourcePath is VfsPath.Local) {
                        DropdownMenuItem(
                            text = { Text(s[Str.GALLERY_REPLACE_ORIGINAL]) },
                            onClick = {
                                showSaveMenu = false
                                showReplaceConfirm = true
                            },
                        )
                    }
                }
            }
        }

        // Preview frame
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            when {
                loading -> CircularProgressIndicator(color = Color.White)
                !isSupportedContainer -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s[Str.GALLERY_UNSUPPORTED_VIDEO_EDIT], color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val st = withContext(Dispatchers.IO) { container.fileSystem.stat(sourcePath) }
                                    if (st != null) {
                                        UiFormatters.openExternallyAsync(
                                            context = context,
                                            safFs = container.safFileSystem,
                                            fs = container.fileSystem,
                                            node = st,
                                            forceChooser = true,
                                        )
                                    }
                                }
                            },
                        ) {
                            Text(s[Str.OPEN_WITH], color = Color.White)
                        }
                    }
                }

                else -> {
                    val previewFrame = filmstrip.firstOrNull()
                    if (previewFrame != null && !previewFrame.isRecycled) {
                        Image(
                            bitmap = previewFrame.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { rotationZ = rotateDegrees.toFloat() },
                        )
                    }
                }
            }
        }

        if (!loading && isSupportedContainer && durationMs > 0L) {
            // Keyframe alignment / actual cut feedback banner
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                when (val r = remuxPlanResult) {
                    is RemuxPlanResult.Ready -> {
                        val cutStart = r.plan.actualCutStartUs
                        val cutEnd = r.plan.actualCutEndUs
                        val statusText = if (cutStart != null && cutEnd != null) {
                            s.format(
                                Str.GALLERY_ACTUAL_CUT_FMT,
                                TimelineBuilder.formatDuration(cutStart / 1000L),
                                TimelineBuilder.formatDuration(cutEnd / 1000L),
                            ) + " · " + s[Str.GALLERY_KEYFRAMES_ALIGNED]
                        } else {
                            "${TimelineBuilder.formatDuration(r.plan.outputDurationUs / 1000L)} · ${s[Str.GALLERY_KEYFRAMES_ALIGNED]}"
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    RemuxPlanResult.TooShortForLossless -> {
                        Text(
                            text = s[Str.GALLERY_CLIP_TOO_SHORT_LOSSLESS],
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    else -> Unit
                }

                Spacer(Modifier.height(8.dp))

                // Filmstrip bar with deleted middle-cut shaded overlay
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        for (frame in filmstrip) {
                            if (!frame.isRecycled) {
                                Image(
                                    bitmap = frame.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                )
                            }
                        }
                    }
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val dark = Color.Black.copy(alpha = 0.72f)
                        val t0 = trimRange.start * size.width
                        val t1 = trimRange.endInclusive * size.width
                        drawRect(dark, Offset.Zero, Size(t0, size.height))
                        drawRect(dark, Offset(t1, 0f), Size(size.width - t1, size.height))
                        middleCutRange?.let { cut ->
                            val c0 = cut.start * size.width
                            val c1 = cut.endInclusive * size.width
                            drawRect(Color.Black.copy(alpha = 0.78f), Offset(c0, 0f), Size((c1 - c0).coerceAtLeast(0f), size.height))
                        }
                    }
                }

                // Head/tail Trim slider
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(s[Str.GALLERY_TRIM], style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.width(64.dp))
                    RangeSlider(
                        value = trimRange,
                        onValueChange = { next ->
                            if (next.endInclusive - next.start >= 0.05f) {
                                trimRange = next
                                middleCutRange?.let { cut ->
                                    if (cut.start <= next.start + 0.02f || cut.endInclusive >= next.endInclusive - 0.02f) {
                                        middleCutRange = null
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                // Optional single middle Cut slider
                middleCutRange?.let { cut ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(s[Str.GALLERY_CUT_MIDDLE], style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.width(64.dp))
                        RangeSlider(
                            value = cut,
                            valueRange = trimRange.start..trimRange.endInclusive,
                            onValueChange = { next ->
                                if (next.endInclusive - next.start >= 0.03f) {
                                    middleCutRange = next
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Bottom tool buttons: Cut middle, Mute, Rotate 90°, Reset
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
            ) {
                EditorToolButton(
                    icon = Icons.Filled.ContentCut,
                    label = if (middleCutRange == null) s[Str.GALLERY_CUT_MIDDLE] else s[Str.GALLERY_CLEAR_CUT],
                ) {
                    if (middleCutRange == null) {
                        val span = trimRange.endInclusive - trimRange.start
                        middleCutRange = (trimRange.start + span * 0.35f)..(trimRange.start + span * 0.65f)
                    } else {
                        middleCutRange = null
                    }
                }
                EditorToolButton(
                    icon = if (mute) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    label = if (mute) s[Str.GALLERY_UNMUTE] else s[Str.GALLERY_MUTE],
                ) {
                    mute = !mute
                }
                EditorToolButton(Icons.AutoMirrored.Filled.RotateRight, s[Str.GALLERY_ROTATE]) {
                    rotateDegrees = (rotateDegrees + 90) % 360
                }
                EditorToolButton(Icons.Filled.Refresh, s[Str.GALLERY_RESET]) {
                    trimRange = 0f..1f
                    middleCutRange = null
                    mute = false
                    rotateDegrees = 0
                }
            }
        }
    }

    if (showReplaceConfirm) {
        AlertDialog(
            onDismissRequest = { showReplaceConfirm = false },
            title = { Text(s[Str.GALLERY_REPLACE_ORIGINAL_CONFIRM_TITLE]) },
            text = { Text(s[Str.GALLERY_REPLACE_ORIGINAL_CONFIRM_DESC]) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showReplaceConfirm = false
                        performSave(replaceOriginal = true)
                    },
                ) {
                    Text(s[Str.GALLERY_REPLACE_ORIGINAL], color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReplaceConfirm = false }) { Text(s[Str.CANCEL]) }
            },
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(s[Str.GALLERY_DISCARD_EDITS_TITLE]) },
            text = { Text(s[Str.GALLERY_DISCARD_EDITS_DESC]) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onClose()
                    },
                ) {
                    Text(s[Str.DISCARD], color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text(s[Str.KEEP]) }
            },
        )
    }
}

@Composable
private fun EditorToolButton(
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
