package com.minifile.explorer.core.gallery

import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsPath
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class SectionKind {
    TODAY,
    YESTERDAY,
    DAY,
    MONTH,
    YEAR,
    PLACE,
    NO_LOCATION,
    FOLDER,
    FLAT_VIDEO_SORT,
}

data class TimelineSection(
    val id: String,
    val keyEpochMs: Long,
    val kind: SectionKind,
    val items: List<MediaItem>,
    val titleHint: String = "",
    val subtitleHint: String? = null,
    val folderPath: VfsPath? = null,
    val placeCluster: PlaceCluster? = null,
)

data class TimelineResult(
    val sections: List<TimelineSection>,
    val flatItems: List<MediaItem>,
    val totalCount: Int,
    val photoCount: Int,
    val videoCount: Int,
)

object TimelineBuilder {

    /**
     * Canonical bestDateMs fallback chain:
     * DATE_TAKEN (> 0) -> DATE_MODIFIED * 1000 (> 0) -> file.lastModified().
     */
    fun bestDateMs(
        dateTakenMs: Long?,
        dateModifiedSeconds: Long?,
        fileLastModifiedMs: Long,
    ): Long {
        if (dateTakenMs != null && dateTakenMs > 0L) return dateTakenMs
        if (dateModifiedSeconds != null && dateModifiedSeconds > 0L) return dateModifiedSeconds * 1000L
        return fileLastModifiedMs.coerceAtLeast(0L)
    }

    fun build(
        items: List<MediaItem>,
        filter: MediaFilter = MediaFilter.ALL,
        groupBy: GalleryGroupBy = GalleryGroupBy.DAY,
        videoSort: VideoSort = VideoSort.RECENT,
        zoneId: ZoneId = ZoneId.systemDefault(),
        nowMs: Long = System.currentTimeMillis(),
        locale: Locale = Locale.US,
        gazetteer: Gazetteer? = null,
        customPlaceLabels: Map<String, String> = emptyMap(),
        preferChinesePlaceNames: Boolean = false,
    ): TimelineResult {
        var photos = 0
        var videos = 0
        for (item in items) {
            if (item.kind == MediaKind.IMAGE) photos++ else videos++
        }

        val filtered = when (filter) {
            MediaFilter.ALL -> items
            MediaFilter.PHOTOS -> items.filter { it.kind == MediaKind.IMAGE }
            MediaFilter.VIDEOS -> items.filter { it.kind == MediaKind.VIDEO }
        }

        if (filtered.isEmpty()) {
            return TimelineResult(
                sections = emptyList(),
                flatItems = emptyList(),
                totalCount = items.size,
                photoCount = photos,
                videoCount = videos,
            )
        }

        // Videos filter with Largest / Longest sorting renders as a clean sorted section.
        if (filter == MediaFilter.VIDEOS && videoSort != VideoSort.RECENT) {
            val sorted = when (videoSort) {
                VideoSort.LARGEST -> filtered.sortedWith(
                    compareByDescending<MediaItem> { it.sizeBytes }
                        .thenByDescending { it.bestDateMs }
                        .thenBy { it.name.lowercase(Locale.ROOT) },
                )

                VideoSort.LONGEST -> filtered.sortedWith(
                    compareByDescending<MediaItem> { it.durationMs }
                        .thenByDescending { it.bestDateMs }
                        .thenBy { it.name.lowercase(Locale.ROOT) },
                )

                VideoSort.RECENT -> filtered
            }
            val sec = TimelineSection(
                id = "videos_${videoSort.name.lowercase(Locale.ROOT)}",
                keyEpochMs = sorted.first().bestDateMs,
                kind = SectionKind.FLAT_VIDEO_SORT,
                items = sorted,
            )
            return TimelineResult(
                sections = listOf(sec),
                flatItems = sorted,
                totalCount = items.size,
                photoCount = photos,
                videoCount = videos,
            )
        }

        val sections = when (groupBy) {
            GalleryGroupBy.DAY -> buildByDay(filtered, zoneId, nowMs, locale)
            GalleryGroupBy.MONTH -> buildByMonth(filtered, zoneId, locale)
            GalleryGroupBy.YEAR -> buildByYear(filtered, zoneId)
            GalleryGroupBy.FOLDER -> buildByFolder(filtered)
            GalleryGroupBy.PLACE -> buildByPlace(filtered, gazetteer, customPlaceLabels, preferChinesePlaceNames)
        }

        val flat = sections.flatMap { it.items }
        return TimelineResult(
            sections = sections,
            flatItems = flat,
            totalCount = items.size,
            photoCount = photos,
            videoCount = videos,
        )
    }

