package com.minifile.explorer.core.gallery

data class RemuxSegment(
    val sourceStartUs: Long,
    val sourceEndUs: Long,
    /** Added to source sample PTS so output PTS is monotonic and gap-free starting at 0. */
    val ptsOffsetUs: Long,
) {
    val durationUs: Long get() = (sourceEndUs - sourceStartUs).coerceAtLeast(0L)
    val outputStartUs: Long get() = sourceStartUs + ptsOffsetUs
    val outputEndUs: Long get() = sourceEndUs + ptsOffsetUs
}

data class RemuxPlan(
    val segments: List<RemuxSegment>,
    val outputDurationUs: Long,
    /** Actual deleted middle range after outward keyframe expansion (null if no middle cut). */
    val actualCutStartUs: Long? = null,
    val actualCutEndUs: Long? = null,
    val mute: Boolean = false,
    val rotationDeltaDegrees: Int = 0,
) {
    fun hasMonotonicContinuousPts(): Boolean {
        if (segments.isEmpty()) return false
        var expectedStart = 0L
        for (seg in segments) {
            if (seg.sourceEndUs <= seg.sourceStartUs) return false
            if (seg.outputStartUs < expectedStart) return false
            if (seg.outputStartUs != expectedStart) return false
            expectedStart = seg.outputEndUs
        }
        return expectedStart == outputDurationUs && outputDurationUs > 0L
    }
}

sealed interface RemuxPlanResult {
    data class Ready(val plan: RemuxPlan) : RemuxPlanResult
    data object TooShortForLossless : RemuxPlanResult
    data object InvalidRanges : RemuxPlanResult
}

/**
 * Pure JVM lossless video remux planner (spec §7.4).
 *
 * Hard privacy & WYSIWYG rules:
 * - Supports 1 segment (head/tail Trim) or 2 segments (Trim + single middle Cut).
 * - Trim start snaps to the greatest sync keyframe <= tStart.
 * - Middle Cut `[tCutStart, tCutEnd]` ALWAYS expands outward:
 *     - Segment 0 ends at `<= tCutStart`
 *     - Segment 1 starts at the SMALLEST sync keyframe `>= tCutEnd` (NEVER seeks backward into the deleted section!)
 *   so frames from the deleted section can never leak back into the output.
 * - Computes exact `ptsOffsetUs` per segment so output PTS is strictly monotonic and continuous from 0.
 * - Disables lossless edit (`TooShortForLossless`) when video is shorter than a single GOP or leaves < 500 ms.
 */
object RemuxPlanner {
    const val MIN_OUTPUT_DURATION_US: Long = 500_000L

