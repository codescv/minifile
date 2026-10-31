package com.minifile.explorer.core.gdrive

import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.MiniJson
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-JVM loopback OAuth 2.0 `/token` test server verifying PKCE `S256` code challenges,
 * `refresh_token` grants, `invalid_grant` errors, and fixed-length HTTP request bodies.
 */
class FakeTokenServer : Closeable {

    data class IssuedAuthCode(
        val clientId: String,
        val redirectUri: String,
        val codeChallenge: String,
        val codeChallengeMethod: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresInSec: Long = 3600L,
    )

    data class RefreshGrant(
        val clientId: String,
        val clientSecret: String?,
        val nextAccessToken: String,
        val rotatedRefreshToken: String? = null,
        val expiresInSec: Long = 3600L,
    )

    data class TokenRequestRecord(
        val grantType: String,
        val params: Map<String, String>,
    )

    private val pool = Executors.newCachedThreadPool()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = pool
        createContext("/token") { exchange -> handleToken(exchange) }
        start()
    }

    val port: Int = server.address.port
    val tokenEndpoint: String = "http://127.0.0.1:$port/token"

    val requestCount = AtomicInteger(0)
    val requests = CopyOnWriteArrayList<TokenRequestRecord>()
    val failInvalidGrant = AtomicBoolean(false)
    val fail500Count = AtomicInteger(0)

    private val authCodes = ConcurrentHashMap<String, IssuedAuthCode>()
    private val refreshGrants = ConcurrentHashMap<String, RefreshGrant>()

    fun registerAuthCode(code: String, spec: IssuedAuthCode) {
        authCodes[code] = spec
    }

    fun registerRefreshToken(refreshToken: String, spec: RefreshGrant) {
        refreshGrants[refreshToken] = spec
    }

    private fun handleToken(exchange: HttpExchange) {
        try {
            requestCount.incrementAndGet()
            if (exchange.requestMethod.uppercase() != "POST") {
                sendJson(exchange, 405, MiniJson.obj("error" to JsonValue.Str("method_not_allowed")))
                return
            }
            val te = exchange.requestHeaders.getFirst("Transfer-Encoding") ?: ""
            val cl = exchange.requestHeaders.getFirst("Content-Length")
            if (te.contains("chunked", ignoreCase = true) || cl == null) {
                sendJson(exchange, 411, MiniJson.obj("error" to JsonValue.Str("length_required")))
                return
            }

            val rawBody = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            val params = parseForm(rawBody)
            val grantType = params["grant_type"].orEmpty()
            requests.add(TokenRequestRecord(grantType = grantType, params = params))

            if (fail500Count.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                sendJson(
                    exchange,
                    500,
                    MiniJson.obj(
                        "error" to JsonValue.Str("server_error"),
                        "error_description" to JsonValue.Str("Simulated 500"),
                    ),
                )
                return
            }

            if (failInvalidGrant.get()) {
                sendJson(
                    exchange,
                    400,
                    MiniJson.obj(
                        "error" to JsonValue.Str("invalid_grant"),
                        "error_description" to JsonValue.Str("Token has been expired or revoked."),
                    ),
                )
                return
            }

            when (grantType) {
                "authorization_code" -> {
                    val code = params["code"].orEmpty()
                    val spec = authCodes.remove(code)
                    if (spec == null) {
                        sendJson(exchange, 400, MiniJson.obj("error" to JsonValue.Str("invalid_grant")))
                        return
                    }
                    if (params["client_id"] != spec.clientId || params["redirect_uri"] != spec.redirectUri) {
                        sendJson(exchange, 400, MiniJson.obj("error" to JsonValue.Str("invalid_request")))
                        return
                    }
                    val verifier = params["code_verifier"].orEmpty()
                    if (spec.codeChallengeMethod != "S256" || verifier.isEmpty()) {
                        sendJson(exchange, 400, MiniJson.obj("error" to JsonValue.Str("invalid_grant")))
                        return
                    }
                    val computedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
                    )
                    if (computedChallenge != spec.codeChallenge) {
                        sendJson(
                            exchange,
                            400,
                            MiniJson.obj(
                                "error" to JsonValue.Str("invalid_grant"),
                                "error_description" to JsonValue.Str("PKCE verification failed"),
                            ),
                        )
                        return
                    }
                    sendJson(
                        exchange,
                        200,
                        MiniJson.obj(
                            "access_token" to JsonValue.Str(spec.accessToken),
                            "refresh_token" to JsonValue.Str(spec.refreshToken),
                            "expires_in" to JsonValue.Num(spec.expiresInSec.toString()),
                            "token_type" to JsonValue.Str("Bearer"),
                            "scope" to JsonValue.Str(DRIVE_SCOPE),
                        ),
                    )
                }

                "refresh_token" -> {
                    val rt = params["refresh_token"].orEmpty()
                    val spec = refreshGrants[rt]
                    if (spec == null) {
                        sendJson(exchange, 400, MiniJson.obj("error" to JsonValue.Str("invalid_grant")))
                        return
                    }
                    if (params["client_id"] != spec.clientId) {
                        sendJson(exchange, 401, MiniJson.obj("error" to JsonValue.Str("invalid_client")))
                        return
                    }
                    if (spec.clientSecret != null && params["client_secret"] != spec.clientSecret) {
                        sendJson(exchange, 401, MiniJson.obj("error" to JsonValue.Str("unauthorized_client")))
                        return
                    }
                    val entries = LinkedHashMap<String, JsonValue>().apply {
                        put("access_token", JsonValue.Str(spec.nextAccessToken))
                        put("expires_in", JsonValue.Num(spec.expiresInSec.toString()))
                        put("token_type", JsonValue.Str("Bearer"))
                        put("scope", JsonValue.Str(DRIVE_SCOPE))
                        if (!spec.rotatedRefreshToken.isNullOrBlank()) {
                            put("refresh_token", JsonValue.Str(spec.rotatedRefreshToken))
                        }
                    }
                    sendJson(exchange, 200, JsonValue.Obj(entries))
                }

                else -> {
                    sendJson(exchange, 400, MiniJson.obj("error" to JsonValue.Str("unsupported_grant_type")))
                }
            }
        } finally {
            exchange.close()
        }
    }

    private fun parseForm(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in body.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val k = if (eq >= 0) part.substring(0, eq) else part
            val v = if (eq >= 0) part.substring(eq + 1) else ""
            out[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
        }
        return out
    }

    private fun sendJson(exchange: HttpExchange, status: Int, obj: JsonValue.Obj) {
        val bytes = MiniJson.stringify(obj).toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(0)
        pool.shutdownNow()
    }
}
