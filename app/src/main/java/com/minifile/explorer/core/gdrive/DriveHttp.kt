package com.minifile.explorer.core.gdrive

import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.Logger
import com.minifile.explorer.core.model.MiniJson
import kotlinx.coroutines.delay
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ThreadLocalRandom

class DriveFileNotFoundException(message: String) : FileNotFoundException(message)

internal data class OpenedDownload(
    val status: Int,
    val contentLength: Long,
    val stream: InputStream,
    val disconnect: () -> Unit,
)

internal data class ResumablePutResult(
    val status: Int,
    val rangeHeader: String?,
    val completedJson: JsonValue.Obj?,
)

/**
 * Pure-JVM `HttpURLConnection` client for Google Drive REST API v3 (§3.1, B2, m2, m3, m7).
 *
 * Enforces:
 * - `setFixedLengthStreamingMode(len)` on EVERY request body (never default buffering, never chunked).
 * - `Accept-Encoding: identity` on downloads so Range/Content-Length are never distorted by gzip.
 * - `urlEncode(...)` on EVERY path segment and query parameter.
 * - Single 401 token-refresh retry via `tokenProvider.accessToken(staleToken = used)`.
 * - Exponential backoff (`baseBackoffMs * 2^attempt + jitter(0..250ms)`, max 5 attempts) using
 *   `delay()` in `suspend` calls and `Thread.sleep()` in blocking calls.
 */