    fun plan(
        durationMs: Long,
        keyframeTimestampsUs: List<Long>,
        keepRangesMs: List<LongRange>,
        mute: Boolean = false,
        rotationDeltaDegrees: Int = 0,
    ): RemuxPlanResult {
        if (durationMs <= 0L) return RemuxPlanResult.TooShortForLossless
        val durationUs = durationMs * 1000L
        val sortedKeys = keyframeTimestampsUs.filter { it in 0L..durationUs }.distinct().sorted()
        val rotDelta = ((rotationDeltaDegrees % 360) + 360) % 360

        if (keepRangesMs.isEmpty() || keepRangesMs.size > VideoEditSpec.MAX_KEEP_SEGMENTS) {
            return RemuxPlanResult.InvalidRanges
        }

        // Check monotonicity of requested keep ranges
        for (i in keepRangesMs.indices) {
            val r = keepRangesMs[i]
            if (r.first < 0L || r.last <= r.first || r.first > durationMs) {
                return RemuxPlanResult.InvalidRanges
            }
            if (i > 0 && r.first <= keepRangesMs[i - 1].last) {
                return RemuxPlanResult.InvalidRanges
            }
        }

        val isTrimOrCutRequested = keepRangesMs.size > 1 ||
            keepRangesMs[0].first > 0L ||
            keepRangesMs[0].last < durationMs

        // If only mute/rotate (full range kept), even a single-keyframe video is remuxable.
        if (!isTrimOrCutRequested) {
            if (durationUs < MIN_OUTPUT_DURATION_US) return RemuxPlanResult.TooShortForLossless
            val seg = RemuxSegment(sourceStartUs = 0L, sourceEndUs = durationUs, ptsOffsetUs = 0L)
            return RemuxPlanResult.Ready(
                RemuxPlan(
                    segments = listOf(seg),
                    outputDurationUs = durationUs,
                    mute = mute,
                    rotationDeltaDegrees = rotDelta,
                ),
            )
        }

        // Trim/Cut requires at least 2 keyframes (duration >= 1 GOP) so snapping is meaningful.
        if (sortedKeys.size < 2) {
            return RemuxPlanResult.TooShortForLossless
        }

        return if (keepRangesMs.size == 1) {
            val r = keepRangesMs[0]
            val reqStartUs = (r.first * 1000L).coerceIn(0L, durationUs)
            val reqEndUs = (r.last * 1000L).coerceIn(0L, durationUs)
            val snappedStart = greatestKeyAtOrBefore(sortedKeys, reqStartUs) ?: sortedKeys.first()
            val snappedEnd = reqEndUs.coerceAtMost(durationUs)
            if (snappedEnd - snappedStart < MIN_OUTPUT_DURATION_US) {
                return RemuxPlanResult.TooShortForLossless
            }
            val seg = RemuxSegment(
                sourceStartUs = snappedStart,
                sourceEndUs = snappedEnd,
                ptsOffsetUs = -snappedStart,
            )
            RemuxPlanResult.Ready(
                RemuxPlan(
                    segments = listOf(seg),
                    outputDurationUs = seg.durationUs,
                    mute = mute,
                    rotationDeltaDegrees = rotDelta,
                ),
            )
        } else {
            val r0 = keepRangesMs[0]
            val r1 = keepRangesMs[1]
            val reqStart0 = (r0.first * 1000L).coerceIn(0L, durationUs)
            val reqCutStart = (r0.last * 1000L).coerceIn(0L, durationUs)
            val reqCutEnd = (r1.first * 1000L).coerceIn(0L, durationUs)
            val reqEnd1 = (r1.last * 1000L).coerceIn(0L, durationUs)

            val start0 = greatestKeyAtOrBefore(sortedKeys, reqStart0) ?: sortedKeys.first()
            val end0 = reqCutStart.coerceAtLeast(start0)

            // CRITICAL PRIVACY RULE: outward expansion!
            // Segment 1 MUST start at the smallest keyframe >= reqCutEnd (NEVER seek backward into [reqCutStart, reqCutEnd]).
            val start1 = smallestKeyAtOrAfter(sortedKeys, reqCutEnd)
                ?: return RemuxPlanResult.TooShortForLossless
            val end1 = reqEnd1.coerceAtMost(durationUs)

            if (end0 - start0 < MIN_OUTPUT_DURATION_US || end1 - start1 < MIN_OUTPUT_DURATION_US) {
                return RemuxPlanResult.TooShortForLossless
            }
            if (start1 < end0) {
                return RemuxPlanResult.InvalidRanges
            }

            val seg0 = RemuxSegment(
                sourceStartUs = start0,
                sourceEndUs = end0,
                ptsOffsetUs = -start0,
            )
            val seg1Offset = seg0.durationUs - start1
            val seg1 = RemuxSegment(
                sourceStartUs = start1,
                sourceEndUs = end1,
                ptsOffsetUs = seg1Offset,
            )
            val totalOut = seg0.durationUs + seg1.durationUs
            if (totalOut < MIN_OUTPUT_DURATION_US) {
                return RemuxPlanResult.TooShortForLossless
            }
            val plan = RemuxPlan(
                segments = listOf(seg0, seg1),
                outputDurationUs = totalOut,
                actualCutStartUs = end0,
                actualCutEndUs = start1,
                mute = mute,
                rotationDeltaDegrees = rotDelta,
            )
            RemuxPlanResult.Ready(plan)
        }
    }

    private fun greatestKeyAtOrBefore(sortedKeys: List<Long>, targetUs: Long): Long? {
        var lo = 0
        var hi = sortedKeys.lastIndex
        var ans: Long? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = sortedKeys[mid]
            if (v <= targetUs) {
                ans = v
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    private fun smallestKeyAtOrAfter(sortedKeys: List<Long>, targetUs: Long): Long? {
        var lo = 0
        var hi = sortedKeys.lastIndex
        var ans: Long? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = sortedKeys[mid]
            if (v >= targetUs) {
                ans = v
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return ans
    }
}
