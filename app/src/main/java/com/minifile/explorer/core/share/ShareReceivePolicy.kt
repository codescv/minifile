package com.minifile.explorer.core.share

import com.minifile.explorer.core.peer.PeerConflictPolicy
import com.minifile.explorer.core.vfs.NameValidation
import com.minifile.explorer.core.vfs.PathUtils
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure-JVM filename sanitization, collision resolution, text-share payload builder, and
 * `file://` private-directory security guard for incoming external Android shares.
 *
 * Zero `android.*` / `androidx.*` / `dalvik.*` imports.
 */
object ShareReceivePolicy {
    const val DEFAULT_FALLBACK_BASE = "shared_file"
    const val DEFAULT_TEXT_NOTE_PREFIX = "shared_note"
    const val MAX_FILENAME_UTF8_BYTES = 200
    const val MAX_COLLISION_UTF8_BYTES = 255
    private const val MAX_EXTENSION_UTF8_BYTES = 32
    private const val MAX_SUBJECT_BASE_UTF8_BYTES = 100

    private val FAT_ILLEGAL_CHARS = setOf('\\', ':', '*', '?', '"', '<', '>', '|')

    private val MIME_TO_EXTENSION: Map<String, String> = mapOf(
        "text/plain" to "txt",
        "text/markdown" to "md",
        "text/x-markdown" to "md",
        "text/html" to "html",
        "text/css" to "css",
        "text/csv" to "csv",
        "text/tab-separated-values" to "tsv",
        "text/xml" to "xml",
        "application/xml" to "xml",
        "text/javascript" to "js",
        "application/javascript" to "js",
        "application/json" to "json",
        "text/yaml" to "yaml",
        "application/x-yaml" to "yaml",
        "text/vcard" to "vcf",
        "text/x-vcard" to "vcf",
        "text/calendar" to "ics",
        "application/pdf" to "pdf",
        "application/zip" to "zip",
        "application/x-zip-compressed" to "zip",
        "application/java-archive" to "jar",
        "application/gzip" to "gz",
        "application/x-gzip" to "gz",
        "application/x-tar" to "tar",
        "application/x-7z-compressed" to "7z",
        "application/vnd.rar" to "rar",
        "application/x-rar-compressed" to "rar",
        "application/vnd.android.package-archive" to "apk",
        "application/msword" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.ms-powerpoint" to "ppt",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "image/jpeg" to "jpg",
        "image/jpg" to "jpg",
        "image/png" to "png",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "image/svg+xml" to "svg",
        "image/bmp" to "bmp",
        "image/x-ms-bmp" to "bmp",
        "image/heic" to "heic",
        "image/heif" to "heif",
        "image/tiff" to "tiff",
        "image/x-icon" to "ico",
        "video/mp4" to "mp4",
        "video/x-matroska" to "mkv",
        "video/webm" to "webm",
        "video/quicktime" to "mov",
        "video/x-msvideo" to "avi",
        "video/3gpp" to "3gp",
        "audio/mpeg" to "mp3",
        "audio/mp3" to "mp3",
        "audio/flac" to "flac",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "audio/ogg" to "ogg",
        "audio/opus" to "opus",
        "audio/mp4" to "m4a",
        "audio/aac" to "aac",
        "audio/x-m4a" to "m4a",
    )

    sealed interface CollisionDecision {
        data class Write(val fileName: String, val overwrite: Boolean) : CollisionDecision
        data object Skip : CollisionDecision
    }

