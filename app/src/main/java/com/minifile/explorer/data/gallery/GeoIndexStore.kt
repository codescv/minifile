package com.minifile.explorer.data.gallery

import android.content.Context
import com.minifile.explorer.core.gallery.Gazetteer
import com.minifile.explorer.core.gallery.GeoPoint
import com.minifile.explorer.core.gallery.MediaItem
import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.MiniJson
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Compact sidecar GPS coordinate cache (`filesDir/gallery/geo_v1.bin`, 24 bytes/entry, <= 2 MiB)
 * + place name override/cache (`filesDir/gallery/places_cache.json`, <= 512 KiB)
 * + lazy loader for built-in offline `assets/places_v1.bin`.
 */
class GeoIndexStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val galleryDir = File(appContext.filesDir, "gallery").apply { mkdirs() }
    private val geoFile = File(galleryDir, "geo_v1.bin")
    private val placesCacheFile = File(galleryDir, "places_cache.json")

    private val lock = Any()
    private val coords = LinkedHashMap<Long, GeoPoint?>()
    private var loaded = false

    val gazetteer: Gazetteer by lazy {
        runCatching {
            appContext.assets.open("places_v1.bin").use { Gazetteer.load(it) }
        }.getOrDefault(Gazetteer.EMPTY)
    }

    fun getCached(item: MediaItem): Pair<Boolean, GeoPoint?> = synchronized(lock) {
        ensureLoadedLocked()
        val key = itemKeyHash(item)
        if (coords.containsKey(key)) true to coords[key] else false to null
    }

    fun putAll(entries: Map<MediaItem, GeoPoint?>) = synchronized(lock) {
        if (entries.isEmpty()) return
        ensureLoadedLocked()
        for ((item, point) in entries) {
            coords[itemKeyHash(item)] = point?.takeIf { it.isValid }
        }
        while (coords.size > MAX_ENTRIES) {
            val firstKey = coords.keys.firstOrNull() ?: break
            coords.remove(firstKey)
        }
        persistLocked()
    }

    fun loadPlaceLabels(): Map<String, String> {
        if (!placesCacheFile.isFile) return emptyMap()
        return runCatching {
            val obj = MiniJson.parse(placesCacheFile.readText(Charsets.UTF_8)) as? JsonValue.Obj
                ?: return@runCatching emptyMap()
            obj.entries.mapNotNull { (k, v) ->
                val str = (v as? JsonValue.Str)?.value?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                k to str
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun savePlaceLabel(clusterId: String, label: String) {
        synchronized(lock) {
            val current = loadPlaceLabels().toMutableMap()
            current[clusterId] = label
            while (current.size > MAX_PLACE_LABELS) {
                val first = current.keys.firstOrNull() ?: break
                current.remove(first)
            }
            runCatching {
                galleryDir.mkdirs()
                val obj = MiniJson.obj(*current.entries.map { it.key to JsonValue.Str(it.value) }.toTypedArray())
                val raw = MiniJson.stringify(obj)
                if (raw.toByteArray(Charsets.UTF_8).size <= MAX_PLACES_CACHE_BYTES) {
                    val tmp = File(galleryDir, "places_cache.json.tmp")
                    tmp.writeText(raw, Charsets.UTF_8)
                    if (!tmp.renameTo(placesCacheFile)) {
                        tmp.copyTo(placesCacheFile, overwrite = true)
                        tmp.delete()
                    }
                }
            }
        }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        if (!geoFile.isFile) return
        runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(geoFile))).use { dis ->
                val magic = dis.readInt()
                if (magic != MAGIC_INT) return
                val count = dis.readInt().coerceIn(0, MAX_ENTRIES)
                repeat(count) {
                    val hash = dis.readLong()
                    val lat = dis.readDouble()
                    val lon = dis.readDouble()
                    coords[hash] = if (lat.isNaN() || lon.isNaN()) null else GeoPoint(lat, lon)
                }
            }
        }
    }

    private fun persistLocked() {
        runCatching {
            galleryDir.mkdirs()
            val tmp = File(galleryDir, "geo_v1.bin.tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { dos ->
                dos.writeInt(MAGIC_INT)
                dos.writeInt(coords.size)
                for ((hash, point) in coords) {
                    dos.writeLong(hash)
                    if (point == null) {
                        dos.writeDouble(Double.NaN)
                        dos.writeDouble(Double.NaN)
                    } else {
                        dos.writeDouble(point.lat)
                        dos.writeDouble(point.lon)
                    }
                }
            }
            if (!tmp.renameTo(geoFile)) {
                tmp.copyTo(geoFile, overwrite = true)
                tmp.delete()
            }
        }
    }

    companion object {
        private const val MAGIC_INT = 0x4D464731 // "MFG1"
        private const val MAX_ENTRIES = (2 * 1024 * 1024) / 24 // ~87k records <= 2 MiB
        private const val MAX_PLACES_CACHE_BYTES = 512 * 1024
        private const val MAX_PLACE_LABELS = 4096

        /** 64-bit FNV-1a hash of `(name, sizeBytes, modifiedSec)`. */
        fun itemKeyHash(item: MediaItem): Long {
            val raw = "${item.name}|${item.sizeBytes}|${item.modifiedMs / 1000L}"
            var h = -0x340d631b7bdddcdbL // 14695981039346656037 unsigned
            for (ch in raw) {
                h = h xor ch.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }
    }
}
