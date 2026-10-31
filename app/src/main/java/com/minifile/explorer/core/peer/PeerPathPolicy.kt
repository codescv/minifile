package com.minifile.explorer.core.peer

import com.minifile.explorer.core.ftp.FtpAccessDeniedException
import com.minifile.explorer.core.vfs.NameValidation
import com.minifile.explorer.core.vfs.PathUtils
import java.net.URLDecoder

/**
 * Pure-JVM relative path & display-name sanitizer for peer file transfers.
 * Zero `android.*` / `androidx.*` / `dalvik.*` imports.
 */
object PeerPathPolicy {
    const val MAX_REL_PATH_CHARS = 1024
    const val MAX_REL_PATH_DEPTH = 32
    const val DEFAULT_MAX_DISPLAY_NAME = 64

    /**
     * Percent-decodes [raw] once (RFC 3986, preserving literal `+`), then enforces:
     * - non-empty and `<= 1024` characters
     * - no NUL (`\u0000`) or backslash (`\`)
     * - no leading `/` or trailing `/`
     * - no empty, `.`, or `..` segments
     * - depth `<= 32`
     * - every segment passes [PathUtils.validateName] (`<= 255` UTF-8 bytes, optional [fatSafe]).
     *
     * Throws [FtpAccessDeniedException] on any violation (mapped to `403 Forbidden` by HTTP server).
     */
    fun validateRelPath(raw: String, fatSafe: Boolean = false): List<String> {
        if (raw.isEmpty()) {
            throw FtpAccessDeniedException("Relative path cannot be empty")
        }
        val decoded = decodePercentStrict(raw)
        if (decoded.isEmpty() || decoded.length > MAX_REL_PATH_CHARS) {
            throw FtpAccessDeniedException("Relative path exceeds maximum length")
        }
        if (decoded.contains('\u0000') || decoded.contains('\\')) {
            throw FtpAccessDeniedException("Illegal character in relative path")
        }
        if (decoded.startsWith("/") || decoded.endsWith("/")) {
            throw FtpAccessDeniedException("Relative path must not start or end with '/'")
        }

        val segments = decoded.split('/')
        if (segments.isEmpty() || segments.size > MAX_REL_PATH_DEPTH) {
            throw FtpAccessDeniedException("Relative path depth exceeds maximum ($MAX_REL_PATH_DEPTH)")
        }

        for (seg in segments) {
            if (seg.isEmpty() || seg == "." || seg == "..") {
                throw FtpAccessDeniedException("Invalid or traversal segment in relative path")
            }
            when (val v = PathUtils.validateName(seg, fatSafe = fatSafe)) {
                NameValidation.Valid -> Unit
                is NameValidation.Invalid -> throw FtpAccessDeniedException("Invalid path segment: ${v.reason}")
            }
        }
        return segments
    }

    /**
     * Strips ASCII control characters (`< 0x20` and `0x7F`), trims leading/trailing whitespace,
     * and truncates to at most [maxLen] UTF-16 code units without splitting surrogate pairs.
     */
    fun sanitizeDisplayName(raw: String, maxLen: Int = DEFAULT_MAX_DISPLAY_NAME): String {
        val cap = maxLen.coerceAtLeast(1)
        if (raw.isEmpty()) return ""
        val cleaned = buildString(minOf(raw.length, cap + 8)) {
            for (ch in raw) {
                if (ch.code < 0x20 || ch.code == 0x7F) continue
                append(ch)
            }
        }.trim()
        if (cleaned.length <= cap) return cleaned
        var end = cap
        if (end > 0 && Character.isHighSurrogate(cleaned[end - 1])) {
            end--
        }
        return cleaned.substring(0, end).trimEnd()
    }

    internal fun decodePercentStrict(raw: String): String {
        // Validate any '%' escapes strictly before delegating to URLDecoder
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '%') {
                if (i + 2 >= raw.length || !isHexDigit(raw[i + 1]) || !isHexDigit(raw[i + 2])) {
                    throw FtpAccessDeniedException("Malformed percent-encoding in relative path")
                }
                i += 3
            } else {
                i++
            }
        }
        return try {
            // Preserve literal '+' rather than converting it to space
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            throw FtpAccessDeniedException("Malformed percent-encoding in relative path")
        }
    }

    private fun isHexDigit(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
}
