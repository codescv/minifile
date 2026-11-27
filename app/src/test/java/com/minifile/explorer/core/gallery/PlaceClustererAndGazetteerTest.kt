package com.minifile.explorer.core.gallery

import com.minifile.explorer.core.vfs.VfsPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlaceClustererAndGazetteerTest {

    private fun photo(name: String, geo: GeoPoint?, dateMs: Long = 1_700_000_000_000L): MediaItem =
        MediaItem(
            path = VfsPath.Local("/storage/emulated/0/DCIM/$name"),
            name = name,
            kind = MediaKind.IMAGE,
            mime = "image/jpeg",
            sizeBytes = 100_000L,
            bestDateMs = dateMs,
            modifiedMs = dateMs,
            geo = geo,
        )

    private fun loadRealAssetGazetteer(): Gazetteer {
        val candidates = listOf(
            File("src/main/assets/places_v1.bin"),
            File("app/src/main/assets/places_v1.bin"),
        )
        val asset = candidates.firstOrNull { it.isFile }
            ?: error("Could not locate places_v1.bin asset from ${File(".").absolutePath}")
        return asset.inputStream().use { Gazetteer.load(it) }
    }

    @Test
    fun realPlacesAsset_loadsCleanlyAndResolvesChineseAndEnglishNames() {
        val gaz = loadRealAssetGazetteer()
        assertTrue("Expected >= 300 cities in places_v1.bin, got ${gaz.cities.size}", gaz.cities.size >= 300)

        val sh = gaz.findNearest(GeoPoint(31.235, 121.480))
        assertNotNull(sh)
        assertEquals("Shanghai", sh!!.displayName(preferChinese = false))
        assertEquals("上海", sh.displayName(preferChinese = true))

        val tokyo = gaz.findNearest(GeoPoint(35.68, 139.65))
        assertNotNull(tokyo)
        assertEquals("Tokyo", tokyo!!.displayName(preferChinese = false))
        assertEquals("东京", tokyo.displayName(preferChinese = true))

        val sf = gaz.findNearest(GeoPoint(37.78, -122.42))
        assertNotNull(sf)
        assertEquals("San Francisco", sf!!.displayName(preferChinese = false))
        assertEquals("旧金山", sf.displayName(preferChinese = true))
    }

    @Test
    fun clustering_mergesWithin15KmKeepsSeparateOutsideAndReconciles100Percent() {
        val gaz = loadRealAssetGazetteer()
        val items = listOf(
            // Two points in Shanghai across 0.1° grid boundary (~4 km apart -> merged!)
            photo("sh1.jpg", GeoPoint(31.195, 121.470), dateMs = 5000L),
            photo("sh2.jpg", GeoPoint(31.225, 121.485), dateMs = 6000L),
            // One point in Beijing (far away -> distinct cluster)
            photo("bj1.jpg", GeoPoint(39.905, 116.405), dateMs = 4000L),
            // Remote ocean point > 50 km from any city -> coordinate fallback
            photo("ocean.jpg", GeoPoint(-20.0, -140.0), dateMs = 3000L),
            // No location & invalid (0,0) -> No location bucket
            photo("screenshot.png", null, dateMs = 2000L),
            photo("zero_gps.jpg", GeoPoint(0.0, 0.0), dateMs = 1000L),
        )

        val res = PlaceClusterer.cluster(items, gazetteer = gaz, preferChinese = true)
        assertEquals(3, res.clusters.size)
        assertEquals(2, res.noLocationItems.size)
        // 100% total reconciliation invariant
        assertEquals(items.size, res.clusters.sumOf { it.items.size } + res.noLocationItems.size)
        assertEquals(items.size, res.totalItemCount)

        val shCluster = res.clusters.first { it.labelEn == "Shanghai" }
        assertEquals(2, shCluster.items.size)
        assertEquals("上海", shCluster.displayLabel(preferChinese = true))
        assertFalse(shCluster.isCoordinateFallback)

        val oceanCluster = res.clusters.first { it.isCoordinateFallback }
        assertEquals("Near 20.0°S, 140.0°W", oceanCluster.labelEn)
    }

    @Test
    fun clustering_handlesAntimeridianAndPolarRegionsCorrectly() {
        // Two photos straddling the ±180° antimeridian (~2 km apart near Fiji)
        val antimeridianItems = listOf(
            photo("fiji_e.jpg", GeoPoint(-16.80, 179.99)),
            photo("fiji_w.jpg", GeoPoint(-16.80, -179.99)),
        )
        val resAnti = PlaceClusterer.cluster(antimeridianItems, gazetteer = Gazetteer.EMPTY)
        assertEquals("Antimeridian points should merge into 1 cluster", 1, resAnti.clusters.size)
        assertEquals(2, resAnti.clusters[0].items.size)
        assertTrue(kotlin.math.abs(resAnti.clusters[0].centroid.lon) > 179.0)

        // Two photos near the North Pole on opposite longitudes (~11 km apart)
        val polarItems = listOf(
            photo("pole1.jpg", GeoPoint(89.95, 10.0)),
            photo("pole2.jpg", GeoPoint(89.95, -170.0)),
        )
        val resPolar = PlaceClusterer.cluster(polarItems, gazetteer = Gazetteer.EMPTY)
        assertEquals("Polar points within 15km should merge into 1 cluster", 1, resPolar.clusters.size)
        assertEquals(2, resPolar.clusters[0].items.size)
    }
}
