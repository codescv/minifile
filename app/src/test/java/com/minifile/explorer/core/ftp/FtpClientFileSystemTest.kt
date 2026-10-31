package com.minifile.explorer.core.ftp

import com.minifile.explorer.core.model.FtpServerBookmark
import com.minifile.explorer.core.model.InMemoryKeyValueStore
import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.data.DefaultFtpServersRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FtpClientFileSystemTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val engines = mutableListOf<FtpServerEngine>()

    @After
    fun tearDown() {
        for (e in engines) {
            runCatching { e.stop() }
        }
        engines.clear()
    }

    private fun startServer(
        rootDir: File,
        auth: FtpAuth = FtpAuth.Anonymous,
        readOnly: Boolean = false,
    ): FtpServerEngine {
        val localFs = LocalFileSystem()
        val cfg = FtpConfig(
            port = 0,
            bindAddress = "127.0.0.1",
            root = VfsPath.Local(rootDir.absolutePath),
            readOnly = readOnly,
            auth = auth,
            passivePorts = 0..0,
            idleTimeoutMs = 15_000L,
            maxClients = 8,
            serverName = "Test MiniFile FTP",
        )
        val srv = FtpServerEngine(
            config = cfg,
            fs = localFs,
            externalIpProvider = { listOf("127.0.0.1") },
        )
        srv.start()
        engines.add(srv)
        return srv
    }

    @Test
    fun endToEndCrudStreamingResumeRenameMoveAndDeleteOverLocalFtpServer() = runBlocking {
        val rootDir = tempFolder.newFolder("ftp_root")
        File(rootDir, "alpha.txt").writeText("hello world", Charsets.UTF_8)
        File(rootDir, ".hidden").writeText("secret", Charsets.UTF_8)
        val sub = File(rootDir, "docs").apply { mkdirs() }
        File(sub, "guide.md").writeText("# Guide\nLine 2", Charsets.UTF_8)

        val srv = startServer(
            rootDir = rootDir,
            auth = FtpAuth.Credentials("alice", "s3cr3t"),
        )

        val store = InMemoryKeyValueStore()
        val serversRepo = DefaultFtpServersRepository(store, clock = { 123_456L })
        val bookmark = serversRepo.add(
            name = "Local Test FTP",
            host = "127.0.0.1",
            port = srv.boundPort,
            anonymous = false,
            username = "alice",
            password = "s3cr3t",
            initialPath = "/",
        )

        // Verify persistence reload of FtpServersRepository
        val reloadedRepo = DefaultFtpServersRepository(store, clock = { 123_456L })
        assertEquals(1, reloadedRepo.servers.value.size)
        assertEquals(bookmark, reloadedRepo.findById(bookmark.id))

        val clientFs = FtpClientFileSystem(serverLookup = { id -> reloadedRepo.findById(id) })
        val rootPath = VfsPath.Ftp(bookmark.id, "/")

        // 1. VfsPath.Ftp serialize / parse roundtrip
        val deepPath = VfsPath.Ftp(bookmark.id, "/docs/guide.md")
        assertEquals("ftp://${bookmark.id}/docs/guide.md", deepPath.serialize())
        assertEquals(deepPath, VfsPath.parse(deepPath.serialize()))
        assertEquals(rootPath, VfsPath.parse("ftp://${bookmark.id}"))
        assertEquals(rootPath, VfsPath.parse("ftp://${bookmark.id}/"))

        // 2. stat root, existing dir, existing file, and nonexistent path
        val rootNode = clientFs.stat(rootPath)
        assertNotNull(rootNode)
        assertTrue(rootNode!!.isDirectory)

        val guideNode = clientFs.stat(deepPath)
        assertNotNull(guideNode)
        assertFalse(guideNode!!.isDirectory)
        assertEquals("# Guide\nLine 2".toByteArray(Charsets.UTF_8).size.toLong(), guideNode.size)

        assertNull(clientFs.stat(VfsPath.Ftp(bookmark.id, "/does_not_exist.bin")))
        assertTrue(clientFs.exists(deepPath))
        assertFalse(clientFs.exists(VfsPath.Ftp(bookmark.id, "/missing")))

        // 3. list with includeHidden = false vs true
        val visibleRoot = clientFs.list(rootPath, includeHidden = false)
        assertEquals(setOf("alpha.txt", "docs"), visibleRoot.map { it.name }.toSet())

        val allRoot = clientFs.list(rootPath, includeHidden = true)
        assertEquals(setOf(".hidden", "alpha.txt", "docs"), allRoot.map { it.name }.toSet())

        // 4. createDirectory & createFile
        val workDir = clientFs.createDirectory(rootPath, "workspace")
        assertEquals(VfsPath.Ftp(bookmark.id, "/workspace"), workDir)
        assertTrue(clientFs.stat(workDir)!!.isDirectory)

        val emptyFile = clientFs.createFile(workDir, "notes.txt", "text/plain")
        assertEquals(0L, clientFs.stat(emptyFile)!!.size)

        // 5. openOutput (write + append) & openInput (full read + offset REST)
        val payload1 = "0123456789ABCDEF".toByteArray(Charsets.UTF_8)
        clientFs.openOutput(emptyFile, append = false).use { out ->
            out.write(payload1)
        }
        val payload2 = "-TAIL".toByteArray(Charsets.UTF_8)
        clientFs.openOutput(emptyFile, append = true).use { out ->
            out.write(payload2)
        }

        val fullRead = clientFs.openInput(emptyFile, offset = 0L).use { it.readBytes() }
        assertArrayEquals("0123456789ABCDEF-TAIL".toByteArray(Charsets.UTF_8), fullRead)

        val offsetRead = clientFs.openInput(emptyFile, offset = 10L).use { it.readBytes() }
        assertArrayEquals("ABCDEF-TAIL".toByteArray(Charsets.UTF_8), offsetRead)

        // 6. rename & moveWithinSameBackend
        val renamedFile = clientFs.rename(emptyFile, "renamed_notes.txt")
        assertEquals(VfsPath.Ftp(bookmark.id, "/workspace/renamed_notes.txt"), renamedFile)
        assertFalse(clientFs.exists(emptyFile))
        assertTrue(clientFs.exists(renamedFile))

        val movedToDocs = clientFs.moveWithinSameBackend(
            src = renamedFile,
            dstDir = VfsPath.Ftp(bookmark.id, "/docs"),
        )
        assertEquals(VfsPath.Ftp(bookmark.id, "/docs/renamed_notes.txt"), movedToDocs)
        assertFalse(clientFs.exists(renamedFile))
        assertTrue(clientFs.exists(movedToDocs!!))

        // 7. recursive delete of non-empty directory + single file delete
        clientFs.delete(VfsPath.Ftp(bookmark.id, "/alpha.txt"))
        assertFalse(clientFs.exists(VfsPath.Ftp(bookmark.id, "/alpha.txt")))

        clientFs.delete(VfsPath.Ftp(bookmark.id, "/docs"))
        assertFalse(clientFs.exists(VfsPath.Ftp(bookmark.id, "/docs")))
        assertFalse(File(rootDir, "docs").exists())
    }

    @Test
    fun parsesUnixAndDosListLinesAndSupportsAnonymousLogin() = runBlocking {
        val rootDir = tempFolder.newFolder("anon_root")
        File(rootDir, "sample.bin").writeBytes(ByteArray(256) { it.toByte() })

        val srv = startServer(rootDir = rootDir, auth = FtpAuth.Anonymous)
        val bookmark = FtpServerBookmark(
            id = "anon-1",
            name = "Anon",
            host = "127.0.0.1",
            port = srv.boundPort,
            anonymous = true,
        )
        val clientFs = FtpClientFileSystem(serverLookup = { if (it == "anon-1") bookmark else null })
        val items = clientFs.list(bookmark.rootPath, includeHidden = false)
        assertEquals(1, items.size)
        assertEquals("sample.bin", items.single().name)
        assertEquals(256L, items.single().size)

        val parent = VfsPath.Ftp("anon-1", "/pub")
        val unixDir = FtpClientFileSystem.parseListLine(
            parent,
            "drwxr-xr-x   2 ftp      ftp          4096 Oct 31  2025 releases",
        )
        assertNotNull(unixDir)
        assertTrue(unixDir!!.isDirectory)
        assertEquals("releases", unixDir.name)
        assertEquals(VfsPath.Ftp("anon-1", "/pub/releases"), unixDir.path)

        val unixFileWithSpaces = FtpClientFileSystem.parseListLine(
            parent,
            "-rw-r--r--   1 ftp      ftp         12345 Oct 31 10:20 my report final.pdf",
        )
        assertNotNull(unixFileWithSpaces)
        assertFalse(unixFileWithSpaces!!.isDirectory)
        assertEquals("my report final.pdf", unixFileWithSpaces.name)
        assertEquals(12345L, unixFileWithSpaces.size)

        val dosDir = FtpClientFileSystem.parseListLine(
            parent,
            "10-31-25  09:15AM       <DIR>          Photos",
        )
        assertNotNull(dosDir)
        assertTrue(dosDir!!.isDirectory)
        assertEquals("Photos", dosDir.name)

        val dosFile = FtpClientFileSystem.parseListLine(
            parent,
            "10-31-2025  02:30PM                98765 archive.zip",
        )
        assertNotNull(dosFile)
        assertFalse(dosFile!!.isDirectory)
        assertEquals("archive.zip", dosFile.name)
        assertEquals(98765L, dosFile.size)
    }
}
