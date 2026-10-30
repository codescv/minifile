package com.minifile.explorer.core.ops

import com.minifile.explorer.core.model.OperationKind
import com.minifile.explorer.core.model.OperationProgress
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.NameValidation
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ZipEntryInfo(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val compressedSize: Long,
    val lastModified: Long,
    val isEncrypted: Boolean = false,
)

data class ExtractResult(
    val extractedFiles: Int,
    val extractedDirs: Int,
    val skippedConflicts: Int,
    val unsafeEntriesSkipped: Int,
    val cancelled: Boolean,
)

class ZipBombException(message: String) : IOException(message)
class EncryptedZipException(message: String) : IOException(message)

class ZipArchiver(
    private val fs: FileSystem,
    private val maxEntries: Int = 200_000,
    private val maxExpansionRatio: Long = 20L,
    private val bombBytesThreshold: Long = 2L * 1024 * 1024 * 1024,
) {
    private val bufferSize = 64 * 1024

    suspend fun compress(
        items: List<VfsPath>,
        dstDir: VfsPath,
        archiveName: String,
        level: Int = Deflater.DEFAULT_COMPRESSION,
        sink: ProgressSink = ProgressSink.NOOP,
    ): VfsPath {
        require(items.isNotEmpty()) { "Nothing selected to compress" }
        val normalizedName = if (archiveName.endsWith(".zip", ignoreCase = true)) {
            archiveName
        } else {
            "$archiveName.zip"
        }
        val validation = PathUtils.validateName(normalizedName)
        if (validation is NameValidation.Invalid) {
            throw IllegalArgumentException(validation.reason)
        }

        val existingNames = fs.list(dstDir, includeHidden = true).mapTo(HashSet()) { it.name }
        val uniqueArchiveName = PathUtils.uniqueName(normalizedName, existingNames, isDirectory = false)
        val archivePath = fs.createFile(dstDir, uniqueArchiveName, "application/zip")

        var completed = false
        var bytesWritten = 0L
        val addedEntries = HashSet<String>()

        try {
            ZipOutputStream(BufferedOutputStream(fs.openOutput(archivePath, append = false)), Charsets.UTF_8).use { zos ->
                if (level == Deflater.NO_COMPRESSION) {
                    zos.setLevel(Deflater.NO_COMPRESSION)
                } else {
                    zos.setLevel(level.coerceIn(Deflater.NO_COMPRESSION, Deflater.BEST_COMPRESSION))
                }

                suspend fun writeNode(node: VfsNode, relPath: String) {
                    if (sink.isCancelled()) return
                    if (node.path == archivePath) return

                    sink.onProgress(
                        OperationProgress(
                            id = "zip-compress",
                            kind = OperationKind.COMPRESS,
                            title = "Creating $uniqueArchiveName",
                            currentItem = relPath,
                            processedBytes = bytesWritten,
                        ),
                    )

                    if (node.isDirectory) {
                        val dirEntryName = "${relPath.trimEnd('/')}/"
                        if (addedEntries.add(dirEntryName)) {
                            val ze = ZipEntry(dirEntryName)
                            if (node.lastModified > 0L) ze.time = node.lastModified
                            zos.putNextEntry(ze)
                            zos.closeEntry()
                        }
                        if (!node.isSymlink) {
                            val children = fs.list(node.path, includeHidden = true)
                            for (child in children) {
                                if (sink.isCancelled()) return
                                writeNode(child, "${relPath.trimEnd('/')}/${child.name}")
                            }
                        }
                    } else {
                        if (!addedEntries.add(relPath)) return
                        val ze = ZipEntry(relPath)
                        if (node.lastModified > 0L) ze.time = node.lastModified
                        zos.putNextEntry(ze)
                        fs.openInput(node.path).use { input ->
                            val buf = ByteArray(bufferSize)
                            while (true) {
                                if (sink.isCancelled()) break
                                val n = input.read(buf)
                                if (n < 0) break
                                if (n > 0) {
                                    zos.write(buf, 0, n)
                                    bytesWritten += n
                                }
                            }
                        }
                        zos.closeEntry()
                    }
                }

                val usedTopNames = HashSet<String>()
                for (item in items) {
                    if (sink.isCancelled()) break
                    if (item == archivePath) continue
                    val node = fs.stat(item) ?: continue
                    val topName = PathUtils.uniqueName(node.name, usedTopNames, node.isDirectory)
                    usedTopNames.add(topName)
                    writeNode(node, topName)
                }
            }

            if (sink.isCancelled()) {
                runCatching { fs.delete(archivePath) }
                throw IOException("Compression cancelled")
            }
            completed = true
            return archivePath
        } finally {
            if (!completed) {
                runCatching { fs.delete(archivePath) }
            }
        }
    }

    suspend fun listEntries(zip: VfsPath): List<ZipEntryInfo> {
        checkNotEncryptedHeader(zip)
        return if (zip is VfsPath.Local) {
            listEntriesLocal(File(zip.normalized))
        } else {
            listEntriesStream(zip)
        }
    }

    private fun listEntriesLocal(file: File): List<ZipEntryInfo> {
        return openZipFileWithFallback(file).use { zf ->
            val out = mutableListOf<ZipEntryInfo>()
            val enumeration = zf.entries()
            while (enumeration.hasMoreElements()) {
                val ze = enumeration.nextElement()
                out.add(
                    ZipEntryInfo(
                        name = ze.name,
                        isDirectory = ze.isDirectory,
                        size = ze.size,
                        compressedSize = ze.compressedSize,
                        lastModified = ze.time.coerceAtLeast(0L),
                    ),
                )
            }
            out
        }
    }

    private fun listEntriesStream(zip: VfsPath): List<ZipEntryInfo> {
        return try {
            readEntriesFromStream(zip, Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            readEntriesFromStream(zip, Charset.forName("CP437"))
        }
    }

    private fun readEntriesFromStream(zip: VfsPath, charset: Charset): List<ZipEntryInfo> {
        val out = mutableListOf<ZipEntryInfo>()
        ZipInputStream(BufferedInputStream(fs.openInput(zip)), charset).use { zis ->
            val drainBuf = ByteArray(bufferSize)
            while (true) {
                val ze = try {
                    zis.nextEntry
                } catch (e: ZipException) {
                    if (e.message?.contains("encrypted", ignoreCase = true) == true) {
                        throw EncryptedZipException("Password-protected ZIP archives are not supported")
                    }
                    throw e
                } ?: break

                var measuredSize = ze.size
                if (!ze.isDirectory && measuredSize < 0L) {
                    var count = 0L
                    while (true) {
                        val n = zis.read(drainBuf)
                        if (n < 0) break
                        count += n
                    }
                    measuredSize = count
                }
                out.add(
                    ZipEntryInfo(
                        name = ze.name,
                        isDirectory = ze.isDirectory,
                        size = measuredSize,
                        compressedSize = ze.compressedSize,
                        lastModified = ze.time.coerceAtLeast(0L),
                    ),
                )
                zis.closeEntry()
            }
        }
        return out
    }

    suspend fun extract(
        zip: VfsPath,
        dstDir: VfsPath,
        entries: Set<String>? = null,
        overwrite: Boolean = false,
        allowLargeArchive: Boolean = false,
        sink: ProgressSink = ProgressSink.NOOP,
    ): ExtractResult {
        checkNotEncryptedHeader(zip)
        val archiveNode = fs.stat(zip) ?: throw IOException("Archive not found: ${zip.displayName}")
        val dstNode = fs.stat(dstDir)
        if (dstNode == null || !dstNode.isDirectory) {
            throw IOException("Destination directory does not exist: ${dstDir.displayName}")
        }

        val archiveSize = archiveNode.size
        return if (zip is VfsPath.Local) {
            extractFromZipFile(
                zipFile = File(zip.normalized),
                archiveSize = archiveSize,
                dstDir = dstDir,
                selectedEntries = entries,
                overwrite = overwrite,
                allowLargeArchive = allowLargeArchive,
                sink = sink,
            )
        } else {
            extractFromStream(
                zip = zip,
                archiveSize = archiveSize,
                dstDir = dstDir,
                selectedEntries = entries,
                overwrite = overwrite,
                allowLargeArchive = allowLargeArchive,
                sink = sink,
            )
        }
    }

    private suspend fun extractFromZipFile(
        zipFile: File,
        archiveSize: Long,
        dstDir: VfsPath,
        selectedEntries: Set<String>?,
        overwrite: Boolean,
        allowLargeArchive: Boolean,
        sink: ProgressSink,
    ): ExtractResult {
        val state = ExtractionState(archiveSize, allowLargeArchive)
        openZipFileWithFallback(zipFile).use { zf ->
            val enumeration = zf.entries()
            while (enumeration.hasMoreElements()) {
                if (sink.isCancelled()) {
                    return state.toResult(cancelled = true)
                }
                val ze = enumeration.nextElement()
                if (!matchesSelection(ze.name, selectedEntries)) continue
                try {
                    if (ze.isDirectory) {
                        processEntry(
                            rawName = ze.name,
                            isDirectory = true,
                            lastModified = ze.time,
                            contentStream = null,
                            dstDir = dstDir,
                            overwrite = overwrite,
                            state = state,
                            sink = sink,
                        )
                    } else {
                        zf.getInputStream(ze).use { entryStream ->
                            processEntry(
                                rawName = ze.name,
                                isDirectory = false,
                                lastModified = ze.time,
                                contentStream = entryStream,
                                dstDir = dstDir,
                                overwrite = overwrite,
                                state = state,
                                sink = sink,
                            )
                        }
                    }
                } catch (e: ZipException) {
                    if (e.message?.contains("encrypted", ignoreCase = true) == true) {
                        throw EncryptedZipException("Password-protected ZIP archives are not supported")
                    }
                    throw e
                }
            }
        }
        return state.toResult(cancelled = sink.isCancelled())
    }

    private suspend fun extractFromStream(
        zip: VfsPath,
        archiveSize: Long,
        dstDir: VfsPath,
        selectedEntries: Set<String>?,
        overwrite: Boolean,
        allowLargeArchive: Boolean,
        sink: ProgressSink,
    ): ExtractResult {
        return try {
            extractStreamWithCharset(zip, Charsets.UTF_8, archiveSize, dstDir, selectedEntries, overwrite, allowLargeArchive, sink)
        } catch (_: IllegalArgumentException) {
            extractStreamWithCharset(zip, Charset.forName("CP437"), archiveSize, dstDir, selectedEntries, overwrite, allowLargeArchive, sink)
        }
    }

    private suspend fun extractStreamWithCharset(
        zip: VfsPath,
        charset: Charset,
        archiveSize: Long,
        dstDir: VfsPath,
        selectedEntries: Set<String>?,
        overwrite: Boolean,
        allowLargeArchive: Boolean,
        sink: ProgressSink,
    ): ExtractResult {
        val state = ExtractionState(archiveSize, allowLargeArchive)
        ZipInputStream(BufferedInputStream(fs.openInput(zip)), charset).use { zis ->
            while (true) {
                if (sink.isCancelled()) {
                    return state.toResult(cancelled = true)
                }
                val ze = try {
                    zis.nextEntry
                } catch (e: ZipException) {
                    if (e.message?.contains("encrypted", ignoreCase = true) == true) {
                        throw EncryptedZipException("Password-protected ZIP archives are not supported")
                    }
                    throw e
                } ?: break

                if (matchesSelection(ze.name, selectedEntries)) {
                    processEntry(
                        rawName = ze.name,
                        isDirectory = ze.isDirectory,
                        lastModified = ze.time,
                        contentStream = if (ze.isDirectory) null else zis,
                        dstDir = dstDir,
                        overwrite = overwrite,
                        state = state,
                        sink = sink,
                    )
                }
                zis.closeEntry()
            }
        }
        return state.toResult(cancelled = sink.isCancelled())
    }

    private suspend fun processEntry(
        rawName: String,
        isDirectory: Boolean,
        lastModified: Long,
        contentStream: InputStream?,
        dstDir: VfsPath,
        overwrite: Boolean,
        state: ExtractionState,
        sink: ProgressSink,
    ) {
        state.entryCount++
        if (!state.allowLargeArchive && state.entryCount > maxEntries) {
            throw ZipBombException("Archive exceeds maximum entry count ($maxEntries)")
        }

        val segments = sanitizeEntrySegments(rawName)
        if (segments == null) {
            state.unsafeSkipped++
            return
        }
        if (segments.isEmpty()) {
            return
        }

        val localRootFile = (dstDir as? VfsPath.Local)?.let { File(it.normalized) }
        if (localRootFile != null) {
            var probe = localRootFile
            for (seg in segments) {
                probe = File(probe, seg)
                if (!LocalFileSystem.isInside(probe, localRootFile)) {
                    state.unsafeSkipped++
                    return
                }
            }
        }

        sink.onProgress(
            OperationProgress(
                id = "zip-extract",
                kind = OperationKind.EXTRACT,
                title = "Extracting archive",
                currentItem = segments.joinToString("/"),
                processedItems = state.entryCount,
                processedBytes = state.totalUncompressedBytes,
                unsafeSkipped = state.unsafeSkipped,
            ),
        )

        val parentSegments = if (isDirectory) segments else segments.dropLast(1)
        var currentDir = dstDir
        for (seg in parentSegments) {
            if (localRootFile != null) {
                val candidate = File((currentDir as VfsPath.Local).normalized, seg)
                if (!LocalFileSystem.isInside(candidate, localRootFile)) {
                    state.unsafeSkipped++
                    return
                }
            }
            val next = fs.child(currentDir, seg)
            val stat = fs.stat(next)
            currentDir = if (stat != null) {
                if (!stat.isDirectory) {
                    if (!overwrite) {
                        state.skippedConflicts++
                        return
                    }
                    fs.delete(next)
                    fs.createDirectory(currentDir, seg)
                } else {
                    next
                }
            } else {
                fs.createDirectory(currentDir, seg)
            }
        }

        if (isDirectory) {
            state.extractedDirs++
            if (lastModified > 0L) {
                runCatching { fs.setLastModified(currentDir, lastModified) }
            }
            return
        }

        val fileName = segments.last()
        if (localRootFile != null) {
            val targetFile = File((currentDir as VfsPath.Local).normalized, fileName)
            if (!LocalFileSystem.isInside(targetFile, localRootFile)) {
                state.unsafeSkipped++
                return
            }
        }

        val existingPath = fs.child(currentDir, fileName)
        val existingStat = fs.stat(existingPath)
        if (existingStat != null) {
            if (existingStat.isDirectory || !overwrite) {
                state.skippedConflicts++
                return
            }
        }

        val targetPath = existingStat?.path
            ?: fs.createFile(currentDir, fileName, PathUtils.guessMimeType(fileName))

        var ok = false
        try {
            fs.openOutput(targetPath, append = false).use { out ->
                val buf = ByteArray(bufferSize)
                val stream = contentStream ?: return@use
                while (true) {
                    if (sink.isCancelled()) return
                    val n = stream.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        state.totalUncompressedBytes += n
                        if (!state.allowLargeArchive && isZipBombTripped(state.totalUncompressedBytes, state.archiveSize)) {
                            throw ZipBombException("Archive exceeded safe expansion threshold (possible zip bomb)")
                        }
                        out.write(buf, 0, n)
                    }
                }
                out.flush()
            }
            ok = true
        } finally {
            if (!ok && existingStat == null) {
                runCatching { fs.delete(targetPath) }
            }
        }

        if (lastModified > 0L) {
            runCatching { fs.setLastModified(targetPath, lastModified) }
        }
        state.extractedFiles++
    }

    private fun isZipBombTripped(uncompressedBytes: Long, archiveSize: Long): Boolean {
        if (uncompressedBytes <= bombBytesThreshold) return false
        return archiveSize <= 0L || uncompressedBytes > archiveSize * maxExpansionRatio
    }

    private fun matchesSelection(entryName: String, selected: Set<String>?): Boolean {
        if (selected == null) return true
        val clean = entryName.trimEnd('/')
        return selected.any { sel ->
            val cleanSel = sel.trimEnd('/')
            entryName == sel || clean == cleanSel || clean.startsWith("$cleanSel/")
        }
    }

    private fun checkNotEncryptedHeader(zip: VfsPath) {
        val header = ByteArray(30)
        val read = fs.openInput(zip).use { stream ->
            var offset = 0
            while (offset < header.size) {
                val n = stream.read(header, offset, header.size - offset)
                if (n <= 0) break
                offset += n
            }
            offset
        }
        // Local file header signature: 0x04034b50 ("PK\u0003\u0004"), general purpose bit flag at bytes 6..7 (LE)
        if (read >= 8 &&
            header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() &&
            header[3] == 0x04.toByte()
        ) {
            val gpFlags = (header[6].toInt() and 0xFF) or ((header[7].toInt() and 0xFF) shl 8)
            if ((gpFlags and 0x01) != 0) {
                throw EncryptedZipException("Password-protected ZIP archives are not supported")
            }
        }
    }

    private fun openZipFileWithFallback(file: File): ZipFile {
        return try {
            val zf = ZipFile(file, Charsets.UTF_8)
            // Eagerly iterate names so malformed UTF-8 triggers fallback before use
            val e = zf.entries()
            while (e.hasMoreElements()) {
                e.nextElement().name
            }
            zf
        } catch (_: IllegalArgumentException) {
            ZipFile(file, Charset.forName("CP437"))
        }
    }

    private class ExtractionState(
        val archiveSize: Long,
        val allowLargeArchive: Boolean,
    ) {
        var entryCount: Int = 0
        var totalUncompressedBytes: Long = 0L
        var extractedFiles: Int = 0
        var extractedDirs: Int = 0
        var skippedConflicts: Int = 0
        var unsafeSkipped: Int = 0

        fun toResult(cancelled: Boolean): ExtractResult = ExtractResult(
            extractedFiles = extractedFiles,
            extractedDirs = extractedDirs,
            skippedConflicts = skippedConflicts,
            unsafeEntriesSkipped = unsafeSkipped,
            cancelled = cancelled,
        )
    }

    companion object {
        /**
         * Validates and splits a ZIP entry path into safe relative segments.
         * Returns `null` if the entry attempts path traversal or contains unsafe characters:
         * - empty or NUL-containing names
         * - leading `/` or `\`
         * - Windows drive letters (`C:...`)
         * - `.` or `..` segments (after splitting on both `/` and `\`)
         */
        fun sanitizeEntrySegments(rawName: String): List<String>? {
            if (rawName.isEmpty() || rawName.contains('\u0000')) return null
            if (rawName.startsWith("/") || rawName.startsWith("\\")) return null
            if (rawName.length >= 2 && rawName[0].isLetter() && rawName[1] == ':') return null

            val trimmed = rawName.trimEnd('/', '\\')
            if (trimmed.isEmpty()) return null

            val rawParts = trimmed.split('/', '\\')
            val segments = ArrayList<String>(rawParts.size)
            for ((idx, part) in rawParts.withIndex()) {
                if (part.isEmpty()) continue
                if (part == "." || part == "..") return null
                if (idx == 0 && part.contains(':')) return null
                if (PathUtils.validateName(part) is NameValidation.Invalid) return null
                segments.add(part)
            }
            return if (segments.isEmpty()) null else segments
        }
    }
}
