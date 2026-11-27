package com.minifile.explorer.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.minifile.explorer.core.gallery.CoreCropMath
import com.minifile.explorer.core.gallery.EditExecutionPlan
import com.minifile.explorer.core.gallery.EditPlanner
import com.minifile.explorer.core.gallery.EditRecipe
import com.minifile.explorer.core.gallery.EditSourceInfo
import com.minifile.explorer.core.gallery.EditUnsupportedReason
import com.minifile.explorer.core.gallery.ImageOutputFormat
import com.minifile.explorer.core.gallery.MediaEditErrorCode
import com.minifile.explorer.core.gallery.MediaEditException
import com.minifile.explorer.core.gallery.MediaExportFormat
import com.minifile.explorer.core.gallery.MediaExtensionRegistry
import com.minifile.explorer.core.gallery.MediaKind
import com.minifile.explorer.core.model.MediaScanNotifier
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class ImageEditOutcome(
    val savedPath: VfsPath,
    val fileName: String,
    val replacedOriginal: Boolean,
    val losslessExifOnly: Boolean,
    val reducedResolution: Boolean,
    val heicConvertedToJpeg: Boolean,
)

/**
 * Android image editing & export preparation engine (spec §6.4 & §7.3):
 * - JPEG rotate/flip without crop -> instant lossless EXIF TAG_ORIENTATION update.
 * - Cropped / non-JPEG -> `BitmapRegionDecoder` + `Matrix` + OOM guard (<= 25% max heap,
 *   auto power-of-2 downscale and OOM retry) + EXIF metadata preservation.
 * - Atomic `.minifile-tmp` write -> decode-bounds verification -> atomic commit -> `rescanFiles`.
 * - Export preparation for `Most compatible` (HEIC -> JPEG) & `Remove location` (GPS strip)
 *   writes staged temp files in `cacheDir/media_edit_tmp/` so `PeerSendItem.size` is exact.
 */
