package com.minifile.explorer.core.gallery

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

data class PlaceCluster(
    val id: String,
    val centroid: GeoPoint,
    val items: List<MediaItem>,
    val labelEn: String,
    val labelZh: String? = null,
    val isCoordinateFallback: Boolean = false,
    val minDateMs: Long,
    val maxDateMs: Long,
) {
    fun displayLabel(preferChinese: Boolean): String =
        if (preferChinese && !labelZh.isNullOrBlank()) labelZh else labelEn
}

data class PlaceClusteringResult(
    val clusters: List<PlaceCluster>,
    val noLocationItems: List<MediaItem>,
    val totalItemCount: Int,
)

/**
 * Pure JVM offline-first place clusterer:
 * - Quantizes valid GPS points onto a 0.1° lat/lon grid (handling ±180° antimeridian wrap).
 * - Merges 8-neighborhood cells (plus polar neighbors near ±90°) whose centroids are within 15 km
 *   using Union-Find.
 * - Computes spherical centroids so antimeridian-straddling clusters stay accurate.
 * - Resolves labels in strict order: local cache -> offline Gazetteer (<= 50 km) -> coordinate fallback.
 * - Guarantees 100% total reconciliation: `sum(cluster.items.size) + noLocationItems.size == items.size`.
 */
object PlaceClusterer {
    const val GRID_STEP_DEG = 0.1
    const val DEFAULT_MERGE_RADIUS_KM = 15.0
    const val DEFAULT_GAZETTEER_RADIUS_KM = 50.0
    private const val LON_BUCKETS = 3600 // -1800 .. +1799
    private const val DEG_TO_RAD = PI / 180.0
    private const val RAD_TO_DEG = 180.0 / PI

    private data class GridCell(val latBucket: Int, val lonBucket: Int)

