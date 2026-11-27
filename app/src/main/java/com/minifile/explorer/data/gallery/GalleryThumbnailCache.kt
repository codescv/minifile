package com.minifile.explorer.data.gallery

import android.content.ComponentCallbacks2
import android.content.ContentUris
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import com.minifile.explorer.core.gallery.MediaItem
import com.minifile.explorer.core.gallery.MediaKind
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.data.SafFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * Memory- and disk-governed thumbnail loader for the Gallery Lens (spec §8.1):
 * - L1 `LruCache` metered by `bitmap.allocationByteCount`, capped at `min(maxHeap / 8, 48 MiB)`.
 * - Grid thumbnails decoded in `Bitmap.Config.RGB_565` (50% memory savings).
 * - API 29+ prefers `ContentResolver.loadThumbnail` for MediaStore-indexed items (no duplicate disk cache).
 * - Own L2 disk cache (`cacheDir/gallery_thumbs/`, WebP q=80, <= 64 MiB LRU) ONLY for unindexed / SAF items.
 * - Concurrency capped at `Dispatchers.IO.limitedParallelism(4)` + off-screen `CancellationSignal`.
 * - Registered for `ComponentCallbacks2.onTrimMemory` to flush L1 under memory pressure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryThumbnailCache(
    context: Context,
    private val safFs: SafFileSystem,
) : ComponentCallbacks2 {

    private val appContext = context.applicationContext
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(4)
    private val diskDir = File(appContext.cacheDir, "gallery_thumbs").apply { mkdirs() }
    private val diskLock = Any()
    private var diskTotalBytes: Long = -1L
    private var diskWritesSinceScan: Int = 0

    private val maxL1Bytes: Int = run {
        val heapEighth = (Runtime.getRuntime().maxMemory() / 8L).coerceAtMost(MAX_L1_BYTES.toLong())
        heapEighth.coerceAtLeast(8L * 1024L * 1024L).toInt()
    }

    private val memoryCache = object : LruCache<String, Bitmap>(maxL1Bytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }

    init {
        appContext.registerComponentCallbacks(this)
    }

    fun peekMemory(item: MediaItem, targetPx: Int): Bitmap? =
        memoryCache.get(cacheKey(item, targetPx))

    suspend fun loadThumbnail(item: MediaItem, targetPx: Int = 256): Bitmap? {
        val clampedPx = targetPx.coerceIn(64, 512)
        val key = cacheKey(item, clampedPx)
        memoryCache.get(key)?.let { return it }

        return withContext(decodeDispatcher) {
            memoryCache.get(key)?.let { return@withContext it }

            val useOwnDiskCache = item.storeId <= 0L || item.path is VfsPath.Saf
            if (useOwnDiskCache) {
                readFromDisk(key)?.let { diskBmp ->
                    memoryCache.put(key, diskBmp)
                    return@withContext diskBmp
                }
            }

            val bmp = decodeCancellable(item, clampedPx) ?: return@withContext null
            memoryCache.put(key, bmp)
            if (useOwnDiskCache) {
                writeToDisk(key, bmp)
            }
            bmp
        }
    }

    /**
     * Loads a higher-resolution display bitmap for the immersive Viewer (capped to <= [maxSidePx]
     * to stay well below OOM budgets). Applies EXIF orientation.
     */
    suspend fun loadViewerBitmap(item: MediaItem, maxSidePx: Int = 2048): Bitmap? =
        withContext(decodeDispatcher) {
            if (item.kind == MediaKind.VIDEO) {
                return@withContext loadThumbnail(item, targetPx = 512)
            }
            runCatching {
                when (val p = item.path) {
                    is VfsPath.Local -> {
                        val file = File(p.normalized)
                        if (!file.isFile) return@runCatching null
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.absolutePath, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                        val sample = computeViewerInSampleSize(bounds.outWidth, bounds.outHeight, maxSidePx)
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@runCatching null
                        val exif = runCatching { ExifInterface(file.absolutePath) }.getOrNull()
                        val orientation = exif?.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL,
                        ) ?: ExifInterface.ORIENTATION_NORMAL
                        applyExifOrientation(raw, orientation)
                    }

                    is VfsPath.Saf -> {
                        val uri = safFs.buildDocumentUri(p)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        appContext.contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it, null, bounds)
                        }
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                        val sample = computeViewerInSampleSize(bounds.outWidth, bounds.outHeight, maxSidePx)
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        val raw = appContext.contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it, null, opts)
                        } ?: return@runCatching null
                        val orientation = runCatching {
                            appContext.contentResolver.openInputStream(uri)?.use { ins ->
                                ExifInterface(ins).getAttributeInt(
                                    ExifInterface.TAG_ORIENTATION,
                                    ExifInterface.ORIENTATION_NORMAL,
                                )
                            }
                        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
                        applyExifOrientation(raw, orientation)
                    }

                    else -> null
                }
            }.getOrNull()
        }

    private suspend fun decodeCancellable(item: MediaItem, targetPx: Int): Bitmap? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { runCatching { signal.cancel() } }
            val result = runCatching {
                decodeThumbnailInternal(item, targetPx, signal)
            }.getOrNull()
            if (cont.isActive) {
                cont.resume(result)
            }
        }

    private fun decodeThumbnailInternal(
        item: MediaItem,
        targetPx: Int,
        signal: CancellationSignal,
    ): Bitmap? {
        if (signal.isCanceled) return null

        // 1. Prefer system MediaStore thumbnail cache on API 29+ when indexed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && item.storeId > 0L && item.path is VfsPath.Local) {
            val baseUri = if (item.kind == MediaKind.VIDEO) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val contentUri = ContentUris.withAppendedId(baseUri, item.storeId)
            val sysBmp = runCatching {
                appContext.contentResolver.loadThumbnail(contentUri, Size(targetPx, targetPx), signal)
            }.getOrNull()
            if (sysBmp != null) return toRgb565IfSafe(sysBmp)
        }

        if (signal.isCanceled) return null

        // 2. Fallback direct decode for unindexed Local / SAF
        return when (val p = item.path) {
            is VfsPath.Local -> {
                val file = File(p.normalized)
                if (!file.isFile || !file.canRead()) return null
                if (item.kind == MediaKind.VIDEO) {
                    decodeVideoThumbLocal(file, targetPx, signal)
                } else {
                    decodeImageThumbLocal(file, targetPx)
                }
            }

            is VfsPath.Saf -> {
                val uri = safFs.buildDocumentUri(p)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val safThumb = runCatching {
                        appContext.contentResolver.loadThumbnail(uri, Size(targetPx, targetPx), signal)
                    }.getOrNull()
                    if (safThumb != null) return toRgb565IfSafe(safThumb)
                }
                if (item.kind == MediaKind.VIDEO) {
                    val mmr = MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(appContext, uri)
                        mmr.getFrameAtTime(-1)?.let { scaleCenterCrop(it, targetPx) }
                    } catch (_: Throwable) {
                        null
                    } finally {
                        runCatching { mmr.release() }
                    }
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    appContext.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    appContext.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    }
                }
            }

            else -> null
        }
    }

    private fun decodeVideoThumbLocal(file: File, targetPx: Int, signal: CancellationSignal): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bmp = runCatching {
                ThumbnailUtils.createVideoThumbnail(file, Size(targetPx, targetPx), signal)
            }.getOrNull()
            if (bmp != null) return toRgb565IfSafe(bmp)
        }
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            mmr.getFrameAtTime(-1)?.let { scaleCenterCrop(it, targetPx) }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun decodeImageThumbLocal(file: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        val exif = runCatching { ExifInterface(file.absolutePath) }.getOrNull()
        val orientation = exif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        ) ?: ExifInterface.ORIENTATION_NORMAL
        return applyExifOrientation(raw, orientation)
    }

    private fun toRgb565IfSafe(bmp: Bitmap): Bitmap {
        if (bmp.config == Bitmap.Config.RGB_565 || bmp.hasAlpha()) return bmp
        return runCatching {
            val copy = bmp.copy(Bitmap.Config.RGB_565, false)
            if (copy != null) {
                if (copy !== bmp) bmp.recycle()
                copy
            } else {
                bmp
            }
        }.getOrDefault(bmp)
    }

    private fun scaleCenterCrop(src: Bitmap, targetPx: Int): Bitmap {
        val scaled = ThumbnailUtils.extractThumbnail(src, targetPx, targetPx, ThumbnailUtils.OPTIONS_RECYCLE_INPUT)
        return toRgb565IfSafe(scaled)
    }

    private fun readFromDisk(key: String): Bitmap? {
        val file = File(diskDir, "${sha256Hex(key)}.webp")
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
    }

    private fun writeToDisk(key: String, bmp: Bitmap) {
        synchronized(diskLock) {
            runCatching {
                diskDir.mkdirs()
                val file = File(diskDir, "${sha256Hex(key)}.webp")
                val tmp = File(diskDir, "${file.name}.tmp")
                FileOutputStream(tmp).use { out ->
                    val fmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    }
                    bmp.compress(fmt, 80, out)
                }
                    val prevLen = if (file.isFile) file.length() else 0L
                    if (tmp.renameTo(file)) {
                        val newLen = file.length()
                        if (diskTotalBytes < 0L) {
                            diskTotalBytes = diskDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: newLen
                        } else {
                            diskTotalBytes = (diskTotalBytes - prevLen + newLen).coerceAtLeast(0L)
                        }
                        diskWritesSinceScan++
                        if (diskTotalBytes > MAX_DISK_BYTES || diskWritesSinceScan >= 64) {
                            diskWritesSinceScan = 0
                            evictDiskCacheIfNeeded()
                        }
                    } else {
                        tmp.delete()
                    }
            }
        }
    }

    private fun evictDiskCacheIfNeeded() {
        val files = diskDir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        diskTotalBytes = total
        if (total <= MAX_DISK_BYTES) return
        val ordered = files.sortedBy { it.lastModified() }
        val target = (MAX_DISK_BYTES * 0.8).toLong()
        for (f in ordered) {
            if (total <= target) break
            val len = f.length()
            if (f.delete()) total -= len
        }
        diskTotalBytes = total.coerceAtLeast(0L)
    }

    fun clearMemoryCache() {
        memoryCache.evictAll()
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            memoryCache.evictAll()
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            memoryCache.trimToSize(maxL1Bytes / 2)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        memoryCache.evictAll()
    }

    private fun cacheKey(item: MediaItem, targetPx: Int): String =
        "${item.path.serialize()}@${item.modifiedMs}@${item.sizeBytes}@$targetPx"

    companion object {
        private const val MAX_L1_BYTES = 48 * 1024 * 1024
        private const val MAX_DISK_BYTES = 64L * 1024L * 1024L

        fun computeInSampleSize(width: Int, height: Int, targetPx: Int): Int {
            var sample = 1
            val maxSide = maxOf(width, height)
            while (maxSide / (sample * 2) >= targetPx) {
                sample *= 2
            }
            return sample.coerceAtLeast(1)
        }

        /**
         * Strict upper-bound sample size for the viewer so decoded max dimension is ALWAYS <= [maxSidePx]
         * (e.g. 12 MP 4000x3000 with maxSidePx=2048 -> inSampleSize=2 -> 2000x1500 ~ 12 MB instead of 48 MB).
         */
        fun computeViewerInSampleSize(width: Int, height: Int, maxSidePx: Int): Int {
            var sample = 1
            val maxSide = maxOf(width, height)
            while (maxSide / sample > maxSidePx) {
                sample *= 2
            }
            return sample.coerceAtLeast(1)
        }

        fun applyExifOrientation(src: Bitmap, orientation: Int): Bitmap {
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                    matrix.setRotate(180f)
                    matrix.postScale(-1f, 1f)
                }

                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.setRotate(90f)
                    matrix.postScale(-1f, 1f)
                }

                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.setRotate(-90f)
                    matrix.postScale(-1f, 1f)
                }

                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
                else -> return src
            }
            return runCatching {
                val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
                if (rotated !== src) src.recycle()
                rotated
            }.getOrDefault(src)
        }

        private fun sha256Hex(raw: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return buildString(digest.size * 2) {
                for (b in digest) {
                    val v = b.toInt() and 0xFF
                    append("0123456789abcdef"[v ushr 4])
                    append("0123456789abcdef"[v and 0x0F])
                }
            }
        }
    }
}
