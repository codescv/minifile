package com.minifile.explorer.data.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import com.minifile.explorer.core.gallery.EditExecutionPlan
import com.minifile.explorer.core.gallery.EditPlanner
import com.minifile.explorer.core.gallery.EditRecipe
import com.minifile.explorer.core.gallery.EditSourceInfo
import com.minifile.explorer.core.gallery.EditUnsupportedReason
import com.minifile.explorer.core.gallery.MediaEditErrorCode
import com.minifile.explorer.core.gallery.MediaEditException
import com.minifile.explorer.core.gallery.MediaKind
import com.minifile.explorer.core.gallery.RemuxPlan
import com.minifile.explorer.core.gallery.RemuxSegment
import com.minifile.explorer.core.model.MediaScanNotifier
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.data.SafFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

data class VideoProbeInfo(
    val sourceInfo: EditSourceInfo,
    val sourceRotationDegrees: Int,
    val audioTrackCount: Int,
)

data class VideoEditOutcome(
    val savedPath: VfsPath,
    val fileName: String,
    val replacedOriginal: Boolean,
    val outputDurationMs: Long,
    val extraAudioTracksDropped: Boolean,
)

/**
 * Zero-reencode lossless video editor using platform `MediaExtractor + MediaMuxer` (spec §7.4).
 *
 * Guarantees:
 * - B-frame safe: video track preserves original `sampleTime + ptsOffsetUs` without monotonic
 *   decoding-order clamping (MediaMuxer writes `ctts` natively).
 * - Drops leading samples `< seg.sourceStartUs` on ALL segments (including open-GOP B-frames).
 * - Uses half-open `[sourceStartUs, sourceEndUs)` on non-final segments to prevent boundary collisions.
 * - Interleaves video and audio samples by output PTS across two extractors.
 * - Reads `VfsPath.Saf` directly via `ContentResolver` Uri without copying multi-GB videos to cacheDir.
 * - Preflights free disk space (`>= sourceSize + 64 MiB`) and writes atomically via `.minifile-tmp`.
 */
