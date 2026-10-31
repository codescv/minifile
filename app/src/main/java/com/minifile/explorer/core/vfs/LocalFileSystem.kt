package com.minifile.explorer.core.vfs

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission

class LocalFileSystem(
    private val fallback: PrivilegedLocalFallback? = null,
) : FileSystem {

    override fun supports(path: VfsPath): Boolean = path is VfsPath.Local

    private fun toFile(path: VfsPath): File {
        require(path is VfsPath.Local) { "Expected Local path, got $path" }
        return File(path.normalized)
    }

    private fun shouldPreferPrivileged(normalizedPath: String): Boolean {
        val fb = fallback ?: return false
        return fb.isAvailable() &&
            PathUtils.isRestrictedAndroidSubtree(normalizedPath) &&
            !PathUtils.isOwnPackageSubtree(normalizedPath, fb.ownPackageName)
    }

    private fun canFallbackPrivileged(normalizedPath: String): Boolean {
        val fb = fallback ?: return false
        return fb.isAvailable() && PathUtils.isRestrictedAndroidSubtree(normalizedPath)
    }

    override suspend fun stat(path: VfsPath): VfsNode? {
        val file = toFile(path)
        if (shouldPreferPrivileged(file.path)) {
            return fallback!!.stat(file.path)
        }
        val nio = file.toPath()
        val isSymlink = runCatching { Files.isSymbolicLink(nio) }.getOrDefault(false)
        if (!file.exists() && !isSymlink) {
            if (canFallbackPrivileged(file.path)) {
                return fallback!!.stat(file.path)
            }
            return null
        }
        return toNode(file, computeChildCount = false)
    }

    override suspend fun list(dir: VfsPath, includeHidden: Boolean): List<VfsNode> {
        val dirFile = toFile(dir)
        if (shouldPreferPrivileged(dirFile.path)) {
            return fallback!!.listDirectory(dirFile.path, includeHidden)
                ?: throw FileNotFoundException("Directory does not exist: ${dirFile.path}")
        }
        if (!dirFile.exists() || !dirFile.isDirectory) {
            if (canFallbackPrivileged(dirFile.path)) {
                return fallback!!.listDirectory(dirFile.path, includeHidden)
                    ?: throw FileNotFoundException("Directory does not exist: ${dirFile.path}")
            }
            throw FileNotFoundException("Directory does not exist: ${dirFile.path}")
        }
        val children = dirFile.listFiles()
        if (children == null) {
            if (canFallbackPrivileged(dirFile.path)) {
                return fallback!!.listDirectory(dirFile.path, includeHidden)
                    ?: throw IOException("Permission denied reading directory: ${dirFile.path}")
            }
            throw IOException("Permission denied reading directory: ${dirFile.path}")
        }

        val nodes = ArrayList<VfsNode>(children.size)
        for (child in children) {
            val name = child.name
            if (!includeHidden && PathUtils.isHiddenName(name)) continue
            nodes.add(toNode(child, computeChildCount = true, includeHiddenCount = includeHidden))
        }
        return nodes
    }

    override suspend fun child(dir: VfsPath, name: String): VfsPath {
        val validation = PathUtils.validateName(name)
        if (validation is NameValidation.Invalid) {
            throw IllegalArgumentException(validation.reason)
        }
        val dirFile = toFile(dir)
        return VfsPath.Local(File(dirFile, name).path)
    }

    override suspend fun parent(path: VfsPath): VfsPath? {
        val local = path as? VfsPath.Local ?: throw IllegalArgumentException("Expected Local path")
        if (local.normalized == "/") return null
        val parentFile = File(local.normalized).parentFile ?: return VfsPath.Local("/")
        return VfsPath.Local(parentFile.path)
    }

    override suspend fun exists(path: VfsPath): Boolean {
        val file = toFile(path)
        if (shouldPreferPrivileged(file.path)) {
            return fallback!!.exists(file.path)
        }
        val direct = file.exists() || runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
        if (!direct && canFallbackPrivileged(file.path)) {
            return fallback!!.exists(file.path)
        }
        return direct
    }

    override suspend fun createDirectory(dir: VfsPath, name: String): VfsPath {
        val target = toFile(child(dir, name))
        if (shouldPreferPrivileged(target.path)) {
            val existing = fallback!!.stat(target.path)
            if (existing != null) {
                if (existing.isDirectory) return VfsPath.Local(target.path)
                throw IOException("A file with name '$name' already exists")
            }
            if (!fallback.mkdir(target.path)) {
                throw IOException("Failed to create directory: ${target.path}")
            }
            return VfsPath.Local(target.path)
        }
        if (!target.parentFile.let { it != null && it.exists() && it.isDirectory }) {
            throw FileNotFoundException("Parent directory does not exist: ${target.parent}")
        }
        if (target.exists()) {
            if (target.isDirectory) return VfsPath.Local(target.path)
            throw IOException("A file with name '$name' already exists")
        }
        if (!target.mkdir()) {
            throw IOException("Failed to create directory: ${target.path}")
        }
        return VfsPath.Local(target.path)
    }

    override suspend fun createFile(dir: VfsPath, name: String, mime: String): VfsPath {
        val target = toFile(child(dir, name))
        if (shouldPreferPrivileged(target.path)) {
            if (!fallback!!.exists(target.path) && !fallback.createFile(target.path)) {
                throw IOException("Failed to create file: ${target.path}")
            }
            return VfsPath.Local(target.path)
        }
        val parent = target.parentFile
        if (parent == null || !parent.exists() || !parent.isDirectory) {
            throw FileNotFoundException("Parent directory does not exist: ${target.parent}")
        }
        if (!target.exists() && !target.createNewFile()) {
            throw IOException("Failed to create file: ${target.path}")
        }
        return VfsPath.Local(target.path)
    }

    override suspend fun delete(path: VfsPath): Boolean {
        val file = toFile(path)
        if (shouldPreferPrivileged(file.path)) {
            return fallback!!.delete(file.path)
        }
        val nio = file.toPath()
        return runCatching { Files.deleteIfExists(nio) }.getOrElse { file.delete() }
    }

    override suspend fun rename(path: VfsPath, newName: String): VfsPath {
        val validation = PathUtils.validateName(newName)
        if (validation is NameValidation.Invalid) {
            throw IllegalArgumentException(validation.reason)
        }
        val src = toFile(path)
        if (shouldPreferPrivileged(src.path)) {
            if (!fallback!!.exists(src.path)) {
                throw FileNotFoundException("Source does not exist: ${src.path}")
            }
            val parent = src.parentFile ?: throw IOException("Cannot rename filesystem root")
            val dst = File(parent, newName)
            if (dst.path != src.path && fallback.exists(dst.path)) {
                throw IOException("Destination already exists: ${dst.path}")
            }
            if (!fallback.rename(src.path, dst.path)) {
                throw IOException("Failed to rename ${src.path} to ${dst.path}")
            }
            return VfsPath.Local(dst.path)
        }
        if (!src.exists() && !runCatching { Files.isSymbolicLink(src.toPath()) }.getOrDefault(false)) {
            throw FileNotFoundException("Source does not exist: ${src.path}")
        }
        val parent = src.parentFile ?: throw IOException("Cannot rename filesystem root")
        val dst = File(parent, newName)
        if (dst.exists() && dst.canonicalPath != src.canonicalPath) {
            throw IOException("Destination already exists: ${dst.path}")
        }
        if (!src.renameTo(dst)) {
            Files.move(src.toPath(), dst.toPath())
        }
        return VfsPath.Local(dst.path)
    }

    override suspend fun moveWithinSameBackend(src: VfsPath, dstDir: VfsPath): VfsPath? {
        val srcFile = toFile(src)
        val dstDirFile = toFile(dstDir)
        if (shouldPreferPrivileged(srcFile.path) || shouldPreferPrivileged(dstDirFile.path)) {
            val dstStat = if (shouldPreferPrivileged(dstDirFile.path)) {
                fallback!!.stat(dstDirFile.path)
            } else {
                stat(dstDir)
            }
            if (dstStat?.isDirectory != true) return null
            val target = File(dstDirFile, srcFile.name)
            val targetExists = if (shouldPreferPrivileged(target.path)) {
                fallback!!.exists(target.path)
            } else {
                target.exists()
            }
            if (targetExists) return null
            return if (fallback!!.rename(srcFile.path, target.path)) VfsPath.Local(target.path) else null
        }
        if (!dstDirFile.isDirectory) return null
        val target = File(dstDirFile, srcFile.name)
        if (target.exists()) return null
        return if (srcFile.renameTo(target)) VfsPath.Local(target.path) else null
    }

    override fun openInput(path: VfsPath, offset: Long): InputStream {
        require(offset >= 0L) { "Negative offset: $offset" }
        val file = toFile(path)
        if (shouldPreferPrivileged(file.path)) {
            return fallback!!.openInput(file.path, offset)
        }
        if (!file.exists() || file.isDirectory) {
            if (canFallbackPrivileged(file.path)) {
                return fallback!!.openInput(file.path, offset)
            }
            throw FileNotFoundException("Cannot open file for reading: ${file.path}")
        }
        if (offset == 0L) {
            return FileInputStream(file)
        }
        val raf = RandomAccessFile(file, "r")
        try {
            raf.seek(offset)
            return RandomAccessFileInputStream(raf)
        } catch (t: Throwable) {
            runCatching { raf.close() }
            throw t
        }
    }

    override fun openOutput(path: VfsPath, append: Boolean): OutputStream {
        val file = toFile(path)
        if (shouldPreferPrivileged(file.path)) {
            return fallback!!.openOutput(file.path, append)
        }
        val parent = file.parentFile
        if (parent == null || !parent.exists() || !parent.isDirectory) {
            if (canFallbackPrivileged(file.path)) {
                return fallback!!.openOutput(file.path, append)
            }
            throw FileNotFoundException("Parent directory does not exist: ${file.parent}")
        }
        return FileOutputStream(file, append)
    }

    fun openOutputAtOffset(path: VfsPath.Local, offset: Long): OutputStream {
        require(offset >= 0L) { "Negative offset: $offset" }
        val file = File(path.normalized)
        val parent = file.parentFile
        if (parent == null || !parent.exists() || !parent.isDirectory) {
            throw FileNotFoundException("Parent directory does not exist: ${file.parent}")
        }
        val raf = RandomAccessFile(file, "rw")
        try {
            raf.seek(offset)
            raf.setLength(offset)
            return RandomAccessFileOutputStream(raf)
        } catch (t: Throwable) {
            runCatching { raf.close() }
            throw t
        }
    }

    override suspend fun setLastModified(path: VfsPath, epochMs: Long): Boolean {
        val file = toFile(path)
        if (shouldPreferPrivileged(file.path)) {
            return fallback!!.setLastModified(file.path, epochMs)
        }
        return file.setLastModified(epochMs)
    }

    override suspend fun space(path: VfsPath): Pair<Long, Long>? {
        val file = toFile(path)
        val probe = generateSequence(file) { it.parentFile }.firstOrNull { it.exists() } ?: return null
        val total = probe.totalSpace
        val usable = probe.usableSpace
        if (total <= 0L) return null
        return total to usable
    }

    private fun toNode(
        file: File,
        computeChildCount: Boolean,
        includeHiddenCount: Boolean = false,
    ): VfsNode {
        val nio = file.toPath()
        val isSymlink = runCatching { Files.isSymbolicLink(nio) }.getOrDefault(false)
        val symlinkTarget = if (isSymlink) {
            runCatching { Files.readSymbolicLink(nio).toString() }.getOrNull()
        } else {
            null
        }
        val isDir = file.isDirectory
        val name = if (file.path == "/") "/" else file.name
        val hasNomedia = isDir && File(file, ".nomedia").exists()
        val childCount = if (isDir && computeChildCount) {
            file.list()?.count { includeHiddenCount || !PathUtils.isHiddenName(it) } ?: -1
        } else {
            -1
        }
        val perms = buildPosixPermissions(file, isDir, isSymlink)
        return VfsNode(
            path = VfsPath.Local(file.path),
            name = name,
            isDirectory = isDir,
            size = if (isDir) 0L else file.length(),
            lastModified = file.lastModified(),
            mimeType = if (isDir) "inode/directory" else PathUtils.guessMimeType(name),
            isHidden = PathUtils.isHiddenName(name),
            canRead = file.canRead(),
            canWrite = file.canWrite(),
            isSymlink = isSymlink,
            childCount = childCount,
            hasNomedia = hasNomedia,
            posixPermissions = perms,
            symlinkTarget = symlinkTarget,
        )
    }

    private fun buildPosixPermissions(file: File, isDir: Boolean, isSymlink: Boolean): String {
        val typeChar = when {
            isSymlink -> 'l'
            isDir -> 'd'
            else -> '-'
        }
        val posix = runCatching {
            Files.getPosixFilePermissions(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        }.getOrNull()
        if (posix != null) {
            fun bit(p: PosixFilePermission, ch: Char) = if (p in posix) ch else '-'
            return buildString(10) {
                append(typeChar)
                append(bit(PosixFilePermission.OWNER_READ, 'r'))
                append(bit(PosixFilePermission.OWNER_WRITE, 'w'))
                append(bit(PosixFilePermission.OWNER_EXECUTE, 'x'))
                append(bit(PosixFilePermission.GROUP_READ, 'r'))
                append(bit(PosixFilePermission.GROUP_WRITE, 'w'))
                append(bit(PosixFilePermission.GROUP_EXECUTE, 'x'))
                append(bit(PosixFilePermission.OTHERS_READ, 'r'))
                append(bit(PosixFilePermission.OTHERS_WRITE, 'w'))
                append(bit(PosixFilePermission.OTHERS_EXECUTE, 'x'))
            }
        }
        val r = if (file.canRead()) 'r' else '-'
        val w = if (file.canWrite()) 'w' else '-'
        val x = if (file.canExecute() || isDir) 'x' else '-'
        return "$typeChar$r$w$x$r-$x---"
    }

    private class RandomAccessFileInputStream(private val raf: RandomAccessFile) : InputStream() {
        override fun read(): Int = raf.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
        override fun close() = raf.close()
    }

    private class RandomAccessFileOutputStream(private val raf: RandomAccessFile) : OutputStream() {
        override fun write(b: Int) = raf.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = raf.write(b, off, len)
        override fun close() = raf.close()
    }

    companion object {
        /**
         * Canonical-path / symlink jail check (`ARCHITECTURE.md` §4.2).
         * Verifies that [candidate] resolves canonically to [root] or a descendant of [root].
         */
        fun isInside(candidate: File, root: File): Boolean {
            return try {
                val r = root.canonicalPath
                val c = candidate.canonicalPath
                if (r == File.separator) {
                    true
                } else {
                    c == r || c.startsWith(r + File.separator)
                }
            } catch (_: IOException) {
                false
            }
        }
    }
}
