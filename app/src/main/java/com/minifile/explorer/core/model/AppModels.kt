package com.minifile.explorer.core.model

import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath

enum class SortField {
    NAME,
    SIZE,
    DATE,
    TYPE,
}

data class SortSpec(
    val by: SortField = SortField.NAME,
    val ascending: Boolean = true,
    val foldersFirst: Boolean = true,
) {
    fun comparator(): Comparator<VfsNode> = Comparator { a, b ->
        if (foldersFirst && a.isDirectory != b.isDirectory) {
            return@Comparator if (a.isDirectory) -1 else 1
        }
        val raw = when (by) {
            SortField.NAME -> PathUtils.naturalCompare(a.name, b.name)
            SortField.SIZE -> {
                val cmp = a.size.compareTo(b.size)
                if (cmp != 0) cmp else PathUtils.naturalCompare(a.name, b.name)
            }

            SortField.DATE -> {
                val cmp = a.lastModified.compareTo(b.lastModified)
                if (cmp != 0) cmp else PathUtils.naturalCompare(a.name, b.name)
            }

            SortField.TYPE -> {
                val extA = if (a.isDirectory) "" else PathUtils.extensionOf(a.name)
                val extB = if (b.isDirectory) "" else PathUtils.extensionOf(b.name)
                val cmp = extA.compareTo(extB)
                if (cmp != 0) cmp else PathUtils.naturalCompare(a.name, b.name)
            }
        }
        if (ascending) raw else -raw
    }

    fun toJson(): JsonValue.Obj = MiniJson.obj(
        "by" to JsonValue.Str(by.name),
        "asc" to JsonValue.Bool(ascending),
        "foldersFirst" to JsonValue.Bool(foldersFirst),
    )

    companion object {
        fun fromJson(obj: JsonValue.Obj): SortSpec {
            val field = obj.str("by")?.let { name ->
                SortField.entries.firstOrNull { it.name == name }
            } ?: SortField.NAME
            return SortSpec(
                by = field,
                ascending = obj.bool("asc", true),
                foldersFirst = obj.bool("foldersFirst", true),
            )
        }
    }
}

