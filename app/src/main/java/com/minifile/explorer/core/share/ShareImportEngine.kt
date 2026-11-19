package com.minifile.explorer.core.share

import com.minifile.explorer.core.gdrive.GDriveAuthException
import com.minifile.explorer.core.model.MediaScanNotifier
import com.minifile.explorer.core.peer.PeerConflictPolicy
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath
import java.io.IOException
import java.io.InputStream
import java.util.Locale

data class ShareImportSource(
    val desiredName: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val openInput: () -> InputStream,
)

data class ShareImportProgress(
    val processedItems: Int,
    val totalItems: Int,
    val currentItemName: String,
    val processedBytes: Long,
    val totalBytes: Long,
) {
    val fraction: Float?
        get() = when {
            totalBytes > 0L -> (processedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            totalItems > 1 -> (processedItems.toFloat() / totalItems.toFloat()).coerceIn(0f, 1f)
            else -> null
        }
}

data class ShareSavedFile(
    val name: String,
    val path: VfsPath,
    val bytesWritten: Long,
)

data class ShareImportResult(
    val destinationDir: VfsPath,
    val saved: List<ShareSavedFile>,
    val skipped: List<String>,
    val failed: List<Pair<String, String>>,
    val cancelled: Boolean,
    val reauthGDriveServerId: String? = null,
)

/**
 * Pure-JVM streaming importer for external shared items into any unified VFS backend
 * (`LocalFileSystem`, `SafFileSystem`, `FtpClientFileSystem`, `GoogleDriveFileSystem`).
 *
 * Streams in bounded 64 KiB chunks so large files are never buffered in RAM, checks cancellation
 * on every chunk, and cleans up newly created partial files on error or cancellation.
 */
class ShareImportEngine(
    private val fs: FileSystem,
    private val mediaScanNotifier: MediaScanNotifier = MediaScanNotifier.NOOP,
) {
    companion object {
        const val CHUNK_SIZE_BYTES = 64 * 1024
        private const val MAX_CREATE_DIR_DEPTH = 8
        private const val PROGRESS_MIN_INTERVAL_NS = 100_000_000L // 100 ms
        private const val PROGRESS_UNKNOWN_SIZE_STEP_BYTES = 512L * 1024L
        private const val TEMP_PART_SUFFIX = ".minifile-part"
        private const val TEMP_BAK_SUFFIX = ".minifile-bak"
    }

    suspend fun importItems(
        sources: List<ShareImportSource>,
        destinationDir: VfsPath,
        conflictPolicy: PeerConflictPolicy = PeerConflictPolicy.RENAME,
        isCancelled: () -> Boolean = { false },
        onProgress: (ShareImportProgress) -> Unit = {},
    ): ShareImportResult {
        if (sources.isEmpty()) {
            return ShareImportResult(
                destinationDir = destinationDir,
                saved = emptyList(),
                skipped = emptyList(),
                failed = emptyList(),
                cancelled = isCancelled(),
            )
        }

        val resolvedDir = try {
            ensureDestinationDirectory(destinationDir)
        } catch (e: Exception) {
            val msg = e.message ?: "Destination folder is unavailable"
            val reauthId = extractReauthServerId(e, destinationDir)
            return ShareImportResult(
                destinationDir = destinationDir,
                saved = emptyList(),
                skipped = emptyList(),
                failed = sources.map { it.desiredName to msg },
                cancelled = false,
                reauthGDriveServerId = reauthId,
            )
        }

        val fatSafe = resolvedDir is VfsPath.Saf
        val caseInsensitive = resolvedDir is VfsPath.Saf
        val existingChildren = try {
            fs.list(resolvedDir, includeHidden = true)
        } catch (e: Exception) {
            val msg = e.message ?: "Cannot read destination folder"
            val reauthId = extractReauthServerId(e, resolvedDir)
            return ShareImportResult(
                destinationDir = resolvedDir,
                saved = emptyList(),
                skipped = emptyList(),
                failed = sources.map { it.desiredName to msg },
                cancelled = false,
                reauthGDriveServerId = reauthId,
            )
        }

        val existingByKey = LinkedHashMap<String, VfsNode>(existingChildren.size)
        val existingNames = LinkedHashSet<String>(existingChildren.size)
        for (child in existingChildren) {
            existingNames.add(child.name)
            existingByKey[lookupKey(child.name, caseInsensitive)] = child
        }

        val knownTotalBytes = if (sources.all { it.sizeBytes >= 0L }) {
            sources.sumOf { it.sizeBytes }
        } else {
            -1L
        }

        val saved = ArrayList<ShareSavedFile>(sources.size)
        val skipped = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()
        var reauthGDriveServerId: String? = null
        val buffer = ByteArray(CHUNK_SIZE_BYTES)
        var totalBytesDone = 0L
        var lastProgressEmitNs = 0L
        var lastProgressEmitBytes = 0L

        fun emitProgress(processedItems: Int, itemName: String, force: Boolean) {
            val nowNs = System.nanoTime()
            if (!force) {
                val elapsedOk = (nowNs - lastProgressEmitNs) >= PROGRESS_MIN_INTERVAL_NS
                val deltaBytes = totalBytesDone - lastProgressEmitBytes
                val deltaOk = if (knownTotalBytes > 0L) {
                    deltaBytes * 100L >= knownTotalBytes
                } else {
                    deltaBytes >= PROGRESS_UNKNOWN_SIZE_STEP_BYTES
                }
                if (!elapsedOk && !deltaOk) return
            }
            lastProgressEmitNs = nowNs
            lastProgressEmitBytes = totalBytesDone
            onProgress(
                ShareImportProgress(
                    processedItems = processedItems,
                    totalItems = sources.size,
                    currentItemName = itemName,
                    processedBytes = totalBytesDone,
                    totalBytes = knownTotalBytes,
                ),
            )
        }

        for ((index, source) in sources.withIndex()) {
            if (isCancelled()) {
                return ShareImportResult(resolvedDir, saved, skipped, failed, cancelled = true)
            }

            val cleanDesired = ShareReceivePolicy.sanitizeFilename(
                rawName = source.desiredName,
                mimeType = source.mimeType,
                fatSafe = fatSafe,
                appendMimeExtensionIfMissing = false,
            )

            val decision = ShareReceivePolicy.resolveCollision(
                desiredName = cleanDesired,
                existingNames = existingNames,
                policy = conflictPolicy,
                caseInsensitive = caseInsensitive,
            )

            val writePlan = when (decision) {
                ShareReceivePolicy.CollisionDecision.Skip -> {
                    skipped.add(cleanDesired)
                    emitProgress(processedItems = index + 1, itemName = cleanDesired, force = true)
                    continue
                }

                is ShareReceivePolicy.CollisionDecision.Write -> decision
            }

            val targetName = writePlan.fileName
            emitProgress(processedItems = index, itemName = targetName, force = true)

            var stagingPath: VfsPath? = null
            var stagingName: String? = null
            var completed = false
            var keepStagingOnFailure = false
            var fileBytesWritten = 0L

            try {
                val existingNode = existingByKey[lookupKey(targetName, caseInsensitive)]
                if (existingNode != null) {
                    if (existingNode.isDirectory) {
                        throw IOException("Cannot overwrite folder '$targetName' with a file")
                    }
                    if (!writePlan.overwrite) {
                        throw IOException("File '$targetName' already exists")
                    }
                }

                val isOverwrite = existingNode != null
                val effectiveMime = source.mimeType
                    ?.substringBefore(';')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.endsWith("/*") && it != "*/*" }
                    ?: PathUtils.guessMimeType(targetName)

                source.openInput().use { input ->
                    val writeName = if (isOverwrite) {
                        buildTempSiblingName(targetName, TEMP_PART_SUFFIX, existingNames, fatSafe, caseInsensitive)
                    } else {
                        targetName
                    }
                    stagingName = writeName
                    val created = fs.createFile(resolvedDir, writeName, effectiveMime)
                    stagingPath = created

                    fs.openOutput(created, append = false).use { output ->
                        while (true) {
                            if (isCancelled()) {
                                break
                            }
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (n > 0) {
                                output.write(buffer, 0, n)
                                fileBytesWritten += n
                                totalBytesDone += n
                                emitProgress(processedItems = index, itemName = targetName, force = false)
                            }
                        }
                        if (!isCancelled()) {
                            output.flush()
                        }
                    }
                }

                if (isCancelled()) {
                    return ShareImportResult(resolvedDir, saved, skipped, failed, cancelled = true)
                }

                val writtenPath = if (isOverwrite) {
                    val temp = checkNotNull(stagingPath)
                    val reservedNames = HashSet(existingNames).apply {
                        stagingName?.let { add(it) }
                    }
                    val backupName = buildTempSiblingName(
                        targetName = targetName,
                        suffix = TEMP_BAK_SUFFIX,
                        existingNames = reservedNames,
                        fatSafe = fatSafe,
                        caseInsensitive = caseInsensitive,
                    )
                    // Step 1: Move original aside to .minifile-bak. If this throws, original is still intact at targetName.
                    val backupPath = fs.rename(existingNode!!.path, backupName)
                    // Step 2: Move staged temp into targetName. On failure, roll back backupPath -> targetName.
                    val renamed = try {
                        fs.rename(temp, targetName)
                    } catch (renameErr: Exception) {
                        val rolledBack = runCatching { fs.rename(backupPath, targetName) }.isSuccess
                        if (!rolledBack) {
                            keepStagingOnFailure = true
                        }
                        throw renameErr
                    }
                    stagingPath = renamed
                    // Step 3: Commit succeeded; delete backup best-effort.
                    runCatching { fs.delete(backupPath) }
                    renamed
                } else {
                    checkNotNull(stagingPath)
                }

                completed = true
                existingNames.add(targetName)
                existingByKey[lookupKey(targetName, caseInsensitive)] = VfsNode(
                    path = writtenPath,
                    name = targetName,
                    isDirectory = false,
                    size = fileBytesWritten,
                    lastModified = 0L,
                    mimeType = source.mimeType,
                )
                saved.add(ShareSavedFile(name = targetName, path = writtenPath, bytesWritten = fileBytesWritten))
                if (writtenPath is VfsPath.Local) {
                    runCatching { mediaScanNotifier.rescan(writtenPath) }
                }
                emitProgress(processedItems = index + 1, itemName = targetName, force = true)
            } catch (e: Exception) {
                if (isCancelled()) {
                    return ShareImportResult(resolvedDir, saved, skipped, failed, cancelled = true)
                }
                if (reauthGDriveServerId == null) {
                    reauthGDriveServerId = extractReauthServerId(e, resolvedDir)
                }
                failed.add(targetName to (e.message ?: e::class.java.simpleName))
            } finally {
                if (!completed && !keepStagingOnFailure && stagingPath != null) {
                    runCatching { fs.delete(stagingPath!!) }
                }
            }
        }

        return ShareImportResult(
            destinationDir = resolvedDir,
            saved = saved,
            skipped = skipped,
            failed = failed,
            cancelled = isCancelled(),
            reauthGDriveServerId = reauthGDriveServerId,
        )
    }

    private fun extractReauthServerId(e: Throwable, dest: VfsPath): String? {
        if (dest !is VfsPath.GDrive) return null
        var cur: Throwable? = e
        while (cur != null) {
            if (cur is GDriveAuthException && cur.needsInteraction) {
                return dest.serverId
            }
            cur = cur.cause
        }
        return null
    }

    private suspend fun ensureDestinationDirectory(dir: VfsPath): VfsPath {
        val existing = fs.stat(dir)
        if (existing != null) {
            if (!existing.isDirectory) {
                throw IOException("Destination '${dir.displayName}' is not a folder")
            }
            return existing.path
        }

        val missingChain = ArrayDeque<String>()
        var cursor: VfsPath = dir
        var anchor: VfsPath? = null
        while (missingChain.size < MAX_CREATE_DIR_DEPTH) {
            val seg = cursor.displayName
            val parent = fs.parent(cursor)
                ?: throw IOException("Destination folder does not exist: ${dir.displayName}")
            missingChain.addFirst(seg)
            val parentStat = fs.stat(parent)
            if (parentStat != null) {
                if (!parentStat.isDirectory) {
                    throw IOException("Parent '${parent.displayName}' is not a folder")
                }
                anchor = parentStat.path
                break
            }
            cursor = parent
        }

        var current = anchor
            ?: throw IOException("Destination parent folder does not exist: ${dir.displayName}")
        for (seg in missingChain) {
            current = fs.createDirectory(current, seg)
        }
        return current
    }

    private fun buildTempSiblingName(
        targetName: String,
        suffix: String,
        existingNames: Set<String>,
        fatSafe: Boolean,
        caseInsensitive: Boolean,
    ): String {
        val base = ShareReceivePolicy.sanitizeFilename(
            rawName = targetName,
            fatSafe = fatSafe,
            maxUtf8Bytes = 120,
            appendMimeExtensionIfMissing = false,
        ).trimStart('.').ifEmpty { "file" }
        val desired = ".$base$suffix"
        return ShareReceivePolicy.uniqueFileName(
            desired = desired,
            existingNames = existingNames,
            caseInsensitive = caseInsensitive,
        )
    }

    private fun lookupKey(name: String, caseInsensitive: Boolean): String =
        if (caseInsensitive) name.lowercase(Locale.ROOT) else name
}
