package com.minifile.explorer.core.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EditPlannerAndCropMathTest {

    @Test
    fun allEightExifOrientations_areExactBijections() {
        val probePoints = listOf(0.17f to 0.31f, 0.83f to 0.12f, 0.42f to 0.79f)
        for (orient in 1..8) {
            for ((x, y) in probePoints) {
                val (u, v) = CoreCropMath.rawPointToDisplayNormalized(x, y, orient)
                val (rx, ry) = CoreCropMath.displayPointToRawNormalized(u, v, orient)
                assertEquals("Forward->Inverse X failed for orientation $orient", x, rx, 1e-5f)
                assertEquals("Forward->Inverse Y failed for orientation $orient", y, ry, 1e-5f)

                val (du, dv) = CoreCropMath.rawPointToDisplayNormalized(rx, ry, orient)
                assertEquals("Inverse->Forward U failed for orientation $orient", u, du, 1e-5f)
                assertEquals("Inverse->Forward V failed for orientation $orient", v, dv, 1e-5f)
            }
        }
    }

    @Test
    fun composeExifOrientation_satisfiesD4GroupClosure() {
        // Rotating 90° CW four times returns to original orientation for every EXIF 1..8
        for (start in 1..8) {
            var cur = start
            repeat(4) {
                cur = CoreCropMath.composeExifOrientation(cur, 90, false)
            }
            assertEquals("4x 90° CW should return to $start", start, cur)

            // Double horizontal flip returns to original
            val flipped = CoreCropMath.composeExifOrientation(start, 0, true)
            val unflipped = CoreCropMath.composeExifOrientation(flipped, 0, true)
            assertEquals("Double flip should return to $start", start, unflipped)
        }
    }

    @Test
    fun randomizedCropRects_alwaysEnforce2pxEvenWidthHeightAnd64pxFloorAcrossAll8Orientations() {
        val rng = Random(42)
        val dimensions = listOf(4032 to 3024, 3001 to 1999, 1080 to 1920, 400 to 300)

        for ((rawW, rawH) in dimensions) {
            for (orient in 1..8) {
                repeat(25) {
                    val l = rng.nextFloat() * 0.9f
                    val t = rng.nextFloat() * 0.9f
                    val r = (l + rng.nextFloat() * (1f - l)).coerceIn(l + 0.001f, 1f)
                    val b = (t + rng.nextFloat() * (1f - t)).coerceIn(t + 0.001f, 1f)
                    val rot = listOf(0, 90, 180, 270)[rng.nextInt(4)]
                    val flip = rng.nextBoolean()

                    val px = CoreCropMath.computeRawCropRect(
                        displayCrop = NormalizedRect(l, t, r, b),
                        rawWidth = rawW,
                        rawHeight = rawH,
                        exifOrientation = orient,
                        userRotateCwDegrees = rot,
                        userFlipHorizontal = flip,
                    )

                    assertEquals("Width must be even (2px aligned)", 0, px.width % 2)
                    assertEquals("Height must be even (2px aligned)", 0, px.height % 2)
                    assertTrue("Width must be >= 64px, got ${px.width}", px.width >= 64)
                    assertTrue("Height must be >= 64px, got ${px.height}", px.height >= 64)
                    assertTrue("Left >= 0", px.left >= 0)
                    assertTrue("Top >= 0", px.top >= 0)
                    assertTrue("Right <= rawW ($rawW), got ${px.right}", px.right <= rawW)
                    assertTrue("Bottom <= rawH ($rawH), got ${px.bottom}", px.bottom <= rawH)
                }
            }
        }
    }

    @Test
    fun editPlanner_routesJpegRotateToLosslessExifAndHeicToDecodeEncode() {
        val jpegSrc = EditSourceInfo(
            kind = MediaKind.IMAGE,
            fileName = "IMG_001.jpg",
            mime = "image/jpeg",
            width = 4000,
            height = 3000,
            exifOrientation = CoreCropMath.ORIENTATION_ROTATE_90,
        )
        val rotateOnly = EditRecipe(kind = MediaKind.IMAGE, rotateDegrees = 90)
        val plan1 = EditPlanner.plan(rotateOnly, jpegSrc)
        assertTrue("JPEG rotate-only must take LosslessExifJpeg", plan1 is EditExecutionPlan.LosslessExifJpeg)
        assertEquals(CoreCropMath.ORIENTATION_ROTATE_180, (plan1 as EditExecutionPlan.LosslessExifJpeg).targetExifOrientation)

        val cropped = EditRecipe(kind = MediaKind.IMAGE, crop = NormalizedRect(0.1f, 0.1f, 0.8f, 0.8f))
        val plan2 = EditPlanner.plan(cropped, jpegSrc)
        assertTrue(plan2 is EditExecutionPlan.ImageRegionDecodeEncode)
        assertFalse((plan2 as EditExecutionPlan.ImageRegionDecodeEncode).heicConvertedToJpeg)

        val heicSrc = jpegSrc.copy(fileName = "IMG_002.heic", mime = "image/heic")
        val planHeic = EditPlanner.plan(rotateOnly, heicSrc)
        assertTrue("HEIC rotate must decode/encode to JPEG", planHeic is EditExecutionPlan.ImageRegionDecodeEncode)
        assertTrue((planHeic as EditExecutionPlan.ImageRegionDecodeEncode).heicConvertedToJpeg)
        assertEquals(ImageOutputFormat.JPEG, planHeic.outputFormat)
    }

    @Test
    fun remuxPlanner_middleCutExpandsOutwardNeverLeakingDeletedFramesAndMaintainsContinuousPts() {
        // Keyframes every 2 seconds: 0s, 2s, 4s, ..., 20s
        val keyframes = (0..10).map { it * 2_000_000L }
        val durationMs = 20_000L

        // User requests keeping [1.5s..7.3s] and [12.4s..19.5s] -> deleting [7.3s..12.4s]
        val res = RemuxPlanner.plan(
            durationMs = durationMs,
            keyframeTimestampsUs = keyframes,
            keepRangesMs = listOf(1_500L..7_300L, 12_400L..19_500L),
            mute = true,
            rotationDeltaDegrees = 90,
        )

        assertTrue("Expected Ready remux plan, got $res", res is RemuxPlanResult.Ready)
        val plan = (res as RemuxPlanResult.Ready).plan
        assertEquals(2, plan.segments.size)

        // Segment 0: start snapped <= 1.5s -> 0s; end = 7.3s
        assertEquals(0L, plan.segments[0].sourceStartUs)
        assertEquals(7_300_000L, plan.segments[0].sourceEndUs)

        // Segment 1: CRITICAL PRIVACY INVARIANT — start snapped >= 12.4s -> 14.0s (NEVER backward to 12.0s!)
        assertEquals(14_000_000L, plan.segments[1].sourceStartUs)
        assertEquals(19_500_000L, plan.segments[1].sourceEndUs)
        assertTrue("Actual cut must start <= requested cut start", plan.actualCutStartUs!! <= 7_300_000L)
        assertTrue("Actual cut must end >= requested cut end (zero leaked frames)", plan.actualCutEndUs!! >= 12_400_000L)

        // Monotonic continuous PTS starting at 0
        assertTrue("PTS must be continuous and monotonic", plan.hasMonotonicContinuousPts())
        assertEquals(0L, plan.segments[0].outputStartUs)
        assertEquals(plan.segments[0].outputEndUs, plan.segments[1].outputStartUs)
        assertEquals(7_300_000L + 5_500_000L, plan.outputDurationUs)
        assertTrue(plan.mute)
        assertEquals(90, plan.rotationDeltaDegrees)
    }

    @Test
    fun remuxPlanner_disablesTrimWhenShorterThanSingleGop() {
        val singleKeyframe = listOf(0L)
        val res = RemuxPlanner.plan(
            durationMs = 1_500L,
            keyframeTimestampsUs = singleKeyframe,
            keepRangesMs = listOf(200L..1_200L),
        )
        assertEquals(RemuxPlanResult.TooShortForLossless, res)

        // Mute-only without trim still succeeds on single-GOP video
        val muteOnly = RemuxPlanner.plan(
            durationMs = 1_500L,
            keyframeTimestampsUs = singleKeyframe,
            keepRangesMs = listOf(0L..1_500L),
            mute = true,
        )
        assertTrue(muteOnly is RemuxPlanResult.Ready)
    }

    @Test
    fun editRecipe_jsonRoundTripPreservesFields() {
        val original = EditRecipe(
            kind = MediaKind.VIDEO,
            rotateDegrees = 270,
            flipHorizontal = false,
            crop = null,
            video = VideoEditSpec(keepRangesMs = listOf(0L..11_200L, 18_400L..43_000L), mute = true),
        )
        val parsed = EditRecipe.fromJsonString(original.toJsonString())
        assertNotNull(parsed)
        assertEquals(original, parsed)
    }
}
