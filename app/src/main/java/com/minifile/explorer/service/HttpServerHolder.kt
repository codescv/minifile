package com.minifile.explorer.service

import com.minifile.explorer.core.http.HotspotSessionGate
import com.minifile.explorer.core.http.HttpEvent
import com.minifile.explorer.core.http.HttpTransferDirection
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HttpLogEntry(
    val id: Long,
    val timestampMs: Long,
    val formattedTime: String,
    val clientIp: String,
    val summary: String,
)

data class HttpServerState(
    val isRunning: Boolean = false,
    val isStarting: Boolean = false,
    val boundPort: Int = 8080,
    val urls: List<String> = emptyList(),
    val hasLanInterface: Boolean = false,
    val activeClients: Int = 0,
    val uploadsCount: Int = 0,
    val downloadsCount: Int = 0,
    val lastError: String? = null,
    val log: List<HttpLogEntry> = emptyList(),
)

object HttpServerHolder {
    const val MAX_LOG_ENTRIES = 500

    private val lock = Any()
    private var nextId = 1L
    private val sessionLogRowIds = LinkedHashMap<String, Long>()
    private val sessionPeerNames = LinkedHashMap<String, String>()
    private val _state = MutableStateFlow(HttpServerState())
    val state: StateFlow<HttpServerState> = _state.asStateFlow()

    /**
     * Process-wide hotspot/BLE token + interface gate shared by every [com.minifile.explorer.core.http.HttpFileServerEngine]
     * instance (the engine is recreated on each start; the gate survives restarts).
     */
    val hotspotGate = HotspotSessionGate()

    private val _peerEvents = MutableSharedFlow<HttpEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** `PeerAnnounce` / `PeerSessionStart` / `PeerSessionEnd` stream for hotspot orchestration (LOHS auto-stop, auto-push). */
    val peerEvents: SharedFlow<HttpEvent> = _peerEvents.asSharedFlow()

    fun markStarting() = synchronized(lock) {
        _state.value = _state.value.copy(isStarting = true, lastError = null)
    }

    fun markStarted(port: Int, lanIps: List<String>) = synchronized(lock) {
        val urls = if (lanIps.isNotEmpty()) {
            lanIps.map { "http://$it:$port" }
        } else {
            listOf("http://127.0.0.1:$port")
        }
        _state.value = _state.value.copy(
            isRunning = true,
            isStarting = false,
            boundPort = port,
            urls = urls,
            hasLanInterface = lanIps.isNotEmpty(),
            activeClients = 0,
            uploadsCount = 0,
            downloadsCount = 0,
            lastError = null,
        )
        appendLogLocked("server", "HTTP server started on port $port")
    }

    fun updateLanIps(lanIps: List<String>) = synchronized(lock) {
        val cur = _state.value
        if (!cur.isRunning) return
        val urls = if (lanIps.isNotEmpty()) {
            lanIps.map { "http://$it:${cur.boundPort}" }
        } else {
            listOf("http://127.0.0.1:${cur.boundPort}")
        }
        _state.value = cur.copy(urls = urls, hasLanInterface = lanIps.isNotEmpty())
    }

    fun updateActiveClients(count: Int) = synchronized(lock) {
        _state.value = _state.value.copy(activeClients = count)
    }

    fun markStopped(errorMessage: String? = null) = synchronized(lock) {
        if (_state.value.isRunning) {
            appendLogLocked("server", errorMessage ?: "HTTP server stopped")
        }
        sessionLogRowIds.clear()
        sessionPeerNames.clear()
        _state.value = _state.value.copy(
            isRunning = false,
            isStarting = false,
            urls = emptyList(),
            activeClients = 0,
            lastError = errorMessage,
        )
    }

    fun logInfo(source: String, message: String) = synchronized(lock) {
        appendLogLocked(source, message)
    }

