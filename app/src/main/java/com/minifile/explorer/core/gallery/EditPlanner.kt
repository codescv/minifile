package com.minifile.explorer.core.gallery

enum class ImageOutputFormat(val extension: String, val mime: String) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp"),
}

enum class EditUnsupportedReason {
    UNSUPPORTED_IMAGE_FORMAT,
    UNSUPPORTED_VIDEO_CONTAINER,
    VIDEO_SPATIAL_CROP_UNSUPPORTED,
    CLIP_TOO_SHORT_FOR_LOSSLESS,
    INVALID_EDIT_RECIPE,
    INVALID_MEDIA_DIMENSIONS,
}

/**
 * Structured pure-JVM media edit/export error codes so UI layers can render localized messages
 * across EN/ZH/JA without concatenating raw English exception text (L3).
 */
enum class MediaEditErrorCode {
    NO_EDITS,
    UNSUPPORTED_FORMAT,
    CLIP_TOO_SHORT,
    INSUFFICIENT_SPACE,
    READ_FAILED,
    WRITE_FAILED,
}

class MediaEditException(
    val code: MediaEditErrorCode,
    cause: Throwable? = null,
) : RuntimeException(code.name, cause)

data class EditSourceInfo(
    val kind: MediaKind,
    val fileName: String,
    val mime: String,
    val width: Int,
    val height: Int,
    val exifOrientation: Int = CoreCropMath.ORIENTATION_NORMAL,
    val durationMs: Long = 0L,
    val keyframeTimestampsUs: List<Long> = emptyList(),
    val hasAudio: Boolean = false,
)

sealed interface EditExecutionPlan {
    data object NoOp : EditExecutionPlan

    /** Lossless instant EXIF orientation rewrite for JPEG when only rotate/flip changed and no crop. */
    data class LosslessExifJpeg(
        val targetExifOrientation: Int,
    ) : EditExecutionPlan

    /** Region decode + transform + encode preserving EXIF metadata. */
    data class ImageRegionDecodeEncode(
        val rawCropRect: PixelRect,
        val outputFormat: ImageOutputFormat,
        val sourceExifOrientation: Int,
        val userRotateCwDegrees: Int,
        val userFlipHorizontal: Boolean,
        val heicConvertedToJpeg: Boolean,
    ) : EditExecutionPlan

    /** Lossless container remux (MediaExtractor + MediaMuxer). */
    data class LosslessVideoRemux(
        val remuxPlan: RemuxPlan,
    ) : EditExecutionPlan

    data class Unsupported(
        val reason: EditUnsupportedReason,
    ) : EditExecutionPlan
}

/**
 * Pure JVM planner mapping `(EditRecipe, EditSourceInfo)` -> deterministic [EditExecutionPlan].
 */
object EditPlanner {

    fun plan(recipe: EditRecipe, source: EditSourceInfo): EditExecutionPlan {
        if (recipe.kind != source.kind) {
            return EditExecutionPlan.Unsupported(EditUnsupportedReason.INVALID_EDIT_RECIPE)
        }
        if (recipe.isIdentity(source.durationMs)) {
            return EditExecutionPlan.NoOp
        }

        return when (source.kind) {
            MediaKind.IMAGE -> planImage(recipe, source)
            MediaKind.VIDEO -> planVideo(recipe, source)
        }
    }

    private fun planImage(recipe: EditRecipe, source: EditSourceInfo): EditExecutionPlan {
        if (source.width <= 0 || source.height <= 0) {
            return EditExecutionPlan.Unsupported(EditUnsupportedReason.INVALID_MEDIA_DIMENSIONS)
        }
        val hasCrop = recipe.crop != null && !recipe.crop.isFullImage()
        val isJpeg = MediaExtensionRegistry.isJpeg(source.fileName, source.mime)
        val isHeic = MediaExtensionRegistry.isHeic(source.fileName, source.mime)
        val isPng = MediaExtensionRegistry.isPng(source.fileName, source.mime)
        val isWebp = MediaExtensionRegistry.isWebp(source.fileName, source.mime)

        // Lossless fast path: JPEG with only rotate/flip and no crop
        if (isJpeg && !hasCrop) {
            val targetOrientation = CoreCropMath.composeExifOrientation(
                existingExif = source.exifOrientation,
                userRotateCwDegrees = recipe.normalizedRotateDegrees,
                userFlipHorizontal = recipe.flipHorizontal,
            )
            return EditExecutionPlan.LosslessExifJpeg(targetExifOrientation = targetOrientation)
        }

        val outputFormat = when {
            isPng -> ImageOutputFormat.PNG
            isWebp -> ImageOutputFormat.WEBP
            isJpeg || isHeic -> ImageOutputFormat.JPEG
            MediaExtensionRegistry.isMedia(source.fileName, source.mime) -> ImageOutputFormat.JPEG
            else -> return EditExecutionPlan.Unsupported(EditUnsupportedReason.UNSUPPORTED_IMAGE_FORMAT)
        }

        val crop = recipe.crop ?: NormalizedRect.FULL
        val rawCropRect = CoreCropMath.computeRawCropRect(
            displayCrop = crop,
            rawWidth = source.width,
            rawHeight = source.height,
            exifOrientation = source.exifOrientation,
            userRotateCwDegrees = recipe.normalizedRotateDegrees,
            userFlipHorizontal = recipe.flipHorizontal,
            enforceEven2Px = true,
            minSizePx = CoreCropMath.MIN_CROP_SIZE_PX,
        )

        return EditExecutionPlan.ImageRegionDecodeEncode(
            rawCropRect = rawCropRect,
            outputFormat = outputFormat,
            sourceExifOrientation = CoreCropMath.normalizeExifOrientation(source.exifOrientation),
            userRotateCwDegrees = recipe.normalizedRotateDegrees,
            userFlipHorizontal = recipe.flipHorizontal,
            heicConvertedToJpeg = isHeic,
        )
    }

    private fun planVideo(recipe: EditRecipe, source: EditSourceInfo): EditExecutionPlan {
        if (!MediaExtensionRegistry.isLosslessEditableVideo(source.fileName, source.mime)) {
            return EditExecutionPlan.Unsupported(EditUnsupportedReason.UNSUPPORTED_VIDEO_CONTAINER)
        }
        if (recipe.crop != null && !recipe.crop.isFullImage()) {
            return EditExecutionPlan.Unsupported(EditUnsupportedReason.VIDEO_SPATIAL_CROP_UNSUPPORTED)
        }
        val videoSpec = recipe.video ?: VideoEditSpec(
            keepRangesMs = listOf(0L..source.durationMs),
            mute = false,
        )
        return when (
            val res = RemuxPlanner.plan(
                durationMs = source.durationMs,
                keyframeTimestampsUs = source.keyframeTimestampsUs,
                keepRangesMs = videoSpec.keepRangesMs,
                mute = videoSpec.mute,
                rotationDeltaDegrees = recipe.normalizedRotateDegrees,
            )
        ) {
            is RemuxPlanResult.Ready -> EditExecutionPlan.LosslessVideoRemux(res.plan)
            RemuxPlanResult.TooShortForLossless ->
                EditExecutionPlan.Unsupported(EditUnsupportedReason.CLIP_TOO_SHORT_FOR_LOSSLESS)

            RemuxPlanResult.InvalidRanges ->
                EditExecutionPlan.Unsupported(EditUnsupportedReason.INVALID_EDIT_RECIPE)
        }
    }
}
