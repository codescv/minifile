package com.minifile.explorer.core.gdrive

import com.minifile.explorer.core.model.GDriveAuthMode
import com.minifile.explorer.core.model.GDriveServerBookmark
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveQueryEscapingTest {

    @Test
    fun escapeDriveQueryLiteralEscapesBackslashBeforeSingleQuote() {
        assertEquals("Bob\\'s File.txt", escapeDriveQueryLiteral("Bob's File.txt"))
        assertEquals("back\\\\slash", escapeDriveQueryLiteral("back\\slash"))
        assertEquals("it\\'s \\\\\\' tricky", escapeDriveQueryLiteral("it's \\' tricky"))
        assertEquals("plain_name.pdf", escapeDriveQueryLiteral("plain_name.pdf"))
    }

    @Test
    fun fakeDriveServerRoundTripsQuotesAndBackslashesWithFullyEncodedUris() = runBlocking {
        FakeDriveServer().use { server ->
            val folder = server.putFolder(name = "Team's \\ Docs")
            val f1 = server.putFile(
                name = "Bob's File.txt",
                parentId = folder.id,
                bytes = "alpha".toByteArray(),
            )
            val f2 = server.putFile(
                name = "back\\slash",
                parentId = folder.id,
                bytes = "beta".toByteArray(),
            )
            val f3 = server.putFile(
                name = "it's \\' tricky",
                parentId = folder.id,
                bytes = "gamma".toByteArray(),
            )

            val bookmark = GDriveServerBookmark(
                id = "srv-escape",
                name = "Escape Test",
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

            // Cold resolve each file directly without listing parent first
            for ((name, expectedContent, expectedId) in listOf(
                Triple("Bob's File.txt", "alpha", f1.id),
                Triple("back\\slash", "beta", f2.id),
                Triple("it's \\' tricky", "gamma", f3.id),
            )) {
                fs.invalidateServer(bookmark.id)
                val vfsPath = VfsPath.GDrive(bookmark.id, "/Team's \\ Docs/$name")
                val node = fs.stat(vfsPath)
                assertNotNull("Expected cold stat to resolve $name", node)
                assertEquals(name, node!!.name)
                val resolved = fs.resolver.resolve(vfsPath, tokenProvider)
                assertEquals(expectedId, resolved?.meta?.id)

                val content = fs.openInput(vfsPath).use { it.readBytes().toString(Charsets.UTF_8) }
                assertEquals(expectedContent, content)
            }

            // Exercise addParents / removeParents & pageToken URL encoding too
            val dstDir = fs.createDirectory(VfsPath.GDrive(bookmark.id, "/"), "Archive '26")
            val moved = fs.moveWithinSameBackend(
                VfsPath.GDrive(bookmark.id, "/Team's \\ Docs/Bob's File.txt"),
                dstDir,
            )
            assertNotNull(moved)

            // Populate > 200 items to exercise pageToken URL encoding
            repeat(205) { idx ->
                server.putFile(name = "item '$idx'.txt", parentId = "root")
            }
            val rootItems = fs.list(VfsPath.GDrive(bookmark.id, "/"), includeHidden = true)
            assertTrue(rootItems.size >= 207)

            assertTrue(
                "Expected zero URI encoding or Content-Length violations, got: ${server.protocolViolations}",
                server.protocolViolations.isEmpty(),
            )
        }
    }
}
