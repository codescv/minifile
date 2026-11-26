package com.minifile.explorer.core.peer

import com.minifile.explorer.core.i18n.AppI18n
import com.minifile.explorer.core.i18n.Str
import com.minifile.explorer.core.diag.Diag
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
        /** Invoked once `begin` returned a session (bytes may follow); used by re-announce retry logic. */
        onSessionStarted: (sessionId: String) -> Unit = {},
    ): PeerSendResult = withContext(ioDispatcher) {
        val peerName = peerDisplayName(peer)
        if (peer.proto > PeerDiscoveryEngine.SUPPORTED_PROTO) {
            throw PeerTransferException(null, AppI18n.current().format(Str.PEER_ERR_NEEDS_NEWER_FMT, peerName))
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
                    title = AppI18n.current().format(Str.SENDING_TO, peerName),
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
        val beginUrl = buildString(256) {
            append(baseUrl).append("/api/peer/begin?files=").append(fileCount)
            append("&bytes=").append(totalBytes)
            append("&device=").append(encodeQueryValue(cleanSender))
            val tok = peer.linkToken?.trim().orEmpty()
            if (tok.isNotEmpty()) append("&tok=").append(encodeQueryValue(tok))
        }
        Diag.log(Diag.PEER) {
            "send begin -> ${peer.host}:${peer.port} files=$fileCount bytes=$totalBytes os=${peer.platform} " +
                "tok=${peer.linkToken.orEmpty()} timeoutMs=${beginTimeoutMs(peer)}"
        }
        val beginResp = try {
            // D2: iOS `begin` blocks up to 60 s on its consent sheet — wait well past it. A BLE-negotiated session
            // (`linkToken`) is normally pre-approved, so a silent receiver is detected sooner.
            client.postEmpty(beginUrl, timeoutMs = beginTimeoutMs(peer))
        } catch (e: Exception) {
            Diag.log(Diag.PEER) {
                "send begin -> ${peer.host}:${peer.port} failed ${e.javaClass.simpleName}: ${e.message.orEmpty()} " +
                    "cancelled=${sink.isCancelled()}"
            }
            if (sink.isCancelled()) {
                client.cancelActive()
                return@withContext PeerSendResult(0, 0L, 0, emptyList(), cancelled = true)
            }
            throw mapTransportError(peer, e, beforeSession = true)
        }

        Diag.log(Diag.PEER) { "send begin <- ${peer.host}:${peer.port} status=${beginResp.status} body=${beginResp.bodyText.take(200)}" }
        if (beginResp.status !in 200..299) {
            throw mapHttpError(peer, beginResp, beforeSession = true)
        }
        val beginObj = parseJsonObj(beginResp.bodyText)
        val sessionId = beginObj?.str("session")?.takeIf { it.isNotBlank() }
            ?: throw PeerTransferException(beginResp.status, AppI18n.current().format(Str.PEER_ERR_INVALID_SESSION_FMT, peerName))
        onSessionStarted(sessionId)

        var sentFiles = 0
        var sentDirs = 0
        var sentBytes = 0L
        var skippedFiles = 0
        var processedItems = 0
        val chunkBuf = ByteArray(CHUNK_BYTES)

        for (item in items) {
            if (sink.isCancelled()) {
                Diag.log(Diag.PEER) { "send cancelled by user session=$sessionId before '${item.relPath}'" }
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
                Diag.log(Diag.PEER) { "send file '${item.relPath}' bytes=$expectedSize session=$sessionId attempt=$attempt" }
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
                    Diag.log(Diag.PEER) { "send cancelled by user mid-file '${item.relPath}' session=$sessionId" }
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
                    Diag.log(Diag.PEER) {
                        "send file '${item.relPath}' pre-100-continue error attempt=$attempt: ${preErr.message.orEmpty()}"
                    }
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
                    Diag.log(Diag.PEER) {
                        "send file '${item.relPath}' failed ${postErr.javaClass.simpleName}: ${postErr.message.orEmpty()} " +
                            "stall=${hasCause<WriteStallException>(postErr)}"
                    }
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
            Diag.log(Diag.PEER) { "send file '${item.relPath}' <- status=${resp.status} ${resp.bodyText.take(160)}" }
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
        Diag.log(Diag.PEER) {
            "send end session=$sessionId status=${endResp?.status ?: "no-response"} files=$sentFiles bytes=$sentBytes " +
                "skipped=$skippedFiles dirs=$sentDirs"
        }
        if (endResp != null && endResp.status == 410) {
            throw PeerTransferException(410, AppI18n.current().format(Str.PEER_ERR_REJECTED_FMT, peerName))
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
            throw PeerTransferException(410, AppI18n.current().format(Str.PEER_ERR_REJECTED_FMT, peerDisplayName(peer)), cause)
        }
    }

    companion object {
        const val CHUNK_BYTES = 256 * 1024

        /** `begin` read timeout (spec D2): covers the receiver's 60 s consent prompt with margin. */
        const val BEGIN_TIMEOUT_MS = 150_000

        /**
         * `begin` read timeout for a BLE-negotiated session (`linkToken`): the receiver pre-approved it, but still
         * covers iOS's 60 s consent prompt if its session token already expired.
         */
        const val LINKED_BEGIN_TIMEOUT_MS = 75_000

        internal fun beginTimeoutMs(peer: PeerInfo): Int =
            if (peer.linkToken?.trim().isNullOrEmpty()) BEGIN_TIMEOUT_MS else LINKED_BEGIN_TIMEOUT_MS

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

        internal fun mapHttpError(
            peer: PeerInfo,
            resp: PeerHttpResponse,
            beforeSession: Boolean = false,
        ): PeerTransferException {
            val name = peerDisplayName(peer)
            if (PeerTransferException.isRedirectStatus(resp.status)) {
                // Files never redirects /api/peer/…: a VPN (tun0), proxy or captive portal answered instead.
                return PeerTransferException(
                    httpStatus = resp.status,
                    userMessage = PeerTransferException.interceptedMessage(name, resp.status),
                    beforeSession = beforeSession,
                    kind = PeerFailureKind.INTERCEPTED,
                )
            }
            val errObj = parseJsonObj(resp.bodyText)
            val serverErr = errObj?.str("error")?.trim().orEmpty()
            val code = errObj?.str("code")?.trim().orEmpty()

            val msg = when (resp.status) {
                401 -> AppI18n.current().format(Str.PEER_ERR_PIN_REQUIRED_FMT, name)
                403 -> {
                    if (code == "read_only" || serverErr.contains("read-only", ignoreCase = true)) {
                        AppI18n.current().format(Str.PEER_IS_READ_ONLY_HINT, name)
                    } else if (code == "declined") {
                        AppI18n.current().format(Str.PEER_ERR_DECLINED_FMT, name)
                    } else if (code == "disabled") {
                        AppI18n.current().format(Str.PEER_ERR_RECEIVING_OFF_FMT, name)
                    } else if (code == "token") {
                        AppI18n.current().format(Str.PEER_ERR_SESSION_EXPIRED_FMT, name)
                    } else {
                        AppI18n.current().format(Str.PEER_ERR_ACCESS_DENIED_FMT, name) + (if (serverErr.isNotEmpty()) ": $serverErr" else "")
                    }
                }
                404 -> AppI18n.current().format(Str.PEER_ERR_NEEDS_UPDATE_FMT, name)
                409 -> AppI18n.current().format(Str.PEER_ERR_CONFLICT_FMT, name) + (if (serverErr.isNotEmpty()) ": $serverErr" else "")
                410 -> AppI18n.current().format(Str.PEER_ERR_REJECTED_FMT, name)
                411 -> AppI18n.current().format(Str.PEER_ERR_LENGTH_REQUIRED_FMT, name)
                503 -> AppI18n.current().format(Str.PEER_ERR_BUSY_FMT, name)
                507 -> AppI18n.current().format(Str.PEER_ERR_NO_SPACE_FMT, name)
                else -> {
                    if (serverErr.isNotEmpty()) {
                        "$name: $serverErr (HTTP ${resp.status})"
                    } else {
                        AppI18n.current().format(Str.PEER_ERR_HTTP_STATUS_FMT, name, resp.status)
                    }
                }
            }
            return PeerTransferException(resp.status, msg, beforeSession = beforeSession)
        }

        internal fun mapTransportError(
            peer: PeerInfo,
            cause: Throwable,
            midTransfer: Boolean = false,
            beforeSession: Boolean = false,
        ): PeerTransferException {
            if (cause is PeerTransferException) return cause
            val name = peerDisplayName(peer)
            if (cause is FtpAccessDeniedException) {
                return PeerTransferException(null, AppI18n.current()[Str.PEER_ERR_LAN_ONLY], cause, beforeSession)
            }
            val msg = if (hasCause<WriteStallException>(cause)) {
                AppI18n.current().format(Str.PEER_ERR_STOPPED_RECEIVING_FMT, name)
            } else if (midTransfer) {
                AppI18n.current().format(Str.PEER_ERR_CONNECTION_LOST_FMT, name)
            } else {
                AppI18n.current().format(Str.PEER_ERR_NOT_REACHABLE_FMT, name)
            }
            return PeerTransferException(
                httpStatus = null,
                userMessage = msg,
                cause = cause,
                beforeSession = beforeSession,
                kind = if (!midTransfer && isConnectFailure(cause)) PeerFailureKind.UNREACHABLE else PeerFailureKind.OTHER,
            )
        }

        private inline fun <reified T : Throwable> hasCause(cause: Throwable?): Boolean {
            var c = cause
            var depth = 0
            while (c != null && depth < 8) {
                if (c is T) return true
                c = c.cause
                depth++
            }
            return false
        }

        /**
         * `true` when [cause] (or any cause in its chain) is a TCP connect failure — refused, no route, unreachable or
         * a connect timeout — as opposed to a read timeout while the receiver shows its consent prompt.
         */
        internal fun isConnectFailure(cause: Throwable?): Boolean {
            var c = cause
            var depth = 0
            while (c != null && depth < 8) {
                when (c) {
                    is java.net.ConnectException, is java.net.NoRouteToHostException, is java.net.PortUnreachableException -> return true
                    is java.net.SocketTimeoutException -> if (c.message.orEmpty().contains("connect", ignoreCase = true)) return true
                }
                if (c is PreContinueIOException && c.message.orEmpty().contains("connect", ignoreCase = true)) return true
                c = c.cause
                depth++
            }
            return false
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
