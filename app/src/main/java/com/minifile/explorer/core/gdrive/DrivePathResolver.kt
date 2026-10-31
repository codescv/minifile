package com.minifile.explorer.core.gdrive

import com.minifile.explorer.core.model.Clock
import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsPath
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class ResolvedDriveEntry(
    val meta: DriveEntryMeta,
    val fromCache: Boolean,
)

/**
 * Per-server Google Drive path -> ID resolver and coherent metadata + directory listing cache (§3.2).
 *
 * - `meta`: positive entries only (`META_TTL_MS = 60_000L`), zero negative caching.
 * - `listing`: complete child map for a directory (`LISTING_TTL_MS = 30_000L`), edited in place on
 *   single-item mutations so bulk transfers do not re-list after every file (M3).
 * - Deterministic collision naming (`M4`): `(folder-first, createdTimeMs, id)` with Pass 1 reserving
 *   exact non-Workspace display names before Workspace `.pdf` / `(Google Doc).pdf` and `[k]` suffixes.
 */
class DrivePathResolver(
    private val http: DriveHttp,
    private val clock: Clock = Clock.SYSTEM,
    private val metaTtlMs: Long = META_TTL_MS,
    private val listingTtlMs: Long = LISTING_TTL_MS,
) {
    companion object {
        const val META_TTL_MS = 60_000L
        const val LISTING_TTL_MS = 30_000L
        const val LIST_PAGE_SIZE = 200

        private val SUFFIX_COUNTER_REGEX = Regex("""^(.*) \[([2-9]|\d{2,})](\.[^.]*)?$""")
        private val UNNAMED_DISPLAY_REGEX = Regex("""^\(unnamed .+\)$""")

        val ENTRY_DETERMINISTIC_ORDER: Comparator<DriveEntryMeta> = Comparator { a, b ->
            if (a.isFolder != b.isFolder) {
                return@Comparator if (a.isFolder) -1 else 1
            }
            val timeCmp = a.createdTimeMs.compareTo(b.createdTimeMs)
            if (timeCmp != 0) return@Comparator timeCmp
            a.id.compareTo(b.id)
        }

        /**
         * Assigns collision-free display names to all raw entries of a single Drive folder (B1, M4).
         */
        fun assignDisplayNames(rawEntries: List<DriveEntryMeta>): LinkedHashMap<String, DriveEntryMeta> {
            val sorted = rawEntries.sortedWith(ENTRY_DETERMINISTIC_ORDER)
            val sanitised = Array(sorted.size) { idx ->
                val e = sorted[idx]
                toDisplayName(e.name, e.id)
            }

            val assigned = arrayOfNulls<String>(sorted.size)
            val claimed = HashSet<String>(sorted.size * 2)

            // Pass 1: First-seen non-Workspace exact sanitised names always win their exact name.
            for (i in sorted.indices) {
                val entry = sorted[i]
                if (!entry.isWorkspaceDoc) {
                    val san = sanitised[i]
                    if (claimed.add(san)) {
                        assigned[i] = san
                    }
                }
            }

            // Pass 2: Workspace docs (.pdf -> (Google Doc).pdf -> [k].pdf) and duplicate non-Workspace items ([k]).
            for (i in sorted.indices) {
                if (assigned[i] != null) continue
                val entry = sorted[i]
                val san = sanitised[i]
                if (entry.isWorkspaceDoc) {
                    val primaryPdf = "$san.pdf"
                    if (claimed.add(primaryPdf)) {
                        assigned[i] = primaryPdf
                        continue
                    }
                    val docPdf = "$san (Google Doc).pdf"
                    if (claimed.add(docPdf)) {
                        assigned[i] = docPdf
                        continue
                    }
                    var k = 2
                    while (true) {
                        val candidate = "$san [$k].pdf"
                        if (claimed.add(candidate)) {
                            assigned[i] = candidate
                            break
                        }
                        k++
                    }
                } else {
                    val (stem, ext) = PathUtils.splitBaseAndExtension(san, isDirectory = entry.isFolder)
                    var k = 2
                    while (true) {
                        val candidate = "$stem [$k]$ext"
                        if (claimed.add(candidate)) {
                            assigned[i] = candidate
                            break
                        }
                        k++
                    }
                }
            }

            val result = LinkedHashMap<String, DriveEntryMeta>(sorted.size)
            for (i in sorted.indices) {
                val disp = assigned[i]!!
                result[disp] = sorted[i].copy(displayName = disp)
            }
            return result
        }

        private fun rootEntry(): DriveEntryMeta = DriveEntryMeta(
            id = "root",
            name = "/",
            displayName = "/",
            mimeType = FOLDER_MIME,
            isFolder = true,
            size = 0L,
            modifiedTimeMs = 0L,
            canWrite = true,
            isShortcut = false,
            shortcutTargetId = null,
            isWorkspaceDoc = false,
        )
    }

    private data class CachedMeta(
        val entry: DriveEntryMeta,
        val cachedAtMs: Long,
    )

    private data class CachedListing(
        val children: LinkedHashMap<String, DriveEntryMeta>,
        val completeAtMs: Long,
    )

    private class ServerCache {
        val lock = ReentrantReadWriteLock()
        val meta = HashMap<String, CachedMeta>()
        val listing = HashMap<String, CachedListing>()
    }

    private val caches = ConcurrentHashMap<String, ServerCache>()

    private fun cacheFor(serverId: String): ServerCache =
        caches.computeIfAbsent(serverId) { ServerCache() }

    private fun joinNormPath(parentNorm: String, childDisplay: String): String =
        if (parentNorm == "/") "/$childDisplay" else "$parentNorm/$childDisplay"

    private fun parentNormPath(normPath: String): String {
        if (normPath == "/") return "/"
        val slash = normPath.lastIndexOf('/')
        return if (slash <= 0) "/" else normPath.substring(0, slash)
    }

    // =========================================================================
    // Public invalidation API
    // =========================================================================

    fun invalidate(path: VfsPath.GDrive) {
        val cache = caches[path.serverId] ?: return
        val norm = path.normalizedRemotePath
        cache.lock.write {
            if (norm == "/") {
                cache.meta.clear()
                cache.listing.clear()
            } else {
                removeSubtreeLocked(cache, norm)
                cache.listing.remove(parentNormPath(norm))
            }
        }
    }

    fun invalidateServer(serverId: String) {
        caches.remove(serverId)
    }

    fun evictPathAndParentListing(path: VfsPath.GDrive) {
        val cache = caches[path.serverId] ?: return
        val norm = path.normalizedRemotePath
        cache.lock.write {
            removeSubtreeLocked(cache, norm)
            cache.listing.remove(parentNormPath(norm))
        }
    }

    // =========================================================================
    // Directory listing (with 30 s complete-listing cache)
    // =========================================================================

    suspend fun listDirectory(
        dir: VfsPath.GDrive,
        tokenProvider: DriveTokenProvider,
    ): List<DriveEntryMeta> {
        val normDir = dir.normalizedRemotePath
        val cache = cacheFor(dir.serverId)
        val now = clock.nowMs()

        cache.lock.read {
            val cachedList = cache.listing[normDir]
            if (cachedList != null && now - cachedList.completeAtMs < listingTtlMs) {
                return cachedList.children.values.toList()
            }
        }

        val resolvedDir = resolve(dir, tokenProvider)
            ?: throw DriveFileNotFoundException("Google Drive folder not found: $normDir")
        val targetDirMeta = if (resolvedDir.meta.isShortcut) {
            resolveShortcutTarget(dir, resolvedDir.meta, tokenProvider)
        } else {
            resolvedDir.meta
        }
        if (!targetDirMeta.isFolder) {
            throw IOException("Not a directory: $normDir")
        }

        val rawFiles = try {
            fetchAllChildrenSuspend(tokenProvider, targetDirMeta.id)
        } catch (nf: DriveFileNotFoundException) {
            if (resolvedDir.fromCache) {
                evictPathAndParentListing(dir)
                val reResolved = resolve(dir, tokenProvider, allowCache = false)
                    ?: throw nf
                val reTarget = if (reResolved.meta.isShortcut) {
                    resolveShortcutTarget(dir, reResolved.meta, tokenProvider)
                } else {
                    reResolved.meta
                }
                fetchAllChildrenSuspend(tokenProvider, reTarget.id)
            } else {
                throw nf
            }
        }

        val assignedMap = assignDisplayNames(rawFiles)
        storeListing(cache, normDir, assignedMap)
        return assignedMap.values.toList()
    }

    private suspend fun fetchAllChildrenSuspend(
        tokenProvider: DriveTokenProvider,
        parentId: String,
    ): List<DriveEntryMeta> {
        val q = "'${escapeDriveQueryLiteral(parentId)}' in parents and trashed = false"
        val out = ArrayList<DriveEntryMeta>()
        var pageToken: String? = null
        while (true) {
            val params = ArrayList<Pair<String, String>>(5).apply {
                add("q" to q)
                add("spaces" to "drive")
                add("pageSize" to LIST_PAGE_SIZE.toString())
                add("orderBy" to "folder,name_natural")
                add("fields" to LIST_FIELDS)
                if (!pageToken.isNullOrEmpty()) {
                    add("pageToken" to pageToken!!)
                }
            }
            val json = http.getJson(tokenProvider, listOf("files"), params)
            val arr = json.arr("files")?.items.orEmpty()
            for (item in arr) {
                val obj = item as? JsonValue.Obj ?: continue
                parseDriveEntryMeta(obj)?.let(out::add)
            }
            val next = json.str("nextPageToken")?.takeIf { it.isNotEmpty() }
            if (next == null) break
            pageToken = next
        }
        return out
    }

    private fun fetchAllChildrenBlocking(
        tokenProvider: DriveTokenProvider,
        parentId: String,
    ): List<DriveEntryMeta> {
        val q = "'${escapeDriveQueryLiteral(parentId)}' in parents and trashed = false"
        val out = ArrayList<DriveEntryMeta>()
        var pageToken: String? = null
        while (true) {
            val params = ArrayList<Pair<String, String>>(5).apply {
                add("q" to q)
                add("spaces" to "drive")
                add("pageSize" to LIST_PAGE_SIZE.toString())
                add("orderBy" to "folder,name_natural")
                add("fields" to LIST_FIELDS)
                if (!pageToken.isNullOrEmpty()) {
                    add("pageToken" to pageToken!!)
                }
            }
            val json = http.getJsonBlocking(tokenProvider, listOf("files"), params)
            val arr = json.arr("files")?.items.orEmpty()
            for (item in arr) {
                val obj = item as? JsonValue.Obj ?: continue
                parseDriveEntryMeta(obj)?.let(out::add)
            }
            val next = json.str("nextPageToken")?.takeIf { it.isNotEmpty() }
            if (next == null) break
            pageToken = next
        }
        return out
    }

    private fun storeListing(
        cache: ServerCache,
        normDir: String,
        assignedMap: LinkedHashMap<String, DriveEntryMeta>,
    ) {
        val now = clock.nowMs()
        cache.lock.write {
            cache.listing[normDir] = CachedListing(LinkedHashMap(assignedMap), now)
            // Drop direct children under normDir whose display name is no longer present
            val prefix = if (normDir == "/") "/" else "$normDir/"
            val staleKeys = ArrayList<String>()
            for (key in cache.meta.keys) {
                if (key != normDir && key.startsWith(prefix)) {
                    val rest = key.substring(prefix.length)
                    val firstSeg = rest.substringBefore('/')
                    if (firstSeg !in assignedMap) {
                        staleKeys.add(key)
                    }
                }
            }
            for (k in staleKeys) {
                cache.meta.remove(k)
                cache.listing.remove(k)
            }
            for ((disp, entry) in assignedMap) {
                val childPath = joinNormPath(normDir, disp)
                cache.meta[childPath] = CachedMeta(entry, now)
            }
        }
    }

    // =========================================================================
    // Path resolution (suspend & blocking)
    // =========================================================================

    suspend fun resolve(
        path: VfsPath.GDrive,
        tokenProvider: DriveTokenProvider,
        allowCache: Boolean = true,
    ): ResolvedDriveEntry? {
        val norm = path.normalizedRemotePath
        if (norm == "/") {
            return ResolvedDriveEntry(rootEntry(), fromCache = false)
        }
        val cache = cacheFor(path.serverId)
        val now = clock.nowMs()

        if (allowCache) {
            cache.lock.read {
                val direct = cache.meta[norm]
                if (direct != null && now - direct.cachedAtMs < metaTtlMs) {
                    return ResolvedDriveEntry(direct.entry, fromCache = true)
                }
                val pNorm = parentNormPath(norm)
                val pList = cache.listing[pNorm]
                if (pList != null && now - pList.completeAtMs < listingTtlMs) {
                    val leaf = norm.substringAfterLast('/')
                    val entry = pList.children[leaf]
                    return if (entry != null) ResolvedDriveEntry(entry, fromCache = true) else null
                }
            }
        }

        val resolved = resolveSegmentsSuspend(cache, path.serverId, norm, tokenProvider, allowCache)
        if (resolved == null && allowCache) {
            // If an intermediate cached folder had become stale (recreated on Drive with a new ID),
            // retry once without cached prefix if any prefix came from cache.
            val hadCachedPrefix = hasAnyCachedPrefix(cache, norm)
            if (hadCachedPrefix) {
                evictAncestors(cache, norm)
                return resolveSegmentsSuspend(cache, path.serverId, norm, tokenProvider, allowCache = false)
            }
        }
        return resolved
    }

    fun resolveBlocking(
        path: VfsPath.GDrive,
        tokenProvider: DriveTokenProvider,
        allowCache: Boolean = true,
    ): ResolvedDriveEntry? {
        val norm = path.normalizedRemotePath
        if (norm == "/") {
            return ResolvedDriveEntry(rootEntry(), fromCache = false)
        }
        val cache = cacheFor(path.serverId)
        val now = clock.nowMs()

        if (allowCache) {
            cache.lock.read {
                val direct = cache.meta[norm]
                if (direct != null && now - direct.cachedAtMs < metaTtlMs) {
                    return ResolvedDriveEntry(direct.entry, fromCache = true)
                }
                val pNorm = parentNormPath(norm)
                val pList = cache.listing[pNorm]
                if (pList != null && now - pList.completeAtMs < listingTtlMs) {
                    val leaf = norm.substringAfterLast('/')
                    val entry = pList.children[leaf]
                    return if (entry != null) ResolvedDriveEntry(entry, fromCache = true) else null
                }
            }
        }

        val resolved = resolveSegmentsBlocking(cache, path.serverId, norm, tokenProvider, allowCache)
        if (resolved == null && allowCache) {
            val hadCachedPrefix = hasAnyCachedPrefix(cache, norm)
            if (hadCachedPrefix) {
                evictAncestors(cache, norm)
                return resolveSegmentsBlocking(cache, path.serverId, norm, tokenProvider, allowCache = false)
            }
        }
        return resolved
    }

    private fun hasAnyCachedPrefix(cache: ServerCache, norm: String): Boolean = cache.lock.read {
        val segments = norm.substring(1).split('/')
        var cur = ""
        for (i in 0 until segments.lastIndex) {
            cur = "$cur/${segments[i]}"
            if (cur in cache.meta) return@read true
        }
        false
    }

    private fun evictAncestors(cache: ServerCache, norm: String) = cache.lock.write {
        val segments = norm.substring(1).split('/')
        var cur = ""
        for (i in 0 until segments.lastIndex) {
            cur = "$cur/${segments[i]}"
            cache.meta.remove(cur)
            cache.listing.remove(cur)
        }
    }

    private suspend fun resolveSegmentsSuspend(
        cache: ServerCache,
        serverId: String,
        norm: String,
        tokenProvider: DriveTokenProvider,
        allowCache: Boolean,
    ): ResolvedDriveEntry? {
        val segments = norm.substring(1).split('/')
        var startIdx = 0
        var currentEntry = rootEntry()
        var currentPath = "/"
        var usedCachedPrefix = false

        if (allowCache) {
            val now = clock.nowMs()
            cache.lock.read {
                var probe = ""
                val paths = Array(segments.size) { idx ->
                    probe = "$probe/${segments[idx]}"
                    probe
                }
                for (i in segments.indices.reversed()) {
                    val hit = cache.meta[paths[i]]
                    if (hit != null && now - hit.cachedAtMs < metaTtlMs) {
                        startIdx = i + 1
                        currentEntry = hit.entry
                        currentPath = paths[i]
                        usedCachedPrefix = true
                        break
                    }
                }
            }
        }

        for (i in startIdx until segments.size) {
            // Follow folder shortcut when traversing into children
            val parentFolderId = if (currentEntry.isShortcut) {
                val target = resolveShortcutTarget(
                    VfsPath.GDrive(serverId, currentPath),
                    currentEntry,
                    tokenProvider,
                )
                if (!target.isFolder) return null
                target.id
            } else {
                if (!currentEntry.isFolder) return null
                currentEntry.id
            }

            val seg = segments[i]
            val childPath = joinNormPath(currentPath, seg)
            val found = resolveSingleChildSuspend(cache, currentPath, parentFolderId, seg, tokenProvider)
                ?: return null
            val withDisplay = if (found.displayName.isEmpty()) found.copy(displayName = seg) else found
            val now = clock.nowMs()
            cache.lock.write {
                cache.meta[childPath] = CachedMeta(withDisplay, now)
            }
            currentEntry = withDisplay
            currentPath = childPath
        }

        return ResolvedDriveEntry(currentEntry, fromCache = usedCachedPrefix && startIdx == segments.size)
    }

    private fun resolveSegmentsBlocking(
        cache: ServerCache,
        serverId: String,
        norm: String,
        tokenProvider: DriveTokenProvider,
        allowCache: Boolean,
    ): ResolvedDriveEntry? {
        val segments = norm.substring(1).split('/')
        var startIdx = 0
        var currentEntry = rootEntry()
        var currentPath = "/"
        var usedCachedPrefix = false

        if (allowCache) {
            val now = clock.nowMs()
            cache.lock.read {
                var probe = ""
                val paths = Array(segments.size) { idx ->
                    probe = "$probe/${segments[idx]}"
                    probe
                }
                for (i in segments.indices.reversed()) {
                    val hit = cache.meta[paths[i]]
                    if (hit != null && now - hit.cachedAtMs < metaTtlMs) {
                        startIdx = i + 1
                        currentEntry = hit.entry
                        currentPath = paths[i]
                        usedCachedPrefix = true
                        break
                    }
                }
            }
        }

        for (i in startIdx until segments.size) {
            val parentFolderId = if (currentEntry.isShortcut) {
                val target = resolveShortcutTargetBlocking(
                    VfsPath.GDrive(serverId, currentPath),
                    currentEntry,
                    tokenProvider,
                )
                if (!target.isFolder) return null
                target.id
            } else {
                if (!currentEntry.isFolder) return null
                currentEntry.id
            }

            val seg = segments[i]
            val childPath = joinNormPath(currentPath, seg)
            val found = resolveSingleChildBlocking(cache, currentPath, parentFolderId, seg, tokenProvider)
                ?: return null
            val withDisplay = if (found.displayName.isEmpty()) found.copy(displayName = seg) else found
            val now = clock.nowMs()
            cache.lock.write {
                cache.meta[childPath] = CachedMeta(withDisplay, now)
            }
            currentEntry = withDisplay
            currentPath = childPath
        }

        return ResolvedDriveEntry(currentEntry, fromCache = usedCachedPrefix && startIdx == segments.size)
    }

    /**
     * Cold resolution of a single child segment under [parentId] (§3.2):
     * ① Exact Drive name (if segment contains `∕`, tries exact first, then `∕` -> `/`).
     * ② If segment ends in `.pdf`, base name restricted to exportable Workspace mime types.
     * ③ If segment is a collision-suffixed name (` [k]`, ` (Google Doc).pdf`, or `(unnamed ...)`),
     *   fetches the parent's children and runs [assignDisplayNames] so cold & warm agree 100%.
     */
    private suspend fun resolveSingleChildSuspend(
        cache: ServerCache,
        parentNormPath: String,
        parentId: String,
        segment: String,
        tokenProvider: DriveTokenProvider,
    ): DriveEntryMeta? {
        // ① Exact Drive name (non-Workspace exact match wins; Workspace docs never match raw name without .pdf unless raw name actually ends in .pdf? Wait: in assignDisplayNames, Workspace doc named "Report.pdf" has display "Report.pdf.pdf", whereas non-Workspace "Report.pdf" has display "Report.pdf"!)
        val candidateRawNames = buildCandidateRawNames(segment)
        for (rawCandidate in candidateRawNames) {
            val hits = queryByNameSuspend(tokenProvider, parentId, rawCandidate)
                .filter { !it.isWorkspaceDoc && toDisplayName(it.name, it.id) == segment }
            if (hits.isNotEmpty()) {
                return hits.sortedWith(ENTRY_DETERMINISTIC_ORDER).first().copy(displayName = segment)
            }
        }

        // ② Virtual `.pdf` Workspace doc resolution (when not a ` (Google Doc).pdf` or ` [k].pdf` collision suffix)
        if (segment.endsWith(".pdf") && !segment.endsWith(" (Google Doc).pdf") && !SUFFIX_COUNTER_REGEX.matches(segment)) {
            val baseDisp = segment.removeSuffix(".pdf")
            for (rawBase in buildCandidateRawNames(baseDisp)) {
                val wsHits = queryWorkspaceByNameSuspend(tokenProvider, parentId, rawBase)
                    .filter { toDisplayName(it.name, it.id) == baseDisp }
                if (wsHits.isNotEmpty()) {
                    return wsHits.sortedWith(ENTRY_DETERMINISTIC_ORDER).first().copy(displayName = segment)
                }
            }
        }

        // ③ Collision suffix (` [k]`, ` (Google Doc).pdf`) or `(unnamed <id8>)`
        if (isCollisionOrUnnamedSegment(segment)) {
            val allRaw = fetchAllChildrenSuspend(tokenProvider, parentId)
            val assigned = assignDisplayNames(allRaw)
            storeListing(cache, parentNormPath, assigned)
            return assigned[segment]
        }

        return null
    }

    private fun resolveSingleChildBlocking(
        cache: ServerCache,
        parentNormPath: String,
        parentId: String,
        segment: String,
        tokenProvider: DriveTokenProvider,
    ): DriveEntryMeta? {
        val candidateRawNames = buildCandidateRawNames(segment)
        for (rawCandidate in candidateRawNames) {
            val hits = queryByNameBlocking(tokenProvider, parentId, rawCandidate)
                .filter { !it.isWorkspaceDoc && toDisplayName(it.name, it.id) == segment }
            if (hits.isNotEmpty()) {
                return hits.sortedWith(ENTRY_DETERMINISTIC_ORDER).first().copy(displayName = segment)
            }
        }

        if (segment.endsWith(".pdf") && !segment.endsWith(" (Google Doc).pdf") && !SUFFIX_COUNTER_REGEX.matches(segment)) {
            val baseDisp = segment.removeSuffix(".pdf")
            for (rawBase in buildCandidateRawNames(baseDisp)) {
                val wsHits = queryWorkspaceByNameBlocking(tokenProvider, parentId, rawBase)
                    .filter { toDisplayName(it.name, it.id) == baseDisp }
                if (wsHits.isNotEmpty()) {
                    return wsHits.sortedWith(ENTRY_DETERMINISTIC_ORDER).first().copy(displayName = segment)
                }
            }
        }

        if (isCollisionOrUnnamedSegment(segment)) {
            val allRaw = fetchAllChildrenBlocking(tokenProvider, parentId)
            val assigned = assignDisplayNames(allRaw)
            storeListing(cache, parentNormPath, assigned)
            return assigned[segment]
        }

        return null
    }

    private fun buildCandidateRawNames(displaySegment: String): List<String> {
        if (DIVISION_SLASH !in displaySegment) return listOf(displaySegment)
        return listOf(displaySegment, displaySegment.replace(DIVISION_SLASH, '/'))
    }

    private fun isCollisionOrUnnamedSegment(segment: String): Boolean =
        SUFFIX_COUNTER_REGEX.matches(segment) ||
            segment.endsWith(" (Google Doc).pdf") ||
            UNNAMED_DISPLAY_REGEX.matches(segment)

    private suspend fun queryByNameSuspend(
        tokenProvider: DriveTokenProvider,
        parentId: String,
        rawName: String,
    ): List<DriveEntryMeta> {
        val q = "name = '${escapeDriveQueryLiteral(rawName)}' and " +
            "'${escapeDriveQueryLiteral(parentId)}' in parents and trashed = false"
        val json = http.getJson(
            tokenProvider = tokenProvider,
            pathSegments = listOf("files"),
            queryParams = listOf(
                "q" to q,
                "spaces" to "drive",
                "pageSize" to "50",
                "fields" to LIST_FIELDS,
            ),
        )
        return json.arr("files")?.items.orEmpty()
            .mapNotNull { (it as? JsonValue.Obj)?.let(::parseDriveEntryMeta) }
    }

    private fun queryByNameBlocking(
        tokenProvider: DriveTokenProvider,
        parentId: String,
        rawName: String,
    ): List<DriveEntryMeta> {
        val q = "name = '${escapeDriveQueryLiteral(rawName)}' and " +
            "'${escapeDriveQueryLiteral(parentId)}' in parents and trashed = false"
        val json = http.getJsonBlocking(
            tokenProvider = tokenProvider,
            pathSegments = listOf("files"),
            queryParams = listOf(
                "q" to q,
                "spaces" to "drive",
                "pageSize" to "50",
                "fields" to LIST_FIELDS,
            ),
        )
        return json.arr("files")?.items.orEmpty()
            .mapNotNull { (it as? JsonValue.Obj)?.let(::parseDriveEntryMeta) }
    }

    private fun buildWorkspaceMimeClause(): String =
        WORKSPACE_EXPORT.keys.joinToString(" or ", prefix = "(", postfix = ")") {
            "mimeType = '${escapeDriveQueryLiteral(it)}'"
        }

    private suspend fun queryWorkspaceByNameSuspend(
        tokenProvider: DriveTokenProvider,
        parentId: String,
        rawBaseName: String,
    ): List<DriveEntryMeta> {
        val q = "name = '${escapeDriveQueryLiteral(rawBaseName)}' and " +
            "'${escapeDriveQueryLiteral(parentId)}' in parents and trashed = false and " +
            buildWorkspaceMimeClause()
        val json = http.getJson(
            tokenProvider = tokenProvider,
            pathSegments = listOf("files"),
            queryParams = listOf(
                "q" to q,
                "spaces" to "drive",
                "pageSize" to "50",
                "fields" to LIST_FIELDS,
            ),
        )
        return json.arr("files")?.items.orEmpty()
            .mapNotNull { (it as? JsonValue.Obj)?.let(::parseDriveEntryMeta) }
            .filter { it.isWorkspaceDoc }
    }

    private fun queryWorkspaceByNameBlocking(
        tokenProvider: DriveTokenProvider,
        parentId: String,
        rawBaseName: String,
    ): List<DriveEntryMeta> {
        val q = "name = '${escapeDriveQueryLiteral(rawBaseName)}' and " +
            "'${escapeDriveQueryLiteral(parentId)}' in parents and trashed = false and " +
            buildWorkspaceMimeClause()
        val json = http.getJsonBlocking(
            tokenProvider = tokenProvider,
            pathSegments = listOf("files"),
            queryParams = listOf(
                "q" to q,
                "spaces" to "drive",
                "pageSize" to "50",
                "fields" to LIST_FIELDS,
            ),
        )
        return json.arr("files")?.items.orEmpty()
            .mapNotNull { (it as? JsonValue.Obj)?.let(::parseDriveEntryMeta) }
            .filter { it.isWorkspaceDoc }
    }

    /**
     * Uncached single-query lookup used by `openOutput` on a missing path (M5) and idempotent `create*`.
     */
    suspend fun queryExactChildUncached(
        parentId: String,
        rawName: String,
        tokenProvider: DriveTokenProvider,
    ): DriveEntryMeta? = queryByNameSuspend(tokenProvider, parentId, rawName)
        .filter { !it.isWorkspaceDoc }
        .sortedWith(ENTRY_DETERMINISTIC_ORDER)
        .firstOrNull()

    fun queryExactChildUncachedBlocking(
        parentId: String,
        rawName: String,
        tokenProvider: DriveTokenProvider,
    ): DriveEntryMeta? = queryByNameBlocking(tokenProvider, parentId, rawName)
        .filter { !it.isWorkspaceDoc }
        .sortedWith(ENTRY_DETERMINISTIC_ORDER)
        .firstOrNull()

    // =========================================================================
    // Shortcut target resolution
    // =========================================================================

    suspend fun resolveShortcutTarget(
        shortcutPath: VfsPath.GDrive,
        shortcutMeta: DriveEntryMeta,
        tokenProvider: DriveTokenProvider,
    ): DriveEntryMeta {
        val targetId = shortcutMeta.shortcutTargetId
            ?: throw DriveFileNotFoundException("Broken Google Drive shortcut: ${shortcutPath.normalizedRemotePath}")
        val obj = http.getJson(
            tokenProvider = tokenProvider,
            pathSegments = listOf("files", targetId),
            queryParams = listOf("fields" to FILE_FIELDS),
        )
        return parseDriveEntryMeta(obj)
            ?: throw DriveFileNotFoundException("Google Drive shortcut target is trashed: ${shortcutPath.normalizedRemotePath}")
    }

    fun resolveShortcutTargetBlocking(
        shortcutPath: VfsPath.GDrive,
        shortcutMeta: DriveEntryMeta,
        tokenProvider: DriveTokenProvider,
    ): DriveEntryMeta {
        val targetId = shortcutMeta.shortcutTargetId
            ?: throw DriveFileNotFoundException("Broken Google Drive shortcut: ${shortcutPath.normalizedRemotePath}")
        val obj = http.getJsonBlocking(
            tokenProvider = tokenProvider,
            pathSegments = listOf("files", targetId),
            queryParams = listOf("fields" to FILE_FIELDS),
        )
        return parseDriveEntryMeta(obj)
            ?: throw DriveFileNotFoundException("Google Drive shortcut target is trashed: ${shortcutPath.normalizedRemotePath}")
    }

    // =========================================================================
    // Synchronous cache mutation hooks (§3.2 invalidation table)
    // =========================================================================

    fun onCreatedOrUploaded(path: VfsPath.GDrive, entry: DriveEntryMeta) {
        val cache = cacheFor(path.serverId)
        val norm = path.normalizedRemotePath
        if (norm == "/") return
        val parentNorm = parentNormPath(norm)
        val leafDisplay = norm.substringAfterLast('/')
        val stored = entry.copy(displayName = leafDisplay)
        val now = clock.nowMs()

        cache.lock.write {
            cache.meta[norm] = CachedMeta(stored, now)
            val parentListing = cache.listing[parentNorm]
            if (parentListing != null) {
                // Check if another entry with a different ID already uses leafDisplay
                val prev = parentListing.children[leafDisplay]
                if (prev != null && prev.id != stored.id) {
                    // Duplicate group created — evict listing so next list() assigns [2] cleanly
                    cache.listing.remove(parentNorm)
                } else {
                    parentListing.children[leafDisplay] = stored
                }
            }
        }
    }

    fun onRenamed(oldPath: VfsPath.GDrive, oldEntry: DriveEntryMeta, newPath: VfsPath.GDrive, updatedEntry: DriveEntryMeta) {
        val cache = cacheFor(oldPath.serverId)
        val oldNorm = oldPath.normalizedRemotePath
        val newNorm = newPath.normalizedRemotePath
        val parentNorm = parentNormPath(oldNorm)
        val oldLeaf = oldNorm.substringAfterLast('/')
        val newLeaf = newNorm.substringAfterLast('/')
        val stored = updatedEntry.copy(displayName = newLeaf)
        val now = clock.nowMs()

        cache.lock.write {
            val hadDuplicates = hasSiblingWithSameBaseLocked(cache, parentNorm, oldEntry)
            removeSubtreeLocked(cache, oldNorm)
            if (hadDuplicates) {
                evictDuplicateSiblingsLocked(cache, parentNorm, oldEntry)
                cache.listing.remove(parentNorm)
            } else {
                val pList = cache.listing[parentNorm]
                if (pList != null) {
                    pList.children.remove(oldLeaf)
                    val existingNew = pList.children[newLeaf]
                    if (existingNew != null && existingNew.id != stored.id) {
                        cache.listing.remove(parentNorm)
                    } else {
                        pList.children[newLeaf] = stored
                    }
                }
            }
            cache.meta[newNorm] = CachedMeta(stored, now)
        }
    }

    fun onMoved(
        srcPath: VfsPath.GDrive,
        srcEntry: DriveEntryMeta,
        dstDirPath: VfsPath.GDrive,
        movedEntry: DriveEntryMeta,
    ): VfsPath.GDrive {
        val cache = cacheFor(srcPath.serverId)
        val srcNorm = srcPath.normalizedRemotePath
        val srcParentNorm = parentNormPath(srcNorm)
        val dstDirNorm = dstDirPath.normalizedRemotePath
        val srcLeaf = srcNorm.substringAfterLast('/')

        // Target display name in dstDir uses base sanitised name (or .pdf for Workspace doc)
        val san = toDisplayName(movedEntry.name, movedEntry.id)
        val dstLeaf = if (movedEntry.isWorkspaceDoc) "$san.pdf" else san
        val dstNorm = joinNormPath(dstDirNorm, dstLeaf)
        val stored = movedEntry.copy(displayName = dstLeaf)
        val now = clock.nowMs()

        cache.lock.write {
            val hadSrcDuplicates = hasSiblingWithSameBaseLocked(cache, srcParentNorm, srcEntry)
            removeSubtreeLocked(cache, srcNorm)
            if (hadSrcDuplicates) {
                evictDuplicateSiblingsLocked(cache, srcParentNorm, srcEntry)
                cache.listing.remove(srcParentNorm)
            } else {
                cache.listing[srcParentNorm]?.children?.remove(srcLeaf)
            }

            val dstList = cache.listing[dstDirNorm]
            if (dstList != null) {
                val collision = dstList.children[dstLeaf]
                if (collision != null && collision.id != stored.id) {
                    cache.listing.remove(dstDirNorm)
                } else {
                    dstList.children[dstLeaf] = stored
                }
            }
            cache.meta[dstNorm] = CachedMeta(stored, now)
        }
        return VfsPath.GDrive(srcPath.serverId, dstNorm)
    }

    fun onDeleted(path: VfsPath.GDrive, deletedEntry: DriveEntryMeta?) {
        val cache = cacheFor(path.serverId)
        val norm = path.normalizedRemotePath
        if (norm == "/") return
        val parentNorm = parentNormPath(norm)
        val leaf = norm.substringAfterLast('/')

        cache.lock.write {
            val hadDuplicates = deletedEntry != null && hasSiblingWithSameBaseLocked(cache, parentNorm, deletedEntry)
            removeSubtreeLocked(cache, norm)
            if (hadDuplicates) {
                evictDuplicateSiblingsLocked(cache, parentNorm, deletedEntry!!)
                cache.listing.remove(parentNorm)
            } else {
                cache.listing[parentNorm]?.children?.remove(leaf)
            }
        }
    }

    fun onLastModifiedUpdated(path: VfsPath.GDrive, epochMs: Long) {
        val cache = cacheFor(path.serverId)
        val norm = path.normalizedRemotePath
        val parentNorm = parentNormPath(norm)
        val leaf = norm.substringAfterLast('/')
        val now = clock.nowMs()

        cache.lock.write {
            val cur = cache.meta[norm]
            if (cur != null) {
                cache.meta[norm] = CachedMeta(cur.entry.copy(modifiedTimeMs = epochMs), now)
            }
            val pList = cache.listing[parentNorm]
            val listItem = pList?.children?.get(leaf)
            if (pList != null && listItem != null) {
                pList.children[leaf] = listItem.copy(modifiedTimeMs = epochMs)
            }
        }
    }

    private fun hasSiblingWithSameBaseLocked(
        cache: ServerCache,
        parentNorm: String,
        target: DriveEntryMeta,
    ): Boolean {
        val targetBase = toDisplayName(target.name, target.id)
        val pList = cache.listing[parentNorm]
        if (pList != null) {
            for (child in pList.children.values) {
                if (child.id != target.id && toDisplayName(child.name, child.id) == targetBase) {
                    return true
                }
            }
        }
        val prefix = if (parentNorm == "/") "/" else "$parentNorm/"
        for ((k, cached) in cache.meta) {
            if (k.startsWith(prefix) && '/' !in k.substring(prefix.length)) {
                if (cached.entry.id != target.id &&
                    toDisplayName(cached.entry.name, cached.entry.id) == targetBase
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun evictDuplicateSiblingsLocked(
        cache: ServerCache,
        parentNorm: String,
        target: DriveEntryMeta,
    ) {
        val targetBase = toDisplayName(target.name, target.id)
        val prefix = if (parentNorm == "/") "/" else "$parentNorm/"
        val matchingChildNorms = ArrayList<String>()
        for ((k, cached) in cache.meta) {
            if (k.startsWith(prefix) && '/' !in k.substring(prefix.length)) {
                if (toDisplayName(cached.entry.name, cached.entry.id) == targetBase) {
                    matchingChildNorms.add(k)
                }
            }
        }
        for (childNorm in matchingChildNorms) {
            removeSubtreeLocked(cache, childNorm)
        }
    }

    private fun removeSubtreeLocked(cache: ServerCache, norm: String) {
        val subPrefix = "$norm/"
        cache.meta.keys.removeIf { it == norm || it.startsWith(subPrefix) }
        cache.listing.keys.removeIf { it == norm || it.startsWith(subPrefix) }
    }
}
