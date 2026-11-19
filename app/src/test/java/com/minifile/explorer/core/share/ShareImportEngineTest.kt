package com.minifile.explorer.core.share

import com.minifile.explorer.core.gdrive.GDriveAuthException
import com.minifile.explorer.core.model.MediaScanNotifier
import com.minifile.explorer.core.peer.PeerConflictPolicy
import com.minifile.explorer.core.vfs.FakeFileSystem
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShareImportEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun streamsMultiChunkFileAndAutoCreatesReceivedFolderLocally() = runBlocking {
        val root = tempFolder.newFolder("primary")
        val receivedDir = VfsPath.Local(File(root, "Received").absolutePath)
        val payload = ByteArray(ShareImportEngine.CHUNK_SIZE_BYTES * 3 + 1234) { idx -> (idx % 251).toByte() }
        val rescanned = mutableListOf<VfsPath>()
        val engine = ShareImportEngine(
            fs = LocalFileSystem(),
            mediaScanNotifier = MediaScanNotifier { rescanned.add(it) },
        )

        val progressEvents = mutableListOf<ShareImportProgress>()
        val res = engine.importItems(
            sources = listOf(
                ShareImportSource(
                    desiredName = "archive.bin",
                    sizeBytes = payload.size.toLong(),
                    mimeType = "application/octet-stream",
                    openInput = { ByteArrayInputStream(payload) },
                ),
            ),
            destinationDir = receivedDir,
            conflictPolicy = PeerConflictPolicy.RENAME,
            onProgress = { progressEvents.add(it) },
        )

        assertFalse(res.cancelled)
        assertTrue(res.failed.isEmpty())
        assertEquals(1, res.saved.size)
        assertEquals("archive.bin", res.saved.first().name)
        assertEquals(payload.size.toLong(), res.saved.first().bytesWritten)

        val savedFile = File(root, "Received/archive.bin")
        assertTrue(savedFile.isFile)
        assertArrayEquals(payload, savedFile.readBytes())
        assertTrue("Expected multi-chunk progress updates", progressEvents.size >= 4)
        assertEquals(payload.size.toLong(), progressEvents.last().processedBytes)
        assertEquals(1, rescanned.size)
    }

    @Test
    fun handlesCollisionPoliciesRenameOverwriteAndSkipOnSaf() = runBlocking {
        val fakeSaf = FakeFileSystem()
        val destDir = fakeSaf.createDirectory(fakeSaf.rootPath(), "Shared")
        val existingPath = fakeSaf.createFile(destDir, "note.txt", "text/plain")
        fakeSaf.openOutput(existingPath).use { it.write("original".toByteArray()) }

        val engine = ShareImportEngine(fakeSaf)

        // RENAME
        val renamed = engine.importItems(
            sources = listOf(
                ShareImportSource(
                    desiredName = "note.txt",
                    sizeBytes = 6L,
                    mimeType = "text/plain",
                    openInput = { ByteArrayInputStream("second".toByteArray()) },
                ),
            ),
            destinationDir = destDir,
            conflictPolicy = PeerConflictPolicy.RENAME,
        )
        assertEquals(listOf("note (1).txt"), renamed.saved.map { it.name })
        assertEquals("original", fakeSaf.openInput(existingPath).readBytes().decodeToString())
        assertEquals("second", fakeSaf.openInput(renamed.saved.first().path).readBytes().decodeToString())

        // SKIP
        val skipped = engine.importItems(
            sources = listOf(
                ShareImportSource(
                    desiredName = "note.txt",
                    sizeBytes = 5L,
                    mimeType = "text/plain",
                    openInput = { ByteArrayInputStream("third".toByteArray()) },
                ),
            ),
            destinationDir = destDir,
            conflictPolicy = PeerConflictPolicy.SKIP,
        )
        assertTrue(skipped.saved.isEmpty())
        assertEquals(listOf("note.txt"), skipped.skipped)
        assertEquals("original", fakeSaf.openInput(existingPath).readBytes().decodeToString())

        // OVERWRITE
        val overwritten = engine.importItems(
            sources = listOf(
                ShareImportSource(
                    desiredName = "note.txt",
                    sizeBytes = 9L,
                    mimeType = "text/plain",
                    openInput = { ByteArrayInputStream("replaced!".toByteArray()) },
                ),
            ),
            destinationDir = destDir,
            conflictPolicy = PeerConflictPolicy.OVERWRITE,
        )
        assertEquals(listOf("note.txt"), overwritten.saved.map { it.name })
        assertEquals("replaced!", fakeSaf.openInput(existingPath).readBytes().decodeToString())
    }

    @Test
    fun cancellationMidStreamDeletesPartialNewFile() = runBlocking {
        val dir = tempFolder.newFolder("cancel_target")
        val destDir = VfsPath.Local(dir.absolutePath)
        val cancelled = AtomicBoolean(false)
        val bigData = ByteArray(ShareImportEngine.CHUNK_SIZE_BYTES * 4) { 0x42 }

        val engine = ShareImportEngine(LocalFileSystem())
        val res = engine.importItems(
            sources = listOf(
                ShareImportSource(
                    desiredName = "partial.dat",
                    sizeBytes = bigData.size.toLong(),
                    mimeType = "application/octet-stream",
                    openInput = { ByteArrayInputStream(bigData) },
                ),
            ),
            destinationDir = destDir,
            isCancelled = { cancelled.get() },
            onProgress = { p ->
                if (p.processedBytes >= ShareImportEngine.CHUNK_SIZE_BYTES) {
                    cancelled.set(true)
                }
            },
        )

        assertTrue(res.cancelled)
        assertTrue(res.saved.isEmpty())
        assertFalse("Partially written new file must be cleaned up", File(dir, "partial.dat").exists())
    }

    @Test
    fun surfacesGDriveReauthServerIdOnAuthExpiry() = runBlocking {
        val gdrivePath = VfsPath.GDrive(serverId = "gd-1", remotePath = "/Uploads")
        val failingGDriveFs = object : FileSystem {
            override fun supports(path: VfsPath): Boolean = path is VfsPath.GDrive
            override suspend fun stat(path: VfsPath): VfsNode? =
                throw GDriveAuthException("Google Drive sign-in expired", needsInteraction = true)
            override suspend fun list(dir: VfsPath, includeHidden: Boolean): List<VfsNode> = emptyList()
            override suspend fun child(dir: VfsPath, name: String): VfsPath = dir
            override suspend fun parent(path: VfsPath): VfsPath? = null
            override suspend fun exists(path: VfsPath): Boolean = false
            override suspend fun createDirectory(dir: VfsPath, name: String): VfsPath = dir
            override suspend fun createFile(dir: VfsPath, name: String, mime: String): VfsPath = dir
            override suspend fun delete(path: VfsPath): Boolean = false
            override suspend fun rename(path: VfsPath, newName: String): VfsPath = path
            override suspend fun moveWithinSameBackend(src: VfsPath, dstDir: VfsPath): VfsPath? = null
            override fun openInput(path: VfsPath, offset: Long): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun openOutput(path: VfsPath, append: Boolean): OutputStream = OutputStream.nullOutputStream()
            override suspend fun setLastModified(path: VfsPath, epochMs: Long): Boolean = false
            override suspend fun space(path: VfsPath): Pair<Long, Long>? = null
        }

        val engine = ShareImportEngine(failingGDriveFs)
        val res = engine.importItems(
            sources = listOf(
                ShareImportSource(
                    desiredName = "doc.pdf",
                    sizeBytes = 4L,
                    mimeType = "application/pdf",
                    openInput = { ByteArrayInputStream("test".toByteArray()) },
                ),
            ),
            destinationDir = gdrivePath,
        )

        assertFalse(res.cancelled)
        assertTrue(res.saved.isEmpty())
        assertEquals(1, res.failed.size)
        assertEquals("gd-1", res.reauthGDriveServerId)
    }

    @Test
    fun rejectsOverwritingDirectoryWithFile() = runBlocking {
        val dir = tempFolder.newFolder("dir_conflict")
        File(dir, "reports").mkdirs()
        val destDir = VfsPath.Local(dir.absolutePath)

        val engine = ShareImportEngine(LocalFileSystem())
        val res = engine.importItems(
            sources = listOf(
                ShareImportSource(
                    desiredName = "reports",
                    sizeBytes = 3L,
                    mimeType = "text/plain",
                    openInput = { ByteArrayInputStream("abc".toByteArray()) },
                ),
            ),
            destinationDir = destDir,
            conflictPolicy = PeerConflictPolicy.OVERWRITE,
        )

        assertTrue(res.saved.isEmpty())
        assertEquals(1, res.failed.size)
        assertNull(res.reauthGDriveServerId)
        assertTrue(File(dir, "reports").isDirectory)
    }

    @Test
    fun deduplicatesIdenticalNamesWithinSameMultiFileBatch() = runBlocking {
        val dir = tempFolder.newFolder("batch_dups")
        val destDir = VfsPath.Local(dir.absolutePath)
        val engine = ShareImportEngine(LocalFileSystem())

        val res = engine.importItems(
            sources = listOf(
                ShareImportSource("image.jpg", 3L, "image/jpeg") { ByteArrayInputStream("one".toByteArray()) },
                ShareImportSource("image.jpg", 3L, "image/jpeg") { ByteArrayInputStream("two".toByteArray()) },
                ShareImportSource("image.jpg", 5L, "image/jpeg") { ByteArrayInputStream("three".toByteArray()) },
            ),
            destinationDir = destDir,
            conflictPolicy = PeerConflictPolicy.RENAME,
        )

        assertFalse(res.cancelled)
        assertTrue(res.failed.isEmpty())
        assertEquals(listOf("image.jpg", "image (1).jpg", "image (2).jpg"), res.saved.map { it.name })
        assertEquals("one", File(dir, "image.jpg").readText())
        assertEquals("two", File(dir, "image (1).jpg").readText())
        assertEquals("three", File(dir, "image (2).jpg").readText())
    }

    @Test
    fun overwritePreservesOriginalOnMidStreamFailure() = runBlocking {
        val dir = tempFolder.newFolder("overwrite_fail")
        val originalFile = File(dir, "important.txt").apply { writeText("ORIGINAL-IMPORTANT-CONTENT") }
        val destDir = VfsPath.Local(dir.absolutePath)
        val engine = ShareImportEngine(LocalFileSystem())

        val failingStream = object : InputStream() {
            private var emitted = false
            override fun read(): Int = read(ByteArray(1), 0, 1)
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!emitted) {
                    emitted = true
                    b[off] = 'X'.code.toByte()
                    return 1
                }
                throw IOException("Simulated read error mid-stream")
            }
        }

        val res = engine.importItems(
            sources = listOf(
                ShareImportSource("important.txt", 1024L, "text/plain") { failingStream },
            ),
            destinationDir = destDir,
            conflictPolicy = PeerConflictPolicy.OVERWRITE,
        )

        assertFalse(res.cancelled)
        assertTrue(res.saved.isEmpty())
        assertEquals(1, res.failed.size)
        assertEquals("ORIGINAL-IMPORTANT-CONTENT", originalFile.readText())
        assertEquals(listOf("important.txt"), dir.list()?.toList())
    }

    @Test
    fun overwritePreservesOriginalOnMidStreamCancelOnLocalAndSaf() = runBlocking {
        // 1) LocalFileSystem
        val dir = tempFolder.newFolder("overwrite_cancel")
        val originalFile = File(dir, "important.bin").apply { writeText("KEEP-ME-INTACT") }
        val bigData = ByteArray(ShareImportEngine.CHUNK_SIZE_BYTES * 3) { 0x59 }
        val localCancelled = AtomicBoolean(false)

        val localRes = ShareImportEngine(LocalFileSystem()).importItems(
            sources = listOf(
                ShareImportSource("important.bin", bigData.size.toLong(), "application/octet-stream") {
                    ByteArrayInputStream(bigData)
                },
            ),
            destinationDir = VfsPath.Local(dir.absolutePath),
            conflictPolicy = PeerConflictPolicy.OVERWRITE,
            isCancelled = { localCancelled.get() },
            onProgress = { p ->
                if (p.processedBytes >= ShareImportEngine.CHUNK_SIZE_BYTES) {
                    localCancelled.set(true)
                }
            },
        )
        assertTrue(localRes.cancelled)
        assertTrue(localRes.saved.isEmpty())
        assertEquals("KEEP-ME-INTACT", originalFile.readText())
        assertEquals(listOf("important.bin"), dir.list()?.toList())

        // 2) FakeFileSystem (SAF)
        val fakeSaf = FakeFileSystem()
        val safDir = fakeSaf.createDirectory(fakeSaf.rootPath(), "Docs")
        val safOriginal = fakeSaf.createFile(safDir, "important.bin", "application/octet-stream")
        fakeSaf.openOutput(safOriginal).use { it.write("SAF-ORIGINAL".toByteArray()) }
        val safCancelled = AtomicBoolean(false)

        val safRes = ShareImportEngine(fakeSaf).importItems(
            sources = listOf(
                ShareImportSource("important.bin", bigData.size.toLong(), "application/octet-stream") {
                    ByteArrayInputStream(bigData)
                },
            ),
            destinationDir = safDir,
            conflictPolicy = PeerConflictPolicy.OVERWRITE,
            isCancelled = { safCancelled.get() },
            onProgress = { p ->
                if (p.processedBytes >= ShareImportEngine.CHUNK_SIZE_BYTES) {
                    safCancelled.set(true)
                }
            },
        )
        assertTrue(safRes.cancelled)
        assertTrue(safRes.saved.isEmpty())
        assertEquals("SAF-ORIGINAL", fakeSaf.openInput(safOriginal).readBytes().decodeToString())
        assertEquals(listOf("important.bin"), fakeSaf.list(safDir, includeHidden = true).map { it.name })
    }

    @Test
    fun overwritePreservesOriginalWhenRenameAlwaysFails() = runBlocking {
        val dir = tempFolder.newFolder("overwrite_rename_always_fails")
        val originalFile = File(dir, "doc.txt").apply { writeText("ORIGINAL-DATA") }
        val delegate = LocalFileSystem()
        val failingRenameFs = object : FileSystem by delegate {
            override suspend fun rename(path: VfsPath, newName: String): VfsPath {
                throw IOException("rename boom")
            }
        }

        val res = ShareImportEngine(failingRenameFs).importItems(
            sources = listOf(
                ShareImportSource("doc.txt", 8L, "text/plain") { ByteArrayInputStream("NEW-DATA".toByteArray()) },
            ),
            destinationDir = VfsPath.Local(dir.absolutePath),
            conflictPolicy = PeerConflictPolicy.OVERWRITE,
        )

        assertTrue(res.saved.isEmpty())
        assertEquals(1, res.failed.size)
        assertTrue(originalFile.isFile)
        assertEquals("ORIGINAL-DATA", originalFile.readText())
        assertEquals(listOf("doc.txt"), dir.list()?.toList())
    }

    @Test
    fun overwriteRollsBackOriginalWhenSecondRenameFails() = runBlocking {
        val dir = tempFolder.newFolder("overwrite_second_rename_fails")
        val originalFile = File(dir, "doc.txt").apply { writeText("ORIGINAL-DATA") }
        val delegate = LocalFileSystem()
        var renameCalls = 0
        val secondRenameFailsFs = object : FileSystem by delegate {
            override suspend fun rename(path: VfsPath, newName: String): VfsPath {
                renameCalls++
                if (renameCalls == 2) {
                    throw IOException("simulated RNTO failure on temp -> target")
                }
                return delegate.rename(path, newName)
            }
        }

        val res = ShareImportEngine(secondRenameFailsFs).importItems(
            sources = listOf(
                ShareImportSource("doc.txt", 8L, "text/plain") { ByteArrayInputStream("NEW-DATA".toByteArray()) },
            ),
            destinationDir = VfsPath.Local(dir.absolutePath),
            conflictPolicy = PeerConflictPolicy.OVERWRITE,
        )

        assertTrue(res.saved.isEmpty())
        assertEquals(1, res.failed.size)
        assertEquals(3, renameCalls) // 1: orig->bak, 2: temp->target (throws), 3: bak->orig (rollback)
        assertTrue(originalFile.isFile)
        assertEquals("ORIGINAL-DATA", originalFile.readText())
        assertEquals(listOf("doc.txt"), dir.list()?.toList())
    }
}
