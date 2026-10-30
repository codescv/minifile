package com.minifile.explorer.core.ops

import com.minifile.explorer.core.model.MediaScanNotifier
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.VfsPath
import java.io.IOException

class NomediaManager(
    private val fs: FileSystem,
    private val scanner: MediaScanNotifier = MediaScanNotifier.NOOP,
) {
    companion object {
        const val NOMEDIA_FILE = ".nomedia"
    }

    suspend fun has(dir: VfsPath): Boolean {
        val candidate = runCatching { fs.child(dir, NOMEDIA_FILE) }.getOrNull()
        if (candidate != null && runCatching { fs.exists(candidate) }.getOrDefault(false)) {
            return true
        }
        return runCatching {
            fs.list(dir, includeHidden = true).any { it.name == NOMEDIA_FILE }
        }.getOrDefault(false)
    }

    /**
     * Creates or removes `.nomedia` in [dir] and notifies media scanner.
     * Returns the resulting `.nomedia` state (`true` if hidden from gallery).
     */
    suspend fun set(dir: VfsPath, hidden: Boolean): Boolean {
        val dirNode = fs.stat(dir) ?: throw IOException("Folder does not exist: ${dir.displayName}")
        if (!dirNode.isDirectory) throw IOException("Not a folder: ${dir.displayName}")

        if (hidden) {
            if (!has(dir)) {
                fs.createFile(dir, NOMEDIA_FILE, "application/octet-stream")
            }
            scanner.rescan(dir)
            return true
        } else {
            val childPath = runCatching { fs.child(dir, NOMEDIA_FILE) }.getOrNull()
            var removed = false
            if (childPath != null && fs.exists(childPath)) {
                removed = fs.delete(childPath)
            }
            if (!removed) {
                val match = fs.list(dir, includeHidden = true).firstOrNull { it.name == NOMEDIA_FILE }
                if (match != null) {
                    removed = fs.delete(match.path)
                }
            }
            scanner.rescan(dir)
            return !removed && has(dir)
        }
    }

    suspend fun toggle(dir: VfsPath): Boolean {
        val targetState = !has(dir)
        return set(dir, targetState)
    }

    suspend fun scan(root: VfsPath, maxDepth: Int = 8): List<VfsPath> {
        val matches = mutableListOf<VfsPath>()

        suspend fun walk(dir: VfsPath, depth: Int) {
            if (depth > maxDepth) return
            val children = runCatching { fs.list(dir, includeHidden = true) }.getOrDefault(emptyList())
            if (children.any { it.name == NOMEDIA_FILE && !it.isDirectory }) {
                matches.add(dir)
            }
            for (child in children) {
                if (child.isDirectory && !child.isSymlink) {
                    walk(child.path, depth + 1)
                }
            }
        }

        walk(root, 0)
        return matches
    }
}
