package com.minifile.explorer.core.gallery

import com.minifile.explorer.core.vfs.VfsPath
import org.junit.Assert.assertEquals
import org.junit.Test

class DragSelectionTest {

    private val paths = (0..9).map { VfsPath.Local("/storage/emulated/0/DCIM/IMG_$it.jpg") }

    @Test
    fun forwardAndBackwardDrag_selectsRangeAndShrinksBackCleanly() {
        val initial = setOf(paths[0])

        // Drag forward from 2 to 6
        val selForward = DragSelection.computeSelection(
            anchorIndex = 2,
            currentIndex = 6,
            initialSelection = initial,
            orderedPaths = paths,
            targetSelected = true,
        )
        assertEquals(setOf(paths[0], paths[2], paths[3], paths[4], paths[5], paths[6]), selForward)

        // Drag back from 6 to 3 -> 4,5,6 revert cleanly while 0 (initial) stays selected
        val selShrunk = DragSelection.computeSelection(
            anchorIndex = 2,
            currentIndex = 3,
            initialSelection = initial,
            orderedPaths = paths,
            targetSelected = true,
        )
        assertEquals(setOf(paths[0], paths[2], paths[3]), selShrunk)

        // Drag backward past anchor from 5 to 1
        val selBackward = DragSelection.computeSelection(
            anchorIndex = 5,
            currentIndex = 1,
            initialSelection = emptySet(),
            orderedPaths = paths,
            targetSelected = true,
        )
        assertEquals((1..5).map { paths[it] }.toSet(), selBackward)
    }

    @Test
    fun deselectDrag_unselectsRangeAndRestoresOutsideRange() {
        val initial = (0..7).map { paths[it] }.toSet()

        val afterDeselect = DragSelection.computeSelection(
            anchorIndex = 4,
            currentIndex = 2,
            initialSelection = initial,
            orderedPaths = paths,
            targetSelected = false,
        )
        assertEquals(setOf(paths[0], paths[1], paths[5], paths[6], paths[7]), afterDeselect)

        // Retract drag back to 3 -> item 2 restores to selected!
        val retracted = DragSelection.computeSelection(
            anchorIndex = 4,
            currentIndex = 3,
            initialSelection = initial,
            orderedPaths = paths,
            targetSelected = false,
        )
        assertEquals(setOf(paths[0], paths[1], paths[2], paths[5], paths[6], paths[7]), retracted)
    }
}
