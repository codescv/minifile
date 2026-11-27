package com.minifile.explorer.data.gallery

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Watches Android MediaStore images & videos URIs and emits debounced (400 ms) change ticks.
 */
class MediaStoreObserver(
    context: Context,
    private val debounceMs: Long = 400L,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    private var registered = false
    private var debounceJob: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            scheduleEmit()
        }
    }

    private fun scheduleEmit() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            _changes.tryEmit(Unit)
        }
    }

    fun start() {
        if (registered) return
        registered = true
        runCatching {
            appContext.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer,
            )
            appContext.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                observer,
            )
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        debounceJob?.cancel()
        runCatching {
            appContext.contentResolver.unregisterContentObserver(observer)
        }
    }
}
