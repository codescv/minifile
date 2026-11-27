package com.minifile.explorer.core.gallery

import kotlin.math.ceil

data class SectionMetric(
    val sectionIndex: Int,
    val sectionId: String,
    val keyEpochMs: Long,
    val startY: Float,
    val endY: Float,
    val headerFlatIndex: Int,
    val firstItemFlatIndex: Int,
    val itemCount: Int,
    val rowCount: Int,
)

data class LayoutIndex(
    val sections: List<SectionMetric>,
    val totalHeightPx: Float,
    val columns: Int,
    val headerHeightPx: Float,
    val rowStridePx: Float,
    val totalFlatItems: Int,
)

data class ScrollTarget(
    val sectionIndex: Int,
    val flatIndex: Int,
    val itemOffsetInSection: Int,
    val pixelOffsetPx: Int,
)

/**
 * Drift-free vertical pixel offset index for multi-column sectioned grids.
 *
 * Precomputes exact cumulative section heights:
 *   Y_s = sum_{k=0..s-1} (H_header + ceil(N_k / C) * (W_cell + G_row))
 * and supports O(log S) binary-search inverse lookup from scrollbar fraction [0, 1]
 * to exact LazyGrid flat item index and pixel offset.
 */
object TimelineLayout {

    fun compute(
        sections: List<TimelineSection>,
        columns: Int,
        headerHeightPx: Float,
        cellHeightPx: Float,
        rowSpacingPx: Float,
        includeHeaders: Boolean = true,
    ): LayoutIndex {
        val cols = columns.coerceAtLeast(1)
        val hHeader = if (includeHeaders) headerHeightPx.coerceAtLeast(0f) else 0f
        val rowStride = (cellHeightPx + rowSpacingPx).coerceAtLeast(1f)

        val metrics = ArrayList<SectionMetric>(sections.size)
        var y = 0f
        var flatIdx = 0

        for ((sIdx, sec) in sections.withIndex()) {
            val n = sec.items.size
            val rows = if (n == 0) 0 else ceil(n.toDouble() / cols.toDouble()).toInt()
            val secHeight = hHeader + rows * rowStride
            val headerFlat = flatIdx
            if (includeHeaders) flatIdx += 1
            val firstItemFlat = flatIdx
            flatIdx += n

            metrics.add(
                SectionMetric(
                    sectionIndex = sIdx,
                    sectionId = sec.id,
                    keyEpochMs = sec.keyEpochMs,
                    startY = y,
                    endY = y + secHeight,
                    headerFlatIndex = headerFlat,
                    firstItemFlatIndex = firstItemFlat,
                    itemCount = n,
                    rowCount = rows,
                ),
            )
            y += secHeight
        }

        return LayoutIndex(
            sections = metrics,
            totalHeightPx = y,
            columns = cols,
            headerHeightPx = hHeader,
            rowStridePx = rowStride,
            totalFlatItems = flatIdx,
        )
    }

    /**
     * O(log S) lookup from normalized scrubber fraction [fraction] in [0f, 1f]
     * to exact LazyGrid flat index and scroll offset.
     */
    fun lookupFraction(index: LayoutIndex, fraction: Float): ScrollTarget {
        if (index.sections.isEmpty() || index.totalHeightPx <= 0f) {
            return ScrollTarget(0, 0, 0, 0)
        }
        val p = fraction.coerceIn(0f, 1f)
        val targetY = p * index.totalHeightPx

        // Binary search for section where startY <= targetY < endY
        var lo = 0
        var hi = index.sections.lastIndex
        var found = index.sections.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val m = index.sections[mid]
            when {
                targetY < m.startY -> hi = mid - 1
                targetY >= m.endY -> lo = mid + 1
                else -> {
                    found = mid
                    break
                }
            }
        }
        if (targetY <= 0f) found = 0

        val sec = index.sections[found]
        val localY = (targetY - sec.startY).coerceAtLeast(0f)
        if (index.headerHeightPx > 0f && localY < index.headerHeightPx) {
            return ScrollTarget(
                sectionIndex = sec.sectionIndex,
                flatIndex = sec.headerFlatIndex,
                itemOffsetInSection = 0,
                pixelOffsetPx = localY.toInt(),
            )
        }
        if (sec.itemCount == 0) {
            return ScrollTarget(
                sectionIndex = sec.sectionIndex,
                flatIndex = sec.headerFlatIndex,
                itemOffsetInSection = 0,
                pixelOffsetPx = 0,
            )
        }

        val yInItems = (localY - index.headerHeightPx).coerceAtLeast(0f)
        val row = (yInItems / index.rowStridePx).toInt().coerceIn(0, (sec.rowCount - 1).coerceAtLeast(0))
        val itemOffset = (row * index.columns).coerceAtMost(sec.itemCount - 1)
        val remPx = (yInItems - row * index.rowStridePx).toInt().coerceAtLeast(0)
        return ScrollTarget(
            sectionIndex = sec.sectionIndex,
            flatIndex = sec.firstItemFlatIndex + itemOffset,
            itemOffsetInSection = itemOffset,
            pixelOffsetPx = remPx,
        )
    }

    /**
     * Inverse mapping: given the LazyGrid's first visible flat index and its pixel offset,
     * returns the normalized scroll fraction in [0f, 1f] without drift.
     */
    fun fractionForFlatIndex(
        index: LayoutIndex,
        firstVisibleFlatIndex: Int,
        firstVisibleItemScrollOffsetPx: Int,
        viewportHeightPx: Float = 0f,
    ): Float {
        if (index.sections.isEmpty() || index.totalHeightPx <= 0f) return 0f
        val scrollable = (index.totalHeightPx - viewportHeightPx).coerceAtLeast(1f)
        val flat = firstVisibleFlatIndex.coerceIn(0, (index.totalFlatItems - 1).coerceAtLeast(0))

        // Binary search section containing flat index
        var lo = 0
        var hi = index.sections.lastIndex
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val m = index.sections[mid]
            val secEndFlat = m.firstItemFlatIndex + m.itemCount
            when {
                flat < m.headerFlatIndex -> hi = mid - 1
                flat >= secEndFlat && secEndFlat > m.headerFlatIndex -> lo = mid + 1
                else -> {
                    found = mid
                    break
                }
            }
        }
        val sec = index.sections[found]
        val y = if (index.headerHeightPx > 0f && flat == sec.headerFlatIndex) {
            sec.startY + firstVisibleItemScrollOffsetPx.coerceAtLeast(0)
        } else {
            val itemIdx = (flat - sec.firstItemFlatIndex).coerceAtLeast(0)
            val row = itemIdx / index.columns
            sec.startY + index.headerHeightPx + row * index.rowStridePx + firstVisibleItemScrollOffsetPx.coerceAtLeast(0)
        }
        return (y / scrollable).coerceIn(0f, 1f)
    }
}
