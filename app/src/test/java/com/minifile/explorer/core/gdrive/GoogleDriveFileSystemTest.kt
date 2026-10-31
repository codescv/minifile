package com.minifile.explorer.core.gdrive

import com.minifile.explorer.core.model.GDriveAuthMode
import com.minifile.explorer.core.model.GDriveServerBookmark
import com.minifile.explorer.core.ops.FileOperations
import com.minifile.explorer.core.vfs.FileSystemRepository
import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class GoogleDriveFileSystemTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newBookmark(id: String = "gd-test"): GDriveServerBookmark = GDriveServerBookmark(
        id = id,
        name = "My Drive",
        email = "alice@example.com",
        authMode = GDriveAuthMode.PASTED_TOKEN,
    )

    private fun newFs(
        server: FakeDriveServer,
        bookmarks: Map<String, GDriveServerBookmark>,
    ): GoogleDriveFileSystem = GoogleDriveFileSystem(
        serverLookup = { bookmarks[it] },
        tokenProviderFor = { DriveTokenProvider { server.validToken.get() } },
        apiBase = server.apiBase,
        uploadBase = server.uploadBase,
        baseBackoffMs = 5L,
        maxJitterMs = 2L,
    )

    @Test
    fun listPagination450EntriesHiddenFilterAndInvalidate() = runBlocking {
        FakeDriveServer().use { server ->
            val bm = newBookmark()
            val fs = newFs(server, mapOf(bm.id to bm))
            val root = VfsPath.GDrive(bm.id, "/")

            repeat(449) { idx ->
                server.putFile(name = "file_%03d.txt".format(idx), bytes = "x".toByteArray())
            }
            server.putFile(name = ".hidden_config", bytes = "secret".toByteArray())

            val visible = fs.list(root, includeHidden = false)
            assertEquals(449, visible.size)
            // 450 total entries at pageSize = 200 -> exactly 3 paginated GET /files calls
            assertEquals(3, server.listRequests.get())

            // Cached listing serves includeHidden = true with 0 extra network calls!
            val withHidden = fs.list(root, includeHidden = true)
            assertEquals(450, withHidden.size)
            assertEquals(3, server.listRequests.get())

            // invalidate(root) forces fresh network fetch
            fs.invalidate(root)
            fs.list(root, includeHidden = true)
            assertEquals(6, server.listRequests.get())
        }
    }

    @Test
    fun bulkCopyOf50FilesUsesInPlaceListingCacheWithAtMostTwoListCalls() = runBlocking {
        FakeDriveServer().use { server ->
            val bm = newBookmark()
            val gdriveFs = newFs(server, mapOf(bm.id to bm))
            val repoFs = FileSystemRepository(listOf(LocalFileSystem(), gdriveFs))
            val ops = FileOperations(repoFs)

            val localSrcDir = tempFolder.newFolder("src50")
            val localPaths = (1..50).map { i ->
                val f = File(localSrcDir, "photo_%02d.bin".format(i))
                f.writeBytes("payload-$i".toByteArray(Charsets.UTF_8))
                VfsPath.Local(f.path)
            }

            val driveRoot = VfsPath.GDrive(bm.id, "/")
            val dstFolder = gdriveFs.createDirectory(driveRoot, "BatchUpload")
            // Prime destination listing once as BrowserScreen does before/during paste
            assertEquals(0, gdriveFs.list(dstFolder, includeHidden = true).size)

            server.listRequests.set(0)
            val copyResult = ops.copy(items = localPaths, dstDir = dstFolder)
            assertEquals("Expected all 50 files to succeed: ${copyResult.failed}", 50, copyResult.succeeded)
            assertTrue(
                "Expected <= 2 directory list requests across 50-file copy, got ${server.listRequests.get()}",
                server.listRequests.get() <= 2,
            )

            // Final list() on dstFolder returns all 50 files from warm in-place cache with ZERO network calls!
            val reqBeforeFinalList = server.totalRequests.get()
            val finalEntries = gdriveFs.list(dstFolder, includeHidden = true)
            assertEquals(50, finalEntries.size)
            assertEquals(
                "Final list(dstFolder) must be served from warm listing cache without network traffic",
                reqBeforeFinalList,
                server.totalRequests.get(),
            )
        }
    }

    @Test
    fun statExistsSpaceCreateDirectoryIdempotentCreateFileNoDuplicateAndM5OpenOutputPreCheck() = runBlocking {
        FakeDriveServer().use { server ->
            val bm = newBookmark()
            val fs = newFs(server, mapOf(bm.id to bm))
            val root = VfsPath.GDrive(bm.id, "/")

            // Root stat & finite space quota
            val rootNode = fs.stat(root)
            assertNotNull(rootNode)
            assertTrue(rootNode!!.isDirectory)
            val finiteSpace = fs.space(root)
            assertNotNull(finiteSpace)
            assertEquals(server.quotaLimit!!, finiteSpace!!.first)
            assertEquals(server.quotaLimit!! - server.quotaUsage, finiteSpace.second)

            // Unlimited Workspace account (no limit field) -> null
            server.quotaLimit = null
            assertNull(fs.space(root))

            // Idempotent createDirectory
            val dir1 = fs.createDirectory(root, "Docs")
            val dir2 = fs.createDirectory(root, "Docs")
            assertEquals(dir1, dir2)
            assertEquals(1, server.activeEntriesInParent("root").count { it.name == "Docs" })

            // createFile followed by openOutput PATCHes the created ID (exactly 1 file ID, no duplicate)
            val createdPath = fs.createFile(dir1, "draft.txt", "text/plain")
            fs.openOutput(createdPath, append = false).use { out ->
                out.write("hello world".toByteArray(Charsets.UTF_8))
            }
            val docsFolderId = server.activeEntriesInParent("root").single { it.name == "Docs" }.id
            val draftsInServer = server.activeEntriesInParent(docsFolderId).filter { it.name == "draft.txt" }
            assertEquals("createFile + openOutput must never create duplicate Drive files", 1, draftsInServer.size)
            assertEquals("hello world", draftsInServer.single().content.decodeToString())

            // M5: External file created outside cached view -> openOutput uncached pre-check finds & PATCHes it!
            val externalFile = server.putFile(
                name = "external.txt",
                parentId = docsFolderId,
                bytes = "old-external".toByteArray(),
            )
            // Note: Docs listing in `fs` is still warm from earlier and does NOT include `external.txt`!
            val externalVfsPath = VfsPath.GDrive(bm.id, "/Docs/external.txt")
            fs.openOutput(externalVfsPath, append = false).use { out ->
                out.write("updated-external".toByteArray(Charsets.UTF_8))
            }
            val externalsInServer = server.activeEntriesInParent(docsFolderId).filter { it.name == "external.txt" }
            assertEquals("M5 pre-check must PATCH externally created file, not POST duplicate", 1, externalsInServer.size)
            assertEquals(externalFile.id, externalsInServer.single().id)
            assertEquals("updated-external", externalsInServer.single().content.decodeToString())

            // M5: Truly missing path -> openOutput creates once via POST resumable
            val brandNewPath = VfsPath.GDrive(bm.id, "/Docs/brand_new.txt")
            fs.openOutput(brandNewPath, append = false).use { out ->
                out.write("fresh-content".toByteArray(Charsets.UTF_8))
            }
            assertTrue(fs.exists(brandNewPath))
            assertEquals("fresh-content", fs.openInput(brandNewPath).use { it.readBytes().decodeToString() })
        }
    }

    @Test
    fun upload20MiBUsesThreeFixedLengthResumableChunksAppendAndResumesDroppedChunk() = runBlocking {
        FakeDriveServer().use { server ->
            val bm = newBookmark()
            val fs = newFs(server, mapOf(bm.id to bm))

            val totalSize = 20 * 1024 * 1024 // 20 MiB = 8 MiB + 8 MiB + 4 MiB
            val payload = ByteArray(totalSize) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
            val bigPath = VfsPath.GDrive(bm.id, "/movie_20mb.bin")

            server.putChunkRequests.clear()
            fs.openOutput(bigPath, append = false).use { out ->
                var off = 0
                val step = 128 * 1024
                while (off < payload.size) {
                    val n = minOf(step, payload.size - off)
                    out.write(payload, off, n)
                    off += n
                }
            }

            assertEquals("20 MiB must be uploaded in 3 PUT chunks (8M + 8M + 4M)", 3, server.putChunkRequests.size)
            assertEquals("bytes 0-8388607/*", server.putChunkRequests[0].contentRangeHeader)
            assertEquals("bytes 8388608-16777215/*", server.putChunkRequests[1].contentRangeHeader)
            assertEquals("bytes 16777216-20971519/20971520", server.putChunkRequests[2].contentRangeHeader)

            for (putReq in server.putChunkRequests) {
                assertNotNull("Every chunk PUT must carry Content-Length (B2)", putReq.contentLengthHeader)
                assertNull("Chunk PUT must never use Transfer-Encoding (B2)", putReq.transferEncodingHeader)
            }
            assertTrue("Zero protocol violations: ${server.protocolViolations}", server.protocolViolations.isEmpty())

            val downloaded = fs.openInput(bigPath).use { it.readBytes() }
            assertArrayEquals(payload, downloaded)

            // Test append = true
            val appendPath = VfsPath.GDrive(bm.id, "/log.txt")
            fs.openOutput(appendPath, append = false).use { it.write("line1\n".toByteArray()) }
            fs.openOutput(appendPath, append = true).use { it.write("line2\n".toByteArray()) }
            assertEquals("line1\nline2\n", fs.openInput(appendPath).use { it.readBytes().decodeToString() })

            // Test resumable chunk recovery after partial commit + 503 drop
            val tenMiB = ByteArray(10 * 1024 * 1024) { idx -> (idx and 0xFF).toByte() }
            server.dropChunkOnceAtBytes.set(3 * 1024 * 1024) // Server commits 3 MiB then returns 503 once
            val resumedPath = VfsPath.GDrive(bm.id, "/resumed_10mb.bin")
            fs.openOutput(resumedPath, append = false).use { it.write(tenMiB) }
            assertArrayEquals(tenMiB, fs.openInput(resumedPath).use { it.readBytes() })
        }
    }

    @Test
    fun openInputRangeIdentityHeader200FallbackAndM6MidStreamReconnect() = runBlocking {
        FakeDriveServer().use { server ->
            val bm = newBookmark()
            val fs = newFs(server, mapOf(bm.id to bm))

            val data = ByteArray(200 * 1024) { idx -> ((idx * 13) and 0xFF).toByte() }
            server.putFile(name = "stream.bin", bytes = data)
            val streamPath = VfsPath.GDrive(bm.id, "/stream.bin")

            // 1. Offset read via HTTP 206 Range + Accept-Encoding: identity
            server.recordedRequests.clear()
            val tailFrom50k = fs.openInput(streamPath, offset = 50_000L).use { it.readBytes() }
            assertArrayEquals(data.copyOfRange(50_000, data.size), tailFrom50k)
            val mediaGet = server.recordedRequests.last { it.queryParams["alt"] == "media" }
            assertEquals("bytes=50000-", mediaGet.rangeHeader)
            assertEquals("identity", mediaGet.acceptEncodingHeader)

            // 2. Server ignores Range and answers 200 -> client manually skips offset bytes accurately
            server.ignoreRangeOnDownload.set(true)
            val tail200 = fs.openInput(streamPath, offset = 123_456L).use { it.readBytes() }
            assertArrayEquals(data.copyOfRange(123_456, data.size), tail200)
            server.ignoreRangeOnDownload.set(false)

            // 3. M6: Single mid-stream drop -> one transparent Range reconnect -> full bytes delivered intact
            server.dropDownloadAfterBytes = 40 * 1024
            server.dropDownloadMidStreamTimes.set(1)
            val recovered = fs.openInput(streamPath, offset = 0L).use { it.readBytes() }
            assertArrayEquals(data, recovered)

            // 4. M6: Second drop on same download -> throws actionable IOException naming the path
            server.dropDownloadMidStreamTimes.set(2)
            try {
                fs.openInput(streamPath, offset = 0L).use { it.readBytes() }
                fail("Expected IOException when download drops twice")
            } catch (e: IOException) {
                assertTrue(
                    "Expected path '/stream.bin' in message, got: ${e.message}",
                    e.message!!.contains("/stream.bin"),
                )
            }
        }
    }

    @Test
    fun workspaceExportQuotaErrorsRenameMoveDeleteSetLastModifiedAnd503Backoff() = runBlocking {
        FakeDriveServer().use { server ->
            val bm = newBookmark()
            val bmOther = newBookmark(id = "gd-other")
            val fs = newFs(server, mapOf(bm.id to bm, bmOther.id to bmOther))
            val root = VfsPath.GDrive(bm.id, "/")

            // Workspace PDF export + exportSizeLimitExceeded
            val doc = server.putWorkspaceDoc(
                name = "Design",
                exportedPdfBytes = "%PDF-1.4 design doc".toByteArray(),
            )
            val docPath = VfsPath.GDrive(bm.id, "/Design.pdf")
            assertEquals("%PDF-1.4 design doc", fs.openInput(docPath).use { it.readBytes().decodeToString() })

            doc.tooLargeToExport = true
            try {
                fs.openInput(docPath).use { it.readBytes() }
                fail("Expected exportSizeLimitExceeded IOException")
            } catch (e: IOException) {
                assertTrue("Got: ${e.message}", e.message!!.contains("Google Doc is too large to export (10 MB limit)"))
            }
            doc.tooLargeToExport = false

            // Rename Workspace doc strips virtual `.pdf` suffix on Drive
            val renamedDocPath = fs.rename(docPath, "Architecture.pdf")
            assertEquals("/Architecture.pdf", (renamedDocPath as VfsPath.GDrive).normalizedRemotePath)
            assertEquals("Architecture", server.getEntry(doc.id)!!.name)

            // Quota exceeded error message
            server.storageQuotaExceeded.set(true)
            try {
                fs.createFile(root, "blocked.bin")
                fail("Expected storageQuotaExceeded IOException")
            } catch (e: IOException) {
                assertTrue("Got: ${e.message}", e.message!!.contains("Google Drive storage is full"))
            }
            server.storageQuotaExceeded.set(false)

            // moveWithinSameBackend uses addParents & removeParents and updates cached listings in place
            val srcDir = fs.createDirectory(root, "Inbox")
            val dstDir = fs.createDirectory(root, "Archive")
            val itemPath = fs.createFile(srcDir, "receipt.pdf", PDF_MIME)
            // Prime both listings
            assertEquals(1, fs.list(srcDir, includeHidden = true).size)
            assertEquals(0, fs.list(dstDir, includeHidden = true).size)

            server.recordedRequests.clear()
            val movedPath = fs.moveWithinSameBackend(itemPath, dstDir)
            assertEquals(VfsPath.GDrive(bm.id, "/Archive/receipt.pdf"), movedPath)
            val moveReq = server.recordedRequests.single()
            assertTrue(moveReq.queryParams.containsKey("addParents"))
            assertTrue(moveReq.queryParams.containsKey("removeParents"))

            // Both listings updated in place -> 0 HTTP calls to list srcDir and dstDir!
            val reqBeforeLists = server.totalRequests.get()
            assertEquals(0, fs.list(srcDir, includeHidden = true).size)
            assertEquals(listOf("receipt.pdf"), fs.list(dstDir, includeHidden = true).map { it.name })
            assertEquals(reqBeforeLists, server.totalRequests.get())

            // Cross-server moveWithinSameBackend returns null (falls back to copy+delete in FileOperations)
            assertNull(fs.moveWithinSameBackend(movedPath!!, VfsPath.GDrive(bmOther.id, "/")))

            // setLastModified sends RFC 3339 and updates cached node
            val targetEpochMs = 1_750_123_456_000L
            assertTrue(fs.setLastModified(movedPath, targetEpochMs))
            assertEquals(targetEpochMs, fs.stat(movedPath)?.lastModified)

            // delete -> trashed: true; 403 -> DELETE fallback; double 403 -> false; root -> false
            assertFalse(fs.delete(root))
            val normalFile = server.putFile(name = "trash_me.txt")
            assertTrue(fs.delete(VfsPath.GDrive(bm.id, "/trash_me.txt")))
            assertTrue(server.getEntry(normalFile.id)!!.trashed)

            val sharedNotOwner = server.putFile(name = "delete_fallback.txt", forbidTrash = true, forbidDelete = false)
            assertTrue(fs.delete(VfsPath.GDrive(bm.id, "/delete_fallback.txt")))
            assertNull(server.getEntry(sharedNotOwner.id))

            val lockedFile = server.putFile(name = "locked.txt", forbidTrash = true, forbidDelete = true)
            assertFalse(fs.delete(VfsPath.GDrive(bm.id, "/locked.txt")))
            assertNotNull(server.getEntry(lockedFile.id))

            // 503 transient errors retry with exponential backoff and succeed
            server.fail503Times.set(2)
            val afterBackoff = fs.stat(movedPath)
            assertNotNull(afterBackoff)
        }
    }
}
