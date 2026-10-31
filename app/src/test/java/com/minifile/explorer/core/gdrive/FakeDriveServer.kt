package com.minifile.explorer.core.gdrive

import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.MiniJson
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Pure-JVM loopback fake of Google Drive REST API v3 (`/drive/v3` and `/upload/drive/v3`) (§5).
 *
 * Strictly enforces:
 * - HTTP 411 when any `POST`, `PATCH`, or `PUT` lacks `Content-Length` or uses `Transfer-Encoding: chunked` (B2).
 * - URL-encoding of all request paths and query strings (no raw space or unencoded `'`) (m7).
 */
class FakeDriveServer(
    initialValidToken: String = "tok_test_valid_initial_token_01",
    var userEmail: String = "alice@example.com",
    var quotaLimit: Long? = 15L * 1024 * 1024 * 1024,
    var quotaUsage: Long = 3L * 1024 * 1024 * 1024,
) : Closeable {

    data class FakeEntry(
        val id: String,
        @Volatile var name: String,
        val mimeType: String,
        val parents: MutableList<String>,
        @Volatile var content: ByteArray = ByteArray(0),
        @Volatile var exportedPdfBytes: ByteArray = "%PDF-1.4 fake export\n".toByteArray(Charsets.UTF_8),
        val createdTimeMs: Long = 1_700_000_000_000L,
        @Volatile var modifiedTimeMs: Long = 1_700_000_000_000L,
        @Volatile var trashed: Boolean = false,
        @Volatile var canEdit: Boolean = true,
        val shortcutTargetId: String? = null,
        val shortcutTargetMimeType: String? = null,
        @Volatile var forbidTrash: Boolean = false,
        @Volatile var forbidDelete: Boolean = false,
        @Volatile var tooLargeToExport: Boolean = false,
    ) {
        val isFolder: Boolean get() = mimeType == FOLDER_MIME
    }

    data class RecordedRequest(
        val method: String,
        val rawPath: String,
        val rawQuery: String?,
        val queryParams: Map<String, String>,
        val contentLengthHeader: String?,
        val transferEncodingHeader: String?,
        val contentRangeHeader: String?,
        val rangeHeader: String?,
        val acceptEncodingHeader: String?,
        val bodyLength: Int,
    )

    private class UploadSession(
        val sessionId: String,
        val existingFileId: String?,
        val createName: String?,
        val createMimeType: String,
        val createParents: List<String>,
    ) {
        val buffer = ByteArrayOutputStream()
        @Volatile var completedEntry: FakeEntry? = null
    }

    private val pool = Executors.newCachedThreadPool()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = pool
        createContext("/") { exchange -> dispatch(exchange) }
        start()
    }

    val port: Int = server.address.port
    val apiBase: String = "http://127.0.0.1:$port/drive/v3"
    val uploadBase: String = "http://127.0.0.1:$port/upload/drive/v3"

    val validToken = AtomicReference(initialValidToken)
    val totalRequests = AtomicInteger(0)
    val listRequests = AtomicInteger(0)
    val filesQueryRequests = AtomicInteger(0)
    val putChunkRequests = CopyOnWriteArrayList<RecordedRequest>()
    val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()
    val protocolViolations = CopyOnWriteArrayList<String>()

    val fail401Once = AtomicBoolean(false)
    val fail503Times = AtomicInteger(0)
    val storageQuotaExceeded = AtomicBoolean(false)
    val ignoreRangeOnDownload = AtomicBoolean(false)
    val dropDownloadMidStreamTimes = AtomicInteger(0)
    @Volatile var dropDownloadAfterBytes: Int = 32 * 1024
    val dropChunkOnceAtBytes = AtomicInteger(0) // > 0 commits N bytes then fails with 503 once

    private val idCounter = AtomicInteger(1000)
    private val sessionCounter = AtomicInteger(1)
    private val entries = ConcurrentHashMap<String, FakeEntry>()
    private val uploadSessions = ConcurrentHashMap<String, UploadSession>()

    init {
        entries["root"] = FakeEntry(
            id = "root",
            name = "My Drive",
            mimeType = FOLDER_MIME,
            parents = mutableListOf(),
        )
    }

    fun nextId(prefix: String = "id"): String = "$prefix-${idCounter.incrementAndGet()}"

    fun putFolder(
        id: String = nextId("dir"),
        name: String,
        parentId: String = "root",
        createdTimeMs: Long = 1_700_000_000_000L,
        modifiedTimeMs: Long = createdTimeMs,
        canEdit: Boolean = true,
    ): FakeEntry {
        val e = FakeEntry(
            id = id,
            name = name,
            mimeType = FOLDER_MIME,
            parents = mutableListOf(parentId),
            createdTimeMs = createdTimeMs,
            modifiedTimeMs = modifiedTimeMs,
            canEdit = canEdit,
        )
        entries[id] = e
        return e
    }

    fun putFile(
        id: String = nextId("file"),
        name: String,
        parentId: String = "root",
        bytes: ByteArray = ByteArray(0),
        mimeType: String = "application/octet-stream",
        createdTimeMs: Long = 1_700_000_000_000L,
        modifiedTimeMs: Long = createdTimeMs,
        canEdit: Boolean = true,
        forbidTrash: Boolean = false,
        forbidDelete: Boolean = false,
    ): FakeEntry {
        val e = FakeEntry(
            id = id,
            name = name,
            mimeType = mimeType,
            parents = mutableListOf(parentId),
            content = bytes.copyOf(),
            createdTimeMs = createdTimeMs,
            modifiedTimeMs = modifiedTimeMs,
            canEdit = canEdit,
            forbidTrash = forbidTrash,
            forbidDelete = forbidDelete,
        )
        entries[id] = e
        return e
    }

    fun putWorkspaceDoc(
        id: String = nextId("doc"),
        name: String,
        parentId: String = "root",
        mimeType: String = "application/vnd.google-apps.document",
        exportedPdfBytes: ByteArray = "%PDF-1.4 exported $name\n".toByteArray(Charsets.UTF_8),
        createdTimeMs: Long = 1_700_000_000_000L,
        modifiedTimeMs: Long = createdTimeMs,
        tooLargeToExport: Boolean = false,
    ): FakeEntry {
        val e = FakeEntry(
            id = id,
            name = name,
            mimeType = mimeType,
            parents = mutableListOf(parentId),
            exportedPdfBytes = exportedPdfBytes,
            createdTimeMs = createdTimeMs,
            modifiedTimeMs = modifiedTimeMs,
            canEdit = true,
            tooLargeToExport = tooLargeToExport,
        )
        entries[id] = e
        return e
    }

    fun putShortcut(
        id: String = nextId("sc"),
        name: String,
        parentId: String = "root",
        targetId: String,
        targetMimeType: String,
        createdTimeMs: Long = 1_700_000_000_000L,
    ): FakeEntry {
        val e = FakeEntry(
            id = id,
            name = name,
            mimeType = SHORTCUT_MIME,
            parents = mutableListOf(parentId),
            createdTimeMs = createdTimeMs,
            modifiedTimeMs = createdTimeMs,
            shortcutTargetId = targetId,
            shortcutTargetMimeType = targetMimeType,
        )
        entries[id] = e
        return e
    }

    fun getEntry(id: String): FakeEntry? = entries[id]

    fun removeRawEntry(id: String) {
        entries.remove(id)
    }

    fun activeEntriesInParent(parentId: String): List<FakeEntry> =
        entries.values.filter { !it.trashed && parentId in it.parents }

    private fun dispatch(exchange: HttpExchange) {
        var keepSocketRawClose = false
        try {
            totalRequests.incrementAndGet()

            val rawUri = exchange.requestURI
            val rawPath = rawUri.rawPath ?: ""
            val rawQuery = rawUri.rawQuery
            if (' ' in exchange.rawRequestTarget || '\'' in exchange.rawRequestTarget) {
                val msg = "Unencoded space or quote in URI: ${exchange.rawRequestTarget}"
                protocolViolations.add(msg)
                sendDriveError(exchange, 400, "badRequest", msg)
                return
            }

            val wireMethod = exchange.requestMethod.uppercase()
            val overrideMethod = exchange.requestHeaders.getFirst("X-HTTP-Method-Override")?.uppercase()
            val method = if (wireMethod == "POST" && !overrideMethod.isNullOrBlank()) overrideMethod else wireMethod

            val clHeader = exchange.requestHeaders.getFirst("Content-Length")
            val teHeader = exchange.requestHeaders.getFirst("Transfer-Encoding")
            if (wireMethod == "POST" || wireMethod == "PATCH" || wireMethod == "PUT") {
                if (teHeader?.contains("chunked", ignoreCase = true) == true || clHeader == null) {
                    protocolViolations.add("Missing Content-Length or chunked Transfer-Encoding on $wireMethod $rawPath")
                    sendDriveError(exchange, 411, "lengthRequired", "Content-Length required")
                    return
                }
            }

            val bodyBytes = if (wireMethod == "POST" || wireMethod == "PATCH" || wireMethod == "PUT") {
                exchange.requestBody.use { it.readBytes() }
            } else {
                exchange.requestBody.use { it.readBytes() }
            }

            val queryParams = decodeQuery(rawQuery)
            val record = RecordedRequest(
                method = method,
                rawPath = rawPath,
                rawQuery = rawQuery,
                queryParams = queryParams,
                contentLengthHeader = clHeader,
                transferEncodingHeader = teHeader,
                contentRangeHeader = exchange.requestHeaders.getFirst("Content-Range"),
                rangeHeader = exchange.requestHeaders.getFirst("Range"),
                acceptEncodingHeader = exchange.requestHeaders.getFirst("Accept-Encoding"),
                bodyLength = bodyBytes.size,
            )
            recordedRequests.add(record)

            // Resumable upload session PUTs carry session capability in URL (no Bearer header required by Google)
            if (rawPath.startsWith("/upload/session/")) {
                putChunkRequests.add(record)
                val sessionId = decodeSegment(rawPath.removePrefix("/upload/session/"))
                handleSessionPut(exchange, sessionId, record, bodyBytes)
                return
            }

            // Bearer authentication check
            val authHeader = exchange.requestHeaders.getFirst("Authorization") ?: ""
            if (fail401Once.compareAndSet(true, false) || authHeader != "Bearer ${validToken.get()}") {
                sendDriveError(exchange, 401, "authError", "Invalid Credentials")
                return
            }

            // Fault injection: 503
            if (fail503Times.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                sendDriveError(exchange, 503, "backendError", "Simulated 503 Backend Error")
                return
            }

            // Upload initiation endpoints
            if (rawPath.startsWith("/upload/drive/v3/files")) {
                val sub = rawPath.removePrefix("/upload/drive/v3/files").trimStart('/')
                val existingId = sub.takeIf { it.isNotEmpty() }?.let(::decodeSegment)
                handleStartResumableUpload(exchange, method, existingId, queryParams, bodyBytes)
                return
            }

            // Standard /drive/v3 endpoints
            if (rawPath == "/drive/v3/about" && method == "GET") {
                handleAbout(exchange)
                return
            }

            if (rawPath == "/drive/v3/files") {
                when (method) {
                    "GET" -> handleFilesList(exchange, queryParams)
                    "POST" -> handleFilesCreate(exchange, bodyBytes)
                    else -> sendDriveError(exchange, 405, "methodNotAllowed", "Unsupported $method")
                }
                return
            }

            if (rawPath.startsWith("/drive/v3/files/")) {
                val tail = rawPath.removePrefix("/drive/v3/files/")
                val parts = tail.split('/').map(::decodeSegment)
                val fileId = parts[0]
                if (parts.size == 2 && parts[1] == "export" && method == "GET") {
                    handleFileExport(exchange, fileId)
                    return
                }
                if (parts.size == 1) {
                    when (method) {
                        "GET" -> {
                            if (queryParams["alt"] == "media") {
                                keepSocketRawClose = handleFileMediaDownload(exchange, fileId, record)
                            } else {
                                handleFileGetMeta(exchange, fileId)
                            }
                        }
                        "PATCH" -> handleFilePatch(exchange, fileId, queryParams, bodyBytes)
                        "DELETE" -> handleFileDelete(exchange, fileId)
                        else -> sendDriveError(exchange, 405, "methodNotAllowed", "Unsupported $method")
                    }
                    return
                }
            }

            sendDriveError(exchange, 404, "notFound", "Endpoint not found: $rawPath")
        } finally {
            if (!keepSocketRawClose) {
                exchange.close()
            }
        }
    }

    private fun handleAbout(exchange: HttpExchange) {
        val quotaEntries = LinkedHashMap<String, JsonValue>().apply {
            put("usage", JsonValue.Str(quotaUsage.toString()))
            quotaLimit?.let { put("limit", JsonValue.Str(it.toString())) }
        }
        val obj = MiniJson.obj(
            "storageQuota" to JsonValue.Obj(quotaEntries),
            "user" to MiniJson.obj("emailAddress" to JsonValue.Str(userEmail)),
        )
        sendJson(exchange, 200, obj)
    }

    private fun handleFilesList(exchange: HttpExchange, queryParams: Map<String, String>) {
        filesQueryRequests.incrementAndGet()
        val q = queryParams["q"].orEmpty()
        val parsedQ = parseDriveQuery(q)
        if (parsedQ.exactName == null) {
            listRequests.incrementAndGet()
        }

        // Verify parent folder exists (if not "root")
        if (parsedQ.parentId != null && parsedQ.parentId != "root") {
            val parentEntry = entries[parsedQ.parentId]
            if (parentEntry == null || parentEntry.trashed) {
                sendDriveError(exchange, 404, "notFound", "Parent not found: ${parsedQ.parentId}")
                return
            }
        }

        val matching = entries.values
            .filter { e ->
                if (e.id == "root") return@filter false
                if (parsedQ.trashedEqualsFalse && e.trashed) return@filter false
                if (parsedQ.parentId != null && parsedQ.parentId !in e.parents) return@filter false
                if (parsedQ.exactName != null && e.name != parsedQ.exactName) return@filter false
                if (parsedQ.allowedMimeTypes.isNotEmpty() && e.mimeType !in parsedQ.allowedMimeTypes) {
                    return@filter false
                }
                true
            }
            .sortedWith(
                compareByDescending<FakeEntry> { it.isFolder }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.createdTimeMs }
                    .thenBy { it.id },
            )

        val pageSize = queryParams["pageSize"]?.toIntOrNull()?.coerceAtLeast(1) ?: 100
        val startOffset = queryParams["pageToken"]
            ?.removePrefix("offset:")
            ?.toIntOrNull()
            ?.coerceAtLeast(0)
            ?: 0

        val slice = if (startOffset >= matching.size) {
            emptyList()
        } else {
            matching.subList(startOffset, minOf(matching.size, startOffset + pageSize))
        }
        val nextOffset = startOffset + slice.size
        val entriesMap = LinkedHashMap<String, JsonValue>().apply {
            if (nextOffset < matching.size) {
                put("nextPageToken", JsonValue.Str("offset:$nextOffset"))
            }
            put("files", JsonValue.Arr(slice.map(::entryToJson)))
        }
        sendJson(exchange, 200, JsonValue.Obj(entriesMap))
    }

    private fun handleFilesCreate(exchange: HttpExchange, bodyBytes: ByteArray) {
        if (storageQuotaExceeded.get()) {
            sendDriveError(exchange, 403, "storageQuotaExceeded", "The user's Drive storage quota has been exceeded.")
            return
        }
        val reqObj = (MiniJson.parse(bodyBytes.toString(Charsets.UTF_8)) as? JsonValue.Obj)
            ?: MiniJson.obj()
        val name = reqObj.str("name") ?: "untitled"
        val mime = reqObj.str("mimeType") ?: "application/octet-stream"
        val parents = reqObj.arr("parents")?.items?.mapNotNull { (it as? JsonValue.Str)?.value }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("root")
        val now = System.currentTimeMillis()
        val created = FakeEntry(
            id = nextId(if (mime == FOLDER_MIME) "dir" else "file"),
            name = name,
            mimeType = mime,
            parents = parents.toMutableList(),
            createdTimeMs = now,
            modifiedTimeMs = now,
        )
        entries[created.id] = created
        sendJson(exchange, 200, entryToJson(created))
    }

    private fun handleFileGetMeta(exchange: HttpExchange, fileId: String) {
        val entry = entries[fileId]
        if (entry == null) {
            sendDriveError(exchange, 404, "notFound", "File not found: $fileId")
            return
        }
        sendJson(exchange, 200, entryToJson(entry))
    }

    private fun handleFileExport(exchange: HttpExchange, fileId: String) {
        val entry = entries[fileId]
        if (entry == null || entry.trashed) {
            sendDriveError(exchange, 404, "notFound", "File not found: $fileId")
            return
        }
        if (entry.tooLargeToExport) {
            sendDriveError(
                exchange,
                403,
                "exportSizeLimitExceeded",
                "This file is too large to be exported.",
            )
            return
        }
        val pdf = entry.exportedPdfBytes
        exchange.responseHeaders.set("Content-Type", PDF_MIME)
        sendRawBytes(exchange, 200, pdf)
    }

    /**
     * Returns `true` when the exchange was abruptly closed mid-body to simulate a TCP drop.
     */
    private fun handleFileMediaDownload(
        exchange: HttpExchange,
        fileId: String,
        record: RecordedRequest,
    ): Boolean {
        val entry = entries[fileId]
        if (entry == null || entry.trashed) {
            sendDriveError(exchange, 404, "notFound", "File not found: $fileId")
            return false
        }
        val full = entry.content
        val rangeHeader = record.rangeHeader

        val status: Int
        val payload: ByteArray
        if (rangeHeader != null && rangeHeader.startsWith("bytes=") && !ignoreRangeOnDownload.get()) {
            val spec = rangeHeader.removePrefix("bytes=")
            val dash = spec.indexOf('-')
            val start = spec.substring(0, dash).toIntOrNull()?.coerceAtLeast(0) ?: 0
            val endInclusive = spec.substring(dash + 1).takeIf { it.isNotBlank() }?.toIntOrNull()
                ?: (full.size - 1)
            if (full.isEmpty() && start == 0) {
                status = 206
                payload = ByteArray(0)
            } else if (start >= full.size) {
                exchange.responseHeaders.set("Content-Range", "bytes */${full.size}")
                sendDriveError(exchange, 416, "requestedRangeNotSatisfiable", "Range out of bounds")
                return false
            } else {
                val clampedEnd = endInclusive.coerceAtMost(full.size - 1)
                payload = full.copyOfRange(start, clampedEnd + 1)
                exchange.responseHeaders.set("Content-Range", "bytes $start-$clampedEnd/${full.size}")
                status = 206
            }
        } else {
            status = 200
            payload = full
        }

        if (dropDownloadMidStreamTimes.getAndUpdate { if (it > 0) it - 1 else 0 } > 0 &&
            payload.size > dropDownloadAfterBytes
        ) {
            exchange.responseHeaders.set("Content-Type", entry.mimeType)
            exchange.sendResponseHeaders(status, payload.size.toLong())
            val out = exchange.responseBody
            out.write(payload, 0, dropDownloadAfterBytes)
            out.flush()
            exchange.close()
            return true
        }

        exchange.responseHeaders.set("Content-Type", entry.mimeType)
        sendRawBytes(exchange, status, payload)
        return false
    }

    private fun handleFilePatch(
        exchange: HttpExchange,
        fileId: String,
        queryParams: Map<String, String>,
        bodyBytes: ByteArray,
    ) {
        val entry = entries[fileId]
        if (entry == null) {
            sendDriveError(exchange, 404, "notFound", "File not found: $fileId")
            return
        }
        val bodyObj = if (bodyBytes.isNotEmpty()) {
            (MiniJson.parse(bodyBytes.toString(Charsets.UTF_8)) as? JsonValue.Obj) ?: MiniJson.obj()
        } else {
            MiniJson.obj()
        }

        if (bodyObj.bool("trashed", false)) {
            if (entry.forbidTrash) {
                sendDriveError(
                    exchange,
                    403,
                    "insufficientFilePermissions",
                    "The user does not have sufficient permissions for this file.",
                )
                return
            }
            entry.trashed = true
        }
        bodyObj.str("name")?.let { entry.name = it }
        bodyObj.str("modifiedTime")?.let { rawIso ->
            val parsedMs = parseRfc3339Ms(rawIso)
            if (parsedMs > 0L) entry.modifiedTimeMs = parsedMs
        }

        queryParams["removeParents"]?.split(',')?.filter { it.isNotBlank() }?.forEach { rem ->
            entry.parents.remove(rem)
        }
        queryParams["addParents"]?.split(',')?.filter { it.isNotBlank() }?.forEach { add ->
            if (add !in entry.parents) entry.parents.add(add)
        }

        sendJson(exchange, 200, entryToJson(entry))
    }

    private fun handleFileDelete(exchange: HttpExchange, fileId: String) {
        val entry = entries[fileId]
        if (entry == null) {
            sendDriveError(exchange, 404, "notFound", "File not found: $fileId")
            return
        }
        if (entry.forbidDelete) {
            sendDriveError(
                exchange,
                403,
                "insufficientFilePermissions",
                "Cannot delete shared item.",
            )
            return
        }
        entries.remove(fileId)
        exchange.responseHeaders.set("Content-Length", "0")
        exchange.sendResponseHeaders(204, -1L)
    }

    private fun handleStartResumableUpload(
        exchange: HttpExchange,
        method: String,
        existingId: String?,
        queryParams: Map<String, String>,
        bodyBytes: ByteArray,
    ) {
        if (queryParams["uploadType"] != "resumable") {
            sendDriveError(exchange, 400, "badRequest", "Expected uploadType=resumable")
            return
        }
        if (storageQuotaExceeded.get()) {
            sendDriveError(exchange, 403, "storageQuotaExceeded", "The user's Drive storage quota has been exceeded.")
            return
        }
        if (existingId != null) {
            val target = entries[existingId]
            if (target == null || target.trashed) {
                sendDriveError(exchange, 404, "notFound", "File not found: $existingId")
                return
            }
        }
        val bodyObj = if (bodyBytes.isNotEmpty()) {
            (MiniJson.parse(bodyBytes.toString(Charsets.UTF_8)) as? JsonValue.Obj) ?: MiniJson.obj()
        } else {
            MiniJson.obj()
        }
        val uploadMime = exchange.requestHeaders.getFirst("X-Upload-Content-Type")
            ?: bodyObj.str("mimeType")
            ?: "application/octet-stream"
        val parents = bodyObj.arr("parents")?.items?.mapNotNull { (it as? JsonValue.Str)?.value }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("root")

        val sessionId = "sess-${sessionCounter.incrementAndGet()}"
        val session = UploadSession(
            sessionId = sessionId,
            existingFileId = existingId,
            createName = bodyObj.str("name"),
            createMimeType = uploadMime,
            createParents = parents,
        )
        uploadSessions[sessionId] = session
        exchange.responseHeaders.set("Location", "http://127.0.0.1:$port/upload/session/$sessionId")
        exchange.responseHeaders.set("Content-Length", "0")
        exchange.sendResponseHeaders(200, -1L)
    }

    private fun handleSessionPut(
        exchange: HttpExchange,
        sessionId: String,
        record: RecordedRequest,
        bodyBytes: ByteArray,
    ) {
        val session = uploadSessions[sessionId]
        if (session == null) {
            sendDriveError(exchange, 404, "notFound", "Upload session not found: $sessionId")
            return
        }
        val contentRange = record.contentRangeHeader.orEmpty()
        if (!contentRange.startsWith("bytes ")) {
            sendDriveError(exchange, 400, "badRequest", "Missing Content-Range")
            return
        }

        val spec = contentRange.removePrefix("bytes ").trim()
        val slash = spec.indexOf('/')
        if (slash <= 0) {
            sendDriveError(exchange, 400, "badRequest", "Malformed Content-Range: $contentRange")
            return
        }
        val rangePart = spec.substring(0, slash)
        val totalPart = spec.substring(slash + 1)

        // Status query or 0-byte finalize: "bytes */*" or "bytes */0"
        if (rangePart == "*") {
            session.completedEntry?.let { done ->
                sendJson(exchange, 200, entryToJson(done))
                return
            }
            val totalKnown = totalPart.toLongOrNull()
            val committed = session.buffer.size()
            if (totalKnown != null && committed.toLong() == totalKnown) {
                val finalized = finalizeSession(session)
                sendJson(exchange, 200, entryToJson(finalized))
                return
            }
            if (committed > 0) {
                exchange.responseHeaders.set("Range", "bytes=0-${committed - 1}")
            }
            exchange.responseHeaders.set("Content-Length", "0")
            exchange.sendResponseHeaders(308, -1L)
            return
        }

        val dash = rangePart.indexOf('-')
        val start = rangePart.substring(0, dash).toInt()
        val endInclusive = rangePart.substring(dash + 1).toInt()
        val expectedLen = endInclusive - start + 1
        if (expectedLen != bodyBytes.size) {
            sendDriveError(
                exchange,
                400,
                "badRequest",
                "Body length ${bodyBytes.size} != Content-Range length $expectedLen",
            )
            return
        }

        // Fault injection: commit partial prefix of chunk then return 503 so client queries & resumes
        val partialCommit = dropChunkOnceAtBytes.getAndSet(0)
        if (partialCommit > 0 && partialCommit <= bodyBytes.size) {
            synchronized(session) {
                if (start == session.buffer.size()) {
                    session.buffer.write(bodyBytes, 0, partialCommit)
                }
            }
            sendDriveError(exchange, 503, "backendError", "Simulated mid-chunk failure after $partialCommit bytes")
            return
        }

        synchronized(session) {
            val curSize = session.buffer.size()
            if (start > curSize) {
                sendDriveError(exchange, 400, "badRequest", "Out-of-order chunk start=$start curSize=$curSize")
                return
            }
            val overlap = curSize - start
            if (overlap < bodyBytes.size) {
                session.buffer.write(bodyBytes, overlap, bodyBytes.size - overlap)
            }
        }

        if (totalPart == "*") {
            val committed = session.buffer.size()
            exchange.responseHeaders.set("Range", "bytes=0-${committed - 1}")
            exchange.responseHeaders.set("Content-Length", "0")
            exchange.sendResponseHeaders(308, -1L)
            return
        }

        val totalSize = totalPart.toInt()
        if (session.buffer.size() < totalSize) {
            val committed = session.buffer.size()
            if (committed > 0) {
                exchange.responseHeaders.set("Range", "bytes=0-${committed - 1}")
            }
            exchange.responseHeaders.set("Content-Length", "0")
            exchange.sendResponseHeaders(308, -1L)
            return
        }

        val finalized = finalizeSession(session)
        sendJson(exchange, 200, entryToJson(finalized))
    }

    private fun finalizeSession(session: UploadSession): FakeEntry = synchronized(session) {
        session.completedEntry?.let { return it }
        val bytes = session.buffer.toByteArray()
        val now = System.currentTimeMillis()
        val entry = if (session.existingFileId != null) {
            val existing = entries[session.existingFileId]!!
            existing.content = bytes
            existing.modifiedTimeMs = now
            existing
        } else {
            val created = FakeEntry(
                id = nextId("file"),
                name = session.createName ?: "uploaded",
                mimeType = session.createMimeType,
                parents = session.createParents.toMutableList(),
                content = bytes,
                createdTimeMs = now,
                modifiedTimeMs = now,
            )
            entries[created.id] = created
            created
        }
        session.completedEntry = entry
        entry
    }

    private data class ParsedQuery(
        val exactName: String?,
        val parentId: String?,
        val trashedEqualsFalse: Boolean,
        val allowedMimeTypes: Set<String>,
    )

    private fun parseDriveQuery(q: String): ParsedQuery {
        var exactName: String? = null
        var parentId: String? = null
        val mimes = LinkedHashSet<String>()
        val trashedFalse = q.contains("trashed = false")

        // Extract all single-quoted literals with their surrounding context
        var idx = 0
        while (idx < q.length) {
            val openQuote = q.indexOf('\'', idx)
            if (openQuote < 0) break
            val (literal, afterClose) = readEscapedSingleQuoted(q, openQuote)
            val before = q.substring(idx, openQuote).trimEnd()
            val after = q.substring(afterClose).trimStart()
            when {
                before.endsWith("name =") -> exactName = literal
                before.endsWith("mimeType =") -> mimes.add(literal)
                after.startsWith("in parents") -> parentId = literal
            }
            idx = afterClose
        }
        return ParsedQuery(
            exactName = exactName,
            parentId = parentId,
            trashedEqualsFalse = trashedFalse,
            allowedMimeTypes = mimes,
        )
    }

    private fun readEscapedSingleQuoted(s: String, openIdx: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = openIdx + 1
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                sb.append(s[i + 1])
                i += 2
                continue
            }
            if (ch == '\'') {
                return sb.toString() to (i + 1)
            }
            sb.append(ch)
            i++
        }
        return sb.toString() to s.length
    }

    private fun entryToJson(e: FakeEntry): JsonValue.Obj {
        val map = LinkedHashMap<String, JsonValue>().apply {
            put("id", JsonValue.Str(e.id))
            put("name", JsonValue.Str(e.name))
            put("mimeType", JsonValue.Str(e.mimeType))
            if (!e.isFolder && e.mimeType !in WORKSPACE_EXPORT && e.mimeType != SHORTCUT_MIME) {
                put("size", JsonValue.Str(e.content.size.toString()))
            }
            put("createdTime", JsonValue.Str(formatRfc3339(e.createdTimeMs)))
            put("modifiedTime", JsonValue.Str(formatRfc3339(e.modifiedTimeMs)))
            put("trashed", JsonValue.Bool(e.trashed))
            put("capabilities", MiniJson.obj("canEdit" to JsonValue.Bool(e.canEdit)))
            if (e.mimeType == SHORTCUT_MIME && e.shortcutTargetId != null) {
                put(
                    "shortcutDetails",
                    MiniJson.obj(
                        "targetId" to JsonValue.Str(e.shortcutTargetId),
                        "targetMimeType" to JsonValue.Str(e.shortcutTargetMimeType ?: "application/octet-stream"),
                    ),
                )
            }
        }
        return JsonValue.Obj(map)
    }

    private fun decodeSegment(raw: String): String = URLDecoder.decode(raw, "UTF-8")

    private fun decodeQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = if (eq >= 0) pair.substring(0, eq) else pair
            val v = if (eq >= 0) pair.substring(eq + 1) else ""
            out[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
        }
        return out
    }

    private fun sendDriveError(exchange: HttpExchange, status: Int, reason: String, message: String) {
        val obj = MiniJson.obj(
            "error" to MiniJson.obj(
                "code" to JsonValue.Num(status.toString()),
                "message" to JsonValue.Str(message),
                "errors" to JsonValue.Arr(
                    listOf(
                        MiniJson.obj(
                            "reason" to JsonValue.Str(reason),
                            "message" to JsonValue.Str(message),
                        ),
                    ),
                ),
            ),
        )
        sendJson(exchange, status, obj)
    }

    private fun sendJson(exchange: HttpExchange, status: Int, obj: JsonValue.Obj) {
        val bytes = MiniJson.stringify(obj).toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
        sendRawBytes(exchange, status, bytes)
    }

    private fun sendRawBytes(exchange: HttpExchange, status: Int, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            exchange.responseHeaders.set("Content-Length", "0")
            exchange.sendResponseHeaders(status, -1L)
        } else {
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    override fun close() {
        server.stop(0)
        pool.shutdownNow()
    }
}
