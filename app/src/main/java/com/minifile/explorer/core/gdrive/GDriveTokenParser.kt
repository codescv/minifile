package com.minifile.explorer.core.gdrive

import com.minifile.explorer.core.model.Clock
import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.MiniJson

data class PastedCredentials(
    val accessToken: String,
    val refreshToken: String = "",
    val expiresAtMs: Long = 0L,
    val clientId: String = "",
    val clientSecret: String = "",
    val tokenType: String = "Bearer",
    val scope: String = "",
)

/**
 * Pure-JVM parser for Mode 3 (`Paste Token / JSON`) (§2.5, B4).
 *
 * Accepts:
 * 1. JSON object (`rclone authorize "drive"` or `minifile gdrive-auth` output).
 * 2. `rclone.conf` section fragment (`token = {...}`, optional `client_id = ...`, `client_secret = ...`).
 * 3. Bare whitespace-free token of >= 20 printable token characters not starting with `{` or `[`
 *    (does NOT check for any `ya29.` prefix).
 */
object GDriveTokenParser {

    fun parse(text: String, clock: Clock = Clock.SYSTEM): PastedCredentials? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        // 1. Direct JSON object
        if (trimmed.startsWith("{")) {
            val obj = runCatching { MiniJson.parse(trimmed) as? JsonValue.Obj }.getOrNull()
                ?: return null
            return parseJsonObj(obj, clock)
        }
        if (trimmed.startsWith("[") && !trimmed.contains('\n')) {
            return null
        }

        // 2. rclone.conf fragment (`token = {...}` + optional `client_id = ...`, `client_secret = ...`)
        parseRcloneConfBlock(trimmed, clock)?.let { return it }

        // Also support rclone authorize terminal output where a single JSON object sits between banner lines
        val embeddedJson = extractRcloneBannerJson(trimmed)
        if (embeddedJson != null) {
            val obj = runCatching { MiniJson.parse(embeddedJson) as? JsonValue.Obj }.getOrNull()
            if (obj != null) {
                parseJsonObj(obj, clock)?.let { return it }
            }
        }

        // 3. Bare access token: single whitespace-free string >= 20 chars, valid HTTP Bearer b64token chars
        if (isValidBareToken(trimmed)) {
            return PastedCredentials(
                accessToken = trimmed,
                expiresAtMs = 0L,
            )
        }

        return null
    }

    private fun parseJsonObj(obj: JsonValue.Obj, clock: Clock): PastedCredentials? {
        val accessToken = obj.str("access_token")?.trim()?.takeIf {
            it.isNotEmpty() && it.none(Char::isWhitespace)
        } ?: return null

        val refreshToken = obj.str("refresh_token")?.trim() ?: ""
        val clientId = obj.str("client_id")?.trim() ?: ""
        val clientSecret = obj.str("client_secret")?.trim() ?: ""
        val tokenType = obj.str("token_type")?.trim()?.ifEmpty { "Bearer" } ?: "Bearer"
        val scope = obj.str("scope")?.trim() ?: ""

        val expiresAtMs = parseExpiryMs(obj, clock)

        return PastedCredentials(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMs = expiresAtMs,
            clientId = clientId,
            clientSecret = clientSecret,
            tokenType = tokenType,
            scope = scope,
        )
    }

    private fun parseExpiryMs(obj: JsonValue.Obj, clock: Clock): Long {
        val expiryStr = obj.str("expiry")?.trim()
        if (!expiryStr.isNullOrEmpty()) {
            val parsed = parseRfc3339Ms(expiryStr)
            if (parsed > 0L) return parsed
        }

        val expiresAtVal = obj.entries["expires_at"]
        when (expiresAtVal) {
            is JsonValue.Num -> {
                val n = expiresAtVal.toLongOrNull() ?: 0L
                if (n > 0L) return normalizeEpochToMs(n)
            }
            is JsonValue.Str -> {
                val s = expiresAtVal.value.trim()
                val n = s.toLongOrNull()
                if (n != null && n > 0L) return normalizeEpochToMs(n)
                val rfc = parseRfc3339Ms(s)
                if (rfc > 0L) return rfc
            }
            else -> Unit
        }

        val expiresInVal = obj.entries["expires_in"]
        val expiresInSec = when (expiresInVal) {
            is JsonValue.Num -> expiresInVal.toLongOrNull()
            is JsonValue.Str -> expiresInVal.value.trim().toLongOrNull()
            else -> null
        }
        if (expiresInSec != null && expiresInSec > 0L) {
            return clock.nowMs() + expiresInSec * 1000L
        }

        return 0L
    }

    private fun normalizeEpochToMs(epoch: Long): Long =
        if (epoch < 100_000_000_000L) epoch * 1000L else epoch

    private fun parseRcloneConfBlock(text: String, clock: Clock): PastedCredentials? {
        var tokenJson: String? = null
        var iniClientId = ""
        var iniClientSecret = ""

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim().removeSurrounding("\"")
            when (key) {
                "token" -> if (value.startsWith("{")) tokenJson = value
                "client_id" -> iniClientId = value
                "client_secret" -> iniClientSecret = value
            }
        }

        val rawToken = tokenJson ?: return null
        val obj = runCatching { MiniJson.parse(rawToken) as? JsonValue.Obj }.getOrNull() ?: return null
        val base = parseJsonObj(obj, clock) ?: return null
        return base.copy(
            clientId = base.clientId.ifEmpty { iniClientId },
            clientSecret = base.clientSecret.ifEmpty { iniClientSecret },
        )
    }

    private fun extractRcloneBannerJson(text: String): String? {
        if (!text.contains("--->") && !text.contains("<---")) return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun isValidBareToken(s: String): Boolean {
        if (s.length < 20) return false
        if (s.startsWith("{") || s.startsWith("[")) return false
        if (s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)) {
            return false
        }
        // RFC 6750 §2.1 Bearer b64token alphabet: ALPHA / DIGIT / "-" / "." / "_" / "~" / "+" / "/" *"="
        for (i in s.indices) {
            val ch = s[i]
            val ok = ch in 'A'..'Z' ||
                ch in 'a'..'z' ||
                ch in '0'..'9' ||
                ch == '-' || ch == '.' || ch == '_' || ch == '~' || ch == '+' || ch == '/' ||
                (ch == '=' && s.substring(i).all { it == '=' })
            if (!ok) return false
        }
        return true
    }
}