    fun onEvent(event: HttpEvent) = synchronized(lock) {
        if (event is HttpEvent.PeerAnnounce || event is HttpEvent.PeerSessionStart || event is HttpEvent.PeerSessionEnd) {
            _peerEvents.tryEmit(event)
        }
        when (event) {
            is HttpEvent.Request -> {
                val isPeerApi = event.target.startsWith("/api/peer/")
                if (!isPeerApi || event.status !in 200..299) {
                    appendLogLocked(event.clientIp, "${event.method} ${event.target} → ${event.status}")
                }
            }

            is HttpEvent.Transfer -> {
                val cur = _state.value
                _state.value = if (event.direction == HttpTransferDirection.UPLOAD) {
                    cur.copy(uploadsCount = cur.uploadsCount + 1)
                } else {
                    cur.copy(downloadsCount = cur.downloadsCount + 1)
                }
                // SHOULD-7 / #80 note 3: sessioned peer uploads aggregate into one row per session;
                // session-less sends keep today's per-file UPLOAD ↑ line.
                if (event.peerSessionId == null) {
                    val arrow = if (event.direction == HttpTransferDirection.UPLOAD) "UPLOAD ↑" else "DOWNLOAD ↓"
                    appendLogLocked(
                        event.clientIp,
                        "$arrow ${event.path} (${formatBytesShort(event.bytes)} in ${event.durationMs} ms)",
                    )
                }
            }

            is HttpEvent.PeerSessionStart -> {
                sessionPeerNames[event.sessionId] = event.deviceName
                val countLabel = if (event.totalFiles == 1) "1 file" else "${event.totalFiles} files"
                val summary = "⇐ ${event.deviceName}  0/${event.totalFiles} ($countLabel · ${formatBytesShort(event.totalBytes)})"
                val rowId = appendLogLocked(event.clientIp, summary)
                sessionLogRowIds[event.sessionId] = rowId
            }

            is HttpEvent.PeerProgress -> {
                val peerName = sessionPeerNames[event.sessionId] ?: "Peer"
                val totalPart = if (event.totalFiles > 0) "${event.processedFiles}/${event.totalFiles} files" else "${event.processedFiles} files"
                val itemTail = event.currentItem.substringAfterLast('/').takeIf { it.isNotEmpty() }
                val summary = buildString {
                    append("⇐ ").append(peerName).append("  ")
                    append(totalPart).append(" · ").append(formatBytesShort(event.processedBytes))
                    if (itemTail != null) {
                        append(" · ").append(itemTail)
                    }
                }
                val rowId = sessionLogRowIds[event.sessionId]
                if (rowId != null) {
                    updateOrAppendLogLocked(rowId, "peer", summary)
                } else {
                    sessionLogRowIds[event.sessionId] = appendLogLocked("peer", summary)
                }
            }

            is HttpEvent.PeerSessionEnd -> {
                val peerName = sessionPeerNames.remove(event.sessionId) ?: event.deviceName
                val rowId = sessionLogRowIds.remove(event.sessionId)
                val secStr = String.format(Locale.US, "%.1f s", (event.durationMs.coerceAtLeast(1L)) / 1000.0)
                val countLabel = if (event.files == 1) "1 file" else "${event.files} files"
                val summary = when {
                    event.ok -> "⇐ $peerName  $countLabel · ${formatBytesShort(event.bytes)} · $secStr"
                    event.cancelled -> "⇐ $peerName  cancelled ($countLabel · ${formatBytesShort(event.bytes)})"
                    else -> "⇐ $peerName  failed${event.error?.let { ": $it" }.orEmpty()} ($countLabel · ${formatBytesShort(event.bytes)})"
                }
                if (rowId != null) {
                    updateOrAppendLogLocked(rowId, event.clientIp, summary)
                } else {
                    appendLogLocked(event.clientIp, summary)
                }
            }

            is HttpEvent.PeerAnnounce -> {
                appendLogLocked(event.clientIp, "Hotspot peer joined: ${event.name} (${event.os ?: "?"}, port ${event.port})")
            }

            is HttpEvent.Error -> {
                appendLogLocked("error", event.message)
            }

            is HttpEvent.Started -> Unit
            HttpEvent.Stopped -> Unit
        }
    }

    fun clearLog() = synchronized(lock) {
        sessionLogRowIds.clear()
        sessionPeerNames.clear()
        _state.value = _state.value.copy(log = emptyList())
    }

    private fun appendLogLocked(clientIp: String, summary: String): Long {
        val now = System.currentTimeMillis()
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(now))
        val id = nextId++
        val entry = HttpLogEntry(
            id = id,
            timestampMs = now,
            formattedTime = timeFmt,
            clientIp = clientIp,
            summary = summary,
        )
        val cur = _state.value.log
        val updated = if (cur.size >= MAX_LOG_ENTRIES) {
            cur.drop(cur.size - MAX_LOG_ENTRIES + 1) + entry
        } else {
            cur + entry
        }
        _state.value = _state.value.copy(log = updated)
        return id
    }

    private fun updateOrAppendLogLocked(rowId: Long, fallbackClientIp: String, summary: String) {
        val cur = _state.value.log
        val idx = cur.indexOfFirst { it.id == rowId }
        if (idx < 0) {
            appendLogLocked(fallbackClientIp, summary)
            return
        }
        val now = System.currentTimeMillis()
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(now))
        val mutable = cur.toMutableList()
        mutable[idx] = mutable[idx].copy(
            timestampMs = now,
            formattedTime = timeFmt,
            summary = summary,
        )
        _state.value = _state.value.copy(log = mutable)
    }

    private fun formatBytesShort(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }
}
