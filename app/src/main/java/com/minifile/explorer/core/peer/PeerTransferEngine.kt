package com.minifile.explorer.core.peer

import com.minifile.explorer.core.ftp.FtpAccessDeniedException
import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.MiniJson
import com.minifile.explorer.core.model.OperationKind
import com.minifile.explorer.core.model.OperationProgress
import com.minifile.explorer.core.ops.ProgressSink
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLEncoder

/**
 * Pure-JVM sender engine for App-to-App Peer Transfer (`zero android.* imports`).
 *
 * Protocol sequence over a single keep-alive [PeerHttpClient]:
 * 1. `POST /api/peer/begin?files=<n>&bytes=<total>&device=<senderName>` -> `{session}`
 * 2. Per item:
 *    - Directory: `POST /api/peer/mkdir?session=<s>&path=<relPath>&mtime=<ms>`
 *    - File: `POST /api/peer/send?session=<s>&path=<relPath>&mtime=<ms>&size=<len>&conflict=<policy>`
 *      with `Content-Length`, `Expect: 100-continue`, and 256 KB streaming chunks.
 * 3. `POST /api/peer/end?session=<s>&ok=1` (or `ok=0` on cancel/abort).
 *
 * Retry rule (`MUST-6` / `#80` note 2):
 * - Retries a file at most once ONLY when [PreContinueIOException] occurs before `100 Continue`.
 * - Any [IOException] after streaming began, or any `4xx`/`5xx`, is terminal for the session.
 */
