package com.minifile.explorer.core.vfs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class LocalFileSystemTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val fs = LocalFileSystem()

    @Test
    fun listStatCreateRenameDeleteAndNomediaDetection() = runBlocking {
        val rootDir = tempFolder.newFolder("workspace")
        val root = VfsPath.Local(rootDir.absolutePath)

        val photosDir = fs.createDirectory(root, "Photos")
        fs.createFile(photosDir, ".nomedia")
        fs.createFile(photosDir, "img1.jpg")
        val hiddenTop = fs.createFile(root, ".secret")
        val note = fs.createFile(root, "hello.txt")

        fs.openOutput(note).use { it.write("Hello MiniFile".toByteArray()) }

        // Hidden excluded, but hasNomedia on Photos must STILL be true
        val visible = fs.list(root, includeHidden = false)
        assertEquals(setOf("Photos", "hello.txt"), visible.map { it.name }.toSet())
        val photosNode = visible.first { it.name == "Photos" }
        assertTrue(photosNode.isDirectory)
        assertTrue(photosNode.hasNomedia)
        assertEquals(1, photosNode.childCount) // img1.jpg visible; .nomedia hidden

        // Hidden included
        val all = fs.list(root, includeHidden = true)
        assertEquals(setOf("Photos", "hello.txt", ".secret"), all.map { it.name }.toSet())
        assertTrue(all.first { it.name == ".secret" }.isHidden)

        // Stat & permissions
        val statNote = fs.stat(note)
        assertNotNull(statNote)
        assertEquals(14L, statNote!!.size)
        assertNotNull(statNote.posixPermissions)
        assertEquals(10, statNote.posixPermissions!!.length)
        assertEquals('-', statNote.posixPermissions!![0])

        // Offset read
        val tail = fs.openInput(note, offset = 6L).use { String(it.readBytes()) }
        assertEquals("MiniFile", tail)

        // Rename
        val renamed = fs.rename(note, "greetings.txt")
        assertFalse(fs.exists(note))
        assertTrue(fs.exists(renamed))

        // Delete
        assertTrue(fs.delete(hiddenTop))
        assertNull(fs.stat(hiddenTop))
    }

    @Test
    fun isInsideDetectsSymlinkEscape() {
        val rootDir = tempFolder.newFolder("jail")
        val outsideDir = tempFolder.newFolder("outside")
        val secretFile = File(outsideDir, "secret.txt").apply { writeText("top-secret") }

        val insideFile = File(rootDir, "ok.txt").apply { writeText("fine") }
        assertTrue(LocalFileSystem.isInside(rootDir, rootDir))
        assertTrue(LocalFileSystem.isInside(insideFile, rootDir))
        assertFalse(LocalFileSystem.isInside(outsideDir, rootDir))

        val linkPath = File(rootDir, "escape_link").toPath()
        Files.createSymbolicLink(linkPath, outsideDir.toPath())
        assertFalse(LocalFileSystem.isInside(linkPath.toFile(), rootDir))
        assertFalse(LocalFileSystem.isInside(File(linkPath.toFile(), secretFile.name), rootDir))
    }
}
