package com.minifile.explorer.core.gallery

import com.minifile.explorer.core.model.MediaScanNotifier
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GallerySnapshot(
    val scope: GalleryScope,
    val items: List<MediaItem> = emptyList(),
    val nonMediaDirectCount: Int = 0,
    val directSubfolders: List<SubfolderChipInfo> = emptyList(),
    val hiddenByNomedia: Boolean = false,
    val truncatedByBudget: Boolean = false,
    val reconciledAddedCount: Int = 0,
    val reconciledGhostCount: Int = 0,
    val isStage0Partial: Boolean = false,
)

sealed interface GalleryMutation {
    data class Removed(val paths: List<VfsPath>) : GalleryMutation
    data class Upserted(val items: List<MediaItem>) : GalleryMutation
    data class Renamed(val oldPath: VfsPath, val newPath: VfsPath, val newName: String) : GalleryMutation
}

/**
 * Pure JVM GalleryRepository implementing FS-as-source-of-truth reconciliation with MediaStore:
 * - Stage 0 fast head query from [primaryIndexSource] (when Local & not `.nomedia`).
 * - Stage 1 full query + bounded shallow FS reconciliation:
 *     - Disk file missing in MediaStore -> added with [MediaItemFlags.PENDING_INDEX] + `rescanFiles`.
 *     - MediaStore ghost row missing on disk -> pruned + `rescanFiles`.
 * - `.nomedia` folders bypass MediaStore and list directly via [VfsListingIndexSource].
 * - Optimistic mutation patching (0 ms UI refresh on Copy/Move/Delete/Rename/Edit/Extract/Receive)
 *   paired with batched [MediaScanNotifier.rescanFiles].
 */
