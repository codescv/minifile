package com.minifile.explorer.core.gallery

import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.MiniJson
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsPath
import java.util.Locale

enum class FolderLensOverride {
    FILES,
    GALLERY,
}

data class FolderModeEntry(
    val mode: FolderLensOverride,
    val lastUsedMs: Long,
    val alwaysFiles: Boolean = false,
)

/**
 * Bounded LRU (default 64) map of per-directory lens overrides with nearest-ancestor resolution.
 * Pure JVM, serializable to JSON via [MiniJson].
 */
data class FolderModePreferences(
    val entries: Map<String, FolderModeEntry> = emptyMap(),
    val maxEntries: Int = MAX_ENTRIES,
) {
    fun record(
        path: VfsPath,
        mode: FolderLensOverride,
        nowMs: Long,
        alwaysFiles: Boolean = false,
    ): FolderModePreferences {
        val key = canonicalKey(path)
        val updated = LinkedHashMap(entries)
        updated[key] = FolderModeEntry(
            mode = mode,
            lastUsedMs = nowMs,
            alwaysFiles = alwaysFiles && mode == FolderLensOverride.FILES,
        )
        while (updated.size > maxEntries) {
            val oldestKey = updated.entries.minByOrNull { it.value.lastUsedMs }?.key ?: break
            updated.remove(oldestKey)
        }
        return copy(entries = updated)
    }

    fun remove(path: VfsPath): FolderModePreferences {
        val key = canonicalKey(path)
        if (key !in entries) return this
        val updated = LinkedHashMap(entries)
        updated.remove(key)
        return copy(entries = updated)
    }

    fun clear(): FolderModePreferences = copy(entries = emptyMap())

    fun touch(path: VfsPath, nowMs: Long): FolderModePreferences {
        val key = canonicalKey(path)
        val existing = entries[key] ?: return this
        val updated = LinkedHashMap(entries)
        updated[key] = existing.copy(lastUsedMs = nowMs)
        return copy(entries = updated)
    }

    /**
     * Resolves the override for [path] or its nearest (deepest) ancestor.
     */
    fun resolveNearestAncestor(path: VfsPath): Pair<VfsPath, FolderModeEntry>? {
        for (candidate in ancestorChain(path)) {
            val entry = entries[canonicalKey(candidate)]
            if (entry != null) return candidate to entry
        }
        return null
    }

    fun toJsonString(): String {
        val pairs = entries.entries.map { (k, v) ->
            k to MiniJson.obj(
                "mode" to JsonValue.Str(v.mode.name),
                "ts" to JsonValue.Num(v.lastUsedMs),
                "alwaysFiles" to JsonValue.Bool(v.alwaysFiles),
            )
        }
        return MiniJson.stringify(MiniJson.obj(*pairs.toTypedArray()))
    }

    companion object {
        const val MAX_ENTRIES = 64

        fun canonicalKey(path: VfsPath): String = when (path) {
            is VfsPath.Local -> "file://${path.normalized.trimEnd('/').ifEmpty { "/" }}"
            is VfsPath.Saf -> "saf:${path.treeUri}#${path.documentId.trimEnd('/')}"
            is VfsPath.Ftp -> path.serialize()
            is VfsPath.GDrive -> path.serialize()
        }

        fun ancestorChain(path: VfsPath): List<VfsPath> {
            val chain = mutableListOf<VfsPath>()
            when (path) {
                is VfsPath.Local -> {
                    var cur = path.normalized
                    while (true) {
                        chain.add(VfsPath.Local(cur))
                        if (cur == "/") break
                        val idx = cur.lastIndexOf('/')
                        cur = if (idx <= 0) "/" else cur.substring(0, idx)
                    }
                }

                is VfsPath.Saf -> {
                    val doc = path.documentId.trimEnd('/')
                    val colon = doc.indexOf(':')
                    if (colon < 0) {
                        var cur = PathUtils.normalizeLocalPath(doc)
                        while (true) {
                            chain.add(VfsPath.Saf(path.treeUri, cur))
                            if (cur == "/") break
                            val idx = cur.lastIndexOf('/')
                            cur = if (idx <= 0) "/" else cur.substring(0, idx)
                        }
                    } else {
                        val prefix = doc.substring(0, colon + 1)
                        val rel = doc.substring(colon + 1).trim('/')
                        val segs = if (rel.isEmpty()) emptyList() else rel.split('/').filter { it.isNotEmpty() }
                        for (len in segs.size downTo 1) {
                            val sub = segs.subList(0, len).joinToString("/")
                            chain.add(VfsPath.Saf(path.treeUri, "$prefix$sub"))
                        }
                        chain.add(VfsPath.Saf(path.treeUri, prefix))
                    }
                }

                is VfsPath.Ftp -> {
                    var cur = path.normalizedRemotePath
                    while (true) {
                        chain.add(VfsPath.Ftp(path.serverId, cur))
                        if (cur == "/") break
                        val idx = cur.lastIndexOf('/')
                        cur = if (idx <= 0) "/" else cur.substring(0, idx)
                    }
                }

                is VfsPath.GDrive -> {
                    var cur = path.normalizedRemotePath
                    while (true) {
                        chain.add(VfsPath.GDrive(path.serverId, cur))
                        if (cur == "/") break
                        val idx = cur.lastIndexOf('/')
                        cur = if (idx <= 0) "/" else cur.substring(0, idx)
                    }
                }
            }
            return chain
        }

        fun fromJsonString(raw: String?, maxEntries: Int = MAX_ENTRIES): FolderModePreferences {
            if (raw.isNullOrBlank()) return FolderModePreferences(emptyMap(), maxEntries)
            val root = runCatching { MiniJson.parse(raw) as? JsonValue.Obj }.getOrNull()
                ?: return FolderModePreferences(emptyMap(), maxEntries)
            val parsed = LinkedHashMap<String, FolderModeEntry>()
            for ((k, v) in root.entries) {
                val obj = v as? JsonValue.Obj ?: continue
                val modeStr = obj.str("mode") ?: continue
                val mode = FolderLensOverride.entries.firstOrNull { it.name == modeStr } ?: continue
                val ts = obj.long("ts", 0L)
                val alwaysFiles = obj.bool("alwaysFiles", false)
                parsed[k] = FolderModeEntry(mode = mode, lastUsedMs = ts, alwaysFiles = alwaysFiles)
            }
            while (parsed.size > maxEntries) {
                val oldestKey = parsed.entries.minByOrNull { it.value.lastUsedMs }?.key ?: break
                parsed.remove(oldestKey)
            }
            return FolderModePreferences(parsed, maxEntries)
        }
    }
}

