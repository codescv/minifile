package com.minifile.explorer.ui.screens

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AddToDrive
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minifile.explorer.BuildConfig
import com.minifile.explorer.core.gallery.MediaItem
import com.minifile.explorer.core.gdrive.GDriveTokenParser
import com.minifile.explorer.core.i18n.Str
import com.minifile.explorer.core.model.ClipboardState
import com.minifile.explorer.core.model.Favorite
import com.minifile.explorer.core.model.FtpServerBookmark
import com.minifile.explorer.core.model.GDriveAuthMode
import com.minifile.explorer.core.model.GDriveServerBookmark
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.core.vfs.Volume
import com.minifile.explorer.core.vfs.VolumeKind
import com.minifile.explorer.data.gallery.GalleryThumbnailCache
import com.minifile.explorer.data.gdrive.AndroidAccountTokenProvider
import com.minifile.explorer.data.gdrive.GDriveSignInState
import com.minifile.explorer.ui.components.ClipboardBar
import com.minifile.explorer.ui.components.RecentMediaStrip
import com.minifile.explorer.ui.i18n.LocalAppStrings
import com.minifile.explorer.ui.util.UiFormatters

const val MINIFILE_APK_PACKAGE = "com.minifile.explorer"
const val MINIFILE_SIGNING_SHA1 =
    "F5:D0:CC:CF:DA:49:44:0B:BB:54:22:CB:88:0F:C8:C6:81:66:A6:54"

private sealed interface ServerRowModel {
    val key: String
    val createdAt: Long

    data class GDriveItem(val bookmark: GDriveServerBookmark) : ServerRowModel {
        override val key: String get() = "gdrive:${bookmark.id}"
        override val createdAt: Long get() = bookmark.createdAt
    }