class GalleryRepository(
    private val fs: FileSystem,
    private val primaryIndexSource: MediaIndexSource? = null,
    private val mediaScanNotifier: MediaScanNotifier = MediaScanNotifier.NOOP,
    val vfsSource: VfsListingIndexSource = VfsListingIndexSource(fs),
) {
    private val _snapshot = MutableStateFlow<GallerySnapshot?>(null)
    val snapshot: StateFlow<GallerySnapshot?> = _snapshot.asStateFlow()

    // Deduplicates repeated rescanFiles requests for the same (path, modifiedMs) within a session
    private val rescanLock = Any()
    private val recentlyRescanned = LinkedHashSet<String>()

    private fun dispatchRescanDeduplicated(paths: List<Pair<VfsPath, Long>>, force: Boolean = false) {
        if (paths.isEmpty()) return
        val toSend = synchronized(rescanLock) {
            val out = ArrayList<VfsPath>(paths.size)
            for ((path, mtime) in paths) {
                val key = "${path.serialize()}@$mtime"
                if (force || recentlyRescanned.add(key)) {
                    out.add(path)
                }
            }
            while (recentlyRescanned.size > 2048) {
                val it = recentlyRescanned.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
            out
        }
        if (toSend.isNotEmpty()) {
            mediaScanNotifier.rescanFiles(toSend)
        }
    }

    suspend fun loadFastHead(scope: GalleryScope, limit: Int = 60): GallerySnapshot {
        val useIndex = shouldUsePrimaryIndex(scope)
        val items = if (useIndex && primaryIndexSource != null) {
            primaryIndexSource.fetchFastHead(scope, limit)
        } else {
            vfsSource.fetchFastHead(scope, limit)
        }
        val snap = GallerySnapshot(
            scope = scope,
            items = items,
            hiddenByNomedia = (scope as? GalleryScope.Folder)?.hiddenByNomedia == true,
            isStage0Partial = true,
        )
        _snapshot.value = snap
        return snap
    }

    suspend fun loadRecent(limit: Int = 8): List<MediaItem> {
        val indexed = primaryIndexSource?.fetchRecent(limit).orEmpty()
        if (indexed.isNotEmpty()) {
            // Verify files still exist on disk so ghost rows don't appear on Home strip
            val valid = ArrayList<MediaItem>(indexed.size)
            val ghosts = ArrayList<VfsPath>()
            for (item in indexed) {
                if (item.path is VfsPath.Local) {
                    if (runCatching { fs.exists(item.path) }.getOrDefault(false)) {
                        valid.add(item)
                    } else {
                        ghosts.add(item.path)
                    }
                } else {
                    valid.add(item)
                }
            }
            if (ghosts.isNotEmpty()) {
                mediaScanNotifier.rescanFiles(ghosts)
            }
            if (valid.isNotEmpty()) return valid.take(limit)
        }
        return vfsSource.fetchRecent(limit)
    }

    suspend fun loadAndReconcile(
        scope: GalleryScope,
        includeHidden: Boolean = false,
    ): GallerySnapshot {
        return when (scope) {
            GalleryScope.Library -> loadLibraryReconciled(includeHidden)
            is GalleryScope.Folder -> loadFolderReconciled(scope, includeHidden)
        }.also { _snapshot.value = it }
    }

    private suspend fun loadLibraryReconciled(includeHidden: Boolean): GallerySnapshot {
        val indexed = primaryIndexSource?.fetchAll(GalleryScope.Library, includeHidden)
        if (indexed == null) {
            val vfsItems = vfsSource.fetchAll(GalleryScope.Library, includeHidden)
            return GallerySnapshot(scope = GalleryScope.Library, items = vfsItems)
        }

        // Shallow reconciliation on indexed items: drop ghost rows whose Local path no longer exists
        // and merge any unindexed files found in known library roots.
        val diffPaths = LinkedHashSet<VfsPath>()
        val reconciled = LinkedHashMap<VfsPath, MediaItem>(indexed.size)
        var ghosts = 0
        for (item in indexed) {
            if (GalleryScopePolicy.isPendingOrTrashedName(item.name)) continue
            if (item.path is VfsPath.Local) {
                val exists = runCatching { fs.exists(item.path) }.getOrDefault(false)
                if (!exists) {
                    ghosts++
                    diffPaths.add(item.path)
                    continue
                }
            }
            reconciled[item.path] = item
        }

        val vfsItems = vfsSource.fetchAll(GalleryScope.Library, includeHidden)
        var added = 0
        for (diskItem in vfsItems) {
            if (diskItem.path !in reconciled) {
                reconciled[diskItem.path] = diskItem.copy(flags = diskItem.flags or MediaItemFlags.PENDING_INDEX)
                diffPaths.add(diskItem.path)
                added++
            }
        }

        if (diffPaths.isNotEmpty()) {
            dispatchRescanDeduplicated(diffPaths.map { it to (reconciled[it]?.modifiedMs ?: 0L) })
        }

        return GallerySnapshot(
            scope = GalleryScope.Library,
            items = reconciled.values.sortedByDescending { it.bestDateMs },
            reconciledAddedCount = added,
            reconciledGhostCount = ghosts,
        )
    }

    private suspend fun loadFolderReconciled(
        scope: GalleryScope.Folder,
        includeHidden: Boolean,
    ): GallerySnapshot {
        val vfsScan = vfsSource.scanFolder(
            dir = scope.dir,
            includeSubtree = scope.includeSubtree,
            includeHidden = includeHidden,
            allowNomediaDirect = true,
        )

        val isNomedia = scope.hiddenByNomedia || vfsScan.hiddenByNomedia
        if (isNomedia || !shouldUsePrimaryIndex(scope) || primaryIndexSource == null) {
            return GallerySnapshot(
                scope = scope.copy(hiddenByNomedia = isNomedia),
                items = vfsScan.mediaItems,
                nonMediaDirectCount = vfsScan.nonMediaDirectCount,
                directSubfolders = vfsScan.directSubfolders,
                hiddenByNomedia = isNomedia,
                truncatedByBudget = vfsScan.truncatedByBudget,
            )
        }

        val indexed = primaryIndexSource.fetchAll(scope, includeHidden)
        val indexedByPath = indexed.associateBy { it.path }
        val diskByPath = vfsScan.mediaItems.associateBy { it.path }
        val diffPaths = LinkedHashMap<VfsPath, Long>()
        var added = 0
        var ghosts = 0

        // FS is source of truth for all visited directories: keep every disk file, enriched with MediaStore metadata.
        val merged = ArrayList<MediaItem>(vfsScan.mediaItems.size)
        for (diskItem in vfsScan.mediaItems) {
            val ms = indexedByPath[diskItem.path]
            if (ms != null) {
                merged.add(
                    ms.copy(
                        sizeBytes = diskItem.sizeBytes,
                        modifiedMs = maxOf(ms.modifiedMs, diskItem.modifiedMs),
                    ),
                )
            } else {
                merged.add(diskItem.copy(flags = diskItem.flags or MediaItemFlags.PENDING_INDEX))
                diffPaths[diskItem.path] = diskItem.modifiedMs
                added++
            }
        }

        // Reconcile MediaStore rows absent from the scanned disk set:
        // - If the row's parent was visited during our scan, it is definitely a ghost -> prune & rescan.
        // - If its parent was NOT visited (e.g. deeper than maxDepth=3 or past maxDirs budget),
        //   check fs.exists: keep it visible if it still exists on disk, or prune as ghost if missing!
        for (msItem in indexed) {
            if (msItem.path in diskByPath) continue
            val parent = runCatching { fs.parent(msItem.path) }.getOrNull()
            if (parent != null && parent in vfsScan.visitedDirs) {
                ghosts++
                diffPaths[msItem.path] = msItem.modifiedMs
            } else {
                val stillOnDisk = runCatching { fs.exists(msItem.path) }.getOrDefault(false)
                if (stillOnDisk) {
                    merged.add(msItem)
                } else {
                    ghosts++
                    diffPaths[msItem.path] = msItem.modifiedMs
                }
            }
        }

        if (diffPaths.isNotEmpty()) {
            dispatchRescanDeduplicated(diffPaths.entries.map { it.key to it.value })
        }

        merged.sortByDescending { it.bestDateMs }
        return GallerySnapshot(
            scope = scope.copy(hiddenByNomedia = false),
            items = merged,
            nonMediaDirectCount = vfsScan.nonMediaDirectCount,
            directSubfolders = vfsScan.directSubfolders,
            hiddenByNomedia = false,
            truncatedByBudget = vfsScan.truncatedByBudget,
            reconciledAddedCount = added,
            reconciledGhostCount = ghosts,
        )
    }

    /**
     * Immediately patches the current in-memory snapshot for 0 ms UI feedback after write operations
     * and dispatches batched [MediaScanNotifier.rescanFiles].
     */
    fun applyOptimisticMutation(mutation: GalleryMutation): GallerySnapshot? {
        val cur = _snapshot.value
        val toRescan = LinkedHashSet<VfsPath>()
        val updated = when (mutation) {
            is GalleryMutation.Removed -> {
                val removeSet = mutation.paths.toSet()
                toRescan.addAll(mutation.paths)
                cur?.copy(items = cur.items.filterNot { it.path in removeSet })
            }

            is GalleryMutation.Upserted -> {
                val byPath = LinkedHashMap<VfsPath, MediaItem>()
                cur?.items?.forEach { byPath[it.path] = it }
                for (item in mutation.items) {
                    toRescan.add(item.path)
                    // R2-4: Only inject into the live snapshot if the item belongs to the current scope
                    if (cur != null && isPathInScope(item.path, cur.scope)) {
                        byPath[item.path] = item
                    }
                }
                cur?.copy(items = byPath.values.sortedByDescending { it.bestDateMs })
            }

            is GalleryMutation.Renamed -> {
                toRescan.add(mutation.oldPath)
                toRescan.add(mutation.newPath)
                cur?.copy(
                    items = cur.items.map { item ->
                        if (item.path == mutation.oldPath) {
                            item.copy(path = mutation.newPath, name = mutation.newName)
                        } else {
                            item
                        }
                    },
                )
            }
        }
        if (toRescan.isNotEmpty()) {
            mediaScanNotifier.rescanFiles(toRescan.toList())
        }
        if (updated != null) {
            _snapshot.value = updated
        }
        return updated
    }

    fun patchGeo(geoByPath: Map<VfsPath, GeoPoint>): GallerySnapshot? {
        if (geoByPath.isEmpty()) return _snapshot.value
        val cur = _snapshot.value ?: return null
        var changed = false
        val next = cur.items.map { item ->
            val g = geoByPath[item.path]
            if (g != null && item.geo != g) {
                changed = true
                item.copy(geo = g)
            } else {
                item
            }
        }
        if (!changed) return cur
        val snap = cur.copy(items = next)
        _snapshot.value = snap
        return snap
    }

    internal fun isPathInScope(path: VfsPath, scope: GalleryScope): Boolean = when (scope) {
        GalleryScope.Library -> path is VfsPath.Local
        is GalleryScope.Folder -> {
            val ancestors = FolderModePreferences.ancestorChain(path)
            if (ancestors.size < 2) {
                false
            } else {
                val targetKey = FolderModePreferences.canonicalKey(scope.dir)
                if (!scope.includeSubtree) {
                    FolderModePreferences.canonicalKey(ancestors[1]) == targetKey
                } else {
                    ancestors.drop(1).any { FolderModePreferences.canonicalKey(it) == targetKey }
                }
            }
        }
    }

    private fun shouldUsePrimaryIndex(scope: GalleryScope): Boolean = when (scope) {
        GalleryScope.Library -> primaryIndexSource != null
        is GalleryScope.Folder -> primaryIndexSource != null &&
            scope.dir is VfsPath.Local &&
            !scope.hiddenByNomedia
    }
}
