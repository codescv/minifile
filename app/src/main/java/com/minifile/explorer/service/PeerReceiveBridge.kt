package com.minifile.explorer.service

import android.content.Context
import com.minifile.explorer.core.http.HttpEvent
import com.minifile.explorer.core.http.HttpFileServerEngine
import com.minifile.explorer.core.i18n.AppI18n
import com.minifile.explorer.core.i18n.Str
import com.minifile.explorer.core.model.OperationKind
import com.minifile.explorer.core.model.OperationProgress
import com.minifile.explorer.core.ops.ProgressSink
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsPath
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * Bridges incoming [HttpFileServerEngine] peer receive sessions (`PeerSessionStart`, `PeerProgress`,
 * `PeerSessionEnd`) into [OperationRegistry] (`OperationKind.RECEIVE`) so active incoming transfers
 * appear in the Active Operations pill/sheet and notification with live speed and Cancel (`cancelSession`).
 */
class PeerReceiveBridge(
    private val context: Context,
    private val operationRegistry: OperationRegistry,
    private val fs: FileSystem,
) {
    private class ActiveReceiveOp(
        val sessionId: String,
        val deviceName: String,
        val totalFiles: Int,
        val totalBytes: Long,
        val doneDeferred: CompletableDeferred<HttpEvent.PeerSessionEnd> = CompletableDeferred(),
        @Volatile var sink: ProgressSink? = null,
    )

    private val sessions = ConcurrentHashMap<String, ActiveReceiveOp>()

    fun onHttpEvent(engine: HttpFileServerEngine, event: HttpEvent) {
        when (event) {
            is HttpEvent.PeerSessionStart -> {
                val rec = ActiveReceiveOp(
                    sessionId = event.sessionId,
                    deviceName = event.deviceName,
                    totalFiles = event.totalFiles,
                    totalBytes = event.totalBytes,
                )
                sessions[event.sessionId] = rec
                val title = AppI18n.current().format(Str.RECEIVING_FROM, event.deviceName)

                operationRegistry.enqueue(
                    context = context,
                    kind = OperationKind.RECEIVE,
                    title = title,
                    retryAction = null,
                    onCancel = {
                        engine.cancelSession(event.sessionId)
                    },
                ) { opId, sink ->
                    rec.sink = sink
                    sink.onProgress(
                        OperationProgress(
                            id = opId,
                            kind = OperationKind.RECEIVE,
                            title = title,
                            currentItem = "",
                            processedItems = 0,
                            totalItems = event.totalFiles,
                            processedBytes = 0L,
                            totalBytes = event.totalBytes,
                        ),
                    )

                    val end = rec.doneDeferred.await()
                    val s = AppI18n.current()
                    val countLabel = s.filesCount(end.files)
                    val sizeLabel = PathUtils.formatBytes(end.bytes)
                    when {
                        end.ok -> {
                            val inboxDir = resolveInboxPathOrNull(engine)
                            OperationRunOutcome(
                                message = s.format(Str.RECEIVED_FROM_FMT, countLabel, sizeLabel, end.deviceName),
                                destinationToOpen = inboxDir,
                                openLabel = s[Str.OPEN],
                            )
                        }

                        sink.isCancelled() -> {
                            OperationRunOutcome(
                                message = s.format(Str.REJECTED_TRANSFER_FROM, end.deviceName),
                            )
                        }

                        end.cancelled -> {
                            OperationRunOutcome(
                                message = s.format(Str.PEER_CANCELLED_TRANSFER, end.deviceName),
                                errorSummary = s[Str.CANCELLED_BY_SENDER],
                            )
                        }

                        else -> {
                            val err = end.error ?: s[Str.TRANSFER_FAILED]
                            OperationRunOutcome(
                                message = s.format(Str.RECEIVE_FAILED_FROM, end.deviceName, err),
                                errorSummary = err,
                            )
                        }
                    }
                }
            }

            is HttpEvent.PeerProgress -> {
                val rec = sessions[event.sessionId] ?: return
                val sink = rec.sink ?: return
                sink.onProgress(
                    OperationProgress(
                        id = "",
                        kind = OperationKind.RECEIVE,
                        title = AppI18n.current().format(Str.RECEIVING_FROM, rec.deviceName),
                        currentItem = event.currentItem,
                        processedItems = event.processedFiles,
                        totalItems = event.totalFiles,
                        processedBytes = event.processedBytes,
                        totalBytes = event.totalBytes,
                    ),
                )
            }

            is HttpEvent.PeerSessionEnd -> {
                val rec = sessions.remove(event.sessionId) ?: return
                rec.doneDeferred.complete(event)
            }

            else -> Unit
        }
    }

    fun cancelAndClearAll() {
        val snapshot = sessions.values.toList()
        sessions.clear()
        val stoppedMsg = AppI18n.current()[Str.SERVER_STOPPED]
        for (rec in snapshot) {
            rec.doneDeferred.complete(
                HttpEvent.PeerSessionEnd(
                    sessionId = rec.sessionId,
                    clientIp = "",
                    deviceName = rec.deviceName,
                    files = 0,
                    bytes = 0L,
                    durationMs = 1L,
                    ok = false,
                    cancelled = true,
                    error = stoppedMsg,
                ),
            )
        }
    }

    private suspend fun resolveInboxPathOrNull(engine: HttpFileServerEngine): VfsPath? {
        val inboxName = engine.config.peerInboxDir.trim('/')
        if (inboxName.isEmpty()) return engine.config.root
        return runCatching {
            var cur = engine.config.root
            for (seg in inboxName.split('/').filter { it.isNotEmpty() }) {
                cur = fs.child(cur, seg)
            }
            if (fs.exists(cur)) cur else engine.config.root
        }.getOrNull()
    }
}
