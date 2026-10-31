package com.minifile.explorer.core.gdrive

import com.minifile.explorer.core.model.Clock
import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.Logger
import com.minifile.explorer.core.model.MiniJson
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class GDriveAuthException(
    message: String,
    val needsInteraction: Boolean,
    cause: Throwable? = null,
) : IOException(message, cause)

data class TokenState(
    val token: String,
    val expiresAtMs: Long,
)

interface DriveTokenProvider {
    /**
     * Returns a bearer token. If [staleToken] != null and equals the currently cached token,
     * a refresh is forced; callers that lost the race receive the already-refreshed token
     * without another network round trip.
     */
    fun accessToken(staleToken: String? = null): String

    companion object {
        operator fun invoke(get: (staleToken: String?) -> String): DriveTokenProvider =
            object : DriveTokenProvider {
                override fun accessToken(staleToken: String?): String = get(staleToken)
            }
    }
}

/**
 * Thread-safe token cache shared by all Google Drive auth modes (M1/M2).
 *
 * - Coalesces concurrent refreshes under [lock]: losers whose [staleToken] differs from
 *   the newly refreshed [token] return immediately without calling [doRefresh].
 * - Memoises refresh [IOException]s for [FAILURE_MEMO_MS] (5 s) so queued callers fail fast.
 * - Supports seeding [initialToken] + [initialExpiresAtMs] from persisted bookmark state
 *   to avoid needless refresh round-trips on cold startup.
 */
abstract class LockedTokenProvider(
    private val clock: Clock = Clock.SYSTEM,
    initialToken: String? = null,
    initialExpiresAtMs: Long = 0L,
) : DriveTokenProvider {

    companion object {
        const val EXPIRY_SAFETY_WINDOW_MS = 60_000L
        const val FAILURE_MEMO_MS = 5_000L
    }

    private val lock = ReentrantLock()
    private var token: String? = initialToken?.takeIf { it.isNotEmpty() }
    private var expiresAtMs: Long = if (token != null) initialExpiresAtMs else 0L
    private var lastFailureAtMs: Long = 0L
    private var lastFailure: IOException? = null

    /**
     * Runs under [lock]. [stale] is the currently cached token that is expired or rejected (or null).
     * Implementations that invalidate external caches (e.g. AccountManager) MUST do so here
     * so a race loser never invalidates a freshly issued token.
     */
    protected abstract fun doRefresh(stale: String?): TokenState

    final override fun accessToken(staleToken: String?): String = lock.withLock {
        val cur = token
        if (cur != null && cur != staleToken && clock.nowMs() < expiresAtMs - EXPIRY_SAFETY_WINDOW_MS) {
            return cur
        }
        lastFailure?.let { prev ->
            if (clock.nowMs() - lastFailureAtMs < FAILURE_MEMO_MS) {
                throw prev
            }
        }
        try {
            val next = doRefresh(stale = cur)
            token = next.token
            expiresAtMs = next.expiresAtMs
            lastFailure = null
            next.token
        } catch (e: IOException) {
            lastFailure = e
            lastFailureAtMs = clock.nowMs()
            throw e
        }
    }

    fun peekCachedToken(): String? = lock.withLock { token }
}

/**
 * Pure-JVM OAuth 2.0 refresh-token provider for `BROWSER_PKCE` and `PASTED_TOKEN` bookmarks.
 */
