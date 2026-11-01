package com.minifile.explorer.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.minifile.explorer.core.feedback.AgentFeedbackBuilder
import com.minifile.explorer.core.feedback.BuiltAgentFeedback
import com.minifile.explorer.core.feedback.FeedbackMetadata
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.data.AndroidVolumeProvider
import com.minifile.explorer.data.update.InstallLaunchResult
import com.minifile.explorer.data.update.UpdateUiState
import com.minifile.explorer.ui.MainViewModel
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    viewModel: MainViewModel,
    onGrantAllFilesAccess: () -> Unit,
    onRequestUnknownSourcesPermission: (Intent) -> Unit,
) {
    val context = LocalContext.current
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
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
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
            // 1. Storage Access (All-files access only)
            item(key = "storage-access") {
                SectionLabel("Storage access")
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("All-files access") },
                            supportingContent = {
                                Text(if (hasAllFiles) "Granted" else "Not granted")
                            },
                            trailingContent = {
                                OutlinedButton(onClick = onGrantAllFilesAccess) {
                                    Text(if (hasAllFiles) "Manage" else "Grant")
                                }
                            },
                        )
                    }
                }
            }

            // 2. Utilities (.nomedia Overview only)
            item(key = "utilities") {
                SectionLabel("Utilities")
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(".nomedia Overview") },
                            supportingContent = { Text("Scan folders hidden from gallery and toggle in bulk") },
                            modifier = Modifier.clickable { showNomediaScanner = true },
                        )
                    }
                }
            }

            // 3. Agent Feedback (反馈给 Agent)
            item(key = "agent-feedback") {
                SectionLabel("Agent Feedback · 反馈给 Agent")
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text("反馈给 Agent") },
                        supportingContent = {
                            Text(
                                if (settings.agentFeedbackEmail.isNotEmpty()) {
                                    "已配置邮箱：${settings.agentFeedbackEmail}"
                                } else {
                                    "一键发送带版本与设备信息的反馈邮件"
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
                                Text("撰写反馈", maxLines = 1, softWrap = false)
                            }
                        },
                        modifier = Modifier.clickable { showFeedbackDialog = true },
                    )
                }
            }

            // 4. About (with integrated Update check & install)
            item(key = "about") {
                SectionLabel("About")
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
                            "Files ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Minimalist file manager with wireless HTTP/FTP transfer, PDF viewer, and PC Companion.",
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
                                    Text("Checking…", style = MaterialTheme.typography.bodySmall)
                                }
                            }

                            is UpdateUiState.UpToDate -> {
                                Text(
                                    "Up to date",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                            }

                            is UpdateUiState.Available -> {
                                val rawTag = st.release.tagName.trim()
                                val tagLabel = if (rawTag.startsWith("v", ignoreCase = true)) rawTag else "v$rawTag"
                                Text(
                                    "New version $tagLabel available",
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
                                        Text("Downloading…", style = MaterialTheme.typography.bodySmall)
                                        Spacer(Modifier.height(4.dp))
                                        if (frac != null) {
                                            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                                        } else {
                                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                        }
                                    }
                                    IconButton(onClick = { viewModel.container.updateManager.cancel() }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Cancel download")
                                    }
                                }
                            }

                            is UpdateUiState.Verifying -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Verifying…", style = MaterialTheme.typography.bodySmall)
                                }
                            }

                            is UpdateUiState.Ready -> {
                                val rawTag = st.release.tagName.trim()
                                val tagLabel = if (rawTag.startsWith("v", ignoreCase = true)) rawTag else "v$rawTag"
                                Text(
                                    "New version $tagLabel available",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
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
                                Text("Check for updates", maxLines = 1, softWrap = false)
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
                                    Text("Download & Install", maxLines = 1, softWrap = false)
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
            title = { Text("反馈给 Agent") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "配置本地 Agent 反馈邮箱后，可一键发送带版本与设备信息的任务邮件（主题自动附加 ${AgentFeedbackBuilder.PROJECT_PREFIX}: [$versionTag] 前缀）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedTextField(
                        value = emailDraft,
                        onValueChange = { emailDraft = it },
                        label = { Text("Agent 反馈邮箱") },
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
                                                "已清除 Agent 反馈邮箱"
                                            } else {
                                                "Agent 反馈邮箱已保存"
                                            },
                                        )
                                    },
                                ) {
                                    Text("保存")
                                }
                            }
                        },
                        supportingText = {
                            when {
                                normalizedDraftEmail.isNotEmpty() && !isEmailValid -> {
                                    Text("请输入有效的邮箱地址（如 ${AgentFeedbackBuilder.EMAIL_PLACEHOLDER}）")
                                }
                                isEmailDirty -> {
                                    Text("点击右侧“保存”或直接发送反馈时会自动保存在本机")
                                }
                                settings.agentFeedbackEmail.isNotEmpty() -> {
                                    Text("仅保存在本机 SharedPreferences")
                                }
                                else -> {
                                    Text("未设置，仅保存在本机 SharedPreferences")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = feedbackTitle,
                        onValueChange = { feedbackTitle = it },
                        label = { Text("标题") },
                        placeholder = { Text("简短概括问题或需求标题") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = feedbackText,
                        onValueChange = { feedbackText = it },
                        label = { Text("问题描述") },
                        placeholder = { Text("详细描述复现步骤、期望行为或改进建议") },
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
                                        contentDescription = "移除图片",
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
                            if (attachedImageUri == null) "增加图片" else "更换图片",
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
                            viewModel.emitSimpleMessage("请先填写有效的 Agent 反馈邮箱")
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
                                    "无法打开邮件应用：${err.message ?: "未找到可用的邮件客户端"}",
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
                    Text("发送反馈给 Agent", maxLines = 1, softWrap = false)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showFeedbackDialog = false },
                    enabled = !sendingFeedback,
                ) {
                    Text("取消")
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
            title = { Text(".nomedia Overview") },
            text = {
                if (scanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Scanning storage for .nomedia folders…")
                    }
                } else if (foundDirs.isEmpty()) {
                    Text("No folders with .nomedia found.")
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
                                        Text("Unhide")
                                    }
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showNomediaScanner = false }) { Text("Close") }
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
        ?: throw IOException("无法读取所选截图")
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
                    throw IOException("截图文件超过 25 MB 限制")
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

private fun launchFeedbackEmailIntent(
    context: Context,
    built: BuiltAgentFeedback,
    stagedScreenshot: StagedScreenshot?,
) {
    if (stagedScreenshot == null) {
        val sendTo = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse(built.mailtoUri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(sendTo)
        }.recoverCatching {
            val fallback = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(built.recipientEmail))
                putExtra(Intent.EXTRA_SUBJECT, built.subject)
                putExtra(Intent.EXTRA_TEXT, built.body)
            }
            context.startActivity(
                Intent.createChooser(fallback, "发送反馈给 Agent")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.getOrThrow()
    } else {
        fun buildSendWithStream(useMailtoSelector: Boolean): Intent =
            Intent(Intent.ACTION_SEND).apply {
                type = stagedScreenshot.mimeType
                putExtra(Intent.EXTRA_EMAIL, arrayOf(built.recipientEmail))
                putExtra(Intent.EXTRA_SUBJECT, built.subject)
                putExtra(Intent.EXTRA_TEXT, built.body)
                putExtra(Intent.EXTRA_STREAM, stagedScreenshot.shareUri)
                clipData = ClipData.newRawUri("screenshot", stagedScreenshot.shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (useMailtoSelector) {
                    selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                }
            }

        runCatching {
            context.startActivity(
                buildSendWithStream(useMailtoSelector = true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.recoverCatching {
            context.startActivity(
                Intent.createChooser(
                    buildSendWithStream(useMailtoSelector = false),
                    "发送反馈给 Agent",
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.getOrThrow()
    }
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
