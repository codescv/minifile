package com.minifile.explorer.core.gallery

import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath

/**
 * Pure JVM abstraction decoupling [GalleryRepository] from Android MediaStore / ContentResolver.
 */
interface MediaIndexSource {
    suspend fun fetchFastHead(scope: GalleryScope, limit: Int): List<MediaItem>
    suspend fun fetchAll(scope: GalleryScope, includeHidden: Boolean = false): List<MediaItem>
    suspend fun fetchRecent(limit: Int): List<MediaItem>
}

data class SubfolderChipInfo(
    val path: VfsPath,
    val name: String,
    val mediaCount: Int = -1,
)

data class VfsScanResult(
    val mediaItems: List<MediaItem>,
    val nonMediaDirectCount: Int,
    val directSubfolders: List<SubfolderChipInfo>,
    val hiddenByNomedia: Boolean,
    val truncatedByBudget: Boolean,
    val visitedDirs: Set<VfsPath> = emptySet(),
)

/**
 * Pure JVM [MediaIndexSource] backed directly by [FileSystem].
 *
 * Used for:
 * - SAF volumes (including camera SD cards like `DCIM/100CANON`)
 * - `.nomedia` folders (direct listing marked [MediaItemFlags.HIDDEN_BY_NOMEDIA])
 * - Shallow bounded reconciliation against MediaStore (FS is source of truth)
 * - Manual gallery lens on FTP / Google Drive / arbitrary directories
 */
