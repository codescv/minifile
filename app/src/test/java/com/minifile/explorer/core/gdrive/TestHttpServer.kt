package com.minifile.explorer.core.gdrive

import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal pure-`java.net.ServerSocket` HTTP/1.1 server for Android JVM unit tests
 * (where `com.sun.net.httpserver` is absent from the `android.jar` compile classpath).
 */
class HttpHeaders {
    private val map = LinkedHashMap<String, MutableList<String>>()

    fun add(name: String, value: String) {
        val existingKey = map.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
        map.getOrPut(existingKey) { ArrayList() }.add(value)
    }

    fun set(name: String, value: String) {
        val existingKey = map.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
        map[existingKey] = mutableListOf(value)
    }

    fun getFirst(name: String): String? {
        val key = map.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: return null
        return map[key]?.firstOrNull()
    }

    fun entries(): Set<Map.Entry<String, List<String>>> = map.entries
}

class HttpExchange internal constructor(
    private val socket: Socket,
    val requestMethod: String,
    val rawRequestTarget: String,
    val requestURI: URI,
    val requestHeaders: HttpHeaders,
    val requestBody: InputStream,
    private val rawOut: OutputStream,
) : Closeable {

    val responseHeaders = HttpHeaders()
    private var headersSent = false

    val responseBody: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            rawOut.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            rawOut.write(b, off, len)
        }

        override fun flush() {
            rawOut.flush()
        }

        override fun close() {
            runCatching { rawOut.flush() }
        }
    }

    fun sendResponseHeaders(status: Int, responseLength: Long) {
        if (headersSent) return
        headersSent = true
        if (responseHeaders.getFirst("Connection") == null) {
            responseHeaders.set("Connection", "close")
        }
        if (responseLength >= 0L) {
            responseHeaders.set("Content-Length", responseLength.toString())
        } else if (responseHeaders.getFirst("Content-Length") == null && status != 204 && status != 304) {
            responseHeaders.set("Content-Length", "0")
        }

        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n")
        for ((k, values) in responseHeaders.entries()) {
            for (v in values) {
                sb.append(k).append(": ").append(v).append("\r\n")
            }
        }
        sb.append("\r\n")
        rawOut.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        rawOut.flush()
    }

    override fun close() {
        runCatching { rawOut.flush() }
        runCatching { socket.close() }
    }

    companion object {
        private fun reasonPhrase(status: Int): String = when (status) {
            200 -> "OK"
            201 -> "Created"
            204 -> "No Content"
            206 -> "Partial Content"
            308 -> "Resume Incomplete"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            411 -> "Length Required"
            416 -> "Range Not Satisfiable"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            504 -> "Gateway Timeout"
            else -> "Status"
        }
    }
}

class HttpServer private constructor(bindAddr: InetSocketAddress, backlog: Int) {

    companion object {
        fun create(addr: InetSocketAddress, backlog: Int): HttpServer = HttpServer(addr, backlog)
    }

    private data class ContextEntry(
        val prefix: String,
        val handler: (HttpExchange) -> Unit,
    )

    private val serverSocket = ServerSocket(
        bindAddr.port,
        if (backlog > 0) backlog else 50,
        bindAddr.address ?: InetAddress.getLoopbackAddress(),
    )

    val address: InetSocketAddress
        get() = InetSocketAddress(serverSocket.inetAddress, serverSocket.localPort)

    var executor: ExecutorService? = null

    private val contexts = CopyOnWriteArrayList<ContextEntry>()
    private val running = AtomicBoolean(false)
    private var acceptThread: Thread? = null
    private var fallbackExecutor: ExecutorService? = null

    fun createContext(prefix: String, handler: (HttpExchange) -> Unit) {
        contexts.add(ContextEntry(prefix, handler))
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val workerPool = executor ?: Executors.newCachedThreadPool().also { fallbackExecutor = it }
        acceptThread = Thread({
            while (running.get() && !serverSocket.isClosed) {
                val client = try {
                    serverSocket.accept()
                } catch (_: SocketException) {
                    break
                } catch (_: Throwable) {
                    continue
                }
                try {
                    workerPool.execute { handleSocket(client) }
                } catch (_: Throwable) {
                    runCatching { client.close() }
                }
            }
        }, "test-http-accept-${serverSocket.localPort}").apply {
            isDaemon = true
            start()
        }
    }