    data class FtpItem(val bookmark: FtpServerBookmark) : ServerRowModel {
        override val key: String get() = "ftp:${bookmark.id}"
        override val createdAt: Long get() = bookmark.createdAt
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StorageHomeScreen(
    volumes: List<Volume>,
    favorites: List<Favorite>,
    ftpServers: List<FtpServerBookmark> = emptyList(),
    gdriveServers: List<GDriveServerBookmark> = emptyList(),
    gdriveSignInState: GDriveSignInState = GDriveSignInState.Idle,
    defaultGDriveClientId: String = "",
    defaultGDriveClientSecret: String = "",
    hasAllFilesAccess: Boolean,
    onboardingDismissed: Boolean,
    clipboard: ClipboardState?,
    onGrantAllFilesAccess: () -> Unit,
    onDismissOnboarding: () -> Unit,
    onMountVolumeSaf: (Volume) -> Unit,
    onOpenVolume: (Volume) -> Unit,
    onOpenFtpServer: (FtpServerBookmark) -> Unit = {},
    onAddFtpServer: (
        name: String,
        host: String,
        port: Int,
        anonymous: Boolean,
        username: String,
        password: String,
        initialPath: String,
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    onUpdateFtpServer: (FtpServerBookmark) -> Unit = {},
    onRemoveFtpServer: (String) -> Unit = {},
    onOpenGDriveServer: (GDriveServerBookmark) -> Unit = {},
    onUpdateGDriveServer: (GDriveServerBookmark) -> Unit = {},
    onRemoveGDriveServer: (String) -> Unit = {},
    onPickGoogleAccount: (displayName: String, initialPath: String, existing: GDriveServerBookmark?) -> Unit =
        { _, _, _ -> },
    onStartGDriveBrowserSignIn: (
        clientId: String,
        clientSecret: String,
        displayName: String,
        initialPath: String,
        existing: GDriveServerBookmark?,
    ) -> Unit = { _, _, _, _, _ -> },
    onSubmitGDrivePastedToken: (
        rawText: String,
        clientId: String,
        clientSecret: String,
        displayName: String,
        initialPath: String,
        existing: GDriveServerBookmark?,
    ) -> Unit = { _, _, _, _, _, _ -> },
    onCancelGDriveSignIn: () -> Unit = {},
    onGDriveSignedInSuccess: (GDriveServerBookmark, Boolean) -> Unit = { _, _ -> },
    onCopiedMessage: (String) -> Unit = {},
    onOpenQuickPath: (VfsPath, String) -> Unit,
    onOpenFavorite: (Favorite) -> Unit,
    onSeeAllFavorites: () -> Unit,
    onRenameFavorite: (Favorite, String) -> Unit = { _, _ -> },
    onRemoveFavorite: (Favorite) -> Unit = {},
    onMoveFavorite: (Favorite, Int) -> Unit = { _, _ -> },
    onShowFavoriteProperties: (Favorite) -> Unit = {},
    onClipboardToFavorite: () -> Unit,
    onClearClipboard: () -> Unit,
    recentMediaItems: List<MediaItem> = emptyList(),
    showRecentMedia: Boolean = false,
    thumbnailCache: GalleryThumbnailCache? = null,
    onOpenRecentMedia: (MediaItem) -> Unit = {},
    onSeeAllMedia: () -> Unit = {},
) {
    val context = LocalContext.current
    val s = LocalAppStrings.current
    val storageVolumes = volumes.filter {
        it.kind == VolumeKind.PRIMARY || it.kind == VolumeKind.SD_CARD || it.kind == VolumeKind.USB_OTG
    }

    val mergedServers = remember(gdriveServers, ftpServers) {
        buildList {
            gdriveServers.forEach { add(ServerRowModel.GDriveItem(it)) }
            ftpServers.forEach { add(ServerRowModel.FtpItem(it)) }
        }.sortedBy { it.createdAt }
    }

    var showAddServerDialog by remember { mutableStateOf(false) }
    var editingFtpServer by remember { mutableStateOf<FtpServerBookmark?>(null) }
    var editingGDriveServer by remember { mutableStateOf<GDriveServerBookmark?>(null) }
    var renamingFavorite by remember { mutableStateOf<Favorite?>(null) }

    val hasGoogleAuth = remember(context) {
        AndroidAccountTokenProvider.hasGoogleAccountAuthenticator(context)
    }

    LaunchedEffect(gdriveSignInState) {
        val success = gdriveSignInState as? GDriveSignInState.Success ?: return@LaunchedEffect
        if (showAddServerDialog || editingGDriveServer != null) {
            showAddServerDialog = false
            editingGDriveServer = null
            onGDriveSignedInSuccess(success.bookmark, success.isReauth)
        }
    }

    val primaryBase = Environment.getExternalStorageDirectory().absolutePath
    val quickAccess = listOf(
        Triple(s[Str.QA_DOWNLOAD], Icons.Filled.Download, "$primaryBase/Download"),
        Triple(s[Str.QA_DCIM], Icons.Filled.PhotoCamera, "$primaryBase/DCIM"),
        Triple(s[Str.QA_PICTURES], Icons.Filled.Image, "$primaryBase/Pictures"),
        Triple(s[Str.QA_DOCUMENTS], Icons.Filled.Description, "$primaryBase/Documents"),
        Triple(s[Str.QA_MOVIES], Icons.Filled.Movie, "$primaryBase/Movies"),
        Triple(s[Str.QA_MUSIC], Icons.Filled.MusicNote, "$primaryBase/Music"),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s[Str.APP_NAME], fontWeight = FontWeight.SemiBold) },
            )
        },
        bottomBar = {
            if (clipboard != null) {
                ClipboardBar(
                    clipboard = clipboard,
                    canPasteHere = false,
                    disabledPasteReason = s[Str.OPEN_FOLDER_TO_PASTE],
                    onPasteHere = {},
                    onPickFavorite = onClipboardToFavorite,
                    onClear = onClearClipboard,
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 4.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (!hasAllFilesAccess && !onboardingDismissed) {
                item(key = "onboarding") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Text(
                                text = s[Str.STORAGE_ACCESS],
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = s[Str.GRANT_ALL_FILES_DESC],
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(onClick = onGrantAllFilesAccess) {
                                    Text(s[Str.GRANT_ACCESS])
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = onDismissOnboarding) {
                                    Text(s[Str.DISMISS])
                                }
                            }
                        }
                    }
                }
            }