class ImageEditorEngine(
    context: Context,
    private val fs: FileSystem,
    private val mediaScanNotifier: MediaScanNotifier,
    private val losslessMp4Remuxer: LosslessMp4Remuxer,
) {
    private val appContext = context.applicationContext
    val tempDir: File = File(appContext.cacheDir, "media_edit_tmp").apply { mkdirs() }

    /** Cold-start cleanup of > 24h leftover temp files (spec §4.2 / §7.5). */
    fun cleanupStaleTempFiles(maxAgeMs: Long = 24L * 3600L * 1000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        tempDir.listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff) {
                f.deleteRecursively()
            }
        }
    }

    suspend fun probeImage(path: VfsPath): EditSourceInfo? = withContext(Dispatchers.IO) {
        val localFile = materializeLocalInput(path) ?: return@withContext null
        val isTemp = path !is VfsPath.Local
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(localFile.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            val exif = runCatching { ExifInterface(localFile.absolutePath) }.getOrNull()
            val orient = exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            ) ?: ExifInterface.ORIENTATION_NORMAL
            EditSourceInfo(
                kind = MediaKind.IMAGE,
                fileName = path.displayName,
                mime = bounds.outMimeType ?: PathUtils.guessMimeType(path.displayName),
                width = bounds.outWidth,
                height = bounds.outHeight,
                exifOrientation = CoreCropMath.normalizeExifOrientation(orient),
            )
        } finally {
            if (isTemp) localFile.delete()
        }
    }

    suspend fun applyEdit(
        sourcePath: VfsPath,
        recipe: EditRecipe,
        replaceOriginal: Boolean = false,
    ): Result<ImageEditOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            val sourceInfo = probeImage(sourcePath)
                ?: throw MediaEditException(MediaEditErrorCode.READ_FAILED)
            val srcSize = runCatching { fs.stat(sourcePath)?.size ?: 0L }.getOrDefault(0L).coerceAtLeast(0L)
            preflightTempSpace(srcSize + SPACE_HEADROOM_BYTES)
            val plan = EditPlanner.plan(recipe, sourceInfo)
            val safeReplace = replaceOriginal && sourcePath is VfsPath.Local
            when (plan) {
                EditExecutionPlan.NoOp -> throw MediaEditException(MediaEditErrorCode.NO_EDITS)
                is EditExecutionPlan.Unsupported -> throw MediaEditException(
                    when (plan.reason) {
                        EditUnsupportedReason.CLIP_TOO_SHORT_FOR_LOSSLESS -> MediaEditErrorCode.CLIP_TOO_SHORT
                        else -> MediaEditErrorCode.UNSUPPORTED_FORMAT
                    },
                )
                is EditExecutionPlan.LosslessVideoRemux -> throw MediaEditException(MediaEditErrorCode.UNSUPPORTED_FORMAT)
                is EditExecutionPlan.LosslessExifJpeg -> executeLosslessExif(sourcePath, plan, safeReplace)
                is EditExecutionPlan.ImageRegionDecodeEncode -> executeRegionEdit(sourcePath, sourceInfo, plan, safeReplace)
            }
        }
    }

    private suspend fun executeLosslessExif(
        sourcePath: VfsPath,
        plan: EditExecutionPlan.LosslessExifJpeg,
        replaceOriginal: Boolean,
    ): ImageEditOutcome {
        tempDir.mkdirs()
        val tmp = File(tempDir, "edit_${System.nanoTime()}.minifile-tmp.jpg")
        try {
            fs.openInput(sourcePath).use { ins ->
                FileOutputStream(tmp).use { out -> ins.copyTo(out, 64 * 1024) }
            }
            val exif = ExifInterface(tmp.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, plan.targetExifOrientation.toString())
            exif.saveAttributes()

            verifyDecodableImage(tmp)
            val savedPath = commitEditedFile(
                sourcePath = sourcePath,
                tempOutput = tmp,
                desiredExt = PathUtils.extensionOf(sourcePath.displayName).ifEmpty { "jpg" },
                mime = "image/jpeg",
                replaceOriginal = replaceOriginal,
            )
            mediaScanNotifier.rescanFiles(listOf(savedPath))
            return ImageEditOutcome(
                savedPath = savedPath,
                fileName = savedPath.displayName,
                replacedOriginal = replaceOriginal,
                losslessExifOnly = true,
                reducedResolution = false,
                heicConvertedToJpeg = false,
            )
        } finally {
            tmp.delete()
        }
    }

    private suspend fun executeRegionEdit(
        sourcePath: VfsPath,
        sourceInfo: EditSourceInfo,
        plan: EditExecutionPlan.ImageRegionDecodeEncode,
        replaceOriginal: Boolean,
    ): ImageEditOutcome {
        tempDir.mkdirs()
        val localSrc = materializeLocalInput(sourcePath)
            ?: throw IOException("Unable to read source image")
        val srcIsTemp = sourcePath !is VfsPath.Local
        val tmp = File(tempDir, "edit_${System.nanoTime()}.minifile-tmp.${plan.outputFormat.extension}")
        try {
            val rect = Rect(
                plan.rawCropRect.left,
                plan.rawCropRect.top,
                plan.rawCropRect.right,
                plan.rawCropRect.bottom,
            )
            val maxBudgetBytes = (Runtime.getRuntime().maxMemory() * 0.25).toLong().coerceAtLeast(16L * 1024L * 1024L)
            var sampleSize = 1
            var reducedResolution = false
            while (true) {
                val estBytes = (rect.width().toLong() / sampleSize) * (rect.height().toLong() / sampleSize) * 4L
                if (estBytes <= maxBudgetBytes || sampleSize >= 16) break
                sampleSize *= 2
                reducedResolution = true
            }

            var transformed: Bitmap? = null
            var lastError: Throwable? = null
            while (sampleSize <= 16 && transformed == null) {
                try {
                    val regionBmp = decodeRegionWithFallback(localSrc, rect, sampleSize)
                        ?: throw IOException("Could not decode image region")
                    val matrix = buildTransformMatrix(
                        exifOrientation = plan.sourceExifOrientation,
                        userRotateCwDegrees = plan.userRotateCwDegrees,
                        userFlipHorizontal = plan.userFlipHorizontal,
                    )
                    transformed = if (matrix.isIdentity) {
                        regionBmp
                    } else {
                        val outBmp = Bitmap.createBitmap(
                            regionBmp,
                            0,
                            0,
                            regionBmp.width,
                            regionBmp.height,
                            matrix,
                            true,
                        )
                        if (outBmp !== regionBmp) regionBmp.recycle()
                        outBmp
                    }
                } catch (oom: OutOfMemoryError) {
                    lastError = oom
                    sampleSize *= 2
                    reducedResolution = true
                    System.gc()
                }
            }

            val finalBmp = transformed ?: throw IOException("Out of memory decoding image", lastError)
            try {
                FileOutputStream(tmp).use { out ->
                    val format = when (plan.outputFormat) {
                        ImageOutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
                        ImageOutputFormat.PNG -> Bitmap.CompressFormat.PNG
                        ImageOutputFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Bitmap.CompressFormat.WEBP_LOSSY
                        } else {
                            @Suppress("DEPRECATION")
                            Bitmap.CompressFormat.WEBP
                        }
                    }
                    val quality = if (plan.outputFormat == ImageOutputFormat.PNG) 100 else 95
                    if (!finalBmp.compress(format, quality, out)) {
                        throw IOException("Image encode failed")
                    }
                }
                if (plan.outputFormat == ImageOutputFormat.JPEG) {
                    copyExifPreservingDates(localSrc, tmp, finalBmp.width, finalBmp.height)
                }
            } finally {
                finalBmp.recycle()
            }

            verifyDecodableImage(tmp)
            // Preserve original file mtime so copy stays adjacent in timeline
            val origStat = runCatching { fs.stat(sourcePath) }.getOrNull()
            val savedPath = commitEditedFile(
                sourcePath = sourcePath,
                tempOutput = tmp,
                desiredExt = plan.outputFormat.extension,
                mime = plan.outputFormat.mime,
                replaceOriginal = replaceOriginal && !plan.heicConvertedToJpeg,
            )
            if (origStat != null && origStat.lastModified > 0L) {
                runCatching { fs.setLastModified(savedPath, origStat.lastModified) }
            }
            mediaScanNotifier.rescanFiles(listOf(savedPath))
            return ImageEditOutcome(
                savedPath = savedPath,
                fileName = savedPath.displayName,
                replacedOriginal = replaceOriginal && !plan.heicConvertedToJpeg,
                losslessExifOnly = false,
                reducedResolution = reducedResolution,
                heicConvertedToJpeg = plan.heicConvertedToJpeg,
            )
        } finally {
            tmp.delete()
            if (srcIsTemp) localSrc.delete()
        }
    }

    /**
     * Stages media for Send/Share when `format == MOST_COMPATIBLE` or `removeLocation == true`,
     * guaranteeing unique filenames (no cross-directory overwrite collisions), OOM-guarded decode,
     * lossless pass-through for GPS-free PNG/WebP, and exact output `size` on disk (spec §1.3 #3 & §6.4).
     */
    suspend fun prepareForExport(
        paths: List<VfsPath>,
        format: MediaExportFormat,
        removeLocation: Boolean,
    ): Pair<List<VfsPath>, List<File>> = withContext(Dispatchers.IO) {
        if (format == MediaExportFormat.ORIGINAL && !removeLocation) {
            return@withContext paths to emptyList()
        }
        val sessionDir = File(tempDir, "export_${System.nanoTime()}").apply { mkdirs() }
        val resultPaths = ArrayList<VfsPath>(paths.size)
        val createdFiles = ArrayList<File>()
        val usedNames = HashSet<String>()

        fun allocateExportFile(desiredName: String): File {
            val unique = PathUtils.uniqueName(desiredName, usedNames, isDirectory = false, caseInsensitive = true)
            usedNames.add(unique)
            return File(sessionDir, unique)
        }

        for (p in paths) {
            val name = p.displayName
            val kind = MediaExtensionRegistry.classify(name)
            if (kind == null) {
                resultPaths.add(p)
                continue
            }
            val isHeic = MediaExtensionRegistry.isHeic(name)
            val isJpeg = MediaExtensionRegistry.isJpeg(name)
            val isPng = MediaExtensionRegistry.isPng(name)
            val isWebp = MediaExtensionRegistry.isWebp(name)
            val needsConvert = kind == MediaKind.IMAGE && format == MediaExportFormat.MOST_COMPATIBLE && isHeic
            val needsStrip = removeLocation

            if (!needsConvert && !needsStrip) {
                resultPaths.add(p)
                continue
            }

            val base = PathUtils.splitBaseAndExtension(name).first
            if (kind == MediaKind.IMAGE) {
                if (isJpeg && !needsConvert) {
                    // Lossless GPS strip via ExifInterface
                    val outFile = allocateExportFile(name)
                    fs.openInput(p).use { ins ->
                        FileOutputStream(outFile).use { out -> ins.copyTo(out, 64 * 1024) }
                    }
                    stripGpsExif(outFile)
                    createdFiles.add(outFile)
                    resultPaths.add(VfsPath.Local(outFile.absolutePath))
                } else if (!needsConvert && (isPng || isWebp)) {
                    // Preserve lossless PNG/WebP when format == ORIGINAL and file has no GPS EXIF (H7)
                    val localSrc = materializeLocalInput(p)
                    val srcIsTemp = p !is VfsPath.Local
                    try {
                        val hasGps = localSrc != null && runCatching {
                            ExifInterface(localSrc.absolutePath).latLong != null
                        }.getOrDefault(false)
                        if (!hasGps) {
                            resultPaths.add(p)
                        } else if (localSrc != null) {
                            val outExt = if (isPng) "png" else "webp"
                            val outFile = allocateExportFile("$base.$outExt")
                            val decoded = decodeBudgetedUpright(localSrc)
                            if (decoded != null) {
                                try {
                                    val fmt = when {
                                        isPng -> Bitmap.CompressFormat.PNG
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP_LOSSY
                                        else -> {
                                            @Suppress("DEPRECATION")
                                            Bitmap.CompressFormat.WEBP
                                        }
                                    }
                                    FileOutputStream(outFile).use { out ->
                                        decoded.compress(fmt, if (isPng) 100 else 92, out)
                                    }
                                } finally {
                                    decoded.recycle()
                                }
                                createdFiles.add(outFile)
                                resultPaths.add(VfsPath.Local(outFile.absolutePath))
                            } else {
                                resultPaths.add(p)
                            }
                        } else {
                            resultPaths.add(p)
                        }
                    } finally {
                        if (srcIsTemp) localSrc?.delete()
                    }
                } else {
                    // Convert HEIC (or other format) to JPEG with OOM budget guard + downscale retry (H7)
                    val outFile = allocateExportFile("$base.jpg")
                    val localSrc = materializeLocalInput(p)
                    val srcIsTemp = p !is VfsPath.Local
                    try {
                        if (localSrc != null) {
                            val upright = decodeBudgetedUpright(localSrc)
                            if (upright != null) {
                                try {
                                    FileOutputStream(outFile).use { out ->
                                        upright.compress(Bitmap.CompressFormat.JPEG, 92, out)
                                    }
                                    copyExifPreservingDates(localSrc, outFile, upright.width, upright.height, includeGps = !removeLocation)
                                } finally {
                                    upright.recycle()
                                }
                                createdFiles.add(outFile)
                                resultPaths.add(VfsPath.Local(outFile.absolutePath))
                            } else {
                                resultPaths.add(p)
                            }
                        } else {
                            resultPaths.add(p)
                        }
                    } finally {
                        if (srcIsTemp) localSrc?.delete()
                    }
                }
            } else {
                // Video: lossless remux without location metadata if removeLocation
                if (needsStrip && MediaExtensionRegistry.isLosslessEditableVideo(name)) {
                    val ext = PathUtils.extensionOf(name).ifEmpty { "mp4" }
                    val outFile = allocateExportFile("$base.$ext")
                    val ok = losslessMp4Remuxer.stripLocationLossless(p, outFile).isSuccess
                    if (ok && outFile.isFile && outFile.length() > 0L) {
                        createdFiles.add(outFile)
                        resultPaths.add(VfsPath.Local(outFile.absolutePath))
                    } else {
                        outFile.delete()
                        resultPaths.add(p)
                    }
                } else {
                    resultPaths.add(p)
                }
            }
        }
        resultPaths to createdFiles
    }

    private fun preflightTempSpace(requiredBytes: Long) {
        tempDir.mkdirs()
        val usable = tempDir.usableSpace
        if (usable > 0L && usable < requiredBytes) {
            throw MediaEditException(MediaEditErrorCode.INSUFFICIENT_SPACE)
        }
    }

    /**
     * Decodes a full image with <= 25% max-heap budget and automatic power-of-2 downscale retry on OOM,
     * applying EXIF orientation upright.
     */
    private fun decodeBudgetedUpright(localSrc: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(localSrc.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val maxBudgetBytes = (Runtime.getRuntime().maxMemory() * 0.25).toLong().coerceAtLeast(16L * 1024L * 1024L)
        var sample = 1
        while (sample < 16) {
            // Account for potential orientation-rotation copy (2x safety factor)
            val estBytes = (bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) * 8L
            if (estBytes <= maxBudgetBytes) break
            sample *= 2
        }

        val orient = runCatching {
            ExifInterface(localSrc.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        while (sample <= 16) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val raw = BitmapFactory.decodeFile(localSrc.absolutePath, opts) ?: return null
                val oriented = com.minifile.explorer.data.gallery.GalleryThumbnailCache.applyExifOrientation(raw, orient)
                // R2-3: applyExifOrientation catches OOM and falls back to raw; treat that as OOM and downscale retry
                if (orient != ExifInterface.ORIENTATION_NORMAL &&
                    orient != ExifInterface.ORIENTATION_UNDEFINED &&
                    oriented === raw
                ) {
                    raw.recycle()
                    sample *= 2
                    System.gc()
                    continue
                }
                return oriented
            } catch (_: OutOfMemoryError) {
                sample *= 2
                System.gc()
            }
        }
        return null
    }

    private fun decodeRegionWithFallback(file: File, rect: Rect, sampleSize: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val regionBmp = runCatching {
            val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(file.absolutePath)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(file.absolutePath, false)
            }
            try {
                decoder.decodeRegion(rect, opts)
            } finally {
                decoder.recycle()
            }
        }.getOrNull()
        if (regionBmp != null) return regionBmp

        // Fallback for formats BitmapRegionDecoder doesn't support (e.g. GIF / AVIF)
        val full = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        val sx = (rect.left / sampleSize).coerceIn(0, (full.width - 1).coerceAtLeast(0))
        val sy = (rect.top / sampleSize).coerceIn(0, (full.height - 1).coerceAtLeast(0))
        val sw = (rect.width() / sampleSize).coerceIn(1, full.width - sx)
        val sh = (rect.height() / sampleSize).coerceIn(1, full.height - sy)
        val cropped = Bitmap.createBitmap(full, sx, sy, sw, sh)
        if (cropped !== full) full.recycle()
        return cropped
    }

    private fun buildTransformMatrix(
        exifOrientation: Int,
        userRotateCwDegrees: Int,
        userFlipHorizontal: Boolean,
    ): Matrix {
        val m = Matrix()
        when (exifOrientation) {
            CoreCropMath.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            CoreCropMath.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            CoreCropMath.ORIENTATION_FLIP_VERTICAL -> {
                m.postRotate(180f)
                m.postScale(-1f, 1f)
            }

            CoreCropMath.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f)
                m.postScale(-1f, 1f)
            }

            CoreCropMath.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            CoreCropMath.ORIENTATION_TRANSVERSE -> {
                m.postRotate(270f)
                m.postScale(-1f, 1f)
            }

            CoreCropMath.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        }
        if (userRotateCwDegrees != 0) {
            m.postRotate(userRotateCwDegrees.toFloat())
        }
        if (userFlipHorizontal) {
            m.postScale(-1f, 1f)
        }
        return m
    }

    private fun copyExifPreservingDates(
        src: File,
        dst: File,
        newWidth: Int,
        newHeight: Int,
        includeGps: Boolean = true,
    ) {
        runCatching {
            val srcExif = ExifInterface(src.absolutePath)
            val dstExif = ExifInterface(dst.absolutePath)
            val tagsToCopy = buildList {
                add(ExifInterface.TAG_DATETIME)
                add(ExifInterface.TAG_DATETIME_ORIGINAL)
                add(ExifInterface.TAG_DATETIME_DIGITIZED)
                add(ExifInterface.TAG_OFFSET_TIME)
                add(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
                add(ExifInterface.TAG_MAKE)
                add(ExifInterface.TAG_MODEL)
                add(ExifInterface.TAG_F_NUMBER)
                add(ExifInterface.TAG_EXPOSURE_TIME)
                add(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                add(ExifInterface.TAG_FOCAL_LENGTH)
                add(ExifInterface.TAG_WHITE_BALANCE)
                if (includeGps) {
                    add(ExifInterface.TAG_GPS_LATITUDE)
                    add(ExifInterface.TAG_GPS_LATITUDE_REF)
                    add(ExifInterface.TAG_GPS_LONGITUDE)
                    add(ExifInterface.TAG_GPS_LONGITUDE_REF)
                    add(ExifInterface.TAG_GPS_ALTITUDE)
                    add(ExifInterface.TAG_GPS_ALTITUDE_REF)
                }
            }
            for (tag in tagsToCopy) {
                srcExif.getAttribute(tag)?.let { dstExif.setAttribute(tag, it) }
            }
            dstExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dstExif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, newWidth.toString())
            dstExif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, newHeight.toString())
            dstExif.saveAttributes()
        }
    }

    private fun stripGpsExif(file: File) {
        runCatching {
            val exif = ExifInterface(file.absolutePath)
            val gpsTags = listOf(
                ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_ALTITUDE,
                ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_GPS_TIMESTAMP,
                ExifInterface.TAG_GPS_DATESTAMP,
                ExifInterface.TAG_GPS_PROCESSING_METHOD,
                ExifInterface.TAG_GPS_AREA_INFORMATION,
            )
            for (t in gpsTags) exif.setAttribute(t, null)
            exif.saveAttributes()
        }
    }

    private fun verifyDecodableImage(file: File) {
        if (!file.isFile || file.length() <= 0L) {
            throw IOException("Output image file is empty")
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            throw IOException("Output image failed verification")
        }
    }

    private suspend fun materializeLocalInput(path: VfsPath): File? {
        if (path is VfsPath.Local) {
            val f = File(path.normalized)
            return if (f.isFile && f.canRead()) f else null
        }
        tempDir.mkdirs()
        val ext = PathUtils.extensionOf(path.displayName).ifEmpty { "bin" }
        val tmp = File(tempDir, "in_${System.nanoTime()}.$ext")
        return runCatching {
            fs.openInput(path).use { ins ->
                FileOutputStream(tmp).use { out -> ins.copyTo(out, 64 * 1024) }
            }
            tmp
        }.getOrElse {
            tmp.delete()
            null
        }
    }

    private suspend fun commitEditedFile(
        sourcePath: VfsPath,
        tempOutput: File,
        desiredExt: String,
        mime: String,
        replaceOriginal: Boolean,
    ): VfsPath {
        if (replaceOriginal && sourcePath is VfsPath.Local) {
            val dstFile = File(sourcePath.normalized)
            val parent = dstFile.parentFile ?: throw IOException("No parent directory")
            val stageInSameDir = File(parent, ".${dstFile.name}.minifile-tmp")
            try {
                tempOutput.copyTo(stageInSameDir, overwrite = true)
                if (!stageInSameDir.renameTo(dstFile)) {
                    tempOutput.copyTo(dstFile, overwrite = true)
                    stageInSameDir.delete()
                }
                return sourcePath
            } catch (e: Exception) {
                stageInSameDir.delete()
                throw e
            }
        }

        val parentDir = fs.parent(sourcePath) ?: throw IOException("Cannot determine destination folder")
        val existingNames = runCatching {
            fs.list(parentDir, includeHidden = true).map { it.name }.toSet()
        }.getOrDefault(emptySet())
        val (base, _) = PathUtils.splitBaseAndExtension(sourcePath.displayName)
        val desiredName = PathUtils.uniqueName(
            "${base}_edited.$desiredExt",
            existingNames,
            isDirectory = false,
            caseInsensitive = true,
        )

        val targetPath = fs.createFile(parentDir, desiredName, mime)
        var committed = false
        try {
            tempOutput.inputStream().use { ins ->
                fs.openOutput(targetPath, append = false).use { out ->
                    ins.copyTo(out, 64 * 1024)
                    out.flush()
                }
            }
            committed = true
            return targetPath
        } finally {
            if (!committed) {
                runCatching { fs.delete(targetPath) }
            }
        }
    }

    companion object {
        private const val SPACE_HEADROOM_BYTES = 64L * 1024L * 1024L
    }
}
