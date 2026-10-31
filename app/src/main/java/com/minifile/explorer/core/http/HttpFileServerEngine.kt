package com.minifile.explorer.core.http

import com.minifile.explorer.core.ftp.FtpAccessDeniedException
import com.minifile.explorer.core.ftp.FtpPathResolver
import com.minifile.explorer.core.ftp.FtpServerEngine
import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.MiniJson
import com.minifile.explorer.core.model.SortSpec
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.NameValidation
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class HttpConfig(
    val port: Int = 8080,
    val bindAddress: String = "0.0.0.0",
    val root: VfsPath,
    val readOnly: Boolean = false,
    val showHidden: Boolean = false,
    val socketTimeoutMs: Int = 60_000,
    val maxClients: Int = 32,
    val serverName: String = "Files HTTP",
)

enum class HttpTransferDirection {
    UPLOAD,
    DOWNLOAD,
}

sealed interface HttpEvent {
    data class Started(val port: Int) : HttpEvent
    data object Stopped : HttpEvent
    data class Request(
        val clientIp: String,
        val method: String,
        val target: String,
        val status: Int,
    ) : HttpEvent

    data class Transfer(
        val clientIp: String,
        val direction: HttpTransferDirection,
        val path: String,
        val bytes: Long,
        val durationMs: Long,
    ) : HttpEvent

    data class Error(val message: String) : HttpEvent
}

private class HttpStatusException(val status: Int, val reason: String, message: String) : IOException(message)

/**
 * Pure-Kotlin HTTP/1.1 wireless file share server (`zero android.* imports`).
 *
 * Security invariants:
 * - Enforces [FtpServerEngine.isAllowedClientAddress] LAN/loopback socket firewall on both peer and local endpoints.
 * - Enforces strict virtual path resolution via [FtpPathResolver] + `LocalFileSystem.isInside` canonical jail,
 *   explicitly rejecting `.` / `..` traversal segments, NUL bytes, backslashes, and out-of-root symlinks with `403`.
 * - Enforces [HttpConfig.readOnly] on `POST /mkdir` and `POST /upload` with `403`.
 */
