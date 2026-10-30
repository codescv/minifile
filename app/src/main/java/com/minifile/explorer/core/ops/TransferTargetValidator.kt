package com.minifile.explorer.core.ops

import com.minifile.explorer.core.model.TransferMode
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsPath

sealed interface TargetValidation {
    data object Allowed : TargetValidation
    data object SameDirectory : TargetValidation
    data object InsideSource : TargetValidation
    data object ReadOnlyTarget : TargetValidation
    data class TargetUnavailable(val reason: String) : TargetValidation

    val isAllowed: Boolean
        get() = this is Allowed

    fun disabledReasonLabel(): String? = when (this) {
        Allowed -> null
        SameDirectory -> "Same folder"
        InsideSource -> "Can't transfer a folder into itself"
        ReadOnlyTarget -> "Read-only folder"
        is TargetUnavailable -> reason
    }
}

object TransferTargetValidator {

    suspend fun validate(
        fs: FileSystem,
        items: List<VfsPath>,
        mode: TransferMode,
        target: VfsPath,
    ): TargetValidation {
        if (!fs.supports(target)) {
            return TargetValidation.TargetUnavailable("Unavailable")
        }
        val targetNode = runCatching { fs.stat(target) }.getOrNull()
            ?: return TargetValidation.TargetUnavailable("Unavailable or unmounted")
        if (!targetNode.isDirectory) {
            return TargetValidation.TargetUnavailable("Not a folder")
        }
        if (!targetNode.canWrite) {
            return TargetValidation.ReadOnlyTarget
        }
        if (items.isEmpty()) {
            return TargetValidation.Allowed
        }

        for (src in items) {
            if (PathUtils.isAncestor(src, target)) {
                return TargetValidation.InsideSource
            }
        }

        val allInSameParent = items.all { src ->
            runCatching { fs.parent(src) }.getOrNull() == target
        }
        if (allInSameParent) {
            return TargetValidation.SameDirectory
        }

        return TargetValidation.Allowed
    }
}
