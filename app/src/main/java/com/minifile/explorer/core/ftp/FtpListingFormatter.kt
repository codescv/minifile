package com.minifile.explorer.core.ftp

import com.minifile.explorer.core.vfs.VfsNode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object FtpListingFormatter {
    private const val SIX_MONTHS_MS = 180L * 24L * 3600L * 1000L

    private val RECENT_LIST_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM dd HH:mm", Locale.US).withZone(ZoneOffset.UTC)
    private val OLD_LIST_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM dd  yyyy", Locale.US).withZone(ZoneOffset.UTC)
    private val MLSD_UTC_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US).withZone(ZoneOffset.UTC)

    fun formatUnixListLine(node: VfsNode, nowMs: Long = System.currentTimeMillis()): String {
        val perms = node.posixPermissions ?: if (node.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
        val size = if (node.isDirectory) 0L else node.size.coerceAtLeast(0L)
        val mtime = if (node.lastModified > 0L) node.lastModified else nowMs
        val isRecent = kotlin.math.abs(nowMs - mtime) < SIX_MONTHS_MS
        val datePart = (if (isRecent) RECENT_LIST_FMT else OLD_LIST_FMT).format(Instant.ofEpochMilli(mtime))
        return String.format(Locale.US, "%s 1 owner group %12d %s %s", perms, size, datePart, node.name)
    }

    fun formatMlsdFacts(node: VfsNode, serverReadOnly: Boolean, displayName: String = node.name): String {
        val type = if (node.isDirectory) "dir" else "file"
        val size = if (node.isDirectory) 0L else node.size.coerceAtLeast(0L)
        val writable = node.canWrite && !serverReadOnly
        val perm = if (node.isDirectory) {
            if (writable) "elcmfd" else "el"
        } else {
            if (writable) "radfw" else "r"
        }
        val sb = StringBuilder()
        sb.append("type=").append(type).append(';')
        sb.append("size=").append(size).append(';')
        if (node.lastModified > 0L) {
            sb.append("modify=").append(MLSD_UTC_FMT.format(Instant.ofEpochMilli(node.lastModified))).append(';')
        }
        sb.append("perm=").append(perm).append("; ")
        sb.append(displayName)
        return sb.toString()
    }

    fun formatMdtmTimestamp(epochMs: Long): String =
        MLSD_UTC_FMT.format(Instant.ofEpochMilli(epochMs))

    fun quoteFtpPath(virtualPath: String): String =
        "\"" + virtualPath.replace("\"", "\"\"") + "\""
}