class HttpFileServerEngine(
    val config: HttpConfig,
    private val fs: FileSystem,
) {
    private val pathResolver = FtpPathResolver(config.root, fs)
    private val sortComparator = SortSpec().comparator()

    private val _events = MutableSharedFlow<HttpEvent>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<HttpEvent> = _events.asSharedFlow()

    private val _activeClients = MutableStateFlow(0)
    val activeClients: StateFlow<Int> = _activeClients.asStateFlow()

    private val running = AtomicBoolean(false)
    private val openSockets = Collections.synchronizedSet(HashSet<Socket>())

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var executor: ExecutorService? = null

    @Volatile
    var boundPort: Int = -1
        private set

    val isRunning: Boolean
        get() = running.get()

    @Synchronized
    fun start() {
        if (running.get()) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        val bindSockAddr = if (
            config.bindAddress == "0.0.0.0" ||
            config.bindAddress == "::" ||
            config.bindAddress.isBlank()
        ) {
            InetSocketAddress(config.port)
        } else {
            InetSocketAddress(InetAddress.getByName(config.bindAddress), config.port)
        }
        try {
            ss.bind(bindSockAddr)
        } catch (t: Throwable) {
            runCatching { ss.close() }
            throw t
        }
        boundPort = ss.localPort
        serverSocket = ss
        running.set(true)

        val pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "minifile-http-worker").apply { isDaemon = true }
        }
        executor = pool
        _events.tryEmit(HttpEvent.Started(boundPort))

        pool.execute {
            acceptLoop(ss, pool)
        }
    }

    private fun acceptLoop(ss: ServerSocket, pool: ExecutorService) {
        while (running.get() && !ss.isClosed) {
            val client = try {
                ss.accept()
            } catch (_: IOException) {
                if (running.get()) {
                    _events.tryEmit(HttpEvent.Error("HTTP accept loop terminated"))
                }
                break
            }

            val remoteAddr = client.inetAddress
            val localAddr = client.localAddress
            if (remoteAddr == null || localAddr == null ||
                !FtpServerEngine.isAllowedClientAddress(remoteAddr) ||
                !FtpServerEngine.isAllowedClientAddress(localAddr)
            ) {
                runCatching { client.close() }
                continue
            }

            if (openSockets.size >= config.maxClients) {
                runCatching {
                    val out = BufferedOutputStream(client.getOutputStream())
                    writeSimpleResponse(
                        out = out,
                        status = 503,
                        reason = "Service Unavailable",
                        contentType = "text/plain; charset=utf-8",
                        body = "Too many active connections\n".toByteArray(Charsets.UTF_8),
                        headOnly = false,
                        closeConnection = true,
                    )
                    client.close()
                }
                continue
            }

            openSockets.add(client)
            _activeClients.value = openSockets.size
            pool.execute {
                try {
                    handleConnection(client)
                } finally {
                    openSockets.remove(client)
                    _activeClients.value = openSockets.size
                    runCatching { client.close() }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null

        val snapshot = synchronized(openSockets) { openSockets.toList() }
        for (sock in snapshot) {
            runCatching { sock.close() }
        }
        openSockets.clear()
        _activeClients.value = 0

        executor?.shutdownNow()
        executor = null
        boundPort = -1
        _events.tryEmit(HttpEvent.Stopped)
    }

    private fun handleConnection(socket: Socket) {
        socket.soTimeout = config.socketTimeoutMs
        socket.tcpNoDelay = true
        val clientIp = socket.inetAddress?.hostAddress ?: "unknown"
        val input = BufferedInputStream(socket.getInputStream(), 32 * 1024)
        val output = BufferedOutputStream(socket.getOutputStream(), 32 * 1024)

        while (running.get() && !socket.isClosed) {
            val requestLine = try {
                readHeaderLine(input, maxBytes = 8192)
            } catch (_: IOException) {
                break
            } ?: break

            if (requestLine.isEmpty()) continue

            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                writeJsonError(output, 400, "Bad Request", "Malformed HTTP request line", headOnly = false, close = true)
                break
            }
            val method = parts[0].uppercase(Locale.ROOT)
            val rawTarget = parts[1]
            val protocol = parts.getOrNull(2)?.uppercase(Locale.ROOT) ?: "HTTP/1.1"

            val headers = LinkedHashMap<String, String>()
            var headerError = false
            var headerCount = 0
            while (true) {
                val line = try {
                    readHeaderLine(input, maxBytes = 8192)
                } catch (_: IOException) {
                    headerError = true
                    null
                }
                if (line == null) {
                    headerError = true
                    break
                }
                if (line.isEmpty()) break
                headerCount++
                if (headerCount > 96) {
                    writeJsonError(output, 431, "Request Header Fields Too Large", "Too many headers", headOnly = false, close = true)
                    return
                }
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                }
            }
            if (headerError) break

            val connHeader = headers["connection"]?.lowercase(Locale.ROOT).orEmpty()
            var shouldClose = protocol == "HTTP/1.0" && !connHeader.contains("keep-alive") ||
                connHeader.contains("close")

            val keepConnectionOpen = runBlocking {
                dispatchRequest(
                    clientIp = clientIp,
                    method = method,
                    rawTarget = rawTarget,
                    headers = headers,
                    input = input,
                    output = output,
                    requestWantsClose = shouldClose,
                )
            }
            if (!keepConnectionOpen) {
                break
            }
        }
    }

    private suspend fun dispatchRequest(
        clientIp: String,
        method: String,
        rawTarget: String,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        requestWantsClose: Boolean,
    ): Boolean {
        val headOnly = method == "HEAD"
        val qIdx = rawTarget.indexOf('?')
        val rawUriPath = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
        val rawQuery = if (qIdx >= 0) rawTarget.substring(qIdx + 1) else ""

        try {
            validateUriRoutePath(rawUriPath)
            val query = parseQueryString(rawQuery)

            return when {
                (rawUriPath == "/" || rawUriPath == "/browse") && (method == "GET" || method == "HEAD") -> {
                    handleBrowse(clientIp, rawTarget, query, output, headOnly, requestWantsClose)
                }

                rawUriPath == "/raw" && (method == "GET" || method == "HEAD") -> {
                    handleRaw(clientIp, rawTarget, query, headers, output, headOnly, requestWantsClose)
                }

                rawUriPath == "/zip" && (method == "GET" || method == "HEAD") -> {
                    handleZip(clientIp, rawTarget, query, output, headOnly)
                }

                rawUriPath == "/mkdir" && method == "POST" -> {
                    handleMkdir(clientIp, rawTarget, query, headers, input, output, requestWantsClose)
                }

                rawUriPath == "/upload" && method == "POST" -> {
                    handleUpload(clientIp, rawTarget, query, headers, input, output, requestWantsClose)
                }

                rawUriPath == "/favicon.ico" && (method == "GET" || method == "HEAD") -> {
                    writeSimpleResponse(
                        out = output,
                        status = 204,
                        reason = "No Content",
                        contentType = "image/x-icon",
                        body = ByteArray(0),
                        headOnly = true,
                        closeConnection = requestWantsClose,
                    )
                    !requestWantsClose
                }

                rawUriPath in setOf("/", "/browse", "/raw", "/zip", "/mkdir", "/upload") -> {
                    _events.tryEmit(HttpEvent.Request(clientIp, method, rawTarget, 405))
                    writeJsonError(output, 405, "Method Not Allowed", "Method $method not allowed", headOnly, close = true)
                    false
                }

                else -> {
                    _events.tryEmit(HttpEvent.Request(clientIp, method, rawTarget, 404))
                    writeJsonError(output, 404, "Not Found", "Unknown endpoint: $rawUriPath", headOnly, close = true)
                    false
                }
            }
        } catch (e: FtpAccessDeniedException) {
            _events.tryEmit(HttpEvent.Request(clientIp, method, rawTarget, 403))
            writeJsonError(output, 403, "Forbidden", e.message ?: "Access denied", headOnly, close = true)
            return false
        } catch (e: HttpStatusException) {
            _events.tryEmit(HttpEvent.Request(clientIp, method, rawTarget, e.status))
            writeJsonError(output, e.status, e.reason, e.message ?: e.reason, headOnly, close = true)
            return false
        } catch (e: Exception) {
            _events.tryEmit(HttpEvent.Error("${method} ${rawTarget}: ${e.message ?: "internal error"}"))
            runCatching {
                writeJsonError(output, 500, "Internal Server Error", e.message ?: "Internal server error", headOnly, close = true)
            }
            return false
        }
    }

    private suspend fun handleBrowse(
        clientIp: String,
        rawTarget: String,
        query: Map<String, String>,
        output: OutputStream,
        headOnly: Boolean,
        closeConnection: Boolean,
    ): Boolean {
        val relParam = query["path"].orEmpty().ifBlank { "/" }
        val resolved = resolveStrictPath(relParam)
        val dirNode = fs.stat(resolved.vfsPath)
            ?: throw HttpStatusException(404, "Not Found", "Folder not found")
        if (!dirNode.isDirectory) {
            throw HttpStatusException(400, "Bad Request", "Target path is not a folder")
        }

        val children = fs.list(resolved.vfsPath, includeHidden = config.showHidden)
            .sortedWith(sortComparator)

        val htmlBytes = renderBrowseHtml(
            virtualPath = resolved.virtualPath,
            entries = children,
            readOnly = config.readOnly,
        ).toByteArray(Charsets.UTF_8)

        _events.tryEmit(HttpEvent.Request(clientIp, if (headOnly) "HEAD" else "GET", rawTarget, 200))
        writeSimpleResponse(
            out = output,
            status = 200,
            reason = "OK",
            contentType = "text/html; charset=utf-8",
            body = htmlBytes,
            headOnly = headOnly,
            closeConnection = closeConnection,
        )
        return !closeConnection
    }

    private suspend fun handleRaw(
        clientIp: String,
        rawTarget: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        output: OutputStream,
        headOnly: Boolean,
        closeConnection: Boolean,
    ): Boolean {
        val relParam = query["path"]?.takeIf { it.isNotBlank() }
            ?: throw HttpStatusException(400, "Bad Request", "Missing 'path' query parameter")
        val resolved = resolveStrictPath(relParam)
        val node = fs.stat(resolved.vfsPath)
            ?: throw HttpStatusException(404, "Not Found", "File not found")
        if (node.isDirectory) {
            throw HttpStatusException(400, "Bad Request", "Cannot stream directory via /raw; use /zip")
        }

        val totalSize = node.size.coerceAtLeast(0L)
        val forceDownload = query["download"] == "1" || query["download"].equals("true", ignoreCase = true)
        val dispositionType = if (forceDownload) "attachment" else "inline"
        val dispositionHeader = buildContentDisposition(dispositionType, node.name)
        val contentType = resolveHttpContentType(node)

        val rangeHeader = headers["range"]
        if (rangeHeader != null) {
            val byteRange = parseByteRange(rangeHeader, totalSize)
            if (byteRange == null) {
                _events.tryEmit(HttpEvent.Request(clientIp, if (headOnly) "HEAD" else "GET", rawTarget, 416))
                writeHeaders(
                    out = output,
                    status = 416,
                    reason = "Range Not Satisfiable",
                    headers = listOf(
                        "Content-Type" to "text/plain; charset=utf-8",
                        "Content-Range" to "bytes */$totalSize",
                        "Accept-Ranges" to "bytes",
                        "Content-Length" to "0",
                        "Connection" to if (closeConnection) "close" else "keep-alive",
                    ),
                )
                output.flush()
                return !closeConnection
            }

            val (start, end) = byteRange
            val sliceLength = end - start + 1L
            _events.tryEmit(HttpEvent.Request(clientIp, if (headOnly) "HEAD" else "GET", rawTarget, 206))
            writeHeaders(
                out = output,
                status = 206,
                reason = "Partial Content",
                headers = listOf(
                    "Content-Type" to contentType,
                    "Content-Length" to sliceLength.toString(),
                    "Content-Range" to "bytes $start-$end/$totalSize",
                    "Accept-Ranges" to "bytes",
                    "Content-Disposition" to dispositionHeader,
                    "Connection" to if (closeConnection) "close" else "keep-alive",
                ),
            )
            if (headOnly) {
                output.flush()
                return !closeConnection
            }

            val t0 = System.currentTimeMillis()
            val sent = fs.openInput(resolved.vfsPath, offset = start).use { fileIn ->
                copyExact(fileIn, output, sliceLength)
            }
            output.flush()
            val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            _events.tryEmit(
                HttpEvent.Transfer(
                    clientIp = clientIp,
                    direction = HttpTransferDirection.DOWNLOAD,
                    path = resolved.virtualPath,
                    bytes = sent,
                    durationMs = elapsed,
                ),
            )
            return sent == sliceLength && !closeConnection
        }

        _events.tryEmit(HttpEvent.Request(clientIp, if (headOnly) "HEAD" else "GET", rawTarget, 200))
        writeHeaders(
            out = output,
            status = 200,
            reason = "OK",
            headers = listOf(
                "Content-Type" to contentType,
                "Content-Length" to totalSize.toString(),
                "Accept-Ranges" to "bytes",
                "Content-Disposition" to dispositionHeader,
                "Connection" to if (closeConnection) "close" else "keep-alive",
            ),
        )
        if (headOnly) {
            output.flush()
            return !closeConnection
        }

        val t0 = System.currentTimeMillis()
        val sent = fs.openInput(resolved.vfsPath, offset = 0L).use { fileIn ->
            copyExact(fileIn, output, totalSize)
        }
        output.flush()
        val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
        _events.tryEmit(
            HttpEvent.Transfer(
                clientIp = clientIp,
                direction = HttpTransferDirection.DOWNLOAD,
                path = resolved.virtualPath,
                bytes = sent,
                durationMs = elapsed,
            ),
        )
        return sent == totalSize && !closeConnection
    }

    private suspend fun handleZip(
        clientIp: String,
        rawTarget: String,
        query: Map<String, String>,
        output: OutputStream,
        headOnly: Boolean,
    ): Boolean {
        val relParam = query["path"].orEmpty().ifBlank { "/" }
        val resolved = resolveStrictPath(relParam)
        val rootNode = fs.stat(resolved.vfsPath)
            ?: throw HttpStatusException(404, "Not Found", "Folder not found")

        val baseArchiveStem = if (resolved.virtualPath == "/") {
            config.root.displayName.takeIf { it.isNotBlank() && it != "/" } ?: "files"
        } else {
            rootNode.name.ifBlank { "folder" }
        }
        val zipFileName = if (baseArchiveStem.endsWith(".zip", ignoreCase = true)) {
            baseArchiveStem
        } else {
            "$baseArchiveStem.zip"
        }

        _events.tryEmit(HttpEvent.Request(clientIp, if (headOnly) "HEAD" else "GET", rawTarget, 200))
        writeHeaders(
            out = output,
            status = 200,
            reason = "OK",
            headers = listOf(
                "Content-Type" to "application/zip",
                "Content-Disposition" to buildContentDisposition("attachment", zipFileName),
                "Cache-Control" to "no-store",
                "Connection" to "close",
            ),
        )
        if (headOnly) {
            output.flush()
            return false
        }

        val t0 = System.currentTimeMillis()
        val countingOut = CountingOutputStream(output)
        val addedEntries = HashSet<String>()
        ZipOutputStream(countingOut, Charsets.UTF_8).use { zos ->
            suspend fun writeTree(node: VfsNode, entryRelPath: String) {
                if (node.isSymlink) return
                pathResolver.ensureLocalInside(node.path)
                if (node.isDirectory) {
                    if (entryRelPath.isNotEmpty()) {
                        val dirEntryName = "${entryRelPath.trimEnd('/')}/"
                        if (addedEntries.add(dirEntryName)) {
                            val ze = ZipEntry(dirEntryName)
                            if (node.lastModified > 0L) ze.time = node.lastModified
                            zos.putNextEntry(ze)
                            zos.closeEntry()
                        }
                    }
                    val kids = fs.list(node.path, includeHidden = config.showHidden)
                        .sortedWith(sortComparator)
                    for (child in kids) {
                        val childRel = if (entryRelPath.isEmpty()) {
                            child.name
                        } else {
                            "${entryRelPath.trimEnd('/')}/${child.name}"
                        }
                        writeTree(child, childRel)
                    }
                } else {
                    val fileEntryName = entryRelPath.ifEmpty { node.name }
                    if (!addedEntries.add(fileEntryName)) return
                    val ze = ZipEntry(fileEntryName)
                    if (node.lastModified > 0L) ze.time = node.lastModified
                    zos.putNextEntry(ze)
                    fs.openInput(node.path, 0L).use { fileIn ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = fileIn.read(buf)
                            if (n < 0) break
                            if (n > 0) zos.write(buf, 0, n)
                        }
                    }
                    zos.closeEntry()
                }
            }

            if (rootNode.isDirectory) {
                val topPrefix = if (resolved.virtualPath == "/") "" else rootNode.name
                writeTree(rootNode, topPrefix)
            } else {
                writeTree(rootNode, rootNode.name)
            }
            zos.finish()
        }
        output.flush()
        val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
        _events.tryEmit(
            HttpEvent.Transfer(
                clientIp = clientIp,
                direction = HttpTransferDirection.DOWNLOAD,
                path = "${resolved.virtualPath} (.zip)",
                bytes = countingOut.bytesWritten,
                durationMs = elapsed,
            ),
        )
        return false
    }

    private suspend fun handleMkdir(
        clientIp: String,
        rawTarget: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        val bodyInfo = prepareRequestBody(headers, input, sendContinueIfNeeded = false)
        drainSmallBodyIfBounded(bodyInfo)

        if (config.readOnly) {
            throw FtpAccessDeniedException("Server is in read-only mode")
        }

        val parentRel = query["path"].orEmpty().ifBlank { "/" }
        val rawFolderName = query["name"] ?: ""
        val cleanName = validateLeafName(rawFolderName)

        val resolvedParent = resolveStrictPath(parentRel)
        val parentNode = fs.stat(resolvedParent.vfsPath)
            ?: throw HttpStatusException(404, "Not Found", "Parent folder does not exist")
        if (!parentNode.isDirectory) {
            throw HttpStatusException(400, "Bad Request", "Parent path is not a folder")
        }

        val candidateChild = fs.child(resolvedParent.vfsPath, cleanName)
        pathResolver.ensureLocalInside(candidateChild)

        val existing = fs.stat(candidateChild)
        if (existing != null && !existing.isDirectory) {
            throw HttpStatusException(409, "Conflict", "A file named '$cleanName' already exists")
        }
        if (existing == null) {
            fs.createDirectory(resolvedParent.vfsPath, cleanName)
        }

        val createdVirtual = if (resolvedParent.virtualPath == "/") {
            "/$cleanName"
        } else {
            "${resolvedParent.virtualPath}/$cleanName"
        }

        _events.tryEmit(HttpEvent.Request(clientIp, "POST", rawTarget, 201))
        val payload = MiniJson.stringify(
            MiniJson.obj(
                "ok" to JsonValue.Bool(true),
                "path" to JsonValue.Str(createdVirtual),
            ),
        ).toByteArray(Charsets.UTF_8)

        val closeAfter = closeConnection || bodyInfo.requiresClose
        writeSimpleResponse(
            out = output,
            status = 201,
            reason = "Created",
            contentType = "application/json; charset=utf-8",
            body = payload,
            headOnly = false,
            closeConnection = closeAfter,
        )
        return !closeAfter
    }

    private suspend fun handleUpload(
        clientIp: String,
        rawTarget: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream,
        closeConnection: Boolean,
    ): Boolean {
        if (config.readOnly) {
            throw FtpAccessDeniedException("Server is in read-only mode")
        }

        val dirRel = query["path"].orEmpty().ifBlank { "/" }
        val resolvedDir = resolveStrictPath(dirRel)
        val dirNode = fs.stat(resolvedDir.vfsPath)
            ?: throw HttpStatusException(404, "Not Found", "Destination folder does not exist")
        if (!dirNode.isDirectory) {
            throw HttpStatusException(400, "Bad Request", "Destination path is not a folder")
        }

        val contentType = headers["content-type"].orEmpty()
        val isMultipart = contentType.startsWith("multipart/form-data", ignoreCase = true)

        if (!isMultipart) {
            val rawFileName = query["name"] ?: ""
            val leafName = validateLeafName(rawFileName)
            val candidateChild = fs.child(resolvedDir.vfsPath, leafName)
            pathResolver.ensureLocalInside(candidateChild)

            val existing = fs.stat(candidateChild)
            if (existing?.isDirectory == true) {
                throw HttpStatusException(409, "Conflict", "A folder named '$leafName' already exists")
            }

            val bodyInfo = prepareRequestBody(headers, input, sendContinueIfNeeded = true, rawOut = output)
            val targetPath = existing?.path
                ?: fs.createFile(resolvedDir.vfsPath, leafName, PathUtils.guessMimeType(leafName))

            val t0 = System.currentTimeMillis()
            var written = 0L
            fs.openOutput(targetPath, append = false).use { fileOut ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = bodyInfo.stream.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        fileOut.write(buf, 0, n)
                        written += n
                    }
                }
                fileOut.flush()
            }
            val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            val virtualFilePath = if (resolvedDir.virtualPath == "/") {
                "/$leafName"
            } else {
                "${resolvedDir.virtualPath}/$leafName"
            }

            _events.tryEmit(HttpEvent.Request(clientIp, "POST", rawTarget, 201))
            _events.tryEmit(
                HttpEvent.Transfer(
                    clientIp = clientIp,
                    direction = HttpTransferDirection.UPLOAD,
                    path = virtualFilePath,
                    bytes = written,
                    durationMs = elapsed,
                ),
            )

            val respJson = MiniJson.stringify(
                MiniJson.obj(
                    "ok" to JsonValue.Bool(true),
                    "name" to JsonValue.Str(leafName),
                    "path" to JsonValue.Str(virtualFilePath),
                    "bytes" to JsonValue.Num(written),
                ),
            ).toByteArray(Charsets.UTF_8)

            val closeAfter = closeConnection || bodyInfo.requiresClose
            writeSimpleResponse(
                out = output,
                status = 201,
                reason = "Created",
                contentType = "application/json; charset=utf-8",
                body = respJson,
                headOnly = false,
                closeConnection = closeAfter,
            )
            return !closeAfter
        }

        // Multipart upload (`multipart/form-data; boundary=...`)
        val boundary = extractMultipartBoundary(contentType)
            ?: throw HttpStatusException(400, "Bad Request", "Missing multipart boundary")

        val bodyInfo = prepareRequestBody(headers, input, sendContinueIfNeeded = true, rawOut = output)
        val (filesCount, totalBytes) = receiveMultipartFiles(
            bodyStream = bodyInfo.stream,
            boundary = boundary,
            resolvedDir = resolvedDir,
            fallbackName = query["name"],
            clientIp = clientIp,
        )

        if (filesCount == 0) {
            throw HttpStatusException(400, "Bad Request", "No files found in multipart upload")
        }

        _events.tryEmit(HttpEvent.Request(clientIp, "POST", rawTarget, 201))
        val respJson = MiniJson.stringify(
            MiniJson.obj(
                "ok" to JsonValue.Bool(true),
                "files" to JsonValue.Num(filesCount.toLong()),
                "bytes" to JsonValue.Num(totalBytes),
            ),
        ).toByteArray(Charsets.UTF_8)

        val closeAfter = closeConnection || bodyInfo.requiresClose
        writeSimpleResponse(
            out = output,
            status = 201,
            reason = "Created",
            contentType = "application/json; charset=utf-8",
            body = respJson,
            headOnly = false,
            closeConnection = closeAfter,
        )
        return !closeAfter
    }

    private suspend fun receiveMultipartFiles(
        bodyStream: InputStream,
        boundary: String,
        resolvedDir: ResolvedHttpPath,
        fallbackName: String?,
        clientIp: String,
    ): Pair<Int, Long> {
        val reader = MultipartStreamReader(bodyStream)
        val firstMarker = "--$boundary"
        val endMarker = "--$boundary--"
        val nextDelimiter = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)

        // Skip preamble lines until initial `--boundary`
        while (true) {
            val line = reader.readCrlfLine(maxBytes = 8192)
                ?: throw HttpStatusException(400, "Bad Request", "Incomplete multipart preamble")
            if (line == endMarker) return 0 to 0L
            if (line == firstMarker) break
        }

        var uploadedFiles = 0
        var uploadedBytes = 0L

        while (true) {
            val partHeaders = LinkedHashMap<String, String>()
            while (true) {
                val hLine = reader.readCrlfLine(maxBytes = 8192)
                    ?: throw HttpStatusException(400, "Bad Request", "Unexpected EOF in multipart headers")
                if (hLine.isEmpty()) break
                val colon = hLine.indexOf(':')
                if (colon > 0) {
                    partHeaders[hLine.substring(0, colon).trim().lowercase(Locale.ROOT)] =
                        hLine.substring(colon + 1).trim()
                }
            }

            val disp = partHeaders["content-disposition"].orEmpty()
            val partFileName = extractDispositionFileName(disp) ?: fallbackName

            val isFinalBoundary: Boolean
            if (!partFileName.isNullOrBlank()) {
                val leafName = validateLeafName(partFileName)
                val candidateChild = fs.child(resolvedDir.vfsPath, leafName)
                pathResolver.ensureLocalInside(candidateChild)
                val existing = fs.stat(candidateChild)
                if (existing?.isDirectory == true) {
                    throw HttpStatusException(409, "Conflict", "A folder named '$leafName' already exists")
                }
                val targetPath = existing?.path
                    ?: fs.createFile(resolvedDir.vfsPath, leafName, PathUtils.guessMimeType(leafName))

                val t0 = System.currentTimeMillis()
                val partBytes: Long
                fs.openOutput(targetPath, append = false).use { fileOut ->
                    val res = reader.streamUntilBoundary(nextDelimiter, fileOut)
                    partBytes = res.first
                    isFinalBoundary = res.second
                    fileOut.flush()
                }
                val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                val virtualFilePath = if (resolvedDir.virtualPath == "/") {
                    "/$leafName"
                } else {
                    "${resolvedDir.virtualPath}/$leafName"
                }
                uploadedFiles++
                uploadedBytes += partBytes
                _events.tryEmit(
                    HttpEvent.Transfer(
                        clientIp = clientIp,
                        direction = HttpTransferDirection.UPLOAD,
                        path = virtualFilePath,
                        bytes = partBytes,
                        durationMs = elapsed,
                    ),
                )
            } else {
                val (_, finalFlag) = reader.streamUntilBoundary(nextDelimiter, NullOutputStream)
                isFinalBoundary = finalFlag
            }

            if (isFinalBoundary) break
        }

        // Drain any trailing epilogue bytes in bounded stream cleanly
        val drain = ByteArray(1024)
        while (bodyStream.read(drain) >= 0) {
            // drain epilogue
        }

        return uploadedFiles to uploadedBytes
    }

    private data class ResolvedHttpPath(
        val virtualPath: String,
        val vfsPath: VfsPath,
    )

    private suspend fun resolveStrictPath(rawRel: String): ResolvedHttpPath {
        if (rawRel.contains('\u0000') || rawRel.contains('\\')) {
            throw FtpAccessDeniedException("Illegal path characters")
        }
        val segments = ArrayList<String>()
        for (seg in rawRel.split('/')) {
            if (seg.isEmpty()) continue
            if (seg == "." || seg == "..") {
                throw FtpAccessDeniedException("Path traversal is not permitted")
            }
            if (PathUtils.validateName(seg) is NameValidation.Invalid) {
                throw FtpAccessDeniedException("Invalid path segment")
            }
            segments.add(seg)
        }
        val normalizedVirtual = if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
        val resolved = pathResolver.resolve("/", normalizedVirtual)
        return ResolvedHttpPath(
            virtualPath = resolved.virtualPath,
            vfsPath = resolved.vfsPath,
        )
    }

    private fun validateUriRoutePath(rawUriPath: String) {
        if (rawUriPath.isEmpty() || !rawUriPath.startsWith("/")) {
            throw FtpAccessDeniedException("Invalid URI path")
        }
        val decoded = decodePercentUtf8(rawUriPath)
        if (decoded.contains('\u0000') || decoded.contains('\\')) {
            throw FtpAccessDeniedException("Illegal URI characters")
        }
        for (part in decoded.split('/')) {
            if (part == "." || part == "..") {
                throw FtpAccessDeniedException("URI path traversal denied")
            }
        }
    }

    private fun validateLeafName(rawName: String): String {
        if (rawName.contains('\u0000') || rawName.contains('/') || rawName.contains('\\')) {
            throw FtpAccessDeniedException("Illegal path separator in name")
        }
        val trimmed = rawName.trim()
        if (trimmed == "." || trimmed == "..") {
            throw FtpAccessDeniedException("Reserved directory traversal name")
        }
        when (val v = PathUtils.validateName(trimmed, fatSafe = config.root is VfsPath.Saf)) {
            NameValidation.Valid -> return trimmed
            is NameValidation.Invalid -> throw HttpStatusException(400, "Bad Request", v.reason)
        }
    }

    private data class PreparedRequestBody(
        val stream: InputStream,
        val boundedLength: Long?,
        val requiresClose: Boolean,
    )

    private fun prepareRequestBody(
        headers: Map<String, String>,
        rawInput: InputStream,
        sendContinueIfNeeded: Boolean,
        rawOut: OutputStream? = null,
    ): PreparedRequestBody {
        if (sendContinueIfNeeded && rawOut != null &&
            headers["expect"]?.equals("100-continue", ignoreCase = true) == true
        ) {
            rawOut.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            rawOut.flush()
        }

        val te = headers["transfer-encoding"]?.lowercase(Locale.ROOT).orEmpty()
        if (te.contains("chunked")) {
            return PreparedRequestBody(
                stream = ChunkedInputStream(rawInput),
                boundedLength = null,
                requiresClose = false,
            )
        }

        val clHeader = headers["content-length"]
        if (clHeader != null) {
            val len = clHeader.trim().toLongOrNull()
            if (len == null || len < 0L) {
                throw HttpStatusException(400, "Bad Request", "Invalid Content-Length header")
            }
            return PreparedRequestBody(
                stream = BoundedInputStream(rawInput, len),
                boundedLength = len,
                requiresClose = false,
            )
        }

        // Neither chunked nor Content-Length: for mkdir empty body, otherwise read until EOF & close
        return PreparedRequestBody(
            stream = rawInput,
            boundedLength = null,
            requiresClose = true,
        )
    }

    private fun drainSmallBodyIfBounded(body: PreparedRequestBody) {
        val len = body.boundedLength ?: return
        if (len <= 0L || len > 65536L) return
        val scratch = ByteArray(1024)
        while (body.stream.read(scratch) >= 0) {
            // drain
        }
    }

    private fun renderBrowseHtml(
        virtualPath: String,
        entries: List<VfsNode>,
        readOnly: Boolean,
    ): String {
        val segments = virtualPath.split('/').filter { it.isNotEmpty() }
        val pageTitle = if (segments.isEmpty()) "Files" else segments.last()
        val encodedCurPath = encodeUrlComponent(virtualPath)
        val jsCurPath = escapeJsSingleQuoted(virtualPath)

        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        return buildString(8192 + entries.size * 320) {
            append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
            append("<meta charset=\"utf-8\">\n")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            append("<title>").append(escapeHtml(pageTitle)).append(" — Files</title>\n")
            append("<style>\n")
            append(WEB_UI_CSS)
            append("\n</style>\n</head>\n<body>\n")
            append("<div class=\"container\">\n")

            // Header
            append("<header class=\"topbar\">\n")
            append("  <div class=\"brand\">\n")
            append("    <span class=\"logo\" aria-hidden=\"true\">&#128193;</span>\n")
            append("    <div>\n")
            append("      <h1>Files</h1>\n")
            append("      <div class=\"sub\">").append(entries.size)
                .append(if (entries.size == 1) " item" else " items")
            if (readOnly) append(" &middot; <span class=\"badge-ro\">Read-only</span>")
            append("</div>\n")
            append("    </div>\n")
            append("  </div>\n")

            append("  <div class=\"actions\">\n")
            append("    <a class=\"btn btn-tonal\" href=\"/zip?path=").append(encodedCurPath).append("\">&#11015; Download Folder (.zip)</a>\n")
            if (!readOnly) {
                append("    <button class=\"btn btn-tonal\" type=\"button\" onclick=\"promptMkdir()\">+ New Folder</button>\n")
                append("    <label class=\"btn btn-primary\" for=\"file-input\">&#11014; Upload Files</label>\n")
                append("    <input id=\"file-input\" type=\"file\" multiple hidden onchange=\"uploadFiles(this.files)\">\n")
            }
            append("  </div>\n")
            append("</header>\n")

            // Breadcrumbs
            append("<nav class=\"crumbs\" aria-label=\"Breadcrumb\">\n")
            append("  <a href=\"/browse\">Home</a>")
            var running = ""
            for (seg in segments) {
                running += "/$seg"
                append(" <span class=\"sep\">/</span> <a href=\"/browse?path=")
                    .append(encodeUrlComponent(running))
                    .append("\">")
                    .append(escapeHtml(seg))
                    .append("</a>")
            }
            append("\n</nav>\n")

            // Upload progress panel
            if (!readOnly) {
                append("<div id=\"upload-card\" class=\"upload-card hidden\">\n")
                append("  <div class=\"upload-row\">\n")
                append("    <span id=\"upload-status\">Uploading…</span>\n")
                append("    <span id=\"upload-pct\">0%</span>\n")
                append("  </div>\n")
                append("  <div class=\"progress-track\"><div id=\"upload-bar\" class=\"progress-fill\"></div></div>\n")
                append("</div>\n")
            }

            // Directory & file listing card
            append("<main class=\"card\">\n")
            if (virtualPath != "/") {
                val parentPath = if (segments.size <= 1) "/" else "/" + segments.dropLast(1).joinToString("/")
                val parentHref = if (parentPath == "/") "/browse" else "/browse?path=${encodeUrlComponent(parentPath)}"
                append("  <div class=\"row\">\n")
                append("    <a class=\"row-main\" href=\"").append(parentHref).append("\">\n")
                append("      <span class=\"icon\" aria-hidden=\"true\">&#11014;&#65039;</span>\n")
                append("      <span class=\"meta\"><span class=\"name\">Parent folder</span><span class=\"detail\">Up one level</span></span>\n")
                append("    </a>\n")
                append("  </div>\n")
            }

            if (entries.isEmpty()) {
                append("  <div class=\"empty\">This folder is empty.</div>\n")
            } else {
                for (entry in entries) {
                    val itemVirtual = if (virtualPath == "/") "/${entry.name}" else "$virtualPath/${entry.name}"
                    val encItemPath = encodeUrlComponent(itemVirtual)
                    val mtimeStr = if (entry.lastModified > 0L) dateFmt.format(Date(entry.lastModified)) else ""

                    if (entry.isDirectory) {
                        val countNote = when {
                            entry.childCount < 0 -> "Folder"
                            entry.childCount == 1 -> "1 item"
                            else -> "${entry.childCount} items"
                        }
                        val detail = if (mtimeStr.isNotEmpty()) "$countNote &middot; $mtimeStr" else countNote
                        append("  <div class=\"row\">\n")
                        append("    <a class=\"row-main\" href=\"/browse?path=").append(encItemPath).append("\">\n")
                        append("      <span class=\"icon folder\" aria-hidden=\"true\">&#128193;</span>\n")
                        append("      <span class=\"meta\"><span class=\"name\">").append(escapeHtml(entry.name))
                            .append("</span><span class=\"detail\">").append(detail).append("</span></span>\n")
                        append("    </a>\n")
                        append("    <div class=\"row-actions\">\n")
                        append("      <a class=\"btn-sm\" href=\"/zip?path=").append(encItemPath)
                            .append("\" title=\"Download folder as ZIP\">.zip</a>\n")
                        append("    </div>\n")
                        append("  </div>\n")
                    } else {
                        val sizeStr = PathUtils.formatBytes(entry.size)
                        val detail = if (mtimeStr.isNotEmpty()) "$sizeStr &middot; $mtimeStr" else sizeStr
                        append("  <div class=\"row\">\n")
                        append("    <a class=\"row-main\" href=\"/raw?path=").append(encItemPath).append("\">\n")
                        append("      <span class=\"icon file\" aria-hidden=\"true\">").append(iconEntityForFile(entry.name)).append("</span>\n")
                        append("      <span class=\"meta\"><span class=\"name\">").append(escapeHtml(entry.name))
                            .append("</span><span class=\"detail\">").append(detail).append("</span></span>\n")
                        append("    </a>\n")
                        append("    <div class=\"row-actions\">\n")
                        append("      <a class=\"btn-sm btn-dl\" href=\"/raw?path=").append(encItemPath)
                            .append("&amp;download=1\" download>Download</a>\n")
                        append("    </div>\n")
                        append("  </div>\n")
                    }
                }
            }
            append("</main>\n")
            append("</div>\n")

            if (!readOnly) {
                append("<script>\n")
                append("const CUR_PATH = '").append(jsCurPath).append("';\n")
                append(WEB_UI_SCRIPT)
                append("\n</script>\n")
            }
            append("</body>\n</html>\n")
        }
    }

    companion object {
        internal fun parseByteRange(rangeHeader: String, totalSize: Long): Pair<Long, Long>? {
            val trimmed = rangeHeader.trim()
            if (!trimmed.startsWith("bytes=", ignoreCase = true)) return null
            if (totalSize <= 0L) return null
            val spec = trimmed.substring(6).trim()
            if (spec.isEmpty() || spec.contains(',')) return null
            val dash = spec.indexOf('-')
            if (dash < 0) return null

            val startPart = spec.substring(0, dash).trim()
            val endPart = spec.substring(dash + 1).trim()

            return if (startPart.isEmpty()) {
                // Suffix range: bytes=-N
                val suffix = endPart.toLongOrNull() ?: return null
                if (suffix <= 0L) return null
                val start = (totalSize - suffix).coerceAtLeast(0L)
                start to (totalSize - 1L)
            } else {
                val start = startPart.toLongOrNull() ?: return null
                if (start < 0L || start >= totalSize) return null
                val end = if (endPart.isEmpty()) {
                    totalSize - 1L
                } else {
                    val parsedEnd = endPart.toLongOrNull() ?: return null
                    if (parsedEnd < start) return null
                    minOf(parsedEnd, totalSize - 1L)
                }
                start to end
            }
        }

        internal fun parseQueryString(rawQuery: String): Map<String, String> {
            if (rawQuery.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq >= 0) {
                    val key = decodeQueryComponent(pair.substring(0, eq))
                    val value = decodeQueryComponent(pair.substring(eq + 1))
                    out[key] = value
                } else {
                    out[decodeQueryComponent(pair)] = ""
                }
            }
            return out
        }

        private fun decodeQueryComponent(raw: String): String {
            return try {
                URLDecoder.decode(raw, "UTF-8")
            } catch (_: IllegalArgumentException) {
                throw FtpAccessDeniedException("Malformed URL encoding")
            }
        }

        private fun decodePercentUtf8(raw: String): String {
            // Unlike application/x-www-form-urlencoded, '+' in a URI path is literal '+'
            return try {
                URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
            } catch (_: IllegalArgumentException) {
                throw FtpAccessDeniedException("Malformed URI percent-encoding")
            }
        }

        internal fun encodeUrlComponent(value: String): String =
            URLEncoder.encode(value, "UTF-8")
                .replace("+", "%20")
                .replace("%2F", "/")

        internal fun buildContentDisposition(dispositionType: String, fileName: String): String {
            val asciiSafe = buildString(fileName.length) {
                for (ch in fileName) {
                    when {
                        ch == '"' || ch == '\\' || ch.code < 0x20 -> append('_')
                        ch.code in 0x20..0x7E -> append(ch)
                        else -> append('_')
                    }
                }
            }.ifBlank { "download" }
            val rfc5987 = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
            return "$dispositionType; filename=\"$asciiSafe\"; filename*=UTF-8''$rfc5987"
        }

        private fun resolveHttpContentType(node: VfsNode): String {
            val base = node.resolvedMimeType
            return if (base.startsWith("text/") && !base.contains("charset", ignoreCase = true)) {
                "$base; charset=utf-8"
            } else {
                base
            }
        }

        private fun extractMultipartBoundary(contentType: String): String? {
            for (param in contentType.split(';').drop(1)) {
                val trimmed = param.trim()
                if (trimmed.startsWith("boundary=", ignoreCase = true)) {
                    val raw = trimmed.substring("boundary=".length).trim().trim('"')
                    if (raw.isNotEmpty() && raw.length <= 200) return raw
                }
            }
            return null
        }

        private fun extractDispositionFileName(disposition: String): String? {
            if (disposition.isEmpty()) return null
            // Prefer RFC 5987 filename*=UTF-8''... if present
            for (part in disposition.split(';')) {
                val t = part.trim()
                if (t.startsWith("filename*=", ignoreCase = true)) {
                    val rawVal = t.substring("filename*=".length).trim().trim('"')
                    val encoded = rawVal.substringAfter("''", rawVal)
                    return runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }.getOrNull()
                }
            }
            for (part in disposition.split(';')) {
                val t = part.trim()
                if (t.startsWith("filename=", ignoreCase = true)) {
                    val v = t.substring("filename=".length).trim()
                    val unquoted = if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) {
                        v.substring(1, v.length - 1)
                    } else {
                        v
                    }
                    return unquoted
                }
            }
            return null
        }

        private fun readHeaderLine(input: InputStream, maxBytes: Int): String? {
            val buf = ByteArrayOutputStream(128)
            var sawAny = false
            while (true) {
                val b = input.read()
                if (b < 0) {
                    return if (sawAny) buf.toString(Charsets.UTF_8.name()).trimEnd('\r') else null
                }
                sawAny = true
                if (b == '\n'.code) {
                    break
                }
                buf.write(b)
                if (buf.size() > maxBytes) {
                    throw HttpStatusException(414, "URI Too Long", "Header line exceeds maximum length")
                }
            }
            val bytes = buf.toByteArray()
            val len = if (bytes.isNotEmpty() && bytes[bytes.lastIndex] == '\r'.code.toByte()) {
                bytes.size - 1
            } else {
                bytes.size
            }
            return String(bytes, 0, len, Charsets.UTF_8)
        }

        private fun copyExact(input: InputStream, output: OutputStream, byteCount: Long): Long {
            var remaining = byteCount
            var totalSent = 0L
            val buf = ByteArray(64 * 1024)
            while (remaining > 0L) {
                val toRead = minOf(remaining, buf.size.toLong()).toInt()
                val n = input.read(buf, 0, toRead)
                if (n < 0) break
                if (n > 0) {
                    output.write(buf, 0, n)
                    remaining -= n
                    totalSent += n
                }
            }
            return totalSent
        }

        private fun writeHeaders(
            out: OutputStream,
            status: Int,
            reason: String,
            headers: List<Pair<String, String>>,
        ) {
            val sb = StringBuilder(256)
            sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            for ((k, v) in headers) {
                sb.append(k).append(": ").append(v).append("\r\n")
            }
            sb.append("Date: ").append(httpDateNow()).append("\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        }

        private fun writeSimpleResponse(
            out: OutputStream,
            status: Int,
            reason: String,
            contentType: String,
            body: ByteArray,
            headOnly: Boolean,
            closeConnection: Boolean,
        ) {
            writeHeaders(
                out = out,
                status = status,
                reason = reason,
                headers = listOf(
                    "Content-Type" to contentType,
                    "Content-Length" to body.size.toString(),
                    "Cache-Control" to "no-store",
                    "Connection" to if (closeConnection) "close" else "keep-alive",
                ),
            )
            if (!headOnly && body.isNotEmpty()) {
                out.write(body)
            }
            out.flush()
        }

        private fun writeJsonError(
            out: OutputStream,
            status: Int,
            reason: String,
            message: String,
            headOnly: Boolean,
            close: Boolean,
        ) {
            val payload = MiniJson.stringify(
                MiniJson.obj(
                    "ok" to JsonValue.Bool(false),
                    "error" to JsonValue.Str(message),
                ),
            ).toByteArray(Charsets.UTF_8)
            writeSimpleResponse(
                out = out,
                status = status,
                reason = reason,
                contentType = "application/json; charset=utf-8",
                body = payload,
                headOnly = headOnly,
                closeConnection = close,
            )
        }

        private fun httpDateNow(): String {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("GMT")
            return fmt.format(Date())
        }

        internal fun escapeHtml(text: String): String = buildString(text.length + 16) {
            for (ch in text) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(ch)
                }
            }
        }

        private fun escapeJsSingleQuoted(text: String): String = buildString(text.length + 8) {
            for (ch in text) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '\'' -> append("\\'")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '<' -> append("\\x3c")
                    '>' -> append("\\x3e")
                    else -> append(ch)
                }
            }
        }

        private fun iconEntityForFile(name: String): String {
            return when (PathUtils.extensionOf(name)) {
                "pdf" -> "&#128213;"
                "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic" -> "&#128444;&#65039;"
                "mp4", "mkv", "webm", "mov", "avi" -> "&#127916;"
                "mp3", "flac", "wav", "ogg", "m4a", "aac" -> "&#127925;"
                "zip", "jar", "7z", "rar", "gz", "tgz" -> "&#128230;"
                "apk" -> "&#129302;"
                else -> "&#128196;"
            }
        }

        private val WEB_UI_CSS = """
            :root {
              color-scheme: light dark;
              --bg: #f6f8fc;
              --card: #ffffff;
              --fg: #1b1c1f;
              --muted: #5f6368;
              --border: #e1e5ec;
              --primary: #0b57d0;
              --primary-fg: #ffffff;
              --tonal-bg: #e8f0fe;
              --tonal-fg: #0b57d0;
              --hover: rgba(11, 87, 208, 0.06);
            }
            @media (prefers-color-scheme: dark) {
              :root {
                --bg: #111318;
                --card: #1b1e24;
                --fg: #e3e5ea;
                --muted: #9aa0a6;
                --border: #2c313a;
                --primary: #a8c7fa;
                --primary-fg: #062e6f;
                --tonal-bg: #1f3354;
                --tonal-fg: #d3e3fd;
                --hover: rgba(168, 199, 250, 0.08);
              }
            }
            * { box-sizing: border-box; }
            body {
              margin: 0;
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
              background: var(--bg);
              color: var(--fg);
              line-height: 1.45;
            }
            .container { max-width: 920px; margin: 0 auto; padding: 16px; }
            .topbar {
              display: flex; flex-wrap: wrap; gap: 12px;
              align-items: center; justify-content: space-between;
              margin-bottom: 12px;
            }
            .brand { display: flex; align-items: center; gap: 10px; }
            .logo { font-size: 28px; }
            h1 { margin: 0; font-size: 20px; font-weight: 650; }
            .sub { font-size: 13px; color: var(--muted); }
            .badge-ro {
              display: inline-block; padding: 1px 8px; border-radius: 999px;
              background: var(--tonal-bg); color: var(--tonal-fg); font-weight: 600; font-size: 11px;
            }
            .actions { display: flex; flex-wrap: wrap; gap: 8px; }
            .btn {
              display: inline-flex; align-items: center; justify-content: center;
              padding: 9px 14px; border-radius: 999px; font-size: 14px; font-weight: 600;
              text-decoration: none; border: none; cursor: pointer; min-height: 40px;
            }
            .btn-primary { background: var(--primary); color: var(--primary-fg); }
            .btn-tonal { background: var(--tonal-bg); color: var(--tonal-fg); }
            .crumbs {
              padding: 10px 14px; background: var(--card); border: 1px solid var(--border);
              border-radius: 14px; margin-bottom: 12px; font-size: 14px; overflow-x: auto; white-space: nowrap;
            }
            .crumbs a { color: var(--primary); text-decoration: none; font-weight: 500; }
            .crumbs a:hover { text-decoration: underline; }
            .sep { color: var(--muted); margin: 0 4px; }
            .upload-card {
              padding: 12px 16px; background: var(--card); border: 1px solid var(--border);
              border-radius: 14px; margin-bottom: 12px;
            }
            .hidden { display: none; }
            .upload-row { display: flex; justify-content: space-between; font-size: 13px; margin-bottom: 6px; }
            .progress-track { height: 8px; background: var(--border); border-radius: 999px; overflow: hidden; }
            .progress-fill { height: 100%; width: 0%; background: var(--primary); transition: width 0.12s linear; }
            .card {
              background: var(--card); border: 1px solid var(--border);
              border-radius: 16px; overflow: hidden;
            }
            .row {
              display: flex; align-items: center; justify-content: space-between;
              border-bottom: 1px solid var(--border); padding: 4px 12px 4px 4px;
            }
            .row:last-child { border-bottom: none; }
            .row:hover { background: var(--hover); }
            .row-main {
              display: flex; align-items: center; gap: 12px; flex: 1; min-width: 0;
              padding: 8px 10px; text-decoration: none; color: inherit;
            }
            .icon { font-size: 22px; flex-shrink: 0; width: 28px; text-align: center; }
            .meta { display: flex; flex-direction: column; min-width: 0; }
            .name { font-size: 15px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
            .detail { font-size: 12px; color: var(--muted); }
            .row-actions { flex-shrink: 0; margin-left: 8px; }
            .btn-sm {
              display: inline-block; padding: 6px 12px; border-radius: 999px;
              background: var(--tonal-bg); color: var(--tonal-fg);
              text-decoration: none; font-size: 12px; font-weight: 600;
            }
            .empty { padding: 36px 16px; text-align: center; color: var(--muted); font-size: 15px; }
        """.trimIndent()

        private val WEB_UI_SCRIPT = """
            async function promptMkdir() {
              const name = window.prompt('New folder name:');
              if (!name || !name.trim()) return;
              const url = '/mkdir?path=' + encodeURIComponent(CUR_PATH) + '&name=' + encodeURIComponent(name.trim());
              const res = await fetch(url, { method: 'POST' });
              if (!res.ok) {
                const data = await res.json().catch(() => ({}));
                alert(data.error || ('Failed to create folder (' + res.status + ')'));
                return;
              }
              window.location.reload();
            }

            function sendSingleFile(file, onProgress) {
              return new Promise((resolve, reject) => {
                const xhr = new XMLHttpRequest();
                const url = '/upload?path=' + encodeURIComponent(CUR_PATH) + '&name=' + encodeURIComponent(file.name);
                xhr.open('POST', url, true);
                xhr.setRequestHeader('Content-Type', 'application/octet-stream');
                xhr.upload.onprogress = (ev) => {
                  if (ev.lengthComputable) onProgress(ev.loaded, ev.total);
                };
                xhr.onload = () => {
                  if (xhr.status >= 200 && xhr.status < 300) resolve();
                  else {
                    let msg = 'Upload failed (' + xhr.status + ')';
                    try { msg = JSON.parse(xhr.responseText).error || msg; } catch (_) {}
                    reject(new Error(msg));
                  }
                };
                xhr.onerror = () => reject(new Error('Network error during upload'));
                xhr.send(file);
              });
            }

            async function uploadFiles(fileList) {
              if (!fileList || fileList.length === 0) return;
              const files = Array.from(fileList);
              const card = document.getElementById('upload-card');
              const statusEl = document.getElementById('upload-status');
              const pctEl = document.getElementById('upload-pct');
              const barEl = document.getElementById('upload-bar');
              card.classList.remove('hidden');

              const totalBytes = files.reduce((acc, f) => acc + (f.size || 0), 0);
              let completedBytes = 0;

              try {
                for (let i = 0; i < files.length; i++) {
                  const f = files[i];
                  statusEl.textContent = 'Uploading (' + (i + 1) + '/' + files.length + '): ' + f.name;
                  await sendSingleFile(f, (loaded) => {
                    const pct = totalBytes > 0 ? Math.min(100, Math.round(((completedBytes + loaded) / totalBytes) * 100)) : 100;
                    pctEl.textContent = pct + '%';
                    barEl.style.width = pct + '%';
                  });
                  completedBytes += (f.size || 0);
                  const pct = totalBytes > 0 ? Math.min(100, Math.round((completedBytes / totalBytes) * 100)) : 100;
                  pctEl.textContent = pct + '%';
                  barEl.style.width = pct + '%';
                }
                statusEl.textContent = 'Upload complete! Refreshing…';
                window.location.reload();
              } catch (err) {
                alert(err.message || 'Upload failed');
                card.classList.add('hidden');
              }
            }

            document.addEventListener('dragover', (e) => { e.preventDefault(); });
            document.addEventListener('drop', (e) => {
              e.preventDefault();
              if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length > 0) {
                uploadFiles(e.dataTransfer.files);
              }
            });
        """.trimIndent()
    }
}

