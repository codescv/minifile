package com.minifile.explorer.core.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Live size summary for the Settings ▸ Diagnostics row. */
data class DiagnosticLogStats(
    val totalBytes: Long = 0L,
    val currentBytes: Long = 0L,
    val rolledBytes: Long = 0L,
    val droppedLines: Long = 0L,
)

/**
 * Bounded, rolling, append-only on-disk diagnostic log (pure JVM; Android supplies the directory).
 *
 * - Line format (shared with iOS): `2026-11-26T09:05:12.345+08:00 [BLE] message` — ISO-8601 local time with
 *   millisecond precision and an explicit numeric offset (never `Z`) so Android and iPhone timelines can be
 *   merged and correlated to the millisecond.
 * - Files: `<dir>/diagnostic.log` + one rollover `diagnostic.log.1`; each capped at [maxBytesPerFile]
 *   (default 512 KiB ⇒ ≤ 1 MiB on disk).
 * - Every message is passed through [DiagnosticLogRedactor] before it is queued.
 * - Writes are serialised on a single daemon thread (FIFO, timestamps captured at the call site), so callers
 *   — including the UI thread — never block on disk. Each line is written straight to the file descriptor
 *   (no user-space buffer) so it survives process death right after the call returns to the writer.
 * - When disabled, [log] returns after a single volatile read; use [Diag.log] with a lambda at call sites so
 *   the message string is never even built.
 */
