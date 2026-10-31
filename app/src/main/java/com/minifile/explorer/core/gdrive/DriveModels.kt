package com.minifile.explorer.core.gdrive

import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.vfs.VfsPath
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime

const val DEFAULT_DRIVE_API_BASE = "https://www.googleapis.com/drive/v3"
const val DEFAULT_DRIVE_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
const val DEFAULT_OAUTH_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
const val DEFAULT_OAUTH_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
const val DEFAULT_OAUTH_REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke"
const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"

const val FOLDER_MIME = "application/vnd.google-apps.folder"
const val SHORTCUT_MIME = "application/vnd.google-apps.shortcut"
const val GOOGLE_APPS_MIME_PREFIX = "application/vnd.google-apps."
const val PDF_MIME = "application/pdf"

const val DIVISION_SLASH: Char = '\u2215' // '∕'

val WORKSPACE_EXPORT: Map<String, String> = mapOf(
    "application/vnd.google-apps.document" to PDF_MIME,
    "application/vnd.google-apps.spreadsheet" to PDF_MIME,
    "application/vnd.google-apps.presentation" to PDF_MIME,
    "application/vnd.google-apps.drawing" to PDF_MIME,
)

val WORKSPACE_DOC_LABELS: Map<String, String> = mapOf(
    "application/vnd.google-apps.document" to "Google Doc",
    "application/vnd.google-apps.spreadsheet" to "Google Sheet",
    "application/vnd.google-apps.presentation" to "Google Slides",
    "application/vnd.google-apps.drawing" to "Google Drawing",
)

const val FILE_FIELDS =
    "id,name,mimeType,size,createdTime,modifiedTime,trashed,capabilities/canEdit,shortcutDetails"
const val LIST_FIELDS = "nextPageToken,files($FILE_FIELDS)"

data class DriveEntryMeta(
    val id: String,
    val name: String,
    val mimeType: String,
    val isFolder: Boolean,
    val size: Long,
    val modifiedTimeMs: Long,
    val canWrite: Boolean,
    val isShortcut: Boolean,
    val shortcutTargetId: String?,
    val isWorkspaceDoc: Boolean,
    val createdTimeMs: Long = 0L,
    val canRead: Boolean = true,
    val displayName: String = "",
    val shortcutTargetMimeType: String? = null,
)

class DriveApiException(
    val status: Int,
    val reason: String?,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Escapes a string literal interpolated inside single quotes in a Google Drive `q=` filter.
 * Backslash MUST be escaped before single quote.
 */
fun escapeDriveQueryLiteral(value: String): String =
    value.replace("\\", "\\\\").replace("'", "\\'")

/**
 * Sanitises an untrusted Drive item name into a safe single-segment VFS display name (B1):
 * strips NUL and ASCII control chars (`< 0x20` or `0x7F`), replaces `/` with U+2215 `∕`,
 * and maps empty, `.` or `..` to `"(unnamed <id8>)"`.
 */
fun toDisplayName(name: String, id: String): String {
    val cleaned = buildString(name.length) {
        for (ch in name) {
            if (ch.code < 0x20 || ch.code == 0x7F) continue
            if (ch == '/') append(DIVISION_SLASH) else append(ch)
        }
    }
    if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
        val shortId = id.take(8).ifEmpty { "item" }
        return "(unnamed $shortId)"
    }
    return cleaned
}

fun isValidDriveTargetName(name: String): Boolean {
    if (name.isEmpty() || name.isBlank()) return false
    if (name == "." || name == "..") return false
    for (ch in name) {
        if (ch == '/' || ch.code < 0x20 || ch.code == 0x7F) return false
    }
    return true
}

fun requireValidDriveTargetName(name: String) {
    if (!isValidDriveTargetName(name)) {
        throw IOException("Invalid name")
    }
}

fun requireValidDriveVfsPath(path: VfsPath.GDrive, allowRoot: Boolean) {
    val raw = path.remotePath
    if (raw.isBlank() || !raw.startsWith("/")) {
        throw IOException("Invalid name")
    }
    if (raw == "/") {
        if (!allowRoot) throw IOException("Invalid name")
        return
    }
    val trimmed = raw.trimEnd('/')
    if (trimmed.isEmpty()) {
        if (!allowRoot) throw IOException("Invalid name")
        return
    }
    val segments = trimmed.substring(1).split('/')
    for (seg in segments) {
        if (!isValidDriveTargetName(seg)) {
            throw IOException("Invalid name")
        }
    }
}

fun parseRfc3339Ms(raw: String?): Long {
    if (raw.isNullOrBlank()) return 0L
    return try {
        Instant.parse(raw).toEpochMilli()
    } catch (_: Exception) {
        try {
            OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}

fun formatRfc3339(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

fun urlEncode(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")

fun parseDriveEntryMeta(obj: JsonValue.Obj): DriveEntryMeta? {
    if (obj.bool("trashed", false)) return null
    val id = obj.str("id")?.takeIf { it.isNotEmpty() } ?: return null
    val rawName = obj.str("name") ?: ""
    val mimeType = obj.str("mimeType") ?: "application/octet-stream"
    val createdTimeMs = parseRfc3339Ms(obj.str("createdTime"))
    val modifiedTimeMs = parseRfc3339Ms(obj.str("modifiedTime"))
    val canEdit = obj.obj("capabilities")?.bool("canEdit", true) ?: true

    val shortcutObj = obj.obj("shortcutDetails")
    val isShortcut = mimeType == SHORTCUT_MIME
    val shortcutTargetId = if (isShortcut) shortcutObj?.str("targetId")?.takeIf { it.isNotEmpty() } else null
    val shortcutTargetMime = if (isShortcut) shortcutObj?.str("targetMimeType") else null

    val isFolder = mimeType == FOLDER_MIME || (isShortcut && shortcutTargetMime == FOLDER_MIME)
    val isWorkspaceDoc = mimeType in WORKSPACE_EXPORT
    val isUnsupportedGoogleApp = !isFolder && !isShortcut && !isWorkspaceDoc &&
        mimeType.startsWith(GOOGLE_APPS_MIME_PREFIX)

    val rawSize = obj.str("size")?.toLongOrNull() ?: obj.long("size", 0L)
    val size = when {
        isFolder || isWorkspaceDoc || isUnsupportedGoogleApp || isShortcut -> 0L
        rawSize < 0L -> 0L
        else -> rawSize
    }
    val canRead = !isUnsupportedGoogleApp
    val canWrite = when {
        isWorkspaceDoc || isUnsupportedGoogleApp -> false
        else -> canEdit
    }

    return DriveEntryMeta(
        id = id,
        name = rawName,
        mimeType = mimeType,
        isFolder = isFolder,
        size = size,
        modifiedTimeMs = modifiedTimeMs,
        canWrite = canWrite,
        isShortcut = isShortcut,
        shortcutTargetId = shortcutTargetId,
        isWorkspaceDoc = isWorkspaceDoc,
        createdTimeMs = createdTimeMs,
        canRead = canRead,
        shortcutTargetMimeType = shortcutTargetMime,
    )
}

private val SECRET_QUERY_OR_JSON_REGEX = Regex(
    """(?i)(access_token|refresh_token|client_secret|code_verifier|Authorization)\s*([=:])\s*([^\s&",}]+)""",
)

fun redactSecrets(input: String): String =
    SECRET_QUERY_OR_JSON_REGEX.replace(input) { m ->
        "${m.groupValues[1]}${m.groupValues[2]}<redacted>"
    }
