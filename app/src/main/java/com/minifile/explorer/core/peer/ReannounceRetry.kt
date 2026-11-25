package com.minifile.explorer.core.peer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * BLE `lohs-s` send recovery (pure JVM, `zero android.* imports`).
 *
 * After an iPhone announced itself on this phone's hotspot it may be suspended by iOS (the user is still in the Camera
 * app) and come back on a restarted HTTP listener — possibly on a new port — and re-announce (same token, same id,
 * see `HotspotSessionGate.consumeAnnounceToken`). Instead of tearing the link down on the first refused / reset /
 * silent `begin`, this helper:
 * - aborts an attempt still waiting for `begin` as soon as a re-announce arrives for a **different** endpoint (or for
 *   the same endpoint once [SAME_ENDPOINT_ABORT_AFTER_MS] passed — a pre-approved `begin` answers instantly, so the
 *   old connection is stuck in a dead listener's backlog), then retries against the announced endpoint;
 * - after a transport failure before any session existed, waits up to [waitMs] for a re-announce and retries.
 * Once a session started (file bytes may have been sent) failures are terminal, so files are never duplicated.
 */
object ReannounceRetry {
    const val DEFAULT_MAX_RETRIES = 3
    const val DEFAULT_WAIT_MS = 45_000L
    const val SAME_ENDPOINT_ABORT_AFTER_MS = 3_000L

    /** `true` for a failure before any session where the receiver may come back on a re-announced endpoint. */
    fun isRetryable(error: Throwable, sessionStarted: Boolean): Boolean {
        if (sessionStarted) return false
        return when (error) {
            is PeerTransferException -> error.httpStatus == null && !error.isInterceptedRedirect
            is IOException -> true
            else -> false
        }
    }

    /** Keeps everything known about [current] (token, platform, name) but routes to [announced]'s endpoint. */
    fun retarget(current: PeerInfo, announced: PeerInfo): PeerInfo =
        current.copy(host = announced.host, port = announced.port, lastSeenMs = announced.lastSeenMs)

    fun sameEndpoint(a: PeerInfo, b: PeerInfo): Boolean = a.host == b.host && a.port == b.port

    suspend fun <T> run(
        initial: PeerInfo,
        reannounces: Flow<PeerInfo>?,
        abortActive: () -> Unit,
        isCancelled: () -> Boolean,
        onWaiting: (PeerInfo) -> Unit = {},
        onRetry: (PeerInfo) -> Unit = {},
        maxRetries: Int = DEFAULT_MAX_RETRIES,
        waitMs: Long = DEFAULT_WAIT_MS,
        nowMs: () -> Long = System::currentTimeMillis,
        attempt: suspend (peer: PeerInfo, onSessionStarted: () -> Unit) -> T,
    ): T {
        if (reannounces == null) return attempt(initial) {}
        return coroutineScope {
            val latest = Channel<PeerInfo>(Channel.CONFLATED)
            val current = AtomicReference(initial)
            val sessionStarted = AtomicBoolean(false)
            val attemptRunning = AtomicBoolean(false)
            val attemptStartedAt = AtomicLong(0L)
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                reannounces.collect { announced ->
                    latest.trySend(announced)
                    if (attemptRunning.get() && !sessionStarted.get()) {
                        val changed = !sameEndpoint(announced, current.get())
                        if (changed || nowMs() - attemptStartedAt.get() >= SAME_ENDPOINT_ABORT_AFTER_MS) {
                            runCatching { abortActive() }
                        }
                    }
                }
            }
            try {
                var target = initial
                var retries = 0
                while (true) {
                    current.set(target)
                    sessionStarted.set(false)
                    attemptStartedAt.set(nowMs())
                    attemptRunning.set(true)
                    try {
                        return@coroutineScope attempt(target) { sessionStarted.set(true) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        attemptRunning.set(false)
                        if (isCancelled() || retries >= maxRetries || !isRetryable(e, sessionStarted.get())) throw e
                        onWaiting(target)
                        val next = withTimeoutOrNull(waitMs) { latest.receive() } ?: throw e
                        if (isCancelled()) throw e
                        retries++
                        target = retarget(target, next)
                        onRetry(target)
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            } finally {
                watcher.cancel()
            }
        }
    }
}
