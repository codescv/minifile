package com.minifile.explorer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AddToDrive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minifile.explorer.core.gdrive.GDriveAuthException
import com.minifile.explorer.core.i18n.AppStrings
import com.minifile.explorer.core.i18n.Str
import com.minifile.explorer.core.model.Favorite
import com.minifile.explorer.core.model.FtpServerBookmark
import com.minifile.explorer.core.model.GDriveServerBookmark
import com.minifile.explorer.core.peer.PeerConflictPolicy
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.NameValidation
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.core.vfs.Volume
import com.minifile.explorer.core.vfs.VolumeKind
import com.minifile.explorer.data.share.IncomingSharePayload
import com.minifile.explorer.data.share.IncomingSharedFile
import com.minifile.explorer.ui.SaveSharedUiState
import com.minifile.explorer.ui.ShareDestinationKind
import com.minifile.explorer.ui.ShareQuickDestination
import com.minifile.explorer.ui.i18n.LocalAppStrings
import com.minifile.explorer.ui.util.UiFormatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveSharedFilesSheet(
    state: SaveSharedUiState,
    quickDestinations: List<ShareQuickDestination>,
    volumes: List<Volume>,
    ftpServers: List<FtpServerBookmark>,
    gdriveServers: List<GDriveServerBookmark>,
    favorites: List<Favorite>,
    fs: FileSystem,
    hasAllFilesAccess: Boolean,
    onSelectDestination: (VfsPath, String?) -> Unit,
    onEditFileName: (String) -> Unit,
    onConflictPolicyChange: (PeerConflictPolicy) -> Unit,
    onGrantAllFilesAccess: () -> Unit,
    onReauthGDrive: (String) -> Unit,
    onSave: () -> Unit,
    onCancelOrDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val s = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    val isSavingLatest by rememberUpdatedState(state.isSaving)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target -> target != SheetValue.Hidden || !isSavingLatest },
    )

    var browsingFolders by remember { mutableStateOf(false) }
    var browseDir by remember { mutableStateOf<VfsPath?>(null) }
    var browseRootBase by remember { mutableStateOf<VfsPath?>(null) }
    var browseRootLabel by remember { mutableStateOf<String?>(null) }
    var browseSubfolders by remember { mutableStateOf<List<VfsNode>>(emptyList()) }
    var browseLoading by remember { mutableStateOf(false) }
    var browseError by remember { mutableStateOf<String?>(null) }
    var browseReauthServerId by remember { mutableStateOf<String?>(null) }
    var browseReloadNonce by remember { mutableIntStateOf(0) }
    var showNewSubfolderDialog by remember { mutableStateOf(false) }

    LaunchedEffect(browsingFolders, browseDir, browseReloadNonce, hasAllFilesAccess) {
        val dir = browseDir
        if (!browsingFolders || dir == null) {
            browseSubfolders = emptyList()
            browseLoading = false
            browseError = null
            browseReauthServerId = null
            return@LaunchedEffect
        }
        browseLoading = true
        browseError = null
        browseReauthServerId = null
        try {
            val folders = withContext(Dispatchers.IO) {
                fs.list(dir, includeHidden = false)
                    .filter { it.isDirectory }
                    .sortedWith { a, b -> PathUtils.naturalCompare(a.name, b.name) }
            }
            browseSubfolders = folders
            browseLoading = false
        } catch (e: Exception) {
            browseSubfolders = emptyList()
            browseLoading = false
            browseError = e.message ?: s[Str.UNABLE_OPEN_FOLDER]
            browseReauthServerId = if (dir is VfsPath.GDrive && e is GDriveAuthException && e.needsInteraction) {
                dir.serverId
            } else {
                null
            }
        }
    }

    val isSingleEditable = state.payload is IncomingSharePayload.Text ||
        (state.payload is IncomingSharePayload.Files && state.payload.items.size == 1)
    val fatSafe = state.selectedDestination is VfsPath.Saf
    val nameValidation = remember(state.editableFileName, fatSafe, isSingleEditable) {
        if (!isSingleEditable) NameValidation.Valid
        else PathUtils.validateName(state.editableFileName.trim(), fatSafe = fatSafe)
    }
    val needsStoragePermission = state.selectedDestination is VfsPath.Local && !hasAllFilesAccess

    ModalBottomSheet(
        onDismissRequest = {
            if (!state.isSaving) {
                onCancelOrDismiss()
            }
        },
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = !state.isSaving,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (browsingFolders) s[Str.CHOOSE_DESTINATION] else s[Str.SAVE_TO_FILES],
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        if (browsingFolders && !state.isSaving) {
                            browsingFolders = false
                        } else {
                            onCancelOrDismiss()
                        }
                    },
                ) {
                    Icon(Icons.Filled.Close, contentDescription = s[Str.CLOSE])
                }
            }

            Spacer(Modifier.height(8.dp))

            if (browsingFolders && !state.isSaving) {
                FolderBrowserSection(
                    browseDir = browseDir,
                    browseRootBase = browseRootBase,
                    browseRootLabel = browseRootLabel,
                    subfolders = browseSubfolders,
                    loading = browseLoading,
                    error = browseError,
                    reauthServerId = browseReauthServerId,
                    hasAllFilesAccess = hasAllFilesAccess,
                    volumes = volumes,
                    ftpServers = ftpServers,
                    gdriveServers = gdriveServers,
                    favorites = favorites,
                    onEnterRoot = { rootPath, label ->
                        browseRootBase = rootPath
                        browseRootLabel = label
                        browseDir = rootPath
                    },
                    onEnterSubfolder = { childPath ->
                        browseDir = childPath
                    },
                    onNavigateUp = {
                        val cur = browseDir
                        if (cur == null) {
                            browsingFolders = false
                        } else if (browseRootBase != null && cur == browseRootBase) {
                            browseDir = null
                            browseRootBase = null
                            browseRootLabel = null
                        } else {
                            scope.launch {
                                val parent = withContext(Dispatchers.IO) {
                                    runCatching { fs.parent(cur) }.getOrNull()
                                }
                                if (parent == null || (browseRootBase != null && !PathUtils.isAncestor(browseRootBase!!, parent))) {
                                    browseDir = null
                                    browseRootBase = null
                                    browseRootLabel = null
                                } else {
                                    browseDir = parent
                                }
                            }
                        }
                    },
                    onSelectCurrent = { dir ->
                        val friendlyLabel = formatFolderLabel(dir, browseRootLabel, ftpServers, gdriveServers, s)
                        onSelectDestination(dir, friendlyLabel)
                        browsingFolders = false
                    },
                    onCreateSubfolder = { showNewSubfolderDialog = true },
                    onRetry = { browseReloadNonce++ },
                    onGrantAllFilesAccess = onGrantAllFilesAccess,
                    onReauthGDrive = onReauthGDrive,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    // Incoming summary
                    IncomingPayloadSummary(
                        payload = state.payload,
                        editableFileName = state.editableFileName,
                        nameValidation = nameValidation,
                        enabled = !state.isSaving,
                        onEditFileName = onEditFileName,
                    )

                    Spacer(Modifier.height(14.dp))

                    // Destination selector
                    Text(
                        text = s[Str.DESTINATION],
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))

                    SelectedDestinationCard(
                        path = state.selectedDestination,
                        label = state.selectedDestinationLabel,
                        ftpServers = ftpServers,
                        gdriveServers = gdriveServers,
                        enabled = !state.isSaving,
                        onBrowse = {
                            browseDir = null
                            browseRootBase = null
                            browseRootLabel = null
                            browsingFolders = true
                        },
                    )

                    if (quickDestinations.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 2.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(quickDestinations, key = { it.id }) { q ->
                                val selected = state.selectedDestination == q.path
                                FilterChip(
                                    selected = selected,
                                    enabled = !state.isSaving,
                                    onClick = { onSelectDestination(q.path, if (q.kind == ShareDestinationKind.CURRENT) null else q.label) },
                                    label = {
                                        Text(
                                            text = q.label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = iconForQuickDestination(q.kind),
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Conflict policy row
                    Text(
                        text = s[Str.CONFLICT_POLICY_PROMPT],
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    val policies = listOf(
                        PeerConflictPolicy.RENAME to s[Str.RENAME],
                        PeerConflictPolicy.OVERWRITE to s[Str.OVERWRITE],
                        PeerConflictPolicy.SKIP to s[Str.SKIP],
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        policies.forEachIndexed { idx, (policy, label) ->
                            SegmentedButton(
                                selected = state.conflictPolicy == policy,
                                onClick = { onConflictPolicyChange(policy) },
                                enabled = !state.isSaving,
                                shape = SegmentedButtonDefaults.itemShape(idx, policies.size),
                            ) {
                                Text(label, maxLines = 1)
                            }
                        }
                    }

                    // Local storage permission warning (ONLY for Local paths lacking permission)
                    if (needsStoragePermission) {
                        Spacer(Modifier.height(12.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.Warning,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = s[Str.STORAGE_PERM_REQUIRED],
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = s[Str.STORAGE_PERM_SAVE_HINT],
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = onGrantAllFilesAccess) {
                                    Text(s[Str.GRANT_ACCESS])
                                }
                            }
                        }
                    }

                    // Error banner (e.g. FTP unreachable, GDrive auth expired, I/O failure)
                    if (state.errorMessage != null) {
                        Spacer(Modifier.height(12.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = state.errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                val reauthId = state.reauthGDriveServerId
                                if (reauthId != null) {
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = { onReauthGDrive(reauthId) }) {
                                        Text(s[Str.SIGN_IN])
                                    }
                                }
                            }
                        }
                    }

                    // Progress indicator during import
                    if (state.isSaving) {
                        val prog = state.progress
                        Spacer(Modifier.height(14.dp))
                        val fraction = prog?.fraction
                        if (fraction != null) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(6.dp))
                        val statusText = buildString {
                            val current = prog?.currentItemName?.takeIf { it.isNotBlank() }
                            append(if (current != null) s.format(Str.SAVING_ITEM, current) else s[Str.SAVING])
                            if (prog != null) {
                                if (prog.totalItems > 1) {
                                    append(" (${prog.processedItems}/${prog.totalItems})")
                                }
                                if (prog.processedBytes > 0L) {
                                    append(" · ")
                                    append(UiFormatters.formatSize(context, prog.processedBytes))
                                    if (prog.totalBytes > 0L) {
                                        append(" / ")
                                        append(UiFormatters.formatSize(context, prog.totalBytes))
                                    }
                                }
                            }
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    // Action buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedButton(
                            onClick = onCancelOrDismiss,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(s[Str.CANCEL])
                        }
                        val canSave = !state.isSaving &&
                            !needsStoragePermission &&
                            nameValidation is NameValidation.Valid
                        Button(
                            onClick = onSave,
                            enabled = canSave,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (state.isSaving) s[Str.SAVING] else s[Str.SAVE])
                        }
                    }
                }
            }
        }
    }

    if (showNewSubfolderDialog) {
        val parentDir = browseDir
        if (parentDir != null) {
            CreateSubfolderInlineDialog(
                parentDir = parentDir,
                existingNames = browseSubfolders.mapTo(HashSet()) { it.name },
                fs = fs,
                onCreated = { createdDir ->
                    showNewSubfolderDialog = false
                    browseDir = createdDir
                    browseReloadNonce++
                },
                onDismiss = { showNewSubfolderDialog = false },
            )
        } else {
            showNewSubfolderDialog = false
        }
    }
}

