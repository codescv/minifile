package com.minifile.explorer.core.update

import java.io.IOException

/** Cooperative cancellation token for blocking HTTP/update calls. */
class CancelToken {
    @Volatile
    var isCancelled: Boolean = false
        private set

    private var abort: (() -> Unit)? = null

    fun cancel() {
        val action: (() -> Unit)?
        synchronized(this) {
            isCancelled = true
            action = abort
            abort = null
        }
        runCatching { action?.invoke() }
    }

    fun attach(onAbort: () -> Unit) {
        synchronized(this) {
            if (!isCancelled) {
                abort = onAbort
                return
            }
        }
        runCatching { onAbort() }
        throw IOException("cancelled")
    }

    fun detach() {
        synchronized(this) {
            abort = null
        }
    }
}

enum class UpdateErrorCode {
    NETWORK,
    TIMEOUT,
    NOT_FOUND,
    RATE_LIMITED,
    BAD_JSON,
    NO_ASSET,
    CORRUPT_DOWNLOAD,
    VERIFY_FAILED,
    CANCELLED,
    INSECURE_URL,
}

class UpdateException(
    val code: UpdateErrorCode,
    message: String,
    cause: Throwable? = null,
    val httpStatus: Int = 0,
) : IOException(message, cause)
