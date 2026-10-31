package com.minifile.explorer.core.peer

import com.minifile.explorer.core.ftp.FtpAccessDeniedException
import com.minifile.explorer.core.ftp.FtpServerEngine
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.util.Locale

data class PeerHttpResponse(
    val status: Int,
    val reason: String,
    val headers: Map<String, String>,
    val bodyBytes: ByteArray,
) {
    val bodyText: String
        get() = String(bodyBytes, Charsets.UTF_8)
}

/**
 * Thrown when an [IOException] occurs before the server accepts a streaming request with `100 Continue`
 * (e.g. connect failure, stale keep-alive reset, or handshake drop before any body bytes were sent).
 */
class PreContinueIOException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Minimal pure-JVM HTTP/1.1 client over raw [Socket]s (`zero android.* imports`).
 *
 * Features:
 * - LAN-only target validation via [FtpServerEngine.isAllowedClientAddress]
 * - Unbound [Socket] passed to [socketBinder] before `connect()` (MUST-1 / MUST-2)
 * - Keep-alive connection pooling for sequential file transfers
 * - `tcpNoDelay = true`, configurable `connectTimeoutMs` and `soTimeoutMs` (default 30 s)
 * - Mandatory `Expect: 100-continue` support on [postStream] so `403`/`409`/`410`/`411`/`507`
 *   responses are received before streaming large bodies
 * - Never follows `3xx` redirects (MUST-3)
 * - Immediate cancellation by closing the active socket ([cancelActive] / [close])
 */
