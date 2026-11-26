package com.minifile.explorer.core.ops

import com.minifile.explorer.core.i18n.AppI18n
import com.minifile.explorer.core.i18n.AppStrings
import com.minifile.explorer.core.i18n.Str
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

    fun disabledReasonLabel(strings: AppStrings = AppI18n.current()): String? = when (this) {
        Allowed -> null
        SameDirectory -> strings[Str.SAME_FOLDER]
        InsideSource -> strings[Str.CANT_TRANSFER_INTO_SELF]
        ReadOnlyTarget -> strings[Str.READ_ONLY_FOLDER]
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
        val s = AppI18n.current()
        if (!fs.supports(target)) {
            return TargetValidation.TargetUnavailable(s[Str.UNAVAILABLE])
        }
        val targetNode = runCatching { fs.stat(target) }.getOrNull()
            ?: return TargetValidation.TargetUnavailable(s[Str.UNAVAILABLE_OR_UNMOUNTED])
        if (!targetNode.isDirectory) {
            return TargetValidation.TargetUnavailable(s[Str.NOT_A_FOLDER])
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