    fun cluster(
        items: List<MediaItem>,
        gazetteer: Gazetteer? = null,
        customLabels: Map<String, String> = emptyMap(),
        preferChinese: Boolean = false,
        mergeRadiusKm: Double = DEFAULT_MERGE_RADIUS_KM,
        gazetteerMaxKm: Double = DEFAULT_GAZETTEER_RADIUS_KM,
    ): PlaceClusteringResult {
        if (items.isEmpty()) {
            return PlaceClusteringResult(emptyList(), emptyList(), 0)
        }

        val noLocation = ArrayList<MediaItem>()
        val cellMap = LinkedHashMap<GridCell, MutableList<MediaItem>>()

        for (item in items) {
            val g = item.geo
            if (g == null || !g.isValid) {
                noLocation.add(item)
                continue
            }
            val cell = quantize(g)
            cellMap.getOrPut(cell) { ArrayList() }.add(item)
        }

        if (cellMap.isEmpty()) {
            return PlaceClusteringResult(emptyList(), noLocation, items.size)
        }

        val cells = cellMap.keys.toList()
        val cellIndex = HashMap<GridCell, Int>(cells.size * 2)
        val cellCentroids = ArrayList<GeoPoint>(cells.size)
        for ((idx, cell) in cells.withIndex()) {
            cellIndex[cell] = idx
            val pts = cellMap.getValue(cell).mapNotNull { it.geo }
            cellCentroids.add(sphericalCentroid(pts))
        }

        // Union-Find across 8-neighborhood cells (with ±180° wrap) and polar proximity
        val parent = IntArray(cells.size) { it }
        fun find(x: Int): Int {
            var cur = x
            while (parent[cur] != cur) {
                parent[cur] = parent[parent[cur]]
                cur = parent[cur]
            }
            return cur
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        val polarCells = ArrayList<Int>()
        for ((idx, cell) in cells.withIndex()) {
            val c1 = cellCentroids[idx]
            if (abs(c1.lat) >= 88.5) {
                polarCells.add(idx)
            }
            for (dLat in -1..1) {
                val nLat = cell.latBucket + dLat
                if (nLat < -900 || nLat > 900) continue
                for (dLon in -1..1) {
                    if (dLat == 0 && dLon == 0) continue
                    val nLon = wrapLonBucket(cell.lonBucket + dLon)
                    val neighborIdx = cellIndex[GridCell(nLat, nLon)] ?: continue
                    if (neighborIdx <= idx) continue
                    val c2 = cellCentroids[neighborIdx]
                    if (Gazetteer.haversineKm(c1.lat, c1.lon, c2.lat, c2.lon) <= mergeRadiusKm) {
                        union(idx, neighborIdx)
                    }
                }
            }
        }
        // Near poles, all longitudes converge so check pairwise among polar cells
        for (i in polarCells.indices) {
            for (j in i + 1 until polarCells.size) {
                val idxA = polarCells[i]
                val idxB = polarCells[j]
                val ca = cellCentroids[idxA]
                val cb = cellCentroids[idxB]
                if (Gazetteer.haversineKm(ca.lat, ca.lon, cb.lat, cb.lon) <= mergeRadiusKm) {
                    union(idxA, idxB)
                }
            }
        }

        val componentItems = LinkedHashMap<Int, MutableList<MediaItem>>()
        for ((idx, cell) in cells.withIndex()) {
            val root = find(idx)
            componentItems.getOrPut(root) { ArrayList() }.addAll(cellMap.getValue(cell))
        }

        val clusters = componentItems.values.map { list ->
            val sortedItems = list.sortedByDescending { it.bestDateMs }
            val centroid = sphericalCentroid(sortedItems.mapNotNull { it.geo })
            val clusterId = clusterKey(centroid)
            val minDate = sortedItems.minOf { it.bestDateMs }
            val maxDate = sortedItems.maxOf { it.bestDateMs }

            val custom = customLabels[clusterId]
            if (!custom.isNullOrBlank()) {
                PlaceCluster(
                    id = clusterId,
                    centroid = centroid,
                    items = sortedItems,
                    labelEn = custom,
                    labelZh = custom,
                    isCoordinateFallback = false,
                    minDateMs = minDate,
                    maxDateMs = maxDate,
                )
            } else {
                val match = gazetteer?.findNearest(centroid, gazetteerMaxKm)
                if (match != null) {
                    PlaceCluster(
                        id = clusterId,
                        centroid = centroid,
                        items = sortedItems,
                        labelEn = match.city.nameEn,
                        labelZh = match.city.nameZh,
                        isCoordinateFallback = false,
                        minDateMs = minDate,
                        maxDateMs = maxDate,
                    )
                } else {
                    val coordLabel = formatCoordinateFallback(centroid)
                    PlaceCluster(
                        id = clusterId,
                        centroid = centroid,
                        items = sortedItems,
                        labelEn = coordLabel,
                        labelZh = null,
                        isCoordinateFallback = true,
                        minDateMs = minDate,
                        maxDateMs = maxDate,
                    )
                }
            }
        }.sortedWith(
            compareByDescending<PlaceCluster> { it.maxDateMs }
                .thenByDescending { it.items.size }
                .thenBy { it.labelEn },
        )

        return PlaceClusteringResult(
            clusters = clusters,
            noLocationItems = noLocation,
            totalItemCount = items.size,
        )
    }

    fun clusterKey(centroid: GeoPoint): String {
        val latRounded = kotlin.math.round(centroid.lat * 100.0) / 100.0
        val lonRounded = kotlin.math.round(centroid.lon * 100.0) / 100.0
        return String.format(Locale.US, "%.2f,%.2f", latRounded, lonRounded)
    }

    fun formatCoordinateFallback(point: GeoPoint): String {
        val latDir = if (point.lat >= 0.0) "N" else "S"
        val lonDir = if (point.lon >= 0.0) "E" else "W"
        return String.format(
            Locale.US,
            "Near %.1f°%s, %.1f°%s",
            abs(point.lat),
            latDir,
            abs(point.lon),
            lonDir,
        )
    }

    fun sphericalCentroid(points: List<GeoPoint>): GeoPoint {
        if (points.isEmpty()) return GeoPoint(0.0, 0.0)
        if (points.size == 1) return points[0]
        var x = 0.0
        var y = 0.0
        var z = 0.0
        for (p in points) {
            val latR = p.lat * DEG_TO_RAD
            val lonR = p.lon * DEG_TO_RAD
            val cosLat = cos(latR)
            x += cosLat * cos(lonR)
            y += cosLat * sin(lonR)
            z += sin(latR)
        }
        val n = points.size.toDouble()
        x /= n
        y /= n
        z /= n
        val hyp = sqrt(x * x + y * y)
        if (hyp < 1e-12 && abs(z) < 1e-12) {
            return points[0]
        }
        val lat = atan2(z, hyp) * RAD_TO_DEG
        val lon = atan2(y, x) * RAD_TO_DEG
        return GeoPoint(lat.coerceIn(-90.0, 90.0), lon.coerceIn(-180.0, 180.0))
    }

    private fun quantize(point: GeoPoint): GridCell {
        val latB = floor(point.lat / GRID_STEP_DEG).toInt().coerceIn(-900, 900)
        val rawLonB = floor(point.lon / GRID_STEP_DEG).toInt()
        val lonB = wrapLonBucket(rawLonB)
        return GridCell(latB, lonB)
    }

    private fun wrapLonBucket(raw: Int): Int {
        // Maps any integer bucket into [-1800, +1799] modulo 3600
        val shifted = ((raw + 1800) % LON_BUCKETS + LON_BUCKETS) % LON_BUCKETS
        return shifted - 1800
    }
}
