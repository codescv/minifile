package com.minifile.explorer.core.gdrive

import com.minifile.explorer.core.model.Clock
import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.MiniJson
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val scope: String,
)

class CancellationIOException(
    message: String = "Google sign-in was cancelled",
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Pure-JVM OAuth 2.0 loopback receiver with PKCE (`S256`) for Google Desktop OAuth clients (§2.4, B3).
 *
 * - Binds `127.0.0.1:0` with backlog 8.
 * - Accepts multiple connections up to the overall deadline, tolerating browser preconnect
 *   idle sockets (`soTimeout = 5_000`), `/favicon.ico` (`404`), and mismatched `state` (`400`).
 * - Responds with fixed HTML/text bodies only — zero query string parameters are ever echoed.
 */
class GoogleOAuthLoopback(
    private val clientId: String,
    private val clientSecret: String = "",
    private val openBrowser: (String) -> Unit,
    private val tokenEndpoint: String = DEFAULT_OAUTH_TOKEN_ENDPOINT,
    private val authEndpoint: String = DEFAULT_OAUTH_AUTH_ENDPOINT,
    private val random: SecureRandom = SecureRandom(),
    private val clock: Clock = Clock.SYSTEM,
) : Closeable {

    companion object {
        private const val CALLBACK_PATH = "/oauth2callback"
        private const val MAX_REQUEST_BYTES = 8192
        private const val SOCKET_READ_TIMEOUT_MS = 5_000

        private const val SUCCESS_HTML =
            "<!doctype html><html><head><meta charset=\"utf-8\"><title>Signed in</title></head>" +
                "<body style=\"font-family:sans-serif;padding:2rem;text-align:center;\">" +
                "<h2>Signed in.</h2><p>You can return to the Files app.</p></body></html>"

        private const val DENIED_HTML =
            "<!doctype html><html><head><meta charset=\"utf-8\"><title>Sign-in denied</title></head>" +
                "<body style=\"font-family:sans-serif;padding:2rem;text-align:center;\">" +
                "<h2>Google sign-in was denied.</h2><p>You can return to the Files app.</p></body></html>"

        internal fun generateCodeVerifier(random: SecureRandom): String {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        internal fun computeCodeChallengeS256(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        internal fun generateState(random: SecureRandom): String {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }

    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    var authUrl: String? = null
        private set

    fun start(timeoutMs: Long = 5 * 60_000L): OAuthTokens {
        require(clientId.isNotBlank()) { "OAuth Client ID is required" }
        if (cancelled.get()) throw CancellationIOException()

        val verifier = generateCodeVerifier(random)
        val challenge = computeCodeChallengeS256(verifier)
        val expectedState = generateState(random)

        val srv = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        serverSocket = srv
        if (cancelled.get()) {
            runCatching { srv.close() }
            throw CancellationIOException()
        }

        val authCode: String
        val redirectUri: String
        try {
            val port = srv.localPort
            redirectUri = "http://127.0.0.1:$port$CALLBACK_PATH"
            val builtAuthUrl = buildAuthUrl(redirectUri, challenge, expectedState)
            authUrl = builtAuthUrl
            openBrowser(builtAuthUrl)

            val deadlineNano = System.nanoTime() + timeoutMs.coerceAtLeast(1L) * 1_000_000L
            authCode = acceptCallbackLoop(srv, expectedState, deadlineNano)
        } finally {
            runCatching { srv.close() }
        }

        if (cancelled.get()) throw CancellationIOException()
        return exchangeAuthorizationCode(
            code = authCode,
            redirectUri = redirectUri,
            codeVerifier = verifier,
        )
    }

    fun cancel() {
        cancelled.set(true)
        runCatching { serverSocket?.close() }
    }

    override fun close() {
        cancel()
    }

    private fun buildAuthUrl(redirectUri: String, challenge: String, state: String): String {
        val base = authEndpoint.trimEnd('?')
        val sep = if ('?' in base) '&' else '?'
        return buildString {
            append(base).append(sep)
            append("client_id=").append(urlEncode(clientId.trim()))
            append("&redirect_uri=").append(urlEncode(redirectUri))
            append("&response_type=code")
            append("&scope=").append(urlEncode(DRIVE_SCOPE))
            append("&code_challenge=").append(urlEncode(challenge))
            append("&code_challenge_method=S256")
            append("&state=").append(urlEncode(state))
            append("&access_type=offline")
            append("&prompt=consent")
        }
    }

    private fun acceptCallbackLoop(
        srv: ServerSocket,
        expectedState: String,
        deadlineNano: Long,
    ): String {
        while (true) {
            if (cancelled.get()) throw CancellationIOException()
            val remainingMs = (deadlineNano - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0L) {
                throw SocketTimeoutException("Timed out waiting for Google sign-in in browser")
            }
            srv.soTimeout = remainingMs.coerceIn(1L, SOCKET_READ_TIMEOUT_MS.toLong()).toInt()

            val socket: Socket = try {
                srv.accept()
            } catch (_: SocketTimeoutException) {
                if ((deadlineNano - System.nanoTime()) <= 0L) {
                    throw SocketTimeoutException("Timed out waiting for Google sign-in in browser")
                }
                continue
            } catch (e: SocketException) {
                if (cancelled.get() || srv.isClosed) {
                    throw CancellationIOException(cause = e)
                }
                throw e
            }

            try {
                socket.use { client ->
                    client.soTimeout = SOCKET_READ_TIMEOUT_MS
                    val requestLine = readRequestLineAndDrainHeaders(client.getInputStream())
                        ?: return@use // Idle preconnect or closed socket; ignore and keep listening.
                    val parts = requestLine.split(' ')
                    val method = parts.getOrNull(0)?.uppercase() ?: ""
                    val rawTarget = parts.getOrNull(1) ?: ""
                    if (method != "GET" || rawTarget.isEmpty()) {
                        writeFixedResponse(client, 400, "Bad Request", "text/plain; charset=utf-8", "Bad Request")
                        return@use
                    }

                    val qIdx = rawTarget.indexOf('?')
                    val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
                    val query = if (qIdx >= 0) rawTarget.substring(qIdx + 1) else ""

                    if (path != CALLBACK_PATH) {
                        writeFixedResponse(client, 404, "Not Found", "text/plain; charset=utf-8", "Not Found")
                        return@use
                    }

                    val params = parseQueryParams(query)
                    val gotState = params["state"]
                    if (gotState.isNullOrEmpty() || gotState != expectedState) {
                        writeFixedResponse(client, 400, "Bad Request", "text/plain; charset=utf-8", "Invalid OAuth state")
                        return@use
                    }

                    if (!params["error"].isNullOrEmpty()) {
                        writeFixedResponse(client, 200, "OK", "text/html; charset=utf-8", DENIED_HTML)
                        throw GDriveAuthException("Google sign-in was denied", needsInteraction = true)
                    }

                    val code = params["code"]?.takeIf { it.isNotEmpty() }
                    if (code == null) {
                        writeFixedResponse(client, 400, "Bad Request", "text/plain; charset=utf-8", "Missing authorization code")
                        return@use
                    }

                    writeFixedResponse(client, 200, "OK", "text/html; charset=utf-8", SUCCESS_HTML)
                    return code
                }
            } catch (authErr: GDriveAuthException) {
                throw authErr
            } catch (_: SocketTimeoutException) {
                // Idle browser preconnect socket timed out — ignore and keep listening (B3).
            } catch (e: IOException) {
                if (cancelled.get()) throw CancellationIOException(cause = e)
                // Ignore transient client socket errors and continue accepting until deadline.
            }
        }
    }

    private fun readRequestLineAndDrainHeaders(input: InputStream): String? {
        val buf = ByteArray(MAX_REQUEST_BYTES)
        var total = 0
        var headerEnd = -1
        while (total < buf.size) {
            val b = input.read()
            if (b < 0) break
            buf[total++] = b.toByte()
            if (total >= 4 &&
                buf[total - 4] == '\r'.code.toByte() &&
                buf[total - 3] == '\n'.code.toByte() &&
                buf[total - 2] == '\r'.code.toByte() &&
                buf[total - 1] == '\n'.code.toByte()
            ) {
                headerEnd = total
                break
            }
        }
        if (total == 0) return null
        val limit = if (headerEnd > 0) headerEnd else total
        val text = String(buf, 0, limit, Charsets.US_ASCII)
        val firstLineEnd = text.indexOf("\r\n")
        val line = (if (firstLineEnd >= 0) text.substring(0, firstLineEnd) else text.lineSequence().firstOrNull() ?: "")
            .trim()
        return line.takeIf { it.isNotEmpty() }
    }

    private fun writeFixedResponse(
        socket: Socket,
        status: Int,
        reason: String,
        contentType: String,
        body: String,
    ) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        val out = socket.getOutputStream()
        out.write(header)
        out.write(bodyBytes)
        out.flush()
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val rawKey = if (eq >= 0) pair.substring(0, eq) else pair
            val rawVal = if (eq >= 0) pair.substring(eq + 1) else ""
            val key = runCatching { URLDecoder.decode(rawKey, "UTF-8") }.getOrDefault(rawKey)
            val value = runCatching { URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal)
            if (key.isNotEmpty() && key !in result) {
                result[key] = value
            }
        }
        return result
    }

    private fun exchangeAuthorizationCode(
        code: String,
        redirectUri: String,
        codeVerifier: String,
    ): OAuthTokens {
        val form = buildString {
            append("code=").append(urlEncode(code))
            append("&client_id=").append(urlEncode(clientId.trim()))
            if (clientSecret.isNotBlank()) {
                append("&client_secret=").append(urlEncode(clientSecret.trim()))
            }
            append("&redirect_uri=").append(urlEncode(redirectUri))
            append("&grant_type=authorization_code")
            append("&code_verifier=").append(urlEncode(codeVerifier))
        }
        val bodyBytes = form.toByteArray(Charsets.UTF_8)
        val conn = (URL(tokenEndpoint).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setFixedLengthStreamingMode(bodyBytes.size)
        }
        try {
            conn.outputStream.use { it.write(bodyBytes) }
            val status = conn.responseCode
            val raw = readHttpBody(conn, status)
            val parsed = runCatching { MiniJson.parse(raw) as? JsonValue.Obj }.getOrNull()
            if (status !in 200..299) {
                val errCode = parsed?.str("error") ?: ""
                val errDesc = parsed?.str("error_description") ?: ""
                val detail = listOf(errCode, errDesc).filter { it.isNotBlank() }.joinToString(": ")
                    .ifBlank { "HTTP $status" }
                throw GDriveAuthException(
                    message = "OAuth token exchange failed (${redactSecrets(detail)})",
                    needsInteraction = true,
                )
            }
            val accessToken = parsed?.str("access_token")?.takeIf { it.isNotBlank() }
                ?: throw IOException("OAuth token response missing access_token")
            val refreshToken = parsed.str("refresh_token") ?: ""
            val expiresIn = parsed.long("expires_in", 3600L).coerceAtLeast(60L)
            val expiresAtMs = clock.nowMs() + expiresIn * 1000L
            val scope = parsed.str("scope") ?: DRIVE_SCOPE
            return OAuthTokens(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtMs = expiresAtMs,
                scope = scope,
            )
        } finally {
            conn.disconnect()
        }
    }
}
