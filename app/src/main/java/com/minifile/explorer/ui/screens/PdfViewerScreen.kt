package com.minifile.explorer.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.data.SafFileSystem
import com.minifile.explorer.ui.MainViewModel
import com.minifile.explorer.ui.util.UiFormatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val MIN_ZOOM = 1.0f
private const val MAX_ZOOM = 4.0f
private const val MAX_RENDER_PX = 3072
private const val BITMAP_CACHE_BYTES = 32 * 1024 * 1024 // 32 MiB

private data class PdfPageSpec(
    val index: Int,
    val widthPt: Int,
    val heightPt: Int,
) {
    val aspectRatio: Float
        get() = if (widthPt > 0 && heightPt > 0) {
            (widthPt.toFloat() / heightPt.toFloat()).coerceIn(0.2f, 5.0f)
        } else {
            0.707f // ISO A4 fallback
        }
}

private class PdfDocumentSession(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val tempFile: File?,
) {
    private val mutex = Mutex()
    private var closed = false

    val pageCount: Int = renderer.pageCount

    suspend fun loadPageSpecs(): List<PdfPageSpec> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock emptyList()
            val count = renderer.pageCount
            val specs = ArrayList<PdfPageSpec>(count)
            for (i in 0 until count) {
                val page = renderer.openPage(i)
                try {
                    specs.add(PdfPageSpec(index = i, widthPt = page.width, heightPt = page.height))
                } finally {
                    page.close()
                }
            }
            specs
        }
    }

    suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap? = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (closed || pageIndex !in 0 until renderer.pageCount) return@withLock null
            val page = renderer.openPage(pageIndex)
            try {
                val pageW = page.width.coerceAtLeast(1)
                val pageH = page.height.coerceAtLeast(1)
                val scale = (targetWidthPx.toFloat() / pageW.toFloat()).coerceAtLeast(1f)
                var bmpW = (pageW * scale).roundToInt().coerceAtLeast(1)
                var bmpH = (pageH * scale).roundToInt().coerceAtLeast(1)
                val largest = max(bmpW, bmpH)
                if (largest > MAX_RENDER_PX) {
                    val clamp = MAX_RENDER_PX.toFloat() / largest.toFloat()
                    bmpW = (bmpW * clamp).roundToInt().coerceAtLeast(1)
                    bmpH = (bmpH * clamp).roundToInt().coerceAtLeast(1)
                }
                val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } finally {
                page.close()
            }
        }
    }

    suspend fun close() {
        withContext(NonCancellable + Dispatchers.IO) {
            mutex.withLock {
                if (closed) return@withLock
                closed = true
                runCatching { renderer.close() }
                runCatching { pfd.close() }
                tempFile?.let { runCatching { it.delete() } }
            }
        }
    }
}

private suspend fun openPdfSession(
    context: Context,
    path: VfsPath,
    fs: FileSystem,
    safFs: SafFileSystem,
): PdfDocumentSession = withContext(Dispatchers.IO) {
    when (path) {
        is VfsPath.Local -> {
            val file = File(path.normalized)
            if (!file.exists() || !file.isFile) {
                throw FileNotFoundException("PDF file does not exist: ${path.displayName}")
            }
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val renderer = PdfRenderer(pfd)
                PdfDocumentSession(pfd = pfd, renderer = renderer, tempFile = null)
            } catch (t: Throwable) {
                runCatching { pfd.close() }
                throw t
            }
        }

        is VfsPath.Saf -> {
            val docUri = safFs.buildDocumentUri(path)
            var directPfd: ParcelFileDescriptor? = null
            try {
                directPfd = context.contentResolver.openFileDescriptor(docUri, "r")
                if (directPfd != null && directPfd.statSize >= 0L) {
                    val renderer = PdfRenderer(directPfd)
                    return@withContext PdfDocumentSession(
                        pfd = directPfd,
                        renderer = renderer,
                        tempFile = null,
                    )
                }
            } catch (sec: SecurityException) {
                directPfd?.let { runCatching { it.close() } }
                throw sec
            } catch (_: Exception) {
                // Provider returned non-seekable pipe descriptor or unsupported fd; fall back to cache copy.
            }
            directPfd?.let { runCatching { it.close() } }

            val cacheDir = File(context.cacheDir, "pdf_viewer").apply { mkdirs() }
            val tempFile = File.createTempFile("doc_", ".pdf", cacheDir)
            var fallbackPfd: ParcelFileDescriptor? = null
            try {
                fs.openInput(path).use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
                fallbackPfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(fallbackPfd)
                PdfDocumentSession(pfd = fallbackPfd, renderer = renderer, tempFile = tempFile)
            } catch (t: Throwable) {
                fallbackPfd?.let { runCatching { it.close() } }
                runCatching { tempFile.delete() }
                throw t
            }
        }

        is VfsPath.Ftp, is VfsPath.GDrive -> {
            val cacheDir = File(context.cacheDir, "pdf_viewer").apply { mkdirs() }
            val tempFile = File.createTempFile("doc_", ".pdf", cacheDir)
            var fallbackPfd: ParcelFileDescriptor? = null
            try {
                fs.openInput(path).use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
                fallbackPfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(fallbackPfd)
                PdfDocumentSession(pfd = fallbackPfd, renderer = renderer, tempFile = tempFile)
            } catch (t: Throwable) {
                fallbackPfd?.let { runCatching { it.close() } }
                runCatching { tempFile.delete() }
                throw t
            }
        }
    }
}