data class ScopeDecision(
    val lens: BrowseLens,
    val scope: GalleryScope?,
    val isMediaRootAggregate: Boolean = false,
    val hiddenByNomedia: Boolean = false,
    val isAutoActivated: Boolean = false,
)

/**
 * Pure JVM policy deciding whether a directory opens in Gallery or Files lens.
 * Supports Local + SAF (including camera SD cards like `1A2B-3C4D:DCIM/100CANON`);
 * FTP & Google Drive never auto-activate.
 */
object GalleryScopePolicy {
    private val AUTO_MEDIA_ROOTS = setOf("dcim", "pictures")

    /**
     * Returns path segments starting from the matched media root (`DCIM` or `Pictures`),
     * e.g. `["DCIM"]` or `["DCIM", "100CANON"]`, or `null` if [path] is not inside a media root
     * (or is on a non-auto backend like FTP/GDrive).
     */
    fun relativeMediaSegments(
        path: VfsPath,
        volumeRoots: List<VfsPath> = emptyList(),
    ): List<String>? {
        val relative = when (path) {
            is VfsPath.Local -> extractLocalRelativeSegments(path, volumeRoots)
            is VfsPath.Saf -> extractSafRelativeSegments(path, volumeRoots)
            is VfsPath.Ftp, is VfsPath.GDrive -> return null
        } ?: return null

        if (relative.isEmpty()) return null
        val first = relative[0].lowercase(Locale.ROOT)
        if (first !in AUTO_MEDIA_ROOTS) return null
        return relative
    }

    fun isMediaRoot(path: VfsPath, volumeRoots: List<VfsPath> = emptyList()): Boolean {
        val segs = relativeMediaSegments(path, volumeRoots) ?: return false
        return segs.size == 1
    }

    fun isInsideMediaRoot(path: VfsPath, volumeRoots: List<VfsPath> = emptyList()): Boolean =
        relativeMediaSegments(path, volumeRoots) != null

    /**
     * Returns true if any segment under the media root (or the media root itself) starts with `.`
     * (e.g. `.thumbnails`, `.trashed-123`, `.pending-123`, `.cache`).
     */
    fun hasHiddenDotSegmentUnderMediaRoot(
        path: VfsPath,
        volumeRoots: List<VfsPath> = emptyList(),
    ): Boolean {
        val segs = relativeMediaSegments(path, volumeRoots) ?: return false
        for (i in 1 until segs.size) {
            if (segs[i].startsWith(".")) return true
        }
        return false
    }

