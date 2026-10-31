package com.minifile.explorer.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.FileProvider
import com.minifile.explorer.core.ops.TextFileCodec
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.data.SafFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UiFormatters {

    private val thumbCache = object : LruCache<String, Bitmap>(64) {}

    fun formatSize(context: Context, bytes: Long): String {
        if (bytes < 0L) return "—"
        return Formatter.formatFileSize(context, bytes)
    }

    fun formatExactSize(context: Context, bytes: Long): String {
        if (bytes < 0L) return "—"
        val human = Formatter.formatFileSize(context, bytes)
        return "$human (${String.format(Locale.US, "%,d", bytes)} bytes)"
    }

    fun formatShortDate(epochMs: Long): String {
        if (epochMs <= 0L) return "—"
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now - epochMs) < 7L * 24L * 3600L * 1000L) {
            return DateUtils.getRelativeTimeSpanString(
                epochMs,
                now,
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE,
            ).toString()
        }
        return SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(epochMs))
    }

    fun formatFullDate(epochMs: Long): String {
        if (epochMs <= 0L) return "—"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))
    }

    fun formatIsoDate(epochMs: Long): String {
        if (epochMs <= 0L) return "—"
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(epochMs))
    }

    fun iconForNode(node: VfsNode): ImageVector {
        if (node.isDirectory) return Icons.Filled.Folder
        val ext = PathUtils.extensionOf(node.name)
        val mime = node.resolvedMimeType
        return when {
            ext == "apk" -> Icons.Filled.Android
            ext in PathUtils.ARCHIVE_EXTENSIONS || ext in setOf("tar", "gz", "7z", "rar") -> Icons.Filled.Archive
            mime.startsWith("image/") -> Icons.Filled.Image
            mime.startsWith("video/") -> Icons.Filled.Movie
            mime.startsWith("audio/") -> Icons.Filled.MusicNote
            ext in setOf("kt", "kts", "java", "py", "js", "ts", "c", "cpp", "h", "json", "xml", "html", "css", "sh", "yml", "yaml", "toml") ->
                Icons.Filled.Code

            mime.startsWith("text/") || ext in PathUtils.TEXT_EXTENSIONS || ext == "pdf" -> Icons.Filled.Description
            else -> Icons.AutoMirrored.Filled.InsertDriveFile
        }
    }

    fun isImageOrVideo(node: VfsNode): Boolean {
        if (node.isDirectory) return false
        val mime = node.resolvedMimeType
        return mime.startsWith("image/") || mime.startsWith("video/")
    }

    suspend fun shouldOpenAsText(node: VfsNode, fs: FileSystem): Boolean {
        if (node.isDirectory) return false
        val ext = PathUtils.extensionOf(node.name)
        if (ext in PathUtils.TEXT_EXTENSIONS || node.name == ".nomedia" || node.name.startsWith(".git")) {
            return true
        }
        val mime = node.resolvedMimeType
        if (mime.startsWith("text/") || mime == "application/json" || mime == "application/xml") {
            return true
        }
        if (ext == "pdf" || ext in PathUtils.ARCHIVE_EXTENSIONS || mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")) {
            return false
        }
        if (node.size in 0L..(1024L * 1024L)) {
            return withContext(Dispatchers.IO) {
                runCatching {
                    fs.openInput(node.path).use { ins ->
                        val head = ByteArray(8192)
                        val n = ins.read(head)
                        if (n <= 0) true else TextFileCodec.isProbablyText(head.copyOf(n))
                    }
                }.getOrDefault(false)
            }
        }
        return false
    }

    suspend fun loadThumbnail(
        context: Context,
        safFs: SafFileSystem,
        node: VfsNode,
        pxSize: Int = 128,
    ): Bitmap? {
        if (!isImageOrVideo(node)) return null
        val cacheKey = "${node.path.serialize()}@${node.lastModified}@$pxSize"
        thumbCache.get(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            val bmp: Bitmap? = runCatching {
                when (val p = node.path) {
                    is VfsPath.Local -> {
                        val file = File(p.normalized)
                        if (!file.isFile || !file.canRead()) return@runCatching null
                        if (node.resolvedMimeType.startsWith("video/")) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                ThumbnailUtils.createVideoThumbnail(file, Size(pxSize, pxSize), null)
                            } else {
                                null
                            }
                        } else {
                            decodeSampledBitmap(file.absolutePath, pxSize)
                        }
                    }

                    is VfsPath.Saf -> {
                        val uri = safFs.buildDocumentUri(p)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            context.contentResolver.loadThumbnail(uri, Size(pxSize, pxSize), null)
                        } else {
                            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        }
                    }

                    is VfsPath.Ftp -> null
                }
            }.getOrNull()

            if (bmp != null) {
                thumbCache.put(cacheKey, bmp)
            }
            bmp
        }
    }

    private fun decodeSampledBitmap(path: String, reqSize: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (opts.outWidth / sample > reqSize * 2 && opts.outHeight / sample > reqSize * 2) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, decodeOpts)
    }

    private fun ftpCacheFile(context: Context, path: VfsPath.Ftp): File {
        val dir = File(context.cacheDir, "ftp_cache").apply { mkdirs() }
        val safeName = path.displayName.replace('/', '_').ifBlank { "file" }
        val hash = "${path.serverId}:${path.normalizedRemotePath}".hashCode().toUInt().toString(16)
        return File(dir, "${hash}_$safeName")
    }

    fun toShareableUri(context: Context, safFs: SafFileSystem, path: VfsPath, fs: FileSystem? = null): Uri {
        return when (path) {
            is VfsPath.Local -> FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                File(path.normalized),
            )

            is VfsPath.Saf -> safFs.buildDocumentUri(path)

            is VfsPath.Ftp -> {
                val target = ftpCacheFile(context, path)
                if (fs != null) {
                    fs.openInput(path).use { ins ->
                        target.outputStream().use { outs ->
                            ins.copyTo(outs, bufferSize = 64 * 1024)
                        }
                    }
                } else if (!target.isFile) {
                    throw IllegalArgumentException("Remote FTP file must be downloaded first before sharing.")
                }
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    target,
                )
            }
        }
    }

    suspend fun openExternallyAsync(
        context: Context,
        safFs: SafFileSystem,
        fs: FileSystem,
        node: VfsNode,
        forceChooser: Boolean = false,
    ): Result<Unit> = runCatching {
        val uri = withContext(Dispatchers.IO) {
            toShareableUri(context, safFs, node.path, fs)
        }
        val mime = if (PathUtils.extensionOf(node.name) == "apk") {
            "application/vnd.android.package-archive"
        } else {
            node.resolvedMimeType
        }
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val toStart = if (forceChooser) {
            Intent.createChooser(viewIntent, "Open ${node.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            viewIntent
        }
        context.startActivity(toStart)
    }

    suspend fun shareItemsAsync(
        context: Context,
        safFs: SafFileSystem,
        fs: FileSystem,
        nodes: List<VfsNode>,
    ): Result<Unit> = runCatching {
        val filesOnly = nodes.filterNot { it.isDirectory }
        require(filesOnly.isNotEmpty()) { "Select at least one file to share (compress folders to ZIP first)." }
        if (filesOnly.size == 1) {
            val single = filesOnly.first()
            val uri = withContext(Dispatchers.IO) {
                toShareableUri(context, safFs, single.path, fs)
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = single.resolvedMimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share ${single.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            val uris = withContext(Dispatchers.IO) {
                val list = ArrayList<Uri>(filesOnly.size)
                for (n in filesOnly) {
                    list.add(toShareableUri(context, safFs, n.path, fs))
                }
                list
            }
            val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share ${filesOnly.size} files").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun openExternally(
        context: Context,
        safFs: SafFileSystem,
        node: VfsNode,
        forceChooser: Boolean = false,
    ): Result<Unit> = runCatching {
        val uri = toShareableUri(context, safFs, node.path)
        val mime = if (PathUtils.extensionOf(node.name) == "apk") {
            "application/vnd.android.package-archive"
        } else {
            node.resolvedMimeType
        }
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val toStart = if (forceChooser) {
            Intent.createChooser(viewIntent, "Open ${node.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            viewIntent
        }
        context.startActivity(toStart)
    }

    fun shareItems(
        context: Context,
        safFs: SafFileSystem,
        nodes: List<VfsNode>,
    ): Result<Unit> = runCatching {
        val filesOnly = nodes.filterNot { it.isDirectory }
        require(filesOnly.isNotEmpty()) { "Select at least one file to share (compress folders to ZIP first)." }
        if (filesOnly.size == 1) {
            val single = filesOnly.first()
            val uri = toShareableUri(context, safFs, single.path)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = single.resolvedMimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share ${single.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            val uris = ArrayList<Uri>(filesOnly.size)
            for (n in filesOnly) {
                uris.add(toShareableUri(context, safFs, n.path))
            }
            val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share ${filesOnly.size} files").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun copyToSystemClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun buildAllFilesAccessIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            )
        }
    }
}
