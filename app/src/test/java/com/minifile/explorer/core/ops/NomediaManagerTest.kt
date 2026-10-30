package com.minifile.explorer.core.ops

import com.minifile.explorer.core.vfs.FakeFileSystem
import com.minifile.explorer.core.vfs.FileSystemRepository
import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NomediaManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun setUnsetToggleScanAndMediaScannerCallbackOnLocalAndSaf() = runBlocking {
        val localFs = LocalFileSystem()
        val safFs = FakeFileSystem("content://saf/media")
        val repo = FileSystemRepository(listOf(localFs, safFs))

        val scanned = mutableListOf<VfsPath>()
        val manager = NomediaManager(repo) { path -> scanned.add(path) }

        val root = VfsPath.Local(tempFolder.newFolder("Pictures").absolutePath)
        val screenshots = repo.createDirectory(root, "Screenshots")
        val camera = repo.createDirectory(root, "Camera")

        assertFalse(manager.has(screenshots))
        assertTrue(manager.set(screenshots, hidden = true))
        assertTrue(manager.has(screenshots))
        assertEquals(listOf(screenshots), scanned)

        // Scan finds Screenshots but not Camera
        val found = manager.scan(root)
        assertEquals(listOf(screenshots), found)

        // Toggle off removes .nomedia
        assertFalse(manager.toggle(screenshots))
        assertFalse(manager.has(screenshots))
        assertEquals(2, scanned.size)
        assertFalse(manager.has(camera))

        // SAF folder toggle
        val safAlbum = repo.createDirectory(safFs.rootPath(), "PrivateAlbum")
        assertTrue(manager.toggle(safAlbum))
        assertTrue(manager.has(safAlbum))
        assertTrue(repo.list(safFs.rootPath(), includeHidden = false).first().hasNomedia)
        assertFalse(manager.set(safAlbum, hidden = false))
        assertFalse(manager.has(safAlbum))
    }
}