private object NullOutputStream : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
    var bytesWritten: Long = 0L
        private set

    override fun write(b: Int) {
        delegate.write(b)
        bytesWritten++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        bytesWritten += len
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.flush()
}

private class BoundedInputStream(
    private val delegate: InputStream,
    private var remaining: Long,
) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0L) return -1
        val b = delegate.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (remaining <= 0L) return -1
        val toRead = minOf(len.toLong(), remaining).toInt()
        val n = delegate.read(b, off, toRead)
        if (n > 0) remaining -= n
        return n
    }
}

private class ChunkedInputStream(private val delegate: InputStream) : InputStream() {
    private var chunkRemaining = 0L
    private var finished = false

    override fun read(): Int {
        val single = ByteArray(1)
        val n = read(single, 0, 1)
        return if (n < 0) -1 else (single[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (finished) return -1
        if (chunkRemaining == 0L) {
            advanceChunk()
            if (finished) return -1
        }
        val toRead = minOf(len.toLong(), chunkRemaining).toInt()
        val n = delegate.read(b, off, toRead)
        if (n < 0) throw EOFException("Unexpected EOF inside HTTP chunk")
        chunkRemaining -= n
        if (chunkRemaining == 0L) {
            expectCrlf()
        }
        return n
    }

    private fun advanceChunk() {
        val line = readAsciiLine()
        val sizeHex = line.substringBefore(';').trim()
        val size = sizeHex.toLongOrNull(16)
            ?: throw IOException("Invalid chunk size: $sizeHex")
        if (size == 0L) {
            finished = true
            // Consume optional trailer headers up to empty line
            while (readAsciiLine().isNotEmpty()) {
                // ignore trailer
            }
        } else {
            chunkRemaining = size
        }
    }

    private fun expectCrlf() {
        val b1 = delegate.read()
        if (b1 == '\r'.code) {
            delegate.read()
        }
    }

    private fun readAsciiLine(): String {
        val sb = StringBuilder(32)
        while (true) {
            val b = delegate.read()
            if (b < 0) throw EOFException("Unexpected EOF reading chunk header")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 4096) throw IOException("Chunk header line too long")
        }
        return sb.toString()
    }
}

private class MultipartStreamReader(private val input: InputStream) {
    private val buf = ByteArray(64 * 1024)
    private var pos = 0
    private var limit = 0
    private var eof = false

