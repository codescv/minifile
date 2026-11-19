package com.minifile.explorer.core.share

import com.minifile.explorer.core.peer.PeerConflictPolicy
import com.minifile.explorer.core.vfs.NameValidation
import com.minifile.explorer.core.vfs.PathUtils
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShareReceivePolicyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // --- Filename Sanitization & Traversal Defense ---

    @Test
    fun sanitizeFilenameKeepsCleanName() {
        assertEquals("report_2026.pdf", ShareReceivePolicy.sanitizeFilename("report_2026.pdf", "application/pdf"))
        assertEquals("Vacation Photo.jpg", ShareReceivePolicy.sanitizeFilename("  Vacation Photo.jpg  ", "image/jpeg"))
    }

    @Test
    fun sanitizeFilenameStripsPathSeparatorsAndTraversal() {
        assertEquals("passwd", ShareReceivePolicy.sanitizeFilename("../../etc/passwd"))
        assertEquals("secret.txt", ShareReceivePolicy.sanitizeFilename("..\\..\\Users\\alice\\secret.txt"))
        assertEquals("photo.png", ShareReceivePolicy.sanitizeFilename("Documents/2026/photo.png"))
        assertEquals("shared_file.txt", ShareReceivePolicy.sanitizeFilename("..", "text/plain"))
        assertEquals("shared_file.pdf", ShareReceivePolicy.sanitizeFilename(".", "application/pdf"))
        assertEquals("shared_file.jpg", ShareReceivePolicy.sanitizeFilename("foo/bar/..", "image/jpeg"))
        assertEquals("shared_file", ShareReceivePolicy.sanitizeFilename("../../../"))
        assertEquals("shared_file", ShareReceivePolicy.sanitizeFilename("..."))
    }

    @Test
    fun sanitizeFilenameStripsNulAndControlChars() {
        val raw = "inv\u0000oice\r\n\t\u0007\u007F_final.pdf"
        assertEquals("invoice_final.pdf", ShareReceivePolicy.sanitizeFilename(raw, "application/pdf"))
    }

    @Test
    fun sanitizeFilenameStripsBidiAndZeroWidthSpoofingChars() {
        val rloAttack = "invoice\u202Efdp.exe"
        assertEquals("invoicefdp.exe", ShareReceivePolicy.sanitizeFilename(rloAttack))

        val zeroWidthAndSeparators = "\uFEFFreport\u200B\u200F\u2066\u2069\u2028\u2029.pdf"
        assertEquals("report.pdf", ShareReceivePolicy.sanitizeFilename(zeroWidthAndSeparators, "application/pdf"))
    }

    @Test
    fun sanitizeFilenameHandlesFatIllegalCharsWhenFatSafe() {
        val raw = "meeting:2026*notes?\"<1>|end..."
        val clean = ShareReceivePolicy.sanitizeFilename(raw, fatSafe = true)
        assertEquals("meeting_2026_notes___1__end", clean)
        assertTrue(PathUtils.validateName(clean, fatSafe = true) is NameValidation.Valid)
    }

    @Test
    fun sanitizeFilenameFallbackFromMimeType() {
        assertEquals("shared_file.jpg", ShareReceivePolicy.sanitizeFilename(null, "image/jpeg"))
        assertEquals("shared_file.pdf", ShareReceivePolicy.sanitizeFilename("   ", "application/pdf"))
        assertEquals("shared_file.txt", ShareReceivePolicy.sanitizeFilename("", "text/plain; charset=utf-8"))
        assertEquals("shared_file", ShareReceivePolicy.sanitizeFilename(null, "application/octet-stream"))
        assertEquals("shared_file", ShareReceivePolicy.sanitizeFilename(null, "*/*"))
        assertEquals("shared_file", ShareReceivePolicy.sanitizeFilename(null, "image/*"))
        assertEquals("attachment.pdf", ShareReceivePolicy.sanitizeFilename("attachment", "application/pdf"))
        assertEquals("attachment", ShareReceivePolicy.sanitizeFilename("attachment", "application/pdf", appendMimeExtensionIfMissing = false))
        assertEquals("shared_file.epub", ShareReceivePolicy.sanitizeFilename(null, "application/epub+zip", extraExtensionFallback = "epub"))
    }

    @Test
    fun sanitizeFilenameCapsLengthWhilePreservingExtension() {
        val longBase = "a".repeat(300)
        val sanitized = ShareReceivePolicy.sanitizeFilename("$longBase.pdf", maxUtf8Bytes = 200)
        assertTrue(sanitized.endsWith(".pdf"))
        assertTrue(sanitized.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(PathUtils.validateName(sanitized) is NameValidation.Valid)

        // Multi-byte CJK + emoji should never split surrogate pairs or exceed UTF-8 byte budget
        val unicodeBase = "文件😀".repeat(80)
        val sanitizedUnicode = ShareReceivePolicy.sanitizeFilename("$unicodeBase.png", maxUtf8Bytes = 120)
        assertTrue(sanitizedUnicode.endsWith(".png"))
        assertTrue(sanitizedUnicode.toByteArray(Charsets.UTF_8).size <= 120)
        assertFalse(Character.isHighSurrogate(sanitizedUnicode.removeSuffix(".png").last()))
    }

    @Test
    fun extensionForMimeTypeMapsCommonTypesAndRejectsWildcards() {
        assertEquals("jpg", ShareReceivePolicy.extensionForMimeType("IMAGE/JPEG"))
        assertEquals("txt", ShareReceivePolicy.extensionForMimeType("text/plain; charset=UTF-8"))
        assertEquals("apk", ShareReceivePolicy.extensionForMimeType("application/vnd.android.package-archive"))
        assertNull(ShareReceivePolicy.extensionForMimeType("application/octet-stream"))
        assertNull(ShareReceivePolicy.extensionForMimeType("*/*"))
        assertNull(ShareReceivePolicy.extensionForMimeType("video/*"))
        assertNull(ShareReceivePolicy.extensionForMimeType(null))
    }

    // --- Collision Handling ---

    @Test
    fun uniqueFileNameIncrementsDeterministically() {
        val existing = setOf("photo.jpg", "photo (1).jpg", "photo (2).jpg")
        assertEquals("photo (3).jpg", ShareReceivePolicy.uniqueFileName("photo.jpg", existing))
        assertEquals("fresh.jpg", ShareReceivePolicy.uniqueFileName("fresh.jpg", existing))

        val noExt = setOf("README", "README (1)")
        assertEquals("README (2)", ShareReceivePolicy.uniqueFileName("README", noExt))
    }

    @Test
    fun uniqueFileNameRespectsCaseInsensitiveAndMaxUtf8Bytes() {
        assertEquals(
            "Photo (1).JPG",
            ShareReceivePolicy.uniqueFileName("Photo.JPG", setOf("photo.jpg"), caseInsensitive = true),
        )

        val maxBase = "x".repeat(251) + ".txt" // 255 bytes exact
        val unique = ShareReceivePolicy.uniqueFileName(maxBase, setOf(maxBase))
        assertTrue(unique.endsWith(" (1).txt"))
        assertTrue(unique.toByteArray(Charsets.UTF_8).size <= 255)
    }

    @Test
    fun resolveCollisionHonorsRenameOverwriteAndSkipPolicies() {
        val existing = setOf("doc.pdf")
        assertEquals(
            ShareReceivePolicy.CollisionDecision.Write("new.pdf", overwrite = false),
            ShareReceivePolicy.resolveCollision("new.pdf", existing, PeerConflictPolicy.RENAME),
        )
        assertEquals(
            ShareReceivePolicy.CollisionDecision.Write("doc (1).pdf", overwrite = false),
            ShareReceivePolicy.resolveCollision("doc.pdf", existing, PeerConflictPolicy.RENAME),
        )
        assertEquals(
            ShareReceivePolicy.CollisionDecision.Write("doc.pdf", overwrite = true),
            ShareReceivePolicy.resolveCollision("doc.pdf", existing, PeerConflictPolicy.OVERWRITE),
        )
        assertEquals(
            ShareReceivePolicy.CollisionDecision.Skip,
            ShareReceivePolicy.resolveCollision("doc.pdf", existing, PeerConflictPolicy.SKIP),
        )
    }

    // --- Security Guard (file:// Private App Directory Rejection) ---

    @Test
    fun blocksPrivateAppDataFileUris() {
        val pkg = "com.minifile.explorer"
        val blocked = listOf(
            "file:///data/data/$pkg/shared_prefs/minifile.xml",
            "file:///data/data/$pkg",
            "FILE:///data/data/$pkg/databases/app.db",
            "file:///data/user/0/$pkg/files/tokens.json",
            "file:///data/user/10/$pkg/cache/secret.bin",
            "file:///data/user_de/0/$pkg/shared_prefs/boot.xml",
            "file:///mnt/expand/abc-123/user/0/$pkg/files/a.txt",
            "file:///storage/emulated/0/../../../../data/data/$pkg/shared_prefs/minifile.xml",
            "file:///data/data/other.app/../$pkg/files/secret",
            "file:///data/data/$pkg/%2e%2e/$pkg/shared_prefs/minifile.xml",
            "file:///%64ata/data/$pkg/shared_prefs/minifile.xml",
            "file://localhost/data/data/$pkg/files/a.txt",
            "file://evil.host/storage/emulated/0/a.txt",
            "file:///data/data/$pkg/files/bad%ZZ.txt",
            "file:///storage/emulated/0/Android/data/$pkg/files/secret.txt",
            "file:///storage/emulated/10/Android/obb/$pkg/main.obb",
            "file:///storage/ABCD-1234/Android/media/$pkg/clip.mp4",
            "file:///sdcard/Android/data/$pkg/cache/ftp_cache/a.bin",
            "file:///proc/self/root/data/data/$pkg/shared_prefs/minifile.xml",
            "file:///proc/self/fd/3",
            "file:///proc/thread-self/cwd/secret",
            "file:///proc/12345/fd/7",
        )
        for (uri in blocked) {
            assertTrue("Expected URI to be blocked: $uri", ShareReceivePolicy.isBlockedFileUri(uri, pkg))
        }
    }

    @Test
    fun blocksOwnContentProviderUrisAndAllowsExternalProviders() {
        val pkg = "com.minifile.explorer"
        val ownAuthorities = listOf("$pkg.fileprovider")

        assertTrue(
            ShareReceivePolicy.isBlockedContentUri(
                "content://$pkg.fileprovider/root/data/data/$pkg/shared_prefs/minifile.xml",
                pkg,
                ownAuthorities,
            ),
        )
        assertTrue(
            ShareReceivePolicy.isBlockedContentUri(
                "content://0@$pkg.fileprovider/cache/ftp_cache/secret.txt",
                pkg,
                ownAuthorities,
            ),
        )
        assertTrue(
            ShareReceivePolicy.isBlockedContentUri(
                "CONTENT://$pkg.custom/item/1",
                pkg,
                ownAuthorities,
            ),
        )
        assertTrue(
            ShareReceivePolicy.isBlockedContentUri(
                "content://opaque.authority/doc/1",
                pkg,
                ownAuthorities,
                authorityOwnerPackage = pkg,
            ),
        )

        assertFalse(
            ShareReceivePolicy.isBlockedContentUri(
                "content://${pkg}2.fileprovider/doc/1",
                pkg,
                ownAuthorities,
                authorityOwnerPackage = "${pkg}2",
            ),
        )
        assertFalse(
            ShareReceivePolicy.isBlockedContentUri(
                "content://com.android.providers.downloads.documents/document/42",
                pkg,
                ownAuthorities,
                authorityOwnerPackage = "com.android.providers.downloads.documents",
            ),
        )
        assertFalse(
            ShareReceivePolicy.isBlockedContentUri(
                "file:///storage/emulated/0/Download/a.pdf",
                pkg,
                ownAuthorities,
            ),
        )
    }

    @Test
    fun allowsExternalStorageSiblingPackagesAndContentUris() {
        val pkg = "com.minifile.explorer"
        val allowed = listOf(
            "file:///storage/emulated/0/Download/report.pdf",
            "file:///storage/emulated/0/DCIM/Camera/IMG_001.jpg",
            "file:///data/data/${pkg}2/files/public.txt",
            "file:///data/user/0/com.example.other/cache/photo.jpg",
            "file:///storage/emulated/0/Android/data/com.example.other/files/public.txt",
            "content://com.android.providers.downloads.documents/document/42",
            "content://media/external/images/media/100",
        )
        for (uri in allowed) {
            assertFalse("Expected URI to be allowed: $uri", ShareReceivePolicy.isBlockedFileUri(uri, pkg))
        }
    }

    @Test
    fun blocksCustomAppDataDirsAndSymlinksIntoPrivateDirs() {
        val pkg = "com.minifile.explorer"
        val privateDir = tempFolder.newFolder("private_app_data")
        val secretFile = File(privateDir, "oauth_tokens.json").apply { writeText("secret") }
        val publicDir = tempFolder.newFolder("public_downloads")
        val normalFile = File(publicDir, "note.txt").apply { writeText("hello") }
        val symlinkToSecret = File(publicDir, "innocent.txt")
        Files.createSymbolicLink(symlinkToSecret.toPath(), secretFile.toPath())

        val appDirs = listOf(privateDir.absolutePath)

        assertTrue(ShareReceivePolicy.isBlockedFileUri(secretFile.toURI().toString(), pkg, appDirs))
        assertTrue(
            "Symlink pointing into private app data must be blocked via canonical path",
            ShareReceivePolicy.isBlockedFileUri(symlinkToSecret.toURI().toString(), pkg, appDirs),
        )
        assertFalse(ShareReceivePolicy.isBlockedFileUri(normalFile.toURI().toString(), pkg, appDirs))
    }

    // --- Text Share Payload Helper ---

    @Test
    fun buildTextSharePayloadDerivesNameFromSubjectOrTimestamp() {
        // 2026-11-19T07:37:11Z
        val fixedEpochMs = 1_795_073_831_000L

        val fromSubject = ShareReceivePolicy.buildTextSharePayload(
            text = "Hello 世界 🚀",
            subject = "Meeting Notes",
            nowEpochMs = fixedEpochMs,
        )
        assertEquals("Meeting Notes.txt", fromSubject.fileName)
        assertArrayEquals("Hello 世界 🚀".toByteArray(Charsets.UTF_8), fromSubject.bytes)
        assertEquals("Hello 世界 🚀", fromSubject.previewText)

        val subjectAlreadyTxt = ShareReceivePolicy.buildTextSharePayload(
            text = "body",
            subject = "todo.TXT",
            nowEpochMs = fixedEpochMs,
        )
        assertEquals("todo.TXT", subjectAlreadyTxt.fileName)

        val maliciousSubject = ShareReceivePolicy.buildTextSharePayload(
            text = "body",
            subject = "../../etc/passwd\u0000",
            nowEpochMs = fixedEpochMs,
        )
        assertEquals("passwd.txt", maliciousSubject.fileName)

        val traversalOnlySubject = ShareReceivePolicy.buildTextSharePayload(
            text = "body",
            subject = "../..",
            nowEpochMs = fixedEpochMs,
        )
        assertEquals("shared_note_20261119_073711.txt", traversalOnlySubject.fileName)

        val noSubject = ShareReceivePolicy.buildTextSharePayload(
            text = "Line 1\nLine 2",
            subject = "   ",
            nowEpochMs = fixedEpochMs,
        )
        assertEquals("shared_note_20261119_073711.txt", noSubject.fileName)
        assertEquals("Line 1 Line 2", noSubject.previewText)
    }
}
