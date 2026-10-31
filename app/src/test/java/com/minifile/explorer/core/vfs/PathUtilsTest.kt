package com.minifile.explorer.core.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathUtilsTest {

    @Test
    fun normalizeLocalPathResolvesDotsAndSlashes() {
        assertEquals("/", PathUtils.normalizeLocalPath(""))
        assertEquals("/", PathUtils.normalizeLocalPath("///"))
        assertEquals("/storage/emulated/0", PathUtils.normalizeLocalPath("/storage/emulated/0/"))
        assertEquals("/storage/Download", PathUtils.normalizeLocalPath("/storage/emulated/../Download/./"))
        assertEquals("/", PathUtils.normalizeLocalPath("/a/b/../../.."))
    }

    @Test
    fun isAncestorHandlesLocalAndSafPaths() {
        val root = VfsPath.Local("/storage/emulated/0")
        val dl = VfsPath.Local("/storage/emulated/0/Download")
        val dl2 = VfsPath.Local("/storage/emulated/0/Downloads")
        val sub = VfsPath.Local("/storage/emulated/0/Download/Telegram")

        assertTrue(PathUtils.isAncestor(dl, dl))
        assertTrue(PathUtils.isAncestor(dl, sub))
        assertTrue(PathUtils.isStrictDescendant(dl, sub))
        assertFalse(PathUtils.isStrictDescendant(dl, dl))
        assertFalse(PathUtils.isAncestor(dl, dl2))
        assertTrue(PathUtils.isAncestor(root, dl))

        val safRoot = VfsPath.Saf("content://tree/primary", "primary:Download")
        val safChild = VfsPath.Saf("content://tree/primary", "primary:Download/Sub")
        val safSibling = VfsPath.Saf("content://tree/primary", "primary:Downloads")
        val safOtherTree = VfsPath.Saf("content://tree/usb", "primary:Download/Sub")

        assertTrue(PathUtils.isAncestor(safRoot, safRoot))
        assertTrue(PathUtils.isStrictDescendant(safRoot, safChild))
        assertFalse(PathUtils.isAncestor(safRoot, safSibling))
        assertFalse(PathUtils.isAncestor(safRoot, safOtherTree))

        val ftpRoot = VfsPath.Ftp("nas1", "/")
        val ftpShare = VfsPath.Ftp("nas1", "/share")
        val ftpNested = VfsPath.Ftp("nas1", "/share/photos")
        val ftpOtherSrv = VfsPath.Ftp("nas2", "/share/photos")

        assertTrue(PathUtils.isAncestor(ftpRoot, ftpRoot))
        assertTrue(PathUtils.isStrictDescendant(ftpRoot, ftpShare))
        assertTrue(PathUtils.isStrictDescendant(ftpShare, ftpNested))
        assertFalse(PathUtils.isStrictDescendant(ftpShare, ftpShare))
        assertFalse(PathUtils.isAncestor(ftpShare, ftpOtherSrv))
    }

    @Test
    fun uniqueNameAppendsParenthesizedCountersPreservingExtension() {
        val existing = setOf("notes.md", "notes (1).md", "Folder", ".nomedia")
        assertEquals("todo.md", PathUtils.uniqueName("todo.md", existing))
        assertEquals("notes (2).md", PathUtils.uniqueName("notes.md", existing))
        assertEquals("Folder (1)", PathUtils.uniqueName("Folder", existing, isDirectory = true))
        assertEquals(".nomedia (1)", PathUtils.uniqueName(".nomedia", existing))
    }

    @Test
    fun validateNameRejectsIllegalInputs() {
        assertTrue(PathUtils.validateName("valid_name-1.txt") is NameValidation.Valid)
        assertTrue(PathUtils.validateName(".nomedia") is NameValidation.Valid)
        assertTrue(PathUtils.validateName("") is NameValidation.Invalid)
        assertTrue(PathUtils.validateName("   ") is NameValidation.Invalid)
        assertTrue(PathUtils.validateName(".") is NameValidation.Invalid)
        assertTrue(PathUtils.validateName("..") is NameValidation.Invalid)
        assertTrue(PathUtils.validateName("a/b") is NameValidation.Invalid)
        assertTrue(PathUtils.validateName("bad\u0000name") is NameValidation.Invalid)
        assertTrue(PathUtils.validateName("a:b", fatSafe = true) is NameValidation.Invalid)
        assertTrue(PathUtils.validateName("a?b", fatSafe = true) is NameValidation.Invalid)
        assertTrue(PathUtils.validateName("trailing.", fatSafe = true) is NameValidation.Invalid)
    }

    @Test
    fun naturalSortOrdersNumbersNumericallyAndCaseInsensitively() {
        val raw = listOf("file10.txt", "File2.txt", "file1.txt", "file20.txt", "alpha.txt")
        val sorted = raw.sortedWith(PathUtils::naturalCompare)
        assertEquals(
            listOf("alpha.txt", "file1.txt", "File2.txt", "file10.txt", "file20.txt"),
            sorted,
        )
    }

    @Test
    fun hiddenFileDetectionAndSerializationRoundTrip() {
        assertTrue(PathUtils.isHiddenName(".nomedia"))
        assertTrue(PathUtils.isHiddenName(".git"))
        assertFalse(PathUtils.isHiddenName("."))
        assertFalse(PathUtils.isHiddenName(".."))
        assertFalse(PathUtils.isHiddenName("readme.md"))

        val local = VfsPath.Local("/storage/emulated/0/Download")
        assertEquals(local, VfsPath.parse(local.serialize()))

        val saf = VfsPath.Saf(
            "content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata",
            "primary:Android/data/com.example",
        )
        assertEquals(saf, VfsPath.parse(saf.serialize()))

        val ftpRoot = VfsPath.Ftp("server-123", "/")
        assertEquals("ftp://server-123/", ftpRoot.serialize())
        assertEquals(ftpRoot, VfsPath.parse(ftpRoot.serialize()))
        assertEquals(ftpRoot, VfsPath.parse("ftp://server-123"))

        val ftpDeep = VfsPath.Ftp("server-123", "/media/movies/clip.mp4/")
        assertEquals("/media/movies/clip.mp4", ftpDeep.normalizedRemotePath)
        assertEquals("clip.mp4", ftpDeep.displayName)
        assertEquals(ftpDeep, VfsPath.parse(ftpDeep.serialize()))
    }
}
