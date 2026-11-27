package com.minifile.explorer.core.gallery

import com.minifile.explorer.core.vfs.VfsPath

/**
 * Pure range drag-to-select computation.
 *
 * When the user long-presses an item at [anchorIndex] and drags to [currentIndex]:
 * - Every index in `[min(anchor, current)..max(anchor, current)]` is set to [targetSelected]
 *   (true when adding to selection, false when deselecting).
 * - Every index outside that range restores its state from [initialSelection], so dragging back
 *   toward the anchor cleanly shrinks/restores the selection.
 */
object DragSelection {

    fun computeSelection(
        anchorIndex: Int,
        currentIndex: Int,
        initialSelection: Set<VfsPath>,
        orderedPaths: List<VfsPath>,
        targetSelected: Boolean = true,
    ): Set<VfsPath> {
        if (orderedPaths.isEmpty()) return initialSelection
        val clampedAnchor = anchorIndex.coerceIn(0, orderedPaths.lastIndex)
        val clampedCurrent = currentIndex.coerceIn(0, orderedPaths.lastIndex)
        val lo = minOf(clampedAnchor, clampedCurrent)
        val hi = maxOf(clampedAnchor, clampedCurrent)

        val result = LinkedHashSet(initialSelection)
        for (i in lo..hi) {
            val p = orderedPaths[i]
            if (targetSelected) {
                result.add(p)
            } else {
                result.remove(p)
            }
        }
        return result
    }
}
