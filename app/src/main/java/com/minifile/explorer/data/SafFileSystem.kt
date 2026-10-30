package com.minifile.explorer.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.NameValidation
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

class SafFileSystem(
    context: Context,
) : FileSystem {

    companion object {
        internal const val MISSING_PREFIX = "__missing__:"

        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }

    private val cr: ContentResolver = context.applicationContext.contentResolver
    // Cache known child -> parent relationships inside a tree URI so parent() works even on opaque DocumentProviders
    private val parentCache = ConcurrentHashMap<String, VfsPath.Saf>()

    override fun supports(path: VfsPath): Boolean = path is VfsPath.Saf

    private fun requireSaf(path: VfsPath): VfsPath.Saf =
        path as? VfsPath.Saf ?: throw IllegalArgumentException("Expected VfsPath.Saf, got $path")

    private fun isMissingSentinel(saf: VfsPath.Saf): Boolean =
        saf.documentId.startsWith(MISSING_PREFIX)

    private fun requireValidName(raw: String): String {
        val trimmed = raw.trim()
        when (val v = PathUtils.validateName(trimmed, fatSafe = false)) {
            NameValidation.Valid -> return trimmed
            is NameValidation.Invalid -> throw IllegalArgumentException(v.reason)
        }
    }

    fun buildDocumentUri(saf: VfsPath.Saf): Uri {
        if (isMissingSentinel(saf)) {
            throw FileNotFoundException("SAF document does not exist: ${saf.documentId}")
        }
        return DocumentsContract.buildDocumentUriUsingTree(Uri.parse(saf.treeUri), saf.documentId)
    }

    override suspend fun stat(path: VfsPath): VfsNode? = withContext(Dispatchers.IO) {
        val saf = requireSaf(path)
        if (isMissingSentinel(saf)) return@withContext null
        val treeUri = Uri.parse(saf.treeUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, saf.documentId)
        try {
            cr.query(docUri, PROJECTION, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@withContext null
                val docId = c.getString(0) ?: saf.documentId
                val rawName = c.getString(1) ?: saf.displayName
                val mime = c.getString(2)
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val modified = if (c.isNull(4)) 0L else c.getLong(4)
                val flags = if (c.isNull(5)) 0 else c.getInt(5)
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val canWrite = if (isDir) {
                    (flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0
                } else {
                    (flags and (
                        DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                            DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                            DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                        )) != 0
                }
                val hasNomedia = if (isDir) queryHasNomedia(treeUri, docId) else false
                VfsNode(
                    path = VfsPath.Saf(saf.treeUri, docId),
                    name = rawName,
                    isDirectory = isDir,
                    size = if (isDir) 0L else size,
                    lastModified = modified,
                    mimeType = if (isDir) "inode/directory" else mime,
                    isHidden = PathUtils.isHiddenName(rawName),
                    canRead = true,
                    canWrite = canWrite,
                    hasNomedia = hasNomedia,
                )
            }
        } catch (_: SecurityException) {
            throw IOException("SAF permission revoked for ${saf.displayName}")
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun list(dir: VfsPath, includeHidden: Boolean): List<VfsNode> =
        withContext(Dispatchers.IO) {
            val saf = requireSaf(dir)
            if (isMissingSentinel(saf)) throw FileNotFoundException("SAF directory not found")
            val treeUri = Uri.parse(saf.treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, saf.documentId)
            val rawRows = ArrayList<RawRow>()

            try {
                cr.query(childrenUri, PROJECTION, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2)
                        val size = if (c.isNull(3)) 0L else c.getLong(3)
                        val modified = if (c.isNull(4)) 0L else c.getLong(4)
                        val flags = if (c.isNull(5)) 0 else c.getInt(5)
                        rawRows.add(RawRow(docId, name, mime, size, modified, flags))
                    }
                } ?: throw IOException("ContentProvider returned null cursor for ${saf.displayName}")
            } catch (e: SecurityException) {
                throw IOException("SAF permission revoked: ${e.message}", e)
            }

            val result = ArrayList<VfsNode>(rawRows.size)
            for (row in rawRows) {
                val childPath = VfsPath.Saf(saf.treeUri, row.docId)
                parentCache[childPath.serialize()] = saf
                val hidden = PathUtils.isHiddenName(row.name)
                if (!includeHidden && hidden) continue
                val isDir = row.mime == DocumentsContract.Document.MIME_TYPE_DIR
                val canWrite = if (isDir) {
                    (row.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0
                } else {
                    (row.flags and (
                        DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                            DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                            DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                        )) != 0
                }
                val hasNomedia = if (isDir) queryHasNomedia(treeUri, row.docId) else false
                result.add(
                    VfsNode(
                        path = childPath,
                        name = row.name,
                        isDirectory = isDir,
                        size = if (isDir) 0L else row.size,
                        lastModified = row.modified,
                        mimeType = if (isDir) "inode/directory" else row.mime,
                        isHidden = hidden,
                        canRead = true,
                        canWrite = canWrite,
                        hasNomedia = hasNomedia,
                    ),
                )
            }
            result
        }

    private data class RawRow(
        val docId: String,
        val name: String,
        val mime: String?,
        val size: Long,
        val modified: Long,
        val flags: Int,
    )

    private fun queryHasNomedia(treeUri: Uri, dirDocId: String): Boolean {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
        val proj = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return runCatching {
            cr.query(childrenUri, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(0) == ".nomedia") return@use true
                }
                false
            } ?: false
        }.getOrDefault(false)
    }

    override suspend fun child(dir: VfsPath, name: String): VfsPath = withContext(Dispatchers.IO) {
        val saf = requireSaf(dir)
        val validName = requireValidName(name)
        if (isMissingSentinel(saf)) {
            val childMissing = VfsPath.Saf(saf.treeUri, "${saf.documentId}/$validName")
            parentCache[childMissing.serialize()] = saf
            return@withContext childMissing
        }
        val treeUri = Uri.parse(saf.treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, saf.documentId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        val foundDocId = runCatching {
            cr.query(childrenUri, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == validName) {
                        return@use c.getString(0)
                    }
                }
                null
            }
        }.getOrNull()

        val result = if (foundDocId != null) {
            VfsPath.Saf(saf.treeUri, foundDocId)
        } else {
            VfsPath.Saf(saf.treeUri, "$MISSING_PREFIX${saf.documentId}/$validName")
        }
        parentCache[result.serialize()] = saf
        result
    }

    override suspend fun parent(path: VfsPath): VfsPath? = withContext(Dispatchers.IO) {
        val saf = requireSaf(path)
        val treeUri = Uri.parse(saf.treeUri)
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        if (saf.documentId == rootDocId) return@withContext null

        parentCache[saf.serialize()]?.let { return@withContext it }

        if (isMissingSentinel(saf)) {
            val raw = saf.documentId.removePrefix(MISSING_PREFIX)
            val slash = raw.lastIndexOf('/')
            if (slash <= 0) return@withContext rootDocId?.let { VfsPath.Saf(saf.treeUri, it) }
            val parentDoc = raw.substring(0, slash)
            return@withContext VfsPath.Saf(saf.treeUri, parentDoc)
        }

        // ExternalStorageProvider convention: "<vol>:<rel/path>"
        if (rootDocId != null && saf.documentId.startsWith("$rootDocId/")) {
            val parentDocId = saf.documentId.substringBeforeLast('/')
            return@withContext VfsPath.Saf(saf.treeUri, parentDocId)
        }
        val lastSlash = saf.documentId.lastIndexOf('/')
        val colon = saf.documentId.indexOf(':')
        if (lastSlash > colon && colon >= 0) {
            val candidateParent = saf.documentId.substring(0, lastSlash)
            if (rootDocId == null || candidateParent == rootDocId || candidateParent.startsWith("$rootDocId/")) {
                return@withContext VfsPath.Saf(saf.treeUri, candidateParent)
            }
            if (candidateParent == saf.documentId.substring(0, colon + 1).trimEnd('/')) {
                return@withContext rootDocId.let { VfsPath.Saf(saf.treeUri, it) }
            }
        }
        rootDocId?.let { VfsPath.Saf(saf.treeUri, it) }
    }

    override suspend fun exists(path: VfsPath): Boolean = stat(path) != null

    override suspend fun createDirectory(dir: VfsPath, name: String): VfsPath =
        withContext(Dispatchers.IO) {
            val saf = requireSaf(dir)
            val validName = requireValidName(name)
            if (isMissingSentinel(saf)) throw FileNotFoundException("Parent SAF directory does not exist")
            val parentUri = buildDocumentUri(saf)
            val created = DocumentsContract.createDocument(
                cr,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                validName,
            ) ?: throw IOException("Failed to create folder '$validName'")
            val createdPath = VfsPath.Saf(saf.treeUri, DocumentsContract.getDocumentId(created))
            parentCache[createdPath.serialize()] = saf
            createdPath
        }

    override suspend fun createFile(dir: VfsPath, name: String, mime: String): VfsPath =
        withContext(Dispatchers.IO) {
            val saf = requireSaf(dir)
            val validName = requireValidName(name)
            if (isMissingSentinel(saf)) throw FileNotFoundException("Parent SAF directory does not exist")
            val existing = child(saf, validName) as VfsPath.Saf
            if (!isMissingSentinel(existing) && stat(existing)?.isDirectory == false) {
                return@withContext existing
            }
            val effectiveMime = if (mime == "application/octet-stream") {
                PathUtils.guessMimeType(validName)
            } else {
                mime
            }
            val parentUri = buildDocumentUri(saf)
            val created = DocumentsContract.createDocument(
                cr,
                parentUri,
                effectiveMime,
                validName,
            ) ?: throw IOException("Failed to create file '$validName'")
            val createdPath = VfsPath.Saf(saf.treeUri, DocumentsContract.getDocumentId(created))
            parentCache[createdPath.serialize()] = saf
            createdPath
        }

    override suspend fun delete(path: VfsPath): Boolean = withContext(Dispatchers.IO) {
        val saf = requireSaf(path)
        if (isMissingSentinel(saf)) return@withContext false
        val docUri = buildDocumentUri(saf)
        runCatching { DocumentsContract.deleteDocument(cr, docUri) }.getOrDefault(false)
    }

    override suspend fun rename(path: VfsPath, newName: String): VfsPath =
        withContext(Dispatchers.IO) {
            val saf = requireSaf(path)
            val validName = requireValidName(newName)
            val docUri = buildDocumentUri(saf)
            val oldParent = parent(saf)
            val renamed = DocumentsContract.renameDocument(cr, docUri, validName)
                ?: throw IOException("Failed to rename '${saf.displayName}' to '$validName'")
            val result = VfsPath.Saf(saf.treeUri, DocumentsContract.getDocumentId(renamed))
            if (oldParent is VfsPath.Saf) {
                parentCache[result.serialize()] = oldParent
            }
            result
        }

    override suspend fun moveWithinSameBackend(src: VfsPath, dstDir: VfsPath): VfsPath? =
        withContext(Dispatchers.IO) {
            val srcSaf = src as? VfsPath.Saf ?: return@withContext null
            val dstSaf = dstDir as? VfsPath.Saf ?: return@withContext null
            if (srcSaf.treeUri != dstSaf.treeUri) return@withContext null
            if (isMissingSentinel(srcSaf) || isMissingSentinel(dstSaf)) return@withContext null
            val srcParent = parent(srcSaf) as? VfsPath.Saf ?: return@withContext null
            runCatching {
                val movedUri = DocumentsContract.moveDocument(
                    cr,
                    buildDocumentUri(srcSaf),
                    buildDocumentUri(srcParent),
                    buildDocumentUri(dstSaf),
                ) ?: return@runCatching null
                val result = VfsPath.Saf(dstSaf.treeUri, DocumentsContract.getDocumentId(movedUri))
                parentCache[result.serialize()] = dstSaf
                result
            }.getOrNull()
        }

    override fun openInput(path: VfsPath, offset: Long): InputStream {
        val saf = requireSaf(path)
        val docUri = buildDocumentUri(saf)
        val stream = cr.openInputStream(docUri)
            ?: throw FileNotFoundException("Cannot open SAF input stream: ${saf.displayName}")
        if (offset > 0L) {
            try {
                skipFully(stream, offset)
            } catch (e: Exception) {
                runCatching { stream.close() }
                throw e
            }
        }
        return stream
    }

    override fun openOutput(path: VfsPath, append: Boolean): OutputStream {
        val saf = requireSaf(path)
        val docUri = buildDocumentUri(saf)
        val out = if (append) {
            cr.openOutputStream(docUri, "wa")
        } else {
            runCatching { cr.openOutputStream(docUri, "wt") }.getOrNull()
                ?: cr.openOutputStream(docUri, "w")
        }
        return out ?: throw FileNotFoundException("Cannot open SAF output stream: ${saf.displayName}")
    }

    override suspend fun setLastModified(path: VfsPath, epochMs: Long): Boolean = false

    override suspend fun space(path: VfsPath): Pair<Long, Long>? = null

    private fun skipFully(stream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val scratch = ByteArray(16 * 1024)
        while (remaining > 0L) {
            val skipped = stream.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val toRead = minOf(remaining, scratch.size.toLong()).toInt()
                val n = stream.read(scratch, 0, toRead)
                if (n < 0) break
                remaining -= n
            }
        }
    }
}