    fun stop(delaySec: Int) {
        running.set(false)
        runCatching { serverSocket.close() }
        fallbackExecutor?.shutdownNow()
    }

    private fun handleSocket(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

            val requestLine = readAsciiLine(input) ?: run {
                socket.close()
                return
            }
            if (requestLine.isEmpty()) {
                socket.close()
                return
            }
            val firstSpace = requestLine.indexOf(' ')
            val secondSpace = if (firstSpace > 0) requestLine.lastIndexOf(' ') else -1
            if (firstSpace <= 0 || secondSpace <= firstSpace) {
                socket.close()
                return
            }
            val method = requestLine.substring(0, firstSpace).trim()
            val rawTarget = requestLine.substring(firstSpace + 1, secondSpace).trim()

            val reqHeaders = HttpHeaders()
            while (true) {
                val hdrLine = readAsciiLine(input) ?: break
                if (hdrLine.isEmpty()) break
                val colon = hdrLine.indexOf(':')
                if (colon > 0) {
                    val name = hdrLine.substring(0, colon).trim()
                    val value = hdrLine.substring(colon + 1).trim()
                    reqHeaders.add(name, value)
                }
            }

            if (reqHeaders.getFirst("Expect")?.equals("100-continue", ignoreCase = true) == true) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                out.flush()
            }

            val contentLength = reqHeaders.getFirst("Content-Length")?.trim()?.toIntOrNull()
            val bodyBytes = if (contentLength != null && contentLength > 0) {
                readExactBytes(input, contentLength)
            } else {
                ByteArray(0)
            }

            val parsedUri = parseRawRequestUri(rawTarget)
            val exchange = HttpExchange(
                socket = socket,
                requestMethod = method,
                rawRequestTarget = rawTarget,
                requestURI = parsedUri,
                requestHeaders = reqHeaders,
                requestBody = ByteArrayInputStream(bodyBytes),
                rawOut = out,
            )

            val reqPath = parsedUri.rawPath ?: "/"
            val matched = contexts
                .filter { reqPath.startsWith(it.prefix) }
                .maxByOrNull { it.prefix.length }

            if (matched == null) {
                exchange.sendResponseHeaders(404, -1L)
                exchange.close()
            } else {
                matched.handler(exchange)
            }
        } catch (_: Throwable) {
            runCatching { socket.close() }
        }
    }

    /**
     * Parses `rawTarget` into a [URI] without failing when tests intentionally probe unencoded characters;
     * if [URI] rejects illegal characters, encodes only what [URI] refuses? Wait: [FakeDriveServer] checks
     * `' ' in rawPath || '\'' in rawPath` on `requestURI.rawPath`! Notice single quote `'` IS legal in RFC 2396
     * [URI] (so `URI("/files?q='root'")` succeeds and keeps `'` in `rawQuery`), whereas raw space `' '` throws
     * `URISyntaxException`. To let [FakeDriveServer] detect raw spaces too, we only fallback if `URI(rawTarget)` throws!
     */
    private fun parseRawRequestUri(rawTarget: String): URI {
        return try {
            URI(rawTarget)
        } catch (_: Exception) {
            // Preserve a space marker if rawTarget had an unencoded space? Actually URI doesn't allow raw space,
            // so only fall back when malformed.
            URI(rawTarget.replace(" ", "%20"))
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder(128)
        var sawAny = false
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (sawAny) sb.toString() else null
            }
            sawAny = true
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') {
                    sb.setLength(sb.length - 1)
                }
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun readExactBytes(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var off = 0
        while (off < count) {
            val n = input.read(buf, off, count - off)
            if (n < 0) throw EOFException("Premature EOF reading HTTP request body ($off / $count)")
            off += n
        }
        return buf
    }
}
