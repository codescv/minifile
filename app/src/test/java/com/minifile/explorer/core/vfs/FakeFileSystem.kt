package com.minifile.explorer.core.vfs

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * In-memory FileSystem supporting [VfsPath.Saf] for fast, deterministic cross-backend JVM tests.
 */
class FakeFileSystem(
    val treeUri: String = "content://fake.saf/tree/root",
    var readOnly: Boolean = false,
    var available: Boolean = true,
) : FileSystem {

    private class Entry(
        var name: String,
        val isDirectory: Boolean,
        var data: ByteArray = ByteArray(0),
        var lastModified: Long = 1_700_000_000_000L,
        var mimeType: String? = null,
        var canWrite: Boolean = true,
    )

    private val entries = LinkedHashMap<String, Entry>()

    init {
        entries["/"] = Entry(name = "/", isDirectory = true)
    }

    fun rootPath(): VfsPath.Saf = VfsPath.Saf(treeUri, "/")

    private fun normDocId(docId: String): String = PathUtils.normalizeLocalPath(docId)

    private fun checkAvailable() {
        if (!available) throw IOException("FakeFileSystem volume unmounted")
    }

    override fun supports(path: VfsPath): Boolean =
        path is VfsPath.Saf && path.treeUri == treeUri

    override suspend fun stat(path: VfsPath): VfsNode? {
        if (!available) return null
        val saf = path as? VfsPath.Saf ?: return null
        if (saf.treeUri != treeUri) return null
        val key = normDocId(saf.documentId)
        val entry = entries[key] ?: return null
        return toNode(key, entry)
    }

    override suspend fun list(dir: VfsPath, includeHidden: Boolean): List<VfsNode> {
        checkAvailable()
        val saf = dir as? VfsPath.Saf ?: throw IllegalArgumentException("Expected SAF path")
        val parentKey = normDocId(saf.documentId)
        val parentEntry = entries[parentKey] ?: throw FileNotFoundException("Missing dir: $parentKey")
        if (!parentEntry.isDirectory) throw IOException("Not a directory: $parentKey")

        val result = mutableListOf<VfsNode>()
        for ((k, entry) in entries) {
            if (k == "/") continue
            val p = parentKeyOf(k)
            if (p == parentKey) {
                if (!includeHidden && PathUtils.isHiddenName(entry.name)) continue
                result.add(toNode(k, entry))
            }
        }
        return result
    }

    override suspend fun child(dir: VfsPath, name: String): VfsPath {
        val validation = PathUtils.validateName(name)
        if (validation is NameValidation.Invalid) {
            throw IllegalArgumentException(validation.reason)
        }
        val saf = dir as? VfsPath.Saf ?: throw IllegalArgumentException("Expected SAF path")
        val base = normDocId(saf.documentId)
        val joined = if (base == "/") "/$name" else "$base/$name"
        return VfsPath.Saf(treeUri, joined)
    }

    override suspend fun parent(path: VfsPath): VfsPath? {
        val saf = path as? VfsPath.Saf ?: throw IllegalArgumentException("Expected SAF path")
        val key = normDocId(saf.documentId)
        if (key == "/") return null
        return VfsPath.Saf(treeUri, parentKeyOf(key))
    }

    override suspend fun exists(path: VfsPath): Boolean = stat(path) != null

    override suspend fun createDirectory(dir: VfsPath, name: String): VfsPath {
        checkAvailable()
        if (readOnly) throw IOException("Read-only filesystem")
        val target = child(dir, name) as VfsPath.Saf
        val key = normDocId(target.documentId)
        val parentKey = parentKeyOf(key)
        val parentEntry = entries[parentKey] ?: throw FileNotFoundException("Missing parent: $parentKey")
        if (!parentEntry.isDirectory) throw IOException("Parent is not a directory")
        val existing = entries[key]
        if (existing != null) {
            if (existing.isDirectory) return target
            throw IOException("File already exists: $key")
        }
        entries[key] = Entry(name = name, isDirectory = true)
        return target
    }

    override suspend fun createFile(dir: VfsPath, name: String, mime: String): VfsPath {
        checkAvailable()
        if (readOnly) throw IOException("Read-only filesystem")
        val target = child(dir, name) as VfsPath.Saf
        val key = normDocId(target.documentId)
        val parentKey = parentKeyOf(key)
        val parentEntry = entries[parentKey] ?: throw FileNotFoundException("Missing parent: $parentKey")
        if (!parentEntry.isDirectory) throw IOException("Parent is not a directory")
        val existing = entries[key]
        if (existing != null && existing.isDirectory) {
            throw IOException("Directory exists with name: $name")
        }
        if (existing == null) {
            entries[key] = Entry(name = name, isDirectory = false, mimeType = mime)
        }
        return target
    }

    override suspend fun delete(path: VfsPath): Boolean {
        checkAvailable()
        if (readOnly) throw IOException("Read-only filesystem")
        val saf = path as? VfsPath.Saf ?: return false
        val key = normDocId(saf.documentId)
        if (key == "/") return false
        val entry = entries[key] ?: return false
        if (entry.isDirectory && entries.keys.any { it != key && parentKeyOf(it) == key }) {
            return false // non-empty directory
        }
        entries.remove(key)
        return true
    }

    override suspend fun rename(path: VfsPath, newName: String): VfsPath {
        checkAvailable()
        if (readOnly) throw IOException("Read-only filesystem")
        val validation = PathUtils.validateName(newName)
        if (validation is NameValidation.Invalid) throw IllegalArgumentException(validation.reason)
        val saf = path as? VfsPath.Saf ?: throw IllegalArgumentException("Expected SAF path")
        val oldKey = normDocId(saf.documentId)
        val entry = entries[oldKey] ?: throw FileNotFoundException("Missing: $oldKey")
        val pKey = parentKeyOf(oldKey)
        val newKey = if (pKey == "/") "/$newName" else "$pKey/$newName"
        if (newKey != oldKey && entries.containsKey(newKey)) {
            throw IOException("Target exists: $newKey")
        }
        val toMove = entries.keys.filter { it == oldKey || it.startsWith("$oldKey/") }
        for (k in toMove) {
            val e = entries.remove(k)!!
            val mapped = newKey + k.removePrefix(oldKey)
            if (k == oldKey) e.name = newName
            entries[mapped] = e
        }
        return VfsPath.Saf(treeUri, newKey)
    }

    override suspend fun moveWithinSameBackend(src: VfsPath, dstDir: VfsPath): VfsPath? {
        if (!supports(src) || !supports(dstDir) || readOnly || !available) return null
        val srcSaf = src as VfsPath.Saf
        val dstSaf = dstDir as VfsPath.Saf
        val oldKey = normDocId(srcSaf.documentId)
        val dstKey = normDocId(dstSaf.documentId)
        val srcEntry = entries[oldKey] ?: return null
        val newKey = if (dstKey == "/") "/${srcEntry.name}" else "$dstKey/${srcEntry.name}"
        if (entries.containsKey(newKey)) return null
        val toMove = entries.keys.filter { it == oldKey || it.startsWith("$oldKey/") }
        for (k in toMove) {
            val e = entries.remove(k)!!
            val mapped = newKey + k.removePrefix(oldKey)
            entries[mapped] = e
        }
        return VfsPath.Saf(treeUri, newKey)
    }

    override fun openInput(path: VfsPath, offset: Long): InputStream {
        checkAvailable()
        val saf = path as? VfsPath.Saf ?: throw IllegalArgumentException("Expected SAF path")
        val key = normDocId(saf.documentId)
        val entry = entries[key] ?: throw FileNotFoundException("Missing file: $key")
        if (entry.isDirectory) throw FileNotFoundException("Is a directory: $key")
        val start = offset.toInt().coerceAtMost(entry.data.size)
        return ByteArrayInputStream(entry.data, start, entry.data.size - start)
    }

    override fun openOutput(path: VfsPath, append: Boolean): OutputStream {
        checkAvailable()
        if (readOnly) throw IOException("Read-only filesystem")
        val saf = path as? VfsPath.Saf ?: throw IllegalArgumentException("Expected SAF path")
        val key = normDocId(saf.documentId)
        val parentKey = parentKeyOf(key)
        val parentEntry = entries[parentKey] ?: throw FileNotFoundException("Missing parent: $parentKey")
        if (!parentEntry.isDirectory) throw IOException("Parent not a directory: $parentKey")
        val existing = entries[key]
        if (existing != null && existing.isDirectory) throw IOException("Cannot overwrite directory: $key")
        val entry = existing ?: Entry(
            name = key.substringAfterLast('/'),
            isDirectory = false,
        ).also { entries[key] = it }

        val seed = if (append) entry.data else ByteArray(0)
        return object : ByteArrayOutputStream() {
            init {
                if (seed.isNotEmpty()) write(seed)
            }

            override fun close() {
                super.close()
                entry.data = toByteArray()
                entry.lastModified = System.currentTimeMillis()
            }
        }
    }

    override suspend fun setLastModified(path: VfsPath, epochMs: Long): Boolean {
        val saf = path as? VfsPath.Saf ?: return false
        val entry = entries[normDocId(saf.documentId)] ?: return false
        entry.lastModified = epochMs
        return true
    }

    override suspend fun space(path: VfsPath): Pair<Long, Long>? = 64_000_000_000L to 32_000_000_000L

    private fun parentKeyOf(key: String): String {
        val idx = key.lastIndexOf('/')
        return if (idx <= 0) "/" else key.substring(0, idx)
    }

    private fun toNode(key: String, entry: Entry): VfsNode {
        val nomediaKey = if (key == "/") "/.nomedia" else "$key/.nomedia"
        val hasNomedia = entry.isDirectory && entries.containsKey(nomediaKey)
        return VfsNode(
            path = VfsPath.Saf(treeUri, key),
            name = entry.name,
            isDirectory = entry.isDirectory,
            size = if (entry.isDirectory) -1L else entry.data.size.toLong(),
            lastModified = entry.lastModified,
            mimeType = entry.mimeType ?: if (entry.isDirectory) "inode/directory" else PathUtils.guessMimeType(entry.name),
            isHidden = PathUtils.isHiddenName(entry.name),
            canRead = true,
            canWrite = !readOnly && entry.canWrite,
            hasNomedia = hasNomedia,
        )
    }
}
