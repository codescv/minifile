package com.minifile.explorer.core.peer

import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.MiniJson
import com.minifile.explorer.core.model.OperationKind
import com.minifile.explorer.core.model.OperationProgress
import com.minifile.explorer.core.ops.ProgressSink
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Malformed / out-of-protocol `MFL2` traffic (bad length header, non-JSON frame, unexpected `t`). */
class Mfl2ProtocolException(message: String) : IOException(message)

/**
 * `MFL2` v1 framing shared byte-for-byte with iOS (`zero android.* imports`).
 *
 * Every control frame on the reliable L2CAP byte stream is `[uint32 big-endian N, 1..65536][N bytes UTF-8 JSON]`.
 * A `file` frame is immediately followed by exactly `size` raw, unframed bytes.
 */
object Mfl2Codec {
    const val VERSION = 1
    const val MAX_FRAME_BYTES = 65_536

    fun encodeFrame(obj: JsonValue.Obj): ByteArray {
        val body = MiniJson.stringify(obj).toByteArray(Charsets.UTF_8)
        if (body.isEmpty() || body.size > MAX_FRAME_BYTES) {
            throw Mfl2ProtocolException("MFL2 frame too large: ${body.size} bytes")
        }
        val out = ByteArray(4 + body.size)
        val n = body.size
        out[0] = (n ushr 24).toByte()
        out[1] = (n ushr 16).toByte()
        out[2] = (n ushr 8).toByte()
        out[3] = n.toByte()
        System.arraycopy(body, 0, out, 4, n)
        return out
    }

    fun writeFrame(out: OutputStream, obj: JsonValue.Obj) {
        out.write(encodeFrame(obj))
        out.flush()
    }

    /** Reads one frame; throws [EOFException] on a clean/abrupt close and [Mfl2ProtocolException] on bad input. */
    fun readFrame(input: InputStream): JsonValue.Obj {
        val hdr = ByteArray(4)
        readFully(input, hdr, 4)
        val n = ((hdr[0].toLong() and 0xFF) shl 24) or ((hdr[1].toLong() and 0xFF) shl 16) or
            ((hdr[2].toLong() and 0xFF) shl 8) or (hdr[3].toLong() and 0xFF)
        if (n <= 0L || n > MAX_FRAME_BYTES) throw Mfl2ProtocolException("Invalid MFL2 frame length $n")
        val body = ByteArray(n.toInt())
        readFully(input, body, body.size)
        return try {
            MiniJson.parse(String(body, Charsets.UTF_8)) as? JsonValue.Obj
        } catch (_: Exception) {
            null
        } ?: throw Mfl2ProtocolException("MFL2 frame is not a JSON object")
    }

    internal fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val r = input.read(buf, off, len - off)
            if (r < 0) throw EOFException("MFL2 stream closed")
            off += r
        }
    }

    fun begin(tok: String, id: String, name: String, files: Int, bytes: Long): JsonValue.Obj = MiniJson.obj(
        "t" to JsonValue.Str("begin"),
        "v" to JsonValue.Num(VERSION),
        "tok" to JsonValue.Str(tok),
        "id" to JsonValue.Str(id),
        "name" to JsonValue.Str(name),
        "files" to JsonValue.Num(files),
        "bytes" to JsonValue.Num(bytes),
    )

    fun mkdir(path: String): JsonValue.Obj = MiniJson.obj("t" to JsonValue.Str("mkdir"), "path" to JsonValue.Str(path))

    fun file(path: String, size: Long, mtime: Long): JsonValue.Obj = MiniJson.obj(
        "t" to JsonValue.Str("file"),
        "path" to JsonValue.Str(path),
        "size" to JsonValue.Num(size),
        "mtime" to JsonValue.Num(mtime),
    )

    fun end(ok: Boolean): JsonValue.Obj = MiniJson.obj("t" to JsonValue.Str("end"), "ok" to JsonValue.Bool(ok))

    fun ok(saved: String? = null): JsonValue.Obj =
        if (saved == null) {
            MiniJson.obj("t" to JsonValue.Str("ok"))
        } else {
            MiniJson.obj("t" to JsonValue.Str("ok"), "saved" to JsonValue.Str(saved))
        }

    fun err(msg: String): JsonValue.Obj = MiniJson.obj("t" to JsonValue.Str("err"), "msg" to JsonValue.Str(msg))

    fun done(ok: Boolean, files: Int, bytes: Long): JsonValue.Obj = MiniJson.obj(
        "t" to JsonValue.Str("done"),
        "ok" to JsonValue.Bool(ok),
        "files" to JsonValue.Num(files),
        "bytes" to JsonValue.Num(bytes),
    )
}

