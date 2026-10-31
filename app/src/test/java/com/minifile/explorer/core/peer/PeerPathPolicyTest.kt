package com.minifile.explorer.core.peer

import com.minifile.explorer.core.ftp.FtpAccessDeniedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class PeerPathPolicyTest {

    @Test
    fun validateRelPath_acceptsValidNestedUnicodeAndLiteralPlusPaths() {
        assertEquals(listOf("photo.jpg"), PeerPathPolicy.validateRelPath("photo.jpg"))
        assertEquals(
            listOf("Vacation 2026", "Day 1", "IMG_0001.JPG"),
            PeerPathPolicy.validateRelPath("Vacation%202026/Day%201/IMG_0001.JPG"),
        )
        assertEquals(
            listOf("C++ Notes+Draft.txt"),
            PeerPathPolicy.validateRelPath("C++%20Notes+Draft.txt"),
        )
        assertEquals(
            listOf("C++", "a+b.txt"),
            PeerPathPolicy.validateRelPath("C%2B%2B/a+b.txt"),
        )
        assertEquals(
            listOf("文档", "报告 (1).pdf"),
            PeerPathPolicy.validateRelPath("文档/报告%20(1).pdf"),
        )
        // Single-pass percent decoding: %252e%252e decodes once to literal "%2e%2e", not ".."
        assertEquals(
            listOf("%2e%2e", "literal.txt"),
            PeerPathPolicy.validateRelPath("%252e%252e/literal.txt"),
        )
        // Exact 32-segment boundary is allowed
        val maxDepthPath = (1..32).joinToString("/") { "d$it" }
        assertEquals(32, PeerPathPolicy.validateRelPath(maxDepthPath).size)
    }

    @Test
    fun validateRelPath_rejectsTraversalSlashesNulBackslashOverlongAndMalformedPercent() {
        val longSegment = "a".repeat(256) // > 255 UTF-8 bytes
        val depth33 = (1..33).joinToString("/") { "d$it" }
        val over1024 = (1..20).joinToString("/") { "x".repeat(60) }

        val invalidInputs = listOf(
            "",
            "/",
            "/leading.txt",
            "%2Fleading.txt",
            "trailing/",
            "trailing%2F",
            "a//b.txt",
            ".",
            "..",
            "../escape.txt",
            "a/../b.txt",
            "a/./b.txt",
            "%2e%2e/escape.txt",
            "%2e%2e%2fescape.txt",
            "sub/%2E%2E/escape.txt",
            "a\\b.txt",
            "a%5cb.txt",
            "nul\u0000byte.txt",
            "nul%00byte.txt",
            "bad%2",
            "bad%ZZescape.txt",
            longSegment,
            depth33,
            over1024,
        )

        for (raw in invalidInputs) {
            try {
                val res = PeerPathPolicy.validateRelPath(raw)
                fail("Expected FtpAccessDeniedException for input '$raw', got $res")
            } catch (_: FtpAccessDeniedException) {
                // expected
            }
        }
    }

    @Test
    fun validateRelPath_enforcesFatSafeWhenRequested() {
        assertEquals(listOf("colon:ok.txt"), PeerPathPolicy.validateRelPath("colon:ok.txt", fatSafe = false))
        try {
            PeerPathPolicy.validateRelPath("colon:forbidden.txt", fatSafe = true)
            fail("Expected FtpAccessDeniedException when fatSafe=true")
        } catch (_: FtpAccessDeniedException) {
            // expected
        }
    }

    @Test
    fun sanitizeDisplayName_stripsControlsTrimsAndPreservesSurrogatePairs() {
        assertEquals("Pixel 8 Pro", PeerPathPolicy.sanitizeDisplayName("  Pixel\u0000 8\n\r\t Pro\u007F  "))
        assertEquals("", PeerPathPolicy.sanitizeDisplayName("\u0000\u0007\n\t "))

        // Emoji 📱 is 2 UTF-16 code units (\uD83D\uDCF1); cutting at odd boundary must not leave dangling high surrogate
        val rawWithEmoji = "ABCD📱EF" // indices: 0='A',1='B',2='C',3='D',4=high,5=low
        val truncated5 = PeerPathPolicy.sanitizeDisplayName(rawWithEmoji, maxLen = 5)
        assertEquals("ABCD", truncated5)
        assertFalse(Character.isHighSurrogate(truncated5.last()))

        val truncated6 = PeerPathPolicy.sanitizeDisplayName(rawWithEmoji, maxLen = 6)
        assertEquals("ABCD📱", truncated6)
    }

    @Test
    fun corePackage_containsZeroAndroidDalvikOrAndroidxImports() {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, "src/main/java/com/minifile/explorer/core"),
            File(cwd, "app/src/main/java/com/minifile/explorer/core"),
        )
        val coreDir = candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate core/ source directory from $cwd")

        val ktFiles = coreDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("Expected non-empty Kotlin source list under $coreDir", ktFiles.isNotEmpty())

        val forbiddenPrefixes = listOf("import android.", "import androidx.", "import dalvik.")
        val violations = ArrayList<String>()
        for (file in ktFiles) {
            file.useLines { lines ->
                lines.forEachIndexed { idx, line ->
                    val trimmed = line.trim()
                    if (forbiddenPrefixes.any { trimmed.startsWith(it) }) {
                        violations.add("${file.relativeTo(coreDir)}:${idx + 1}: $trimmed")
                    }
                }
            }
        }
        assertTrue(
            "Pure-JVM core/ must have zero android/androidx/dalvik imports, found:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }
}
