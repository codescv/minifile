package com.minifile.explorer.ui

import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.MiniJson
import com.minifile.explorer.core.model.SortField
import com.minifile.explorer.core.model.SortSpec
import com.minifile.explorer.core.model.TransferMode
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.ui.util.UiFormatters
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
    fun classifyRestrictedAndroidDir_detectsDataAndObbOnlyOnLocalPaths() {
        assertEquals(
            RestrictedAndroidDirKind.DATA,
            MainViewModel.classifyRestrictedAndroidDir(VfsPath.Local("/storage/emulated/0/Android/data")),
        )
        assertEquals(
            RestrictedAndroidDirKind.OBB,
            MainViewModel.classifyRestrictedAndroidDir(VfsPath.Local("/storage/emulated/0/Android/obb")),
        )
        assertNull(
            MainViewModel.classifyRestrictedAndroidDir(VfsPath.Local("/storage/emulated/0/Android/media")),
        )
        assertNull(
            MainViewModel.classifyRestrictedAndroidDir(
                VfsPath.Local("/storage/emulated/0/Android/data/com.minifile.explorer"),
            ),
        )
        assertNull(
            MainViewModel.classifyRestrictedAndroidDir(
                VfsPath.Saf("content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata", "primary:Android/data"),
            ),
        )
    }

    @Test
    fun classifyRestrictedPackageDir_andTargetSafMetadata_matchExpectedSpec() {
        val dataTarget = MainViewModel.classifyRestrictedPackageDir(
            VfsPath.Local("/storage/emulated/0/Android/data/com.tencent.mm"),
        )
        assertEquals(RestrictedAndroidDirKind.DATA to "com.tencent.mm", dataTarget)

        val obbTarget = MainViewModel.classifyRestrictedPackageDir(
            VfsPath.Local("/storage/emulated/0/Android/obb/com.miHoYo.Yuanshen"),
        )
        assertEquals(RestrictedAndroidDirKind.OBB to "com.miHoYo.Yuanshen", obbTarget)

        // Deeper paths inside an app folder or parent Android/data itself should NOT classify as top-level package root
        assertNull(
            MainViewModel.classifyRestrictedPackageDir(
                VfsPath.Local("/storage/emulated/0/Android/data"),
            ),
        )
        assertNull(
            MainViewModel.classifyRestrictedPackageDir(
                VfsPath.Local("/storage/emulated/0/Android/data/com.tencent.mm/files"),
            ),
        )

        val model = RestrictedPackageTarget(
            kind = RestrictedAndroidDirKind.DATA,
            packageName = "com.tencent.mm",
            appLabel = "WeChat",
        )
        assertEquals("pkg_data:com.tencent.mm", model.safKey)
        assertEquals("primary:Android/data/com.tencent.mm", model.safInitialDocId)
        assertEquals("WeChat data", model.safMountLabel)
        assertEquals("toggle_adb_wireless", UiFormatters.WIRELESS_DEBUGGING_FRAGMENT_KEY)
    }
}