/** Single-use, constant-time token check for the `MFL2` `begin` handshake (the accepted BLE `hello.tok`). */
class SingleUseToken(private val expected: String) {
    private val used = AtomicBoolean(false)

    /** `true` exactly once, for a valid token equal to the expected one. */
    fun consume(actual: String?): Boolean {
        if (actual == null || !HotspotLinkCodec.isValidToken(expected)) return false
        val match = HotspotLinkCodec.constantTimeEquals(expected, actual)
        return match && used.compareAndSet(false, true)
    }
}

/** Receiver-side callbacks (UI progress, HTTP event log, media scan). All optional. */
interface L2capReceiveListener {
    fun onBegin(senderId: String, senderName: String, files: Int, bytes: Long) {}
    fun onProgress(processedFiles: Int, totalFiles: Int, processedBytes: Long, totalBytes: Long, currentItem: String) {}
    fun onFileSaved(savedRelPath: String, path: VfsPath, bytes: Long, durationMs: Long) {}

    companion object {
        val NOOP = object : L2capReceiveListener {}
    }
}

data class L2capReceiveResult(
    val ok: Boolean,
    val senderId: String,
    val senderName: String,
    val files: Int,
    val bytes: Long,
    val dirs: Int = 0,
    val saved: List<String> = emptyList(),
    val cancelled: Boolean = false,
    val error: String? = null,
)

/**
 * Pure-JVM `MFL2` v1 engine for zero-QR, zero-password BLE L2CAP CoC transfers (`zero android.* imports`).
 * Works on any reliable byte stream (Android `BluetoothSocket`, piped streams in tests).
 *
 * 1. `begin{v,tok,id,name,files,bytes}` → `ok` | `err`
 * 2. `mkdir{path}` → `ok` | `err`
 * 3. `file{path,size,mtime}` + `size` raw bytes → `ok{saved}` | `err`
 * 4. `end{ok}` → `done{ok,files,bytes}`, then close.
 */
