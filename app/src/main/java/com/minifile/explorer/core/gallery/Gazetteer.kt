package com.minifile.explorer.core.gallery

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GazetteerCity(
    val lat: Double,
    val lon: Double,
    val populationRank: Int,
    val countryCode: String,
    val nameEn: String,
    val nameZh: String? = null,
)

data class GazetteerMatch(
    val city: GazetteerCity,
    val distanceKm: Double,
) {
    fun displayName(preferChinese: Boolean): String =
        if (preferChinese && !city.nameZh.isNullOrBlank()) city.nameZh else city.nameEn
}

/**
 * Offline compact city dictionary (`assets/places_v1.bin`, magic `MFPL`, version 1).
 *
 * Binary record format (big-endian):
 *   - Header: 4B magic "MFPL", 2B version (u16 = 1), 4B count (u32)
 *   - Record x count:
 *       4B latE4 (Int, degrees * 10000)
 *       4B lonE4 (Int, degrees * 10000)
 *       2B populationRank (u16, higher = larger city)
 *       2B countryCode (2 ASCII bytes, e.g. "CN", "US", "JP")
 *       1B enLen (u8), [enLen] UTF-8 bytes
 *       1B zhLen (u8), [zhLen] UTF-8 bytes (0 if no Chinese alias)
 */
class Gazetteer(
    val cities: List<GazetteerCity>,
) {
    fun findNearest(
        point: GeoPoint,
        maxDistanceKm: Double = DEFAULT_MAX_DISTANCE_KM,
    ): GazetteerMatch? {
        if (!point.isValid || cities.isEmpty()) return null
        val latDelta = maxDistanceKm / 110.574
        val cosLat = cos(point.lat * DEG_TO_RAD).coerceAtLeast(0.01)
        val lonDelta = (maxDistanceKm / (111.320 * cosLat)).coerceAtMost(180.0)

        var bestCity: GazetteerCity? = null
        var bestDist = Double.POSITIVE_INFINITY

        for (city in cities) {
            if (kotlin.math.abs(city.lat - point.lat) > latDelta) continue
            val dLonRaw = kotlin.math.abs(city.lon - point.lon)
            val dLon = minOf(dLonRaw, 360.0 - dLonRaw)
            if (dLon > lonDelta) continue

            val dist = haversineKm(point.lat, point.lon, city.lat, city.lon)
            if (dist <= maxDistanceKm) {
                // Slight tie-break in favor of higher population rank when within 1 km
                if (dist + 0.001 < bestDist ||
                    (kotlin.math.abs(dist - bestDist) <= 1.0 &&
                        bestCity != null && city.populationRank > bestCity.populationRank)
                ) {
                    bestDist = dist
                    bestCity = city
                }
            }
        }
        return bestCity?.let { GazetteerMatch(it, bestDist) }
    }

    companion object {
        const val MAGIC = "MFPL"
        const val VERSION = 1
        const val DEFAULT_MAX_DISTANCE_KM = 50.0
        private const val EARTH_RADIUS_KM = 6371.0088
        private const val DEG_TO_RAD = PI / 180.0

        val EMPTY = Gazetteer(emptyList())

        fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = (lat2 - lat1) * DEG_TO_RAD
            val dLon = (lon2 - lon1) * DEG_TO_RAD
            val rLat1 = lat1 * DEG_TO_RAD
            val rLat2 = lat2 * DEG_TO_RAD
            val sinLat = sin(dLat / 2.0)
            val sinLon = sin(dLon / 2.0)
            val a = sinLat * sinLat + cos(rLat1) * cos(rLat2) * sinLon * sinLon
            val c = 2.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
            return EARTH_RADIUS_KM * c
        }

        fun load(bytes: ByteArray): Gazetteer = load(ByteArrayInputStream(bytes))

        fun load(input: InputStream): Gazetteer {
            val dis = DataInputStream(BufferedInputStream(input, 16 * 1024))
            val magicBytes = ByteArray(4)
            dis.readFully(magicBytes)
            val magic = String(magicBytes, Charsets.US_ASCII)
            require(magic == MAGIC) { "Invalid places binary magic: $magic" }
            val version = dis.readUnsignedShort()
            require(version == VERSION) { "Unsupported places binary version: $version" }
            val count = dis.readInt()
            require(count in 0..500_000) { "Invalid places record count: $count" }

            val list = ArrayList<GazetteerCity>(count)
            val ccBuf = ByteArray(2)
            for (i in 0 until count) {
                val latE4 = dis.readInt()
                val lonE4 = dis.readInt()
                val rank = dis.readUnsignedShort()
                dis.readFully(ccBuf)
                val cc = String(ccBuf, Charsets.US_ASCII)
                val enLen = dis.readUnsignedByte()
                val enBytes = ByteArray(enLen)
                dis.readFully(enBytes)
                val zhLen = dis.readUnsignedByte()
                val zhName = if (zhLen > 0) {
                    val zhBytes = ByteArray(zhLen)
                    dis.readFully(zhBytes)
                    String(zhBytes, Charsets.UTF_8)
                } else {
                    null
                }
                list.add(
                    GazetteerCity(
                        lat = latE4 / 10_000.0,
                        lon = lonE4 / 10_000.0,
                        populationRank = rank,
                        countryCode = cc,
                        nameEn = String(enBytes, Charsets.UTF_8),
                        nameZh = zhName,
                    ),
                )
            }
            return Gazetteer(list)
        }
    }
}
