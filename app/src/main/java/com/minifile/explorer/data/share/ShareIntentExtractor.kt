package com.minifile.explorer.data.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.minifile.explorer.core.model.Clock
import com.minifile.explorer.core.share.ShareImportSource
import com.minifile.explorer.core.share.ShareReceivePolicy
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RawShareIntent(
    val uris: List<Uri>,
    val text: String?,
    val subject: String?,
    val mimeType: String?,
    val skippedBlockedCount: Int,
)

data class IncomingSharedFile(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String?,
)

sealed interface IncomingSharePayload {
    data class Files(
        val items: List<IncomingSharedFile>,
        val skippedBlockedCount: Int = 0,
    ) : IncomingSharePayload

    data class Text(
        val text: String,
        val subject: String?,
        val suggestedFileName: String,
        val byteCount: Long,
        val previewText: String,
    ) : IncomingSharePayload
}

/**
 * Extracts, validates, resolves metadata for, and opens streams from external
 * `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intents.
 */
object ShareIntentExtractor {
    const val EXTRA_SHARE_CONSUMED = "com.minifile.explorer.EXTRA_SHARE_CONSUMED"

    fun isShareAction(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        return action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
    }

    /**
     * Extracts shared URIs and/or text from [intent], applies the private-data `file://` security
     * guard, and marks/clears [intent] in-place so configuration changes never re-open the dialog.
     */
    fun extractAndConsume(context: Context, intent: Intent?): RawShareIntent? {
        if (intent == null) return null
        if (intent.getBooleanExtra(EXTRA_SHARE_CONSUMED, false)) return null
        if (!isShareAction(intent)) return null

        val action = intent.action
        val mimeType = intent.type
        val rawUris = extractOrderedUris(intent, action)
        val appDataDirs = collectAppDataDirs(context)

        val safeUris = ArrayList<Uri>(rawUris.size)
        var skippedBlocked = 0
        for (uri in rawUris) {
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (scheme != "content" && scheme != "file") {
                skippedBlocked++
                continue
            }
            if (isBlockedUri(context, uri, appDataDirs)) {
                skippedBlocked++
                continue
            }
            safeUris.add(uri)
        }

        val hadAnyStreamUri = rawUris.isNotEmpty()
        val subject = readCharSequenceExtra(intent, Intent.EXTRA_SUBJECT)
        val text = if (!hadAnyStreamUri) {
            readCharSequenceExtra(intent, Intent.EXTRA_TEXT)
                ?: extractClipDataText(context, intent)
        } else {
            null
        }

        consumeIntentInPlace(intent)

        if (safeUris.isEmpty() && text.isNullOrEmpty() && skippedBlocked == 0) {
            return null
        }

        return RawShareIntent(
            uris = safeUris,
            text = text,
            subject = subject,
            mimeType = mimeType,
            skippedBlockedCount = skippedBlocked,
        )
    }

    /**
     * Resolves display names, sizes, and MIME types on `Dispatchers.IO` without buffering file data.
     */
    suspend fun resolvePayload(
        context: Context,
        raw: RawShareIntent,
        clock: Clock = Clock.SYSTEM,
    ): IncomingSharePayload? = withContext(Dispatchers.IO) {
        if (raw.uris.isNotEmpty()) {
            val resolver = context.contentResolver
            val resolved = ArrayList<IncomingSharedFile>(raw.uris.size)
            for (uri in raw.uris) {
                resolved.add(resolveSingleUri(resolver, uri, raw.mimeType))
            }
            IncomingSharePayload.Files(
                items = resolved,
                skippedBlockedCount = raw.skippedBlockedCount,
            )
        } else if (!raw.text.isNullOrEmpty()) {
            val textPayload = ShareReceivePolicy.buildTextSharePayload(
                text = raw.text,
                subject = raw.subject,
                nowEpochMs = clock.nowMs(),
            )
            IncomingSharePayload.Text(
                text = raw.text,
                subject = raw.subject,
                suggestedFileName = textPayload.fileName,
                byteCount = textPayload.bytes.size.toLong(),
                previewText = textPayload.previewText,
            )
        } else if (raw.skippedBlockedCount > 0) {
            IncomingSharePayload.Files(
                items = emptyList(),
                skippedBlockedCount = raw.skippedBlockedCount,
            )
        } else {
            null
        }
    }