    private fun sortByRecency(items: List<MediaItem>): List<MediaItem> =
        items.sortedWith(
            compareByDescending<MediaItem> { it.bestDateMs }
                .thenComparator { a, b -> PathUtils.naturalCompare(a.name, b.name) },
        )

    private fun buildByDay(
        items: List<MediaItem>,
        zoneId: ZoneId,
        nowMs: Long,
        locale: Locale,
    ): List<TimelineSection> {
        val sorted = sortByRecency(items)
        val today = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        val yesterday = today.minusDays(1)
        val sameYearFmt = DateTimeFormatter.ofPattern("EEE, MMM d", locale)
        val otherYearFmt = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", locale)

        val grouped = LinkedHashMap<LocalDate, MutableList<MediaItem>>()
        for (item in sorted) {
            val d = Instant.ofEpochMilli(item.bestDateMs).atZone(zoneId).toLocalDate()
            grouped.getOrPut(d) { ArrayList() }.add(item)
        }

        return grouped.entries.map { (date, list) ->
            val kind = when (date) {
                today -> SectionKind.TODAY
                yesterday -> SectionKind.YESTERDAY
                else -> SectionKind.DAY
            }
            val hint = if (date.year == today.year) date.format(sameYearFmt) else date.format(otherYearFmt)
            TimelineSection(
                id = "day:$date",
                keyEpochMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                kind = kind,
                items = list,
                titleHint = hint,
            )
        }
    }

    private fun buildByMonth(
        items: List<MediaItem>,
        zoneId: ZoneId,
        locale: Locale,
    ): List<TimelineSection> {
        val sorted = sortByRecency(items)
        val fmt = DateTimeFormatter.ofPattern("MMMM yyyy", locale)
        val grouped = LinkedHashMap<YearMonth, MutableList<MediaItem>>()
        for (item in sorted) {
            val d = Instant.ofEpochMilli(item.bestDateMs).atZone(zoneId).toLocalDate()
            val ym = YearMonth.of(d.year, d.month)
            grouped.getOrPut(ym) { ArrayList() }.add(item)
        }
        return grouped.entries.map { (ym, list) ->
            TimelineSection(
                id = "month:$ym",
                keyEpochMs = ym.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
                kind = SectionKind.MONTH,
                items = list,
                titleHint = ym.format(fmt),
            )
        }
    }

