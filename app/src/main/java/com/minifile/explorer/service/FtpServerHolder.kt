package com.minifile.explorer.service

import com.minifile.explorer.core.ftp.FtpEvent
import com.minifile.explorer.core.ftp.TransferDirection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FtpLogEntry(
    val id: Long,
    val timestampMs: Long,
    val formattedTime: String,
    val clientIp: String,
    val summary: String,
)

data class FtpServerState(
    val isRunning: Boolean = false,
    val isStarting: Boolean = false,
    val boundPort: Int = 2121,
    val urls: List<String> = emptyList(),
    val hasLanInterface: Boolean = false,
    val activeClients: Int = 0,
    val uploadsCount: Int = 0,
    val downloadsCount: Int = 0,
    val lastError: String? = null,
    val log: List<FtpLogEntry> = emptyList(),
)

object FtpServerHolder {
    const val MAX_LOG_ENTRIES = 500

    private val lock = Any()
    private var nextId = 1L
    private val _state = MutableStateFlow(FtpServerState())
    val state: StateFlow<FtpServerState> = _state.asStateFlow()

    fun markStarting() = synchronized(lock) {
        _state.value = _state.value.copy(isStarting = true, lastError = null)
    }

    fun markStarted(port: Int, lanIps: List<String>) = synchronized(lock) {
        val urls = if (lanIps.isNotEmpty()) {
            lanIps.map { "ftp://$it:$port" }
        } else {
            listOf("ftp://127.0.0.1:$port")
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
        appendLogLocked("server", "FTP server started on port $port")
    }

    fun updateLanIps(lanIps: List<String>) = synchronized(lock) {
        val cur = _state.value
        if (!cur.isRunning) return
        val urls = if (lanIps.isNotEmpty()) {
            lanIps.map { "ftp://$it:${cur.boundPort}" }
        } else {
            listOf("ftp://127.0.0.1:${cur.boundPort}")
        }
        _state.value = cur.copy(urls = urls, hasLanInterface = lanIps.isNotEmpty())
    }

    fun updateActiveClients(count: Int) = synchronized(lock) {
        _state.value = _state.value.copy(activeClients = count)
    }

    fun markStopped(errorMessage: String? = null) = synchronized(lock) {
        if (_state.value.isRunning) {
            appendLogLocked("server", errorMessage ?: "FTP server stopped")
        }
        _state.value = _state.value.copy(
            isRunning = false,
            isStarting = false,
            urls = emptyList(),
            activeClients = 0,
            lastError = errorMessage,
        )
    }

    fun onEvent(event: FtpEvent) = synchronized(lock) {
        when (event) {
            is FtpEvent.ClientConnected -> {
                appendLogLocked(event.clientIp, "connected")
            }

            is FtpEvent.ClientDisconnected -> {
                appendLogLocked(event.clientIp, "disconnected")
            }

            is FtpEvent.Command -> {
                val safeArg = if (event.verb.equals("PASS", ignoreCase = true)) {
                    "****"
                } else {
                    event.arg
                }
                val line = if (safeArg.isBlank()) event.verb else "${event.verb} $safeArg"
                appendLogLocked(event.clientIp, line)
            }

            is FtpEvent.Transfer -> {
                val cur = _state.value
                val arrow = if (event.direction == TransferDirection.UPLOAD) "STOR ↑" else "RETR ↓"
                _state.value = if (event.direction == TransferDirection.UPLOAD) {
                    cur.copy(uploadsCount = cur.uploadsCount + 1)
                } else {
                    cur.copy(downloadsCount = cur.downloadsCount + 1)
                }
                appendLogLocked(
                    event.clientIp,
                    "$arrow ${event.path} (${formatBytesShort(event.bytes)} in ${event.durationMs} ms)",
                )
            }

            is FtpEvent.Error -> {
                appendLogLocked("error", event.message)
            }

            is FtpEvent.Started -> Unit
            FtpEvent.Stopped -> Unit
        }
    }

    fun clearLog() = synchronized(lock) {
        _state.value = _state.value.copy(log = emptyList())
    }

    fun exportLogText(): String = synchronized(lock) {
        val sb = StringBuilder()
        for (entry in _state.value.log) {
            sb.append(entry.formattedTime)
                .append("  [")
                .append(entry.clientIp)
                .append("]  ")
                .append(entry.summary)
                .append('\n')
        }
        sb.toString()
    }

    private fun appendLogLocked(clientIp: String, summary: String) {
        val now = System.currentTimeMillis()
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(now))
        val entry = FtpLogEntry(
            id = nextId++,
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