class LosslessMp4Remuxer(
    context: Context,
    private val fs: FileSystem,
    private val mediaScanNotifier: MediaScanNotifier,
    private val safFs: SafFileSystem? = null,
) {
    private val appContext = context.applicationContext
    private val tempDir: File = File(appContext.cacheDir, "media_edit_tmp").apply { mkdirs() }

    private sealed interface VideoInputSource {
        data class LocalFile(val file: File, val isTemp: Boolean) : VideoInputSource
        data class SafUri(val uri: Uri) : VideoInputSource

        fun applyTo(mmr: MediaMetadataRetriever, context: Context) {
            when (this) {
                is LocalFile -> mmr.setDataSource(file.absolutePath)
                is SafUri -> mmr.setDataSource(context, uri)
            }
        }

        fun applyTo(extractor: MediaExtractor, context: Context) {
            when (this) {
                is LocalFile -> extractor.setDataSource(file.absolutePath)
                is SafUri -> extractor.setDataSource(context, uri, null)
            }
        }

        fun cleanup() {
            if (this is LocalFile && isTemp) file.delete()
        }
    }

    suspend fun probeVideo(path: VfsPath): VideoProbeInfo? = withContext(Dispatchers.IO) {
        val input = openVideoSource(path) ?: return@withContext null
        val mmr = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        try {
            input.applyTo(mmr, appContext)
            val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                ?: PathUtils.guessMimeType(path.displayName)

            input.applyTo(extractor, appContext)
            var videoTrack = -1
            var audioTracks = 0
            for (i in 0 until extractor.trackCount) {
                val trackMime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (trackMime.startsWith("video/") && videoTrack < 0) {
                    videoTrack = i
                } else if (trackMime.startsWith("audio/")) {
                    audioTracks++
                }
            }

            val keyframes = ArrayList<Long>()
            if (videoTrack >= 0) {
                extractor.selectTrack(videoTrack)
                var guard = 0
                while (guard < 200_000) {
                    val sampleTime = extractor.sampleTime
                    if (sampleTime < 0L) break
                    if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        keyframes.add(sampleTime)
                    }
                    if (!extractor.advance()) break
                    guard++
                }
            }

            VideoProbeInfo(
                sourceInfo = EditSourceInfo(
                    kind = MediaKind.VIDEO,
                    fileName = path.displayName,
                    mime = mime,
                    width = width,
                    height = height,
                    durationMs = durationMs,
                    keyframeTimestampsUs = keyframes,
                    hasAudio = audioTracks > 0,
                ),
                sourceRotationDegrees = ((rotation % 360) + 360) % 360,
                audioTrackCount = audioTracks,
            )
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { extractor.release() }
            runCatching { mmr.release() }
            input.cleanup()
        }
    }

    suspend fun extractFilmstrip(path: VfsPath, count: Int = 10, targetWidthPx: Int = 120): List<Bitmap> =
        withContext(Dispatchers.IO) {
            val input = openVideoSource(path) ?: return@withContext emptyList()
            val mmr = MediaMetadataRetriever()
            try {
                input.applyTo(mmr, appContext)
                val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: return@withContext emptyList()
                if (durationMs <= 0L) return@withContext emptyList()
                val n = count.coerceIn(4, 16)
                val frames = ArrayList<Bitmap>(n)
                for (i in 0 until n) {
                    val tUs = ((durationMs.toDouble() * i / n.toDouble()) * 1000.0).toLong()
                    val bmp = runCatching {
                        mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }.getOrNull()
                    if (bmp != null) {
                        val ratio = targetWidthPx.toFloat() / bmp.width.coerceAtLeast(1)
                        val h = (bmp.height * ratio).toInt().coerceAtLeast(32)
                        val scaled = Bitmap.createScaledBitmap(bmp, targetWidthPx, h, true)
                        if (scaled !== bmp) bmp.recycle()
                        frames.add(scaled)
                    }
                }
                frames
            } catch (_: Throwable) {
                emptyList()
            } finally {
                runCatching { mmr.release() }
                input.cleanup()
            }
        }

    suspend fun applyEdit(
        sourcePath: VfsPath,
        recipe: EditRecipe,
        probe: VideoProbeInfo? = null,
        replaceOriginal: Boolean = false,
    ): Result<VideoEditOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            val info = probe ?: probeVideo(sourcePath) ?: throw MediaEditException(MediaEditErrorCode.READ_FAILED)
            val plan = EditPlanner.plan(recipe, info.sourceInfo)
            val remuxPlan = when (plan) {
                is EditExecutionPlan.LosslessVideoRemux -> plan.remuxPlan
                EditExecutionPlan.NoOp -> throw MediaEditException(MediaEditErrorCode.NO_EDITS)
                is EditExecutionPlan.Unsupported -> throw MediaEditException(
                    when (plan.reason) {
                        EditUnsupportedReason.CLIP_TOO_SHORT_FOR_LOSSLESS -> MediaEditErrorCode.CLIP_TOO_SHORT
                        else -> MediaEditErrorCode.UNSUPPORTED_FORMAT
                    },
                )
                else -> throw MediaEditException(MediaEditErrorCode.UNSUPPORTED_FORMAT)
            }

            // Free-space preflight (>= estimated source size + 64 MiB) per spec §7.5
            val srcSize = runCatching { fs.stat(sourcePath)?.size ?: 0L }.getOrDefault(0L).coerceAtLeast(0L)
            preflightTempSpace(srcSize + SPACE_HEADROOM_BYTES)

            tempDir.mkdirs()
            val input = openVideoSource(sourcePath) ?: throw MediaEditException(MediaEditErrorCode.READ_FAILED)
            val ext = PathUtils.extensionOf(sourcePath.displayName).lowercase().let {
                if (it in setOf("mp4", "m4v", "mov", "3gp", "3gpp", "webm")) it else "mp4"
            }
            val tmp = File(tempDir, "remux_${System.nanoTime()}.minifile-tmp.$ext")
            try {
                executeRemuxInterleaved(
                    input = input,
                    output = tmp,
                    plan = remuxPlan,
                    sourceRotationDegrees = info.sourceRotationDegrees,
                    ext = ext,
                )
                val verifiedDurationMs = verifyVideoOutput(tmp)
                val safeReplace = replaceOriginal && sourcePath is VfsPath.Local
                val savedPath = commitEditedVideo(
                    sourcePath = sourcePath,
                    tempOutput = tmp,
                    ext = ext,
                    replaceOriginal = safeReplace,
                )
                mediaScanNotifier.rescanFiles(listOf(savedPath))
                VideoEditOutcome(
                    savedPath = savedPath,
                    fileName = savedPath.displayName,
                    replacedOriginal = safeReplace,
                    outputDurationMs = verifiedDurationMs,
                    extraAudioTracksDropped = !remuxPlan.mute && info.audioTrackCount > 1,
                )
            } finally {
                tmp.delete()
                input.cleanup()
            }
        }
    }

    /**
     * Losslessly remuxes a video without calling `setLocation` so GPS metadata is stripped
     * with zero quality loss, B-frame PTS fidelity, and deterministic output byte size.
     */
    suspend fun stripLocationLossless(sourcePath: VfsPath, destinationFile: File): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val info = probeVideo(sourcePath) ?: throw IOException("Cannot probe video")
                val srcSize = runCatching { fs.stat(sourcePath)?.size ?: 0L }.getOrDefault(0L).coerceAtLeast(0L)
                preflightTempSpace(srcSize + SPACE_HEADROOM_BYTES)
                val input = openVideoSource(sourcePath) ?: throw IOException("Cannot read video")
                try {
                    val durUs = (info.sourceInfo.durationMs * 1000L).coerceAtLeast(1L)
                    val fullPlan = RemuxPlan(
                        segments = listOf(RemuxSegment(0L, durUs, 0L)),
                        outputDurationUs = durUs,
                        mute = false,
                        rotationDeltaDegrees = 0,
                    )
                    val ext = PathUtils.extensionOf(sourcePath.displayName).lowercase()
                    executeRemuxInterleaved(
                        input = input,
                        output = destinationFile,
                        plan = fullPlan,
                        sourceRotationDegrees = info.sourceRotationDegrees,
                        ext = ext,
                    )
                    verifyVideoOutput(destinationFile)
                    destinationFile
                } finally {
                    input.cleanup()
                }
            }
        }

    private fun preflightTempSpace(requiredBytes: Long) {
        tempDir.mkdirs()
        val usable = tempDir.usableSpace
        if (usable > 0L && usable < requiredBytes) {
            throw MediaEditException(MediaEditErrorCode.INSUFFICIENT_SPACE)
        }
    }

    private fun muxerFormatForExt(ext: String): Int = when (ext) {
        "webm" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
        "3gp", "3gpp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP
        } else {
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        }

        else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
    }

    private class TrackCursor(
        val extractor: MediaExtractor,
        val srcTrack: Int,
        val dstTrack: Int,
        val isVideo: Boolean,
        val segments: List<RemuxSegment>,
    ) {
        var segIndex: Int = 0
        var hasSample: Boolean = false
        var samplePtsUs: Long = 0L
        var sampleFlags: Int = 0
        private var lastAudioPtsUs: Long = Long.MIN_VALUE

        fun start() {
            extractor.selectTrack(srcTrack)
            seekToCurrentSegment()
            advanceToValidSample()
        }

        private fun seekToCurrentSegment() {
            if (segIndex >= segments.size) {
                hasSample = false
                return
            }
            val seg = segments[segIndex]
            val mode = if (segIndex == 0) {
                MediaExtractor.SEEK_TO_PREVIOUS_SYNC
            } else {
                MediaExtractor.SEEK_TO_NEXT_SYNC
            }
            extractor.seekTo(seg.sourceStartUs, mode)
        }

        fun advanceToNext() {
            if (!extractor.advance()) {
                segIndex++
                if (segIndex < segments.size) {
                    seekToCurrentSegment()
                    advanceToValidSample()
                } else {
                    hasSample = false
                }
                return
            }
            advanceToValidSample()
        }

        private fun advanceToValidSample() {
            while (segIndex < segments.size) {
                val seg = segments[segIndex]
                val isLastSeg = segIndex == segments.lastIndex
                val st = extractor.sampleTime
                if (st < 0L) {
                    segIndex++
                    seekToCurrentSegment()
                    continue
                }
                // Drop samples strictly before segment start on ALL segments (prevents open-GOP leading B-frame leakage)
                if (st < seg.sourceStartUs) {
                    if (!extractor.advance()) {
                        segIndex++
                        seekToCurrentSegment()
                    }
                    continue
                }
                // Non-final segments use half-open [sourceStartUs, sourceEndUs) to prevent boundary collision
                val pastEnd = if (isLastSeg) st > seg.sourceEndUs else st >= seg.sourceEndUs
                if (pastEnd) {
                    segIndex++
                    seekToCurrentSegment()
                    continue
                }

                var outPts = (st + seg.ptsOffsetUs).coerceAtLeast(0L)
                // CRITICAL (H1): Video preserves exact B-frame presentation timestamps (no decoding-order clamping).
                // Monotonic clamping is applied ONLY to audio track.
                if (!isVideo) {
                    if (lastAudioPtsUs != Long.MIN_VALUE && outPts <= lastAudioPtsUs) {
                        outPts = lastAudioPtsUs + 1L
                    }
                    lastAudioPtsUs = outPts
                }
                samplePtsUs = outPts
                sampleFlags = extractor.sampleFlags
                hasSample = true
                return
            }
            hasSample = false
        }
    }

    private fun executeRemuxInterleaved(
        input: VideoInputSource,
        output: File,
        plan: RemuxPlan,
        sourceRotationDegrees: Int,
        ext: String,
    ) {
        val videoExtractor = MediaExtractor()
        var audioExtractor: MediaExtractor? = null
        val muxer = MediaMuxer(output.absolutePath, muxerFormatForExt(ext))
        var muxerStarted = false
        try {
            input.applyTo(videoExtractor, appContext)
            var videoSrcTrack = -1
            var audioSrcTrack = -1
            var maxBufferSize = 1024 * 1024

            for (i in 0 until videoExtractor.trackCount) {
                val fmt = videoExtractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxBufferSize = maxOf(maxBufferSize, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                if (mime.startsWith("video/") && videoSrcTrack < 0) {
                    videoSrcTrack = i
                } else if (!plan.mute && mime.startsWith("audio/") && audioSrcTrack < 0) {
                    audioSrcTrack = i
                }
            }
            if (videoSrcTrack < 0) throw IOException("No video track found")

            val videoDstTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoSrcTrack))
            val audioDstTrack = if (audioSrcTrack >= 0) {
                audioExtractor = MediaExtractor().also { input.applyTo(it, appContext) }
                muxer.addTrack(videoExtractor.getTrackFormat(audioSrcTrack))
            } else {
                -1
            }

            val finalRotation = ((sourceRotationDegrees + plan.rotationDeltaDegrees) % 360 + 360) % 360
            muxer.setOrientationHint(finalRotation)
            muxer.start()
            muxerStarted = true

            val buffer = ByteBuffer.allocate(maxBufferSize)
            val info = MediaCodec.BufferInfo()

            val vCursor = TrackCursor(videoExtractor, videoSrcTrack, videoDstTrack, isVideo = true, plan.segments).apply { start() }
            val aCursor = if (audioExtractor != null && audioDstTrack >= 0) {
                TrackCursor(audioExtractor, audioSrcTrack, audioDstTrack, isVideo = false, plan.segments).apply { start() }
            } else {
                null
            }

            while (vCursor.hasSample || (aCursor != null && aCursor.hasSample)) {
                val pickVideo = when {
                    !vCursor.hasSample -> false
                    aCursor == null || !aCursor.hasSample -> true
                    else -> vCursor.samplePtsUs <= aCursor.samplePtsUs
                }
                val active = if (pickVideo) vCursor else aCursor!!
                val size = active.extractor.readSampleData(buffer, 0)
                if (size >= 0) {
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = active.samplePtsUs
                    info.flags = mapSampleFlags(active.sampleFlags)
                    muxer.writeSampleData(active.dstTrack, buffer, info)
                }
                active.advanceToNext()
            }
        } finally {
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor?.release() }
        }
    }

    private fun mapSampleFlags(extractorFlags: Int): Int {
        var out = 0
        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            out = out or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
            out = out or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return out
    }

    private fun verifyVideoOutput(file: File): Long {
        if (!file.isFile || file.length() <= 0L) {
            throw IOException("Remuxed video file is empty")
        }
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (dur <= 0L) throw IOException("Remuxed video has invalid duration")
            dur
        } finally {
            runCatching { mmr.release() }
        }
    }

    private suspend fun openVideoSource(path: VfsPath): VideoInputSource? {
        if (path is VfsPath.Local) {
            val f = File(path.normalized)
            return if (f.isFile && f.canRead()) VideoInputSource.LocalFile(f, isTemp = false) else null
        }
        if (path is VfsPath.Saf && safFs != null) {
            return VideoInputSource.SafUri(safFs.buildDocumentUri(path))
        }
        tempDir.mkdirs()
        val ext = PathUtils.extensionOf(path.displayName).ifEmpty { "mp4" }
        val tmp = File(tempDir, "vid_in_${System.nanoTime()}.$ext")
        return runCatching {
            fs.openInput(path).use { ins ->
                FileOutputStream(tmp).use { out -> ins.copyTo(out, 64 * 1024) }
            }
            VideoInputSource.LocalFile(tmp, isTemp = true)
        }.getOrElse {
            tmp.delete()
            null
        }
    }

    private suspend fun commitEditedVideo(
        sourcePath: VfsPath,
        tempOutput: File,
        ext: String,
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
            "${base}_edited.$ext",
            existingNames,
            isDirectory = false,
            caseInsensitive = true,
        )
        val targetPath = fs.createFile(parentDir, desiredName, PathUtils.guessMimeType(desiredName))
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