class L2capPeerTransferEngine(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val chunkBytes: Int = CHUNK_BYTES,
) {
    private class LocalCancelledException : IOException("Transfer cancelled locally")

    // =============================================================================================
    // Sender
    // =============================================================================================

    suspend fun send(
        input: InputStream,
        output: OutputStream,
        token: String,
        senderId: String,
        senderName: String,
        peerName: String,
        items: List<PeerSendItem>,
        sink: ProgressSink = ProgressSink.NOOP,
    ): PeerSendResult = withContext(ioDispatcher) {
        if (items.isEmpty()) return@withContext PeerSendResult(0, 0L, 0, emptyList())
        if (sink.isCancelled()) return@withContext PeerSendResult(0, 0L, 0, emptyList(), cancelled = true)

        val totalItems = items.size
        val fileCount = items.count { !it.isDirectory }
        val totalBytes = items.sumOf { if (it.isDirectory) 0L else it.size.coerceAtLeast(0L) }
        val cleanSender = PeerPathPolicy.sanitizeDisplayName(senderName, 64).ifEmpty { "Files" }

        var sentFiles = 0
        var sentDirs = 0
        var sentBytes = 0L
        var processed = 0

        fun report(bytesNow: Long, current: String) {
            sink.onProgress(
                OperationProgress(
                    id = "",
                    kind = OperationKind.SEND,
                    title = "Sending to $peerName",
                    currentItem = current,
                    processedItems = processed,
                    totalItems = totalItems,
                    processedBytes = bytesNow,
                    totalBytes = totalBytes,
                ),
            )
        }

        fun cancelledResult() = PeerSendResult(sentFiles, sentBytes, 0, emptyList(), dirs = sentDirs, cancelled = true)

        fun expectOk(reply: JsonValue.Obj): JsonValue.Obj {
            when (reply.str("t")) {
                "ok" -> return reply
                "err" -> throw PeerTransferException(null, "$peerName: ${reply.str("msg")?.ifBlank { null } ?: "transfer refused"}")
                else -> throw Mfl2ProtocolException("Unexpected MFL2 reply '${reply.str("t")}'")
            }
        }

        try {
            report(0L, items.first().relPath)
            Mfl2Codec.writeFrame(output, Mfl2Codec.begin(token, senderId, cleanSender, fileCount, totalBytes))
            expectOk(Mfl2Codec.readFrame(input))

            val buf = ByteArray(chunkBytes)
            for (item in items) {
                if (sink.isCancelled()) {
                    runCatching { Mfl2Codec.writeFrame(output, Mfl2Codec.end(false)) }
                    return@withContext cancelledResult()
                }
                report(sentBytes, item.relPath)
                if (item.isDirectory) {
                    Mfl2Codec.writeFrame(output, Mfl2Codec.mkdir(item.relPath))
                    expectOk(Mfl2Codec.readFrame(input))
                    sentDirs++
                    processed++
                    continue
                }
                val size = item.size.coerceAtLeast(0L)
                Mfl2Codec.writeFrame(output, Mfl2Codec.file(item.relPath, size, item.mtime.coerceAtLeast(0L)))
                var fileSent = 0L
                if (size > 0L) {
                    item.open().use { src ->
                        while (fileSent < size) {
                            if (sink.isCancelled()) throw LocalCancelledException()
                            val want = minOf(size - fileSent, buf.size.toLong()).toInt()
                            val n = src.read(buf, 0, want)
                            if (n < 0) {
                                throw PeerTransferException(
                                    null,
                                    "Source ended early for '${item.relPath}' ($fileSent/$size bytes)",
                                )
                            }
                            if (n == 0) continue
                            output.write(buf, 0, n)
                            fileSent += n
                            report(sentBytes + fileSent, item.relPath)
                        }
                    }
                }
                output.flush()
                expectOk(Mfl2Codec.readFrame(input))
                sentFiles++
                sentBytes += size
                processed++
                report(sentBytes, item.relPath)
            }

            Mfl2Codec.writeFrame(output, Mfl2Codec.end(true))
            val done = Mfl2Codec.readFrame(input)
            when (done.str("t")) {
                "done" -> if (!done.bool("ok", false)) {
                    throw PeerTransferException(null, "$peerName did not save every file")
                }
                "err" -> throw PeerTransferException(null, "$peerName: ${done.str("msg").orEmpty().ifBlank { "transfer failed" }}")
                else -> throw Mfl2ProtocolException("Unexpected MFL2 reply '${done.str("t")}'")
            }
            PeerSendResult(sentFiles, sentBytes, 0, emptyList(), dirs = sentDirs)
        } catch (_: LocalCancelledException) {
            cancelledResult()
        } catch (e: PeerTransferException) {
            if (sink.isCancelled()) cancelledResult() else throw e
        } catch (e: IOException) {
            if (sink.isCancelled()) {
                cancelledResult()
            } else {
                throw PeerTransferException(null, "Bluetooth connection to $peerName lost", e)
            }
        }
    }

    // =============================================================================================
    // Receiver
    // =============================================================================================

    /**
     * Serves exactly one `MFL2` session from [input]/[output], saving into `<[root]>/<[inboxDir]>/…` with
     * rename-on-conflict (never overwrites). Never throws for protocol / IO failures: the outcome is in the result.
     */
    @Suppress("UNREACHABLE_CODE")
    suspend fun receive(
        input: InputStream,
        output: OutputStream,
        token: SingleUseToken,
        fs: FileSystem,
        root: VfsPath,
        inboxDir: String = DEFAULT_INBOX,
        listener: L2capReceiveListener = L2capReceiveListener.NOOP,
        isCancelled: () -> Boolean = { false },
    ): L2capReceiveResult = withContext(ioDispatcher) {
        val fatSafe = root is VfsPath.Saf
        var senderId = ""
        var senderName = ""
        var totalFiles = 0
        var totalBytes = 0L
        var files = 0
        var dirs = 0
        var bytes = 0L
        val saved = ArrayList<String>()

        fun result(ok: Boolean, error: String? = null, cancelled: Boolean = false) = L2capReceiveResult(
            ok = ok,
            senderId = senderId,
            senderName = senderName,
            files = files,
            bytes = bytes,
            dirs = dirs,
            saved = saved.toList(),
            cancelled = cancelled,
            error = error,
        )

        fun replyErr(msg: String) {
            runCatching { Mfl2Codec.writeFrame(output, Mfl2Codec.err(msg)) }
        }

        try {
            // 1) Handshake
            val begin = Mfl2Codec.readFrame(input)
            if (begin.str("t") != "begin") {
                replyErr("Expected begin")
                return@withContext result(false, "Unexpected MFL2 handshake")
            }
            if (begin.int("v", 0) != Mfl2Codec.VERSION) {
                replyErr("Unsupported MFL2 version")
                return@withContext result(false, "Unsupported MFL2 version")
            }
            if (!token.consume(begin.str("tok"))) {
                replyErr("Invalid or expired session token")
                return@withContext result(false, "Invalid session token")
            }
            senderId = begin.str("id").orEmpty().take(128)
            senderName = PeerPathPolicy.sanitizeDisplayName(begin.str("name").orEmpty(), 64).ifEmpty { "Nearby device" }
            totalFiles = begin.int("files", 0).coerceAtLeast(0)
            totalBytes = begin.long("bytes", 0L).coerceAtLeast(0L)
            val inbox = try {
                ensureDirChain(fs, root, inboxSegments(inboxDir, fatSafe))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                replyErr("Receiver storage unavailable")
                return@withContext result(false, e.message ?: "Receiver storage unavailable")
            }
            Mfl2Codec.writeFrame(output, Mfl2Codec.ok())
            listener.onBegin(senderId, senderName, totalFiles, totalBytes)
            listener.onProgress(0, totalFiles, 0L, totalBytes, "")

            val buf = ByteArray(chunkBytes)
            while (true) {
                if (isCancelled()) {
                    replyErr("Cancelled by receiver")
                    return@withContext result(false, "Cancelled", cancelled = true)
                }
                val frame = Mfl2Codec.readFrame(input)
                when (frame.str("t")) {
                    "mkdir" -> {
                        val segs = try {
                            PeerPathPolicy.validateDecodedRelPath(frame.str("path").orEmpty(), fatSafe)
                        } catch (e: IOException) {
                            replyErr(e.message ?: "Invalid path")
                            return@withContext result(false, e.message ?: "Invalid path")
                        }
                        try {
                            ensureDirChain(fs, inbox, segs)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            replyErr(e.message ?: "Could not create folder")
                            return@withContext result(false, e.message ?: "Could not create folder")
                        }
                        dirs++
                        Mfl2Codec.writeFrame(output, Mfl2Codec.ok())
                    }

                    "file" -> {
                        val rawPath = frame.str("path").orEmpty()
                        val size = frame.long("size", -1L)
                        if (size < 0L) {
                            replyErr("Invalid size")
                            return@withContext result(false, "Invalid size")
                        }
                        val mtime = frame.long("mtime", 0L)
                        val outcome = receiveFile(
                            input = input,
                            fs = fs,
                            inbox = inbox,
                            rawPath = rawPath,
                            size = size,
                            mtime = mtime,
                            fatSafe = fatSafe,
                            buf = buf,
                            isCancelled = isCancelled,
                        ) { written ->
                            listener.onProgress(files, totalFiles, bytes + written, totalBytes, rawPath)
                        }
                        when (outcome) {
                            is FileOutcome.Saved -> {
                                files++
                                bytes += size
                                saved += outcome.savedRel
                                listener.onFileSaved(outcome.savedRel, outcome.path, size, outcome.durationMs)
                                listener.onProgress(files, totalFiles, bytes, totalBytes, rawPath)
                                Mfl2Codec.writeFrame(output, Mfl2Codec.ok(outcome.savedRel))
                            }
                            is FileOutcome.Rejected -> {
                                // The raw bytes were drained, so the stream is still in sync: report and close.
                                replyErr(outcome.message)
                                return@withContext result(false, outcome.message)
                            }
                            FileOutcome.Cancelled -> {
                                replyErr("Cancelled by receiver")
                                return@withContext result(false, "Cancelled", cancelled = true)
                            }
                        }
                    }

                    "end" -> {
                        val ok = frame.bool("ok", false)
                        runCatching { Mfl2Codec.writeFrame(output, Mfl2Codec.done(ok, files, bytes)) }
                        return@withContext if (ok) {
                            result(true)
                        } else {
                            result(false, "$senderName cancelled", cancelled = true)
                        }
                    }

                    else -> {
                        replyErr("Unexpected frame")
                        return@withContext result(false, "Unexpected MFL2 frame '${frame.str("t")}'")
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            result(false, "Unexpected end of MFL2 session")
        } catch (e: Mfl2ProtocolException) {
            replyErr(e.message ?: "Protocol error")
            result(false, e.message ?: "Protocol error")
        } catch (e: IOException) {
            if (isCancelled()) {
                result(false, "Cancelled", cancelled = true)
            } else {
                result(false, if (senderName.isEmpty()) "Bluetooth connection lost" else "Connection to $senderName lost")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            replyErr("Receiver error")
            result(false, e.message ?: "Receiver error")
        }
    }

    private sealed class FileOutcome {
        class Saved(val savedRel: String, val path: VfsPath, val durationMs: Long) : FileOutcome()
        class Rejected(val message: String) : FileOutcome()
        data object Cancelled : FileOutcome()
    }

    private suspend fun receiveFile(
        input: InputStream,
        fs: FileSystem,
        inbox: VfsPath,
        rawPath: String,
        size: Long,
        mtime: Long,
        fatSafe: Boolean,
        buf: ByteArray,
        isCancelled: () -> Boolean,
        onBytes: (Long) -> Unit,
    ): FileOutcome {
        val t0 = System.currentTimeMillis()
        val segs = try {
            PeerPathPolicy.validateDecodedRelPath(rawPath, fatSafe)
        } catch (e: IOException) {
            return drainThen(input, size, buf, isCancelled) { FileOutcome.Rejected(e.message ?: "Invalid path") }
        }
        val dirSegs = segs.dropLast(1)
        val leaf = segs.last()
        val parent = try {
            ensureDirChain(fs, inbox, dirSegs)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return drainThen(input, size, buf, isCancelled) { FileOutcome.Rejected(e.message ?: "Could not create folder") }
        }
        val tempName = ".mfl2-${UUID.randomUUID().toString().replace("-", "").take(12)}.part"
        val temp = try {
            fs.createFile(parent, tempName, "application/octet-stream")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return drainThen(input, size, buf, isCancelled) { FileOutcome.Rejected(e.message ?: "Could not create file") }
        }
        var finalPath: VfsPath? = null
        try {
            var written = 0L
            var writeError: IOException? = null
            val out = try {
                fs.openOutput(temp, append = false)
            } catch (e: Exception) {
                writeError = e as? IOException ?: IOException(e.message, e)
                null
            }
            try {
                while (written < size) {
                    if (isCancelled()) return FileOutcome.Cancelled
                    val want = minOf(size - written, buf.size.toLong()).toInt()
                    val n = input.read(buf, 0, want)
                    if (n < 0) throw EOFException("MFL2 stream closed mid-file")
                    if (n == 0) continue
                    if (writeError == null) {
                        try {
                            out?.write(buf, 0, n)
                        } catch (e: IOException) {
                            // Keep draining so the stream stays framed, then report the storage error.
                            writeError = e
                        }
                    }
                    written += n
                    onBytes(written)
                }
                if (writeError == null) out?.flush()
            } finally {
                runCatching { out?.close() }
            }
            writeError?.let { return FileOutcome.Rejected("Could not save '$leaf': ${it.message ?: "storage error"}") }

            val existing = fs.list(parent, includeHidden = true).mapTo(HashSet()) { it.name }
            existing.remove(tempName)
            val chosen = PathUtils.uniqueName(leaf, existing, isDirectory = false, caseInsensitive = fatSafe)
            val moved = fs.rename(temp, chosen)
            finalPath = moved
            if (mtime > 0L) runCatching { fs.setLastModified(moved, mtime) }
            val savedRel = (dirSegs + chosen).joinToString("/")
            return FileOutcome.Saved(savedRel, moved, (System.currentTimeMillis() - t0).coerceAtLeast(1L))
        } finally {
            if (finalPath == null) runCatching { fs.delete(temp) }
        }
    }

    private inline fun drainThen(
        input: InputStream,
        size: Long,
        buf: ByteArray,
        isCancelled: () -> Boolean,
        outcome: () -> FileOutcome,
    ): FileOutcome {
        var left = size
        while (left > 0L) {
            if (isCancelled()) return FileOutcome.Cancelled
            val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (n < 0) throw EOFException("MFL2 stream closed mid-file")
            left -= n
        }
        return outcome()
    }

    private fun inboxSegments(inboxDir: String, fatSafe: Boolean): List<String> {
        val raw = inboxDir.trim('/')
        return if (raw.isEmpty()) emptyList() else PeerPathPolicy.validateDecodedRelPath(raw, fatSafe)
    }

    /** Creates / walks `base/seg1/seg2…`, refusing symlinks and file-vs-folder collisions. */
    private suspend fun ensureDirChain(fs: FileSystem, base: VfsPath, segments: List<String>): VfsPath {
        var cur = base
        for (seg in segments) {
            val next = fs.child(cur, seg)
            val st = fs.stat(next)
            cur = when {
                st == null -> try {
                    fs.createDirectory(cur, seg)
                } catch (e: IOException) {
                    // Lost a race with a concurrent receive: accept the folder if it now exists.
                    fs.stat(next)?.takeIf { it.isDirectory && !it.isSymlink }?.path ?: throw e
                }
                st.isSymlink -> throw IOException("Symbolic link folders are not permitted")
                !st.isDirectory -> throw IOException("A file already exists at '$seg'")
                else -> st.path
            }
        }
        return cur
    }

    companion object {
        const val CHUNK_BYTES = 32 * 1024
        const val DEFAULT_INBOX = "Received"
    }
}