    /**
     * Builds streaming [ShareImportSource] entries for [com.minifile.explorer.core.share.ShareImportEngine].
     * Re-checks the `file://` security guard at stream-open time as defense in depth.
     */
    fun buildImportSources(
        context: Context,
        payload: IncomingSharePayload,
        editedSingleName: String?,
        fatSafe: Boolean,
    ): List<ShareImportSource> {
        val appContext = context.applicationContext ?: context
        val resolver = appContext.contentResolver
        val appDataDirs = collectAppDataDirs(appContext)

        return when (payload) {
            is IncomingSharePayload.Text -> {
                val desiredName = editedSingleName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: payload.suggestedFileName
                val bytes = payload.text.toByteArray(Charsets.UTF_8)
                listOf(
                    ShareImportSource(
                        desiredName = ShareReceivePolicy.sanitizeFilename(
                            rawName = desiredName,
                            mimeType = "text/plain",
                            fatSafe = fatSafe,
                            appendMimeExtensionIfMissing = false,
                        ),
                        sizeBytes = bytes.size.toLong(),
                        mimeType = "text/plain",
                        openInput = { ByteArrayInputStream(bytes) },
                    ),
                )
            }

            is IncomingSharePayload.Files -> {
                val singleOverride = if (payload.items.size == 1) {
                    editedSingleName?.trim()?.takeIf { it.isNotEmpty() }
                } else {
                    null
                }
                payload.items.map { item ->
                    val name = singleOverride ?: item.displayName
                    val cleanName = ShareReceivePolicy.sanitizeFilename(
                        rawName = name,
                        mimeType = item.mimeType,
                        fatSafe = fatSafe,
                        appendMimeExtensionIfMissing = singleOverride == null,
                        extraExtensionFallback = mimeExtensionFromAndroid(item.mimeType),
                    )
                    ShareImportSource(
                        desiredName = cleanName,
                        sizeBytes = item.sizeBytes,
                        mimeType = item.mimeType,
                        openInput = {
                            openSafeInputStream(appContext, resolver, item.uri, cleanName, appDataDirs)
                        },
                    )
                }
            }
        }
    }

    internal fun collectAppDataDirs(context: Context): List<String> {
        val appContext = context.applicationContext ?: context
        val dirs = LinkedHashSet<String>()
        appContext.applicationInfo?.dataDir?.takeIf { it.isNotBlank() }?.let { dirs.add(it) }
        runCatching { appContext.dataDir?.absolutePath }.getOrNull()?.let { dirs.add(it) }
        runCatching { appContext.filesDir?.absolutePath }.getOrNull()?.let { dirs.add(it) }
        runCatching { appContext.cacheDir?.absolutePath }.getOrNull()?.let { dirs.add(it) }
        runCatching { appContext.codeCacheDir?.absolutePath }.getOrNull()?.let { dirs.add(it) }
        runCatching { appContext.noBackupFilesDir?.absolutePath }.getOrNull()?.let { dirs.add(it) }
        runCatching {
            appContext.createDeviceProtectedStorageContext().dataDir?.absolutePath
        }.getOrNull()?.let { dirs.add(it) }
        runCatching { appContext.getExternalFilesDirs(null) }.getOrNull()?.forEach { dir ->
            dir?.parentFile?.absolutePath?.let { dirs.add(it) }
            dir?.absolutePath?.let { dirs.add(it) }
        }
        runCatching { appContext.externalCacheDirs }.getOrNull()?.forEach { dir ->
            dir?.parentFile?.absolutePath?.let { dirs.add(it) }
            dir?.absolutePath?.let { dirs.add(it) }
        }
        runCatching { appContext.obbDirs }.getOrNull()?.forEach { dir ->
            dir?.absolutePath?.let { dirs.add(it) }
        }
        return dirs.toList()
    }

    private fun isBlockedUri(context: Context, uri: Uri, appDataDirs: List<String>): Boolean {
        val rawUri = uri.toString()
        if (ShareReceivePolicy.isBlockedFileUri(rawUri, context.packageName, appDataDirs)) {
            return true
        }
        if (uri.scheme.equals("content", ignoreCase = true)) {
            val authority = uri.authority?.substringAfterLast('@')
            val ownerPackage = authority?.let { auth ->
                runCatching {
                    context.packageManager.resolveContentProvider(auth, 0)?.packageName
                }.getOrNull()
            }
            if (ShareReceivePolicy.isBlockedContentUri(
                    rawUri = rawUri,
                    packageName = context.packageName,
                    ownProviderAuthorities = listOf("${context.packageName}.fileprovider"),
                    authorityOwnerPackage = ownerPackage,
                )
            ) {
                return true
            }
        }
        return false
    }

