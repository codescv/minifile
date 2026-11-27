package com.minifile.explorer.data.gallery

import android.content.Context
import android.location.Geocoder
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.minifile.explorer.core.gallery.GeoPoint
import com.minifile.explorer.core.gallery.MediaItem
import com.minifile.explorer.core.gallery.MediaKind
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.data.SafFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.round

/**
 * Extracts GPS coordinates from images (`androidx.exifinterface`) and videos (`MediaMetadataRetriever`),
 * backed by [GeoIndexStore] so repeated visits to Place grouping are near-instant.
 *
 * Online reverse-geocoding is strictly opt-in (off by default in settings) and rounds coordinates
 * to 0.01° (~1 km) with 1 req/s rate limiting when explicitly enabled.
 */
class ExifGeoReader(
    context: Context,
    private val safFs: SafFileSystem,
    val geoStore: GeoIndexStore,
) {
    private val appContext = context.applicationContext

    suspend fun extractMissingGeo(
        items: List<MediaItem>,
        maxExtractBatch: Int = 500,
    ): Map<VfsPath, GeoPoint> = withContext(Dispatchers.IO) {
        val resolved = LinkedHashMap<VfsPath, GeoPoint>()
        val newlyComputed = LinkedHashMap<MediaItem, GeoPoint?>()
        var extractedCount = 0

        for (item in items) {
            if (item.geo != null && item.geo.isValid) continue
            val (hit, cached) = geoStore.getCached(item)
            if (hit) {
                if (cached != null && cached.isValid) {
                    resolved[item.path] = cached
                }
                continue
            }
            if (extractedCount >= maxExtractBatch) continue
            extractedCount++
            val extracted = readGeoPoint(item)
            newlyComputed[item] = extracted
            if (extracted != null && extracted.isValid) {
                resolved[item.path] = extracted
            }
        }

        if (newlyComputed.isNotEmpty()) {
            geoStore.putAll(newlyComputed)
        }
        resolved
    }

    fun readGeoPoint(item: MediaItem): GeoPoint? = when (item.kind) {
        MediaKind.IMAGE -> readImageGeo(item.path)
        MediaKind.VIDEO -> readVideoGeo(item.path)
    }

    private fun readImageGeo(path: VfsPath): GeoPoint? = runCatching {
        val exif = when (path) {
            is VfsPath.Local -> {
                val f = File(path.normalized)
                if (!f.isFile || !f.canRead()) return@runCatching null
                ExifInterface(f.absolutePath)
            }

            is VfsPath.Saf -> {
                val uri = safFs.buildDocumentUri(path)
                appContext.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
                    ?: return@runCatching null
            }

            else -> return@runCatching null
        }
        val latLon = exif.latLong ?: return@runCatching null
        GeoPoint(latLon[0], latLon[1]).takeIf { it.isValid }
    }.getOrNull()

    private fun readVideoGeo(path: VfsPath): GeoPoint? {
        val mmr = MediaMetadataRetriever()
        return try {
            when (path) {
                is VfsPath.Local -> {
                    val f = File(path.normalized)
                    if (!f.isFile || !f.canRead()) return null
                    mmr.setDataSource(f.absolutePath)
                }

                is VfsPath.Saf -> {
                    mmr.setDataSource(appContext, safFs.buildDocumentUri(path))
                }

                else -> return null
            }
            val raw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION) ?: return null
            parseIso6709Location(raw)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    /**
     * Opt-in online place name refinement. ONLY called when user explicitly enabled
     * `settings.galleryOnlinePlaceNames`. Rounds coordinates to 0.01° (~1 km) and throttles 1 req/s.
     */
    suspend fun resolveOnlinePlaceName(
        clusterId: String,
        centroid: GeoPoint,
        locale: Locale = Locale.getDefault(),
    ): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        val roundedLat = round(centroid.lat * 100.0) / 100.0
        val roundedLon = round(centroid.lon * 100.0) / 100.0
        delay(1000L) // 1 req/s rate limit
        val geocoder = Geocoder(appContext, locale)
        val label = runCatching {
            @Suppress("DEPRECATION")
            val addrs = geocoder.getFromLocation(roundedLat, roundedLon, 1)
            val addr = addrs?.firstOrNull() ?: return@runCatching null
            listOfNotNull(addr.subLocality, addr.locality, addr.adminArea)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(2)
                .joinToString(", ")
                .takeIf { it.isNotBlank() }
        }.getOrNull()
        if (label != null) {
            geoStore.savePlaceLabel(clusterId, label)
        }
        label
    }

    companion object {
        private val ISO_6709_REGEX = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""")

        fun parseIso6709Location(raw: String): GeoPoint? {
            val match = ISO_6709_REGEX.find(raw.trim()) ?: return null
            val lat = match.groupValues[1].toDoubleOrNull() ?: return null
            val lon = match.groupValues[2].toDoubleOrNull() ?: return null
            return GeoPoint(lat, lon).takeIf { it.isValid }
        }
    }
}