    private fun fill(minAvailable: Int): Boolean {
        if (limit - pos >= minAvailable) return true
        if (pos > 0) {
            val rem = limit - pos
            if (rem > 0) {
                System.arraycopy(buf, pos, buf, 0, rem)
            }
            pos = 0
            limit = rem
        }
        while (!eof && limit - pos < minAvailable && limit < buf.size) {
            val n = input.read(buf, limit, buf.size - limit)
            if (n < 0) {
                eof = true
                break
            }
            if (n > 0) limit += n
        }
        return limit - pos >= minAvailable
    }

    fun readCrlfLine(maxBytes: Int): String? {
        val lineOut = ByteArrayOutputStream(128)
        while (true) {
            if (!fill(1)) {
                return if (lineOut.size() > 0) {
                    lineOut.toString(Charsets.UTF_8.name()).trimEnd('\r')
                } else {
                    null
                }
            }
            val b = buf[pos++]
            if (b == '\n'.code.toByte()) {
                break
            }
            lineOut.write(b.toInt() and 0xFF)
            if (lineOut.size() > maxBytes) {
                throw HttpStatusException(400, "Bad Request", "Multipart header line too long")
            }
        }
        val bytes = lineOut.toByteArray()
        val len = if (bytes.isNotEmpty() && bytes[bytes.lastIndex] == '\r'.code.toByte()) {
            bytes.size - 1
        } else {
            bytes.size
        }
        return String(bytes, 0, len, Charsets.UTF_8)
    }