class DriveHttp(
    val apiBase: String = DEFAULT_DRIVE_API_BASE,
    val uploadBase: String = DEFAULT_DRIVE_UPLOAD_BASE,
    private val logger: Logger = Logger.NOOP,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val uploadReadTimeoutMs: Int = 60_000,
    private val baseBackoffMs: Long = 500L,
    private val maxJitterMs: Long = 250L,
) {
    companion object {
        const val MAX_ATTEMPTS = 5
        private val RATE_LIMIT_REASONS = setOf("userRateLimitExceeded", "rateLimitExceeded")
        private val RETRYABLE_STATUSES = setOf(429, 500, 502, 503, 504)
    }

    fun buildApiUrl(
        pathSegments: List<String>,
        queryParams: List<Pair<String, String>> = emptyList(),
    ): String = buildUrl(apiBase, pathSegments, queryParams)

    fun buildUploadUrl(
        pathSegments: List<String>,
        queryParams: List<Pair<String, String>> = emptyList(),
    ): String = buildUrl(uploadBase, pathSegments, queryParams)

    private fun buildUrl(
        base: String,
        pathSegments: List<String>,
        queryParams: List<Pair<String, String>>,
    ): String {
        val sb = StringBuilder(base.trimEnd('/'))
        for (seg in pathSegments) {
            sb.append('/').append(urlEncode(seg))
        }
        if (queryParams.isNotEmpty()) {
            sb.append('?')
            queryParams.forEachIndexed { idx, (k, v) ->
                if (idx > 0) sb.append('&')
                sb.append(urlEncode(k)).append('=').append(urlEncode(v))
            }
        }
        return sb.toString()
    }

    suspend fun getJson(
        tokenProvider: DriveTokenProvider,
        pathSegments: List<String>,
        queryParams: List<Pair<String, String>> = emptyList(),
    ): JsonValue.Obj = executeJsonSuspend(
        tokenProvider = tokenProvider,
        method = "GET",
        url = buildApiUrl(pathSegments, queryParams),
        body = null,
    )

    fun getJsonBlocking(
        tokenProvider: DriveTokenProvider,
        pathSegments: List<String>,
        queryParams: List<Pair<String, String>> = emptyList(),
    ): JsonValue.Obj = executeJsonBlocking(
        tokenProvider = tokenProvider,
        method = "GET",
        url = buildApiUrl(pathSegments, queryParams),
        body = null,
    )

    suspend fun postJson(
        tokenProvider: DriveTokenProvider,
        pathSegments: List<String>,
        queryParams: List<Pair<String, String>> = emptyList(),
        body: JsonValue.Obj,
    ): JsonValue.Obj = executeJsonSuspend(
        tokenProvider = tokenProvider,
        method = "POST",
        url = buildApiUrl(pathSegments, queryParams),
        body = body,
    )

    suspend fun patchJson(
        tokenProvider: DriveTokenProvider,
        pathSegments: List<String>,
        queryParams: List<Pair<String, String>> = emptyList(),
        body: JsonValue.Obj? = null,
    ): JsonValue.Obj = executeJsonSuspend(
        tokenProvider = tokenProvider,
        method = "PATCH",
        url = buildApiUrl(pathSegments, queryParams),
        body = body ?: MiniJson.obj(),
    )

    suspend fun delete(
        tokenProvider: DriveTokenProvider,
        pathSegments: List<String>,
        queryParams: List<Pair<String, String>> = emptyList(),
    ) {
        executeRawSuspend(
            tokenProvider = tokenProvider,
            method = "DELETE",
            url = buildApiUrl(pathSegments, queryParams),
            bodyBytes = null,
            contentType = null,
        )
    }

    private suspend fun executeJsonSuspend(
        tokenProvider: DriveTokenProvider,
        method: String,
        url: String,
        body: JsonValue.Obj?,
    ): JsonValue.Obj {
        val bodyBytes = body?.let { MiniJson.stringify(it).toByteArray(Charsets.UTF_8) }
        val raw = executeRawSuspend(
            tokenProvider = tokenProvider,
            method = method,
            url = url,
            bodyBytes = bodyBytes,
            contentType = if (bodyBytes != null) "application/json; charset=UTF-8" else null,
        )
        if (raw.isBlank()) return MiniJson.obj()
        return (runCatching { MiniJson.parse(raw) }.getOrNull() as? JsonValue.Obj)
            ?: throw IOException("Malformed JSON from Google Drive")
    }

    private fun executeJsonBlocking(
        tokenProvider: DriveTokenProvider,
        method: String,
        url: String,
        body: JsonValue.Obj?,
    ): JsonValue.Obj {
        val bodyBytes = body?.let { MiniJson.stringify(it).toByteArray(Charsets.UTF_8) }
        val raw = executeRawBlocking(
            tokenProvider = tokenProvider,
            method = method,
            url = url,
            bodyBytes = bodyBytes,
            contentType = if (bodyBytes != null) "application/json; charset=UTF-8" else null,
        )
        if (raw.isBlank()) return MiniJson.obj()
        return (runCatching { MiniJson.parse(raw) }.getOrNull() as? JsonValue.Obj)
            ?: throw IOException("Malformed JSON from Google Drive")
    }

    private data class SingleAttemptOutcome(
        val status: Int,
        val rawBody: String,
        val headers: Map<String, List<String>>,
    )

    private fun performSingleJsonOrEmptyCall(
        method: String,
        url: String,
        bearerToken: String,
        bodyBytes: ByteArray?,
        contentType: String?,
        extraHeaders: List<Pair<String, String>> = emptyList(),
        readTimeoutOverrideMs: Int = readTimeoutMs,
    ): SingleAttemptOutcome {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutOverrideMs
            setMethod(this, method)
            setRequestProperty("Authorization", "Bearer $bearerToken")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "identity")
            for ((hk, hv) in extraHeaders) {
                setRequestProperty(hk, hv)
            }
            if (bodyBytes != null) {
                doOutput = true
                if (contentType != null) {
                    setRequestProperty("Content-Type", contentType)
                }
                setFixedLengthStreamingMode(bodyBytes.size)
            }
        }
        try {
            if (bodyBytes != null) {
                conn.outputStream.use { out ->
                    if (bodyBytes.isNotEmpty()) out.write(bodyBytes)
                }
            }
            val status = conn.responseCode
            val rawBody = readHttpBody(conn, status)
            return SingleAttemptOutcome(status, rawBody, conn.headerFields ?: emptyMap())
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun executeRawSuspend(
        tokenProvider: DriveTokenProvider,
        method: String,
        url: String,
        bodyBytes: ByteArray?,
        contentType: String?,
    ): String {
        var retried401 = false
        var staleToken: String? = null
        var attempt = 0

        while (true) {
            val token = tokenProvider.accessToken(staleToken)
            staleToken = null
            val outcome = try {
                performSingleJsonOrEmptyCall(method, url, token, bodyBytes, contentType)
            } catch (timeout: SocketTimeoutException) {
                if (attempt + 1 >= MAX_ATTEMPTS) throw timeout
                delay(computeBackoffMs(attempt))
                attempt++
                continue
            }

            if (outcome.status == 401) {
                if (!retried401) {
                    retried401 = true
                    staleToken = token
                    continue
                }
                throw GDriveAuthException(
                    message = "Google Drive sign-in expired — sign in again",
                    needsInteraction = true,
                )
            }

            if (outcome.status in 200..299) {
                return outcome.rawBody
            }

            val parsedErr = parseDriveError(outcome.status, outcome.rawBody)
            if (shouldRetry(outcome.status, parsedErr.reason) && attempt + 1 < MAX_ATTEMPTS) {
                delay(computeBackoffMs(attempt))
                attempt++
                continue
            }
            throw mapDriveException(outcome.status, parsedErr)
        }
    }

    private fun executeRawBlocking(
        tokenProvider: DriveTokenProvider,
        method: String,
        url: String,
        bodyBytes: ByteArray?,
        contentType: String?,
    ): String {
        var retried401 = false
        var staleToken: String? = null
        var attempt = 0

        while (true) {
            val token = tokenProvider.accessToken(staleToken)
            staleToken = null
            val outcome = try {
                performSingleJsonOrEmptyCall(method, url, token, bodyBytes, contentType)
            } catch (timeout: SocketTimeoutException) {
                if (attempt + 1 >= MAX_ATTEMPTS) throw timeout
                sleepBlocking(computeBackoffMs(attempt))
                attempt++
                continue
            }

            if (outcome.status == 401) {
                if (!retried401) {
                    retried401 = true
                    staleToken = token
                    continue
                }
                throw GDriveAuthException(
                    message = "Google Drive sign-in expired — sign in again",
                    needsInteraction = true,
                )
            }

            if (outcome.status in 200..299) {
                return outcome.rawBody
            }

            val parsedErr = parseDriveError(outcome.status, outcome.rawBody)
            if (shouldRetry(outcome.status, parsedErr.reason) && attempt + 1 < MAX_ATTEMPTS) {
                sleepBlocking(computeBackoffMs(attempt))
                attempt++
                continue
            }
            throw mapDriveException(outcome.status, parsedErr)
        }
    }

    internal data class ResumableSessionStart(
        val sessionUri: String,
        val initialMeta: DriveEntryMeta?,
    )

    internal fun startResumableUploadBlocking(
        tokenProvider: DriveTokenProvider,
        existingFileId: String?,
        metadataBody: JsonValue.Obj?,
        uploadContentType: String,
    ): ResumableSessionStart {
        val method = if (existingFileId == null) "POST" else "PATCH"
        val segments = if (existingFileId == null) listOf("files") else listOf("files", existingFileId)
        val url = buildUploadUrl(
            pathSegments = segments,
            queryParams = listOf(
                "uploadType" to "resumable",
                "fields" to FILE_FIELDS,
            ),
        )
        val payloadObj = metadataBody ?: MiniJson.obj()
        val bodyBytes = MiniJson.stringify(payloadObj).toByteArray(Charsets.UTF_8)
        val extraHeaders = listOf("X-Upload-Content-Type" to uploadContentType)

        var retried401 = false
        var staleToken: String? = null
        var attempt = 0

        while (true) {
            val token = tokenProvider.accessToken(staleToken)
            staleToken = null
            val outcome = try {
                performSingleJsonOrEmptyCall(
                    method = method,
                    url = url,
                    bearerToken = token,
                    bodyBytes = bodyBytes,
                    contentType = "application/json; charset=UTF-8",
                    extraHeaders = extraHeaders,
                    readTimeoutOverrideMs = uploadReadTimeoutMs,
                )
            } catch (timeout: SocketTimeoutException) {
                if (attempt + 1 >= MAX_ATTEMPTS) throw timeout
                sleepBlocking(computeBackoffMs(attempt))
                attempt++
                continue
            }

            if (outcome.status == 401) {
                if (!retried401) {
                    retried401 = true
                    staleToken = token
                    continue
                }
                throw GDriveAuthException(
                    message = "Google Drive sign-in expired — sign in again",
                    needsInteraction = true,
                )
            }

            if (outcome.status in 200..299) {
                val loc = findHeaderIgnoreCase(outcome.headers, "Location")
                    ?: throw IOException("Google Drive resumable upload missing Location header")
                val meta = if (outcome.rawBody.isNotBlank()) {
                    runCatching {
                        (MiniJson.parse(outcome.rawBody) as? JsonValue.Obj)?.let(::parseDriveEntryMeta)
                    }.getOrNull()
                } else {
                    null
                }
                return ResumableSessionStart(sessionUri = loc, initialMeta = meta)
            }

            val parsedErr = parseDriveError(outcome.status, outcome.rawBody)
            if (shouldRetry(outcome.status, parsedErr.reason) && attempt + 1 < MAX_ATTEMPTS) {
                sleepBlocking(computeBackoffMs(attempt))
                attempt++
                continue
            }
            throw mapDriveException(outcome.status, parsedErr)
        }
    }

    internal fun putResumableChunkSingleAttemptBlocking(
        sessionUri: String,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        contentRange: String,
        contentType: String = "application/octet-stream",
    ): ResumablePutResult {
        val conn = (URL(sessionUri).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "PUT"
            connectTimeout = connectTimeoutMs
            readTimeout = uploadReadTimeoutMs
            doOutput = true
            setRequestProperty("Content-Range", contentRange)
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "identity")
            setFixedLengthStreamingMode(length)
        }
        try {
            conn.outputStream.use { out ->
                if (length > 0) {
                    out.write(buffer, offset, length)
                }
            }
            val status = conn.responseCode
            val rangeHeader = conn.getHeaderField("Range")
                ?: findHeaderIgnoreCase(conn.headerFields ?: emptyMap(), "Range")
            if (status == 308) {
                // Drain any small body cleanly
                runCatching { readHttpBody(conn, status) }
                return ResumablePutResult(status = 308, rangeHeader = rangeHeader, completedJson = null)
            }
            val rawBody = readHttpBody(conn, status)
            if (status == 200 || status == 201) {
                val obj = if (rawBody.isNotBlank()) {
                    runCatching { MiniJson.parse(rawBody) as? JsonValue.Obj }.getOrNull()
                } else {
                    null
                }
                return ResumablePutResult(status = status, rangeHeader = rangeHeader, completedJson = obj)
            }
            val parsedErr = parseDriveError(status, rawBody)
            if (shouldRetry(status, parsedErr.reason)) {
                throw RetryableUploadChunkException(status, parsedErr.reason, parsedErr.message)
            }
            throw mapDriveException(status, parsedErr)
        } finally {
            conn.disconnect()
        }
    }

    internal class RetryableUploadChunkException(
        val status: Int,
        val reason: String?,
        message: String,
    ) : IOException("Transient upload error ($status ${reason ?: ""}): $message")

    internal fun openDownloadBlocking(
        tokenProvider: DriveTokenProvider,
        fileId: String,
        isExport: Boolean,
        exportMimeType: String?,
        rangeOffset: Long,
        requireRangeHeader: Boolean = false,
    ): OpenedDownload {
        val url = if (isExport) {
            buildApiUrl(
                pathSegments = listOf("files", fileId, "export"),
                queryParams = listOf("mimeType" to (exportMimeType ?: PDF_MIME)),
            )
        } else {
            buildApiUrl(
                pathSegments = listOf("files", fileId),
                queryParams = listOf("alt" to "media"),
            )
        }

        var retried401 = false
        var staleToken: String? = null
        var attempt = 0

        while (true) {
            val token = tokenProvider.accessToken(staleToken)
            staleToken = null

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept-Encoding", "identity")
                if (!isExport && (rangeOffset > 0L || requireRangeHeader)) {
                    setRequestProperty("Range", "bytes=$rangeOffset-")
                }
            }

            var keepOpen = false
            try {
                val status = try {
                    conn.responseCode
                } catch (timeout: SocketTimeoutException) {
                    if (attempt + 1 >= MAX_ATTEMPTS) throw timeout
                    sleepBlocking(computeBackoffMs(attempt))
                    attempt++
                    continue
                }

                if (status == 401) {
                    runCatching { readHttpBody(conn, status) }
                    if (!retried401) {
                        retried401 = true
                        staleToken = token
                        continue
                    }
                    throw GDriveAuthException(
                        message = "Google Drive sign-in expired — sign in again",
                        needsInteraction = true,
                    )
                }

                if (status == 200 || status == 206) {
                    val contentLength = conn.contentLengthLong
                    val stream = conn.inputStream
                    keepOpen = true
                    return OpenedDownload(
                        status = status,
                        contentLength = contentLength,
                        stream = stream,
                        disconnect = { conn.disconnect() },
                    )
                }

                val rawErr = readHttpBody(conn, status)
                val parsedErr = parseDriveError(status, rawErr)
                if (shouldRetry(status, parsedErr.reason) && attempt + 1 < MAX_ATTEMPTS) {
                    sleepBlocking(computeBackoffMs(attempt))
                    attempt++
                    continue
                }
                throw mapDriveException(status, parsedErr)
            } finally {
                if (!keepOpen) {
                    conn.disconnect()
                }
            }
        }
    }

    internal fun computeBackoffMs(attempt: Int): Long {
        val exp = baseBackoffMs * (1L shl attempt.coerceIn(0, 6))
        val jitter = if (maxJitterMs > 0L) ThreadLocalRandom.current().nextLong(0L, maxJitterMs + 1L) else 0L
        return exp + jitter
    }

    internal fun sleepBlocking(ms: Long) {
        if (ms <= 0L) return
        try {
            Thread.sleep(ms)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted during Google Drive retry backoff").apply {
                initCause(ie)
            }
        }
    }

    private fun shouldRetry(status: Int, reason: String?): Boolean {
        if (status in RETRYABLE_STATUSES) return true
        if (status == 403 && reason != null && reason in RATE_LIMIT_REASONS) return true
        return false
    }

    private data class ParsedDriveError(
        val reason: String?,
        val message: String,
    )

    private fun parseDriveError(status: Int, rawBody: String): ParsedDriveError {
        if (rawBody.isBlank()) return ParsedDriveError(null, "HTTP $status")
        val root = runCatching { MiniJson.parse(rawBody) as? JsonValue.Obj }.getOrNull()
            ?: return ParsedDriveError(null, rawBody.take(200))
        val errObj = root.obj("error")
        if (errObj == null) {
            val simpleMsg = root.str("error_description") ?: root.str("error") ?: "HTTP $status"
            return ParsedDriveError(null, simpleMsg)
        }
        val msg = errObj.str("message") ?: "HTTP $status"
        val firstErr = errObj.arr("errors")?.items?.firstOrNull() as? JsonValue.Obj
        val reason = firstErr?.str("reason") ?: errObj.str("status")
        return ParsedDriveError(reason = reason, message = msg)
    }

    private fun mapDriveException(status: Int, err: ParsedDriveError): IOException {
        if (status == 404) {
            return DriveFileNotFoundException("Google Drive item not found: ${err.message}")
        }
        if (status == 403) {
            when (err.reason) {
                "exportSizeLimitExceeded" -> {
                    return DriveApiException(
                        status = 403,
                        reason = err.reason,
                        message = "Google Doc is too large to export (10 MB limit)",
                    )
                }
                "storageQuotaExceeded" -> {
                    return DriveApiException(
                        status = 403,
                        reason = err.reason,
                        message = "Google Drive storage is full",
                    )
                }
            }
        }
        logger.log(
            "DriveHttp",
            "Drive HTTP $status (${err.reason ?: "unknown"}): ${redactSecrets(err.message)}",
            null,
        )
        val reasonPrefix = err.reason?.takeIf { it.isNotBlank() }?.let { "$it " } ?: ""
        return DriveApiException(
            status = status,
            reason = err.reason,
            message = "Drive: $reasonPrefix${redactSecrets(err.message)}".trim(),
        )
    }

    private fun findHeaderIgnoreCase(headers: Map<String, List<String>>, name: String): String? {
        for ((k, v) in headers) {
            if (k.equals(name, ignoreCase = true)) {
                return v.firstOrNull()
            }
        }
        return null
    }

    private fun setMethod(conn: HttpURLConnection, method: String) {
        if (method == "PATCH") {
            try {
                conn.requestMethod = "PATCH"
            } catch (_: ProtocolException) {
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            }
        } else {
            conn.requestMethod = method
        }
    }
}
