package com.minifile.explorer.core.peer

import java.io.IOException
import java.io.InputStream
import java.util.Locale

enum class PeerSource {
    BEACON,
    PROBE,
    MANUAL,

    /** Discovered passively over Bluetooth LE (no IP until a hotspot / LAN link is negotiated). */
    BLE,

    /** Learned by scanning a QR code (`WIFI:` / `minifile://` / `http://`). */
    QR,

    /** Announced itself via `POST /api/peer/announce` while this device hosts a hotspot. */
    ANNOUNCE,
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
    /** `"android"` / `"ios"` when known (from `/api/peer/info` `os`, BLE adv, or announce). */
    val platform: String? = null,
    /** One-shot `tok` forwarded to `/api/peer/begin` for hotspot (BLE / Files-QR) sessions. */
    val linkToken: String? = null,
    /** `true` when the remote host currently serves a LocalOnlyHotspot (`/api/peer/info` `hotspot`). */
    val hotspot: Boolean = false,
    /** BLE flags byte (`HotspotLinkCodec.FLAG_*`) for [PeerSource.BLE] peers. */
    val bleFlags: Int = 0,
) {
    /** `true` when this peer has no routable IP yet (pure BLE row). */
    val needsLink: Boolean
        get() = host.isBlank() || port <= 0

    /** Human-readable address for list rows (never `":0"`). */
    val displayEndpoint: String
        get() = if (needsLink) {
            if (source == PeerSource.BLE) "Bluetooth" else "Nearby"
        } else {
            endpoint
        }

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

/** Coarse failure class of a [PeerTransferException], used to decide on an automatic Bluetooth fallback. */
enum class PeerFailureKind {
    /** Anything else (declined, read-only, disk full, protocol, …): never retried over another link. */
    OTHER,

    /** TCP connect / read failed before the receiver answered (AP isolation, VPN black-hole, wrong subnet). */
    UNREACHABLE,

    /** A `301/302/303/307/308` came back: a VPN, proxy or captive portal intercepted the LAN request. */
    INTERCEPTED,
}

class PeerTransferException(
    val httpStatus: Int?,
    val userMessage: String,
    cause: Throwable? = null,
    /** `true` when the failure happened before a `/api/peer/begin` session existed (no file bytes were sent). */
    val beforeSession: Boolean = false,
    val kind: PeerFailureKind = if (httpStatus != null && isRedirectStatus(httpStatus)) {
        PeerFailureKind.INTERCEPTED
    } else {
        PeerFailureKind.OTHER
    },
) : IOException(userMessage, cause) {
    /** Files servers never redirect `/api/peer/…`: a `3xx` means a VPN / proxy / captive portal answered instead. */
    val isInterceptedRedirect: Boolean
        get() = kind == PeerFailureKind.INTERCEPTED

    companion object {
        val REDIRECT_STATUSES: Set<Int> = setOf(301, 302, 303, 307, 308)

        fun isRedirectStatus(status: Int): Boolean = status in REDIRECT_STATUSES

        /** Actionable English copy for an intercepted LAN request (never "returned HTTP 302"). */
        fun interceptedMessage(peerName: String, status: Int): String =
            "Connection to $peerName was intercepted (HTTP $status) — turn off VPN or proxy (or enable LAN bypass) and try again"

        /**
         * `Location` header reduced to `scheme://host[:port]/path` for logs: query, fragment and userinfo stripped so
         * captive-portal / proxy tokens are never exposed. Returns `null` when absent or unparsable.
         */
        fun sanitizeLocation(raw: String?): String? {
            val v = raw?.trim().orEmpty()
            if (v.isEmpty()) return null
            return runCatching {
                val u = java.net.URI(v)
                val host = u.host ?: return@runCatching u.rawPath?.substringBefore('?')?.takeIf { it.isNotEmpty() }
                val scheme = u.scheme ?: "http"
                val port = if (u.port > 0) ":${u.port}" else ""
                "$scheme://$host$port${u.rawPath.orEmpty()}"
            }.getOrNull()
        }
    }
}