class OAuthTokenProvider(
    private val clientId: String,
    private val clientSecret: String = "",
    initialAccessToken: String = "",
    initialRefreshToken: String = "",
    initialExpiresAtMs: Long = 0L,
    private val tokenEndpoint: String = DEFAULT_OAUTH_TOKEN_ENDPOINT,
    private val clock: Clock = Clock.SYSTEM,
    private val logger: Logger = Logger.NOOP,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
    private val onTokensRefreshed: (accessToken: String, refreshToken: String?, expiresAtMs: Long) -> Unit =
        { _, _, _ -> },
) : LockedTokenProvider(
    clock = clock,
    initialToken = initialAccessToken.takeIf { it.isNotBlank() },
    initialExpiresAtMs = when {
        initialAccessToken.isBlank() -> 0L
        initialExpiresAtMs > 0L -> initialExpiresAtMs
        initialRefreshToken.isBlank() || clientId.isBlank() -> Long.MAX_VALUE / 2
        else -> 0L
    },
) {
    private var refreshToken: String = initialRefreshToken

    override fun doRefresh(stale: String?): TokenState {
        val rt = refreshToken.trim()
        val cid = clientId.trim()
        if (rt.isEmpty() || cid.isEmpty()) {
            throw GDriveAuthException(
                message = "Google sign-in expired — sign in again",
                needsInteraction = true,
            )
        }

        val form = buildString {
            append("grant_type=refresh_token")
            append("&refresh_token=").append(urlEncode(rt))
            append("&client_id=").append(urlEncode(cid))
            if (clientSecret.isNotBlank()) {
                append("&client_secret=").append(urlEncode(clientSecret.trim()))
            }
        }
        val bodyBytes = form.toByteArray(Charsets.UTF_8)
        val conn = (URL(tokenEndpoint).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setFixedLengthStreamingMode(bodyBytes.size)
        }

        try {
            conn.outputStream.use { out -> out.write(bodyBytes) }
            val status = conn.responseCode
            val raw = readHttpBody(conn, status)
            val parsed = runCatching { MiniJson.parse(raw) as? JsonValue.Obj }.getOrNull()
            if (status !in 200..299) {
                val errCode = parsed?.str("error") ?: ""
                val errDesc = parsed?.str("error_description") ?: parsed?.obj("error")?.str("message") ?: ""
                logger.log("GDriveAuth", "Token refresh failed (HTTP $status, error=$errCode)", null)
                if (status == 400 || status == 401) {
                    if (errCode == "invalid_grant" || errCode == "unauthorized_client" || errCode == "invalid_client") {
                        throw GDriveAuthException(
                            message = "Google sign-in expired — sign in again",
                            needsInteraction = true,
                        )
                    }
                }
                val detail = errDesc.ifBlank { errCode.ifBlank { "HTTP $status" } }
                throw IOException("Token refresh failed: ${redactSecrets(detail)}")
            }

            val newAccess = parsed?.str("access_token")?.takeIf { it.isNotBlank() }
                ?: throw IOException("Token endpoint response missing access_token")
            val expiresInSec = parsed.long("expires_in", 3600L).coerceAtLeast(60L)
            val newExpiresAt = clock.nowMs() + expiresInSec * 1000L
            val rotatedRefresh = parsed.str("refresh_token")?.takeIf { it.isNotBlank() }
            if (rotatedRefresh != null) {
                refreshToken = rotatedRefresh
            }
            runCatching { onTokensRefreshed(newAccess, rotatedRefresh, newExpiresAt) }
            return TokenState(token = newAccess, expiresAtMs = newExpiresAt)
        } finally {
            conn.disconnect()
        }
    }
}

internal fun readHttpBody(conn: HttpURLConnection, status: Int): String {
    val stream = if (status in 200..299) {
        runCatching { conn.inputStream }.getOrNull()
    } else {
        conn.errorStream ?: runCatching { conn.inputStream }.getOrNull()
    }
    return stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
}

/**
 * Fetches `user.emailAddress` from `GET /about` to verify credentials and populate the bookmark.
 */
fun fetchDriveUserEmail(
    accessToken: String,
    apiBase: String = DEFAULT_DRIVE_API_BASE,
    connectTimeoutMs: Int = 10_000,
    readTimeoutMs: Int = 15_000,
): String {
    val url = "${apiBase.trimEnd('/')}/about?fields=${urlEncode("user(emailAddress)")}"
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        requestMethod = "GET"
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        setRequestProperty("Authorization", "Bearer $accessToken")
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Accept-Encoding", "identity")
    }
    try {
        val status = conn.responseCode
        val raw = readHttpBody(conn, status)
        if (status == 401) {
            throw GDriveAuthException("Google Drive token is invalid or expired", needsInteraction = true)
        }
        if (status !in 200..299) {
            val errObj = runCatching { (MiniJson.parse(raw) as? JsonValue.Obj)?.obj("error") }.getOrNull()
            val msg = errObj?.str("message") ?: "HTTP $status"
            throw IOException("Google Drive verification failed: $msg")
        }
        val obj = runCatching { MiniJson.parse(raw) as? JsonValue.Obj }.getOrNull()
        return obj?.obj("user")?.str("emailAddress")?.trim() ?: ""
    } finally {
        conn.disconnect()
    }
}

/**
 * Best-effort OAuth token revocation used when removing a Google Drive server bookmark.
 */
fun revokeOAuthTokenBestEffort(
    token: String,
    revokeEndpoint: String = DEFAULT_OAUTH_REVOKE_ENDPOINT,
) {
    if (token.isBlank()) return
    runCatching {
        val body = "token=${urlEncode(token)}".toByteArray(Charsets.UTF_8)
        val conn = (URL(revokeEndpoint).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 5_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setFixedLengthStreamingMode(body.size)
        }
        try {
            conn.outputStream.use { it.write(body) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }
}
