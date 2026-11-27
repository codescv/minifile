package com.minifile.explorer.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.minifile.explorer.BuildConfig
import com.minifile.explorer.core.diag.DiagnosticExport
import com.minifile.explorer.core.feedback.AgentFeedbackBuilder
import com.minifile.explorer.core.feedback.BuiltAgentFeedback
import com.minifile.explorer.core.feedback.FeedbackEmailTargetResolver
import com.minifile.explorer.core.feedback.FeedbackMetadata
import com.minifile.explorer.core.feedback.FeedbackSendIntentSpec
import com.minifile.explorer.core.feedback.ResolvedEmailActivity
import com.minifile.explorer.core.gallery.MediaExportFormat
import com.minifile.explorer.core.i18n.AppI18n
import com.minifile.explorer.core.i18n.AppLanguage
import com.minifile.explorer.core.i18n.Str
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.data.AndroidVolumeProvider
import com.minifile.explorer.data.update.InstallLaunchResult
import com.minifile.explorer.data.update.UpdateUiState
import com.minifile.explorer.ui.MainViewModel
import com.minifile.explorer.ui.i18n.LocalAppStrings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_FEEDBACK_ATTACHMENT_BYTES = 25L * 1024L * 1024L

private data class StagedScreenshot(
    val shareUri: Uri,
    val displayName: String,
    val mimeType: String,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ToolsScreen(
    viewModel: MainViewModel,
    onGrantAllFilesAccess: () -> Unit,
    onRequestUnknownSourcesPermission: (Intent) -> Unit,
) {
    val context = LocalContext.current
    val s = LocalAppStrings.current
    val scope = rememberCoroutineScope()

    val settings by viewModel.settings.collectAsState()
    val updateState by viewModel.container.updateManager.state.collectAsState()
    var showNomediaScanner by remember { mutableStateOf(false) }
    var showFeedbackDialog by rememberSaveable { mutableStateOf(false) }

    var emailDraft by rememberSaveable(settings.agentFeedbackEmail) {
        mutableStateOf(settings.agentFeedbackEmail)
    }
    var feedbackTitle by rememberSaveable { mutableStateOf("") }
    var feedbackText by rememberSaveable { mutableStateOf("") }
    var attachedImageUri by remember { mutableStateOf<Uri?>(null) }
    var attachedImageName by remember { mutableStateOf<String?>(null) }
    var sendingFeedback by remember { mutableStateOf(false) }

    val screenshotPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { pickedUri ->
        if (pickedUri != null) {
            attachedImageUri = pickedUri
            attachedImageName = resolvePickedDisplayName(context, pickedUri)
        }
    }

    val hasAllFiles = AndroidVolumeProvider.hasAllFilesAccess(context)
    val diagnostics = viewModel.container.diagnostics
    val diagStats by diagnostics.stats.collectAsState()
    var diagBusy by remember { mutableStateOf(false) }

    // v1.0.16: one tap on "Download & Install" downloads, verifies (SHA-256 + signature) and launches the
    // system installer directly; "Install update" stays available to re-trigger if the installer is dismissed.
    fun triggerInstall() {
        when (val res = viewModel.container.updateManager.launchInstaller(context)) {
            InstallLaunchResult.Launched -> Unit
            is InstallLaunchResult.NeedsUnknownSourcesPermission -> onRequestUnknownSourcesPermission(res.settingsIntent)
            is InstallLaunchResult.Failed -> viewModel.emitSimpleMessage(res.message)
        }
    }

    val normalizedDraftEmail = AgentFeedbackBuilder.normalizeEmail(emailDraft)
    val isEmailValid = AgentFeedbackBuilder.isValidEmail(normalizedDraftEmail)
    val isEmailDirty = normalizedDraftEmail != settings.agentFeedbackEmail
    val versionTag = AgentFeedbackBuilder.normalizeVersionTag(BuildConfig.VERSION_NAME)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s[Str.TAB_SETTINGS], fontWeight = FontWeight.SemiBold) },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 4.dp,
                bottom = innerPadding.calculateBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // 0. Language (System Default / 中文 / English / 日本語 — applies immediately)
            item(key = "language") {
                SectionLabel(s[Str.SETTINGS_SECTION_LANGUAGE])
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        val subtitle = if (settings.appLanguage == AppLanguage.SYSTEM) {
                            s.format(Str.LANGUAGE_FOLLOWING_SYSTEM, s.language.nativeName)
                        } else {
                            s[Str.LANGUAGE_CURRENT_HINT]
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val options = listOf(
                            AppLanguage.SYSTEM to s[Str.LANGUAGE_SYSTEM_DEFAULT],
                            AppLanguage.ZH to s[Str.LANGUAGE_ZH],
                            AppLanguage.EN to s[Str.LANGUAGE_EN],
                            AppLanguage.JA to s[Str.LANGUAGE_JA],
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            for ((langOption, label) in options) {
                                FilterChip(
                                    selected = settings.appLanguage == langOption,
                                    onClick = {
                                        viewModel.container.settingsRepository.setAppLanguage(langOption)
                                    },
                                    label = { Text(label, maxLines = 1, softWrap = false) },
                                )
                            }
                        }
                    }
                }
            }

            // 1. Storage Access (All-files access only)
            item(key = "storage-access") {
                SectionLabel(s[Str.STORAGE_ACCESS])
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(s[Str.ALL_FILES_ACCESS]) },
                            supportingContent = {
                                Text(if (hasAllFiles) s[Str.GRANTED] else s[Str.NOT_GRANTED])
                            },
                            trailingContent = {
                                OutlinedButton(onClick = onGrantAllFilesAccess) {
                                    Text(if (hasAllFiles) s[Str.MANAGE] else s[Str.GRANT])
                                }
                            },
                        )
                    }
                }
            }

            // 1.5 Photos / Gallery preferences (spec §3.3 & §4.5)
            item(key = "photos-settings") {
                SectionLabel(s[Str.SETTINGS_SECTION_PHOTOS])
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(s[Str.SETTINGS_SHOW_RECENT_HOME]) },
                            supportingContent = { Text(s[Str.SETTINGS_SHOW_RECENT_HOME_DESC]) },
                            trailingContent = {
                                Switch(
                                    checked = settings.galleryShowRecentOnHome,
                                    onCheckedChange = { on ->
                                        viewModel.container.settingsRepository.update { it.copy(galleryShowRecentOnHome = on) }
                                    },
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.container.settingsRepository.update {
                                    it.copy(galleryShowRecentOnHome = !it.galleryShowRecentOnHome)
                                }
                            },
                        )
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(s[Str.SETTINGS_AUTO_OPEN_MEDIA]) },
                            supportingContent = { Text(s[Str.SETTINGS_AUTO_OPEN_MEDIA_DESC]) },
                            trailingContent = {
                                Switch(
                                    checked = settings.galleryAutoOpenMediaFolders,
                                    onCheckedChange = { on ->
                                        viewModel.container.settingsRepository.update { it.copy(galleryAutoOpenMediaFolders = on) }
                                    },
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.container.settingsRepository.update {
                                    it.copy(galleryAutoOpenMediaFolders = !it.galleryAutoOpenMediaFolders)
                                }
                            },
                        )
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(s[Str.SETTINGS_RESET_FOLDER_PREFS]) },
                            supportingContent = { Text(s[Str.SETTINGS_RESET_FOLDER_PREFS_DESC]) },
                            modifier = Modifier.clickable {
                                viewModel.galleryController.resetFolderModePreferences()
                                viewModel.emitSimpleMessage(s[Str.SETTINGS_FOLDER_PREFS_RESET_DONE])
                            },
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = s[Str.SETTINGS_DEFAULT_EXPORT_FORMAT],
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Spacer(Modifier.height(6.dp))
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                SegmentedButton(
                                    selected = settings.galleryExportFormat == MediaExportFormat.ORIGINAL,
                                    onClick = { viewModel.galleryController.setExportFormat(MediaExportFormat.ORIGINAL) },
                                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                                ) {
                                    Text(s[Str.GALLERY_FORMAT_ORIGINAL])
                                }
                                SegmentedButton(
                                    selected = settings.galleryExportFormat == MediaExportFormat.MOST_COMPATIBLE,
                                    onClick = { viewModel.galleryController.setExportFormat(MediaExportFormat.MOST_COMPATIBLE) },
                                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                                ) {
                                    Text(s[Str.GALLERY_FORMAT_COMPATIBLE])
                                }
                            }
                        }
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(s[Str.SETTINGS_REMOVE_LOCATION_DEFAULT]) },
                            supportingContent = { Text(s[Str.SETTINGS_REMOVE_LOCATION_DEFAULT_DESC]) },
                            trailingContent = {
                                Switch(
                                    checked = settings.galleryRemoveLocationOnExport,
                                    onCheckedChange = { on ->
                                        viewModel.galleryController.setRemoveLocationOnExport(on)
                                    },
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.galleryController.setRemoveLocationOnExport(!settings.galleryRemoveLocationOnExport)
                            },
                        )
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(s[Str.SETTINGS_ONLINE_PLACE_NAMES]) },
                            supportingContent = {
                                Text(
                                    if (settings.galleryOnlinePlaceNames) {
                                        s[Str.SETTINGS_ONLINE_PLACE_NAMES_DESC]
                                    } else {
                                        "${s[Str.SETTINGS_ONLINE_PLACE_NAMES_DESC]} · ${s[Str.SETTINGS_OFFLINE_PLACES_CREDIT]}"
                                    },
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = settings.galleryOnlinePlaceNames,
                                    onCheckedChange = { on ->
                                        viewModel.galleryController.setOnlinePlaceNames(on)
                                    },
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.galleryController.setOnlinePlaceNames(!settings.galleryOnlinePlaceNames)
                            },
                        )
                    }
                }
            }

            // 2. Utilities (.nomedia Overview only)
            item(key = "utilities") {
                SectionLabel(s[Str.UTILITIES])
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(s[Str.NOMEDIA_OVERVIEW]) },
                            supportingContent = { Text(s[Str.NOMEDIA_OVERVIEW_DESC]) },
                            modifier = Modifier.clickable { showNomediaScanner = true },
                        )
                    }
                }
            }

            // 3. Agent Feedback (unified single-language UI)
            item(key = "agent-feedback") {
                SectionLabel(s[Str.AGENT_FEEDBACK_SECTION])
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(s[Str.AGENT_FEEDBACK_TITLE]) },
                        supportingContent = {
                            Text(
                                if (settings.agentFeedbackEmail.isNotEmpty()) {
                                    s.format(Str.AGENT_FEEDBACK_CONFIGURED, settings.agentFeedbackEmail)
                                } else {
                                    s[Str.AGENT_FEEDBACK_SUBTITLE]
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Button(onClick = { showFeedbackDialog = true }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(s[Str.AGENT_FEEDBACK_COMPOSE], maxLines = 1, softWrap = false)
                            }
                        },
                        modifier = Modifier.clickable { showFeedbackDialog = true },
                    )
                }
            }

            // 4. Diagnostics (toggleable rolling transfer / network log with copy, export and clear)
            item(key = "diagnostics") {
                SectionLabel(s[Str.DIAGNOSTICS])
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val logBytes = diagStats.totalBytes
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(s[Str.DIAGNOSTIC_LOGGING]) },
                            supportingContent = {
                                Text(
                                    DiagnosticExport.statusLine(settings.diagnosticLoggingEnabled, logBytes, s),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = settings.diagnosticLoggingEnabled,
                                    onCheckedChange = { on -> diagnostics.setEnabled(on) },
                                )
                            },
                            modifier = Modifier.clickable { diagnostics.setEnabled(!settings.diagnosticLoggingEnabled) },
                        )
                        val canExport = !diagBusy && (logBytes > 0L || settings.diagnosticLoggingEnabled)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        ) {
                            OutlinedButton(
                                enabled = canExport,
                                onClick = {
                                    diagBusy = true
                                    scope.launch {
                                        try {
                                            val text = diagnostics.clipboardText()
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            val copied = cm != null && runCatching {
                                                cm.setPrimaryClip(ClipData.newPlainText(s[Str.DIAG_CLIPBOARD_LABEL], text))
                                            }.isSuccess
                                            viewModel.emitSimpleMessage(
                                                if (copied) s[Str.DIAG_LOGS_COPIED] else s[Str.DIAG_LOGS_COPY_FAILED],
                                            )
                                        } finally {
                                            diagBusy = false
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(s[Str.COPY_LOGS], maxLines = 1, softWrap = false)
                            }
                            OutlinedButton(
                                enabled = canExport,
                                onClick = {
                                    diagBusy = true
                                    scope.launch {
                                        try {
                                            val file = diagnostics.writeExportFile()
                                            val chooser = diagnostics.shareIntent(file)
                                            if (!context.tryStartActivity(chooser)) {
                                                viewModel.emitSimpleMessage(s[Str.DIAG_NO_SHARE_APP])
                                            }
                                        } catch (e: IOException) {
                                            viewModel.emitSimpleMessage(s.format(Str.DIAG_EXPORT_FAILED, e.message ?: ""))
                                        } catch (e: IllegalArgumentException) {
                                            viewModel.emitSimpleMessage(s.format(Str.DIAG_EXPORT_FAILED, e.message ?: ""))
                                        } finally {
                                            diagBusy = false
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(s[Str.EXPORT_LOGS], maxLines = 1, softWrap = false)
                            }
                            TextButton(
                                enabled = !diagBusy && logBytes > 0L,
                                onClick = {
                                    diagBusy = true
                                    scope.launch {
                                        try {
                                            diagnostics.clear()
                                            viewModel.emitSimpleMessage(s[Str.DIAG_LOGS_CLEARED])
                                        } finally {
                                            diagBusy = false
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(s[Str.CLEAR], maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }

            // 5. About (with integrated Update check & install)
            item(key = "about") {
                SectionLabel(s[Str.ABOUT])
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Text(
                            "${s[Str.APP_NAME]} ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            s[Str.ABOUT_DESCRIPTION],
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = {
                                val open = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/codescv/minifile"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(open) }
                            },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
                        ) {
                            Text("github.com/codescv/minifile")
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        }

                        when (val st = updateState) {
                            UpdateUiState.Idle -> Unit

                            UpdateUiState.Checking -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(s[Str.CHECKING], style = MaterialTheme.typography.bodySmall)
                                }
                            }

                            is UpdateUiState.UpToDate -> {
                                Text(
                                    s[Str.UP_TO_DATE],
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                            }

                            is UpdateUiState.Available -> {
                                val rawTag = st.release.tagName.trim()
                                val tagLabel = if (rawTag.startsWith("v", ignoreCase = true)) rawTag else "v$rawTag"
                                Text(
                                    s.format(Str.NEW_VERSION_AVAILABLE, tagLabel),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }

                            is UpdateUiState.Downloading -> {
                                val frac = if (st.totalBytes > 0L) {
                                    (st.downloadedBytes.toFloat() / st.totalBytes.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    null
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(s[Str.DOWNLOADING], style = MaterialTheme.typography.bodySmall)
                                        Spacer(Modifier.height(4.dp))
                                        if (frac != null) {
                                            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                                        } else {
                                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                        }
                                    }
                                    IconButton(onClick = { viewModel.container.updateManager.cancel() }) {
                                        Icon(Icons.Filled.Close, contentDescription = s[Str.CANCEL_DOWNLOAD])
                                    }
                                }
                            }

                            is UpdateUiState.Verifying -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(s[Str.VERIFYING], style = MaterialTheme.typography.bodySmall)
                                }
                            }

                            is UpdateUiState.Ready -> {
                                val rawTag = st.release.tagName.trim()
                                val tagLabel = if (rawTag.startsWith("v", ignoreCase = true)) rawTag else "v$rawTag"
                                Text(
                                    s.format(Str.NEW_VERSION_READY, tagLabel),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    s[Str.UPDATE_READY_HINT],
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            is UpdateUiState.Error -> {
                                Text(
                                    st.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.container.updateManager.check(force = true) },
                                enabled = updateState !is UpdateUiState.Checking &&
                                    updateState !is UpdateUiState.Downloading &&
                                    updateState !is UpdateUiState.Verifying,
                            ) {
                                Text(s[Str.CHECK_FOR_UPDATES], maxLines = 1, softWrap = false)
                            }

                            if (updateState is UpdateUiState.Available || updateState is UpdateUiState.Ready) {
                                Button(
                                    onClick = {
                                        when (updateState) {
                                            is UpdateUiState.Available -> {
                                                viewModel.container.updateManager.download(
                                                    onReadyToInstall = { triggerInstall() },
                                                )
                                            }
                                            is UpdateUiState.Ready -> triggerInstall()
                                            else -> Unit
                                        }
                                    },
                                ) {
                                    Text(
                                        if (updateState is UpdateUiState.Ready) s[Str.INSTALL_UPDATE] else s[Str.DOWNLOAD_AND_INSTALL],
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFeedbackDialog) {
        AlertDialog(
            onDismissRequest = { if (!sendingFeedback) showFeedbackDialog = false },
            title = { Text(s[Str.AGENT_FEEDBACK_TITLE]) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        s.format(Str.AGENT_FEEDBACK_DIALOG_DESC, AgentFeedbackBuilder.PROJECT_PREFIX, versionTag),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedTextField(
                        value = emailDraft,
                        onValueChange = { emailDraft = it },
                        label = { Text(s[Str.AGENT_FEEDBACK_EMAIL_LABEL]) },
                        placeholder = { Text(AgentFeedbackBuilder.EMAIL_PLACEHOLDER) },
                        singleLine = true,
                        isError = normalizedDraftEmail.isNotEmpty() && !isEmailValid,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Done,
                        ),
                        trailingIcon = {
                            if (isEmailDirty && (normalizedDraftEmail.isEmpty() || isEmailValid)) {
                                TextButton(
                                    onClick = {
                                        viewModel.container.settingsRepository.setAgentFeedbackEmail(normalizedDraftEmail)
                                        viewModel.emitSimpleMessage(
                                            if (normalizedDraftEmail.isEmpty()) {
                                                s[Str.AGENT_FEEDBACK_EMAIL_CLEARED]
                                            } else {
                                                s[Str.AGENT_FEEDBACK_EMAIL_SAVED]
                                            },
                                        )
                                    },
                                ) {
                                    Text(s[Str.SAVE])
                                }
                            }
                        },
                        supportingText = {
                            when {
                                normalizedDraftEmail.isNotEmpty() && !isEmailValid -> {
                                    Text(s.format(Str.AGENT_FEEDBACK_EMAIL_INVALID_HINT, AgentFeedbackBuilder.EMAIL_PLACEHOLDER))
                                }
                                isEmailDirty -> {
                                    Text(s[Str.AGENT_FEEDBACK_EMAIL_DIRTY_HINT])
                                }
                                settings.agentFeedbackEmail.isNotEmpty() -> {
                                    Text(s[Str.AGENT_FEEDBACK_EMAIL_SAVED_HINT])
                                }
                                else -> {
                                    Text(s[Str.AGENT_FEEDBACK_EMAIL_UNSET_HINT])
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = feedbackTitle,
                        onValueChange = { feedbackTitle = it },
                        label = { Text(s[Str.AGENT_FEEDBACK_TITLE_LABEL]) },
                        placeholder = { Text(s[Str.AGENT_FEEDBACK_TITLE_PLACEHOLDER]) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = feedbackText,
                        onValueChange = { feedbackText = it },
                        label = { Text(s[Str.AGENT_FEEDBACK_DESC_LABEL]) },
                        placeholder = { Text(s[Str.AGENT_FEEDBACK_DESC_PLACEHOLDER]) },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (attachedImageUri != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = attachedImageName ?: "screenshot.png",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = {
                                        attachedImageUri = null
                                        attachedImageName = null
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = s[Str.AGENT_FEEDBACK_REMOVE_IMAGE],
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { screenshotPickerLauncher.launch("image/*") },
                        enabled = !sendingFeedback,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Image,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (attachedImageUri == null) s[Str.AGENT_FEEDBACK_ADD_IMAGE] else s[Str.AGENT_FEEDBACK_CHANGE_IMAGE],
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targetEmail = AgentFeedbackBuilder.normalizeEmail(emailDraft)
                        if (!AgentFeedbackBuilder.isValidEmail(targetEmail)) {
                            viewModel.emitSimpleMessage(s[Str.AGENT_FEEDBACK_ENTER_VALID_EMAIL])
                            return@Button
                        }
                        viewModel.container.settingsRepository.setAgentFeedbackEmail(targetEmail)
                        val currentTitle = feedbackTitle
                        val currentFeedback = feedbackText
                        val currentUri = attachedImageUri
                        val currentAttachmentName = attachedImageName
                        sendingFeedback = true
                        scope.launch {
                            val result = runCatching {
                                val staged = if (currentUri != null) {
                                    withContext(Dispatchers.IO) {
                                        stageScreenshotForShare(
                                            context = context,
                                            sourceUri = currentUri,
                                            preferredName = currentAttachmentName,
                                        )
                                    }
                                } else {
                                    null
                                }
                                val metadata = FeedbackMetadata(
                                    appName = "Files",
                                    packageName = context.packageName,
                                    versionName = BuildConfig.VERSION_NAME,
                                    versionCode = BuildConfig.VERSION_CODE,
                                    deviceManufacturer = Build.MANUFACTURER.orEmpty(),
                                    deviceModel = Build.MODEL.orEmpty(),
                                    androidRelease = Build.VERSION.RELEASE.orEmpty(),
                                    sdkInt = Build.VERSION.SDK_INT,
                                )
                                val built = AgentFeedbackBuilder.build(
                                    recipientEmail = targetEmail,
                                    feedbackText = currentFeedback,
                                    metadata = metadata,
                                    screenshotName = staged?.displayName,
                                    title = currentTitle,
                                    strings = s,
                                )
                                launchFeedbackEmailIntent(
                                    context = context,
                                    built = built,
                                    stagedScreenshot = staged,
                                )
                            }
                            sendingFeedback = false
                            result.onSuccess {
                                feedbackTitle = ""
                                feedbackText = ""
                                attachedImageUri = null
                                attachedImageName = null
                                showFeedbackDialog = false
                            }.onFailure { err ->
                                viewModel.emitSimpleMessage(
                                    s.format(
                                        Str.AGENT_FEEDBACK_OPEN_EMAIL_FAILED,
                                        err.message ?: s[Str.AGENT_FEEDBACK_NO_EMAIL_CLIENT],
                                    ),
                                )
                            }
                        }
                    },
                    enabled = isEmailValid &&
                        (feedbackTitle.isNotBlank() || feedbackText.isNotBlank() || attachedImageUri != null) &&
                        !sendingFeedback,
                ) {
                    if (sendingFeedback) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(s[Str.AGENT_FEEDBACK_SEND_BTN], maxLines = 1, softWrap = false)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showFeedbackDialog = false },
                    enabled = !sendingFeedback,
                ) {
                    Text(s[Str.CANCEL])
                }
            },
        )
    }

    if (showNomediaScanner) {
        var scanning by remember { mutableStateOf(true) }
        var foundDirs by remember { mutableStateOf<List<VfsPath>>(emptyList()) }

        LaunchedEffect(Unit) {
            scanning = true
            val root = VfsPath.Local(Environment.getExternalStorageDirectory().absolutePath)
            foundDirs = withContext(Dispatchers.IO) {
                runCatching { viewModel.container.nomediaManager.scan(root, maxDepth = 5) }.getOrDefault(emptyList())
            }
            scanning = false
        }

        AlertDialog(
            onDismissRequest = { showNomediaScanner = false },
            title = { Text(s[Str.NOMEDIA_OVERVIEW]) },
            text = {
                if (scanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(s[Str.SCANNING_NOMEDIA])
                    }
                } else if (foundDirs.isEmpty()) {
                    Text(s[Str.NO_NOMEDIA_FOUND])
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(foundDirs, key = { it.serialize() }) { dirPath ->
                            ListItem(
                                headlineContent = { Text(dirPath.displayName) },
                                supportingContent = {
                                    Text((dirPath as? VfsPath.Local)?.normalized ?: dirPath.serialize(), maxLines = 1)
                                },
                                trailingContent = {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    viewModel.container.nomediaManager.set(dirPath, false)
                                                }
                                                foundDirs = foundDirs.filterNot { it == dirPath }
                                            }
                                        },
                                    ) {
                                        Text(s[Str.UNHIDE])
                                    }
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showNomediaScanner = false }) { Text(s[Str.CLOSE]) }
            },
        )
    }
}

private fun resolvePickedDisplayName(context: Context, uri: Uri): String {
    val queried = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
    val raw = queried?.trim()?.takeIf { it.isNotEmpty() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.trim()?.takeIf { it.isNotEmpty() }
        ?: "screenshot.png"
    return sanitizeAttachmentFileName(raw)
}

private fun sanitizeAttachmentFileName(raw: String): String {
    val cleaned = raw
        .replace('/', '_')
        .replace('\\', '_')
        .replace(Regex("""[\u0000-\u001F\u007F]"""), "")
        .trim()
        .trimStart('.')
    if (cleaned.isEmpty()) return "screenshot.png"
    return if (cleaned.contains('.')) cleaned.take(96) else "${cleaned.take(88)}.png"
}

private fun stageScreenshotForShare(
    context: Context,
    sourceUri: Uri,
    preferredName: String?,
): StagedScreenshot {
    val dir = File(context.cacheDir, "feedback_cache").apply { mkdirs() }
    dir.listFiles()?.forEach { existing ->
        runCatching { existing.delete() }
    }
    val safeName = sanitizeAttachmentFileName(preferredName ?: resolvePickedDisplayName(context, sourceUri))
    val target = File(dir, safeName)
    val mimeType = context.contentResolver.getType(sourceUri)
        ?.takeIf { it.startsWith("image/", ignoreCase = true) }
        ?: "image/*"

    val input = context.contentResolver.openInputStream(sourceUri)
        ?: throw IOException(AppI18n.current()[Str.AGENT_FEEDBACK_READ_IMAGE_FAILED])
    input.use { ins ->
        target.outputStream().use { outs ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                if (n == 0) continue
                total += n
                if (total > MAX_FEEDBACK_ATTACHMENT_BYTES) {
                    outs.close()
                    target.delete()
                    throw IOException(AppI18n.current()[Str.AGENT_FEEDBACK_IMAGE_TOO_LARGE])
                }
                outs.write(buf, 0, n)
            }
            outs.flush()
        }
    }

    val shareUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        target,
    )
    return StagedScreenshot(
        shareUri = shareUri,
        displayName = safeName,
        mimeType = mimeType,
    )
}

/**
 * Opens the feedback email (`v1.0.16`).
 *
 * With an image attached, Android 15–17 / ColorOS rejects `ACTION_SEND(image/...)` + `mailto:` selector
 * intents (`MATCH_NO_TYPE`) or hijacks them into the generic `分享1张图片` share sheet. Instead, probe
 * `mailto:` handlers, resolve each email app's own `ACTION_SEND` compose activity via
 * [FeedbackEmailTargetResolver] (default app > Gmail > other `mailto:` apps, system resolvers filtered),
 * grant URI read permission, and start an explicit-component `ACTION_SEND` without selector so the
 * email compose page opens directly. The system chooser is only a last resort.
 */
private fun launchFeedbackEmailIntent(
    context: Context,
    built: BuiltAgentFeedback,
    stagedScreenshot: StagedScreenshot?,
) {
    val pm = context.packageManager
    val mailtoProbes = listOf(
        Intent(Intent.ACTION_SENDTO, Uri.parse(built.mailtoUri)),
        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")),
    )
    val defaultMailto = mailtoProbes.firstNotNullOfOrNull { probe ->
        runCatching { pm.resolveDefaultEmailActivity(probe) }.getOrNull()
    }
    val mailtoActivities = mailtoProbes
        .flatMap { probe ->
            pm.queryEmailActivitiesSafe(probe, PackageManager.MATCH_DEFAULT_ONLY) +
                pm.queryEmailActivitiesSafe(probe, 0)
        }
        .distinct()

    if (stagedScreenshot == null) {
        fun buildSendTo(pkg: String?): Intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse(built.mailtoUri)
            if (pkg != null) setPackage(pkg)
            putExtra(Intent.EXTRA_EMAIL, arrayOf(built.recipientEmail))
            putExtra(Intent.EXTRA_SUBJECT, built.subject)
            putExtra(Intent.EXTRA_TEXT, built.body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (context.tryStartActivity(buildSendTo(null))) return
        val emailPackages = FeedbackEmailTargetResolver.orderEmailPackages(
            defaultMailto = defaultMailto,
            mailtoActivities = mailtoActivities,
            includeGmailIfInstalled = false,
        )
        for (pkg in emailPackages) {
            if (context.tryStartActivity(buildSendTo(pkg))) return
        }
        val fallback = Intent(Intent.ACTION_SEND).apply {
            type = FeedbackEmailTargetResolver.RFC822_MIME
            putExtra(Intent.EXTRA_EMAIL, arrayOf(built.recipientEmail))
            putExtra(Intent.EXTRA_SUBJECT, built.subject)
            putExtra(Intent.EXTRA_TEXT, built.body)
        }
        context.startActivity(
            Intent.createChooser(fallback, AppI18n.current()[Str.AGENT_FEEDBACK_CHOOSER_TITLE])
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return
    }

    val attachmentUri = stagedScreenshot.shareUri
    val targets = FeedbackEmailTargetResolver.resolveComposeTargets(
        defaultMailto = defaultMailto,
        mailtoActivities = mailtoActivities,
        attachmentMime = stagedScreenshot.mimeType,
    ) { pkg, mime ->
        val probe = Intent(Intent.ACTION_SEND).apply {
            type = mime
            setPackage(pkg)
        }
        pm.queryEmailActivitiesSafe(probe, PackageManager.MATCH_DEFAULT_ONLY)
            .ifEmpty { pm.queryEmailActivitiesSafe(probe, 0) }
    }

    for (target in targets) {
        val spec = FeedbackEmailTargetResolver.buildAttachmentSendSpec(
            payload = built,
            target = target,
            attachmentUri = attachmentUri.toString(),
        )
        runCatching {
            context.grantUriPermission(target.packageName, attachmentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val intent = spec.toIntent(attachmentUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (context.tryStartActivity(intent)) return
    }

    // Last resort only when no email client exposes a visible ACTION_SEND compose activity.
    val fallbackSend = Intent(Intent.ACTION_SEND).apply {
        type = stagedScreenshot.mimeType.ifBlank { FeedbackEmailTargetResolver.DEFAULT_IMAGE_MIME }
        putExtra(Intent.EXTRA_EMAIL, arrayOf(built.recipientEmail))
        putExtra(Intent.EXTRA_SUBJECT, built.subject)
        putExtra(Intent.EXTRA_TEXT, built.body)
        putExtra(Intent.EXTRA_STREAM, attachmentUri)
        clipData = ClipData.newRawUri(FeedbackEmailTargetResolver.CLIP_LABEL, attachmentUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(fallbackSend, AppI18n.current()[Str.AGENT_FEEDBACK_CHOOSER_TITLE])
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun FeedbackSendIntentSpec.toIntent(streamUri: Uri): Intent {
    val spec = this
    val intent = Intent(spec.action)
    val explicitClass = spec.className
    if (explicitClass != null) {
        intent.component = ComponentName(spec.packageName, explicitClass)
    } else {
        intent.setPackage(spec.packageName)
    }
    intent.type = spec.mimeType
    intent.putExtra(Intent.EXTRA_EMAIL, spec.recipients.toTypedArray())
    intent.putExtra(Intent.EXTRA_SUBJECT, spec.subject)
    intent.putExtra(Intent.EXTRA_TEXT, spec.text)
    intent.putExtra(Intent.EXTRA_STREAM, streamUri)
    intent.clipData = ClipData.newRawUri(spec.clipLabel, streamUri)
    if (spec.grantReadUriPermission) intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // spec.hasSelector is always false by contract: never set `selector` on an explicit ACTION_SEND.
    return intent
}

private fun Context.tryStartActivity(intent: Intent): Boolean =
    try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

private fun PackageManager.resolveDefaultEmailActivity(intent: Intent): ResolvedEmailActivity? {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
    } else {
        @Suppress("DEPRECATION")
        resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }
    return info?.toResolvedEmailActivity()
}

private fun PackageManager.queryEmailActivitiesSafe(intent: Intent, flags: Int): List<ResolvedEmailActivity> =
    runCatching {
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            queryIntentActivities(intent, flags)
        }
        infos.mapNotNull { it.toResolvedEmailActivity() }
    }.getOrDefault(emptyList())

private fun ResolveInfo.toResolvedEmailActivity(): ResolvedEmailActivity? {
    val ai = activityInfo ?: return null
    val pkg = ai.packageName ?: return null
    val name = ai.name ?: return null
    return ResolvedEmailActivity(packageName = pkg, activityName = name)
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}
