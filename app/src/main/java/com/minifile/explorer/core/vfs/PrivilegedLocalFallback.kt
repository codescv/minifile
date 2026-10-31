package com.minifile.explorer.core.vfs

import java.io.InputStream
import java.io.OutputStream

/**
 * Pure-Kotlin interface allowing [LocalFileSystem] in `core/` to delegate operations on
 * FUSE-restricted `/Android/data` and `/Android/obb` paths to an elevated shell backend
 * (Shizuku `IShizukuService` or Root `su`) without introducing `android.*` imports into `core/`.
 */
interface PrivilegedLocalFallback {
    val ownPackageName: String?
        get() = null

    fun isAvailable(): Boolean
    fun stat(path: String): VfsNode?
    fun exists(path: String): Boolean
    fun listDirectory(path: String, includeHidden: Boolean): List<VfsNode>?
    fun mkdir(path: String): Boolean
    fun createFile(path: String): Boolean
    fun delete(path: String): Boolean
    fun rename(srcPath: String, dstPath: String): Boolean
    fun setLastModified(path: String, epochMs: Long): Boolean
    fun openInput(path: String, offset: Long = 0L): InputStream
    fun openOutput(path: String, append: Boolean = false): OutputStream
}

/**
 * Pure-Kotlin POSIX shell quoting and NUL-delimited `stat`/`ls` parser shared with `PrivilegedShellBridge`.
 */
object PrivilegedShellCodec {

    fun shellQuote(arg: String): String =
        "'" + arg.replace("'", "'\\''") + "'"

    /**
     * Parses NUL-delimited records emitted by our privileged `listDirectory` shell snippet.
     * Each entry emits 6 NUL-terminated fields:
     * `[0] isDir ("1"/"0")`, `[1] hasNomedia ("1"/"0")`, `[2] statMeta ("perms|size|mtimeSec")`,
     * `[3] childCount (int or "-1")`, `[4] symlinkTarget`, `[5] entryName`.
     */
    fun parseDirectoryListing(
        parentNormalizedPath: String,
        rawBytes: ByteArray,
        includeHidden: Boolean,
    ): List<VfsNode> {
        if (rawBytes.isEmpty()) return emptyList()
        val text = rawBytes.toString(Charsets.UTF_8)
        val tokens = text.split('\u0000')
        // Trailing '\0' leaves a trailing empty token; each record consumes 6 tokens.
        val out = ArrayList<VfsNode>(tokens.size / 6)
        val cleanParent = PathUtils.normalizeLocalPath(parentNormalizedPath).trimEnd('/')
        var i = 0
        while (i + 5 < tokens.size) {
            val isDir = tokens[i] == "1"
            val hasNomedia = tokens[i + 1] == "1"
            val statMeta = tokens[i + 2]
            val childCount = tokens[i + 3].toIntOrNull() ?: -1
            val symlinkTarget = tokens[i + 4].takeIf { it.isNotEmpty() }
            val name = tokens[i + 5]
            i += 6

            if (name.isEmpty() || name == "." || name == "..") continue
            val hidden = PathUtils.isHiddenName(name)
            if (!includeHidden && hidden) continue

            val childPath = VfsPath.Local("$cleanParent/$name")
            out.add(
                buildNodeFromStatMeta(
                    path = childPath,
                    name = name,
                    isDir = isDir,
                    hasNomedia = hasNomedia,
                    childCount = childCount,
                    statMeta = statMeta,
                    symlinkTarget = symlinkTarget,
                ),
            )
        }
        return out
    }

    /**
     * Parses a single NUL-delimited stat output:
     * `[0] isDir ("1"/"0")`, `[1] hasNomedia ("1"/"0")`, `[2] statMeta ("perms|size|mtimeSec")`,
     * `[3] symlinkTarget`.
     */
    fun parseSingleStat(normalizedPath: String, rawBytes: ByteArray): VfsNode? {
        if (rawBytes.isEmpty()) return null
        val tokens = rawBytes.toString(Charsets.UTF_8).split('\u0000')
        if (tokens.size < 4) return null
        val isDir = tokens[0] == "1"
        val hasNomedia = tokens[1] == "1"
        val statMeta = tokens[2]
        val symlinkTarget = tokens[3].takeIf { it.isNotEmpty() }
        val norm = PathUtils.normalizeLocalPath(normalizedPath)
        val name = if (norm == "/") "/" else norm.substringAfterLast('/')
        return buildNodeFromStatMeta(
            path = VfsPath.Local(norm),
            name = name,
            isDir = isDir,
            hasNomedia = hasNomedia,
            childCount = -1,
            statMeta = statMeta,
            symlinkTarget = symlinkTarget,
        )
    }

    private fun buildNodeFromStatMeta(
        path: VfsPath.Local,
        name: String,
        isDir: Boolean,
        hasNomedia: Boolean,
        childCount: Int,
        statMeta: String,
        symlinkTarget: String?,
    ): VfsNode {
        val parts = statMeta.split('|')
        val perms = parts.getOrNull(0)?.takeIf { it.length >= 4 }
            ?: if (isDir) "drwxrwx---" else "-rw-rw----"
        val size = parts.getOrNull(1)?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val mtimeSec = parts.getOrNull(2)?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val isSymlink = symlinkTarget != null || perms.startsWith('l')
        return VfsNode(
            path = path,
            name = name,
            isDirectory = isDir,
            size = if (isDir) 0L else size,
            lastModified = mtimeSec * 1000L,
            mimeType = if (isDir) "inode/directory" else PathUtils.guessMimeType(name),
            isHidden = PathUtils.isHiddenName(name),
            canRead = true,
            canWrite = true,
            isSymlink = isSymlink,
            childCount = if (isDir) childCount else -1,
            hasNomedia = isDir && hasNomedia,
            posixPermissions = perms,
            symlinkTarget = symlinkTarget,
        )
    }
}