            // 1. Storage Volumes
            item(key = "storage-section") {
                SectionHeader(s[Str.STORAGE])
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        storageVolumes.forEach { vol ->
                            VolumeRow(
                                volume = vol,
                                icon = when (vol.kind) {
                                    VolumeKind.PRIMARY -> Icons.Filled.Smartphone
                                    VolumeKind.USB_OTG -> Icons.Filled.Usb
                                    else -> Icons.Filled.SdCard
                                },
                                context = context,
                                onClick = {
                                    if (vol.root != null) onOpenVolume(vol) else onMountVolumeSaf(vol)
                                },
                                onMountClick = if (vol.needsSafGrant && vol.kind != VolumeKind.PRIMARY) {
                                    { onMountVolumeSaf(vol) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }

            // 1.5 Recent Photos & Videos strip (spec §3.3)
            if (hasAllFilesAccess && showRecentMedia && BuildConfig.THUMBNAILS && thumbnailCache != null && recentMediaItems.isNotEmpty()) {
                item(key = "recent-media") {
                    RecentMediaStrip(
                        items = recentMediaItems,
                        thumbnailCache = thumbnailCache,
                        onItemClick = onOpenRecentMedia,
                        onSeeAllClick = onSeeAllMedia,
                    )
                }
            }

            // 2. Unified Servers (Google Drive + FTP)
            item(key = "servers-section") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SectionHeader(s[Str.SERVERS])
                    TextButton(
                        onClick = {
                            onCancelGDriveSignIn()
                            showAddServerDialog = true
                        },
                    ) {
                        Text(s[Str.ADD_SERVER_BTN])
                    }
                }
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (mergedServers.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onCancelGDriveSignIn()
                                    showAddServerDialog = true
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        ) {
                            Text(
                                text = s[Str.NO_SERVERS_CONFIGURED],
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = s[Str.NO_SERVERS_HINT],
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            mergedServers.forEachIndexed { idx, rowModel ->
                                if (idx > 0) {
                                    HorizontalDivider()
                                }
                                when (rowModel) {
                                    is ServerRowModel.GDriveItem -> {
                                        val srv = rowModel.bookmark
                                        ServerRow(
                                            icon = Icons.Filled.AddToDrive,
                                            title = srv.name,
                                            subtitle = srv.displayUrl,
                                            onClick = { onOpenGDriveServer(srv) },
                                            onEdit = {
                                                onCancelGDriveSignIn()
                                                editingGDriveServer = srv
                                            },
                                            onRemove = { onRemoveGDriveServer(srv.id) },
                                        )
                                    }

                                    is ServerRowModel.FtpItem -> {
                                        val srv = rowModel.bookmark
                                        ServerRow(
                                            icon = Icons.Filled.Dns,
                                            title = srv.name,
                                            subtitle = srv.displayUrl,
                                            onClick = { onOpenFtpServer(srv) },
                                            onEdit = { editingFtpServer = srv },
                                            onRemove = { onRemoveFtpServer(srv.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Quick Access
            item(key = "quick-access") {
                SectionHeader(s[Str.QUICK_ACCESS])
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    for ((label, icon, pathStr) in quickAccess) {
                        AssistChip(
                            onClick = { onOpenQuickPath(VfsPath.Local(pathStr), s[Str.INTERNAL]) },
                            leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            // 4. Recent Favorites
            item(key = "recent-favorites") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SectionHeader(s[Str.RECENT_FAVORITES])
                    TextButton(onClick = onSeeAllFavorites) {
                        Text(s[Str.SEE_ALL_ARROW])
                    }
                }
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (favorites.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onSeeAllFavorites)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        ) {
                            Text(
                                text = s[Str.NO_FAVORITES_YET],
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = s[Str.NO_FAVORITES_HINT],
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            favorites.forEachIndexed { idx, fav ->
                                if (idx > 0) {
                                    HorizontalDivider()
                                }
                                FavoriteRow(
                                    favorite = fav,
                                    canMoveUp = idx > 0,
                                    canMoveDown = idx < favorites.lastIndex,
                                    onClick = { onOpenFavorite(fav) },
                                    onRename = { renamingFavorite = fav },
                                    onProperties = { onShowFavoriteProperties(fav) },
                                    onMoveUp = { onMoveFavorite(fav, -1) },
                                    onMoveDown = { onMoveFavorite(fav, 1) },
                                    onRemove = { onRemoveFavorite(fav) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    renamingFavorite?.let { fav ->
        var text by remember(fav.id) { mutableStateOf(fav.name) }
        AlertDialog(
            onDismissRequest = { renamingFavorite = null },
            title = { Text(s[Str.RENAME_FAVORITE]) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(s[Str.NAME]) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRenameFavorite(fav, text)
                        renamingFavorite = null
                    },
                ) {
                    Text(s[Str.SAVE])
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingFavorite = null }) { Text(s[Str.CANCEL]) }
            },
        )
    }

    if (showAddServerDialog) {
        AddServerDialog(
            hasGoogleAccountAuthenticator = hasGoogleAuth,
            signInState = gdriveSignInState,
            defaultGDriveClientId = defaultGDriveClientId,
            defaultGDriveClientSecret = defaultGDriveClientSecret,
            onSaveFtp = { name, host, port, anon, user, pass, path ->
                showAddServerDialog = false
                onAddFtpServer(name, host, port, anon, user, pass, path)
            },
            onPickGoogleAccount = { dispName, startFolder ->
                onPickGoogleAccount(dispName, startFolder, null)
            },
            onStartGDriveBrowserSignIn = { cid, csec, dispName, startFolder ->
                onStartGDriveBrowserSignIn(cid, csec, dispName, startFolder, null)
            },
            onSubmitGDrivePastedToken = { raw, cid, csec, dispName, startFolder ->
                onSubmitGDrivePastedToken(raw, cid, csec, dispName, startFolder, null)
            },
            onCancelGDriveSignIn = onCancelGDriveSignIn,
            onCopiedMessage = onCopiedMessage,
            onDismiss = {
                onCancelGDriveSignIn()
                showAddServerDialog = false
            },
        )
    }

    editingFtpServer?.let { target ->
        AddEditFtpServerDialog(
            existing = target,
            onSave = { name, host, port, anon, user, pass, path ->
                editingFtpServer = null
                onUpdateFtpServer(
                    target.copy(
                        name = name.ifBlank { host },
                        host = host,
                        port = port,
                        anonymous = anon,
                        username = if (anon) "anonymous" else user.ifBlank { "anonymous" },
                        password = if (anon) "" else pass,
                        initialPath = PathUtils.normalizeLocalPath(path.ifBlank { "/" }),
                    ),
                )
            },
            onDismiss = { editingFtpServer = null },
        )
    }

    editingGDriveServer?.let { target ->
        EditGDriveServerDialog(
            bookmark = target,
            startInReauthMode = false,
            hasGoogleAccountAuthenticator = hasGoogleAuth,
            signInState = gdriveSignInState,
            defaultClientId = defaultGDriveClientId,
            defaultClientSecret = defaultGDriveClientSecret,
            onSaveMetadata = { updated ->
                editingGDriveServer = null
                onUpdateGDriveServer(updated)
            },
            onRemove = {
                val id = target.id
                editingGDriveServer = null
                onRemoveGDriveServer(id)
            },
            onPickGoogleAccount = { dispName, startFolder ->
                onPickGoogleAccount(dispName, startFolder, target)
            },
            onStartBrowserSignIn = { cid, csec, dispName, startFolder ->
                onStartGDriveBrowserSignIn(cid, csec, dispName, startFolder, target)
            },
            onSubmitPastedToken = { raw, cid, csec, dispName, startFolder ->
                onSubmitGDrivePastedToken(raw, cid, csec, dispName, startFolder, target)
            },
            onCancelSignIn = onCancelGDriveSignIn,
            onCopiedMessage = onCopiedMessage,
            onDismiss = {
                onCancelGDriveSignIn()
                editingGDriveServer = null
            },
        )
    }
}

@Composable
private fun FavoriteRow(
    favorite: Favorite,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onProperties: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val s = LocalAppStrings.current
    var menuOpen by remember { mutableStateOf(false) }
    val subtitle = when (val p = favorite.path) {
        is VfsPath.Local -> p.normalized
        is VfsPath.Saf -> p.documentId
        is VfsPath.Ftp -> p.serialize()
        is VfsPath.GDrive -> p.serialize()
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Icon(
            imageVector = if (favorite.isDirectory) Icons.Filled.Star else Icons.Filled.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = favorite.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (favorite.path is VfsPath.Saf) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.Usb,
                        contentDescription = s[Str.EXTERNAL_SAF],
                        modifier = Modifier.size(15.dp),
                    )
                }
                if (favorite.path is VfsPath.Ftp) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.Cloud,
                        contentDescription = "FTP",
                        modifier = Modifier.size(15.dp),
                    )
                }
                if (favorite.path is VfsPath.GDrive) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.AddToDrive,
                        contentDescription = "Google Drive",
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = s.format(Str.MORE_ACTIONS_FOR, favorite.name))
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text(s[Str.RENAME]) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(s[Str.PROPERTIES]) },
                    onClick = {
                        menuOpen = false
                        onProperties()
                    },
                )
                if (canMoveUp) {
                    DropdownMenuItem(
                        text = { Text(s[Str.MOVE_UP]) },
                        onClick = {
                            menuOpen = false
                            onMoveUp()
                        },
                    )
                }
                if (canMoveDown) {
                    DropdownMenuItem(
                        text = { Text(s[Str.MOVE_DOWN]) },
                        onClick = {
                            menuOpen = false
                            onMoveDown()
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(s[Str.REMOVE], color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    },
                )
            }
        }
    }
}

@Composable
private fun ServerRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val s = LocalAppStrings.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = s.format(Str.MORE_ACTIONS_FOR, title))
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text(s[Str.EDIT]) },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text(s[Str.REMOVE], color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerDialog(
    hasGoogleAccountAuthenticator: Boolean,
    signInState: GDriveSignInState,
    defaultGDriveClientId: String,
    defaultGDriveClientSecret: String,
    onSaveFtp: (
        name: String,
        host: String,
        port: Int,
        anonymous: Boolean,
        username: String,
        password: String,
        initialPath: String,
    ) -> Unit,
    onPickGoogleAccount: (displayName: String, initialPath: String) -> Unit,
    onStartGDriveBrowserSignIn: (
        clientId: String,
        clientSecret: String,
        displayName: String,
        initialPath: String,
    ) -> Unit,
    onSubmitGDrivePastedToken: (
        rawText: String,
        clientId: String,
        clientSecret: String,
        displayName: String,
        initialPath: String,
    ) -> Unit,
    onCancelGDriveSignIn: () -> Unit,
    onCopiedMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    // 0 = Google Drive (default), 1 = FTP Server
    var selectedType by remember { mutableIntStateOf(0) }
    var ftpSaveTrigger by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s[Str.ADD_SERVER]) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = selectedType == 0,
                        onClick = { selectedType = 0 },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text("Google Drive")
                    }
                    SegmentedButton(
                        selected = selectedType == 1,
                        onClick = {
                            onCancelGDriveSignIn()
                            selectedType = 1
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text(s[Str.FTP_SERVER])
                    }
                }

                if (selectedType == 0) {
                    GDriveServerForm(
                        initialDisplayName = "",
                        initialStartFolder = "/",
                        hasGoogleAccountAuthenticator = hasGoogleAccountAuthenticator,
                        signInState = signInState,
                        defaultClientId = defaultGDriveClientId,
                        defaultClientSecret = defaultGDriveClientSecret,
                        onPickGoogleAccount = onPickGoogleAccount,
                        onStartBrowserSignIn = onStartGDriveBrowserSignIn,
                        onSubmitPastedToken = onSubmitGDrivePastedToken,
                        onCancelSignIn = onCancelGDriveSignIn,
                        onCopiedMessage = onCopiedMessage,
                    )
                } else {
                    FtpServerForm(
                        existing = null,
                        saveTrigger = ftpSaveTrigger,
                        onValidatedSave = onSaveFtp,
                    )
                }
            }
        },
        confirmButton = {
            if (selectedType == 1) {
                Button(onClick = { ftpSaveTrigger++ }) {
                    Text(s[Str.SAVE])
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[Str.CLOSE]) }
        },
    )
}

@Composable
fun EditGDriveServerDialog(
    bookmark: GDriveServerBookmark,
    startInReauthMode: Boolean,
    hasGoogleAccountAuthenticator: Boolean,
    signInState: GDriveSignInState,
    defaultClientId: String,
    defaultClientSecret: String,
    onSaveMetadata: (GDriveServerBookmark) -> Unit,
    onRemove: () -> Unit,
    onPickGoogleAccount: (displayName: String, initialPath: String) -> Unit,
    onStartBrowserSignIn: (
        clientId: String,
        clientSecret: String,
        displayName: String,
        initialPath: String,
    ) -> Unit,
    onSubmitPastedToken: (
        rawText: String,
        clientId: String,
        clientSecret: String,
        displayName: String,
        initialPath: String,
    ) -> Unit,
    onCancelSignIn: () -> Unit,
    onCopiedMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    var reauthMode by remember(bookmark.id, startInReauthMode) { mutableStateOf(startInReauthMode) }
    var name by remember(bookmark.id) { mutableStateOf(bookmark.name) }
    var startFolder by remember(bookmark.id) { mutableStateOf(bookmark.initialPath) }

    val accountLine = remember(bookmark, s) {
        val who = bookmark.email.ifBlank { bookmark.accountName.ifBlank { "Google Drive" } }
        val modeLabel = when (bookmark.authMode) {
            GDriveAuthMode.SYSTEM_ACCOUNT -> s[Str.GDRIVE_AUTH_ANDROID]
            GDriveAuthMode.BROWSER_PKCE -> s[Str.GDRIVE_AUTH_BROWSER]
            GDriveAuthMode.PASTED_TOKEN -> s[Str.GDRIVE_AUTH_TOKEN]
        }
        "$who · $modeLabel"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (reauthMode) s[Str.SIGN_IN_GDRIVE] else s[Str.EDIT_GDRIVE])
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = accountLine,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }

                if (reauthMode) {
                    GDriveServerForm(
                        initialDisplayName = name,
                        initialStartFolder = startFolder,
                        hasGoogleAccountAuthenticator = hasGoogleAccountAuthenticator,
                        signInState = signInState,
                        defaultClientId = bookmark.clientId.ifBlank { defaultClientId },
                        defaultClientSecret = bookmark.clientSecret.ifBlank { defaultClientSecret },
                        onPickGoogleAccount = onPickGoogleAccount,
                        onStartBrowserSignIn = onStartBrowserSignIn,
                        onSubmitPastedToken = onSubmitPastedToken,
                        onCancelSignIn = onCancelSignIn,
                        onCopiedMessage = onCopiedMessage,
                    )
                } else {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(s[Str.DISPLAY_NAME]) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = startFolder,
                        onValueChange = { startFolder = it },
                        label = { Text(s[Str.START_FOLDER]) },
                        placeholder = { Text("/") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = { reauthMode = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(s[Str.REAUTHENTICATE])
                    }
                    TextButton(
                        onClick = onRemove,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(s[Str.REMOVE_SERVER])
                    }
                }
            }
        },
        confirmButton = {
            if (!reauthMode) {
                Button(
                    onClick = {
                        val fallback = bookmark.email.ifBlank { bookmark.accountName.ifBlank { "Google Drive" } }
                        onSaveMetadata(
                            bookmark.copy(
                                name = name.trim().ifEmpty { fallback },
                                initialPath = PathUtils.normalizePosixPath(startFolder.trim().ifEmpty { "/" }),
                            ),
                        )
                    },
                ) {
                    Text(s[Str.SAVE])
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (reauthMode && !startInReauthMode) {
                        onCancelSignIn()
                        reauthMode = false
                    } else {
                        onDismiss()
                    }
                },
            ) {
                Text(if (reauthMode && !startInReauthMode) s[Str.BACK] else s[Str.CANCEL])
            }
        },
    )
}