    fun isPendingOrTrashedName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.startsWith(".pending-") || lower.startsWith(".trashed-")
    }

    fun isExcludedDirectoryName(name: String): Boolean = name.startsWith(".")

    /**
     * Computes the media root [VfsPath] corresponding to [path], if [path] is inside `DCIM` or `Pictures`.
     */
    fun mediaRootFor(path: VfsPath, volumeRoots: List<VfsPath> = emptyList()): VfsPath? {
        val segs = relativeMediaSegments(path, volumeRoots) ?: return null
        if (segs.size == 1) return path
        return when (path) {
            is VfsPath.Local -> {
                val base = matchingLocalVolumeRoot(path, volumeRoots) ?: return null
                val prefix = if (base == "/") "" else base.trimEnd('/')
                VfsPath.Local("$prefix/${segs[0]}")
            }

            is VfsPath.Saf -> {
                val doc = path.documentId
                val colon = doc.indexOf(':')
                if (colon >= 0) {
                    val volPrefix = doc.substring(0, colon + 1)
                    VfsPath.Saf(path.treeUri, "$volPrefix${segs[0]}")
                } else {
                    VfsPath.Saf(path.treeUri, "/${segs[0]}")
                }
            }

            is VfsPath.Ftp, is VfsPath.GDrive -> null
        }
    }

    fun decide(
        dir: VfsPath,
        volumeRoots: List<VfsPath> = emptyList(),
        autoOpenMediaFolders: Boolean = true,
        overrides: FolderModePreferences = FolderModePreferences(),
        hasNomediaInDirOrAncestorToMediaRoot: Boolean = false,
        mediaCountInSubtree: Int = 1,
    ): ScopeDecision {
        // 1. Remote backends (FTP / GDrive) never auto-open; only explicit override = GALLERY activates.
        if (dir is VfsPath.Ftp || dir is VfsPath.GDrive) {
            val override = overrides.resolveNearestAncestor(dir)?.second
            return if (override?.mode == FolderLensOverride.GALLERY) {
                ScopeDecision(
                    lens = BrowseLens.GALLERY,
                    scope = GalleryScope.Folder(dir = dir, includeSubtree = false, hiddenByNomedia = false),
                    isAutoActivated = false,
                )
            } else {
                ScopeDecision(lens = BrowseLens.FILES, scope = null)
            }
        }

        // 2. Nearest ancestor manual override takes priority (LRU 64).
        val resolvedOverride = overrides.resolveNearestAncestor(dir)
        if (resolvedOverride != null) {
            val (matchedPath, override) = resolvedOverride
            val isInheritedFromAncestor = FolderModePreferences.canonicalKey(matchedPath) !=
                FolderModePreferences.canonicalKey(dir)
            return when (override.mode) {
                FolderLensOverride.FILES -> ScopeDecision(lens = BrowseLens.FILES, scope = null)
                FolderLensOverride.GALLERY -> {
                    // Inherited ancestor GALLERY overrides MUST still respect dot-segment exclusions
                    // (e.g. DCIM/.thumbnails) and require media in the subtree (so toggling GALLERY
                    // on a broad ancestor never turns empty/non-media folders into galleries).
                    if (isInheritedFromAncestor) {
                        if (hasAnyDotSegment(dir, matchedPath) || hasHiddenDotSegmentUnderMediaRoot(dir, volumeRoots)) {
                            return ScopeDecision(lens = BrowseLens.FILES, scope = null)
                        }
                        if (mediaCountInSubtree <= 0) {
                            return ScopeDecision(lens = BrowseLens.FILES, scope = null)
                        }
                    }
                    val rootAgg = isMediaRoot(dir, volumeRoots)
                    ScopeDecision(
                        lens = BrowseLens.GALLERY,
                        scope = GalleryScope.Folder(
                            dir = dir,
                            includeSubtree = !hasNomediaInDirOrAncestorToMediaRoot,
                            hiddenByNomedia = hasNomediaInDirOrAncestorToMediaRoot,
                        ),
                        isMediaRootAggregate = rootAgg,
                        hiddenByNomedia = hasNomediaInDirOrAncestorToMediaRoot,
                        isAutoActivated = false,
                    )
                }
            }
        }

        // 3. Global auto-gallery toggle.
        if (!autoOpenMediaFolders) {
            return ScopeDecision(lens = BrowseLens.FILES, scope = null)
        }

        // 4. Strict segment boundary check for DCIM / Pictures.
        val segs = relativeMediaSegments(dir, volumeRoots)
            ?: return ScopeDecision(lens = BrowseLens.FILES, scope = null)

        // 5. Exclude hidden dot-prefixed segments (.thumbnails, .trashed-*, .pending-*, .cache).
        if (hasHiddenDotSegmentUnderMediaRoot(dir, volumeRoots)) {
            return ScopeDecision(lens = BrowseLens.FILES, scope = null)
        }

        // 6. Empty or non-media-only directory falls back to Files lens.
        if (mediaCountInSubtree <= 0) {
            return ScopeDecision(lens = BrowseLens.FILES, scope = null)
        }

        // 7. .nomedia isolation: direct VFS gallery listing marked hidden, never merged into parent.
        if (hasNomediaInDirOrAncestorToMediaRoot) {
            return ScopeDecision(
                lens = BrowseLens.GALLERY,
                scope = GalleryScope.Folder(
                    dir = dir,
                    includeSubtree = false,
                    hiddenByNomedia = true,
                ),
                isMediaRootAggregate = false,
                hiddenByNomedia = true,
                isAutoActivated = true,
            )
        }

        // 8. DCIM / Pictures root always aggregates subtree + subfolder chips; subfolders show subtree timeline.
        val isRoot = segs.size == 1
        return ScopeDecision(
            lens = BrowseLens.GALLERY,
            scope = GalleryScope.Folder(
                dir = dir,
                includeSubtree = true,
                hiddenByNomedia = false,
            ),
            isMediaRootAggregate = isRoot,
            hiddenByNomedia = false,
            isAutoActivated = true,
        )
    }

    private fun hasAnyDotSegment(dir: VfsPath, ancestor: VfsPath): Boolean {
        val segs = when {
            dir is VfsPath.Local && ancestor is VfsPath.Local -> {
                val base = ancestor.normalized.trimEnd('/')
                val rel = dir.normalized.removePrefix(base).trim('/')
                rel.split('/').filter { it.isNotEmpty() }
            }

            dir is VfsPath.Saf && ancestor is VfsPath.Saf -> {
                val base = ancestor.documentId.trimEnd('/')
                val rel = dir.documentId.removePrefix(base).trim('/')
                rel.split('/').filter { it.isNotEmpty() }
            }

            else -> emptyList()
        }
        return segs.any { it.startsWith(".") }
    }

    private fun extractLocalRelativeSegments(
        path: VfsPath.Local,
        volumeRoots: List<VfsPath>,
    ): List<String>? {
        val base = matchingLocalVolumeRoot(path, volumeRoots) ?: return null
        val norm = path.normalized
        val rel = when {
            norm == base -> return emptyList()
            base == "/" -> norm.removePrefix("/")
            norm.startsWith("$base/") -> norm.removePrefix("$base/")
            else -> return null
        }
        return rel.split('/').filter { it.isNotEmpty() }
    }

    private fun matchingLocalVolumeRoot(
        path: VfsPath.Local,
        volumeRoots: List<VfsPath>,
    ): String? {
        val norm = path.normalized
        val matchedExplicit = volumeRoots
            .filterIsInstance<VfsPath.Local>()
            .map { it.normalized }
            .filter { root ->
                norm == root || (root == "/" && norm.startsWith("/")) || norm.startsWith("$root/")
            }
            .maxByOrNull { it.length }
        if (matchedExplicit != null) return matchedExplicit

        // Standard Android storage mount prefixes fallback when volumeRoots is empty:
        // /storage/emulated/<user>/... or /storage/<volume-id>/... or /sdcard/...
        val segs = norm.trimStart('/').split('/').filter { it.isNotEmpty() }
        if (segs.size >= 3 && segs[0] == "storage" && segs[1] == "emulated") {
            return "/storage/emulated/${segs[2]}"
        }
        if (segs.size >= 2 && segs[0] == "storage" && segs[1] != "emulated") {
            return "/storage/${segs[1]}"
        }
        if (segs.isNotEmpty() && segs[0] == "sdcard") {
            return "/sdcard"
        }
        return null
    }

    private fun extractSafRelativeSegments(
        path: VfsPath.Saf,
        volumeRoots: List<VfsPath>,
    ): List<String>? {
        val doc = path.documentId.trim()
        if (doc.isEmpty()) return null
        val colon = doc.indexOf(':')
        val relPart = if (colon >= 0) {
            doc.substring(colon + 1)
        } else {
            val matchedRoot = volumeRoots
                .filterIsInstance<VfsPath.Saf>()
                .filter { it.treeUri == path.treeUri }
                .map { PathUtils.normalizeLocalPath(it.documentId) }
                .filter { root ->
                    val normDoc = PathUtils.normalizeLocalPath(doc)
                    normDoc == root || root == "/" || normDoc.startsWith("$root/")
                }
                .maxByOrNull { it.length }
            val normDoc = PathUtils.normalizeLocalPath(doc)
            if (matchedRoot != null && matchedRoot != "/") {
                normDoc.removePrefix(matchedRoot)
            } else {
                normDoc
            }
        }
        return relPart.trim('/').split('/').filter { it.isNotEmpty() }
    }
}