class PeerHttpClient(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val soTimeoutMs: Int = DEFAULT_SO_TIMEOUT_MS,
    private val socketBinder: (Socket) -> Unit = {},
) : Closeable {

    private class PooledConn(
        val hostKey: String,
        val port: Int,
        val socket: Socket,
        val input: BufferedInputStream,
        val output: BufferedOutputStream,
    ) {
        fun isUsable(): Boolean =
            !socket.isClosed && socket.isConnected && !socket.isInputShutdown && !socket.isOutputShutdown

        fun closeQuietly() {
            runCatching { socket.close() }
        }
    }

    private val lock = Any()
    private var pooled: PooledConn? = null
    private var active: PooledConn? = null

    @Volatile
    private var closed: Boolean = false

    fun get(
        url: String,
        timeoutMs: Int = soTimeoutMs,
        connectTimeoutOverrideMs: Int = connectTimeoutMs,
    ): PeerHttpResponse = executeSimple(
        method = "GET",
        url = url,
        timeoutMs = timeoutMs,
        connectTimeoutOverrideMs = connectTimeoutOverrideMs,
    )

    fun postEmpty(
        url: String,
        timeoutMs: Int = soTimeoutMs,
        connectTimeoutOverrideMs: Int = connectTimeoutMs,
    ): PeerHttpResponse = executeSimple(
        method = "POST",
        url = url,
        timeoutMs = timeoutMs,
        connectTimeoutOverrideMs = connectTimeoutOverrideMs,
    )

    private fun executeSimple(
        method: String,
        url: String,
        timeoutMs: Int,
        connectTimeoutOverrideMs: Int,
    ): PeerHttpResponse {
        val target = parseTarget(url)
        var attempt = 0
        while (true) {
            attempt++
            val (conn, wasReused) = checkoutConnection(
                target = target,
                connectTimeoutMs = connectTimeoutOverrideMs,
                soTimeoutMs = timeoutMs,
            )
            var keepAlive = false
            try {
                val reqHeaders = buildString(256) {
                    append(method).append(' ').append(target.pathAndQuery).append(" HTTP/1.1\r\n")
                    append("Host: ").append(target.hostHeader).append("\r\n")
                    append("User-Agent: Files-Peer/1.0\r\n")
                    append("Accept: application/json\r\n")
                    if (method == "POST") {
                        append("Content-Length: 0\r\n")
                    }
                    append("Connection: keep-alive\r\n\r\n")
                }
                conn.output.write(reqHeaders.toByteArray(Charsets.ISO_8859_1))
                conn.output.flush()

                val response = readFinalResponse(conn)
                keepAlive = !shouldCloseConnection(response.headers)
                return response
            } catch (e: IOException) {
                conn.closeQuietly()
                if (wasReused && attempt == 1 && !closed) {
                    // Transparently reconnect once if an idle keep-alive socket was closed by peer
                    continue
                }
                throw e
            } finally {
                releaseConnection(conn, keepAlive = keepAlive)
            }
        }
    }

    /**
     * Streams a fixed-length `POST` request body.
     *
     * When [expectContinue] is `true` (default), sends `Expect: 100-continue` and waits for
     * `HTTP/1.1 100 Continue` before invoking [writeBody].
     * - If the server rejects or short-circuits with a final status (e.g. `403`, `409`, `410`, `411`, `507`),
     *   [writeBody] is never called and that [PeerHttpResponse] is returned directly.
     * - If an [IOException] happens before `100 Continue` is received, throws [PreContinueIOException].
     * - Any [IOException] after `100 Continue` is thrown as a plain [IOException] (unless an early
     *   server HTTP response is already waiting in the socket buffer, in which case it is returned).
     */
    fun postStream(
        url: String,
        contentLength: Long,
        contentType: String = "application/octet-stream",
        expectContinue: Boolean = true,
        writeBody: (OutputStream) -> Unit,
    ): PeerHttpResponse {
        require(contentLength >= 0L) { "contentLength must be non-negative: $contentLength" }
        val target = parseTarget(url)

        val conn = try {
            checkoutConnection(
                target = target,
                connectTimeoutMs = connectTimeoutMs,
                soTimeoutMs = soTimeoutMs,
            ).first
        } catch (e: FtpAccessDeniedException) {
            throw e
        } catch (e: IOException) {
            throw PreContinueIOException(e.message ?: "Failed to connect to peer", e)
        }

        var keepAlive = false
        var receivedContinue = !expectContinue
        try {
            try {
                val reqHeaders = buildString(320) {
                    append("POST ").append(target.pathAndQuery).append(" HTTP/1.1\r\n")
                    append("Host: ").append(target.hostHeader).append("\r\n")
                    append("User-Agent: Files-Peer/1.0\r\n")
                    append("Content-Type: ").append(contentType).append("\r\n")
                    append("Content-Length: ").append(contentLength).append("\r\n")
                    if (expectContinue) {
                        append("Expect: 100-continue\r\n")
                    }
                    append("Connection: keep-alive\r\n\r\n")
                }
                conn.output.write(reqHeaders.toByteArray(Charsets.ISO_8859_1))
                conn.output.flush()

                if (expectContinue) {
                    while (true) {
                        val (status, reason) = readStatusLine(conn.input)
                        val interimHeaders = readHeaders(conn.input)
                        if (status == 100) {
                            receivedContinue = true
                            break
                        }
                        if (status in 101..199) {
                            continue
                        }
                        // Server replied with a final status (e.g. 403/409/410/411/507) before body!
                        // Since we did not send body bytes, close connection if non-zero body was expected
                        // so stream framing cannot desynchronize.
                        val bodyBytes = readResponseBody(conn.input, status, interimHeaders)
                        val canKeep = contentLength == 0L && !shouldCloseConnection(interimHeaders)
                        keepAlive = canKeep
                        if (!canKeep) {
                            conn.closeQuietly()
                        }
                        return PeerHttpResponse(
                            status = status,
                            reason = reason,
                            headers = interimHeaders,
                            bodyBytes = bodyBytes,
                        )
                    }
                }
            } catch (e: IOException) {
                conn.closeQuietly()
                if (closed) throw e
                throw PreContinueIOException(e.message ?: "Connection failed before 100 Continue", e)
            }

            // 100 Continue received (or not requested): stream body bytes now.
            try {
                writeBody(conn.output)
                conn.output.flush()
            } catch (writeErr: IOException) {
                // Check if the server wrote an early HTTP error response before closing the socket.
                val earlyResponse = tryReadEarlyResponse(conn)
                conn.closeQuietly()
                if (earlyResponse != null) {
                    return earlyResponse
                }
                throw writeErr
            }

            val finalResp = readFinalResponse(conn)
            keepAlive = !shouldCloseConnection(finalResp.headers)
            return finalResp
        } catch (e: IOException) {
            conn.closeQuietly()
            throw e
        } finally {
            releaseConnection(conn, keepAlive = keepAlive && receivedContinue)
        }
    }

    /**
     * Immediately closes any active or pooled socket to interrupt a blocking read/write,
     * while allowing subsequent requests (such as `POST /api/peer/end?ok=0`) to open a new socket.
     */
    fun cancelActive() {
        val toClose = ArrayList<PooledConn>(2)
        synchronized(lock) {
            active?.let { toClose.add(it) }
            pooled?.let { toClose.add(it) }
            pooled = null
        }
        for (c in toClose) {
            c.closeQuietly()
        }
    }

    override fun close() {
        closed = true
        cancelActive()
    }

    private fun tryReadEarlyResponse(conn: PooledConn): PeerHttpResponse? {
        return try {
            conn.socket.soTimeout = 250
            val (status, reason) = readStatusLine(conn.input)
            val headers = readHeaders(conn.input)
            val body = readResponseBody(conn.input, status, headers)
            PeerHttpResponse(status = status, reason = reason, headers = headers, bodyBytes = body)
        } catch (_: Throwable) {
            null
        }
    }

    private fun readFinalResponse(conn: PooledConn): PeerHttpResponse {
        while (true) {
            val (status, reason) = readStatusLine(conn.input)
            val headers = readHeaders(conn.input)
            if (status in 100..199) {
                continue
            }
            val body = readResponseBody(conn.input, status, headers)
            return PeerHttpResponse(
                status = status,
                reason = reason,
                headers = headers,
                bodyBytes = body,
            )
        }
    }

    private data class ParsedTarget(
        val rawHost: String,
        val hostKey: String,
        val port: Int,
        val hostHeader: String,
        val pathAndQuery: String,
    )

    private fun parseTarget(url: String): ParsedTarget {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw IOException("Malformed peer URL: $url", e)
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http") {
            throw IOException("Unsupported scheme in peer URL (expected http): $url")
        }
        val host = uri.host ?: throw IOException("Missing host in peer URL: $url")
        val cleanHost = if (host.startsWith("[") && host.endsWith("]")) {
            host.substring(1, host.length - 1)
        } else {
            host
        }
        val port = if (uri.port != -1) uri.port else 80
        if (port !in 1..65535) {
            throw IOException("Invalid port in peer URL: $port")
        }
        val hostHeaderPart = if (cleanHost.contains(':')) "[$cleanHost]" else cleanHost
        val hostHeader = "$hostHeaderPart:$port"
        val rawPath = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val pathAndQuery = if (uri.rawQuery != null) "$rawPath?${uri.rawQuery}" else rawPath
        return ParsedTarget(
            rawHost = cleanHost,
            hostKey = cleanHost.lowercase(Locale.ROOT),
            port = port,
            hostHeader = hostHeader,
            pathAndQuery = pathAndQuery,
        )
    }

    private fun checkoutConnection(
        target: ParsedTarget,
        connectTimeoutMs: Int,
        soTimeoutMs: Int,
    ): Pair<PooledConn, Boolean> {
        if (closed) {
            throw SocketException("PeerHttpClient is closed")
        }

        synchronized(lock) {
            val existing = pooled
            if (existing != null) {
                pooled = null
                if (existing.hostKey == target.hostKey && existing.port == target.port && existing.isUsable()) {
                    runCatching { existing.socket.soTimeout = soTimeoutMs }
                    active = existing
                    return existing to true
                } else {
                    existing.closeQuietly()
                }
            }
        }

        val addr = InetAddress.getByName(target.rawHost)
        if (!FtpServerEngine.isAllowedClientAddress(addr)) {
            throw FtpAccessDeniedException("Peer address is outside private LAN/loopback: ${target.rawHost}")
        }

        val sock = createAndConnectSocket(addr, target.port, connectTimeoutMs, soTimeoutMs)
        val conn = PooledConn(
            hostKey = target.hostKey,
            port = target.port,
            socket = sock,
            input = BufferedInputStream(sock.getInputStream(), IO_BUFFER_BYTES),
            output = BufferedOutputStream(sock.getOutputStream(), IO_BUFFER_BYTES),
        )
        synchronized(lock) {
            if (closed) {
                conn.closeQuietly()
                throw SocketException("PeerHttpClient is closed")
            }
            active = conn
        }
        return conn to false
    }

    private fun createAndConnectSocket(
        addr: InetAddress,
        port: Int,
        connectTimeoutMs: Int,
        soTimeoutMs: Int,
    ): Socket {
        val sock = Socket()
        try {
            // MUST-1 / MUST-2: invoke socketBinder on unbound Socket() before connect()
            socketBinder(sock)
            sock.tcpNoDelay = true
            sock.soTimeout = soTimeoutMs
            sock.connect(InetSocketAddress(addr, port), connectTimeoutMs)
            return sock
        } catch (e: IOException) {
            runCatching { sock.close() }
            // If loopback failed solely because a Wi-Fi Network binder bound SO_BINDTODEVICE to wlan0,
            // allow loopback-only fallback without binder; never fall back for non-loopback addresses.
            if (addr.isLoopbackAddress && e is SocketException) {
                val loopSock = Socket()
                try {
                    loopSock.tcpNoDelay = true
                    loopSock.soTimeout = soTimeoutMs
                    loopSock.connect(InetSocketAddress(addr, port), connectTimeoutMs)
                    return loopSock
                } catch (loopErr: IOException) {
                    runCatching { loopSock.close() }
                    throw loopErr
                }
            }
            throw e
        } catch (t: Throwable) {
            runCatching { sock.close() }
            throw t
        }
    }

    private fun releaseConnection(conn: PooledConn, keepAlive: Boolean) {
        var discard: PooledConn? = null
        synchronized(lock) {
            if (active === conn) {
                active = null
            }
            if (!closed && keepAlive && conn.isUsable()) {
                discard = pooled
                pooled = conn
            } else {
                discard = conn
            }
        }
        discard?.closeQuietly()
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_SO_TIMEOUT_MS = 30_000
        private const val IO_BUFFER_BYTES = 64 * 1024
        private const val MAX_HEADER_LINE_BYTES = 8 * 1024
        private const val MAX_HEADERS_COUNT = 96
        private const val MAX_RESPONSE_BODY_BYTES = 4 * 1024 * 1024

        private fun shouldCloseConnection(headers: Map<String, String>): Boolean {
            val conn = headers["connection"]?.lowercase(Locale.ROOT) ?: return false
            return conn.contains("close")
        }

        private fun readStatusLine(input: InputStream): Pair<Int, String> {
            while (true) {
                val line = readCrlfLine(input)
                    ?: throw EOFException("Unexpected EOF while reading HTTP status line")
                if (line.isEmpty()) continue
                if (!line.startsWith("HTTP/", ignoreCase = true)) {
                    throw IOException("Malformed HTTP status line: $line")
                }
                val firstSpace = line.indexOf(' ')
                if (firstSpace < 0) {
                    throw IOException("Malformed HTTP status line: $line")
                }
                val rest = line.substring(firstSpace + 1).trimStart()
                val secondSpace = rest.indexOf(' ')
                val codeStr = if (secondSpace >= 0) rest.substring(0, secondSpace) else rest
                val reason = if (secondSpace >= 0) rest.substring(secondSpace + 1).trim() else ""
                val code = codeStr.toIntOrNull()
                    ?: throw IOException("Invalid HTTP status code in line: $line")
                return code to reason
            }
        }

        private fun readHeaders(input: InputStream): Map<String, String> {
            val headers = LinkedHashMap<String, String>()
            var count = 0
            while (true) {
                val line = readCrlfLine(input)
                    ?: throw EOFException("Unexpected EOF while reading HTTP headers")
                if (line.isEmpty()) break
                count++
                if (count > MAX_HEADERS_COUNT) {
                    throw IOException("Too many HTTP response headers")
                }
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase(Locale.ROOT)
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                }
            }
            return headers
        }

        private fun readResponseBody(
            input: InputStream,
            status: Int,
            headers: Map<String, String>,
        ): ByteArray {
            if (status in 100..199 || status == 204 || status == 304) {
                return ByteArray(0)
            }
            val te = headers["transfer-encoding"]?.lowercase(Locale.ROOT).orEmpty()
            if (te.contains("chunked")) {
                return readChunkedBody(input)
            }
            val clHeader = headers["content-length"]
            if (clHeader != null) {
                val len = clHeader.trim().toLongOrNull()
                    ?: throw IOException("Invalid Content-Length: $clHeader")
                if (len < 0L || len > MAX_RESPONSE_BODY_BYTES) {
                    throw IOException("Response Content-Length out of bounds: $len")
                }
                return readExactBytes(input, len.toInt())
            }
            if (shouldCloseConnection(headers)) {
                return readBoundedToEof(input)
            }
            return ByteArray(0)
        }

        private fun readExactBytes(input: InputStream, length: Int): ByteArray {
            if (length == 0) return ByteArray(0)
            val out = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val n = input.read(out, offset, length - offset)
                if (n < 0) {
                    throw EOFException("Unexpected EOF reading HTTP response body ($offset/$length bytes)")
                }
                offset += n
            }
            return out
        }

        private fun readBoundedToEof(input: InputStream): ByteArray {
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) {
                    if (baos.size() + n > MAX_RESPONSE_BODY_BYTES) {
                        throw IOException("Response body exceeds maximum allowed size")
                    }
                    baos.write(buf, 0, n)
                }
            }
            return baos.toByteArray()
        }

        private fun readChunkedBody(input: InputStream): ByteArray {
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val sizeLine = readCrlfLine(input)
                    ?: throw EOFException("Unexpected EOF reading chunk size")
                val hexPart = sizeLine.substringBefore(';').trim()
                val chunkSize = hexPart.toIntOrNull(16)
                    ?: throw IOException("Invalid chunk size: $sizeLine")
                if (chunkSize < 0 || baos.size() + chunkSize > MAX_RESPONSE_BODY_BYTES) {
                    throw IOException("Chunked response exceeds maximum allowed size")
                }
                if (chunkSize == 0) {
                    // Drain optional trailer headers until blank line
                    while (true) {
                        val trailer = readCrlfLine(input) ?: break
                        if (trailer.isEmpty()) break
                    }
                    break
                }
                var rem = chunkSize
                while (rem > 0) {
                    val n = input.read(buf, 0, minOf(rem, buf.size))
                    if (n < 0) throw EOFException("Unexpected EOF in chunked body")
                    if (n > 0) {
                        baos.write(buf, 0, n)
                        rem -= n
                    }
                }
                val crlf = readCrlfLine(input)
                    ?: throw EOFException("Unexpected EOF after chunk payload")
                if (crlf.isNotEmpty()) {
                    throw IOException("Expected CRLF after chunk payload")
                }
            }
            return baos.toByteArray()
        }

        private fun readCrlfLine(input: InputStream): String? {
            val baos = ByteArrayOutputStream(128)
            var sawByte = false
            while (true) {
                val b = input.read()
                if (b < 0) {
                    return if (sawByte) {
                        val raw = baos.toByteArray()
                        val len = if (raw.isNotEmpty() && raw.last() == '\r'.code.toByte()) raw.size - 1 else raw.size
                        String(raw, 0, len, Charsets.ISO_8859_1)
                    } else {
                        null
                    }
                }
                sawByte = true
                if (b == '\n'.code) break
                baos.write(b)
                if (baos.size() > MAX_HEADER_LINE_BYTES) {
                    throw IOException("HTTP header line exceeds maximum length")
                }
            }
            val raw = baos.toByteArray()
            val len = if (raw.isNotEmpty() && raw.last() == '\r'.code.toByte()) raw.size - 1 else raw.size
            return String(raw, 0, len, Charsets.ISO_8859_1)
        }
    }
}