@Composable
private fun GDriveServerForm(
    initialDisplayName: String,
    initialStartFolder: String,
    hasGoogleAccountAuthenticator: Boolean,
    signInState: GDriveSignInState,
    defaultClientId: String,
    defaultClientSecret: String,
    onPickGoogleAccount: (displayName: String, initialPath: String) -> Unit,
    onStartBrowserSignIn: (
        clientId: String,
        clientSecret: String,
        displayName: String,
        initialPath: String,
    ) -> Unit,
    onSubmitPastedToken: (
        rawText: String,
        clientId: String,
        clientSecret: String,
        displayName: String,
        initialPath: String,
    ) -> Unit,
    onCancelSignIn: () -> Unit,
    onCopiedMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val s = LocalAppStrings.current
    var displayName by remember { mutableStateOf(initialDisplayName) }
    var startFolder by remember { mutableStateOf(initialStartFolder) }

    // Expanded auth drawer: 0 = collapsed, 1 = Browser OAuth, 2 = Paste Token / JSON
    var expandedSection by remember { mutableIntStateOf(0) }
    var showSetupHelp by remember { mutableStateOf(false) }

    val seedClientId = remember(defaultClientId) {
        defaultClientId.ifBlank { BuildConfig.GDRIVE_DEFAULT_CLIENT_ID }
    }
    var clientId by remember(seedClientId) { mutableStateOf(seedClientId) }
    var clientSecret by remember(defaultClientSecret) { mutableStateOf(defaultClientSecret) }
    var showSecret by remember { mutableStateOf(false) }

    var pastedText by remember { mutableStateOf("") }
    var pasteClientId by remember(defaultClientId) { mutableStateOf(defaultClientId) }
    var pasteClientSecret by remember(defaultClientSecret) { mutableStateOf(defaultClientSecret) }
    var showPasteSecret by remember { mutableStateOf(false) }

    val isBusy = signInState is GDriveSignInState.WaitingForBrowser ||
        signInState is GDriveSignInState.Exchanging

    val parsedPastePreview = remember(pastedText) {
        if (pastedText.isBlank()) null else GDriveTokenParser.parse(pastedText)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text(s[Str.DISPLAY_NAME_OPTIONAL]) },
            placeholder = { Text(s[Str.DEFAULTS_TO_GOOGLE_EMAIL]) },
            singleLine = true,
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = startFolder,
            onValueChange = { startFolder = it },
            label = { Text(s[Str.START_FOLDER]) },
            placeholder = { Text("/") },
            singleLine = true,
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = s[Str.SIGN_IN_METHOD],
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(onClick = { showSetupHelp = !showSetupHelp }) {
                Icon(
                    Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = s[Str.OAUTH_SETUP_HELP],
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(if (showSetupHelp) s[Str.HIDE_SETUP_INFO] else s[Str.GCP_OAUTH_SETUP])
            }
        }

        if (showSetupHelp) {
            GDriveOAuthSetupHelpCard(
                onCopy = { label, text ->
                    UiFormatters.copyToSystemClipboard(context, label, text)
                    onCopiedMessage(s.format(Str.COPIED_ITEM, label))
                },
            )
        }

        // Mode 1: Sign in with Google Account (Android system account)
        if (hasGoogleAccountAuthenticator) {
            Button(
                onClick = {
                    expandedSection = 0
                    onPickGoogleAccount(displayName, startFolder)
                },
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(s[Str.SIGN_IN_GOOGLE_ACCOUNT])
            }
        }

        // Mode 2: Sign in with Browser (OAuth 2.0 loopback + PKCE S256)
        OutlinedButton(
            onClick = { expandedSection = if (expandedSection == 1) 0 else 1 },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(s[Str.SIGN_IN_BROWSER])
        }

        if (expandedSection == 1) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(12.dp),
                ) {
                    OutlinedTextField(
                        value = clientId,
                        onValueChange = { clientId = it },
                        label = { Text(s[Str.OAUTH_CLIENT_ID_LABEL]) },
                        placeholder = { Text("YOUR_CLIENT_ID.apps.googleusercontent.com") },
                        singleLine = true,
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = clientSecret,
                        onValueChange = { clientSecret = it },
                        label = { Text(s[Str.CLIENT_SECRET_REQUIRED_LABEL]) },
                        placeholder = { Text("YOUR_CLIENT_SECRET") },
                        singleLine = true,
                        enabled = !isBusy,
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showSecret = !showSecret }) {
                                Icon(
                                    if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = s[Str.TOGGLE_CLIENT_SECRET_VISIBILITY],
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            onStartBrowserSignIn(clientId, clientSecret, displayName, startFolder)
                        },
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(s[Str.OPEN_BROWSER_SIGN_IN])
                    }
                }
            }
        }

        // Mode 3: Paste Token / JSON
        TextButton(
            onClick = { expandedSection = if (expandedSection == 2) 0 else 2 },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(s[Str.PASTE_TOKEN_JSON])
        }

        if (expandedSection == 2) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(12.dp),
                ) {
                    Text(
                        text = s[Str.PASTE_TOKEN_HINT],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = pastedText,
                        onValueChange = { pastedText = it },
                        label = { Text(s[Str.TOKEN_OR_JSON]) },
                        minLines = 3,
                        maxLines = 6,
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (parsedPastePreview != null && parsedPastePreview.refreshToken.isBlank()) {
                        Text(
                            text = s[Str.NO_REFRESH_TOKEN_WARN],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    OutlinedTextField(
                        value = pasteClientId,
                        onValueChange = { pasteClientId = it },
                        label = { Text(s[Str.CLIENT_ID_OPTIONAL_LABEL]) },
                        placeholder = { Text("YOUR_CLIENT_ID.apps.googleusercontent.com") },
                        singleLine = true,
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pasteClientSecret,
                        onValueChange = { pasteClientSecret = it },
                        label = { Text(s[Str.CLIENT_SECRET_OPTIONAL_LABEL]) },
                        placeholder = { Text("YOUR_CLIENT_SECRET") },
                        singleLine = true,
                        enabled = !isBusy,
                        visualTransformation = if (showPasteSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPasteSecret = !showPasteSecret }) {
                                Icon(
                                    if (showPasteSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = s[Str.TOGGLE_CLIENT_SECRET_VISIBILITY],
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            onSubmitPastedToken(
                                pastedText,
                                pasteClientId,
                                pasteClientSecret,
                                displayName,
                                startFolder,
                            )
                        },
                        enabled = !isBusy && pastedText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(s[Str.VERIFY_AND_CONNECT])
                    }
                }
            }
        }

        // Live sign-in status strip
        when (signInState) {
            GDriveSignInState.Idle -> Unit

            is GDriveSignInState.WaitingForBrowser -> {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = s[Str.WAITING_FOR_BROWSER],
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Text(
                            text = s[Str.COMPLETE_SIGN_IN_BROWSER_HINT],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    UiFormatters.copyToSystemClipboard(context, s[Str.GOOGLE_SIGN_IN_URL], signInState.authUrl)
                                    onCopiedMessage(s[Str.COPIED_SIGN_IN_LINK])
                                },
                            ) {
                                Text(s[Str.COPY_LINK])
                            }
                            TextButton(onClick = onCancelSignIn) {
                                Text(s[Str.CANCEL])
                            }
                        }
                    }
                }
            }

            GDriveSignInState.Exchanging -> {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(s[Str.VERIFYING_WITH_GOOGLE], style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            is GDriveSignInState.Error -> {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Text(
                            text = signInState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (expandedSection == 1 && clientId.isNotBlank()) {
                                TextButton(
                                    onClick = {
                                        onStartBrowserSignIn(clientId, clientSecret, displayName, startFolder)
                                    },
                                ) {
                                    Text(s[Str.TRY_AGAIN])
                                }
                            }
                            if (signInState.authUrl != null) {
                                TextButton(
                                    onClick = {
                                        UiFormatters.copyToSystemClipboard(
                                            context,
                                            s[Str.GOOGLE_SIGN_IN_URL],
                                            signInState.authUrl,
                                        )
                                        onCopiedMessage(s[Str.COPIED_SIGN_IN_LINK])
                                    },
                                ) {
                                    Text(s[Str.COPY_LINK])
                                }
                            }
                        }
                    }
                }
            }

            is GDriveSignInState.Success -> Unit
        }
    }
}