class VfsListingIndexSource(
    private val fs: FileSystem,
    private val maxDepth: Int = 3,
    private val maxDirs: Int = 200,
    private val libraryRootsProvider: () -> List<VfsPath> = { emptyList() },
) : MediaIndexSource {

    override suspend fun fetchFastHead(scope: GalleryScope, limit: Int): List<MediaItem> {
        val all = fetchAll(scope, includeHidden = false)
        return all.sortedByDescending { it.bestDateMs }.take(limit.coerceAtLeast(0))
    }

    override suspend fun fetchRecent(limit: Int): List<MediaItem> =
        fetchFastHead(GalleryScope.Library, limit)

    override suspend fun fetchAll(scope: GalleryScope, includeHidden: Boolean): List<MediaItem> =
        when (scope) {
            GalleryScope.Library -> {
                val roots = libraryRootsProvider()
                val seen = HashSet<VfsPath>()
                val out = ArrayList<MediaItem>()
                for (r in roots) {
                    val scan = scanFolder(
                        dir = r,
                        includeSubtree = true,
                        includeHidden = includeHidden,
                        allowNomediaDirect = false,
                    )
                    for (item in scan.mediaItems) {
                        if (seen.add(item.path)) out.add(item)
                    }
                }
                out.sortedByDescending { it.bestDateMs }
            }

            is GalleryScope.Folder -> {
                scanFolder(
                    dir = scope.dir,
                    includeSubtree = scope.includeSubtree,
                    includeHidden = includeHidden,
                    allowNomediaDirect = true,
                ).mediaItems
            }
        }

    suspend fun scanFolder(
        dir: VfsPath,
        includeSubtree: Boolean,
        includeHidden: Boolean = false,
        allowNomediaDirect: Boolean = true,
    ): VfsScanResult {
        val mediaItems = ArrayList<MediaItem>()
        val directSubfolders = ArrayList<SubfolderChipInfo>()
        val visitedDirs = LinkedHashSet<VfsPath>()
        var nonMediaDirectCount = 0
        var dirsVisited = 0
        var truncated = false

        val rootEntries = runCatching { fs.list(dir, includeHidden = true) }.getOrDefault(emptyList())
        val rootHasNomedia = rootEntries.any { !it.isDirectory && it.name.equals(".nomedia", ignoreCase = true) }

        if (rootHasNomedia && !allowNomediaDirect) {
            return VfsScanResult(
                mediaItems = emptyList(),
                nonMediaDirectCount = 0,
                directSubfolders = emptyList(),
                hiddenByNomedia = true,
                truncatedByBudget = false,
                visitedDirs = setOf(dir),
            )
        }

        val effectiveSubtree = includeSubtree && !rootHasNomedia
        val flagMask = if (rootHasNomedia) MediaItemFlags.HIDDEN_BY_NOMEDIA else 0

        suspend fun visit(currentDir: VfsPath, depth: Int, preloaded: List<VfsNode>? = null) {
            if (dirsVisited >= maxDirs) {
                truncated = true
                return
            }
            dirsVisited++
            visitedDirs.add(currentDir)

            val entries = preloaded ?: runCatching { fs.list(currentDir, includeHidden = true) }.getOrDefault(emptyList())
            if (depth > 0 && entries.any { !it.isDirectory && it.name.equals(".nomedia", ignoreCase = true) }) {
                // Subdirectory with .nomedia is strictly isolated from parent timeline.
                return
            }

            val bucketName = currentDir.displayName
            val subDirs = ArrayList<VfsNode>()

            for (node in entries) {
                if (GalleryScopePolicy.isPendingOrTrashedName(node.name)) continue
                if (node.isDirectory) {
                    if (GalleryScopePolicy.isExcludedDirectoryName(node.name) && !includeHidden) continue
                    if (node.name.startsWith(".")) continue
                    if (depth == 0) {
                        directSubfolders.add(SubfolderChipInfo(path = node.path, name = node.name))
                    }
                    if (effectiveSubtree && !node.isSymlink) {
                        if (depth < maxDepth) {
                            subDirs.add(node)
                        } else {
                            // Depth budget reached with unexplored subdirectories remaining
                            truncated = true
                        }
                    }
                } else {
                    if (node.name.equals(".nomedia", ignoreCase = true)) continue
                    if (!includeHidden && node.isHidden) continue
                    val kind = MediaExtensionRegistry.classify(node.name, node.mimeType)
                    if (kind != null) {
                        mediaItems.add(
                            MediaItem(
                                path = node.path,
                                name = node.name,
                                kind = kind,
                                mime = node.resolvedMimeType,
                                sizeBytes = node.size.coerceAtLeast(0L),
                                bestDateMs = node.lastModified.coerceAtLeast(0L),
                                modifiedMs = node.lastModified.coerceAtLeast(0L),
                                bucketDir = bucketName,
                                storeId = -1L,
                                flags = flagMask,
                            ),
                        )
                    } else if (depth == 0) {
                        nonMediaDirectCount++
                    }
                }
            }

            for (sub in subDirs) {
                visit(sub.path, depth + 1, preloaded = null)
            }
        }

        visit(dir, 0, preloaded = rootEntries)
        directSubfolders.sortBy { it.name.lowercase() }
        mediaItems.sortByDescending { it.bestDateMs }

        return VfsScanResult(
            mediaItems = mediaItems,
            nonMediaDirectCount = nonMediaDirectCount,
            directSubfolders = directSubfolders,
            hiddenByNomedia = rootHasNomedia,
            truncatedByBudget = truncated,
            visitedDirs = visitedDirs,
        )
    }

    /**
     * Short-circuit probe returning `true` on the very first media file found under [dir]
     * (bounded by [probeMaxDepth] and [probeMaxDirs]) to avoid double full scans on navigation.
     */
    suspend fun hasAnyMedia(
        dir: VfsPath,
        preloadedRootEntries: List<VfsNode>? = null,
        includeSubtree: Boolean = true,
        includeHidden: Boolean = false,
        probeMaxDepth: Int = maxDepth,
        probeMaxDirs: Int = 64,
    ): Boolean {
        val rootEntries = preloadedRootEntries ?: runCatching { fs.list(dir, includeHidden = true) }.getOrDefault(emptyList())
        val rootHasNomedia = rootEntries.any { !it.isDirectory && it.name.equals(".nomedia", ignoreCase = true) }
        val effectiveSubtree = includeSubtree && !rootHasNomedia
        var visited = 0

        suspend fun probe(currentDir: VfsPath, depth: Int, preloaded: List<VfsNode>?): Boolean {
            if (visited >= probeMaxDirs) return false
            visited++
            val entries = preloaded ?: runCatching { fs.list(currentDir, includeHidden = true) }.getOrDefault(emptyList())
            if (depth > 0 && entries.any { !it.isDirectory && it.name.equals(".nomedia", ignoreCase = true) }) {
                return false
            }
            val subDirs = ArrayList<VfsNode>()
            for (node in entries) {
                if (GalleryScopePolicy.isPendingOrTrashedName(node.name)) continue
                if (node.isDirectory) {
                    if (node.name.startsWith(".")) continue
                    if (effectiveSubtree && depth < probeMaxDepth && !node.isSymlink) {
                        subDirs.add(node)
                    }
                } else {
                    if (node.name.equals(".nomedia", ignoreCase = true)) continue
                    if (!includeHidden && node.isHidden) continue
                    if (MediaExtensionRegistry.isMedia(node.name, node.mimeType)) return true
                }
            }
            for (sub in subDirs) {
                if (probe(sub.path, depth + 1, null)) return true
            }
            return false
        }

        return probe(dir, 0, rootEntries)
    }

    /**
     * Checks whether [dir] or any ancestor up to [stopAtAncestor] contains a `.nomedia` file.
     */
    suspend fun hasNomediaUpTo(dir: VfsPath, stopAtAncestor: VfsPath?): Boolean {
        var cur: VfsPath? = dir
        var guard = 0
        while (cur != null && guard < 32) {
            guard++
            val entries = runCatching { fs.list(cur, includeHidden = true) }.getOrNull()
            if (entries != null && entries.any { !it.isDirectory && it.name.equals(".nomedia", ignoreCase = true) }) {
                return true
            }
            if (cur == stopAtAncestor) break
            cur = runCatching { fs.parent(cur) }.getOrNull()
        }
        return false
    }
}
