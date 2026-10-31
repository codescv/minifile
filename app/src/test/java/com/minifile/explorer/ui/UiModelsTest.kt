package com.minifile.explorer.ui

import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.MiniJson
import com.minifile.explorer.core.model.SortField
import com.minifile.explorer.core.model.SortSpec
import com.minifile.explorer.core.model.TransferMode
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiModelsTest {

    private fun node(
        name: String,
        isDir: Boolean = false,
        size: Long = 0L,
        mtime: Long = 0L,
    ) = VfsNode(
        path = VfsPath.Local("/tmp/$name"),
        name = name,
        isDirectory = isDir,
        size = size,
        lastModified = mtime,
    )

    @Test
    fun sortSpec_ordersFoldersFirstAndAppliesNaturalNumericNameOrder() {
        val entries = listOf(
            node("file10.txt", size = 100L),
            node("alpha", isDir = true),
            node("file2.txt", size = 500L),
            node("beta", isDir = true),
        )

        val sortedNameAsc = entries.sortedWith(SortSpec(by = SortField.NAME, ascending = true, foldersFirst = true).comparator())
        assertEquals(listOf("alpha", "beta", "file2.txt", "file10.txt"), sortedNameAsc.map { it.name })

        val sortedSizeDesc = entries.sortedWith(SortSpec(by = SortField.SIZE, ascending = false, foldersFirst = true).comparator())
        assertEquals(listOf("beta", "alpha", "file2.txt", "file10.txt"), sortedSizeDesc.map { it.name })

        val roundTripped = SortSpec.fromJson(
            MiniJson.parse(MiniJson.stringify(SortSpec(SortField.DATE, ascending = false, foldersFirst = false).toJson())) as JsonValue.Obj,
        )
        assertEquals(SortField.DATE, roundTripped.by)
        assertEquals(false, roundTripped.ascending)
        assertEquals(false, roundTripped.foldersFirst)
    }

    @Test
    fun clipboardHolder_deduplicatesAndClears() {
        ClipboardHolder.clear()
        assertNull(ClipboardHolder.state.value)

        val p1 = VfsPath.Local("/sdcard/a.txt")
        val p2 = VfsPath.Local("/sdcard/b.txt")
        ClipboardHolder.set(listOf(p1, p2, p1), TransferMode.MOVE)
        assertEquals(listOf(p1, p2), ClipboardHolder.state.value?.items)
        assertEquals(TransferMode.MOVE, ClipboardHolder.state.value?.mode)

        ClipboardHolder.clear()
        assertNull(ClipboardHolder.state.value)
    }

    @Test
    fun mainTab_wirelessTabLabelIsTransferAndToolsTabLabelIsSettings() {
        assertEquals("Transfer", MainTab.WIRELESS.label)
        assertEquals("Settings", MainTab.TOOLS.label)
        assertEquals(listOf("Browse", "Favorites", "Transfer", "Settings"), MainTab.entries.map { it.label })
    }
}
