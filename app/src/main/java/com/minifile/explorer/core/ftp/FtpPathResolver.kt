package com.minifile.explorer.core.ftp

import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.NameValidation
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsPath
import java.io.File
import java.io.IOException

class FtpAccessDeniedException(message: String) : IOException(message)

data class ResolvedFtpPath(
    val virtualPath: String,
    val vfsPath: VfsPath,
)

data class ResolvedParentAndName(
    val virtualPath: String,
    val parentVirtualPath: String,
    val parentVfsPath: VfsPath,
    val name: String,
)

/**
 * Normalizes virtual FTP paths within the configured [root] jail (`ARCHITECTURE.md` §6.1).
 * - `..` clamps at `/` so `CWD ../../..` stays inside the root.
 * - Rejects NUL bytes and backslashes.
 * - Enforces canonical-path / symlink checks via [LocalFileSystem.isInside] for `Local` roots.
 */
class FtpPathResolver(
    private val root: VfsPath,
    private val fs: FileSystem,
) {
    private val localRootFile: File? = (root as? VfsPath.Local)?.let { File(it.normalized) }

    init {
        if (localRootFile != null && !localRootFile.exists()) {
            // Root check happens dynamically too, but normalize reference
        }
    }

    fun normalizeVirtual(cwd: String, rawArg: String): List<String> {
        if (rawArg.contains('\u0000') || rawArg.contains('\\')) {
            throw FtpAccessDeniedException("Illegal path characters")
        }
        val combined = if (rawArg.startsWith("/")) {
            rawArg
        } else if (cwd == "/" || cwd.isEmpty()) {
            "/$rawArg"
        } else {
            "${cwd.trimEnd('/')}/$rawArg"
        }

        val stack = ArrayDeque<String>()
        for (part in combined.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> {
                    if (PathUtils.validateName(part) is NameValidation.Invalid) {
                        throw FtpAccessDeniedException("Invalid path segment")
                    }
                    stack.addLast(part)
                }
            }
        }
        return stack.toList()
    }

    suspend fun resolve(cwd: String, rawArg: String = ""): ResolvedFtpPath {
        val segments = if (rawArg.isEmpty()) {
            normalizeVirtual("/", cwd)
        } else {
            normalizeVirtual(cwd, rawArg)
        }
        ensureLocalInside(root)

        var current = root
        for (seg in segments) {
            current = fs.child(current, seg)
            ensureLocalInside(current)
        }

        val virtualPath = if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
        return ResolvedFtpPath(virtualPath = virtualPath, vfsPath = current)
    }

    suspend fun resolveParentAndName(cwd: String, rawArg: String): ResolvedParentAndName {
        val segments = normalizeVirtual(cwd, rawArg)
        if (segments.isEmpty()) {
            throw FtpAccessDeniedException("Operation not permitted on root directory")
        }
        ensureLocalInside(root)

        val parentSegments = segments.dropLast(1)
        val leafName = segments.last()
        var parentPath = root
        for (seg in parentSegments) {
            parentPath = fs.child(parentPath, seg)
            ensureLocalInside(parentPath)
        }

        val parentNode = fs.stat(parentPath)
            ?: throw FtpAccessDeniedException("Parent directory does not exist")
        if (!parentNode.isDirectory) {
            throw FtpAccessDeniedException("Parent path is not a directory")
        }

        val candidateTarget = fs.child(parentPath, leafName)
        ensureLocalInside(candidateTarget)

        val parentVirtual = if (parentSegments.isEmpty()) "/" else "/" + parentSegments.joinToString("/")
        val fullVirtual = "/" + segments.joinToString("/")
        return ResolvedParentAndName(
            virtualPath = fullVirtual,
            parentVirtualPath = parentVirtual,
            parentVfsPath = parentPath,
            name = leafName,
        )
    }

    fun ensureLocalInside(candidate: VfsPath) {
        val rootFile = localRootFile ?: return
        val localCandidate = candidate as? VfsPath.Local
            ?: throw FtpAccessDeniedException("Cross-backend escape denied")
        val candidateFile = File(localCandidate.normalized)
        if (!LocalFileSystem.isInside(candidateFile, rootFile)) {
            throw FtpAccessDeniedException("Access denied")
        }
    }
}
