package com.minifile.explorer.core.diag

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Snapshot of app/device/network context recorded in the `[SESSION]` banner and export header. */
data class DiagnosticEnvironment(
    val appVersion: String,
    val versionCode: Long,
    val platform: String,
    val device: String,
    /** e.g. `wlan0 192.168.1.23/24`, `ap0 192.168.49.1/24 (hotspot)`. */
    val interfaces: List<String>,
    val discoverable: Boolean,
    val httpServer: String,
    val ftpServer: String,
    val extra: List<Pair<String, String>> = emptyList(),
)

object DiagnosticExport {
    /** UTF-16 chars: ≈256 KB in a Binder parcel, far below the 1 MB transaction buffer. */
    const val CLIPBOARD_MAX_CHARS = 128 * 1024
    const val FILE_PREFIX = "minifile-android-diag-"
    const val EXPORT_DIR = "diag_export"

    private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun exportFileName(epochMs: Long, zone: ZoneId): String =
        FILE_PREFIX + FILE_STAMP.format(Instant.ofEpochMilli(epochMs).atZone(zone)) + ".txt"

    /** Settings ▸ Diagnostics row caption (English, shared wording with iOS). */
    fun statusLine(enabled: Boolean, totalBytes: Long): String = when {
        enabled -> "Recording · ${formatSize(totalBytes)}"
        totalBytes > 0L -> "Off · ${formatSize(totalBytes)} saved"
        else -> "Off — enable to record transfer & network diagnostics"
    }

    /** `0 B`, `812 B`, `18.4 KB`, `1.0 MB` (1024-based, always `.` decimal separator). */
    fun formatSize(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        if (b < 1024L) return "$b B"
        val kb = b / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        return String.format(Locale.US, "%.1f MB", kb / 1024.0)
    }

    fun sessionBanner(env: DiagnosticEnvironment, reason: String = "diagnostic logging enabled"): String = buildString {
        append(reason)
        append(" · app=").append(env.appVersion).append(" (").append(env.versionCode).append(')')
        append(" · os=").append(env.platform)
        append(" · device=").append(env.device)
        append(" · discoverable=").append(if (env.discoverable) "on" else "off")
        append(" · http=").append(env.httpServer)
        append(" · ftp=").append(env.ftpServer)
        append(" · ifaces=[").append(env.interfaces.joinToString(", ")).append(']')
        env.extra.forEach { (k, v) -> append(" · ").append(k).append('=').append(v) }
    }

    fun header(env: DiagnosticEnvironment, generatedAtMs: Long, zone: ZoneId, stats: DiagnosticLogStats): String =
        buildString {
            append("# MiniFile diagnostic log\n")
            append("# generated: ").append(DiagnosticLogger.formatTimestamp(generatedAtMs, zone)).append('\n')
            append("# app: ").append(env.appVersion).append(" (").append(env.versionCode).append(")\n")
            append("# os: ").append(env.platform).append('\n')
            append("# device: ").append(env.device).append('\n')
            append("# discoverable: ").append(if (env.discoverable) "on" else "off").append('\n')
            append("# http: ").append(env.httpServer).append('\n')
            append("# ftp: ").append(env.ftpServer).append('\n')
            append("# interfaces: ").append(env.interfaces.joinToString(", ").ifEmpty { "none" }).append('\n')
            env.extra.forEach { (k, v) -> append("# ").append(k).append(": ").append(v).append('\n') }
            append("# log bytes: ").append(stats.totalBytes)
            if (stats.droppedLines > 0) append(" (dropped ").append(stats.droppedLines).append(" lines)")
            append('\n')
            append("# ---\n")
        }.let(DiagnosticLogRedactor::redact)

    /**
     * Header + chronological body. When [maxChars] is set and the result would exceed it, the *oldest* lines are
     * dropped (cut at a line boundary) and a `# … truncated …` marker is inserted, so the newest events — the
     * ones being debugged — always survive. The returned string never exceeds [maxChars].
     */
    fun compose(header: String, body: String, maxChars: Int? = null): String {
        val trimmedBody = body.ifEmpty { "# (no diagnostic log lines recorded)\n" }
        val full = header + trimmedBody
        if (maxChars == null || full.length <= maxChars) return full
        val marker = "# \u2026 truncated %d earlier chars \u2026\n"
        // Size the marker with the widest possible number so the final string can only be shorter.
        val budget = maxChars - header.length - marker.format(body.length).length
        if (budget <= 0) return full.take(maxChars)
        val minStart = body.length - budget
        val nl = body.indexOf('\n', (minStart - 1).coerceAtLeast(0))
        var start = if (nl >= 0) nl + 1 else minStart
        if (start < body.length && Character.isLowSurrogate(body[start])) start++
        return header + marker.format(start) + body.substring(start)
    }
}
