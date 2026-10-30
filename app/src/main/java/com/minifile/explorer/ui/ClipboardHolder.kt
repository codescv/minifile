package com.minifile.explorer.ui

import com.minifile.explorer.core.model.ClipboardState
import com.minifile.explorer.core.model.TransferMode
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process clipboard state (`DESIGN.md` §2.1).
 * Persists across directory & tab changes, cleared automatically on process death.
 */
object ClipboardHolder {
    private val _state = MutableStateFlow<ClipboardState?>(null)
    val state: StateFlow<ClipboardState?> = _state.asStateFlow()

    fun set(items: List<VfsPath>, mode: TransferMode) {
        if (items.isEmpty()) {
            _state.value = null
        } else {
            _state.value = ClipboardState(items = items.distinct(), mode = mode)
        }
    }

    fun clear() {
        _state.value = null
    }
}