class DiagnosticLogger(
    private val dir: File,
    private val maxBytesPerFile: Long = DEFAULT_MAX_BYTES_PER_FILE,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    maxPendingLines: Int = DEFAULT_MAX_PENDING_LINES,
) {
    @Volatile
    var enabled: Boolean = false
        private set

    private val currentFile = File(dir, FILE_NAME)
    private val rolledFile = File(dir, ROLLED_FILE_NAME)

    private val dropped = AtomicLong(0L)
    private val _stats = MutableStateFlow(DiagnosticLogStats())
    val stats: StateFlow<DiagnosticLogStats> = _stats.asStateFlow()

    // Writer state, guarded by [ioLock] (held by the writer thread per line and by clear / readAll callers).
    internal val ioLock = Any()
    private var stream: FileOutputStream? = null
    private var currentBytes = 0L
    private var rolledBytes = 0L
    private var droppedReported = 0L

    private val executor: ThreadPoolExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(maxPendingLines.coerceAtLeast(16)),
        { r -> Thread(r, "minifile-diag-log").apply { isDaemon = true } },
        // Queue full: drop the line (counted, reported later). A rejected control task (flush / read) is a Future:
        // cancel it so its caller's get() returns at once instead of waiting for a task that will never run.
        RejectedExecutionHandler { r, _ ->
            if (r is Future<*>) r.cancel(false) else dropped.incrementAndGet()
        },
    )

    init {
        submit {
            synchronized(ioLock) {
                currentBytes = currentFile.lengthOrZero()
                rolledBytes = rolledFile.lengthOrZero()
                publishStats()
            }
        }
    }

    /**
     * Turns recording on/off. On an off→on transition [sessionBanner] (if given) is evaluated and recorded as a
     * `[SESSION]` line so every recording window starts with the app/device/network context.
     */
    @Synchronized
    fun setEnabled(on: Boolean, sessionBanner: (() -> String)? = null) {
        val was = enabled
        if (on == was) return
        if (on) {
            enabled = true
            sessionBanner?.let { banner -> log(Diag.SESSION, runCatching(banner).getOrElse { "banner failed: $it" }) }
        } else {
            log(Diag.SESSION, "diagnostic logging disabled")
            enabled = false
        }
    }

    /** Records one line when enabled; no-op (single volatile read) when disabled. */
    fun log(tag: String, message: String) {
        if (!enabled) return
        val line = formatLine(clock(), zone(), tag, message)
        submit { synchronized(ioLock) { appendLine(line) } }
    }

    /** Blocks until every line queued before this call has been written (tests / before export). */
    fun flush(timeoutMs: Long = 5_000L) {
        val f = runCatching { executor.submit { } }.getOrNull() ?: return
        val done = runCatching { f.get(timeoutMs, TimeUnit.MILLISECONDS) }.isSuccess
        if (!done) {
            // Rejected (queue full): wait for the backlog to drain instead, then for the in-flight write.
            val deadline = System.currentTimeMillis() + timeoutMs
            while (executor.queue.isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(2)
            synchronized(ioLock) { }
        }
    }

    /**
     * Deletes both log files and resets the size summary. Lines still queued are discarded too. Runs on the caller
     * thread (never queued), so it works even when the writer queue is full.
     */
    fun clear() {
        executor.queue.clear()
        synchronized(ioLock) {
            closeStream()
            currentFile.delete()
            rolledFile.delete()
            currentBytes = 0L
            rolledBytes = 0L
            dropped.set(0L)
            droppedReported = 0L
            publishStats()
        }
    }

    /** Chronological log body: `diagnostic.log.1` followed by `diagnostic.log` (re-redacted defensively). */
    fun readAll(): String {
        flush()
        val raw = synchronized(ioLock) {
            val sb = StringBuilder()
            for (f in listOf(rolledFile, currentFile)) {
                if (f.isFile) runCatching { sb.append(f.readText(Charsets.UTF_8)) }
            }
            sb.toString()
        }
        return raw.lineSequence().joinToString("\n") { DiagnosticLogRedactor.redact(it) }
    }

    val totalBytes: Long get() = _stats.value.totalBytes

    /** Stops the writer thread (tests). Pending lines are written first. */
    fun shutdown() {
        flush()
        executor.shutdown()
        runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
        closeStream()
    }

    private fun submit(block: () -> Unit) {
        runCatching { executor.execute(block) }
    }

    private fun appendLine(line: String) {
        val pendingDrops = dropped.get() - droppedReported
        if (pendingDrops > 0) {
            droppedReported += pendingDrops
            writeBytes(formatLine(clock(), zone(), "DIAG", "dropped $pendingDrops line(s): writer queue full"))
        }
        writeBytes(line)
        publishStats()
    }

    private fun writeBytes(line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        try {
            if (currentBytes > 0 && currentBytes + bytes.size > maxBytesPerFile) rotate()
            val out = stream ?: openStream()
            out.write(bytes)
            currentBytes += bytes.size
        } catch (e: IOException) {
            closeStream()
        } catch (e: SecurityException) {
            closeStream()
        }
    }

    private fun rotate() {
        closeStream()
        rolledFile.delete()
        if (!currentFile.renameTo(rolledFile)) {
            currentFile.delete()
        }
        rolledBytes = rolledFile.lengthOrZero()
        currentBytes = 0L
    }

    private fun openStream(): FileOutputStream {
        if (!dir.isDirectory) dir.mkdirs()
        val s = FileOutputStream(currentFile, true)
        stream = s
        currentBytes = currentFile.lengthOrZero()
        return s
    }

    private fun closeStream() {
        runCatching { stream?.close() }
        stream = null
    }

    private fun publishStats() {
        _stats.value = DiagnosticLogStats(
            totalBytes = currentBytes + rolledBytes,
            currentBytes = currentBytes,
            rolledBytes = rolledBytes,
            droppedLines = dropped.get(),
        )
    }

    private fun File.lengthOrZero(): Long = if (isFile) length() else 0L

    companion object {
        const val FILE_NAME = "diagnostic.log"
        const val ROLLED_FILE_NAME = "diagnostic.log.1"
        const val DEFAULT_MAX_BYTES_PER_FILE = 512L * 1024L
        const val DEFAULT_MAX_PENDING_LINES = 4096
        const val MAX_MESSAGE_CHARS = 4000

        private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")

        /** `2026-11-26T09:05:12.345+08:00` (always a numeric offset; UTC renders as `+00:00`). */
        fun formatTimestamp(epochMs: Long, zone: ZoneId): String =
            TIMESTAMP.format(Instant.ofEpochMilli(epochMs).atZone(zone))

        /** One single-line, redacted entry: `<timestamp> [<TAG>] <message>`. */
        fun formatLine(epochMs: Long, zone: ZoneId, tag: String, message: String): String {
            val cleanTag = tag.trim().uppercase().filter { it.isLetterOrDigit() || it == '_' || it == '-' }
                .ifEmpty { "LOG" }
            // Redact BEFORE truncating: a cut through a quoted secret would otherwise defeat the JSON rules.
            var msg = DiagnosticLogRedactor.redact(
                message
                    .replace("\r\n", "\\n")
                    .replace("\n", "\\n")
                    .replace("\r", "\\n"),
            )
            if (msg.length > MAX_MESSAGE_CHARS) {
                var cut = MAX_MESSAGE_CHARS
                if (Character.isHighSurrogate(msg[cut - 1])) cut--
                msg = msg.take(cut) + "${DiagnosticLogRedactor.ELLIPSIS}(+${msg.length - cut} chars)"
            }
            return "${formatTimestamp(epochMs, zone)} [$cleanTag] $msg"
        }
    }
}

/**
 * Process-wide diagnostic log entry point used by instrumentation in both `core` and the Android layers.
 * [install] is called once by the Android `AppContainer`; until then (and in unit tests) every call is a no-op.
 *
 * ```
 * Diag.log(Diag.BLE) { "gatt connected addr=$addr mtu=$mtu" }   // lambda never runs while logging is off
 * ```
 */
object Diag {
    const val APP = "APP"
    const val BLE = "BLE"
    const val HOTSPOT = "HOTSPOT"
    const val NET = "NET"
    const val HTTP = "HTTP"
    const val PEER = "PEER"
    const val SESSION = "SESSION"
    const val ACTIVITY = "ACTIVITY"

    @Volatile
    @PublishedApi
    internal var logger: DiagnosticLogger? = null

    fun install(l: DiagnosticLogger?) {
        logger = l
    }

    val isEnabled: Boolean get() = logger?.enabled == true

    inline fun log(tag: String, message: () -> String) {
        val l = logger ?: return
        if (!l.enabled) return
        l.log(tag, runCatching(message).getOrElse { "<message failed: ${it.javaClass.simpleName}>" })
    }
}
