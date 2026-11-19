package com.minifile.explorer.ui

import com.minifile.explorer.core.peer.PeerConflictPolicy
import com.minifile.explorer.core.share.ShareImportProgress
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.data.share.IncomingSharePayload

enum class ShareDestinationKind {
    RECEIVED,
    DOWNLOADS,
    CURRENT,
    VOLUME,
    FTP,
    GDRIVE,
    FAVORITE,
}

data class ShareQuickDestination(
    val id: String,
    val label: String,
    val path: VfsPath,
    val kind: ShareDestinationKind,
)

data class SaveSharedUiState(
    val payload: IncomingSharePayload,
    val editableFileName: String,
    val selectedDestination: VfsPath,
    val selectedDestinationLabel: String,
    val conflictPolicy: PeerConflictPolicy = PeerConflictPolicy.RENAME,
    val isSaving: Boolean = false,
    val progress: ShareImportProgress? = null,
    val errorMessage: String? = null,
    val reauthGDriveServerId: String? = null,
)
