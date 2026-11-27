package com.minifile.explorer.ui.gallery

import com.minifile.explorer.core.gallery.DragSelection
import com.minifile.explorer.core.gallery.FolderLensOverride
import com.minifile.explorer.core.gallery.FolderModePreferences
import com.minifile.explorer.core.gallery.GalleryGroupBy
import com.minifile.explorer.core.gallery.GalleryMutation
import com.minifile.explorer.core.gallery.GalleryRepository
import com.minifile.explorer.core.gallery.GalleryScope
import com.minifile.explorer.core.gallery.GalleryScopePolicy
import com.minifile.explorer.core.gallery.GallerySnapshot
import com.minifile.explorer.core.gallery.GridDensity
import com.minifile.explorer.core.gallery.MediaExportFormat
import com.minifile.explorer.core.gallery.MediaFilter
import com.minifile.explorer.core.gallery.MediaItem
import com.minifile.explorer.core.gallery.SubfolderChipInfo
import com.minifile.explorer.core.gallery.TimelineBuilder
import com.minifile.explorer.core.gallery.TimelineSection
import com.minifile.explorer.core.gallery.VideoSort
import com.minifile.explorer.core.i18n.AppI18n
import com.minifile.explorer.core.i18n.ResolvedLanguage
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.data.SettingsRepository
import com.minifile.explorer.data.gallery.ExifGeoReader
import com.minifile.explorer.data.gallery.GeoIndexStore
import com.minifile.explorer.data.gallery.MediaStoreIndexSource
import com.minifile.explorer.data.gallery.MediaStoreObserver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.Locale

data class GalleryUiState(
    val scope: GalleryScope? = null,
    val isLoading: Boolean = false,
    val isRefreshingGeo: Boolean = false,
    val filter: MediaFilter = MediaFilter.ALL,
    val groupBy: GalleryGroupBy = GalleryGroupBy.DAY,
    val density: GridDensity = GridDensity.MEDIUM,
    val videoSort: VideoSort = VideoSort.RECENT,
    val sections: List<TimelineSection> = emptyList(),
    val flatItems: List<MediaItem> = emptyList(),
    val totalCount: Int = 0,
    val photoCount: Int = 0,
    val videoCount: Int = 0,
    val nonMediaDirectCount: Int = 0,
    val directSubfolders: List<SubfolderChipInfo> = emptyList(),
    val hiddenByNomedia: Boolean = false,
    val truncatedByBudget: Boolean = false,
    val selectedPaths: Set<VfsPath> = emptySet(),
    val isMediaRootAggregate: Boolean = false,
) {
    val isSelectionMode: Boolean get() = selectedPaths.isNotEmpty()
}

/**
 * Standalone controller for Gallery lens state, two-stage loading, drag-selection,
 * Places geo extraction, and Home recent-media warm-up. Keeps `MainViewModel.kt` lean.
 */
