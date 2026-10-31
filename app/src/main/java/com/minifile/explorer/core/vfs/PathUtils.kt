package com.minifile.explorer.core.vfs

import java.util.Locale

sealed interface NameValidation {
    data object Valid : NameValidation
    data class Invalid(val reason: String) : NameValidation
}

object PathUtils {
    private val FAT_ILLEGAL_CHARS = setOf('\\', ':', '*', '?', '"', '<', '>', '|')

    val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "json", "xml", "yml", "yaml", "log", "csv", "tsv",
        "ini", "conf", "cfg", "properties", "env", "py", "sh", "bash", "zsh", "bat",
        "js", "mjs", "cjs", "ts", "tsx", "jsx", "kt", "kts", "java", "c", "cpp", "cc",
        "h", "hpp", "rs", "go", "rb", "php", "html", "htm", "css", "scss", "gradle",
        "toml", "nomedia", "gitignore", "gitattributes", "sql", "csv",
    )

    val ARCHIVE_EXTENSIONS = setOf("zip", "jar", "apk")

    fun normalizeLocalPath(raw: String): String {
        if (raw.isEmpty()) return "/"
        val replaced = raw.replace('\\', '/')
        val segments = ArrayDeque<String>()
        for (part in replaced.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeLast()
                else -> segments.addLast(part)
            }
        }
        return if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
    }

    fun isHiddenName(name: String): Boolean =
        name.startsWith(".") && name != "." && name != ".."

    fun validateName(name: String, fatSafe: Boolean = false): NameValidation {
        if (name.isEmpty()) return NameValidation.Invalid("Name cannot be empty")
        if (name.isBlank()) return NameValidation.Invalid("Name cannot be blank")
        if (name == "." || name == "..") return NameValidation.Invalid("Reserved directory name")
        if (name.contains('/') || name.contains('\u0000')) {
            return NameValidation.Invalid("Name contains illegal path character")
        }
        if (name.toByteArray(Charsets.UTF_8).size > 255) {
            return NameValidation.Invalid("Name is too long (max 255 UTF-8 bytes)")
        }
        if (fatSafe) {
            val bad = name.firstOrNull { it in FAT_ILLEGAL_CHARS || it.code < 0x20 }
            if (bad != null) {
                return NameValidation.Invalid("Character '$bad' is not allowed on external storage")
            }
            if (name.endsWith('.') || name.endsWith(' ')) {
                return NameValidation.Invalid("Name cannot end with a dot or space on FAT volumes")
            }
        }
        return NameValidation.Valid
    }

    fun splitBaseAndExtension(name: String, isDirectory: Boolean = false): Pair<String, String> {
        if (isDirectory) return name to ""
        val lastDot = name.lastIndexOf('.')
        if (lastDot <= 0 || lastDot == name.length - 1) {
            return name to ""
        }
        return name.substring(0, lastDot) to name.substring(lastDot)
    }

    fun uniqueName(
        desired: String,
        existingNames: Set<String>,
        isDirectory: Boolean = false,
        caseInsensitive: Boolean = false,
    ): String {
        val lookup = if (caseInsensitive) {
            existingNames.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        } else {
            existingNames
        }
        fun exists(candidate: String): Boolean =
            if (caseInsensitive) candidate.lowercase(Locale.ROOT) in lookup else candidate in lookup

        if (!exists(desired)) return desired
        val (base, ext) = splitBaseAndExtension(desired, isDirectory)
        var counter = 1
        while (true) {
            val candidate = "$base ($counter)$ext"
            if (!exists(candidate)) return candidate
            counter++
        }
    }

    /**
     * Returns true if [ancestor] equals [candidate] OR [candidate] is located strictly under [ancestor].
     */
    fun isAncestor(ancestor: VfsPath, candidate: VfsPath): Boolean =
        isSameOrDescendant(ancestor, candidate)

    fun isSameOrDescendant(ancestor: VfsPath, candidate: VfsPath): Boolean =
        ancestor == candidate || isStrictDescendant(ancestor, candidate)

    fun isStrictDescendant(ancestor: VfsPath, candidate: VfsPath): Boolean {
        return when {
            ancestor is VfsPath.Local && candidate is VfsPath.Local -> {
                val a = ancestor.normalized
                val c = candidate.normalized
                if (a == c) false
                else if (a == "/") c.startsWith("/") && c != "/"
                else c.startsWith("$a/")
            }

            ancestor is VfsPath.Saf && candidate is VfsPath.Saf -> {
                if (ancestor.treeUri != candidate.treeUri) return false
                val aDoc = ancestor.documentId
                val cDoc = candidate.documentId
                if (aDoc == cDoc) return false
                when {
                    aDoc.isEmpty() || aDoc == "/" -> cDoc.isNotEmpty() && cDoc != "/"
                    aDoc.endsWith(":") -> cDoc.startsWith(aDoc) && cDoc.length > aDoc.length
                    else -> {
                        val prefix = aDoc.trimEnd('/') + "/"
                        cDoc.startsWith(prefix)
                    }
                }
            }

            ancestor is VfsPath.Ftp && candidate is VfsPath.Ftp -> {
                if (ancestor.serverId != candidate.serverId) return false
                val a = ancestor.normalizedRemotePath
                val c = candidate.normalizedRemotePath
                if (a == c) false
                else if (a == "/") c.startsWith("/") && c != "/"
                else c.startsWith("$a/")
            }

            else -> false
        }
    }

    /**
     * Natural case-insensitive comparator ("file2" < "file10") with deterministic tie-breaker.
     */
    fun naturalCompare(a: String, b: String): Int {
        if (a == b) return 0
        var ia = 0
        var ib = 0
        val na = a.length
        val nb = b.length

        while (ia < na && ib < nb) {
            val ca = a[ia]
            val cb = b[ib]
            if (ca.isDigit() && cb.isDigit()) {
                val startA = ia
                while (ia < na && a[ia].isDigit()) ia++
                val startB = ib
                while (ib < nb && b[ib].isDigit()) ib++

                val numA = a.substring(startA, ia).trimStart('0')
                val numB = b.substring(startB, ib).trimStart('0')
                if (numA.length != numB.length) {
                    return numA.length.compareTo(numB.length)
                }
                val cmpDigits = numA.compareTo(numB)
                if (cmpDigits != 0) return cmpDigits
                val lenDiff = (ia - startA).compareTo(ib - startB)
                if (lenDiff != 0) return lenDiff
            } else {
                val la = ca.lowercaseChar()
                val lb = cb.lowercaseChar()
                if (la != lb) return la.compareTo(lb)
                ia++
                ib++
            }
        }
        if (ia < na) return 1
        if (ib < nb) return -1
        return a.compareTo(b)
    }

    fun extensionOf(name: String): String {
        val trimmed = name.trimStart('.')
        if (name.startsWith('.') && !trimmed.contains('.')) {
            return trimmed.lowercase(Locale.ROOT)
        }
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase(Locale.ROOT)
    }

    fun isKnownTextFileName(name: String): Boolean {
        val ext = extensionOf(name)
        return ext in TEXT_EXTENSIONS
    }

    fun isArchiveFileName(name: String): Boolean {
        val ext = extensionOf(name)
        return ext in ARCHIVE_EXTENSIONS
    }

    fun guessMimeType(name: String): String {
        return when (extensionOf(name)) {
            "txt", "log", "ini", "conf", "cfg", "properties", "env", "gitignore" -> "text/plain"
            "md", "markdown" -> "text/markdown"
            "json" -> "application/json"
            "xml" -> "text/xml"
            "html", "htm" -> "text/html"
            "css", "scss" -> "text/css"
            "csv" -> "text/csv"
            "yml", "yaml" -> "text/yaml"
            "js", "mjs", "cjs", "ts", "tsx", "jsx" -> "text/javascript"
            "py", "sh", "bash", "kt", "kts", "java", "c", "cpp", "h", "hpp", "rs", "go", "toml", "gradle" -> "text/plain"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            "m4a", "aac" -> "audio/mp4"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "jar" -> "application/java-archive"
            "gz", "tgz" -> "application/gzip"
            "7z" -> "application/x-7z-compressed"
            "rar" -> "application/vnd.rar"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unitIdx = 0
        while (value >= 1024.0 && unitIdx < units.lastIndex) {
            value /= 1024.0
            unitIdx++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unitIdx])
    }
}
