package com.minifile.explorer.core.gdrive

import com.minifile.explorer.core.model.Clock
import com.minifile.explorer.core.model.GDriveAuthMode
import com.minifile.explorer.core.model.GDriveServerBookmark
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class DrivePathResolverTest {

    @Test
    fun nestedResolveUsesCacheAndRespectsMetaAndListingTtl() = runBlocking {
        FakeDriveServer().use { server ->
            val nowMs = AtomicLong(10_000_000L)
            val clock = Clock { nowMs.get() }
            val dirA = server.putFolder(id = "dir-a", name = "Projects", parentId = "root")
            val dirB = server.putFolder(id = "dir-b", name = "2026", parentId = dirA.id)
            val fileC = server.putFile(id = "file-c", name = "spec.txt", parentId = dirB.id, bytes = "v1".toByteArray())
            val fileD = server.putFile(id = "file-d", name = "notes.txt", parentId = dirB.id, bytes = "v2".toByteArray())

            val http = DriveHttp(apiBase = server.apiBase, uploadBase = server.uploadBase, baseBackoffMs = 5L, maxJitterMs = 2L)
            val resolver = DrivePathResolver(http = http, clock = clock)
            val tp = DriveTokenProvider { server.validToken.get() }

            val pathC = VfsPath.GDrive("srv-1", "/Projects/2026/spec.txt")
            val r1 = resolver.resolve(pathC, tp)
            assertEquals(fileC.id, r1?.meta?.id)

            val afterFirst = server.totalRequests.get()
            // Warm repeat resolve makes 0 HTTP requests
            val r2 = resolver.resolve(pathC, tp)
            assertEquals(fileC.id, r2?.meta?.id)
            assertEquals(afterFirst, server.totalRequests.get())

            // Resolving sibling `/Projects/2026/notes.txt` reuses cached `/Projects/2026` -> only 1 query!
            val pathD = VfsPath.GDrive("srv-1", "/Projects/2026/notes.txt")
            val rD = resolver.resolve(pathD, tp)
            assertEquals(fileD.id, rD?.meta?.id)
            assertEquals(afterFirst + 1, server.totalRequests.get())

            // Listing `/Projects/2026` caches complete listing for 30s
            val dirPath = VfsPath.GDrive("srv-1", "/Projects/2026")
            val listed1 = resolver.listDirectory(dirPath, tp)
            assertEquals(2, listed1.size)
            val afterList1 = server.totalRequests.get()

            nowMs.addAndGet(20_000L) // < 30s listing TTL
            val listed2 = resolver.listDirectory(dirPath, tp)
            assertEquals(2, listed2.size)
            assertEquals(afterList1, server.totalRequests.get())

            // Advance past 30s listing TTL -> refetches listing
            nowMs.addAndGet(15_000L) // total +35s
            resolver.listDirectory(dirPath, tp)
            assertEquals(afterList1 + 1, server.totalRequests.get())

            // Advance past 60s meta TTL -> resolve refetches
            val beforeMetaExpiry = server.totalRequests.get()
            nowMs.addAndGet(65_000L)
            resolver.resolve(pathC, tp)
            assertTrue(server.totalRequests.get() > beforeMetaExpiry)
        }
    }

    @Test
    fun duplicateNamesCollisionSkipAndWorkspaceCollisionAreDeterministicWarmAndCold() = runBlocking {
        FakeDriveServer().use { server ->
            // Three files: two named "x.txt" + one literally named "x [2].txt" (M4)
            val x1 = server.putFile(
                id = "id-01",
                name = "x.txt",
                createdTimeMs = 1_000L,
                bytes = "first-x".toByteArray(),
            )
            val x2 = server.putFile(
                id = "id-02",
                name = "x.txt",
                createdTimeMs = 2_000L,
                bytes = "second-x".toByteArray(),
            )
            val xLiteral2 = server.putFile(
                id = "id-03",
                name = "x [2].txt",
                createdTimeMs = 3_000L,
                bytes = "literal-x2".toByteArray(),
            )

            // Real PDF "Report.pdf" + Google Doc "Report" -> "Report.pdf" & "Report (Google Doc).pdf" (M4)
            val realPdf = server.putFile(
                id = "id-pdf",
                name = "Report.pdf",
                mimeType = PDF_MIME,
                createdTimeMs = 4_000L,
                bytes = "real-pdf-bytes".toByteArray(),
            )
            val wsDoc = server.putWorkspaceDoc(
                id = "id-doc",
                name = "Report",
                createdTimeMs = 1_000L, // Even if older, exact non-Workspace "Report.pdf" wins Pass 1!
                exportedPdfBytes = "exported-doc-pdf".toByteArray(),
            )
            val standaloneDoc = server.putWorkspaceDoc(
                id = "id-spec",
                name = "Spec",
                createdTimeMs = 5_000L,
                exportedPdfBytes = "spec-pdf".toByteArray(),
            )

            val bookmark = GDriveServerBookmark(
                id = "srv-dup",
                name = "Dup Drive",
                authMode = GDriveAuthMode.PASTED_TOKEN,
            )
            val tp = DriveTokenProvider { server.validToken.get() }
            val fs = GoogleDriveFileSystem(
                serverLookup = { if (it == bookmark.id) bookmark else null },
                tokenProviderFor = { tp },
                apiBase = server.apiBase,
                uploadBase = server.uploadBase,
                baseBackoffMs = 5L,
                maxJitterMs = 2L,
            )

            val root = VfsPath.GDrive(bookmark.id, "/")
            val listed = fs.list(root, includeHidden = true)
            val names = listed.map { it.name }.toSet()
            assertEquals(
                setOf("x.txt", "x [2].txt", "x [3].txt", "Report.pdf", "Report (Google Doc).pdf", "Spec.pdf"),
                names,
            )

            // Verify warm resolution maps every name to the exact expected Drive ID
            val expectedIds = mapOf(
                "/x.txt" to x1.id,
                "/x [2].txt" to xLiteral2.id, // Exact name wins over duplicate counter!
                "/x [3].txt" to x2.id, // Duplicate skips taken "x [2].txt" -> "x [3].txt"
                "/Report.pdf" to realPdf.id,
                "/Report (Google Doc).pdf" to wsDoc.id,
                "/Spec.pdf" to standaloneDoc.id,
            )
            for ((remotePath, expectedId) in expectedIds) {
                val warm = fs.resolver.resolve(VfsPath.GDrive(bookmark.id, remotePath), tp)
                assertEquals("Warm resolve mismatch for $remotePath", expectedId, warm?.meta?.id)
            }

            // Verify COLD resolution (empty cache before each lookup) resolves the exact same IDs!
            for ((remotePath, expectedId) in expectedIds) {
                fs.invalidateServer(bookmark.id)
                val cold = fs.resolver.resolve(VfsPath.GDrive(bookmark.id, remotePath), tp)
                assertNotNull("Expected cold resolve for $remotePath", cold)
                assertEquals("Cold resolve mismatch for $remotePath", expectedId, cold!!.meta.id)
            }
        }
    }

    @Test
    fun shortcutTraversalStaleId404ReResolveAndDuplicateGroupEviction() = runBlocking {
        FakeDriveServer().use { server ->
            val realFolder = server.putFolder(id = "real-dir", name = "SharedTarget", parentId = "root")
            val innerFile = server.putFile(
                id = "inner-1",
                name = "hello.txt",
                parentId = realFolder.id,
                bytes = "through-shortcut".toByteArray(),
            )
            val folderShortcut = server.putShortcut(
                id = "sc-dir",
                name = "LinkToShared",
                parentId = "root",
                targetId = realFolder.id,
                targetMimeType = FOLDER_MIME,
            )
            val fileShortcut = server.putShortcut(
                id = "sc-file",
                name = "LinkToHello.txt",
                parentId = "root",
                targetId = innerFile.id,
                targetMimeType = "text/plain",
            )

            val bookmark = GDriveServerBookmark(
                id = "srv-sc",
                name = "Shortcut Drive",
                authMode = GDriveAuthMode.PASTED_TOKEN,
            )
            val tp = DriveTokenProvider { server.validToken.get() }
            val fs = GoogleDriveFileSystem(
                serverLookup = { if (it == bookmark.id) bookmark else null },
                tokenProviderFor = { tp },
                apiBase = server.apiBase,
                uploadBase = server.uploadBase,
                baseBackoffMs = 5L,
                maxJitterMs = 2L,
            )

            // Shortcut metadata in root listing
            val rootItems = fs.list(VfsPath.GDrive(bookmark.id, "/"), includeHidden = true).associateBy { it.name }
            val scDirNode = rootItems.getValue("LinkToShared")
            assertTrue(scDirNode.isSymlink)
            assertTrue(scDirNode.isDirectory)
            assertEquals(realFolder.id, scDirNode.symlinkTarget)

            // Traversing folder shortcut lists target folder's children; opening file shortcut reads target bytes
            val viaShortcut = fs.list(VfsPath.GDrive(bookmark.id, "/LinkToShared"), includeHidden = true)
            assertEquals(listOf("hello.txt"), viaShortcut.map { it.name })
            assertEquals(
                "through-shortcut",
                fs.openInput(VfsPath.GDrive(bookmark.id, "/LinkToHello.txt")).use { it.readBytes().decodeToString() },
            )
            // Deleting shortcut trashes ONLY the shortcut, leaving realFolder intact
            assertTrue(fs.delete(VfsPath.GDrive(bookmark.id, "/LinkToShared")))
            assertTrue(server.getEntry(folderShortcut.id)!!.trashed)
            assertEquals(false, server.getEntry(realFolder.id)!!.trashed)
            assertNotNull(server.getEntry(fileShortcut.id))

            // Stale-ID 404 recovery: cache `SharedTarget`, then replace it externally on Drive with a new folder ID
            val sharedPath = VfsPath.GDrive(bookmark.id, "/SharedTarget")
            assertEquals(1, fs.list(sharedPath, includeHidden = true).size)
            // Force listing TTL miss on SharedTarget while keeping SharedTarget's meta ID cached, or remove raw entry
            fs.resolver.evictPathAndParentListing(VfsPath.GDrive(bookmark.id, "/SharedTarget/hello.txt"))
            server.removeRawEntry(realFolder.id)
            val recreatedFolder = server.putFolder(id = "real-dir-v2", name = "SharedTarget", parentId = "root")
            server.putFile(id = "inner-2", name = "fresh.txt", parentId = recreatedFolder.id, bytes = "new".toByteArray())

            // Warm cached meta for `/SharedTarget` still points to `real-dir` -> listing gets 404 -> evicts & re-resolves `real-dir-v2`!
            val recoveredChildren = fs.list(sharedPath, includeHidden = true)
            assertEquals(listOf("fresh.txt"), recoveredChildren.map { it.name })

            // Duplicate-group eviction on delete/rename:
            val dup1 = server.putFile(id = "dup-1", name = "dup.txt", parentId = recreatedFolder.id, createdTimeMs = 1_000L)
            val dup2 = server.putFile(id = "dup-2", name = "dup.txt", parentId = recreatedFolder.id, createdTimeMs = 2_000L)
            fs.invalidate(sharedPath)
            val beforeDeleteNames = fs.list(sharedPath, includeHidden = true).map { it.name }.toSet()
            assertTrue("dup.txt" in beforeDeleteNames && "dup [2].txt" in beforeDeleteNames)

            // Delete primary `dup.txt` (dup1) -> sibling `dup [2].txt` (dup2) must immediately promote to `dup.txt`
            assertTrue(fs.delete(VfsPath.GDrive(bookmark.id, "/SharedTarget/dup.txt")))
            assertTrue(server.getEntry(dup1.id)!!.trashed)
            val afterDelete = fs.list(sharedPath, includeHidden = true).map { it.name }.toSet()
            assertTrue("dup.txt" in afterDelete)
            assertTrue("dup [2].txt" !in afterDelete)
            assertEquals(
                dup2.id,
                fs.resolver.resolve(VfsPath.GDrive(bookmark.id, "/SharedTarget/dup.txt"), tp)?.meta?.id,
            )
        }
    }
}