enum class ViewMode {
    LIST,
    GRID,
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class TransferMode {
    COPY,
    MOVE,
}

data class ClipboardState(
    val items: List<VfsPath>,
    val mode: TransferMode,
)

data class Favorite(
    val id: String,
    val name: String,
    val path: VfsPath,
    val createdAt: Long,
    val lastUsedAt: Long,
    val order: Int,
    val color: Int? = null,
    val isDirectory: Boolean = true,
) {
    fun toJson(): JsonValue.Obj {
        val pairs = mutableListOf<Pair<String, JsonValue>>(
            "id" to JsonValue.Str(id),
            "name" to JsonValue.Str(name),
            "path" to JsonValue.Str(path.serialize()),
            "createdAt" to JsonValue.Num(createdAt),
            "lastUsedAt" to JsonValue.Num(lastUsedAt),
            "order" to JsonValue.Num(order),
            "isDirectory" to JsonValue.Bool(isDirectory),
        )
        if (color != null) {
            pairs.add("color" to JsonValue.Num(color))
        }
        return MiniJson.obj(*pairs.toTypedArray())
    }

    companion object {
        fun fromJson(obj: JsonValue.Obj): Favorite? {
            val id = obj.str("id") ?: return null
            val name = obj.str("name") ?: return null
            val rawPath = obj.str("path") ?: return null
            val path = runCatching { VfsPath.parse(rawPath) }.getOrNull() ?: return null
            return Favorite(
                id = id,
                name = name,
                path = path,
                createdAt = obj.long("createdAt", 0L),
                lastUsedAt = obj.long("lastUsedAt", 0L),
                order = obj.int("order", 0),
                color = obj.intOrNull("color"),
                isDirectory = obj.bool("isDirectory", true),
            )
        }
    }
}

data class FtpServerBookmark(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 21,
    val anonymous: Boolean = true,
    val username: String = "anonymous",
    val password: String = "",
    val initialPath: String = "/",
    val createdAt: Long = 0L,
) {
    val rootPath: VfsPath.Ftp
        get() = VfsPath.Ftp(id, PathUtils.normalizeLocalPath(initialPath.ifBlank { "/" }))

    val displayUrl: String
        get() {
            val normPath = PathUtils.normalizeLocalPath(initialPath.ifBlank { "/" })
            val userPart = if (anonymous) "" else "${username.ifBlank { "anonymous" }}@"
            val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            return "ftp://$userPart$hostPart:$port$normPath"
        }

    fun toJson(): JsonValue.Obj = MiniJson.obj(
        "id" to JsonValue.Str(id),
        "name" to JsonValue.Str(name),
        "host" to JsonValue.Str(host),
        "port" to JsonValue.Num(port),
        "anonymous" to JsonValue.Bool(anonymous),
        "username" to JsonValue.Str(username),
        "password" to JsonValue.Str(password),
        "initialPath" to JsonValue.Str(PathUtils.normalizeLocalPath(initialPath.ifBlank { "/" })),
        "createdAt" to JsonValue.Num(createdAt),
    )

    companion object {
        fun fromJson(obj: JsonValue.Obj): FtpServerBookmark? {
            val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return null
            val host = obj.str("host")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val name = obj.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: host
            val port = obj.int("port", 21).coerceIn(1, 65535)
            val anonymous = obj.bool("anonymous", true)
            val username = obj.str("username") ?: "anonymous"
            val password = obj.str("password") ?: ""
            val initialPath = PathUtils.normalizeLocalPath(obj.str("initialPath")?.ifBlank { "/" } ?: "/")
            val createdAt = obj.long("createdAt", 0L)
            return FtpServerBookmark(
                id = id,
                name = name,
                host = host,
                port = port,
                anonymous = anonymous,
                username = username,
                password = password,
                initialPath = initialPath,
                createdAt = createdAt,
            )
        }
    }
}

enum class GDriveAuthMode {
    SYSTEM_ACCOUNT,
    BROWSER_PKCE,
    PASTED_TOKEN,
}

data class GDriveServerBookmark(
    val id: String,
    val name: String,
    val email: String = "",
    val authMode: GDriveAuthMode,
    val accountName: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtMs: Long = 0L,
    val clientId: String = "",
    val clientSecret: String = "",
    val initialPath: String = "/",
    val createdAt: Long = 0L,
) {
    val rootPath: VfsPath.GDrive
        get() = VfsPath.GDrive(id, PathUtils.normalizeLocalPath(initialPath.ifBlank { "/" }))

    val displayUrl: String
        get() {
            val normPath = PathUtils.normalizeLocalPath(initialPath.ifBlank { "/" })
            val who = email.ifBlank { accountName.ifBlank { "…" } }
            return "gdrive://$who$normPath"
        }

    fun toJson(): JsonValue.Obj {
        val persistTokens = authMode != GDriveAuthMode.SYSTEM_ACCOUNT
        return MiniJson.obj(
            "id" to JsonValue.Str(id),
            "name" to JsonValue.Str(name),
            "email" to JsonValue.Str(email),
            "authMode" to JsonValue.Str(authMode.name),
            "accountName" to JsonValue.Str(accountName),
            "accessToken" to JsonValue.Str(if (persistTokens) accessToken else ""),
            "refreshToken" to JsonValue.Str(if (persistTokens) refreshToken else ""),
            "expiresAtMs" to JsonValue.Num(if (persistTokens) expiresAtMs else 0L),
            "clientId" to JsonValue.Str(clientId),
            "clientSecret" to JsonValue.Str(clientSecret),
            "initialPath" to JsonValue.Str(PathUtils.normalizeLocalPath(initialPath.ifBlank { "/" })),
            "createdAt" to JsonValue.Num(createdAt),
        )
    }

    companion object {
        fun fromJson(obj: JsonValue.Obj): GDriveServerBookmark? {
            val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return null
            val authModeRaw = obj.str("authMode") ?: return null
            val authMode = GDriveAuthMode.entries.firstOrNull { it.name == authModeRaw } ?: return null
            val email = obj.str("email")?.trim() ?: ""
            val accountName = obj.str("accountName")?.trim() ?: ""
            val fallbackName = email.ifEmpty { accountName.ifEmpty { "Google Drive" } }
            val name = obj.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackName
            val persistTokens = authMode != GDriveAuthMode.SYSTEM_ACCOUNT
            val accessToken = if (persistTokens) obj.str("accessToken") ?: "" else ""
            val refreshToken = if (persistTokens) obj.str("refreshToken") ?: "" else ""
            val expiresAtMs = if (persistTokens) obj.long("expiresAtMs", 0L) else 0L
            val clientId = obj.str("clientId")?.trim() ?: ""
            val clientSecret = obj.str("clientSecret")?.trim() ?: ""
            val initialPath = PathUtils.normalizeLocalPath(obj.str("initialPath")?.ifBlank { "/" } ?: "/")
            val createdAt = obj.long("createdAt", 0L)
            return GDriveServerBookmark(
                id = id,
                name = name,
                email = email,
                authMode = authMode,
                accountName = accountName,
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtMs = expiresAtMs,
                clientId = clientId,
                clientSecret = clientSecret,
                initialPath = initialPath,
                createdAt = createdAt,
            )
        }
    }
}

data class AppSettings(
    val showHidden: Boolean = false,
    val viewMode: ViewMode = ViewMode.LIST,
    val sort: SortSpec = SortSpec(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val showThumbnails: Boolean = true,
    val confirmOverwrite: Boolean = true,
    val openTextInBuiltInEditor: Boolean = true,
    val editorFontSp: Int = 14,
    val editorWrap: Boolean = false,
    val editorLineNumbers: Boolean = true,
    val rememberLastFolder: Boolean = false,
    val lastFolder: String? = null,
    val onboardingDone: Boolean = false,
    val httpPort: Int = 8080,
    val httpRoot: String? = null,
    val httpReadOnly: Boolean = false,
    val ftpPort: Int = 2121,
    val ftpRoot: String? = null,
    val ftpAnonymous: Boolean = true,
    val ftpUsername: String = "minifile",
    val ftpPassword: String = "",
    val ftpReadOnly: Boolean = false,
    val ftpPasvStart: Int = 50000,
    val ftpPasvEnd: Int = 50100,
    val ftpIdleTimeoutSec: Int = 300,
    val ftpKeepScreenOn: Boolean = true,
    val favoritePickerSortByRecency: Boolean = true,
    val peerDeviceId: String = "",
    val peerDeviceName: String = "Files",
    val updateAutoCheck: Boolean = true,
    val updateIncludePre: Boolean = false,
    val updateLastCheckMs: Long = 0L,
    val updateDismissedTag: String? = null,
    val gdriveLastClientId: String = "",
    val gdriveLastClientSecret: String = "",
)

enum class OperationKind {
    COPY,
    MOVE,
    DELETE,
    COMPRESS,
    EXTRACT,
    COMPUTE_SIZE,
    SEND,
    RECEIVE,
}

data class OperationProgress(
    val id: String,
    val kind: OperationKind,
    val title: String,
    val currentItem: String = "",
    val processedItems: Int = 0,
    val totalItems: Int = 0,
    val processedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val bytesPerSecond: Long = 0L,
    val finished: Boolean = false,
    val cancelled: Boolean = false,
    val errorSummary: String? = null,
    val unsafeSkipped: Int = 0,
) {
    val fraction: Float?
        get() = when {
            totalBytes > 0L -> (processedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            totalItems > 0 -> (processedItems.toFloat() / totalItems.toFloat()).coerceIn(0f, 1f)
            else -> null
        }
}

data class OperationResult(
    val succeeded: Int,
    val skipped: Int,
    val failed: List<Pair<VfsPath, String>>,
    val cancelled: Boolean,
)

data class FolderStats(
    val totalBytes: Long,
    val fileCount: Int,
    val dirCount: Int,
)