    private fun openSafeInputStream(
        appContext: Context,
        resolver: android.content.ContentResolver,
        uri: Uri,
        displayName: String,
        appDataDirs: List<String>,
    ) = if (isBlockedUri(appContext, uri, appDataDirs)) {
        throw SecurityException("Blocked access to private app file")
    } else if (uri.scheme.equals("file", ignoreCase = true)) {
        val path = uri.path ?: throw FileNotFoundException("Invalid file URI for $displayName")
        FileInputStream(File(path))
    } else {
        resolver.openInputStream(uri)
            ?: throw IOException("Cannot read shared file '$displayName' (permission expired or file removed)")
    }

    private fun extractOrderedUris(intent: Intent, action: String?): List<Uri> {
        val seen = LinkedHashMap<String, Uri>()
        fun addUri(uri: Uri?) {
            if (uri == null) return
            val key = uri.toString()
            if (key.isNotEmpty() && !seen.containsKey(key)) {
                seen[key] = uri
            }
        }

        when (action) {
            Intent.ACTION_SEND -> {
                val streamUri = readSingleStreamExtra(intent)
                addUri(streamUri)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val list = readMultipleStreamExtra(intent)
                if (list != null) {
                    for (u in list) {
                        addUri(u)
                    }
                }
            }
        }

        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                addUri(clip.getItemAt(i)?.uri)
            }
        }

        return seen.values.toList()
    }

    private fun readSingleStreamExtra(intent: Intent): Uri? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        }.getOrNull()
    }

    private fun readMultipleStreamExtra(intent: Intent): ArrayList<Uri>? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
        }.getOrNull()
    }

    private fun readCharSequenceExtra(intent: Intent, key: String): String? {
        return runCatching {
            intent.getCharSequenceExtra(key)?.toString() ?: intent.getStringExtra(key)
        }.getOrNull()
    }

    private fun extractClipDataText(context: Context, intent: Intent): String? {
        val clip = intent.clipData ?: return null
        if (clip.itemCount <= 0) return null
        val item = clip.getItemAt(0) ?: return null
        val direct = item.text?.toString()
        if (!direct.isNullOrEmpty()) return direct
        if (item.uri == null) {
            return runCatching { item.coerceToText(context)?.toString() }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun consumeIntentInPlace(intent: Intent) {
        intent.putExtra(EXTRA_SHARE_CONSUMED, true)
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.removeExtra(Intent.EXTRA_TEXT)
        intent.removeExtra(Intent.EXTRA_SUBJECT)
        intent.clipData = null
        intent.action = Intent.ACTION_MAIN
    }

    private fun resolveSingleUri(
        resolver: android.content.ContentResolver,
        uri: Uri,
        intentMimeType: String?,
    ): IncomingSharedFile {
        var rawName: String? = null
        var sizeBytes = -1L

        if (uri.scheme.equals("file", ignoreCase = true)) {
            val file = uri.path?.let { File(it) }
            if (file != null) {
                rawName = file.name
                if (file.isFile) {
                    sizeBytes = file.length()
                }
            }
        } else {
            runCatching {
                resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                            rawName = cursor.getString(nameIdx)
                        }
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            sizeBytes = cursor.getLong(sizeIdx)
                        }
                    }
                }
            }

            if (sizeBytes < 0L) {
                sizeBytes = runCatching {
                    resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                        if (afd.length >= 0L) afd.length else -1L
                    } ?: -1L
                }.getOrDefault(-1L)
            }
        }

        val resolvedMime = runCatching { resolver.getType(uri) }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != "*/*" }
            ?: intentMimeType?.takeIf { it.isNotBlank() && it != "*/*" && !it.endsWith("/*") }

        val candidateRawName = rawName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringAfterLast(':')
                ?.takeIf { it.isNotBlank() }

        val sanitized = ShareReceivePolicy.sanitizeFilename(
            rawName = candidateRawName,
            mimeType = resolvedMime,
            fatSafe = false,
            appendMimeExtensionIfMissing = true,
            extraExtensionFallback = mimeExtensionFromAndroid(resolvedMime),
        )

        return IncomingSharedFile(
            uri = uri,
            displayName = sanitized,
            sizeBytes = sizeBytes,
            mimeType = resolvedMime,
        )
    }

    private fun mimeExtensionFromAndroid(mimeType: String?): String? {
        val base = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (base.isEmpty() || base == "*/*" || base.endsWith("/*") || base == "application/octet-stream") {
            return null
        }
        return runCatching {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(base)
        }.getOrNull()
    }
}
