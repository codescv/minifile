package com.minifile.explorer.core.ops

import com.minifile.explorer.core.model.OperationProgress
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

class FileOperationsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun copySameDirCrossBackendAndConflictPolicies() = runBlocking {
        val localFs = LocalFileSystem()
        val safFs = FakeFileSystem("content://usb/tree/1")
        val repo = FileSystemRepository(listOf(localFs, safFs))
        val ops = FileOperations(repo)

        val localRoot = VfsPath.Local(tempFolder.newFolder("phone").absolutePath)
        val safRoot = safFs.rootPath()

        val srcFile = repo.createFile(localRoot, "report.txt")
        repo.openOutput(srcFile).use { it.write("v1-content".toByteArray()) }

        // Same-dir copy auto-renames to report (1).txt
        val sameCopy = ops.copy(listOf(srcFile), localRoot)
        assertEquals(1, sameCopy.succeeded)
        val phoneNames = repo.list(localRoot, includeHidden = true).map { it.name }.toSet()
        assertEquals(setOf("report.txt", "report (1).txt"), phoneNames)

        // Cross-backend copy Local -> SAF
        val toUsb = ops.copy(listOf(srcFile), safRoot)
        assertEquals(1, toUsb.succeeded)
        val usbFile = repo.child(safRoot, "report.txt")
        assertEquals("v1-content", String(repo.openInput(usbFile).readBytes()))

        // Update source and test SKIP, OVERWRITE, KEEP_BOTH
        repo.openOutput(srcFile).use { it.write("v2-updated".toByteArray()) }

        val skipped = ops.copy(listOf(srcFile), safRoot, resolver = ConflictResolver.SKIP_ALL)
        assertEquals(0, skipped.succeeded)
        assertEquals(1, skipped.skipped)
        assertEquals("v1-content", String(repo.openInput(usbFile).readBytes()))

        val overwritten = ops.copy(listOf(srcFile), safRoot, resolver = ConflictResolver.OVERWRITE_ALL)
        assertEquals(1, overwritten.succeeded)
        assertEquals("v2-updated", String(repo.openInput(usbFile).readBytes()))

        val keptBoth = ops.copy(listOf(srcFile), safRoot, resolver = ConflictResolver.KEEP_BOTH_ALL)
        assertEquals(1, keptBoth.succeeded)
        val usbCopy = repo.child(safRoot, "report (1).txt")
        assertEquals("v2-updated", String(repo.openInput(usbCopy).readBytes()))
    }

    @Test
    fun moveCrossBackendMoveIntoSelfRejectionCancellationAndRecursiveDelete() = runBlocking {
        val localFs = LocalFileSystem()
        val safFs = FakeFileSystem("content://usb/tree/2")
        val repo = FileSystemRepository(listOf(localFs, safFs))
        val ops = FileOperations(repo)

        val localRoot = VfsPath.Local(tempFolder.newFolder("phone2").absolutePath)
        val safRoot = safFs.rootPath()

        val projectDir = repo.createDirectory(localRoot, "Project")
        val subDir = repo.createDirectory(projectDir, "sub")
        val doc = repo.createFile(subDir, "notes.md")
        repo.openOutput(doc).use { it.write("# Notes\nHello!".toByteArray()) }

        // Move folder into itself / descendant must fail cleanly
        val selfMove = ops.move(listOf(projectDir), subDir)
        assertEquals(0, selfMove.succeeded)
        assertEquals(1, selfMove.failed.size)
        assertTrue(repo.exists(doc))

        // Cancelled move mid-stream must NOT delete source
        val cancelSink = object : ProgressSink {
            var count = 0
            override fun onProgress(p: OperationProgress) {
                count++
            }
            override fun isCancelled(): Boolean = count >= 2
        }
        val cancelledMove = ops.move(listOf(projectDir), safRoot, sink = cancelSink)
        assertTrue(cancelledMove.cancelled)
        assertTrue(repo.exists(doc))

        // Clean cross-backend move Local -> SAF (overwriting partial target dir from cancelled run)
        val moved = ops.move(listOf(projectDir), safRoot, resolver = ConflictResolver.OVERWRITE_ALL)
        assertEquals(1, moved.succeeded)
        assertFalse(repo.exists(projectDir))
        val movedDoc = repo.child(repo.child(repo.child(safRoot, "Project"), "sub"), "notes.md")
        assertEquals("# Notes\nHello!", String(repo.openInput(movedDoc).readBytes()))

        // Stats and recursive delete on SAF
        val stats = ops.sizeOf(listOf(repo.child(safRoot, "Project")))
        assertEquals(1, stats.fileCount)
        assertEquals(2, stats.dirCount)
        assertEquals(14L, stats.totalBytes)

        val del = ops.delete(listOf(repo.child(safRoot, "Project")))
        assertEquals(1, del.succeeded)
        assertFalse(repo.exists(movedDoc))
    }
}
