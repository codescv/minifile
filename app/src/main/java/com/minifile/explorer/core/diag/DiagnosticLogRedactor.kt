package com.minifile.explorer.core.diag

/**
 * Scrubs secrets from diagnostic log lines before they ever touch disk (and again on export, as a
 * belt-and-braces pass over files written by older builds). Pure JVM; shared rules with the iOS
 * `DiagnosticLogRedactor` so Android↔iPhone logs redact identically.
 *
 * - Wi-Fi QR payloads: `WIFI:T:WPA;S:ssid;P:<secret>;;` → `P:<redacted>;`
 * - Password-like keys (JSON `"pass":"…"` or `pass=…` / `password: …`, including prefixed keys such as
 *   `ftp_password`) and OAuth material (`access_token`, `refresh_token`, `id_token`, `client_secret`) →
 *   `<redacted>`
 * - `Authorization: Bearer …` / `Basic …` → `Bearer <redacted>`
 * - Session / announce tokens (`"tok":"…"`, `tok=…`, `token=…`; an escaped `\n` ends the value) → 4-char prefix + `…` so the same
 *   transfer can be correlated across both devices' logs without exposing the full token.
 *
 * Every rule is idempotent: `redact(redact(x)) == redact(x)`.
 */
object DiagnosticLogRedactor {
    const val REDACTED = "<redacted>"
    const val ELLIPSIS = "\u2026"
    const val TOKEN_PREFIX_CHARS = 4

    /** Key names whose values are always fully removed. Optional `foo_` prefixes are allowed. */
    private const val SECRET_KEY =
        "(?:[A-Za-z0-9]+[_-])*(?:pass|password|passwd|pwd|passphrase|psk|secret|client_secret|" +
            "access_token|refresh_token|id_token|api_key|apikey|pin)"

    /**
     * Unquoted value: stops at whitespace / `&;,"'()}]` and at an escaped newline (`\n`, as produced by
     * [DiagnosticLogger.formatLine]) while still covering other backslashes inside a password.
     */
    private const val VALUE = """(?:(?!\\n)[^\s&;,"'()}\]])+"""

    /** Key names whose values are truncated to a short prefix. Not preceded by `_` (so not `access_token`). */
    private const val TOKEN_KEY = "(?:tok|token)"

    private val wifiPassword = Regex(
        """(WIFI:(?:[A-Za-z]{1,8}:(?:\\.|[^;\\])*;)*?P:)(?:\\.|[^;\\])*""",
        RegexOption.IGNORE_CASE,
    )

    private val jsonSecret = Regex(
        """("$SECRET_KEY"\s*:\s*)"(?:\\.|[^"\\])*"""",
        RegexOption.IGNORE_CASE,
    )

    private val kvSecret = Regex(
        """(?<![A-Za-z0-9_])($SECRET_KEY)(\s*[=:]\s*)(?!$REDACTED_LITERAL)$VALUE""",
        RegexOption.IGNORE_CASE,
    )

    private val authScheme = Regex(
        """\b(Bearer|Basic)\s+(?!$REDACTED_LITERAL)[A-Za-z0-9._~+/=-]{8,}""",
        RegexOption.IGNORE_CASE,
    )

    private val jsonToken = Regex(
        """("$TOKEN_KEY"\s*:\s*)"((?:\\.|[^"\\])*)"""",
        RegexOption.IGNORE_CASE,
    )

    private val kvToken = Regex(
        """(?<![A-Za-z0-9_\-])($TOKEN_KEY)(\s*=\s*)($VALUE)""",
        RegexOption.IGNORE_CASE,
    )

    fun redact(input: String): String {
        if (input.isEmpty()) return input
        var s = input
        s = wifiPassword.replace(s) { m -> m.groupValues[1] + REDACTED }
        s = jsonSecret.replace(s) { m -> m.groupValues[1] + "\"" + REDACTED + "\"" }
        s = kvSecret.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + REDACTED }
        s = authScheme.replace(s) { m -> m.groupValues[1] + " " + REDACTED }
        s = jsonToken.replace(s) { m -> m.groupValues[1] + "\"" + tokenPrefix(m.groupValues[2]) + "\"" }
        s = kvToken.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + tokenPrefix(m.groupValues[3]) }
        return s
    }

    /**
     * `abcdef123` → `abcd…`; values of ≤4 chars collapse to `…` (a 4-char prefix would be the whole token);
     * empty stays empty. Already-truncated values (`abcd…`) are returned unchanged.
     */
    fun tokenPrefix(token: String?): String {
        if (token.isNullOrEmpty()) return ""
        if (token == REDACTED) return token
        if (token.endsWith(ELLIPSIS) && token.length <= TOKEN_PREFIX_CHARS + ELLIPSIS.length) return token
        if (token.length <= TOKEN_PREFIX_CHARS) return ELLIPSIS
        return token.take(TOKEN_PREFIX_CHARS) + ELLIPSIS
    }
}

/** Regex-escaped `<redacted>` used in negative look-aheads so redaction stays idempotent. */
private const val REDACTED_LITERAL = "<redacted>"
