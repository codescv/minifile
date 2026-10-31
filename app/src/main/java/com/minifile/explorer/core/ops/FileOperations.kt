package com.minifile.explorer.core.ops

import com.minifile.explorer.core.model.Clock
import com.minifile.explorer.core.model.FolderStats
import com.minifile.explorer.core.model.OperationKind
import com.minifile.explorer.core.model.OperationProgress
import com.minifile.explorer.core.model.OperationResult
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale

enum class ConflictResolution {
    SKIP,
    OVERWRITE,
    KEEP_BOTH,
}

fun interface ConflictResolver {
    suspend fun resolve(src: VfsNode, existing: VfsNode): ConflictResolution

    companion object {
        val SKIP_ALL = ConflictResolver { _, _ -> ConflictResolution.SKIP }
        val OVERWRITE_ALL = ConflictResolver { _, _ -> ConflictResolution.OVERWRITE }
        val KEEP_BOTH_ALL = ConflictResolver { _, _ -> ConflictResolution.KEEP_BOTH }
    }
}

interface ProgressSink {
    fun onProgress(p: OperationProgress)
    fun isCancelled(): Boolean

    companion object {
        val NOOP = object : ProgressSink {
            override fun onProgress(p: OperationProgress) = Unit
            override fun isCancelled(): Boolean = false
        }
    }
}

data class SearchHit(
    val node: VfsNode,
    val relativePath: String,
)