private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    pdfPath: VfsPath,
    viewModel: MainViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var session by remember(pdfPath) { mutableStateOf<PdfDocumentSession?>(null) }
    var pageSpecs by remember(pdfPath) { mutableStateOf<List<PdfPageSpec>>(emptyList()) }
    var isLoading by remember(pdfPath) { mutableStateOf(true) }
    var errorMessage by remember(pdfPath) { mutableStateOf<String?>(null) }

    var zoom by remember(pdfPath) { mutableFloatStateOf(1.0f) }
    var panX by remember(pdfPath) { mutableFloatStateOf(0.0f) }
    var panY by remember(pdfPath) { mutableFloatStateOf(0.0f) }
    var showJumpDialog by remember { mutableStateOf(false) }

    val bitmapCache = remember(pdfPath) {
        object : LruCache<String, Bitmap>(BITMAP_CACHE_BYTES) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }
    }

    BackHandler { onClose() }

    DisposableEffect(pdfPath) {
        var openedSession: PdfDocumentSession? = null
        val job = scope.launch {
            isLoading = true
            errorMessage = null
            try {
                val loaded = openPdfSession(
                    context = context,
                    path = pdfPath,
                    fs = viewModel.container.fileSystem,
                    safFs = viewModel.container.safFileSystem,
                )
                openedSession = loaded
                val specs = loaded.loadPageSpecs()
                session = loaded
                pageSpecs = specs
            } catch (_: SecurityException) {
                errorMessage = "This PDF is password-protected or access was denied. Use 'Open with…' to open it in an external PDF reader."
            } catch (e: Exception) {
                errorMessage = e.message ?: "Unable to open PDF document."
            } finally {
                isLoading = false
            }
        }
        onDispose {
            job.cancel()
            val toClose = openedSession ?: session
            session = null
            bitmapCache.evictAll()
            if (toClose != null) {
                cleanupScope.launch { toClose.close() }
            }
        }
    }

    val pageCount = pageSpecs.size
    val currentPage by remember(pageCount) {
        derivedStateOf {
            if (pageCount == 0) {
                0
            } else {
                val visible = listState.layoutInfo.visibleItemsInfo
                if (visible.isEmpty()) {
                    (listState.firstVisibleItemIndex + 1).coerceIn(1, pageCount)
                } else {
                    val viewportCenter = (listState.layoutInfo.viewportStartOffset + listState.layoutInfo.viewportEndOffset) / 2
                    val centerItem = visible.minByOrNull { item ->
                        val itemCenter = item.offset + item.size / 2
                        abs(itemCenter - viewportCenter)
                    } ?: visible.first()
                    (centerItem.index + 1).coerceIn(1, pageCount)
                }
            }
        }
    }

    fun openExternally() {
        val node = VfsNode(
            path = pdfPath,
            name = pdfPath.displayName,
            isDirectory = false,
            size = 0L,
            lastModified = 0L,
            mimeType = "application/pdf",
        )
        scope.launch {
            UiFormatters.openExternallyAsync(
                context = context,
                safFs = viewModel.container.safFileSystem,
                fs = viewModel.container.fileSystem,
                node = node,
                forceChooser = true,
            ).onFailure { err ->
                viewModel.emitSimpleMessage("Cannot open externally: ${err.message}")
            }
        }
    }

    fun applyZoom(newZoom: Float) {
        val clamped = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        zoom = clamped
        if (clamped <= 1.01f) {
            panX = 0f
            panY = 0f
        }
    }

    val renderScaleBucket = when {
        zoom >= 2.5f -> 3.0f
        zoom >= 1.5f -> 2.0f
        else -> 1.35f // Crisp high-DPI default even at 100% zoom
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = pdfPath.displayName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (pageCount > 0) {
                            Text(
                                text = "Page $currentPage / $pageCount",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                actions = {
                    if (pageCount > 0) {
                        IconButton(onClick = { showJumpDialog = true }) {
                            Icon(Icons.Filled.FindInPage, contentDescription = "Jump to page")
                        }
                    }
                    IconButton(onClick = { openExternally() }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open with…")
                    }
                },
            )
        },
        bottomBar = {
            if (!isLoading && errorMessage == null && pageCount > 0) {
                Surface(
                    tonalElevation = 3.dp,
                    shadowElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.clickable { showJumpDialog = true },
                        ) {
                            Text(
                                text = "Page $currentPage / $pageCount",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            IconButton(
                                onClick = { applyZoom(zoom - 0.25f) },
                                enabled = zoom > MIN_ZOOM + 0.01f,
                            ) {
                                Icon(Icons.Filled.ZoomOut, contentDescription = "Zoom out")
                            }
                            TextButton(
                                onClick = { applyZoom(1.0f) },
                            ) {
                                Text(
                                    text = "${(zoom * 100f).roundToInt()}%",
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            IconButton(
                                onClick = { applyZoom(zoom + 0.25f) },
                                enabled = zoom < MAX_ZOOM - 0.01f,
                            ) {
                                Icon(Icons.Filled.ZoomIn, contentDescription = "Zoom in")
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .clipToBounds(),
        ) {
            val density = LocalDensity.current
            val viewportWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(360f)
            val viewportHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(480f)
            val targetRenderWidthPx = (viewportWidthPx * renderScaleBucket).roundToInt().coerceIn(512, MAX_RENDER_PX)

            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("Opening PDF…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                errorMessage != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PictureAsPdf,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = errorMessage!!,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = { openExternally() }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Open with…")
                        }
                    }
                }

                pageSpecs.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("This PDF has 0 pages.", style = MaterialTheme.typography.bodyLarge)
                    }
                }

                else -> {
                    val activeSession = session
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(viewportWidthPx, viewportHeightPx) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.count { it.pressed }
                                        if (pressed >= 2) {
                                            val zoomChange = event.calculateZoom()
                                            val panChange = event.calculatePan()
                                            val newZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                            val maxPanX = (viewportWidthPx * (newZoom - 1f)) / 2f
                                            val maxPanY = (viewportHeightPx * (newZoom - 1f)) / 2f
                                            zoom = newZoom
                                            if (newZoom <= 1.01f) {
                                                panX = 0f
                                                panY = 0f
                                            } else {
                                                panX = (panX + panChange.x).coerceIn(-maxPanX, maxPanX)
                                                panY = (panY + panChange.y).coerceIn(-maxPanY, maxPanY)
                                            }
                                            event.changes.forEach { ch ->
                                                if (ch.positionChanged()) ch.consume()
                                            }
                                        } else if (pressed == 1 && zoom > 1.01f) {
                                            val panChange = event.calculatePan()
                                            if (abs(panChange.x) > abs(panChange.y) && abs(panChange.x) > 0.5f) {
                                                val maxPanX = (viewportWidthPx * (zoom - 1f)) / 2f
                                                panX = (panX + panChange.x).coerceIn(-maxPanX, maxPanX)
                                                event.changes.forEach { ch ->
                                                    if (ch.positionChanged()) ch.consume()
                                                }
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = panX
                                translationY = panY
                            },
                    ) {
                        items(
                            count = pageSpecs.size,
                            key = { idx -> pageSpecs[idx].index },
                        ) { idx ->
                            val spec = pageSpecs[idx]
                            if (activeSession != null) {
                                PdfPageCard(
                                    session = activeSession,
                                    spec = spec,
                                    totalPages = pageCount,
                                    targetWidthPx = targetRenderWidthPx,
                                    bitmapCache = bitmapCache,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showJumpDialog && pageCount > 0) {
        var pageInput by remember(currentPage) { mutableStateOf(currentPage.toString()) }
        var inputError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showJumpDialog = false },
            title = { Text("Jump to page") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pageInput,
                        onValueChange = {
                            pageInput = it
                            inputError = null
                        },
                        label = { Text("Page (1–$pageCount)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (inputError != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = inputError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = pageInput.trim().toIntOrNull()
                        if (target == null || target !in 1..pageCount) {
                            inputError = "Enter a page between 1 and $pageCount"
                        } else {
                            showJumpDialog = false
                            scope.launch {
                                listState.scrollToItem(target - 1)
                            }
                        }
                    },
                ) {
                    Text("Go")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJumpDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun PdfPageCard(
    session: PdfDocumentSession,
    spec: PdfPageSpec,
    totalPages: Int,
    targetWidthPx: Int,
    bitmapCache: LruCache<String, Bitmap>,
) {
    val cacheKey = "${spec.index}@$targetWidthPx"
    var bitmap by remember(spec.index) { mutableStateOf(bitmapCache.get(cacheKey)) }

    LaunchedEffect(session, spec.index, targetWidthPx) {
        val cached = bitmapCache.get(cacheKey)
        if (cached != null) {
            bitmap = cached
            return@LaunchedEffect
        }
        val rendered = runCatching {
            session.renderPage(spec.index, targetWidthPx)
        }.getOrNull()
        if (rendered != null) {
            bitmapCache.put(cacheKey, rendered)
            bitmap = rendered
        }
    }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(spec.aspectRatio),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Page ${spec.index + 1} of $totalPages",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }
        }
    }
}
