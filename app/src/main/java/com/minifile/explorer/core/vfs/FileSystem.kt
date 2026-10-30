package com.minifile.explorer.core.vfs

import java.io.InputStream
import java.io.OutputStream

interface FileSystem {
    fun supports(path: VfsPath): Boolean
    suspend fun stat(path: VfsPath): VfsNode?
    suspend fun list(dir: VfsPath, includeHidden: Boolean): List<VfsNode>
    suspend fun child(dir: VfsPath, name: String): VfsPath
    suspend fun parent(path: VfsPath): VfsPath?
    suspend fun exists(path: VfsPath): Boolean
    suspend fun createDirectory(dir: VfsPath, name: String): VfsPath
    suspend fun createFile(
        dir: VfsPath,
        name: String,
        mime: String = "application/octet-stream",
    ): VfsPath

    suspend fun delete(path: VfsPath): Boolean
    suspend fun rename(path: VfsPath, newName: String): VfsPath
    suspend fun moveWithinSameBackend(src: VfsPath, dstDir: VfsPath): VfsPath?
    fun openInput(path: VfsPath, offset: Long = 0L): InputStream
    fun openOutput(path: VfsPath, append: Boolean = false): OutputStream
    suspend fun setLastModified(path: VfsPath, epochMs: Long): Boolean
    suspend fun space(path: VfsPath): Pair<Long, Long>?
}

class FileSystemRepository(
    private val backends: List<FileSystem>,
) : FileSystem {

    private fun backendFor(path: VfsPath): FileSystem =
        backends.firstOrNull { it.supports(path) }
            ?: throw IllegalArgumentException("No FileSystem backend supports $path")

    override fun supports(path: VfsPath): Boolean =
        backends.any { it.supports(path) }

    override suspend fun stat(path: VfsPath): VfsNode? =
        backendFor(path).stat(path)

    override suspend fun list(dir: VfsPath, includeHidden: Boolean): List<VfsNode> =
        backendFor(dir).list(dir, includeHidden)

    override suspend fun child(dir: VfsPath, name: String): VfsPath =
        backendFor(dir).child(dir, name)

    override suspend fun parent(path: VfsPath): VfsPath? =
        backendFor(path).parent(path)

    override suspend fun exists(path: VfsPath): Boolean =
        backendFor(path).exists(path)

    override suspend fun createDirectory(dir: VfsPath, name: String): VfsPath =
        backendFor(dir).createDirectory(dir, name)

    override suspend fun createFile(dir: VfsPath, name: String, mime: String): VfsPath =
        backendFor(dir).createFile(dir, name, mime)

    override suspend fun delete(path: VfsPath): Boolean =
        backendFor(path).delete(path)

    override suspend fun rename(path: VfsPath, newName: String): VfsPath =
        backendFor(path).rename(path, newName)

    override suspend fun moveWithinSameBackend(src: VfsPath, dstDir: VfsPath): VfsPath? {
        val srcBackend = backendFor(src)
        val dstBackend = backendFor(dstDir)
        if (srcBackend !== dstBackend) return null
        return srcBackend.moveWithinSameBackend(src, dstDir)
    }

    override fun openInput(path: VfsPath, offset: Long): InputStream =
        backendFor(path).openInput(path, offset)

    override fun openOutput(path: VfsPath, append: Boolean): OutputStream =
        backendFor(path).openOutput(path, append)

    override suspend fun setLastModified(path: VfsPath, epochMs: Long): Boolean =
        backendFor(path).setLastModified(path, epochMs)

    override suspend fun space(path: VfsPath): Pair<Long, Long>? =
        backendFor(path).space(path)
}