class PeerTransferEngine(
    private val client: PeerHttpClient = PeerHttpClient(),
    private val senderDeviceNameProvider: () -> String = { "Files" },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        @Suppress("UNUSED_PARAMETER") fs: FileSystem,
        client: PeerHttpClient = PeerHttpClient(),
        senderDeviceNameProvider: () -> String = { "Files" },
    ) : this(
        client = client,
        senderDeviceNameProvider = senderDeviceNameProvider,
    )

    private class LocalCancelledException : IOException("Transfer cancelled locally")

    suspend fun send(
        peer: PeerInfo,
        items: List<PeerSendItem>,
        sink: ProgressSink = ProgressSink.NOOP,
        conflict: PeerConflictPolicy = PeerConflictPolicy.RENAME,
        senderDeviceName: String = senderDeviceNameProvider(),
    ): PeerSendResult = withContext(ioDispatcher) {
        val peerName = peerDisplayName(peer)
        if (peer.proto > PeerDiscoveryEngine.SUPPORTED_PROTO) {
            throw PeerTransferException(null, "$peerName needs newer Files")
        }
        if (items.isEmpty()) {
            return@withContext PeerSendResult(files = 0, bytes = 0L, skipped = 0, errors = emptyList())
        }
        if (sink.isCancelled()) {
            return@withContext PeerSendResult(
                files = 0,
                bytes = 0L,
                skipped = 0,
                errors = emptyList(),
                cancelled = true,
            )
        }

        val totalItems = items.size
        val fileCount = items.count { !it.isDirectory }
        val totalBytes = items.sumOf { if (it.isDirectory) 0L else it.size.coerceAtLeast(0L) }
        val cleanSender = PeerPathPolicy.sanitizeDisplayName(senderDeviceName, 64).ifEmpty { "Files" }
        val baseUrl = peer.baseUrl

        fun reportProgress(processedItems: Int, processedBytes: Long, currentItem: String) {
            sink.onProgress(
                OperationProgress(
                    id = "",
                    kind = OperationKind.SEND,
                    title = "Sending to $peerName",
                    currentItem = currentItem,
                    processedItems = processedItems,
                    totalItems = totalItems,
                    processedBytes = processedBytes,
                    totalBytes = totalBytes,
                ),
            )
        }

        reportProgress(processedItems = 0, processedBytes = 0L, currentItem = items.first().relPath)

        // 1. Begin session
        val beginUrl = "$baseUrl/api/peer/begin?files=$fileCount&bytes=$totalBytes&device=${encodeQueryValue(cleanSender)}"
        val beginResp = try {
            client.postEmpty(beginUrl)
        } catch (e: Exception) {
            if (sink.isCancelled()) {
                client.cancelActive()
                return@withContext PeerSendResult(0, 0L, 0, emptyList(), cancelled = true)
            }
            throw mapTransportError(peer, e)
        }

        if (beginResp.status !in 200..299) {
            throw mapHttpError(peer, beginResp)
        }
        val beginObj = parseJsonObj(beginResp.bodyText)
        val sessionId = beginObj?.str("session")?.takeIf { it.isNotBlank() }
            ?: throw PeerTransferException(beginResp.status, "Invalid session response from $peerName")

        var sentFiles = 0
        var sentDirs = 0
        var sentBytes = 0L
        var skippedFiles = 0
        var processedItems = 0
        val chunkBuf = ByteArray(CHUNK_BYTES)

        for (item in items) {
            if (sink.isCancelled()) {
                client.cancelActive()
                sendEndBestEffort(baseUrl, sessionId, ok = false)
                return@withContext PeerSendResult(
                    files = sentFiles,
                    bytes = sentBytes,
                    skipped = skippedFiles,
                    errors = emptyList(),
                    dirs = sentDirs,
                    cancelled = true,
                )
            }

            reportProgress(
                processedItems = processedItems,
                processedBytes = sentBytes,
                currentItem = item.relPath,
            )

            if (item.isDirectory) {
                val mkdirUrl = buildString(256) {
                    append(baseUrl).append("/api/peer/mkdir?session=").append(encodeQueryValue(sessionId))
                    append("&path=").append(encodeQueryValue(item.relPath))
                    if (item.mtime > 0L) {
                        append("&mtime=").append(item.mtime)
                    }
                }
                val mkdirResp = executeDirectoryItem(peer, mkdirUrl, baseUrl, sessionId, sink)
                    ?: return@withContext PeerSendResult(
                        files = sentFiles,
                        bytes = sentBytes,
                        skipped = skippedFiles,
                        errors = emptyList(),
                        dirs = sentDirs,
                        cancelled = true,
                    )

                if (mkdirResp.status !in 200..299) {
                    if (mkdirResp.status != 410) {
                        sendEndBestEffort(baseUrl, sessionId, ok = false)
                    }
                    throw mapHttpError(peer, mkdirResp)
                }
                sentDirs++
                processedItems++
                reportProgress(
                    processedItems = processedItems,
                    processedBytes = sentBytes,
                    currentItem = item.relPath,
                )
                continue
            }

            val expectedSize = item.size.coerceAtLeast(0L)
            val sendUrl = buildString(320) {
                append(baseUrl).append("/api/peer/send?session=").append(encodeQueryValue(sessionId))
                append("&path=").append(encodeQueryValue(item.relPath))
                append("&mtime=").append(item.mtime)
                append("&size=").append(expectedSize)
                append("&conflict=").append(conflict.wireValue)
            }

            var attempt = 0
            var fileResp: PeerHttpResponse? = null
            while (true) {
                attempt++
                try {
                    fileResp = client.postStream(
                        url = sendUrl,
                        contentLength = expectedSize,
                        contentType = "application/octet-stream",
                        expectContinue = true,
                    ) { out ->
                        var fileSent = 0L
                        item.open().use { rawIn ->
                            while (fileSent < expectedSize) {
                                if (sink.isCancelled()) {
                                    client.cancelActive()
                                    throw LocalCancelledException()
                                }
                                val toRead = minOf((expectedSize - fileSent), chunkBuf.size.toLong()).toInt()
                                val n = rawIn.read(chunkBuf, 0, toRead)
                                if (n < 0) {
                                    throw IOException(
                                        "Source stream ended prematurely for '${item.relPath}' ($fileSent/$expectedSize bytes)",
                                    )
                                }
                                if (n == 0) continue
                                if (sink.isCancelled()) {
                                    client.cancelActive()
                                    throw LocalCancelledException()
                                }
                                out.write(chunkBuf, 0, n)
                                fileSent += n
                                reportProgress(
                                    processedItems = processedItems,
                                    processedBytes = sentBytes + fileSent,
                                    currentItem = item.relPath,
                                )
                            }
                        }
                    }
                    break
                } catch (_: LocalCancelledException) {
                    client.cancelActive()
                    sendEndBestEffort(baseUrl, sessionId, ok = false)
                    return@withContext PeerSendResult(
                        files = sentFiles,
                        bytes = sentBytes,
                        skipped = skippedFiles,
                        errors = emptyList(),
                        dirs = sentDirs,
                        cancelled = true,
                    )
                } catch (preErr: PreContinueIOException) {
                    if (sink.isCancelled()) {
                        client.cancelActive()
                        sendEndBestEffort(baseUrl, sessionId, ok = false)
                        return@withContext PeerSendResult(
                            files = sentFiles,
                            bytes = sentBytes,
                            skipped = skippedFiles,
                            errors = emptyList(),
                            dirs = sentDirs,
                            cancelled = true,
                        )
                    }
                    // MUST-6 / #80 note 2: retry once ONLY when IOException happened BEFORE 100 Continue
                    if (attempt == 1) {
                        continue
                    }
                    sendEndAndThrowIfRejected(peer, baseUrl, sessionId, preErr)
                    throw mapTransportError(peer, preErr)
                } catch (postErr: Exception) {
                    if (sink.isCancelled()) {
                        client.cancelActive()
                        sendEndBestEffort(baseUrl, sessionId, ok = false)
                        return@withContext PeerSendResult(
                            files = sentFiles,
                            bytes = sentBytes,
                            skipped = skippedFiles,
                            errors = emptyList(),
                            dirs = sentDirs,
                            cancelled = true,
                        )
                    }
                    // Post-100-Continue IOException is terminal; check if receiver cancelled the session (410)
                    sendEndAndThrowIfRejected(peer, baseUrl, sessionId, postErr)
                    throw mapTransportError(peer, postErr, midTransfer = true)
                }
            }

            val resp = fileResp!!
            if (resp.status !in 200..299) {
                if (resp.status != 410) {
                    sendEndBestEffort(baseUrl, sessionId, ok = false)
                }
                throw mapHttpError(peer, resp)
            }

            val respObj = parseJsonObj(resp.bodyText)
            val wasSkipped = respObj?.bool("skipped", false) == true
            if (wasSkipped) {
                skippedFiles++
            } else {
                sentFiles++
                sentBytes += expectedSize
            }
            processedItems++
            reportProgress(
                processedItems = processedItems,
                processedBytes = sentBytes,
                currentItem = item.relPath,
            )
        }

        // 3. End session
        val endUrl = "$baseUrl/api/peer/end?session=${encodeQueryValue(sessionId)}&ok=1"
        val endResp = runCatching { client.postEmpty(endUrl) }.getOrNull()
        if (endResp != null && endResp.status == 410) {
            throw PeerTransferException(410, "Rejected by $peerName")
        }

        PeerSendResult(
            files = sentFiles,
            bytes = sentBytes,
            skipped = skippedFiles,
            errors = emptyList(),
            dirs = sentDirs,
            cancelled = false,
        )
    }

    private fun executeDirectoryItem(
        peer: PeerInfo,
        mkdirUrl: String,
        baseUrl: String,
        sessionId: String,
        sink: ProgressSink,
    ): PeerHttpResponse? {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return client.postEmpty(mkdirUrl)
            } catch (e: Exception) {
                if (sink.isCancelled()) {
                    client.cancelActive()
                    sendEndBestEffort(baseUrl, sessionId, ok = false)
                    return null
                }
                if (e is IOException && e !is FtpAccessDeniedException && attempt == 1) {
                    continue
                }
                sendEndAndThrowIfRejected(peer, baseUrl, sessionId, e)
                throw mapTransportError(peer, e)
            }
        }
    }

    private fun sendEndBestEffort(baseUrl: String, sessionId: String, ok: Boolean) {
        val okFlag = if (ok) "1" else "0"
        val endUrl = "$baseUrl/api/peer/end?session=${encodeQueryValue(sessionId)}&ok=$okFlag"
        runCatching {
            client.postEmpty(endUrl, timeoutMs = 1_500, connectTimeoutOverrideMs = 1_500)
        }
    }

    private fun sendEndAndThrowIfRejected(
        peer: PeerInfo,
        baseUrl: String,
        sessionId: String,
        cause: Throwable,
    ) {
        val endUrl = "$baseUrl/api/peer/end?session=${encodeQueryValue(sessionId)}&ok=0"
        val endResp = runCatching {
            client.postEmpty(endUrl, timeoutMs = 2_000, connectTimeoutOverrideMs = 1_500)
        }.getOrNull()
        if (endResp?.status == 410) {
            throw PeerTransferException(410, "Rejected by ${peerDisplayName(peer)}", cause)
        }
    }

    companion object {
        const val CHUNK_BYTES = 256 * 1024

        /**
         * Walks VFS [roots] into flat [PeerSendItem]s with `relPath` rooted at each top-level node's name,
         * preserving empty directories (`isDirectory = true`) and skipping symlinks.
         */
        suspend fun collectSendItems(
            fs: FileSystem,
            roots: List<VfsPath>,
            includeHidden: Boolean = true,
        ): List<PeerSendItem> {
            val result = ArrayList<PeerSendItem>()
            val usedTopNames = HashSet<String>()

            suspend fun walkDir(dirNode: VfsNode, dirRelPath: String) {
                val children = fs.list(dirNode.path, includeHidden = includeHidden)
                    .filter { !it.isSymlink }
                if (children.isEmpty()) {
                    result.add(
                        PeerSendItem(
                            relPath = dirRelPath,
                            size = 0L,
                            mtime = dirNode.lastModified,
                            isDirectory = true,
                        ),
                    )
                    return
                }
                for (child in children) {
                    val childRel = "$dirRelPath/${child.name}"
                    if (child.isDirectory) {
                        walkDir(child, childRel)
                    } else {
                        val targetPath = child.path
                        result.add(
                            PeerSendItem(
                                relPath = childRel,
                                size = child.size.coerceAtLeast(0L),
                                mtime = child.lastModified,
                                isDirectory = false,
                                open = { fs.openInput(targetPath, 0L) },
                            ),
                        )
                    }
                }
            }

            for (root in roots) {
                val node = fs.stat(root) ?: continue
                if (node.isSymlink) continue
                val baseName = node.name.ifBlank { root.displayName.ifBlank { "file" } }
                val topName = com.minifile.explorer.core.vfs.PathUtils.uniqueName(
                    desired = baseName,
                    existingNames = usedTopNames,
                    isDirectory = node.isDirectory,
                )
                usedTopNames.add(topName)

                if (node.isDirectory) {
                    walkDir(node, topName)
                } else {
                    val targetPath = node.path
                    result.add(
                        PeerSendItem(
                            relPath = topName,
                            size = node.size.coerceAtLeast(0L),
                            mtime = node.lastModified,
                            isDirectory = false,
                            open = { fs.openInput(targetPath, 0L) },
                        ),
                    )
                }
            }
            return result
        }

        internal fun encodeQueryValue(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        internal fun peerDisplayName(peer: PeerInfo): String =
            PeerPathPolicy.sanitizeDisplayName(peer.name, 64).ifEmpty { peer.host }

        internal fun mapHttpError(peer: PeerInfo, resp: PeerHttpResponse): PeerTransferException {
            val name = peerDisplayName(peer)
            val errObj = parseJsonObj(resp.bodyText)
            val serverErr = errObj?.str("error")?.trim().orEmpty()
            val code = errObj?.str("code")?.trim().orEmpty()

            val msg = when (resp.status) {
                401 -> "$name requires a PIN — update Files"
                403 -> {
                    if (code == "read_only" || serverErr.contains("read-only", ignoreCase = true)) {
                        "$name is read-only (Transfer ▸ Settings)"
                    } else {
                        "Access denied by $name${if (serverErr.isNotEmpty()) ": $serverErr" else ""}"
                    }
                }
                404 -> "$name needs Files 1.0.6+"
                409 -> "Conflict on $name${if (serverErr.isNotEmpty()) ": $serverErr" else ""}"
                410 -> "Rejected by $name"
                411 -> "Length required by $name"
                503 -> "$name is busy — try again shortly"
                507 -> "Not enough space on $name"
                else -> {
                    if (serverErr.isNotEmpty()) {
                        "$name: $serverErr (HTTP ${resp.status})"
                    } else {
                        "$name returned HTTP ${resp.status}"
                    }
                }
            }
            return PeerTransferException(resp.status, msg)
        }

        internal fun mapTransportError(
            peer: PeerInfo,
            cause: Throwable,
            midTransfer: Boolean = false,
        ): PeerTransferException {
            if (cause is PeerTransferException) return cause
            val name = peerDisplayName(peer)
            if (cause is FtpAccessDeniedException) {
                return PeerTransferException(null, "Only local network (LAN) addresses are allowed", cause)
            }
            val msg = if (midTransfer) {
                "Connection to $name lost"
            } else {
                "$name not reachable"
            }
            return PeerTransferException(null, msg, cause)
        }

        private fun parseJsonObj(raw: String): JsonValue.Obj? {
            if (raw.isBlank()) return null
            return try {
                MiniJson.parse(raw) as? JsonValue.Obj
            } catch (_: Exception) {
                null
            }
        }
    }
}