    /**
     * Streams part content into [out] up to [delimiter] (`\r\n--boundary`), consuming the trailing
     * `\r\n` (more parts -> second=false) or `--[\r\n]` (final boundary -> second=true).
     */
    fun streamUntilBoundary(delimiter: ByteArray, out: OutputStream): Pair<Long, Boolean> {
        var totalWritten = 0L
        while (true) {
            fill(delimiter.size + 4)
            val avail = limit - pos
            if (avail <= 0) {
                throw HttpStatusException(400, "Bad Request", "Unexpected EOF before multipart boundary")
            }
            val matchOffset = indexOfSubArray(buf, pos, limit, delimiter)
            if (matchOffset >= 0) {
                val chunkLen = matchOffset - pos
                if (chunkLen > 0) {
                    out.write(buf, pos, chunkLen)
                    totalWritten += chunkLen
                }
                pos = matchOffset + delimiter.size
                fill(4)
                if (limit - pos >= 2 && buf[pos] == '-'.code.toByte() && buf[pos + 1] == '-'.code.toByte()) {
                    pos += 2
                    if (limit - pos >= 2 && buf[pos] == '\r'.code.toByte() && buf[pos + 1] == '\n'.code.toByte()) {
                        pos += 2
                    } else if (limit - pos >= 1 && buf[pos] == '\n'.code.toByte()) {
                        pos += 1
                    }
                    return totalWritten to true
                }
                if (limit - pos >= 2 && buf[pos] == '\r'.code.toByte() && buf[pos + 1] == '\n'.code.toByte()) {
                    pos += 2
                    return totalWritten to false
                }
                if (limit - pos >= 1 && buf[pos] == '\n'.code.toByte()) {
                    pos += 1
                    return totalWritten to false
                }
                throw HttpStatusException(400, "Bad Request", "Malformed multipart boundary terminator")
            }

            if (eof) {
                throw HttpStatusException(400, "Bad Request", "Missing closing multipart boundary")
            }

            val keepTail = delimiter.size - 1
            val safeFlush = avail - keepTail
            if (safeFlush > 0) {
                out.write(buf, pos, safeFlush)
                totalWritten += safeFlush
                pos += safeFlush
            } else {
                // Force at least one more byte read
                val prevLimit = limit
                fill(avail + 1)
                if (limit == prevLimit && eof) {
                    throw HttpStatusException(400, "Bad Request", "Truncated multipart stream")
                }
            }
        }
    }

    private fun indexOfSubArray(haystack: ByteArray, from: Int, to: Int, needle: ByteArray): Int {
        val maxStart = to - needle.size
        if (maxStart < from) return -1
        val first = needle[0]
        outer@ for (i in from..maxStart) {
            if (haystack[i] != first) continue
            for (j in 1 until needle.size) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
