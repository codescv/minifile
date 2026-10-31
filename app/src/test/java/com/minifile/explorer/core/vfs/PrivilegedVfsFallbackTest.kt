package com.minifile.explorer.core.vfs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

class PrivilegedVfsFallbackTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun shellQuote_escapesSingleQuotesAndSpecialCharactersSafely() {
        assertEquals("'simple'", PrivilegedShellCodec.shellQuote("simple"))
        assertEquals("''", PrivilegedShellCodec.shellQuote(""))
        assertEquals("'it'\\''s a \"test\" \$HOME `id`'", PrivilegedShellCodec.shellQuote("it's a \"test\" \$HOME `id`"))
    }

    @Test
    fun parseDirectoryListing_handlesSpecialFilenamesNomediaHiddenAndSymlinks() {
        val raw = buildString {
            // 1. Directory with .nomedia and spaces/quotes in name
            append("1\u00001\u0000drwxrwx---|4096|1700000001\u0000-1\u0000\u0000Cache '2026'\u0000")
            // 2. Hidden file
            append("0\u00000\u0000-rw-rw----|128|1700000002\u0000-1\u0000\u0000.token\u0000")
            // 3. Symlink file with newline in name
            append("0\u00000\u0000lrwxrwxrwx|18|1700000003\u0000-1\u0000/data/target.json\u0000config\nlink.json\u0000")
        }.toByteArray(Charsets.UTF_8)

        val visibleOnly = PrivilegedShellCodec.parseDirectoryListing(
            parentNormalizedPath = "/storage/emulated/0/Android/data/com.example.app",
            rawBytes = raw,
            includeHidden = false,
        )
        assertEquals(2, visibleOnly.size)

        val dirNode = visibleOnly[0]
        assertEquals("Cache '2026'", dirNode.name)
        assertTrue(dirNode.isDirectory)
        assertTrue(dirNode.hasNomedia)
        assertEquals(0L, dirNode.size)
        assertEquals(1700000001_000L, dirNode.lastModified)
        assertEquals(
            VfsPath.Local("/storage/emulated/0/Android/data/com.example.app/Cache '2026'"),
            dirNode.path,
        )

        val linkNode = visibleOnly[1]
        assertEquals("config\nlink.json", linkNode.name)
        assertFalse(linkNode.isDirectory)
        assertTrue(linkNode.isSymlink)
        assertEquals("/data/target.json", linkNode.symlinkTarget)
        assertEquals(18L, linkNode.size)

        val withHidden = PrivilegedShellCodec.parseDirectoryListing(
            parentNormalizedPath = "/storage/emulated/0/Android/data/com.example.app",
            rawBytes = raw,
            includeHidden = true,
        )
        assertEquals(3, withHidden.size)
        assertTrue(withHidden.any { it.name == ".token" && it.isHidden })
    }

    @Test
    fun parseSingleStat_parsesFileAndDirectoryMetadata() {
        val rawFile = "0\u00000\u0000-rw-rw----|2048|1700000100\u0000\u0000".toByteArray(Charsets.UTF_8)
        val fileNode = PrivilegedShellCodec.parseSingleStat(
            "/storage/emulated/0/Android/obb/com.game/main.1.com.game.obb",
            rawFile,
        )
        assertNotNull(fileNode)
        assertEquals("main.1.com.game.obb", fileNode!!.name)
        assertFalse(fileNode.isDirectory)
        assertEquals(2048L, fileNode.size)
        assertEquals(1700000100_000L, fileNode.lastModified)

        assertNull(PrivilegedShellCodec.parseSingleStat("/storage/emulated/0/Android/data", ByteArray(0)))
    }

    @Test
    fun pathUtils_identifiesRestrictedSubtreesAndOwnPackageExclusions() {
        assertTrue(PathUtils.isRestrictedAndroidSubtree("/storage/emulated/0/Android/data"))
        assertTrue(PathUtils.isRestrictedAndroidSubtree("/storage/emulated/0/Android/data/com.foo/files"))
        assertTrue(PathUtils.isRestrictedAndroidSubtree("/storage/emulated/0/Android/obb/com.foo"))
        assertFalse(PathUtils.isRestrictedAndroidSubtree("/storage/emulated/0/Android/media/com.foo"))
        assertFalse(PathUtils.isRestrictedAndroidSubtree("/storage/emulated/0/Download"))

        assertTrue(
            PathUtils.isOwnPackageSubtree(
                "/storage/emulated/0/Android/data/com.minifile.explorer/files",
                "com.minifile.explorer",
            ),
        )
        assertFalse(
            PathUtils.isOwnPackageSubtree(
                "/storage/emulated/0/Android/data/com.other.app/files",
                "com.minifile.explorer",
            ),
        )
    }

    @Test
    fun localFileSystem_delegatesOtherAppAndroidDataToPrivilegedFallbackAndKeepsOwnPackageLocal() = runBlocking {
        val fakeStorageRoot = tempFolder.newFolder("emulated0")
        val ownPkgDir = File(fakeStorageRoot, "Android/data/com.minifile.explorer/files").apply { mkdirs() }
        val ownLocalFile = File(ownPkgDir, "own.txt").apply { writeText("local-own-data") }

        val memoryPrivilegedFiles = LinkedHashMap<String, ByteArray>()
        val memoryPrivilegedDirs = LinkedHashSet<String>()
        val otherAppBase = "${PathUtils.normalizeLocalPath(fakeStorageRoot.absolutePath)}/Android/data/com.other.app"
        memoryPrivilegedDirs.add(PathUtils.normalizeLocalPath("${fakeStorageRoot.absolutePath}/Android/data"))
        memoryPrivilegedDirs.add(otherAppBase)

        val fallback = object : PrivilegedLocalFallback {
            override val ownPackageName: String = "com.minifile.explorer"
            override fun isAvailable(): Boolean = true

            override fun stat(path: String): VfsNode? {
                val norm = PathUtils.normalizeLocalPath(path)
                return when {
                    norm in memoryPrivilegedDirs -> VfsNode(
                        path = VfsPath.Local(norm),
                        name = norm.substringAfterLast('/'),
                        isDirectory = true,
                        size = 0L,
                        lastModified = 1_000L,
                    )
                    norm in memoryPrivilegedFiles -> VfsNode(
                        path = VfsPath.Local(norm),
                        name = norm.substringAfterLast('/'),
                        isDirectory = false,
                        size = memoryPrivilegedFiles.getValue(norm).size.toLong(),
                        lastModified = 2_000L,
                    )
                    else -> null
                }
            }

            override fun exists(path: String): Boolean {
                val norm = PathUtils.normalizeLocalPath(path)
                return norm in memoryPrivilegedDirs || norm in memoryPrivilegedFiles
            }

            override fun listDirectory(path: String, includeHidden: Boolean): List<VfsNode>? {
                val norm = PathUtils.normalizeLocalPath(path)
                if (norm !in memoryPrivilegedDirs) return null
                val prefix = "$norm/"
                val out = mutableListOf<VfsNode>()
                for (d in memoryPrivilegedDirs) {
                    if (d.startsWith(prefix) && '/' !in d.removePrefix(prefix)) {
                        val name = d.removePrefix(prefix)
                        if (includeHidden || !name.startsWith('.')) {
                            out.add(stat(d)!!)
                        }
                    }
                }
                for ((f, _) in memoryPrivilegedFiles) {
                    if (f.startsWith(prefix) && '/' !in f.removePrefix(prefix)) {
                        val name = f.removePrefix(prefix)
                        if (includeHidden || !name.startsWith('.')) {
                            out.add(stat(f)!!)
                        }
                    }
                }
                return out
            }

            override fun mkdir(path: String): Boolean {
                memoryPrivilegedDirs.add(PathUtils.normalizeLocalPath(path))
                return true
            }

            override fun createFile(path: String): Boolean {
                val norm = PathUtils.normalizeLocalPath(path)
                memoryPrivilegedFiles.putIfAbsent(norm, ByteArray(0))
                return true
            }

            override fun delete(path: String): Boolean {
                val norm = PathUtils.normalizeLocalPath(path)
                return memoryPrivilegedFiles.remove(norm) != null || memoryPrivilegedDirs.remove(norm)
            }

            override fun rename(srcPath: String, dstPath: String): Boolean {
                val s = PathUtils.normalizeLocalPath(srcPath)
                val d = PathUtils.normalizeLocalPath(dstPath)
                val bytes = memoryPrivilegedFiles.remove(s) ?: return false
                memoryPrivilegedFiles[d] = bytes
                return true
            }

            override fun setLastModified(path: String, epochMs: Long): Boolean = exists(path)

            override fun openInput(path: String, offset: Long): InputStream {
                val norm = PathUtils.normalizeLocalPath(path)
                val data = memoryPrivilegedFiles[norm] ?: throw FileNotFoundException(norm)
                val slice = if (offset >= data.size) ByteArray(0) else data.copyOfRange(offset.toInt(), data.size)
                return ByteArrayInputStream(slice)
            }

            override fun openOutput(path: String, append: Boolean): OutputStream {
                val norm = PathUtils.normalizeLocalPath(path)
                val buf = ByteArrayOutputStream()
                if (append) {
                    memoryPrivilegedFiles[norm]?.let { buf.write(it) }
                }
                return object : OutputStream() {
                    override fun write(b: Int) = buf.write(b)
                    override fun write(b: ByteArray, off: Int, len: Int) = buf.write(b, off, len)
                    override fun close() {
                        memoryPrivilegedFiles[norm] = buf.toByteArray()
                    }
                }
            }
        }

        val fs = LocalFileSystem(fallback)

        // 1. Own package path bypasses privileged shell and uses direct java.io.File
        val ownVfsFile = VfsPath.Local(ownLocalFile.absolutePath)
        assertEquals("local-own-data", fs.openInput(ownVfsFile).use { String(it.readBytes()) })
        assertFalse(ownLocalFile.absolutePath in memoryPrivilegedFiles)

        // 2. Other app's Android/data path uses PrivilegedLocalFallback transparently
        val otherRoot = VfsPath.Local(otherAppBase)
        val cacheDir = fs.createDirectory(otherRoot, "cache")
        assertTrue(fs.exists(cacheDir))

        val cfg = fs.createFile(otherRoot, "prefs.json")
        fs.openOutput(cfg).use { it.write("""{"ok":true}""".toByteArray(Charsets.UTF_8)) }

        val listed = fs.list(otherRoot, includeHidden = true)
        assertEquals(setOf("cache", "prefs.json"), listed.map { it.name }.toSet())

        val readBack = fs.openInput(cfg, offset = 6L).use { it.readBytes() }
        assertArrayEquals("true}".toByteArray(Charsets.UTF_8), readBack)

        val renamed = fs.rename(cfg, "prefs.bak")
        assertFalse(fs.exists(cfg))
        assertTrue(fs.exists(renamed))

        assertTrue(fs.delete(renamed))
        assertFalse(fs.exists(renamed))
    }
}
