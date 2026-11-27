package com.minifile.explorer.core.gallery

import kotlin.math.roundToInt

data class PixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

/**
 * Pure JVM crop & EXIF orientation geometry engine.
 *
 * Enforces all spec §7.2 invariants:
 * 1. Exact inverse mapping across all 8 EXIF orientations (1..8).
 * 2. Hardware-encoder-friendly 2px even alignment with >= 64px floor (when raw source permits).
 * 3. Aspect ratio constraint with opposite-corner anchoring clamped inside [0, 1]^4.
 * 4. Exact dihedral group D4 composition for lossless JPEG EXIF orientation updates.
 */
object CoreCropMath {
    const val ORIENTATION_NORMAL = 1
    const val ORIENTATION_FLIP_HORIZONTAL = 2
    const val ORIENTATION_ROTATE_180 = 3
    const val ORIENTATION_FLIP_VERTICAL = 4
    const val ORIENTATION_TRANSPOSE = 5
    const val ORIENTATION_ROTATE_90 = 6
    const val ORIENTATION_TRANSVERSE = 7
    const val ORIENTATION_ROTATE_270 = 8

    const val MIN_CROP_SIZE_PX = 64

    fun normalizeExifOrientation(exifOrientation: Int): Int =
        if (exifOrientation in 1..8) exifOrientation else ORIENTATION_NORMAL

    fun swapsAxes(exifOrientation: Int): Boolean =
        exifOrientation in setOf(
            ORIENTATION_TRANSPOSE,
            ORIENTATION_ROTATE_90,
            ORIENTATION_TRANSVERSE,
            ORIENTATION_ROTATE_270,
        )

    fun displayDimensions(rawWidth: Int, rawHeight: Int, exifOrientation: Int): Pair<Int, Int> =
        if (swapsAxes(exifOrientation)) rawHeight to rawWidth else rawWidth to rawHeight

    /**
     * Maps a normalized point `(x, y)` in raw sensor space `[0, 1]^2`
     * to normalized display space `(u, v)` `[0, 1]^2` according to EXIF orientation 1..8.
     */
    fun rawPointToDisplayNormalized(x: Float, y: Float, exifOrientation: Int): Pair<Float, Float> =
        when (normalizeExifOrientation(exifOrientation)) {
            ORIENTATION_NORMAL -> x to y
            ORIENTATION_FLIP_HORIZONTAL -> (1f - x) to y
            ORIENTATION_ROTATE_180 -> (1f - x) to (1f - y)
            ORIENTATION_FLIP_VERTICAL -> x to (1f - y)
            ORIENTATION_TRANSPOSE -> y to x
            ORIENTATION_ROTATE_90 -> (1f - y) to x
            ORIENTATION_TRANSVERSE -> (1f - y) to (1f - x)
            ORIENTATION_ROTATE_270 -> y to (1f - x)
            else -> x to y
        }

    /**
     * Exact inverse of [rawPointToDisplayNormalized]: maps normalized display point `(u, v)`
     * back to raw sensor coordinates `(x, y)`.
     */
    fun displayPointToRawNormalized(u: Float, v: Float, exifOrientation: Int): Pair<Float, Float> =
        when (normalizeExifOrientation(exifOrientation)) {
            ORIENTATION_NORMAL -> u to v
            ORIENTATION_FLIP_HORIZONTAL -> (1f - u) to v
            ORIENTATION_ROTATE_180 -> (1f - u) to (1f - v)
            ORIENTATION_FLIP_VERTICAL -> u to (1f - v)
            ORIENTATION_TRANSPOSE -> v to u
            ORIENTATION_ROTATE_90 -> v to (1f - u)
            ORIENTATION_TRANSVERSE -> (1f - v) to (1f - u)
            ORIENTATION_ROTATE_270 -> (1f - v) to u
            else -> u to v
        }

    /**
     * Maps a point in user-edited space (after [userRotateCwDegrees] and [userFlipHorizontal]
     * were applied on top of EXIF display space) back into EXIF display space.
     *
     * Forward order in editor: EXIF display -> rotate CW by `userRotateCwDegrees` -> flip horizontal if `userFlipHorizontal`.
     * Inverse order: undo flip horizontal -> undo rotate CW.
     */
    fun editedPointToDisplayNormalized(
        u: Float,
        v: Float,
        userRotateCwDegrees: Int,
        userFlipHorizontal: Boolean,
    ): Pair<Float, Float> {
        val u1 = if (userFlipHorizontal) 1f - u else u
        val v1 = v
        val rot = ((userRotateCwDegrees % 360) + 360) % 360
        return when (rot) {
            90 -> v1 to (1f - u1)
            180 -> (1f - u1) to (1f - v1)
            270 -> (1f - v1) to u1
            else -> u1 to v1
        }
    }

