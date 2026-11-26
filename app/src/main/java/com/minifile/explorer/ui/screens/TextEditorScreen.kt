package com.minifile.explorer.ui.screens

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minifile.explorer.core.i18n.Str
import com.minifile.explorer.core.model.MiniJson
import com.minifile.explorer.core.ops.BomKind
import com.minifile.explorer.core.ops.LineEnding
import com.minifile.explorer.core.ops.TextFileCodec
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.ui.MainViewModel
import com.minifile.explorer.ui.i18n.LocalAppStrings
import com.minifile.explorer.ui.util.UiFormatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset

private const val VIEWER_WARN_BYTES = 2L * 1024L * 1024L
private const val VIEWER_HARD_LIMIT_BYTES = 20L * 1024L * 1024L
private const val MAX_UNDO_STEPS = 120

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    initialPath: VfsPath,
    forceEditInitial: Boolean,
    viewModel: MainViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val s = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val browser by viewModel.browser.collectAsState()

    var activePath by remember { mutableStateOf(initialPath) }
    var fileSizeBytes by remember { mutableLongStateOf(0L) }
    var canWriteFile by remember { mutableStateOf(true) }
    var readOnlyViewerMode by remember { mutableStateOf(false) }
    var hardViewerOnly by remember { mutableStateOf(false) }

    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var charset by remember { mutableStateOf<Charset>(Charsets.UTF_8) }
    var bom by remember { mutableStateOf(BomKind.NONE) }
    var eol by remember { mutableStateOf(LineEnding.LF) }
    var savedText by remember { mutableStateOf("") }
    var tfv by remember { mutableStateOf(TextFieldValue("")) }

    val undoStack = remember { mutableStateListOf<String>() }
    val redoStack = remember { mutableStateListOf<String>() }

    var showFindBar by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var findCaseSensitive by remember { mutableStateOf(false) }
    var currentMatchIndex by remember { mutableIntStateOf(0) }

    var overflowExpanded by remember { mutableStateOf(false) }
    var showEncodingDialog by remember { mutableStateOf(false) }
    var showSaveAsDialog by remember { mutableStateOf(false) }
    var showUnsavedExitGuard by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    val isDirty = tfv.text != savedText

    fun loadFromDisk(overrideCharset: Charset? = null) {
        loading = true
        loadError = null
        scope.launch {
            try {
                val (statNode, decoded) = withContext(Dispatchers.IO) {
                    val st = viewModel.container.fileSystem.stat(activePath)
                    val dec = TextFileCodec.readFile(
                        fs = viewModel.container.fileSystem,
                        path = activePath,
                        overrideCharset = overrideCharset,
                        maxBytes = VIEWER_HARD_LIMIT_BYTES.toInt(),
                    )
                    st to dec
                }
                val sz = statNode?.size ?: decoded.text.length.toLong()
                fileSizeBytes = sz
                canWriteFile = statNode?.canWrite != false
                hardViewerOnly = sz > VIEWER_HARD_LIMIT_BYTES
                readOnlyViewerMode = !forceEditInitial && (sz > VIEWER_WARN_BYTES || !canWriteFile)

                charset = decoded.charset
                bom = decoded.bom
                eol = decoded.eol
                savedText = decoded.text
                tfv = TextFieldValue(
                    text = decoded.text,
                    selection = TextRange(decoded.text.length),
                )
                undoStack.clear()
                redoStack.clear()
                loading = false
            } catch (e: Exception) {
                loadError = e.message ?: s[Str.FAILED_READ_FILE]
                loading = false
            }
        }
    }

    LaunchedEffect(activePath) {
        loadFromDisk(null)
    }

    LaunchedEffect(loading, readOnlyViewerMode) {
        if (!loading && loadError == null && !readOnlyViewerMode) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    fun applyTextEdit(newValue: TextFieldValue) {
        if (newValue.text != tfv.text) {
            undoStack.add(tfv.text)
            if (undoStack.size > MAX_UNDO_STEPS) {
                undoStack.removeAt(0)
            }
            redoStack.clear()
        }
        tfv = newValue
    }

    fun performSave(closeAfter: Boolean = false) {
        if (!canWriteFile) {
            showSaveAsDialog = true
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    TextFileCodec.saveFile(
                        fs = viewModel.container.fileSystem,
                        path = activePath,
                        text = tfv.text,
                        charset = charset,
                        eol = eol,
                        bom = bom,
                    )
                }
                savedText = tfv.text
                fileSizeBytes = TextFileCodec.encode(tfv.text, charset, eol, bom).size.toLong()
                viewModel.emitSimpleMessage(s.format(Str.SAVED_FILE, activePath.displayName))
                if (closeAfter) {
                    onClose()
                }
            } catch (e: Exception) {
                viewModel.emitSimpleMessage(s.format(Str.SAVE_FAILED, e.message.orEmpty()))
            }
        }
    }

    fun requestExit() {
        if (isDirty) {
            showUnsavedExitGuard = true
        } else {
            onClose()
        }
    }

    BackHandler(enabled = true) {
        requestExit()
    }

    // Compute Find matches
    val matchRanges: List<IntRange> = remember(tfv.text, findQuery, findCaseSensitive, showFindBar) {
        if (!showFindBar || findQuery.isEmpty()) {
            emptyList()
        } else {
            val out = ArrayList<IntRange>()
            var start = 0
            while (start <= tfv.text.length - findQuery.length) {
                val idx = tfv.text.indexOf(findQuery, startIndex = start, ignoreCase = !findCaseSensitive)
                if (idx < 0) break
                out.add(idx until (idx + findQuery.length))
                start = idx + findQuery.length.coerceAtLeast(1)
            }
            out
        }
    }

    fun jumpToMatch(index: Int) {
        if (matchRanges.isEmpty()) return
        val wrapped = ((index % matchRanges.size) + matchRanges.size) % matchRanges.size
        currentMatchIndex = wrapped
        val r = matchRanges[wrapped]
        tfv = tfv.copy(selection = TextRange(r.first, r.last + 1))
    }

    val isJsonFile = PathUtils.extensionOf(activePath.displayName) == "json"

    // Compute cursor line & column
    val (cursorLine, cursorCol) = remember(tfv.text, tfv.selection) {
        val pos = tfv.selection.start.coerceIn(0, tfv.text.length)
        var ln = 1
        var lastNl = -1
        for (i in 0 until pos) {
            if (tfv.text[i] == '\n') {
                ln++
                lastNl = i
            }
        }
        ln to (pos - lastNl)
    }

    val lineCount = remember(tfv.text) {
        var c = 1
        for (ch in tfv.text) if (ch == '\n') c++
        c
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { requestExit() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s[Str.BACK])
                    }
                },
                title = {
                    val dirtyMark = if (isDirty) " ●" else ""
                    Text(
                        text = "${activePath.displayName}$dirtyMark",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    IconButton(onClick = { showFindBar = !showFindBar }) {
                        Icon(Icons.Filled.Search, contentDescription = s[Str.FIND])
                    }
                    IconButton(
                        onClick = {
                            if (undoStack.isNotEmpty()) {
                                val prev = undoStack.removeAt(undoStack.lastIndex)
                                redoStack.add(tfv.text)
                                tfv = TextFieldValue(prev, TextRange(prev.length))
                            }
                        },
                        enabled = undoStack.isNotEmpty() && !readOnlyViewerMode,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = s[Str.UNDO])
                    }
                    IconButton(
                        onClick = {
                            if (redoStack.isNotEmpty()) {
                                val next = redoStack.removeAt(redoStack.lastIndex)
                                undoStack.add(tfv.text)
                                tfv = TextFieldValue(next, TextRange(next.length))
                            }
                        },
                        enabled = redoStack.isNotEmpty() && !readOnlyViewerMode,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = s[Str.REDO])
                    }
                    IconButton(
                        onClick = { performSave(closeAfter = false) },
                        enabled = !readOnlyViewerMode,
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = s[Str.SAVE])
                    }
                    Box {
                        IconButton(onClick = { overflowExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = s[Str.EDITOR_OPTIONS])
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(s[Str.SAVE_AS_ELLIPSIS]) },
                                onClick = {
                                    overflowExpanded = false
                                    showSaveAsDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(if (settings.editorWrap) s[Str.DISABLE_WORD_WRAP] else s[Str.ENABLE_WORD_WRAP])
                                },
                                onClick = {
                                    overflowExpanded = false
                                    viewModel.container.settingsRepository.update {
                                        it.copy(editorWrap = !it.editorWrap)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(if (settings.editorLineNumbers) s[Str.HIDE_LINE_NUMBERS] else s[Str.SHOW_LINE_NUMBERS])
                                },
                                onClick = {
                                    overflowExpanded = false
                                    viewModel.container.settingsRepository.update {
                                        it.copy(editorLineNumbers = !it.editorLineNumbers)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(s.format(Str.FONT_SIZE_INC, settings.editorFontSp)) },
                                onClick = {
                                    viewModel.container.settingsRepository.update {
                                        it.copy(editorFontSp = (it.editorFontSp + 2).coerceAtMost(28))
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(s.format(Str.FONT_SIZE_DEC, settings.editorFontSp)) },
                                onClick = {
                                    viewModel.container.settingsRepository.update {
                                        it.copy(editorFontSp = (it.editorFontSp - 2).coerceAtLeast(10))
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(s[Str.ENCODING_AND_EOL_MENU]) },
                                onClick = {
                                    overflowExpanded = false
                                    showEncodingDialog = true
                                },
                            )
                            if (isJsonFile && !readOnlyViewerMode) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(s[Str.PRETTY_PRINT_JSON]) },
                                    onClick = {
                                        overflowExpanded = false
                                        runCatching {
                                            val parsed = MiniJson.parse(tfv.text)
                                            val formatted = MiniJson.stringify(parsed, indent = 2) + "\n"
                                            applyTextEdit(TextFieldValue(formatted, TextRange(formatted.length)))
                                        }.onFailure {
                                            viewModel.emitSimpleMessage(s.format(Str.INVALID_JSON, it.message.orEmpty()))
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showEncodingDialog = true }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    val charsetLabel = charset.name().uppercase()
                    Text(
                        text = s.format(
                            Str.EDITOR_STATUS_LINE,
                            charsetLabel,
                            eol.label,
                            cursorLine,
                            cursorCol,
                            UiFormatters.formatSize(context, fileSizeBytes),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (readOnlyViewerMode) {
                        Text(
                            text = s[Str.READ_ONLY_BADGE],
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (readOnlyViewerMode && !loading && loadError == null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = if (!canWriteFile) s[Str.OPENED_READ_ONLY_BANNER] else s[Str.LARGE_FILE_VIEWER_BANNER],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        if (!hardViewerOnly) {
                            TextButton(onClick = { readOnlyViewerMode = false }) {
                                Text(s[Str.EDIT_ANYWAY])
                            }
                        }
                    }
                }
            }

            if (showFindBar) {
                Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedTextField(
                                value = findQuery,
                                onValueChange = {
                                    findQuery = it
                                    currentMatchIndex = 0
                                },
                                placeholder = { Text(s[Str.FIND_IN_TEXT]) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            FilterChip(
                                selected = findCaseSensitive,
                                onClick = { findCaseSensitive = !findCaseSensitive },
                                label = { Text("Aa") },
                            )
                            Text(
                                text = if (matchRanges.isEmpty()) "0/0" else "${currentMatchIndex + 1}/${matchRanges.size}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            IconButton(onClick = { jumpToMatch(currentMatchIndex - 1) }, enabled = matchRanges.isNotEmpty()) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = s[Str.PREVIOUS_MATCH])
                            }
                            IconButton(onClick = { jumpToMatch(currentMatchIndex + 1) }, enabled = matchRanges.isNotEmpty()) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = s[Str.NEXT_MATCH])
                            }
                            IconButton(onClick = { showFindBar = false }) {
                                Icon(Icons.Filled.Close, contentDescription = s[Str.CLOSE_FIND])
                            }
                        }
                        if (!readOnlyViewerMode) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OutlinedTextField(
                                    value = replaceQuery,
                                    onValueChange = { replaceQuery = it },
                                    placeholder = { Text(s[Str.REPLACE_WITH]) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedButton(
                                    onClick = {
                                        if (matchRanges.isNotEmpty()) {
                                            val r = matchRanges[currentMatchIndex.coerceIn(0, matchRanges.lastIndex)]
                                            val updated = tfv.text.replaceRange(r, replaceQuery)
                                            applyTextEdit(TextFieldValue(updated, TextRange(r.first + replaceQuery.length)))
                                        }
                                    },
                                    enabled = matchRanges.isNotEmpty(),
                                ) {
                                    Text(s[Str.REPLACE])
                                }
                                OutlinedButton(
                                    onClick = {
                                        if (findQuery.isNotEmpty()) {
                                            val updated = tfv.text.replace(findQuery, replaceQuery, ignoreCase = !findCaseSensitive)
                                            applyTextEdit(TextFieldValue(updated, TextRange(0)))
                                        }
                                    },
                                    enabled = matchRanges.isNotEmpty(),
                                ) {
                                    Text(s[Str.ALL])
                                }
                            }
                        }
                    }
                }
            }

            when {
                loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                loadError != null -> {
                    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(loadError!!, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                else -> {
                    val fontSp = settings.editorFontSp.sp
                    val lineHeightSp = (settings.editorFontSp + 6).sp
                    val codeStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSp,
                        lineHeight = lineHeightSp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(verticalScroll),
                    ) {
                        if (settings.editorLineNumbers && !settings.editorWrap) {
                            val gutterNumbers = remember(lineCount) {
                                (1..lineCount).joinToString("\n")
                            }
                            Text(
                                text = gutterNumbers,
                                style = codeStyle.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                    textAlign = TextAlign.End,
                                ),
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                            )
                        }

                        val fieldModifier = if (settings.editorWrap) {
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                                .focusRequester(focusRequester)
                        } else {
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .horizontalScroll(horizontalScroll)
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                                .focusRequester(focusRequester)
                        }

                        BasicTextField(
                            value = tfv,
                            onValueChange = { if (!readOnlyViewerMode) applyTextEdit(it) },
                            readOnly = readOnlyViewerMode,
                            textStyle = codeStyle,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = fieldModifier,
                        )
                    }
                }
            }
        }
    }

    if (showEncodingDialog) {
        AlertDialog(
            onDismissRequest = { showEncodingDialog = false },
            title = { Text(s[Str.ENCODING_AND_EOL_TITLE]) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s[Str.LINE_ENDINGS_ON_SAVE], style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (candidateEol in listOf(LineEnding.LF, LineEnding.CRLF)) {
                            FilterChip(
                                selected = eol == candidateEol,
                                onClick = { eol = candidateEol },
                                label = { Text(candidateEol.label) },
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(s[Str.CHAR_ENCODING_REDECODE], style = MaterialTheme.typography.labelLarge)
                    for (csName in TextFileCodec.SUPPORTED_CHARSET_NAMES) {
                        val cs = Charset.forName(csName)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    charset = cs
                                    showEncodingDialog = false
                                    loadFromDisk(overrideCharset = cs)
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            RadioButton(selected = charset == cs, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(csName)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showEncodingDialog = false }) {
                    Text(s[Str.DONE])
                }
            },
        )
    }

    if (showSaveAsDialog) {
        val folderFavorites = remember(favorites) { favorites.filter { it.isDirectory } }
        var newName by remember { mutableStateOf(activePath.displayName) }
        var chosenFolder by remember {
            mutableStateOf(
                browser.currentDir
                    ?: folderFavorites.firstOrNull()?.path
                    ?: VfsPath.Local(java.io.File(Environment.getExternalStorageDirectory(), "Download").absolutePath),
            )
        }
        AlertDialog(
            onDismissRequest = { showSaveAsDialog = false },
            title = { Text(s[Str.SAVE_AS]) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(s[Str.FILE_NAME]) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(s.format(Str.TARGET_FOLDER_FMT, chosenFolder.displayName), style = MaterialTheme.typography.bodySmall)
                    for (fav in folderFavorites.take(4)) {
                        FilterChip(
                            selected = chosenFolder == fav.path,
                            onClick = { chosenFolder = fav.path },
                            label = { Text("★ ${fav.name}") },
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val target = withContext(Dispatchers.IO) {
                                    val f = viewModel.container.fileSystem.createFile(chosenFolder, newName.trim())
                                    TextFileCodec.saveFile(
                                        fs = viewModel.container.fileSystem,
                                        path = f,
                                        text = tfv.text,
                                        charset = charset,
                                        eol = eol,
                                        bom = bom,
                                    )
                                    f
                                }
                                activePath = target
                                canWriteFile = true
                                readOnlyViewerMode = false
                                savedText = tfv.text
                                showSaveAsDialog = false
                                viewModel.emitSimpleMessage(s.format(Str.SAVED_AS_FILE, target.displayName))
                            } catch (e: Exception) {
                                viewModel.emitSimpleMessage(s.format(Str.SAVE_AS_FAILED, e.message.orEmpty()))
                            }
                        }
                    },
                ) {
                    Text(s[Str.SAVE])
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveAsDialog = false }) { Text(s[Str.CANCEL]) }
            },
        )
    }

    if (showUnsavedExitGuard) {
        AlertDialog(
            onDismissRequest = { showUnsavedExitGuard = false },
            title = { Text(s[Str.UNSAVED_CHANGES]) },
            text = { Text(s.format(Str.UNSAVED_CHANGES_DESC, activePath.displayName)) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showUnsavedExitGuard = false
                            onClose()
                        },
                    ) {
                        Text(s[Str.DISCARD], color = MaterialTheme.colorScheme.error)
                    }
                    Button(
                        onClick = {
                            showUnsavedExitGuard = false
                            performSave(closeAfter = true)
                        },
                    ) {
                        Text(s[Str.SAVE])
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnsavedExitGuard = false }) {
                    Text(s[Str.CANCEL])
                }
            },
        )
    }
}
