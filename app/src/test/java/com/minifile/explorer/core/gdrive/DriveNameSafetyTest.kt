package com.minifile.explorer.core.gdrive

import com.minifile.explorer.core.model.GDriveAuthMode
import com.minifile.explorer.core.model.GDriveServerBookmark
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class DriveNameSafetyTest {

    @Test
    fun hostileDriveItemNamesAreSanitisedOnListAndResolveBothWarmAndCold() = runBlocking {
        FakeDriveServer().use { server ->
            val slashItem = server.putFile(
                id = "idslash0123",
                name = "a/b",
                bytes = "slash-body".toByteArray(),
            )
            val dotDotItem = server.putFile(
                id = "iddotdot999",
                name = "..",
                bytes = "dotdot-body".toByteArray(),
            )
            val dotItem = server.putFile(
                id = "iddotsingle",
                name = ".",
                bytes = "dot-body".toByteArray(),
            )
            val emptyItem = server.putFile(
                id = "idempty0000",
                name = "",
                bytes = "empty-body".toByteArray(),
            )
            val nulItem = server.putFile(
                id = "idnulchar11",
                name = "x\u0000y",
                bytes = "nul-body".toByteArray(),
            )

            val bookmark = GDriveServerBookmark(
                id = "srv-safety",
                name = "Safety Drive",
                email = "alice@example.com",
                authMode = GDriveAuthMode.PASTED_TOKEN,
            )
            val tokenProvider = DriveTokenProvider { server.validToken.get() }
            val fs = GoogleDriveFileSystem(
                serverLookup = { if (it == bookmark.id) bookmark else null },
                tokenProviderFor = { tokenProvider },
                apiBase = server.apiBase,
                uploadBase = server.uploadBase,
                baseBackoffMs = 5L,
                maxJitterMs = 2L,
            )

            val root = VfsPath.GDrive(bookmark.id, "/")
            val listed = fs.list(root, includeHidden = true)
            val byName = listed.associateBy { it.name }

            val expectedSlashDisplay = "a${DIVISION_SLASH}b"
            assertTrue("Expected $expectedSlashDisplay in ${byName.keys}", expectedSlashDisplay in byName)
            assertTrue("(unnamed iddotdot)" in byName)
            assertTrue("(unnamed iddotsin)" in byName)
            assertTrue("(unnamed idempty0)" in byName)
            assertTrue("xy" in byName)

            for (node in listed) {
                assertTrue("Display name must never contain '/': ${node.name}", '/' !in node.name)
                assertTrue(node.name != "." && node.name != ".." && node.name.isNotEmpty())
            }

            // Warm read of each sanitised item verifies it resolves to its exact underlying Drive ID
            assertEquals(
                "slash-body",
                fs.openInput(byName.getValue(expectedSlashDisplay).path).use { it.readBytes().decodeToString() },
            )
            assertEquals(
                "dotdot-body",
                fs.openInput(byName.getValue("(unnamed iddotdot)").path).use { it.readBytes().decodeToString() },
            )
            assertEquals(
                "dot-body",
                fs.openInput(byName.getValue("(unnamed iddotsin)").path).use { it.readBytes().decodeToString() },
            )
            assertEquals(
                "empty-body",
                fs.openInput(byName.getValue("(unnamed idempty0)").path).use { it.readBytes().decodeToString() },
            )
            assertEquals(
                "nul-body",
                fs.openInput(byName.getValue("xy").path).use { it.readBytes().decodeToString() },
            )

            // Cold resolve of `/a∕b` (with DIVISION_SLASH) finds Drive's `"a/b"` without prior list()
            fs.invalidateServer(bookmark.id)
            val coldSlashPath = VfsPath.GDrive(bookmark.id, "/$expectedSlashDisplay")
            val coldNode = fs.stat(coldSlashPath)
            assertNotNull("Expected cold stat of $coldSlashPath to succeed", coldNode)
            assertEquals(expectedSlashDisplay, coldNode!!.name)
            assertEquals(
                slashItem.id,
                fs.resolver.resolve(coldSlashPath, tokenProvider)?.meta?.id,
            )
            assertEquals(
                "slash-body",
                fs.openInput(coldSlashPath).use { it.readBytes().decodeToString() },
            )

            // Also verify cold resolve of unnamed fallback items
            fs.invalidateServer(bookmark.id)
            val coldDotDot = VfsPath.GDrive(bookmark.id, "/(unnamed iddotdot)")
            assertEquals(dotDotItem.id, fs.resolver.resolve(coldDotDot, tokenProvider)?.meta?.id)
            assertEquals(dotItem.id, fs.resolver.resolve(VfsPath.GDrive(bookmark.id, "/(unnamed iddotsin)"), tokenProvider)?.meta?.id)
            assertEquals(emptyItem.id, fs.resolver.resolve(VfsPath.GDrive(bookmark.id, "/(unnamed idempty0)"), tokenProvider)?.meta?.id)
            assertEquals(nulItem.id, fs.resolver.resolve(VfsPath.GDrive(bookmark.id, "/xy"), tokenProvider)?.meta?.id)
        }
    }

    @Test
    fun createRenameAndMoveRejectHostileNamesWithZeroHttpRequests() = runBlocking {
        FakeDriveServer().use { server ->
            val validFile = server.putFile(name = "clean.txt", bytes = "ok".toByteArray())
            val bookmark = GDriveServerBookmark(
                id = "srv-reject",
                name = "Reject Drive",
                email = "alice@example.com",
                authMode = GDriveAuthMode.PASTED_TOKEN,
            )
            val fs = GoogleDriveFileSystem(
                serverLookup = { if (it == bookmark.id) bookmark else null },
                tokenProviderFor = { DriveTokenProvider { server.validToken.get() } },
                apiBase = server.apiBase,
                uploadBase = server.uploadBase,
            )

            val root = VfsPath.GDrive(bookmark.id, "/")
            val cleanPath = VfsPath.GDrive(bookmark.id, "/${validFile.name}")
            val invalidNames = listOf("a/b", "..", ".", "", "   ", "x\u0000y", "line\nbreak")

            val beforeCount = server.totalRequests.get()
            for (bad in invalidNames) {
                expectIOException("createDirectory('$bad')") { fs.createDirectory(root, bad) }
                expectIOException("createFile('$bad')") { fs.createFile(root, bad) }
                expectIOException("rename('$bad')") { fs.rename(cleanPath, bad) }
            }

            // moveWithinSameBackend with un-normalized / hostile remotePath segments
            expectIOException("moveWithinSameBackend with '..' dst") {
                fs.moveWithinSameBackend(cleanPath, VfsPath.GDrive(bookmark.id, "/.."))
            }
            expectIOException("moveWithinSameBackend with NUL src") {
                fs.moveWithinSameBackend(VfsPath.GDrive(bookmark.id, "/bad\u0000name"), root)
            }

            assertEquals(
                "Rejected names must make ZERO HTTP calls",
                beforeCount,
                server.totalRequests.get(),
            )
        }
    }

    private suspend fun expectIOException(label: String, block: suspend () -> Any?) {
        try {
            block()
            fail("Expected IOException for $label")
        } catch (_: IOException) {
            // expected
        }
    }
}
