package com.minifile.explorer.core.ftp

import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class FtpPathResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun clampsDotDotWithinJailAndAllowsLiteralPercentSequences() = runBlocking {
        val rootDir = tempFolder.newFolder("ftp_root")
        val sub = File(rootDir, "Download/Sub").apply { mkdirs() }
        val fs = LocalFileSystem()
        val resolver = FtpPathResolver(VfsPath.Local(rootDir.absolutePath), fs)

        // Deep '..' clamps cleanly to '/' without leaking outside root
        val r1 = resolver.resolve("/", "../../../../etc/passwd")
        assertEquals("/etc/passwd", r1.virtualPath)
        assertTrue(LocalFileSystem.isInside(File((r1.vfsPath as VfsPath.Local).normalized), rootDir))

        val r2 = resolver.resolve("/Download/Sub", "../../..")
        assertEquals("/", r2.virtualPath)
        assertEquals(VfsPath.Local(rootDir.absolutePath), r2.vfsPath)

        // Literal %2e%2e is treated as an ordinary filename, not decoded to '..'
        val r3 = resolver.resolve("/", "%2e%2e/%2e%2e/secret")
        assertEquals("/%2e%2e/%2e%2e/secret", r3.virtualPath)
        assertTrue(LocalFileSystem.isInside(File((r3.vfsPath as VfsPath.Local).normalized), rootDir))

        // Relative navigation inside valid subdirs
        val r4 = resolver.resolve("/Download", "Sub")
        assertEquals("/Download/Sub", r4.virtualPath)
        assertEquals(VfsPath.Local(sub.absolutePath), r4.vfsPath)
    }

    @Test
    fun rejectsBackslashesNulAndSymlinkEscapeOutsideRoot() = runBlocking {
        val rootDir = tempFolder.newFolder("ftp_jail")
        val outsideDir = tempFolder.newFolder("outside_secret")
        File(outsideDir, "passwd").writeText("root:x:0:0")

        val escapeLink = File(rootDir, "escape").toPath()
        Files.createSymbolicLink(escapeLink, outsideDir.toPath())

        val fs = LocalFileSystem()
        val resolver = FtpPathResolver(VfsPath.Local(rootDir.absolutePath), fs)

        val rejectedInputs = listOf(
            "a\\b",
            "..\\secret",
            "bad\u0000file.txt",
            "/escape",
            "/escape/passwd",
        )

        for (bad in rejectedInputs) {
            try {
                resolver.resolve("/", bad)
                fail("Expected FtpAccessDeniedException for '$bad'")
            } catch (_: FtpAccessDeniedException) {
                // Expected
            }
        }
    }
}