class GalleryController(
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val repository: GalleryRepository,
    private val mediaStoreIndexSource: MediaStoreIndexSource? = null,
    private val mediaStoreObserver: MediaStoreObserver? = null,
    private val geoIndexStore: GeoIndexStore? = null,
    private val exifGeoReader: ExifGeoReader? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(
        GalleryUiState(
            groupBy = settingsRepository.settings.value.galleryGroupBy,
            density = settingsRepository.settings.value.galleryGridDensity,
        ),
    )
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    private val _recentHomeItems = MutableStateFlow<List<MediaItem>>(
        mediaStoreIndexSource?.readHeadCache().orEmpty(),
    )
    val recentHomeItems: StateFlow<List<MediaItem>> = _recentHomeItems.asStateFlow()

    private var activeSnapshot: GallerySnapshot? = null
    private var loadJob: Job? = null
    private var geoJob: Job? = null
    private var recentJob: Job? = null
    private var isForeground: Boolean = false

    init {
        // Observe MediaStore external changes only while in foreground (started via setForeground)
        mediaStoreObserver?.let { obs ->
            scope.launch {
                obs.changes.collect {
                    if (!isForeground) return@collect
                    refreshRecentHome()
                    val currentScope = _state.value.scope
                    if (currentScope != null) {
                        openScope(
                            galleryScope = currentScope,
                            isMediaRootAggregate = _state.value.isMediaRootAggregate,
                            preserveFilter = true,
                            silent = true,
                        )
                    }
                }
            }
        }

        // Keep groupBy & density synced with persisted settings
        scope.launch {
            settingsRepository.settings.collect { s ->
                val cur = _state.value
                if (cur.groupBy != s.galleryGroupBy || cur.density != s.galleryGridDensity) {
                    _state.value = cur.copy(
                        groupBy = s.galleryGroupBy,
                        density = s.galleryGridDensity,
                    )
                    recomputeTimeline()
                }
            }
        }
    }

    fun setForeground(foreground: Boolean) {
        if (isForeground == foreground) return
        isForeground = foreground
        if (foreground) {
            mediaStoreObserver?.start()
            refreshRecentHome()
            val curScope = _state.value.scope
            if (curScope != null) {
                openScope(
                    galleryScope = curScope,
                    isMediaRootAggregate = _state.value.isMediaRootAggregate,
                    preserveFilter = true,
                    silent = true,
                )
            }
        } else {
            mediaStoreObserver?.stop()
        }
    }

    fun touchFolderMode(path: VfsPath) {
        val prefs = folderModePreferences()
        val matched = prefs.resolveNearestAncestor(path)?.first ?: return
        val touched = prefs.touch(matched, System.currentTimeMillis())
        settingsRepository.update { it.copy(galleryFolderModesJson = touched.toJsonString()) }
    }

    fun refreshRecentHome() {
        recentJob?.cancel()
        recentJob = scope.launch {
            val loaded = withContext(ioDispatcher) {
                runCatching { repository.loadRecent(limit = 8) }.getOrDefault(emptyList())
            }
            _recentHomeItems.value = loaded
        }
    }

    fun openScope(
        galleryScope: GalleryScope,
        isMediaRootAggregate: Boolean = false,
        preserveFilter: Boolean = false,
        silent: Boolean = false,
    ) {
        loadJob?.cancel()
        val prev = _state.value
        val nextFilter = if (preserveFilter) prev.filter else MediaFilter.ALL
        val nextVideoSort = if (preserveFilter) prev.videoSort else VideoSort.RECENT
        val keepSelection = prev.scope == galleryScope

        _state.value = prev.copy(
            scope = galleryScope,
            isLoading = !silent && !keepSelection,
            filter = nextFilter,
            videoSort = nextVideoSort,
            isMediaRootAggregate = isMediaRootAggregate,
            selectedPaths = if (keepSelection) prev.selectedPaths else emptySet(),
        )

        loadJob = scope.launch {
            // Stage 0: fast head query for instant first paint (skip on silent refresh)
            if (!silent) {
                val headSnap = withContext(ioDispatcher) {
                    runCatching { repository.loadFastHead(galleryScope, limit = 60) }.getOrNull()
                }
                if (headSnap != null && headSnap.items.isNotEmpty() && _state.value.scope == galleryScope) {
                    activeSnapshot = headSnap
                    recomputeTimeline(loadingOverride = false)
                }
            }

            // Stage 1: full projection + bounded FS reconciliation
            val includeHidden = settingsRepository.settings.value.showHidden
            val fullSnap = withContext(ioDispatcher) {
                runCatching { repository.loadAndReconcile(galleryScope, includeHidden) }.getOrNull()
            }
            if (fullSnap != null && _state.value.scope == galleryScope) {
                activeSnapshot = fullSnap
                recomputeTimeline(loadingOverride = false)
                if (_state.value.groupBy == GalleryGroupBy.PLACE) {
                    triggerGeoExtraction()
                }
            } else if (_state.value.scope == galleryScope) {
                _state.value = _state.value.copy(isLoading = false)
            }
        }
    }

    fun clearScope() {
        loadJob?.cancel()
        geoJob?.cancel()
        activeSnapshot = null
        _state.value = _state.value.copy(
            scope = null,
            isLoading = false,
            sections = emptyList(),
            flatItems = emptyList(),
            totalCount = 0,
            photoCount = 0,
            videoCount = 0,
            nonMediaDirectCount = 0,
            directSubfolders = emptyList(),
            selectedPaths = emptySet(),
        )
    }

    fun setFilter(filter: MediaFilter) {
        if (_state.value.filter == filter) return
        val nextVideoSort = if (filter == MediaFilter.VIDEOS) _state.value.videoSort else VideoSort.RECENT
        _state.value = _state.value.copy(filter = filter, videoSort = nextVideoSort)
        recomputeTimeline()
    }

    fun setGroupBy(groupBy: GalleryGroupBy) {
        settingsRepository.update { it.copy(galleryGroupBy = groupBy) }
        _state.value = _state.value.copy(groupBy = groupBy)
        recomputeTimeline()
        if (groupBy == GalleryGroupBy.PLACE) {
            triggerGeoExtraction()
        }
    }

    fun setDensity(density: GridDensity) {
        settingsRepository.update { it.copy(galleryGridDensity = density) }
        _state.value = _state.value.copy(density = density)
    }

    fun setVideoSort(videoSort: VideoSort) {
        if (_state.value.videoSort == videoSort) return
        _state.value = _state.value.copy(videoSort = videoSort)
        recomputeTimeline()
    }

    fun setExportFormat(format: MediaExportFormat) {
        settingsRepository.update { it.copy(galleryExportFormat = format) }
    }

    fun setRemoveLocationOnExport(remove: Boolean) {
        settingsRepository.update { it.copy(galleryRemoveLocationOnExport = remove) }
    }

    fun setOnlinePlaceNames(enabled: Boolean) {
        settingsRepository.update { it.copy(galleryOnlinePlaceNames = enabled) }
        if (enabled && _state.value.groupBy == GalleryGroupBy.PLACE) {
            triggerGeoExtraction()
        }
    }

    fun folderModePreferences(): FolderModePreferences =
        FolderModePreferences.fromJsonString(settingsRepository.settings.value.galleryFolderModesJson)

    fun recordFolderMode(path: VfsPath, mode: FolderLensOverride, alwaysFiles: Boolean = false) {
        val updated = folderModePreferences().record(
            path = path,
            mode = mode,
            nowMs = System.currentTimeMillis(),
            alwaysFiles = alwaysFiles,
        )
        settingsRepository.update { it.copy(galleryFolderModesJson = updated.toJsonString()) }
    }

    fun resetFolderModePreferences() {
        settingsRepository.update { it.copy(galleryFolderModesJson = "{}") }
    }

    // --- Selection & Drag-to-Select ---
    fun toggleSelection(path: VfsPath) {
        val next = _state.value.selectedPaths.toMutableSet()
        if (!next.add(path)) next.remove(path)
        _state.value = _state.value.copy(selectedPaths = next)
    }

    fun applyDragSelection(
        anchorIndex: Int,
        currentIndex: Int,
        initialSelection: Set<VfsPath>,
        targetSelected: Boolean = true,
    ) {
        val ordered = _state.value.flatItems.map { it.path }
        val updated = DragSelection.computeSelection(
            anchorIndex = anchorIndex,
            currentIndex = currentIndex,
            initialSelection = initialSelection,
            orderedPaths = ordered,
            targetSelected = targetSelected,
        )
        _state.value = _state.value.copy(selectedPaths = updated)
    }

    fun toggleSectionSelection(section: TimelineSection) {
        val secPaths = section.items.map { it.path }
        val cur = _state.value.selectedPaths.toMutableSet()
        val allSelected = secPaths.isNotEmpty() && secPaths.all { it in cur }
        if (allSelected) {
            cur.removeAll(secPaths.toSet())
        } else {
            cur.addAll(secPaths)
        }
        _state.value = _state.value.copy(selectedPaths = cur)
    }

    fun selectAll() {
        val all = _state.value.flatItems.map { it.path }.toSet()
        _state.value = _state.value.copy(selectedPaths = all)
    }

    fun invertSelection() {
        val all = _state.value.flatItems.map { it.path }.toSet()
        _state.value = _state.value.copy(selectedPaths = all - _state.value.selectedPaths)
    }

    fun clearSelection() {
        if (_state.value.selectedPaths.isNotEmpty()) {
            _state.value = _state.value.copy(selectedPaths = emptySet())
        }
    }

    fun selectedItems(): List<MediaItem> {
        val sel = _state.value.selectedPaths
        if (sel.isEmpty()) return emptyList()
        return _state.value.flatItems.filter { it.path in sel }
    }

    // --- Optimistic updates ---
    fun onMutation(mutation: GalleryMutation) {
        val updated = repository.applyOptimisticMutation(mutation)
        if (updated != null && updated.scope == _state.value.scope) {
            activeSnapshot = updated
            recomputeTimeline()
        }
        refreshRecentHome()
    }

    fun onAccessMediaLocationResult(granted: Boolean) {
        if (granted && _state.value.groupBy == GalleryGroupBy.PLACE) {
            triggerGeoExtraction()
        }
    }

    fun stop() {
        loadJob?.cancel()
        geoJob?.cancel()
        recentJob?.cancel()
        mediaStoreObserver?.stop()
    }

    private fun triggerGeoExtraction() {
        val reader = exifGeoReader ?: return
        val snap = activeSnapshot ?: return
        geoJob?.cancel()
        geoJob = scope.launch {
            _state.value = _state.value.copy(isRefreshingGeo = true)
            try {
                val extracted = reader.extractMissingGeo(snap.items)
                if (extracted.isNotEmpty()) {
                    val patched = repository.patchGeo(extracted)
                    if (patched != null) {
                        activeSnapshot = patched
                        recomputeTimeline()
                    }
                }
                // Optional online refinement ONLY if user explicitly enabled it in Settings
                if (settingsRepository.settings.value.galleryOnlinePlaceNames) {
                    val sections = _state.value.sections
                    val cached = geoIndexStore?.loadPlaceLabels().orEmpty()
                    for (sec in sections) {
                        val cluster = sec.placeCluster ?: continue
                        if (cluster.id in cached) continue
                        val refined = reader.resolveOnlinePlaceName(cluster.id, cluster.centroid)
                        if (refined != null) {
                            recomputeTimeline()
                        }
                    }
                }
            } finally {
                _state.value = _state.value.copy(isRefreshingGeo = false)
            }
        }
    }

    private fun recomputeTimeline(loadingOverride: Boolean? = null) {
        val snap = activeSnapshot
        val cur = _state.value
        if (snap == null) {
            _state.value = cur.copy(isLoading = loadingOverride ?: cur.isLoading)
            return
        }
        val lang = AppI18n.currentLanguage.value
        val locale = when (lang) {
            ResolvedLanguage.ZH -> Locale.SIMPLIFIED_CHINESE
            ResolvedLanguage.JA -> Locale.JAPANESE
            ResolvedLanguage.EN -> Locale.US
        }
        val preferZh = lang == ResolvedLanguage.ZH
        val timeline = TimelineBuilder.build(
            items = snap.items,
            filter = cur.filter,
            groupBy = cur.groupBy,
            videoSort = cur.videoSort,
            zoneId = ZoneId.systemDefault(),
            nowMs = System.currentTimeMillis(),
            locale = locale,
            gazetteer = geoIndexStore?.gazetteer,
            customPlaceLabels = geoIndexStore?.loadPlaceLabels().orEmpty(),
            preferChinesePlaceNames = preferZh,
        )
        val validSel = cur.selectedPaths.intersect(timeline.flatItems.map { it.path }.toSet())
        _state.value = cur.copy(
            isLoading = loadingOverride ?: cur.isLoading,
            sections = timeline.sections,
            flatItems = timeline.flatItems,
            totalCount = timeline.totalCount,
            photoCount = timeline.photoCount,
            videoCount = timeline.videoCount,
            nonMediaDirectCount = snap.nonMediaDirectCount,
            directSubfolders = snap.directSubfolders,
            hiddenByNomedia = snap.hiddenByNomedia,
            truncatedByBudget = snap.truncatedByBudget,
            selectedPaths = validSel,
        )
    }
}