@Composable
private fun GDriveOAuthSetupHelpCard(
    onCopy: (label: String, value: String) -> Unit,
) {
    val s = LocalAppStrings.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Text(
                text = s[Str.GCP_SETUP_TITLE],
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = s[Str.GCP_SETUP_STEP_1],
                style = MaterialTheme.typography.bodySmall,
            )
            CopyableMonoField(
                label = s[Str.PACKAGE_NAME],
                value = MINIFILE_APK_PACKAGE,
                onCopy = { onCopy(s[Str.PACKAGE_NAME], MINIFILE_APK_PACKAGE) },
            )
            CopyableMonoField(
                label = s[Str.CERTIFICATE_SHA1],
                value = MINIFILE_SIGNING_SHA1,
                onCopy = { onCopy(s[Str.SHA1_FINGERPRINT], MINIFILE_SIGNING_SHA1) },
            )
            Text(
                text = s[Str.GCP_SETUP_STEP_2],
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = s[Str.GCP_SETUP_NOTE],
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CopyableMonoField(
    label: String,
    value: String,
    onCopy: () -> Unit,
) {
    val s = LocalAppStrings.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy)
            .padding(vertical = 2.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = s.format(Str.COPY_LABEL, label),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun FtpServerForm(
    existing: FtpServerBookmark?,
    saveTrigger: Int,
    onValidatedSave: (
        name: String,
        host: String,
        port: Int,
        anonymous: Boolean,
        username: String,
        password: String,
        initialPath: String,
    ) -> Unit,
) {
    val s = LocalAppStrings.current
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var host by remember(existing?.id) { mutableStateOf(existing?.host ?: "") }
    var portText by remember(existing?.id) { mutableStateOf((existing?.port ?: 21).toString()) }
    var anonymous by remember(existing?.id) { mutableStateOf(existing?.anonymous ?: true) }
    var username by remember(existing?.id) {
        mutableStateOf(existing?.username?.takeIf { it != "anonymous" || existing.anonymous.not() } ?: "")
    }
    var password by remember(existing?.id) { mutableStateOf(existing?.password ?: "") }
    var showPassword by remember { mutableStateOf(false) }
    var initialPath by remember(existing?.id) { mutableStateOf(existing?.initialPath ?: "/") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(saveTrigger) {
        if (saveTrigger <= 0) return@LaunchedEffect
        val cleanHost = host.trim()
            .removePrefix("ftp://")
            .substringBefore('/')
            .substringBefore(':')
        if (cleanHost.isEmpty()) {
            errorMsg = s[Str.ERR_ENTER_VALID_HOST]
            return@LaunchedEffect
        }
        val parsedPort = portText.trim().toIntOrNull()
        if (parsedPort == null || parsedPort !in 1..65535) {
            errorMsg = s[Str.ERR_PORT_RANGE_1_65535]
            return@LaunchedEffect
        }
        if (!anonymous && username.trim().isEmpty()) {
            errorMsg = s[Str.ERR_ENTER_USER_OR_ANON]
            return@LaunchedEffect
        }
        val cleanName = name.trim().ifEmpty { cleanHost }
        val cleanPath = PathUtils.normalizeLocalPath(initialPath.trim().ifEmpty { "/" })
        onValidatedSave(cleanName, cleanHost, parsedPort, anonymous, username.trim(), password, cleanPath)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(s[Str.NAME_OPTIONAL]) },
            placeholder = { Text(s[Str.MY_NAS_PC]) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = host,
            onValueChange = {
                host = it
                errorMsg = null
            },
            label = { Text(s[Str.HOST_IP]) },
            placeholder = { Text("192.168.1.100") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = portText,
            onValueChange = {
                portText = it
                errorMsg = null
            },
            label = { Text(s[Str.PORT]) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = initialPath,
            onValueChange = { initialPath = it },
            label = { Text(s[Str.REMOTE_PATH]) },
            placeholder = { Text("/") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { anonymous = !anonymous },
        ) {
            Text(s[Str.ANONYMOUS_LOGIN])
            Switch(checked = anonymous, onCheckedChange = { anonymous = it })
        }
        if (!anonymous) {
            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it
                    errorMsg = null
                },
                label = { Text(s[Str.USERNAME]) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(s[Str.PASSWORD]) },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = s[Str.TOGGLE_PASSWORD_VISIBILITY],
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (errorMsg != null) {
            Text(
                text = errorMsg!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
fun AddEditFtpServerDialog(
    existing: FtpServerBookmark?,
    onSave: (
        name: String,
        host: String,
        port: Int,
        anonymous: Boolean,
        username: String,
        password: String,
        initialPath: String,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    var saveTrigger by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) s[Str.ADD_FTP_SERVER] else s[Str.EDIT_FTP_SERVER]) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                FtpServerForm(
                    existing = existing,
                    saveTrigger = saveTrigger,
                    onValidatedSave = onSave,
                )
            }
        },
        confirmButton = {
            Button(onClick = { saveTrigger++ }) {
                Text(s[Str.SAVE])
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s[Str.CANCEL]) }
        },
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun VolumeRow(
    volume: Volume,
    icon: ImageVector,
    context: android.content.Context,
    onClick: () -> Unit,
    onMountClick: (() -> Unit)?,
) {
    val s = LocalAppStrings.current
    val hasSpace = volume.totalBytes > 0L && volume.freeBytes >= 0L
    val usedBytes = (volume.totalBytes - volume.freeBytes).coerceAtLeast(0L)
    val usedRatio = if (hasSpace) (usedBytes.toFloat() / volume.totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    val displayVolumeName = if (volume.kind == VolumeKind.PRIMARY) s[Str.INTERNAL_STORAGE] else volume.name

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(displayVolumeName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                val spaceText = if (hasSpace) {
                    s.format(Str.SPACE_FREE_OF, UiFormatters.formatSize(context, volume.freeBytes), UiFormatters.formatSize(context, volume.totalBytes))
                } else {
                    volume.subtitle ?: s[Str.TAP_TO_BROWSE]
                }
                Text(
                    text = spaceText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onMountClick != null) {
                OutlinedButton(onClick = onMountClick) {
                    Text(s[Str.MOUNT])
                }
            }
        }
        if (hasSpace) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { usedRatio },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}
