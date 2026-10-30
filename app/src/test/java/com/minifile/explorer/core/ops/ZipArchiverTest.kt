package com.minifile.explorer.core.ops

import com.minifile.explorer.core.vfs.FakeFileSystem
import com.minifile.explorer.core.vfs.FileSystemRepository
import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipArchiverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun roundTripUtf8EmptyDirsLevelsAndSelectiveExtractOnLocalAndSaf() = runBlocking {
        val localFs = LocalFileSystem()
        val safFs = FakeFileSystem("content://saf/zip")
        val repo = FileSystemRepository(listOf(localFs, safFs))
        val archiver = ZipArchiver(repo)

        val srcRoot = VfsPath.Local(tempFolder.newFolder("src").absolutePath)
        val sub = repo.createDirectory(srcRoot, "文档_Docs")
        repo.createDirectory(srcRoot, "EmptyFolder")
        val utfFile = repo.createFile(sub, "测试 файл ✓ (1).txt")
        repo.openOutput(utfFile).use { it.write("UTF-8 payload ✓".toByteArray(Charsets.UTF_8)) }
        val second = repo.createFile(srcRoot, "readme.md")
        repo.openOutput(second).use { it.write("Hello zip".toByteArray()) }

        val zipDir = VfsPath.Local(tempFolder.newFolder("zips").absolutePath)
        val zipPath = archiver.compress(
            items = listOf(sub, repo.child(srcRoot, "EmptyFolder"), second),
            dstDir = zipDir,
            archiveName = "bundle",
            level = Deflater.BEST_SPEED,
        )
        assertEquals("bundle.zip", zipPath.displayName)

        val entries = archiver.listEntries(zipPath)
        assertTrue(entries.any { it.name == "EmptyFolder/" && it.isDirectory })
        assertTrue(entries.any { it.name == "文档_Docs/测试 файл ✓ (1).txt" })

        // Extract to Local
        val outLocal = VfsPath.Local(tempFolder.newFolder("outLocal").absolutePath)
        val res1 = archiver.extract(zipPath, outLocal, overwrite = false)
        assertEquals(2, res1.extractedFiles)
        assertEquals(0, res1.unsafeEntriesSkipped)
        assertTrue(File((outLocal as VfsPath.Local).normalized, "EmptyFolder").isDirectory)
        assertEquals(
            "UTF-8 payload ✓",
            File(outLocal.normalized, "文档_Docs/测试 файл ✓ (1).txt").readText(Charsets.UTF_8),
        )

        // Re-extract with overwrite=false skips existing files
        val resSkip = archiver.extract(zipPath, outLocal, overwrite = false)
        assertEquals(2, resSkip.skippedConflicts)

        // Copy zip to SAF and test stream-based listEntries + selective extract into SAF
        val ops = FileOperations(repo)
        ops.copy(listOf(zipPath), safFs.rootPath())
        val safZip = repo.child(safFs.rootPath(), "bundle.zip")
        val safListed = archiver.listEntries(safZip)
        assertEquals(entries.map { it.name }.toSet(), safListed.map { it.name }.toSet())

        val safOut = repo.createDirectory(safFs.rootPath(), "Extracted")
        val resSaf = archiver.extract(
            zip = safZip,
            dstDir = safOut,
            entries = setOf("readme.md"),
            overwrite = true,
        )
        assertEquals(1, resSaf.extractedFiles)
        assertEquals("Hello zip", String(repo.openInput(repo.child(safOut, "readme.md")).readBytes()))
        assertFalse(repo.exists(repo.child(safOut, "文档_Docs")))
    }

    @Test
    fun zipSlipRejectsCraftedTraversalAbsoluteDriveLetterBackslashNulAndSymlink() = runBlocking {
        val localFs = LocalFileSystem()
        val archiver = ZipArchiver(localFs)

        val workspace = tempFolder.newFolder("zipslip")
        val evilZipFile = File(workspace, "crafted.zip")
        ZipOutputStream(FileOutputStream(evilZipFile), Charsets.UTF_8).use { zos ->
            fun put(name: String, body: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(body.toByteArray())
                zos.closeEntry()
            }
            put("../evil.sh", "bad1")
            put("/abs/escape.txt", "bad2")
            put("C:\\Windows\\x.dll", "bad3")
            put("a/../../b.txt", "bad4")
            put("..\\win_escape.txt", "bad5")
            put("nul\u0000byte.txt", "bad6")
            put("symlink_dir/pwned.txt", "bad7")
            put("safe/good.txt", "legit")
        }

        val dstDirFile = File(workspace, "target").apply { mkdirs() }
        val outsideDir = File(workspace, "outside").apply { mkdirs() }
        // Pre-plant a symlink inside target -> outside to test canonicalPath / symlink escape protection
        Files.createSymbolicLink(File(dstDirFile, "symlink_dir").toPath(), outsideDir.toPath())

        val result = archiver.extract(
            zip = VfsPath.Local(evilZipFile.absolutePath),
            dstDir = VfsPath.Local(dstDirFile.absolutePath),
            overwrite = true,
        )
        assertEquals(1, result.extractedFiles)
        assertEquals(7, result.unsafeEntriesSkipped)
        assertEquals("legit", File(dstDirFile, "safe/good.txt").readText())
        assertFalse(File(workspace, "evil.sh").exists())
        assertFalse(File(outsideDir, "pwned.txt").exists())
    }

    @Test
    fun zipBombGuardAbortsExcessiveExpansion() = runBlocking {
        val localFs = LocalFileSystem()
        // Configure low threshold (16 KB, ratio 5x) to verify zip-bomb abortion cleanly and fast
        val archiver = ZipArchiver(
            fs = localFs,
            maxEntries = 50,
            maxExpansionRatio = 5L,
            bombBytesThreshold = 16 * 1024L,
        )

        val dir = tempFolder.newFolder("bomb")
        val bombZip = File(dir, "zeros.zip")
        ZipOutputStream(FileOutputStream(bombZip)).use { zos ->
            zos.setLevel(Deflater.BEST_COMPRESSION)
            zos.putNextEntry(ZipEntry("huge.bin"))
            // 128 KB of zeros compresses to ~200 bytes (ratio > 500x)
            zos.write(ByteArray(128 * 1024))
            zos.closeEntry()
        }

        val outDir = File(dir, "out").apply { mkdirs() }
        try {
            archiver.extract(
                zip = VfsPath.Local(bombZip.absolutePath),
                dstDir = VfsPath.Local(outDir.absolutePath),
            )
            fail("Expected ZipBombException")
        } catch (_: ZipBombException) {
            // Expected; partial output cleaned up
            assertFalse(File(outDir, "huge.bin").exists())
        }
    }
}