class FileOperations(
    private val fs: FileSystem,
    private val clock: Clock = Clock.SYSTEM,
) {
    val fileSystem: FileSystem get() = fs
    private val bufferSize = 64 * 1024

    suspend fun copy(
        items: List<VfsPath>,
        dstDir: VfsPath,
        resolver: ConflictResolver = ConflictResolver.KEEP_BOTH_ALL,
        sink: ProgressSink = ProgressSink.NOOP,
    ): OperationResult {
        return transfer(
            items = items,
            dstDir = dstDir,
            deleteSource = false,
            resolver = resolver,
            sink = sink,
        )
    }

    suspend fun move(
        items: List<VfsPath>,
        dstDir: VfsPath,
        resolver: ConflictResolver = ConflictResolver.KEEP_BOTH_ALL,
        sink: ProgressSink = ProgressSink.NOOP,
    ): OperationResult {
        return transfer(
            items = items,
            dstDir = dstDir,
            deleteSource = true,
            resolver = resolver,
            sink = sink,
        )
    }

    private suspend fun transfer(
        items: List<VfsPath>,
        dstDir: VfsPath,
        deleteSource: Boolean,
        resolver: ConflictResolver,
        sink: ProgressSink,
    ): OperationResult {
        val dstNode = fs.stat(dstDir)
        if (dstNode == null || !dstNode.isDirectory) {
            return OperationResult(
                succeeded = 0,
                skipped = 0,
                failed = items.map { it to "Destination folder does not exist" },
                cancelled = false,
            )
        }

        val kind = if (deleteSource) OperationKind.MOVE else OperationKind.COPY
        val opId = "${kind.name.lowercase(Locale.ROOT)}-${clock.nowMs()}"
        val startMs = clock.nowMs()
        var succeeded = 0
        var skipped = 0
        val failed = mutableListOf<Pair<VfsPath, String>>()
        var bytesDone = 0L

        for ((index, src) in items.withIndex()) {
            if (sink.isCancelled()) {
                return OperationResult(succeeded, skipped, failed, cancelled = true)
            }
            val srcNode = runCatching { fs.stat(src) }.getOrNull()
            if (srcNode == null) {
                failed.add(src to "Source item no longer exists")
                continue
            }

            if (srcNode.isDirectory && PathUtils.isAncestor(src, dstDir)) {
                failed.add(src to "Cannot ${if (deleteSource) "move" else "copy"} a folder into itself")
                continue
            }

            val srcParent = runCatching { fs.parent(src) }.getOrNull()
            val sameDir = srcParent == dstDir
            if (sameDir && deleteSource) {
                skipped++
                continue
            }

            sink.onProgress(
                OperationProgress(
                    id = opId,
                    kind = kind,
                    title = if (deleteSource) "Moving items" else "Copying items",
                    currentItem = srcNode.name,
                    processedItems = index,
                    totalItems = items.size,
                    processedBytes = bytesDone,
                ),
            )

            try {
                val existingChildren = fs.list(dstDir, includeHidden = true)
                val existingNames = existingChildren.mapTo(HashSet()) { it.name }
                val existingTarget = existingChildren.firstOrNull { it.name == srcNode.name }

                if (deleteSource && !sameDir && existingTarget == null) {
                    val fastMoved = fs.moveWithinSameBackend(src, dstDir)
                    if (fastMoved != null) {
                        succeeded++
                        continue
                    }
                }

                val targetName: String
                val overwriteExisting: Boolean

                if (sameDir) {
                    targetName = PathUtils.uniqueName(srcNode.name, existingNames, srcNode.isDirectory)
                    overwriteExisting = false
                } else if (existingTarget != null) {
                    when (resolver.resolve(srcNode, existingTarget)) {
                        ConflictResolution.SKIP -> {
                            skipped++
                            continue
                        }

                        ConflictResolution.KEEP_BOTH -> {
                            targetName = PathUtils.uniqueName(srcNode.name, existingNames, srcNode.isDirectory)
                            overwriteExisting = false
                        }

                        ConflictResolution.OVERWRITE -> {
                            targetName = srcNode.name
                            overwriteExisting = true
                        }
                    }
                } else {
                    targetName = srcNode.name
                    overwriteExisting = false
                }

                val outcome = copyEntryRecursive(
                    srcNode = srcNode,
                    dstParent = dstDir,
                    targetName = targetName,
                    overwrite = overwriteExisting,
                    resolver = resolver,
                    sink = sink,
                    onBytes = { delta ->
                        bytesDone += delta
                        val elapsedSec = ((clock.nowMs() - startMs).coerceAtLeast(1L)) / 1000.0
                        sink.onProgress(
                            OperationProgress(
                                id = opId,
                                kind = kind,
                                title = if (deleteSource) "Moving items" else "Copying items",
                                currentItem = srcNode.name,
                                processedItems = index,
                                totalItems = items.size,
                                processedBytes = bytesDone,
                                bytesPerSecond = (bytesDone / elapsedSec).toLong(),
                            ),
                        )
                    },
                )

                if (outcome.cancelled) {
                    return OperationResult(succeeded, skipped, failed, cancelled = true)
                }

                if (deleteSource) {
                    val createdStat = fs.stat(outcome.targetPath)
                        ?: throw IOException("Verification failed: destination missing after copy")
                    if (!srcNode.isDirectory && srcNode.size >= 0 && createdStat.size >= 0 &&
                        createdStat.size != srcNode.size
                    ) {
                        throw IOException("Verification failed: size mismatch (${createdStat.size} != ${srcNode.size})")
                    }
                    val delRes = deleteSingleRecursive(src, sink)
                    if (!delRes) {
                        if (sink.isCancelled()) {
                            return OperationResult(succeeded, skipped, failed, cancelled = true)
                        }
                        throw IOException("Copied item, but could not remove original source")
                    }
                }
                succeeded++
            } catch (t: Throwable) {
                failed.add(src to (t.message ?: t::class.java.simpleName))
            }
        }

        return OperationResult(
            succeeded = succeeded,
            skipped = skipped,
            failed = failed,
            cancelled = sink.isCancelled(),
        )
    }

    private data class CopyEntryOutcome(
        val targetPath: VfsPath,
        val cancelled: Boolean,
    )

    private suspend fun copyEntryRecursive(
        srcNode: VfsNode,
        dstParent: VfsPath,
        targetName: String,
        overwrite: Boolean,
        resolver: ConflictResolver,
        sink: ProgressSink,
        onBytes: (Long) -> Unit,
    ): CopyEntryOutcome {
        if (sink.isCancelled()) {
            return CopyEntryOutcome(dstParent, cancelled = true)
        }
        if (srcNode.isDirectory) {
            val existing = fs.list(dstParent, includeHidden = true).firstOrNull { it.name == targetName }
            val dstSubDir = if (existing != null) {
                if (!existing.isDirectory) {
                    if (!overwrite) throw IOException("Cannot replace file '$targetName' with folder")
                    fs.delete(existing.path)
                    fs.createDirectory(dstParent, targetName)
                } else {
                    existing.path
                }
            } else {
                fs.createDirectory(dstParent, targetName)
            }

            if (!srcNode.isSymlink) {
                val children = fs.list(srcNode.path, includeHidden = true)
                val dstChildMap = fs.list(dstSubDir, includeHidden = true).associateBy { it.name }.toMutableMap()
                for (child in children) {
                    if (sink.isCancelled()) {
                        return CopyEntryOutcome(dstSubDir, cancelled = true)
                    }
                    val conflict = dstChildMap[child.name]
                    var childTargetName = child.name
                    var childOverwrite = overwrite
                    if (conflict != null && !overwrite) {
                        when (resolver.resolve(child, conflict)) {
                            ConflictResolution.SKIP -> continue
                            ConflictResolution.OVERWRITE -> childOverwrite = true
                            ConflictResolution.KEEP_BOTH -> {
                                childTargetName = PathUtils.uniqueName(child.name, dstChildMap.keys, child.isDirectory)
                            }
                        }
                    }
                    val subOut = copyEntryRecursive(
                        srcNode = child,
                        dstParent = dstSubDir,
                        targetName = childTargetName,
                        overwrite = childOverwrite,
                        resolver = resolver,
                        sink = sink,
                        onBytes = onBytes,
                    )
                    if (subOut.cancelled) {
                        return CopyEntryOutcome(dstSubDir, cancelled = true)
                    }
                }
            }
            if (srcNode.lastModified > 0L) {
                runCatching { fs.setLastModified(dstSubDir, srcNode.lastModified) }
            }
            return CopyEntryOutcome(dstSubDir, cancelled = false)
        } else {
            val existing = fs.list(dstParent, includeHidden = true).firstOrNull { it.name == targetName }
            val dstFile = if (existing != null) {
                if (existing.isDirectory) {
                    throw IOException("Cannot overwrite directory '$targetName' with a file")
                }
                if (!overwrite) {
                    throw IOException("Destination '$targetName' already exists")
                }
                existing.path
            } else {
                fs.createFile(dstParent, targetName, srcNode.resolvedMimeType)
            }

            val createdNew = existing == null
            var completed = false
            try {
                fs.openInput(srcNode.path).use { input ->
                    fs.openOutput(dstFile, append = false).use { output ->
                        val buf = ByteArray(bufferSize)
                        while (true) {
                            if (sink.isCancelled()) {
                                return CopyEntryOutcome(dstFile, cancelled = true)
                            }
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n > 0) {
                                output.write(buf, 0, n)
                                onBytes(n.toLong())
                            }
                        }
                        output.flush()
                    }
                }
                completed = true
            } finally {
                if (!completed && createdNew) {
                    runCatching { fs.delete(dstFile) }
                }
            }

            if (srcNode.lastModified > 0L) {
                runCatching { fs.setLastModified(dstFile, srcNode.lastModified) }
            }
            return CopyEntryOutcome(dstFile, cancelled = false)
        }
    }

    suspend fun delete(
        items: List<VfsPath>,
        sink: ProgressSink = ProgressSink.NOOP,
    ): OperationResult {
        val opId = "delete-${clock.nowMs()}"
        var succeeded = 0
        val failed = mutableListOf<Pair<VfsPath, String>>()

        for ((index, path) in items.withIndex()) {
            if (sink.isCancelled()) {
                return OperationResult(succeeded, 0, failed, cancelled = true)
            }
            sink.onProgress(
                OperationProgress(
                    id = opId,
                    kind = OperationKind.DELETE,
                    title = "Deleting items",
                    currentItem = path.displayName,
                    processedItems = index,
                    totalItems = items.size,
                ),
            )
            try {
                val ok = deleteSingleRecursive(path, sink)
                if (sink.isCancelled()) {
                    return OperationResult(succeeded, 0, failed, cancelled = true)
                }
                if (ok) {
                    succeeded++
                } else {
                    failed.add(path to "Could not delete item")
                }
            } catch (t: Throwable) {
                failed.add(path to (t.message ?: t::class.java.simpleName))
            }
        }
        return OperationResult(succeeded, 0, failed, cancelled = sink.isCancelled())
    }

    private suspend fun deleteSingleRecursive(path: VfsPath, sink: ProgressSink): Boolean {
        if (sink.isCancelled()) return false
        val node = fs.stat(path) ?: return false
        if (node.isDirectory && !node.isSymlink) {
            val children = fs.list(path, includeHidden = true)
            for (child in children) {
                if (sink.isCancelled()) return false
                if (!deleteSingleRecursive(child.path, sink)) {
                    return false
                }
            }
        }
        return fs.delete(path)
    }

    suspend fun rename(item: VfsPath, newName: String): VfsPath =
        fs.rename(item, newName)

    suspend fun createFolder(dir: VfsPath, name: String): VfsPath =
        fs.createDirectory(dir, name)

    suspend fun createFile(dir: VfsPath, name: String): VfsPath =
        fs.createFile(dir, name, PathUtils.guessMimeType(name))

    suspend fun sizeOf(
        items: List<VfsPath>,
        sink: ProgressSink = ProgressSink.NOOP,
    ): FolderStats {
        var bytes = 0L
        var files = 0
        var dirs = 0

        suspend fun visit(path: VfsPath) {
            if (sink.isCancelled()) return
            val node = fs.stat(path) ?: return
            if (node.isDirectory) {
                dirs++
                if (!node.isSymlink) {
                    val children = runCatching { fs.list(path, includeHidden = true) }.getOrDefault(emptyList())
                    for (child in children) {
                        if (sink.isCancelled()) return
                        visit(child.path)
                    }
                }
            } else {
                files++
                if (node.size > 0L) bytes += node.size
            }
        }

        for (item in items) {
            if (sink.isCancelled()) break
            visit(item)
        }
        return FolderStats(totalBytes = bytes, fileCount = files, dirCount = dirs)
    }

    suspend fun searchRecursive(
        root: VfsPath,
        query: String,
        includeHidden: Boolean,
        maxDepth: Int = 12,
        sink: ProgressSink = ProgressSink.NOOP,
        onHit: suspend (SearchHit) -> Unit = {},
    ): List<SearchHit> {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return emptyList()
        val hits = mutableListOf<SearchHit>()

        suspend fun walk(dir: VfsPath, relPrefix: String, depth: Int) {
            if (depth > maxDepth || sink.isCancelled()) return
            val entries = runCatching { fs.list(dir, includeHidden) }.getOrElse {
                if (depth == 0) throw FileNotFoundException("Cannot search directory: ${dir.displayName}")
                return
            }
            for (entry in entries) {
                if (sink.isCancelled()) return
                val rel = if (relPrefix.isEmpty()) entry.name else "$relPrefix/${entry.name}"
                if (entry.name.lowercase(Locale.ROOT).contains(needle)) {
                    val hit = SearchHit(entry, rel)
                    hits.add(hit)
                    onHit(hit)
                }
                if (entry.isDirectory && !entry.isSymlink) {
                    walk(entry.path, rel, depth + 1)
                }
            }
        }

        walk(root, "", 0)
        return hits
    }
}
