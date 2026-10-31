package com.minifile.explorer.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.minifile.explorer.core.model.OperationKind
import com.minifile.explorer.core.model.OperationProgress
import com.minifile.explorer.core.ops.ProgressSink
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class OperationCompletionEvent(
    val id: String,
    val kind: OperationKind,
    val title: String,
    val snackbarMessage: String,
    val destinationToOpen: VfsPath? = null,
    val openLabel: String = "Open",
    val cancelled: Boolean = false,
    val error: String? = null,
)

data class OperationRunOutcome(
    val message: String,
    val destinationToOpen: VfsPath? = null,
    val openLabel: String = "Open",
    val unsafeSkipped: Int = 0,
    val errorSummary: String? = null,
)

class OperationRegistry {
    companion object {
        const val MAX_HISTORY = 20
        val instance: OperationRegistry by lazy { OperationRegistry() }
    }

    private data class RunningEntry(
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val onCancel: (() -> Unit)? = null,
        var job: Job? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<String, RunningEntry>()
    private val retryHandlers = ConcurrentHashMap<String, () -> Unit>()

    private val _active = MutableStateFlow<List<OperationProgress>>(emptyList())
    val active: StateFlow<List<OperationProgress>> = _active.asStateFlow()

    private val _history = MutableStateFlow<List<OperationProgress>>(emptyList())
    val history: StateFlow<List<OperationProgress>> = _history.asStateFlow()

    private val _completions = MutableSharedFlow<OperationCompletionEvent>(extraBufferCapacity = 16)
    val completions: SharedFlow<OperationCompletionEvent> = _completions.asSharedFlow()

    fun enqueue(
        context: Context,
        kind: OperationKind,
        title: String,
        retryAction: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
        block: suspend (id: String, sink: ProgressSink) -> OperationRunOutcome,
    ): String {
        val opId = UUID.randomUUID().toString()
        val entry = RunningEntry(onCancel = onCancel)
        running[opId] = entry
        if (retryAction != null) {
            retryHandlers[opId] = retryAction
        }

        val initial = OperationProgress(
            id = opId,
            kind = kind,
            title = title,
        )
        upsertActive(initial)

        runCatching {
            val svcIntent = Intent(context.applicationContext, FileOperationService::class.java)
            ContextCompat.startForegroundService(context.applicationContext, svcIntent)
        }

        val startedMs = System.currentTimeMillis()
        val sink = object : ProgressSink {
            override fun onProgress(progress: OperationProgress) {
                val elapsedSec = ((System.currentTimeMillis() - startedMs) / 1000.0).coerceAtLeast(0.05)
                val speed = if (progress.processedBytes > 0L) (progress.processedBytes / elapsedSec).toLong() else 0L
                upsertActive(
                    progress.copy(
                        id = opId,
                        kind = kind,
                        title = title,
                        bytesPerSecond = speed,
                    ),
                )
            }

            override fun isCancelled(): Boolean = entry.cancelled.get()
        }

        entry.job = scope.launch {
            try {
                val outcome = block(opId, sink)
                val cancelled = entry.cancelled.get()
                val latest = _active.value.firstOrNull { it.id == opId } ?: initial
                val finishedProgress = latest.copy(
                    finished = true,
                    cancelled = cancelled,
                    errorSummary = outcome.errorSummary,
                    unsafeSkipped = outcome.unsafeSkipped,
                )
                removeActiveAndRecordHistory(finishedProgress)
                _completions.tryEmit(
                    OperationCompletionEvent(
                        id = opId,
                        kind = kind,
                        title = title,
                        snackbarMessage = outcome.message,
                        destinationToOpen = outcome.destinationToOpen,
                        openLabel = outcome.openLabel,
                        cancelled = cancelled,
                        error = outcome.errorSummary,
                    ),
                )
            } catch (_: CancellationException) {
                val latest = _active.value.firstOrNull { it.id == opId } ?: initial
                removeActiveAndRecordHistory(latest.copy(finished = true, cancelled = true))
                _completions.tryEmit(
                    OperationCompletionEvent(
                        id = opId,
                        kind = kind,
                        title = title,
                        snackbarMessage = "$title cancelled",
                        cancelled = true,
                    ),
                )
            } catch (e: Exception) {
                val cancelled = entry.cancelled.get()
                val latest = _active.value.firstOrNull { it.id == opId } ?: initial
                val errMsg = e.message ?: "Operation failed"
                removeActiveAndRecordHistory(
                    latest.copy(
                        finished = true,
                        cancelled = cancelled,
                        errorSummary = if (cancelled) null else errMsg,
                    ),
                )
                _completions.tryEmit(
                    OperationCompletionEvent(
                        id = opId,
                        kind = kind,
                        title = title,
                        snackbarMessage = if (cancelled) "$title cancelled" else "$title failed: $errMsg",
                        cancelled = cancelled,
                        error = if (cancelled) null else errMsg,
                    ),
                )
            } finally {
                running.remove(opId)
            }
        }
        return opId
    }

    fun cancel(opId: String) {
        running[opId]?.let { entry ->
            if (entry.cancelled.compareAndSet(false, true)) {
                runCatching { entry.onCancel?.invoke() }
            }
        }
    }

    fun cancelAll() {
        for ((_, entry) in running) {
            if (entry.cancelled.compareAndSet(false, true)) {
                runCatching { entry.onCancel?.invoke() }
            }
        }
    }

    fun canRetry(opId: String): Boolean = retryHandlers.containsKey(opId)

    fun retry(opId: String) {
        retryHandlers[opId]?.invoke()
    }

    fun clearHistory() {
        _history.value = emptyList()
        retryHandlers.clear()
    }

    private fun upsertActive(p: OperationProgress) {
        synchronized(this) {
            val current = _active.value.toMutableList()
            val idx = current.indexOfFirst { it.id == p.id }
            if (idx >= 0) {
                current[idx] = p
            } else {
                current.add(p)
            }
            _active.value = current
        }
    }

    private fun removeActiveAndRecordHistory(p: OperationProgress) {
        synchronized(this) {
            _active.value = _active.value.filterNot { it.id == p.id }
            val nextHist = (listOf(p) + _history.value).take(MAX_HISTORY)
            _history.value = nextHist
        }
    }
}