    /**
     * Composes an existing EXIF orientation (1..8) with user CW rotation (0/90/180/270)
     * and horizontal flip in display space, returning the resulting EXIF orientation (1..8).
     */
    fun composeExifOrientation(
        existingExif: Int,
        userRotateCwDegrees: Int,
        userFlipHorizontal: Boolean,
    ): Int {
        // Test image of two asymmetric probe points (0.2, 0.3) and (0.8, 0.4)
        // uniquely identifies all 8 elements of D4.
        fun applyForward(x: Float, y: Float): Pair<Float, Float> {
            val (du, dv) = rawPointToDisplayNormalized(x, y, existingExif)
            val rot = ((userRotateCwDegrees % 360) + 360) % 360
            val (ru, rv) = when (rot) {
                90 -> (1f - dv) to du
                180 -> (1f - du) to (1f - dv)
                270 -> dv to (1f - du)
                else -> du to dv
            }
            return if (userFlipHorizontal) (1f - ru) to rv else ru to rv
        }

        val p1 = applyForward(0.2f, 0.3f)
        val p2 = applyForward(0.8f, 0.4f)
        for (candidate in 1..8) {
            val c1 = rawPointToDisplayNormalized(0.2f, 0.3f, candidate)
            val c2 = rawPointToDisplayNormalized(0.8f, 0.4f, candidate)
            val match = kotlin.math.abs(p1.first - c1.first) < 1e-4f &&
                kotlin.math.abs(p1.second - c1.second) < 1e-4f &&
                kotlin.math.abs(p2.first - c2.first) < 1e-4f &&
                kotlin.math.abs(p2.second - c2.second) < 1e-4f
            if (match) return candidate
        }
        return ORIENTATION_NORMAL
    }

    /**
     * Converts a normalized crop rectangle in user-edited display space back to raw sensor pixels,
     * applying 2px even alignment and >= 64px minimum size (bounded by raw source dimensions).
     */
    fun computeRawCropRect(
        displayCrop: NormalizedRect,
        rawWidth: Int,
        rawHeight: Int,
        exifOrientation: Int = ORIENTATION_NORMAL,
        userRotateCwDegrees: Int = 0,
        userFlipHorizontal: Boolean = false,
        enforceEven2Px: Boolean = true,
        minSizePx: Int = MIN_CROP_SIZE_PX,
    ): PixelRect {
        require(rawWidth > 0 && rawHeight > 0) { "Invalid raw dimensions: ${rawWidth}x$rawHeight" }
        val c = displayCrop.clamped(minExtent = 0.0001f)
        val corners = listOf(
            c.left to c.top,
            c.right to c.top,
            c.left to c.bottom,
            c.right to c.bottom,
        ).map { (u, v) ->
            val (du, dv) = editedPointToDisplayNormalized(u, v, userRotateCwDegrees, userFlipHorizontal)
            displayPointToRawNormalized(du, dv, exifOrientation)
        }

        val minX = corners.minOf { it.first }.coerceIn(0f, 1f)
        val maxX = corners.maxOf { it.first }.coerceIn(0f, 1f)
        val minY = corners.minOf { it.second }.coerceIn(0f, 1f)
        val maxY = corners.maxOf { it.second }.coerceIn(0f, 1f)

        val rawLeftF = minX * rawWidth
        val rawTopF = minY * rawHeight
        val rawWidthF = (maxX - minX) * rawWidth
        val rawHeightF = (maxY - minY) * rawHeight

        return alignPixelRect(
            leftF = rawLeftF,
            topF = rawTopF,
            widthF = rawWidthF,
            heightF = rawHeightF,
            boundsWidth = rawWidth,
            boundsHeight = rawHeight,
            enforceEven2Px = enforceEven2Px,
            minSizePx = minSizePx,
        )
    }

    fun alignPixelRect(
        leftF: Float,
        topF: Float,
        widthF: Float,
        heightF: Float,
        boundsWidth: Int,
        boundsHeight: Int,
        enforceEven2Px: Boolean = true,
        minSizePx: Int = MIN_CROP_SIZE_PX,
    ): PixelRect {
        val maxW = if (enforceEven2Px && boundsWidth >= 2) boundsWidth and 1.inv() else boundsWidth.coerceAtLeast(1)
        val maxH = if (enforceEven2Px && boundsHeight >= 2) boundsHeight and 1.inv() else boundsHeight.coerceAtLeast(1)
        val effMinW = minOf(minSizePx, maxW).let { if (enforceEven2Px && maxW >= 2) (it and 1.inv()).coerceAtLeast(2) else it.coerceAtLeast(1) }
        val effMinH = minOf(minSizePx, maxH).let { if (enforceEven2Px && maxH >= 2) (it and 1.inv()).coerceAtLeast(2) else it.coerceAtLeast(1) }

        val targetW = if (enforceEven2Px) {
            (widthF.roundToInt() and 1.inv()).coerceIn(effMinW, maxW)
        } else {
            widthF.roundToInt().coerceIn(effMinW, maxW)
        }
        val targetH = if (enforceEven2Px) {
            (heightF.roundToInt() and 1.inv()).coerceIn(effMinH, maxH)
        } else {
            heightF.roundToInt().coerceIn(effMinH, maxH)
        }

        val maxLeft = (boundsWidth - targetW).coerceAtLeast(0)
        val maxTop = (boundsHeight - targetH).coerceAtLeast(0)
        var left = leftF.roundToInt().coerceIn(0, maxLeft)
        var top = topF.roundToInt().coerceIn(0, maxTop)
        if (enforceEven2Px) {
            left = (left and 1.inv()).coerceIn(0, maxLeft)
            top = (top and 1.inv()).coerceIn(0, maxTop)
        }
        return PixelRect(left, top, left + targetW, top + targetH)
    }

