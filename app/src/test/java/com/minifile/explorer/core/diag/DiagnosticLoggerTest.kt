package com.minifile.explorer.core.diag

import com.minifile.explorer.core.peer.HotspotLinkMessage
import com.minifile.explorer.core.peer.LinkMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DiagnosticLoggerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private val loggers = ArrayList<DiagnosticLogger>()

    // 2026-11-26T01:05:12.345Z
    private val fixedMs = 1_795_655_112_345L

    @Before
    fun setUp() {
        dir = File(tmp.root, "diag")
    }

    @After
    fun tearDown() {
        Diag.install(null)
        loggers.forEach { it.shutdown() }
    }

    private fun newLogger(maxBytes: Long = DiagnosticLogger.DEFAULT_MAX_BYTES_PER_FILE, zone: ZoneId = ZoneOffset.ofHours(8)) =
        DiagnosticLogger(dir, maxBytesPerFile = maxBytes, clock = { fixedMs }, zone = { zone }).also { loggers += it }

    @Test
    fun timestampIsIso8601WithMillisAndExplicitOffset() {
        assertEquals("2026-11-26T09:05:12.345+08:00", DiagnosticLogger.formatTimestamp(fixedMs, ZoneOffset.ofHours(8)))
        // UTC renders as +00:00, never `Z`, so both platforms parse/sort identically.
        assertEquals("2026-11-26T01:05:12.345+00:00", DiagnosticLogger.formatTimestamp(fixedMs, ZoneOffset.UTC))
        assertEquals("2026-11-25T20:05:12.345-05:00", DiagnosticLogger.formatTimestamp(fixedMs, ZoneId.of("America/New_York")))
        assertEquals("2026-11-26T06:35:12.345+05:30", DiagnosticLogger.formatTimestamp(fixedMs, ZoneId.of("Asia/Kolkata")))
        assertEquals("2026-11-26T01:05:12.005+00:00", DiagnosticLogger.formatTimestamp(fixedMs - 340, ZoneOffset.UTC))
    }

    @Test
    fun lineFormatIsSingleLineTaggedAndRedacted() {
        val line = DiagnosticLogger.formatLine(fixedMs, ZoneOffset.ofHours(8), "ble", "hotspot pass=abc tok=0123456789\nsecond")
        assertEquals("2026-11-26T09:05:12.345+08:00 [BLE] hotspot pass=<redacted> tok=0123…\\nsecond", line)
        val long = DiagnosticLogger.formatLine(fixedMs, ZoneOffset.UTC, "NET", "x".repeat(5000))
        assertTrue(long.contains("…(+1000 chars)"))
        assertFalse(long.contains("x".repeat(4001)))
    }

    @Test
    fun secretStraddlingTheLengthCapIsStillRedacted() {
        val msg = "x".repeat(DiagnosticLogger.MAX_MESSAGE_CHARS - 12) + "{\"pass\":\"TopSecretValue99\",\"tok\":\"0123456789\"}"
        val line = DiagnosticLogger.formatLine(fixedMs, ZoneOffset.UTC, "BLE", msg)
        assertFalse(line.contains("TopSecret"))
        assertFalse(line.contains("0123456789"))
        val kv = "y".repeat(DiagnosticLogger.MAX_MESSAGE_CHARS - 8) + " pass=TopSecretValue99 tail"
        assertFalse(DiagnosticLogger.formatLine(fixedMs, ZoneOffset.UTC, "BLE", kv).contains("TopSecret"))
    }

    @Test
    fun fullQueueNeverHangsFlushReadOrClear() {
        val l = DiagnosticLogger(dir, clock = { fixedMs }, zone = { ZoneOffset.UTC }, maxPendingLines = 16).also { loggers += it }
        l.flush()
        l.setEnabled(true)
        synchronized(l.ioLock) {
            // Writer is blocked on the lock: 16 lines queue up, the rest are dropped.
            repeat(100) { i -> l.log(Diag.NET, "burst $i") }
            val t0 = System.nanoTime()
            l.flush(timeoutMs = 300)
            assertTrue("flush must not hang on a rejected task", (System.nanoTime() - t0) / 1_000_000 < 2_000)
        }
        l.flush()
        val text = l.readAll()
        assertTrue(text.contains("[DIAG] dropped "))
        val written = text.lines().count { it.contains("[NET] burst") }
        assertTrue("written=$written", written in 16..17)

        // clear() runs on the caller even while the queue is full.
        synchronized(l.ioLock) {
            repeat(100) { i -> l.log(Diag.NET, "burst2 $i") }
            val t0 = System.nanoTime()
            l.clear()
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
            assertFalse(File(dir, DiagnosticLogger.FILE_NAME).exists())
        }
        l.flush()
        // At most the single line the writer had already dequeued survives the clear.
        assertTrue(l.readAll().lines().count { it.contains("burst2") } <= 1)
    }

    @Test
    fun offByDefaultWritesNothingAndSkipsMessageConstruction() {
        val l = newLogger()
        Diag.install(l)
        assertFalse(l.enabled)
        var built = 0
        repeat(100) { Diag.log(Diag.BLE) { built++; "should not appear" } }
        l.log(Diag.NET, "direct call also dropped")
        l.flush()
        assertEquals(0, built)
        assertEquals(0L, l.stats.value.totalBytes)
        assertFalse(File(dir, DiagnosticLogger.FILE_NAME).exists())
    }

    @Test
    fun notInstalledDiagIsNoOp() {
        Diag.install(null)
        var built = 0
        Diag.log(Diag.APP) { built++; "x" }
        assertEquals(0, built)
        assertFalse(Diag.isEnabled)
    }

    @Test
    fun enableRecordsSessionBannerAndPersistsAcrossInstances() {
        val l = newLogger()
        Diag.install(l)
        l.setEnabled(true) { "diagnostic logging enabled · app=1.0.23" }
        Diag.log(Diag.PEER) { "begin accepted session=abc" }
        l.flush()
        val text = l.readAll()
        val lines = text.trim().lines()
        assertEquals(2, lines.size)
        assertEquals("2026-11-26T09:05:12.345+08:00 [SESSION] diagnostic logging enabled · app=1.0.23", lines[0])
        assertEquals("2026-11-26T09:05:12.345+08:00 [PEER] begin accepted session=abc", lines[1])
        assertEquals(File(dir, DiagnosticLogger.FILE_NAME).length(), l.stats.value.totalBytes)

        // Toggling off keeps the file exportable; a new process (logger) sees the old size.
        l.setEnabled(false)
        l.flush()
        val size = l.stats.value.totalBytes
        assertTrue(l.readAll().trim().lines().last().endsWith("[SESSION] diagnostic logging disabled"))
        l.shutdown()
        val l2 = newLogger()
        l2.flush()
        assertEquals(size, l2.stats.value.totalBytes)
        assertFalse(l2.enabled)
        assertTrue(l2.readAll().contains("begin accepted session=abc"))
    }

    @Test
    fun secondEnableDoesNotRepeatBanner() {
        val l = newLogger()
        var banners = 0
        l.setEnabled(true) { banners++; "b" }
        l.setEnabled(true) { banners++; "b" }
        assertEquals(1, banners)
    }

    @Test
    fun rotatesIntoSingleRolloverAndStaysWithinCap() {
        val max = 4_096L
        val l = newLogger(maxBytes = max)
        l.setEnabled(true)
        repeat(400) { i -> l.log(Diag.NET, "line %04d ".format(i) + "y".repeat(40)) }
        l.flush()
        val cur = File(dir, DiagnosticLogger.FILE_NAME)
        val rolled = File(dir, DiagnosticLogger.ROLLED_FILE_NAME)
        assertTrue(rolled.exists())
        assertTrue(cur.length() <= max)
        assertTrue(rolled.length() <= max)
        assertEquals(cur.length() + rolled.length(), l.stats.value.totalBytes)
        assertTrue(l.stats.value.totalBytes <= 2 * max)
        assertFalse(File(dir, "diagnostic.log.2").exists())

        // Export is chronological: rolled (older) first, then current; newest line last; numbering increases.
        val nums = l.readAll().trim().lines().map { Regex("line (\\d{4})").find(it)!!.groupValues[1].toInt() }
        assertEquals(nums.sorted(), nums)
        assertEquals(399, nums.last())
        assertTrue(nums.first() > 0) // oldest lines were dropped by rotation
    }

    @Test
    fun clearRemovesFilesAndResetsSize() {
        val l = newLogger(maxBytes = 1024)
        l.setEnabled(true)
        repeat(100) { l.log(Diag.HTTP, "z".repeat(50)) }
        l.flush()
        assertTrue(l.stats.value.totalBytes > 0)
        l.clear()
        assertEquals(0L, l.stats.value.totalBytes)
        assertFalse(File(dir, DiagnosticLogger.FILE_NAME).exists())
        assertFalse(File(dir, DiagnosticLogger.ROLLED_FILE_NAME).exists())
        assertEquals("", l.readAll())
        // Still recording after clear.
        l.log(Diag.HTTP, "after clear")
        l.flush()
        assertTrue(l.readAll().contains("after clear"))
    }

    @Test
    fun concurrentWritersProduceIntactLines() {
        val l = newLogger()
        l.setEnabled(true)
        val threads = 8
        val perThread = 250
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i -> l.log(Diag.PEER, "t=$t i=$i payload=${"p".repeat(20)}") }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        l.flush()
        val lines = l.readAll().trim().lines()
        assertEquals(threads * perThread, lines.size)
        val pattern = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{2}:\d{2} \[PEER] t=\d i=\d+ payload=p{20}$""")
        assertTrue(lines.all { pattern.matches(it) })
        for (t in 0 until threads) {
            val mine = lines.filter { it.contains("t=$t ") }.map { it.substringAfter("i=").substringBefore(' ').toInt() }
            assertEquals((0 until perThread).toList(), mine) // per-thread FIFO order preserved
        }
    }

    @Test
    fun secretsNeverReachDisk() {
        val l = newLogger()
        l.setEnabled(true)
        l.log(Diag.HOTSPOT, "reservation started ssid=AndroidShare_9 sec=wpa2 pass=TopSecret99")
        l.log(Diag.BLE, DiagnosticDescribe.linkMessage(
            HotspotLinkMessage.Hotspot("AndroidShare_9", "TopSecret99", "wpa2", "192.168.49.1", 8080, "feedfacecafebeef"),
        ))
        l.log(Diag.PEER, "qr WIFI:T:WPA;S:AndroidShare_9;P:TopSecret99;;")
        l.flush()
        val raw = File(dir, DiagnosticLogger.FILE_NAME).readText()
        assertFalse(raw.contains("TopSecret99"))
        assertFalse(raw.contains("feedfacecafebeef"))
        assertTrue(raw.contains("tok=feed…"))
    }

    @Test
    fun describeLinkMessagesWithoutSecrets() {
        assertEquals("link mode=lohs-s", DiagnosticDescribe.linkMessage(HotspotLinkMessage.Link(LinkMode.LOHS_S)))
        assertEquals("ready ip=192.168.49.23 port=8080", DiagnosticDescribe.linkMessage(HotspotLinkMessage.Ready("192.168.49.23", 8080)))
        val hello = DiagnosticDescribe.linkMessage(
            HotspotLinkMessage.Hello("id-1234567890", "Pixel", "android", "1.0.23", 2, 10L, "0123456789abcdef", listOf("10.0.0.2")),
        )
        assertTrue(hello.startsWith("hello id=id-12345 name=Pixel"))
        assertTrue(hello.contains("tok=0123…"))
        assertFalse(hello.contains("0123456789abcdef"))
    }
}