@Composable
private fun IncomingPayloadSummary(
    payload: IncomingSharePayload,
    editableFileName: String,
    nameValidation: NameValidation,
    enabled: Boolean,
    onEditFileName: (String) -> Unit,
) {
    val context = LocalContext.current
    val s = LocalAppStrings.current
    when (payload) {
        is IncomingSharePayload.Text -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = s.format(Str.SHARED_TEXT_FMT, UiFormatters.formatSize(context, payload.byteCount)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (payload.previewText.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = payload.previewText,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            EditableFileNameField(
                value = editableFileName,
                validation = nameValidation,
                enabled = enabled,
                onValueChange = onEditFileName,
            )
        }

        is IncomingSharePayload.Files -> {
            if (payload.items.size == 1) {
                val item = payload.items.first()
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Icon(
                            imageVector = iconForSharedFile(item),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = formatFileSubtitle(context, item, s),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                EditableFileNameField(
                    value = editableFileName,
                    validation = nameValidation,
                    enabled = enabled,
                    onValueChange = onEditFileName,
                )
            } else {
                val knownSizes = payload.items.filter { it.sizeBytes >= 0L }
                val totalKnown = knownSizes.sumOf { it.sizeBytes }
                val sizeSummary = when {
                    knownSizes.size == payload.items.size -> UiFormatters.formatSize(context, totalKnown)
                    knownSizes.isNotEmpty() -> "${UiFormatters.formatSize(context, totalKnown)}+"
                    else -> null
                }
                val header = if (sizeSummary != null) {
                    s.format(Str.FILES_COUNT_WITH_SIZE, payload.items.size, sizeSummary)
                } else {
                    s.filesCount(payload.items.size)
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = header,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 132.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(payload.items.indices.toList(), key = { idx -> "${idx}_${payload.items[idx].uri}" }) { idx ->
                                val item = payload.items[idx]
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(
                                        imageVector = iconForSharedFile(item),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = item.displayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (item.sizeBytes >= 0L) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = UiFormatters.formatSize(context, item.sizeBytes),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (payload.skippedBlockedCount > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (payload.skippedBlockedCount == 1) {
                        s[Str.UNSAFE_ITEM_SKIPPED_SINGLE]
                    } else {
                        s.format(Str.UNSAFE_ITEM_SKIPPED_PLURAL, payload.skippedBlockedCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun EditableFileNameField(
    value: String,
    validation: NameValidation,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    val s = LocalAppStrings.current
    val errorText = (validation as? NameValidation.Invalid)?.reason
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(s[Str.FILE_NAME]) },
        singleLine = true,
        enabled = enabled,
        isError = errorText != null,
        supportingText = errorText?.let { err ->
            { Text(err, color = MaterialTheme.colorScheme.error) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SelectedDestinationCard(
    path: VfsPath,
    label: String,
    ftpServers: List<FtpServerBookmark>,
    gdriveServers: List<GDriveServerBookmark>,
    enabled: Boolean,
    onBrowse: () -> Unit,
) {
    val s = LocalAppStrings.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onBrowse),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = iconForVfsPath(path),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatDestinationSubtitle(path, ftpServers, gdriveServers),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onBrowse,
                enabled = enabled,
            ) {
                Text(s[Str.TAB_BROWSE])
            }
        }
    }
}

@Composable
private fun FolderBrowserSection(
    browseDir: VfsPath?,
    browseRootBase: VfsPath?,
    browseRootLabel: String?,
    subfolders: List<VfsNode>,
    loading: Boolean,
    error: String?,
    reauthServerId: String?,
    hasAllFilesAccess: Boolean,
    volumes: List<Volume>,
    ftpServers: List<FtpServerBookmark>,
    gdriveServers: List<GDriveServerBookmark>,
    favorites: List<Favorite>,
    onEnterRoot: (VfsPath, String) -> Unit,
    onEnterSubfolder: (VfsPath) -> Unit,
    onNavigateUp: () -> Unit,
    onSelectCurrent: (VfsPath) -> Unit,
    onCreateSubfolder: () -> Unit,
    onRetry: () -> Unit,
    onGrantAllFilesAccess: () -> Unit,
    onReauthGDrive: (String) -> Unit,
) {
    val s = LocalAppStrings.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        IconButton(onClick = onNavigateUp) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s[Str.BACK])
        }
        Column(modifier = Modifier.weight(1f)) {
            val title = if (browseDir == null) {
                s[Str.LOCATIONS]
            } else {
                formatFolderLabel(browseDir, browseRootLabel, ftpServers, gdriveServers, s)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (browseDir != null) {
                Text(
                    text = formatDestinationSubtitle(browseDir, ftpServers, gdriveServers),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (browseDir != null) {
            IconButton(
                onClick = onCreateSubfolder,
                enabled = !loading && error == null,
            ) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = s[Str.NEW_FOLDER])
            }
            Spacer(Modifier.width(4.dp))
            Button(
                onClick = { onSelectCurrent(browseDir) },
                enabled = !loading,
            ) {
                Text(s[Str.SELECT])
            }
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

    if (browseDir == null) {
        val readyVolumes = volumes.filter { it.root != null }
        val folderFavorites = favorites.filter { it.isDirectory }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp),
        ) {
            if (readyVolumes.isNotEmpty()) {
                item(key = "sec_storage") {
                    PickerSectionHeader(s[Str.STORAGE])
                }
                items(readyVolumes, key = { "vol_${it.id}" }) { vol ->
                    val root = vol.root ?: return@items
                    val volLabel = if (vol.kind == VolumeKind.PRIMARY) s[Str.INTERNAL_STORAGE] else vol.name
                    ListItem(
                        leadingContent = {
                            Icon(
                                imageVector = if (vol.kind == VolumeKind.USB_OTG || root is VfsPath.Saf) {
                                    Icons.Filled.Usb
                                } else {
                                    Icons.Filled.Storage
                                },
                                contentDescription = null,
                            )
                        },
                        headlineContent = { Text(volLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = vol.subtitle?.let {
                            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEnterRoot(root, volLabel) },
                    )
                }
            }

            if (gdriveServers.isNotEmpty() || ftpServers.isNotEmpty()) {
                item(key = "sec_servers") {
                    PickerSectionHeader(s[Str.SERVERS])
                }
                items(gdriveServers, key = { "gd_${it.id}" }) { server ->
                    ListItem(
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Filled.AddToDrive,
                                contentDescription = "Google Drive",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        headlineContent = { Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(server.displayUrl, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEnterRoot(server.rootPath, server.name) },
                    )
                }
                items(ftpServers, key = { "ftp_${it.id}" }) { server ->
                    ListItem(
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Filled.Dns,
                                contentDescription = s[Str.FTP_SERVER_DESC],
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        headlineContent = { Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(server.displayUrl, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEnterRoot(server.rootPath, server.name) },
                    )
                }
            }

            if (folderFavorites.isNotEmpty()) {
                item(key = "sec_favorites") {
                    PickerSectionHeader(s[Str.FAVORITES])
                }
                items(folderFavorites, key = { "fav_${it.id}" }) { fav ->
                    ListItem(
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        headlineContent = { Text(fav.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                formatDestinationSubtitle(fav.path, ftpServers, gdriveServers),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEnterRoot(fav.path, fav.name) },
                    )
                }
            }
        }
    } else {
        when {
            loading -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                ) {
                    CircularProgressIndicator()
                }
            }

            error != null -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (browseDir is VfsPath.Local && !hasAllFilesAccess) {
                            Button(onClick = onGrantAllFilesAccess) {
                                Text(s[Str.GRANT_ACCESS])
                            }
                        } else if (reauthServerId != null) {
                            Button(onClick = { onReauthGDrive(reauthServerId) }) {
                                Text(s[Str.SIGN_IN])
                            }
                        }
                        OutlinedButton(onClick = onRetry) {
                            Text(s[Str.RETRY])
                        }
                    }
                }
            }

            subfolders.isEmpty() -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                ) {
                    Text(
                        text = s[Str.NO_SUBFOLDERS_SELECT],
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    items(subfolders, key = { it.path.serialize() }) { sub ->
                        ListItem(
                            leadingContent = {
                                Icon(Icons.Filled.Folder, contentDescription = null)
                            },
                            headlineContent = {
                                Text(sub.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onEnterSubfolder(sub.path) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateSubfolderInlineDialog(
    parentDir: VfsPath,
    existingNames: Set<String>,
    fs: FileSystem,
    onCreated: (VfsPath) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    val initial = remember(existingNames, s) {
        PathUtils.uniqueName(s[Str.NEW_FOLDER], existingNames, isDirectory = true)
    }
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        title = { Text(s[Str.NEW_FOLDER]) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text(s[Str.FOLDER_NAME]) },
                    singleLine = true,
                    enabled = !creating,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !creating,
                onClick = {
                    val trimmed = name.trim()
                    val v = PathUtils.validateName(trimmed, fatSafe = parentDir is VfsPath.Saf)
                    if (v is NameValidation.Invalid) {
                        error = v.reason
                        return@Button
                    }
                    creating = true
                    scope.launch {
                        try {
                            val created = withContext(Dispatchers.IO) {
                                fs.createDirectory(parentDir, trimmed)
                            }
                            onCreated(created)
                        } catch (e: Exception) {
                            creating = false
                            error = e.message ?: s[Str.FAILED_CREATE_FOLDER]
                        }
                    }
                },
            ) {
                Text(if (creating) s[Str.CREATING] else s[Str.CREATE])
            }
        },
        dismissButton = {
            TextButton(
                enabled = !creating,
                onClick = onDismiss,
            ) {
                Text(s[Str.CANCEL])
            }
        },
    )
}

@Composable
private fun PickerSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

private fun iconForQuickDestination(kind: ShareDestinationKind): ImageVector = when (kind) {
    ShareDestinationKind.RECEIVED -> Icons.Filled.Inbox
    ShareDestinationKind.DOWNLOADS -> Icons.Filled.Download
    ShareDestinationKind.CURRENT -> Icons.Filled.FolderOpen
    ShareDestinationKind.VOLUME -> Icons.Filled.Usb
    ShareDestinationKind.FTP -> Icons.Filled.Dns
    ShareDestinationKind.GDRIVE -> Icons.Filled.AddToDrive
    ShareDestinationKind.FAVORITE -> Icons.Filled.Star
}

private fun iconForVfsPath(path: VfsPath): ImageVector = when (path) {
    is VfsPath.Local -> Icons.Filled.Folder
    is VfsPath.Saf -> Icons.Filled.Usb
    is VfsPath.Ftp -> Icons.Filled.Dns
    is VfsPath.GDrive -> Icons.Filled.AddToDrive
}

private fun iconForSharedFile(item: IncomingSharedFile): ImageVector {
    val mime = item.mimeType ?: PathUtils.guessMimeType(item.displayName)
    return when {
        mime.startsWith("image/") -> Icons.Filled.Image
        mime.startsWith("video/") -> Icons.Filled.Movie
        mime.startsWith("audio/") -> Icons.Filled.MusicNote
        mime.startsWith("text/") || mime == "application/pdf" -> Icons.Filled.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

private fun formatFileSubtitle(context: android.content.Context, item: IncomingSharedFile, s: AppStrings): String {
    val sizeText = if (item.sizeBytes >= 0L) UiFormatters.formatSize(context, item.sizeBytes) else s[Str.UNKNOWN_SIZE]
    val mime = item.mimeType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() && it != "*/*" }
    return if (mime != null) "$sizeText · $mime" else sizeText
}

private fun formatFolderLabel(
    path: VfsPath,
    rootLabel: String?,
    ftpServers: List<FtpServerBookmark>,
    gdriveServers: List<GDriveServerBookmark>,
    s: AppStrings,
): String = when (path) {
    is VfsPath.Ftp -> {
        val name = ftpServers.firstOrNull { it.id == path.serverId }?.name ?: rootLabel ?: "FTP"
        if (path.normalizedRemotePath == "/") name else "$name / ${path.displayName}"
    }

    is VfsPath.GDrive -> {
        val name = gdriveServers.firstOrNull { it.id == path.serverId }?.name ?: rootLabel ?: "Google Drive"
        if (path.normalizedRemotePath == "/") name else "$name / ${path.displayName}"
    }

    is VfsPath.Local, is VfsPath.Saf -> path.displayName.ifEmpty { rootLabel ?: s[Str.FOLDER] }
}

private fun formatDestinationSubtitle(
    path: VfsPath,
    ftpServers: List<FtpServerBookmark>,
    gdriveServers: List<GDriveServerBookmark>,
): String = when (path) {
    is VfsPath.Local -> path.normalized
    is VfsPath.Saf -> path.documentId
    is VfsPath.Ftp -> {
        val srv = ftpServers.firstOrNull { it.id == path.serverId }
        if (srv != null) "ftp://${srv.host}:${srv.port}${path.normalizedRemotePath}" else path.serialize()
    }

    is VfsPath.GDrive -> {
        val srv = gdriveServers.firstOrNull { it.id == path.serverId }
        val who = srv?.email?.ifBlank { srv.accountName }?.ifBlank { srv.name }
        if (who != null) "Google Drive ($who) · ${path.normalizedRemotePath}" else path.serialize()
    }
}
