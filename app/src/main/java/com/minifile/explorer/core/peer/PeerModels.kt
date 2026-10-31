package com.minifile.explorer.core.peer

import java.io.IOException
import java.io.InputStream
import java.util.Locale

enum class PeerSource {
    BEACON,
    PROBE,
    MANUAL,
}

enum class PeerConflictPolicy(val wireValue: String) {
    RENAME("rename"),
    OVERWRITE("overwrite"),
    SKIP("skip"),
    ;

    companion object {
        fun fromWire(raw: String?): PeerConflictPolicy? {
            if (raw.isNullOrBlank()) return RENAME
            val norm = raw.trim().lowercase(Locale.ROOT)
            return entries.firstOrNull { it.wireValue == norm }
        }
    }
}

data class PeerInfo(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val version: String,
    val readOnly: Boolean,
    val source: PeerSource,
    val lastSeenMs: Long,
    val proto: Int = 1,
) {
    val endpoint: String
        get() {
            val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            return "$hostPart:$port"
        }

    val baseUrl: String
        get() = "http://$endpoint"
}

data class PeerSendItem(
    val relPath: String,
    val size: Long,
    val mtime: Long,
    val isDirectory: Boolean = false,
    val open: () -> InputStream = {
        throw UnsupportedOperationException("Cannot open stream on directory item: $relPath")
    },
)

data class PeerSendResult(
    val files: Int,
    val bytes: Long,
    val skipped: Int,
    val errors: List<Pair<String, String>>,
    val dirs: Int = 0,
    val cancelled: Boolean = false,
)

class PeerTransferException(
    val httpStatus: Int?,
    val userMessage: String,
    cause: Throwable? = null,
) : IOException(userMessage, cause)