    /**
     * Constrains a normalized crop rectangle to [targetAspectRatio] (width / height in display pixels),
     * anchoring the corner opposite to [draggedCorner] and clamping within `[0, 1]^4`.
     */
    fun constrainCropRect(
        rect: NormalizedRect,
        targetAspectRatio: Float?,
        displayAspectRatio: Float,
        draggedCorner: CropCorner = CropCorner.BOTTOM_RIGHT,
        minExtent: Float = 0.08f,
    ): NormalizedRect {
        val clamped = rect.clamped(minExtent)
        if (targetAspectRatio == null || targetAspectRatio <= 0f || displayAspectRatio <= 0f) {
            return clamped
        }
        // In normalized coords, (nw * dispW) / (nh * dispH) = targetAspectRatio
        // => nw / nh = targetAspectRatio / displayAspectRatio = normRatio
        val normRatio = targetAspectRatio / displayAspectRatio

        val anchorX = when (draggedCorner) {
            CropCorner.TOP_LEFT, CropCorner.BOTTOM_LEFT -> clamped.right
            CropCorner.TOP_RIGHT, CropCorner.BOTTOM_RIGHT -> clamped.left
        }
        val anchorY = when (draggedCorner) {
            CropCorner.TOP_LEFT, CropCorner.TOP_RIGHT -> clamped.bottom
            CropCorner.BOTTOM_LEFT, CropCorner.BOTTOM_RIGHT -> clamped.top
        }

        val maxAvailW = when (draggedCorner) {
            CropCorner.TOP_LEFT, CropCorner.BOTTOM_LEFT -> anchorX
            CropCorner.TOP_RIGHT, CropCorner.BOTTOM_RIGHT -> 1f - anchorX
        }
        val maxAvailH = when (draggedCorner) {
            CropCorner.TOP_LEFT, CropCorner.TOP_RIGHT -> anchorY
            CropCorner.BOTTOM_LEFT, CropCorner.BOTTOM_RIGHT -> 1f - anchorY
        }

        var w = clamped.width.coerceIn(minExtent, maxAvailW.coerceAtLeast(minExtent))
        var h = w / normRatio
        if (h > maxAvailH) {
            h = maxAvailH
            w = h * normRatio
        }
        if (w > maxAvailW) {
            w = maxAvailW
            h = w / normRatio
        }
        if (h < minExtent && minExtent * normRatio <= maxAvailW) {
            h = minExtent
            w = h * normRatio
        }

        val l = when (draggedCorner) {
            CropCorner.TOP_LEFT, CropCorner.BOTTOM_LEFT -> (anchorX - w).coerceAtLeast(0f)
            CropCorner.TOP_RIGHT, CropCorner.BOTTOM_RIGHT -> anchorX
        }
        val t = when (draggedCorner) {
            CropCorner.TOP_LEFT, CropCorner.TOP_RIGHT -> (anchorY - h).coerceAtLeast(0f)
            CropCorner.BOTTOM_LEFT, CropCorner.BOTTOM_RIGHT -> anchorY
        }
        return NormalizedRect(
            left = l.coerceIn(0f, 1f),
            top = t.coerceIn(0f, 1f),
            right = (l + w).coerceIn(0f, 1f),
            bottom = (t + h).coerceIn(0f, 1f),
        )
    }

    /**
     * Computes a centered normalized crop rectangle with [targetAspectRatio] inside display aspect ratio.
     */
    fun centeredCropForAspectRatio(
        targetAspectRatio: Float?,
        displayAspectRatio: Float,
    ): NormalizedRect {
        if (targetAspectRatio == null || targetAspectRatio <= 0f || displayAspectRatio <= 0f) {
            return NormalizedRect.FULL
        }
        val normRatio = targetAspectRatio / displayAspectRatio
        val (w, h) = if (normRatio >= 1f) {
            1f to (1f / normRatio)
        } else {
            normRatio to 1f
        }
        val l = (1f - w) / 2f
        val t = (1f - h) / 2f
        return NormalizedRect(l, t, l + w, t + h)
    }
}
