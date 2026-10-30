package com.minifile.explorer.core.ops

import com.minifile.explorer.core.model.OperationKind
import com.minifile.explorer.core.model.OperationProgress
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.VfsPath
import java.io.IOException
import java.security.MessageDigest

class ChecksumCalculator(
    private val fs: FileSystem,
) {
    private val bufferSize = 256 * 1024

    suspend fun digest(
        path: VfsPath,
        algorithm: String = "SHA-256",
        sink: ProgressSink = ProgressSink.NOOP,
    ): String {
        val node = fs.stat(path) ?: throw IOException("File not found: ${path.displayName}")
        if (node.isDirectory) throw IOException("Cannot compute checksum of a directory")

        val md = MessageDigest.getInstance(algorithm)
        var processed = 0L
        fs.openInput(path).use { input ->
            val buf = ByteArray(bufferSize)
            while (true) {
                if (sink.isCancelled()) {
                    throw IOException("Checksum cancelled")
                }
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) {
                    md.update(buf, 0, n)
                    processed += n
                    sink.onProgress(
                        OperationProgress(
                            id = "checksum-$algorithm",
                            kind = OperationKind.COMPUTE_SIZE,
                            title = "Computing $algorithm",
                            currentItem = node.name,
                            processedBytes = processed,
                            totalBytes = node.size,
                        ),
                    )
                }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