    private fun buildByYear(
        items: List<MediaItem>,
        zoneId: ZoneId,
    ): List<TimelineSection> {
        val sorted = sortByRecency(items)
        val grouped = LinkedHashMap<Int, MutableList<MediaItem>>()
        for (item in sorted) {
            val y = Instant.ofEpochMilli(item.bestDateMs).atZone(zoneId).year
            grouped.getOrPut(y) { ArrayList() }.add(item)
        }
        return grouped.entries.map { (year, list) ->
            TimelineSection(
                id = "year:$year",
                keyEpochMs = LocalDate.of(year, 1, 1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
                kind = SectionKind.YEAR,
                items = list,
                titleHint = year.toString(),
            )
        }
    }

    private fun buildByFolder(items: List<MediaItem>): List<TimelineSection> {
        val sorted = sortByRecency(items)
        val grouped = LinkedHashMap<String, MutableList<MediaItem>>()
        for (item in sorted) {
            val key = item.bucketDir.ifBlank { parentDirKey(item.path) }
            grouped.getOrPut(key) { ArrayList() }.add(item)
        }
        return grouped.entries
            .sortedByDescending { it.value.first().bestDateMs }
            .map { (bucket, list) ->
                val folderPath = parentVfsPath(list.first().path)
                val label = bucket.trimEnd('/').substringAfterLast('/').ifBlank { bucket }
                TimelineSection(
                    id = "folder:$bucket",
                    keyEpochMs = list.first().bestDateMs,
                    kind = SectionKind.FOLDER,
                    items = list,
                    titleHint = label,
                    folderPath = folderPath,
                )
            }
    }

    private fun buildByPlace(
        items: List<MediaItem>,
        gazetteer: Gazetteer?,
        customPlaceLabels: Map<String, String>,
        preferChinese: Boolean,
    ): List<TimelineSection> {
        val sorted = sortByRecency(items)
        val clustered = PlaceClusterer.cluster(
            items = sorted,
            gazetteer = gazetteer,
            customLabels = customPlaceLabels,
            preferChinese = preferChinese,
        )
        val out = ArrayList<TimelineSection>(clustered.clusters.size + 1)
        for (c in clustered.clusters) {
            out.add(
                TimelineSection(
                    id = "place:${c.id}",
                    keyEpochMs = c.maxDateMs,
                    kind = SectionKind.PLACE,
                    items = c.items,
                    titleHint = c.displayLabel(preferChinese),
                    placeCluster = c,
                ),
            )
        }
        if (clustered.noLocationItems.isNotEmpty()) {
            out.add(
                TimelineSection(
                    id = "place:no_location",
                    keyEpochMs = clustered.noLocationItems.first().bestDateMs,
                    kind = SectionKind.NO_LOCATION,
                    items = clustered.noLocationItems,
                    titleHint = "No location",
                ),
            )
        }
        return out
    }

    private fun parentDirKey(path: VfsPath): String = when (path) {
        is VfsPath.Local -> {
            val norm = path.normalized
            val idx = norm.lastIndexOf('/')
            if (idx <= 0) "/" else norm.substring(0, idx)
        }

        is VfsPath.Saf -> {
            val doc = path.documentId
            val idx = doc.lastIndexOf('/')
            if (idx <= 0) doc else doc.substring(0, idx)
        }

        is VfsPath.Ftp -> {
            val norm = path.normalizedRemotePath
            val idx = norm.lastIndexOf('/')
            if (idx <= 0) "/" else norm.substring(0, idx)
        }

        is VfsPath.GDrive -> {
            val norm = path.normalizedRemotePath
            val idx = norm.lastIndexOf('/')
            if (idx <= 0) "/" else norm.substring(0, idx)
        }
    }

    private fun parentVfsPath(path: VfsPath): VfsPath? = when (path) {
        is VfsPath.Local -> {
            val norm = path.normalized
            val idx = norm.lastIndexOf('/')
            if (idx <= 0) null else VfsPath.Local(norm.substring(0, idx))
        }

        is VfsPath.Saf -> {
            val doc = path.documentId
            val idx = doc.lastIndexOf('/')
            if (idx <= 0) null else VfsPath.Saf(path.treeUri, doc.substring(0, idx))
        }

        is VfsPath.Ftp -> {
            val norm = path.normalizedRemotePath
            val idx = norm.lastIndexOf('/')
            if (idx <= 0) null else VfsPath.Ftp(path.serverId, norm.substring(0, idx))
        }

        is VfsPath.GDrive -> {
            val norm = path.normalizedRemotePath
            val idx = norm.lastIndexOf('/')
            if (idx <= 0) null else VfsPath.GDrive(path.serverId, norm.substring(0, idx))
        }
    }

    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "0:00"
        val totalSec = (durationMs + 500L) / 1000L
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(Locale.US, "%d:%02d", mins, secs)
        }
    }
}