    data class TextSharePayload(
        val fileName: String,
        val bytes: ByteArray,
        val previewText: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TextSharePayload) return false
            return fileName == other.fileName &&
                bytes.contentEquals(other.bytes) &&
                previewText == other.previewText
        }

        override fun hashCode(): Int {
            var result = fileName.hashCode()
            result = 31 * result + bytes.contentHashCode()
            result = 31 * result + previewText.hashCode()
            return result
        }
    }

    private val defaultCanonicalPathResolver: (String) -> String? = { path ->
        runCatching { File(path).canonicalPath }.getOrNull()
    }

    /**
     * Normalizes a MIME type (stripping parameters such as `; charset=utf-8`) and returns a
     * lowercase file extension without a leading dot, or `null` for generic/wildcard/unknown types.
     */
    fun extensionForMimeType(mimeType: String?): String? {
        val base = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        if (base == "*/*" || base.endsWith("/*") || base == "application/octet-stream") {
            return null
        }
        return MIME_TO_EXTENSION[base]
    }

    /**
     * Sanitizes an untrusted external filename:
     * - strips NUL (`\u0000`) and control characters
     * - extracts the leaf segment across `/` and `\`, rejecting `.` and `..` traversal components
     * - replaces FAT-illegal characters when [fatSafe] is true
     * - trims leading/trailing whitespace (and trailing dots on FAT)
     * - falls back to `[fallbackBase][.<ext>]` when empty or unsafe
     * - optionally appends an extension inferred from [mimeType] / [extraExtensionFallback] when missing
     * - caps UTF-8 byte length to [maxUtf8Bytes] while preserving the file extension
     */
    fun sanitizeFilename(
        rawName: String?,
        mimeType: String? = null,
        fatSafe: Boolean = false,
        fallbackBase: String = DEFAULT_FALLBACK_BASE,
        maxUtf8Bytes: Int = MAX_FILENAME_UTF8_BYTES,
        appendMimeExtensionIfMissing: Boolean = true,
        extraExtensionFallback: String? = null,
    ): String {
        val limit = maxUtf8Bytes.coerceIn(16, MAX_COLLISION_UTF8_BYTES)
        val extFromMime = resolveCleanExtension(mimeType, extraExtensionFallback)

        val candidate = extractSafeLeaf(rawName, fatSafe)
        val resolved = if (candidate.isEmpty()) {
            val base = sanitizeSingleSegment(fallbackBase, fatSafe).ifEmpty { DEFAULT_FALLBACK_BASE }
            if (extFromMime != null) "$base.$extFromMime" else base
        } else {
            val hasExt = PathUtils.splitBaseAndExtension(candidate, isDirectory = false).second.isNotEmpty()
            if (!hasExt && appendMimeExtensionIfMissing && extFromMime != null) {
                "$candidate.$extFromMime"
            } else {
                candidate
            }
        }

        val capped = capUtf8Filename(resolved, limit, fatSafe)
        val finalName = if (PathUtils.validateName(capped, fatSafe = fatSafe) is NameValidation.Valid) {
            capped
        } else {
            val fallback = if (extFromMime != null) "$DEFAULT_FALLBACK_BASE.$extFromMime" else DEFAULT_FALLBACK_BASE
            capUtf8Filename(fallback, limit, fatSafe)
        }
        return finalName
    }

    /**
     * Deterministic unique filename generator (`name (1).ext`, `name (2).ext`, ...) that also
     * enforces the 255-byte UTF-8 filesystem limit even when the desired filename is near max length.
     */
    fun uniqueFileName(
        desired: String,
        existingNames: Set<String>,
        caseInsensitive: Boolean = false,
        maxUtf8Bytes: Int = MAX_COLLISION_UTF8_BYTES,
    ): String {
        val limit = maxUtf8Bytes.coerceIn(16, MAX_COLLISION_UTF8_BYTES)
        val lookup = if (caseInsensitive) {
            existingNames.mapTo(HashSet(existingNames.size)) { it.lowercase(Locale.ROOT) }
        } else {
            existingNames
        }
        fun exists(name: String): Boolean =
            if (caseInsensitive) name.lowercase(Locale.ROOT) in lookup else name in lookup

        val initial = capUtf8Filename(desired, limit, fatSafe = false)
        if (!exists(initial)) return initial

        val (rawBase, rawExt) = splitPreservingReasonableExtension(initial, limit)
        var counter = 1
        while (true) {
            val suffix = " ($counter)"
            val reserved = suffix.toByteArray(Charsets.UTF_8).size + rawExt.toByteArray(Charsets.UTF_8).size
            val baseCap = (limit - reserved).coerceAtLeast(1)
            val trimmedBase = truncateUtf8(rawBase, baseCap).trimEnd()
                .ifEmpty { truncateUtf8(DEFAULT_FALLBACK_BASE, baseCap) }
            val candidate = "$trimmedBase$suffix$rawExt"
            if (!exists(candidate)) return candidate
            counter++
        }
    }

    /**
     * Resolves a filename collision against [existingNames] according to [policy].
     */
    fun resolveCollision(
        desiredName: String,
        existingNames: Set<String>,
        policy: PeerConflictPolicy,
        caseInsensitive: Boolean = false,
    ): CollisionDecision {
        val collides = if (caseInsensitive) {
            val lower = desiredName.lowercase(Locale.ROOT)
            existingNames.any { it.lowercase(Locale.ROOT) == lower }
        } else {
            desiredName in existingNames
        }
        if (!collides) {
            return CollisionDecision.Write(desiredName, overwrite = false)
        }
        return when (policy) {
            PeerConflictPolicy.RENAME -> CollisionDecision.Write(
                fileName = uniqueFileName(desiredName, existingNames, caseInsensitive),
                overwrite = false,
            )

            PeerConflictPolicy.OVERWRITE -> CollisionDecision.Write(
                fileName = desiredName,
                overwrite = true,
            )

            PeerConflictPolicy.SKIP -> CollisionDecision.Skip
        }
    }

    /**
     * Builds a filename and UTF-8 byte payload for plain-text `ACTION_SEND` intents.
     * Uses [subject] when present (sanitized and given a `.txt` extension), otherwise falls back
     * to `shared_note_<yyyyMMdd_HHmmss>.txt` in UTC for deterministic naming.
     */
    fun buildTextSharePayload(
        text: String,
        subject: String? = null,
        nowEpochMs: Long,
        fatSafe: Boolean = false,
    ): TextSharePayload {
        val cleanSubject = subject?.let { extractSafeLeaf(it, fatSafe) }.orEmpty()
        val fileName = if (cleanSubject.isNotEmpty()) {
            val baseCapped = if (cleanSubject.endsWith(".txt", ignoreCase = true)) {
                capUtf8Filename(cleanSubject, MAX_SUBJECT_BASE_UTF8_BYTES + 4, fatSafe)
            } else {
                val baseOnly = truncateUtf8(cleanSubject, MAX_SUBJECT_BASE_UTF8_BYTES).trimEnd(' ', '.')
                val effectiveBase = baseOnly.ifEmpty { timestampNoteBase(nowEpochMs) }
                "$effectiveBase.txt"
            }
            sanitizeFilename(
                rawName = baseCapped,
                mimeType = "text/plain",
                fatSafe = fatSafe,
                fallbackBase = timestampNoteBase(nowEpochMs),
                appendMimeExtensionIfMissing = true,
            )
        } else {
            "${timestampNoteBase(nowEpochMs)}.txt"
        }

        val preview = buildString {
            for (ch in text) {
                if (ch == '\r' || ch == '\n' || ch == '\t') {
                    append(' ')
                } else if (ch.code >= 0x20 && ch.code != 0x7F) {
                    append(ch)
                }
                if (length >= 200) break
            }
        }.replace(Regex("\\s+"), " ").trim()

        return TextSharePayload(
            fileName = fileName,
            bytes = text.toByteArray(Charsets.UTF_8),
            previewText = preview,
        )
    }

    /**
     * Security guard: returns `true` if [rawUri] is a `content:` URI whose authority belongs to
     * this app's own package (preventing confused-deputy exfiltration through our own non-exported
     * `FileProvider` or internal providers).
     */
    fun isBlockedContentUri(
        rawUri: String,
        packageName: String,
        ownProviderAuthorities: Collection<String> = emptyList(),
        authorityOwnerPackage: String? = null,
    ): Boolean {
        val trimmed = rawUri.trim()
        if (trimmed.isEmpty()) return false
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx <= 0) return false
        val scheme = trimmed.substring(0, colonIdx).lowercase(Locale.ROOT)
        if (scheme != "content") return false

        val authority = extractContentAuthority(trimmed) ?: return true
        val pkg = packageName.trim()
        if (pkg.isNotEmpty() && authorityOwnerPackage != null &&
            authorityOwnerPackage.trim().equals(pkg, ignoreCase = true)
        ) {
            return true
        }
        if (ownProviderAuthorities.any { it.trim().equals(authority, ignoreCase = true) }) {
            return true
        }
        if (pkg.isNotEmpty() && (
                authority.equals(pkg, ignoreCase = true) ||
                    authority.startsWith("$pkg.", ignoreCase = true)
                )
        ) {
            return true
        }
        return false
    }

    private fun extractContentAuthority(rawUri: String): String? {
        val afterScheme = rawUri.substringAfter(':')
        if (!afterScheme.startsWith("//")) return null
        val rest = afterScheme.substring(2)
        val rawAuthority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        // Strip optional Android multi-user prefix like "0@com.example.fileprovider"
        val stripped = rawAuthority.substringAfterLast('@').trim()
        return stripped.takeIf { it.isNotEmpty() }
    }

    /**
     * Security guard: returns `true` if [rawUri] is a `file:` URI that targets (or traverses into)
     * this app's private data directories, or if it is a malformed `file:` URI.
     *
     * Non-`file:` URIs (such as `content://`) return `false` here (the Android receiver separately
     * enforces allowed URI schemes and [isBlockedContentUri]).
     */
    fun isBlockedFileUri(
        rawUri: String,
        packageName: String,
        appDataDirs: Collection<String> = emptyList(),
        canonicalPathResolver: (String) -> String? = defaultCanonicalPathResolver,
    ): Boolean {
        val trimmed = rawUri.trim()
        if (trimmed.isEmpty()) return false
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx <= 0) return false
        val scheme = trimmed.substring(0, colonIdx).lowercase(Locale.ROOT)
        if (scheme != "file") return false

        val decodedPath = extractFileUriPath(trimmed) ?: return true
        return isPathInsideAppPrivateDirs(
            path = decodedPath,
            packageName = packageName,
            appDataDirs = appDataDirs,
            canonicalPathResolver = canonicalPathResolver,
        )
    }

    /**
     * Pure path guard checking whether [path] (both lexically normalized and canonicalized via
     * [canonicalPathResolver]) falls inside `/data/data/<pkg>`, `/data/user/<n>/<pkg>`,
     * `/data/user_de/<n>/<pkg>`, or any directory in [appDataDirs].
     */
    fun isPathInsideAppPrivateDirs(
        path: String,
        packageName: String,
        appDataDirs: Collection<String> = emptyList(),
        canonicalPathResolver: (String) -> String? = defaultCanonicalPathResolver,
    ): Boolean {
        if (path.isBlank() || path.contains('\u0000')) return true

        val normalizedInput = PathUtils.normalizeLocalPath(path)
        val canonicalInput = canonicalPathResolver(normalizedInput)
            ?.takeIf { it.isNotBlank() }
            ?.let { PathUtils.normalizeLocalPath(it) }

        val candidates = buildList {
            add(normalizedInput)
            if (canonicalInput != null && canonicalInput != normalizedInput) {
                add(canonicalInput)
            }
        }

        val normalizedAppDirs = buildSet {
            for (dir in appDataDirs) {
                val trimmed = dir.trim()
                if (trimmed.isEmpty()) continue
                add(PathUtils.normalizeLocalPath(trimmed))
                canonicalPathResolver(trimmed)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { add(PathUtils.normalizeLocalPath(it)) }
            }
        }

        val pkg = packageName.trim()
        for (candidate in candidates) {
            if (isBlockedProcPath(candidate)) return true
            for (privateDir in normalizedAppDirs) {
                if (isSameOrUnder(candidate, privateDir)) return true
            }
            if (pkg.isNotEmpty() && matchesAndroidPrivateDataPattern(candidate, pkg)) {
                return true
            }
        }
        return false
    }

    private fun extractFileUriPath(rawUri: String): String? {
        val parsed = runCatching { URI(rawUri) }.getOrNull()
        val rawPath = if (parsed != null && parsed.scheme?.equals("file", ignoreCase = true) == true) {
            val host = parsed.host
            if (!host.isNullOrEmpty() && !host.equals("localhost", ignoreCase = true)) {
                return null
            }
            parsed.rawPath ?: parsed.rawSchemeSpecificPart
        } else {
            val afterScheme = rawUri.substringAfter(':')
            when {
                afterScheme.startsWith("///") -> afterScheme.substring(2)
                afterScheme.startsWith("//localhost/", ignoreCase = true) -> afterScheme.substring(11)
                afterScheme.startsWith("//") -> return null
                else -> afterScheme
            }
        }
        if (rawPath.isNullOrEmpty()) return null
        val withoutQuery = rawPath.substringBefore('?').substringBefore('#')
        val decoded = decodePercentPath(withoutQuery) ?: return null
        if (decoded.contains('\u0000')) return null
        return decoded
    }

    private fun decodePercentPath(raw: String): String? {
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '%') {
                if (i + 2 >= raw.length || !isHex(raw[i + 1]) || !isHex(raw[i + 2])) {
                    return null
                }
                i += 3
            } else {
                i++
            }
        }
        return runCatching {
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        }.getOrNull()
    }

    private fun isHex(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun isBlockedProcPath(normalizedPath: String): Boolean {
        val segments = normalizedPath.split('/').filter { it.isNotEmpty() }
        if (segments.size < 2 || segments[0] != "proc") return false
        val target = segments[1]
        val isSelfOrPid = target == "self" || target == "thread-self" || target.all { it.isDigit() }
        if (!isSelfOrPid) return false
        if (segments.size == 2) return true
        return segments[2] in setOf("root", "cwd", "fd", "fdinfo", "task", "mem", "maps", "environ", "cmdline")
    }

    private fun matchesAndroidPrivateDataPattern(normalizedPath: String, pkg: String): Boolean {
        val segments = normalizedPath.split('/').filter { it.isNotEmpty() }
        // /data/data/<pkg>(/...)?
        if (segments.size >= 3 &&
            segments[0] == "data" &&
            segments[1] == "data" &&
            segments[2] == pkg
        ) {
            return true
        }
        // /data/user/<userId>/<pkg>(/...)? or /data/user_de/<userId>/<pkg>(/...)?
        if (segments.size >= 4 &&
            segments[0] == "data" &&
            (segments[1] == "user" || segments[1] == "user_de") &&
            segments[2].all { it.isDigit() } &&
            segments[3] == pkg
        ) {
            return true
        }
        // /mnt/expand/<uuid>/user/<userId>/<pkg> or /mnt/expand/<uuid>/user_de/<userId>/<pkg>
        if (segments.size >= 6 &&
            segments[0] == "mnt" &&
            segments[1] == "expand" &&
            (segments[3] == "user" || segments[3] == "user_de") &&
            segments[4].all { it.isDigit() } &&
            segments[5] == pkg
        ) {
            return true
        }
        // External app-private directories:
        // /sdcard/Android/(data|obb|media)/<pkg>
        // /storage/<vol>/Android/(data|obb|media)/<pkg>
        // /storage/emulated/<userId>/Android/(data|obb|media)/<pkg>
        // /mnt/user/<userId>/<vol>/Android/(data|obb|media)/<pkg>
        val androidIdx = when {
            segments.size >= 4 && segments[0] == "sdcard" -> 1
            segments.size >= 6 && segments[0] == "storage" && segments[1] == "emulated" && segments[2].all { it.isDigit() } -> 3
            segments.size >= 5 && segments[0] == "storage" -> 2
            segments.size >= 6 && segments[0] == "mnt" && segments[1] == "user" && segments[2].all { it.isDigit() } -> 4
            else -> -1
        }
        if (androidIdx >= 0 &&
            segments.size >= androidIdx + 3 &&
            segments[androidIdx] == "Android" &&
            segments[androidIdx + 1] in setOf("data", "obb", "media") &&
            segments[androidIdx + 2] == pkg
        ) {
            return true
        }
        return false
    }

    private fun isSameOrUnder(candidate: String, rootDir: String): Boolean {
        if (rootDir == "/") return false
        return candidate == rootDir || candidate.startsWith("$rootDir/")
    }

    private fun timestampNoteBase(nowEpochMs: Long): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return "${DEFAULT_TEXT_NOTE_PREFIX}_${fmt.format(Date(nowEpochMs))}"
    }

    private fun resolveCleanExtension(mimeType: String?, extraExtensionFallback: String?): String? {
        val fromTable = extensionForMimeType(mimeType)
        if (fromTable != null) return fromTable
        val cleanExtra = extraExtensionFallback
            ?.trim()
            ?.trimStart('.')
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() && it.length <= 12 && it.all { ch -> ch in 'a'..'z' || ch in '0'..'9' } }
        return cleanExtra
    }

    private fun extractSafeLeaf(rawName: String?, fatSafe: Boolean): String {
        if (rawName.isNullOrBlank()) return ""
        val stripped = stripControlChars(rawName)
        if (stripped.isEmpty()) return ""
        val segments = stripped.split('/', '\\')
        var lastIdx = segments.lastIndex
        while (lastIdx >= 0 && segments[lastIdx].isBlank()) {
            lastIdx--
        }
        if (lastIdx < 0) return ""
        val leaf = segments[lastIdx]
        return sanitizeSingleSegment(leaf, fatSafe)
    }

    private fun sanitizeSingleSegment(rawSegment: String, fatSafe: Boolean): String {
        val stripped = stripControlChars(rawSegment)
        val replaced = buildString(stripped.length) {
            for (ch in stripped) {
                if (ch == '/' || ch == '\\' || ch == '\u0000') continue
                if (fatSafe && ch in FAT_ILLEGAL_CHARS) {
                    append('_')
                } else {
                    append(ch)
                }
            }
        }
        var trimmed = replaced.trim()
        if (fatSafe) {
            trimmed = trimmed.trimEnd('.', ' ')
        }
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return ""
        if (trimmed.all { it == '.' || it.isWhitespace() }) return ""
        return trimmed
    }

    private fun stripControlChars(input: String): String = buildString(input.length) {
        var i = 0
        while (i < input.length) {
            val cp = Character.codePointAt(input, i)
            val charCount = Character.charCount(cp)
            val isUnsafeControl = cp == 0 ||
                cp < 0x20 ||
                cp == 0x7F ||
                cp == 0x2028 ||
                cp == 0x2029 ||
                Character.isISOControl(cp) ||
                Character.getType(cp) == Character.FORMAT.toInt()
            if (!isUnsafeControl) {
                appendCodePoint(cp)
            }
            i += charCount
        }
    }

    private fun capUtf8Filename(name: String, maxUtf8Bytes: Int, fatSafe: Boolean): String {
        if (name.toByteArray(Charsets.UTF_8).size <= maxUtf8Bytes) return name
        val (base, ext) = splitPreservingReasonableExtension(name, maxUtf8Bytes)
        val extBytes = ext.toByteArray(Charsets.UTF_8).size
        val baseBudget = (maxUtf8Bytes - extBytes).coerceAtLeast(1)
        var truncatedBase = truncateUtf8(base, baseBudget).trimEnd()
        if (fatSafe) {
            truncatedBase = truncatedBase.trimEnd('.', ' ')
        }
        if (truncatedBase.isEmpty() || truncatedBase == "." || truncatedBase == "..") {
            truncatedBase = truncateUtf8(DEFAULT_FALLBACK_BASE, baseBudget)
        }
        return "$truncatedBase$ext"
    }

    private fun splitPreservingReasonableExtension(name: String, maxUtf8Bytes: Int): Pair<String, String> {
        val (base, ext) = PathUtils.splitBaseAndExtension(name, isDirectory = false)
        val extBytes = ext.toByteArray(Charsets.UTF_8).size
        if (ext.isEmpty() || extBytes > MAX_EXTENSION_UTF8_BYTES || extBytes >= maxUtf8Bytes - 1) {
            return name to ""
        }
        return base to ext
    }

    private fun truncateUtf8(input: String, maxBytes: Int): String {
        if (maxBytes <= 0 || input.isEmpty()) return ""
        if (input.toByteArray(Charsets.UTF_8).size <= maxBytes) return input
        val sb = StringBuilder()
        var usedBytes = 0
        var i = 0
        while (i < input.length) {
            val cp = Character.codePointAt(input, i)
            val charCount = Character.charCount(cp)
            val cpUtf8Bytes = when {
                cp <= 0x7F -> 1
                cp <= 0x7FF -> 2
                cp <= 0xFFFF -> 3
                else -> 4
            }
            if (usedBytes + cpUtf8Bytes > maxBytes) break
            sb.appendCodePoint(cp)
            usedBytes += cpUtf8Bytes
            i += charCount
        }
        return sb.toString()
    }
}
