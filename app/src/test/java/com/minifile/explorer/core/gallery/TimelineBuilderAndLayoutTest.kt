package com.minifile.explorer.core.gallery

import com.minifile.explorer.core.vfs.VfsPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class TimelineBuilderAndLayoutTest {

    private val nyZone = ZoneId.of("America/New_York")

    private fun epochAt(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0, zone: ZoneId = nyZone): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun item(
        name: String,
        kind: MediaKind = MediaKind.IMAGE,
        dateMs: Long,
        size: Long = 1000L,
        durMs: Long = 0L,
        bucket: String = "Camera",
    ): MediaItem = MediaItem(
        path = VfsPath.Local("/storage/emulated/0/DCIM/$bucket/$name"),
        name = name,
        kind = kind,
        mime = if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg",
        sizeBytes = size,
        bestDateMs = dateMs,
        modifiedMs = dateMs,
        durationMs = durMs,
        bucketDir = bucket,
    )

    @Test
    fun bestDateMs_followsFallbackChain() {
        assertEquals(1_700_000_000_000L, TimelineBuilder.bestDateMs(1_700_000_000_000L, 1_600_000_000L, 1_500_000_000_000L))
        assertEquals(1_600_000_000_000L, TimelineBuilder.bestDateMs(0L, 1_600_000_000L, 1_500_000_000_000L))
        assertEquals(1_600_000_000_000L, TimelineBuilder.bestDateMs(null, 1_600_000_000L, 1_500_000_000_000L))
        assertEquals(1_500_000_000_000L, TimelineBuilder.bestDateMs(null, 0L, 1_500_000_000_000L))
        assertEquals(0L, TimelineBuilder.bestDateMs(null, null, -5L))
    }

    @Test
    fun dayMonthYearGrouping_handlesTodayYesterdayLeapDayAndDst() {
        val now = epochAt(2024, 3, 11, 12, 0) // Day after US DST spring-forward (2024-03-10)
        val items = listOf(
            item("today.jpg", dateMs = epochAt(2024, 3, 11, 9, 0)),
            item("dst_yesterday.jpg", dateMs = epochAt(2024, 3, 10, 23, 30)),
            item("leap_day.jpg", dateMs = epochAt(2024, 2, 29, 18, 0)),
            item("prev_year.mp4", kind = MediaKind.VIDEO, dateMs = epochAt(2023, 12, 31, 23, 59), durMs = 15_000L),
        )

        val byDay = TimelineBuilder.build(items, groupBy = GalleryGroupBy.DAY, zoneId = nyZone, nowMs = now, locale = Locale.US)
        assertEquals(4, byDay.sections.size)
        assertEquals(SectionKind.TODAY, byDay.sections[0].kind)
        assertEquals(SectionKind.YESTERDAY, byDay.sections[1].kind)
        assertEquals("day:2024-02-29", byDay.sections[2].id)
        assertEquals("day:2023-12-31", byDay.sections[3].id)
        assertEquals(3, byDay.photoCount)
        assertEquals(1, byDay.videoCount)

        val byMonth = TimelineBuilder.build(items, groupBy = GalleryGroupBy.MONTH, zoneId = nyZone, nowMs = now)
        assertEquals(3, byMonth.sections.size)
        assertEquals("month:2024-03", byMonth.sections[0].id)
        assertEquals(2, byMonth.sections[0].items.size)

        val byYear = TimelineBuilder.build(items, groupBy = GalleryGroupBy.YEAR, zoneId = nyZone, nowMs = now)
        assertEquals(2, byYear.sections.size)
        assertEquals("year:2024", byYear.sections[0].id)
        assertEquals(3, byYear.sections[0].items.size)
        assertEquals("year:2023", byYear.sections[1].id)
    }

    @Test
    fun filterAndVideoSort_largestAndLongestWorkAccurately() {
        val t0 = epochAt(2026, 6, 1, 10, 0)
        val items = listOf(
            item("p1.jpg", MediaKind.IMAGE, t0 + 1000L, size = 500_000L),
            item("v_small_long.mp4", MediaKind.VIDEO, t0 + 2000L, size = 10_000_000L, durMs = 120_000L),
            item("v_huge_short.mp4", MediaKind.VIDEO, t0 + 3000L, size = 250_000_000L, durMs = 15_000L),
            item("v_mid.mp4", MediaKind.VIDEO, t0 + 4000L, size = 80_000_000L, durMs = 60_000L),
        )

        val photos = TimelineBuilder.build(items, filter = MediaFilter.PHOTOS, zoneId = nyZone, nowMs = t0)
        assertEquals(1, photos.flatItems.size)
        assertEquals(1, photos.photoCount)
        assertEquals(3, photos.videoCount)

        val largest = TimelineBuilder.build(items, filter = MediaFilter.VIDEOS, videoSort = VideoSort.LARGEST, zoneId = nyZone, nowMs = t0)
        assertEquals(listOf("v_huge_short.mp4", "v_mid.mp4", "v_small_long.mp4"), largest.flatItems.map { it.name })

        val longest = TimelineBuilder.build(items, filter = MediaFilter.VIDEOS, videoSort = VideoSort.LONGEST, zoneId = nyZone, nowMs = t0)
        assertEquals(listOf("v_small_long.mp4", "v_mid.mp4", "v_huge_short.mp4"), longest.flatItems.map { it.name })
    }

    @Test
    fun timelineLayout_exactRaggedRowOffsetsAndZeroDriftLookup() {
        // Section 0: 5 items, Section 1: 1 item, Section 2: 10 items
        val base = 1_700_000_000_000L
        val sections = listOf(
            TimelineSection("s0", base, SectionKind.DAY, (1..5).map { item("a$it.jpg", dateMs = base) }),
            TimelineSection("s1", base - 86_400_000L, SectionKind.DAY, listOf(item("b1.jpg", dateMs = base - 86_400_000L))),
            TimelineSection("s2", base - 172_800_000L, SectionKind.DAY, (1..10).map { item("c$it.jpg", dateMs = base - 172_800_000L) }),
        )

        for (cols in listOf(3, 4, 6)) {
            val headerH = 44f
            val cellH = 100f
            val gap = 2f
            val stride = cellH + gap
            val idx = TimelineLayout.compute(sections, cols, headerH, cellH, gap)

            // Verify exact cumulative Y_s formula
            val rows0 = (5 + cols - 1) / cols
            val rows1 = (1 + cols - 1) / cols
            val rows2 = (10 + cols - 1) / cols
            val expectedY1 = headerH + rows0 * stride
            val expectedY2 = expectedY1 + headerH + rows1 * stride
            val expectedTotal = expectedY2 + headerH + rows2 * stride

            assertEquals(0f, idx.sections[0].startY, 1e-3f)
            assertEquals(expectedY1, idx.sections[1].startY, 1e-3f)
            assertEquals(expectedY2, idx.sections[2].startY, 1e-3f)
            assertEquals(expectedTotal, idx.totalHeightPx, 1e-3f)

            // Lookup at boundaries 0.0 and 1.0
            val start = TimelineLayout.lookupFraction(idx, 0f)
            assertEquals(0, start.sectionIndex)
            assertEquals(0, start.flatIndex)

            val end = TimelineLayout.lookupFraction(idx, 1f)
            assertEquals(2, end.sectionIndex)
            assertTrue(end.flatIndex < idx.totalFlatItems)

            // Lookup midpoint of Section 1 hits Section 1 accurately
            val midSec1Frac = (expectedY1 + headerH + 10f) / expectedTotal
            val hit1 = TimelineLayout.lookupFraction(idx, midSec1Frac)
            assertEquals(1, hit1.sectionIndex)
            assertEquals(idx.sections[1].firstItemFlatIndex, hit1.flatIndex)

            // Inverse mapping round-trip
            val roundTripFrac = TimelineLayout.fractionForFlatIndex(idx, hit1.flatIndex, hit1.pixelOffsetPx, viewportHeightPx = 0f)
            assertEquals(midSec1Frac, roundTripFrac, 0.01f)
        }
    }
}
