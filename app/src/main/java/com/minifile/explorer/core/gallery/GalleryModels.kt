package com.minifile.explorer.core.gallery

import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsPath
import java.util.Locale

enum class MediaKind {
    IMAGE,
    VIDEO,
}

enum class MediaFilter {
    ALL,
    PHOTOS,
    VIDEOS,
}

enum class TimeGranularity {
    DAY,
    MONTH,
    YEAR,
}

enum class GalleryGroupBy {
    DAY,
    MONTH,
    YEAR,
    PLACE,
    FOLDER;

    fun toTimeGranularityOrNull(): TimeGranularity? = when (this) {
        DAY -> TimeGranularity.DAY
        MONTH -> TimeGranularity.MONTH
        YEAR -> TimeGranularity.YEAR
        PLACE, FOLDER -> null
    }
}

enum class GridDensity(val minCellDp: Int, val portraitCols: Int) {
    LARGE(120, 3),
    MEDIUM(88, 4),
    SMALL(60, 6),
}

enum class VideoSort {
    RECENT,
    LARGEST,
    LONGEST,
}

enum class BrowseLens {
    FILES,
    GALLERY,
}

enum class MediaExportFormat {
    ORIGINAL,
    MOST_COMPATIBLE,
}

data class GeoPoint(
    val lat: Double,
    val lon: Double,
) {
    val isValid: Boolean
        get() = lat.isFinite() && lon.isFinite() &&
            lat in -90.0..90.0 && lon in -180.0..180.0 &&
            !(lat == 0.0 && lon == 0.0)
}

object MediaItemFlags {
    const val MOTION_OR_LIVE: Int = 1 shl 0
    const val HDR: Int = 1 shl 1
    const val PENDING_INDEX: Int = 1 shl 2
    const val HIDDEN_BY_NOMEDIA: Int = 1 shl 3
}

data class MediaItem(
    val path: VfsPath,
    val name: String,
    val kind: MediaKind,
    val mime: String,
    val sizeBytes: Long,
    val bestDateMs: Long,
    val modifiedMs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val orientation: Int = 0,
    val durationMs: Long = 0L,
    val bucketDir: String = "",
    val storeId: Long = -1L,
    val geo: GeoPoint? = null,
    val flags: Int = 0,
) {
    val isImage: Boolean get() = kind == MediaKind.IMAGE
    val isVideo: Boolean get() = kind == MediaKind.VIDEO

    fun hasFlag(flag: Int): Boolean = (flags and flag) != 0

    val effectiveWidth: Int
        get() = if (orientation == 90 || orientation == 270) height else width

    val effectiveHeight: Int
        get() = if (orientation == 90 || orientation == 270) width else height
}

sealed interface GalleryScope {
    data object Library : GalleryScope

    data class Folder(
        val dir: VfsPath,
        val includeSubtree: Boolean = true,
        val hiddenByNomedia: Boolean = false,
    ) : GalleryScope

    fun serialize(): String = when (this) {
        Library -> "library"
        is Folder -> "folder:${if (includeSubtree) 1 else 0}:${if (hiddenByNomedia) 1 else 0}:${dir.serialize()}"
    }

    companion object {
        fun parse(raw: String?): GalleryScope? {
            if (raw.isNullOrBlank()) return null
            if (raw == "library") return Library
            if (raw.startsWith("folder:")) {
                val rest = raw.removePrefix("folder:")
                val firstColon = rest.indexOf(':')
                if (firstColon <= 0) return null
                val secondColon = rest.indexOf(':', firstColon + 1)
                if (secondColon <= firstColon) return null
                val includeSubtree = rest.substring(0, firstColon) == "1"
                val hiddenByNomedia = rest.substring(firstColon + 1, secondColon) == "1"
                val pathRaw = rest.substring(secondColon + 1)
                val parsedPath = runCatching { VfsPath.parse(pathRaw) }.getOrNull() ?: return null
                return Folder(parsedPath, includeSubtree, hiddenByNomedia)
            }
            return null
        }
    }
}

object MediaExtensionRegistry {
    val IMAGE_EXTENSIONS: Set<String> = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "avif", "dng",
    )

    val VIDEO_EXTENSIONS: Set<String> = setOf(
        "mp4", "m4v", "mov", "3gp", "3gpp", "mkv", "webm", "avi", "ts",
    )

    val LOSSLESS_VIDEO_CONTAINER_EXTENSIONS: Set<String> = setOf(
        "mp4", "m4v", "3gp", "3gpp", "webm", "mov",
    )

    fun classify(fileName: String, mime: String? = null): MediaKind? {
        val m = mime?.lowercase(Locale.ROOT).orEmpty()
        if (m.startsWith("image/") && m != "image/svg+xml") return MediaKind.IMAGE
        if (m.startsWith("video/")) return MediaKind.VIDEO
        val ext = PathUtils.extensionOf(fileName)
        return when {
            ext in IMAGE_EXTENSIONS -> MediaKind.IMAGE
            ext in VIDEO_EXTENSIONS -> MediaKind.VIDEO
            else -> null
        }
    }

    fun isMedia(fileName: String, mime: String? = null): Boolean = classify(fileName, mime) != null

    fun isJpeg(fileName: String, mime: String? = null): Boolean {
        val m = mime?.lowercase(Locale.ROOT).orEmpty()
        if (m == "image/jpeg" || m == "image/jpg") return true
        val ext = PathUtils.extensionOf(fileName)
        return ext == "jpg" || ext == "jpeg"
    }

    fun isHeic(fileName: String, mime: String? = null): Boolean {
        val m = mime?.lowercase(Locale.ROOT).orEmpty()
        if (m == "image/heic" || m == "image/heif") return true
        val ext = PathUtils.extensionOf(fileName)
        return ext == "heic" || ext == "heif"
    }

    fun isPng(fileName: String, mime: String? = null): Boolean {
        val m = mime?.lowercase(Locale.ROOT).orEmpty()
        if (m == "image/png") return true
        return PathUtils.extensionOf(fileName) == "png"
    }

    fun isWebp(fileName: String, mime: String? = null): Boolean {
        val m = mime?.lowercase(Locale.ROOT).orEmpty()
        if (m == "image/webp") return true
        return PathUtils.extensionOf(fileName) == "webp"
    }

    fun isLosslessEditableVideo(fileName: String, mime: String? = null): Boolean {
        val ext = PathUtils.extensionOf(fileName)
        if (ext in LOSSLESS_VIDEO_CONTAINER_EXTENSIONS) return true
        val m = mime?.lowercase(Locale.ROOT).orEmpty()
        return m in setOf("video/mp4", "video/3gpp", "video/webm", "video/quicktime")
    }
}
