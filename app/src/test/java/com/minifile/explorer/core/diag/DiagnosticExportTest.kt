package com.minifile.explorer.core.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class DiagnosticExportTest {

    private val env = DiagnosticEnvironment(
        appVersion = "1.0.23",
        versionCode = 10023,
        platform = "Android 15 (SDK 35)",
        device = "Google Pixel 8",
        interfaces = listOf("ap0 192.168.49.1/24 (hotspot-capable)", "wlan0 10.0.0.5/24"),
        discoverable = true,
        httpServer = "running :8080",
        ftpServer = "stopped",
        extra = listOf("zone" to "Asia/Shanghai"),
    )

    @Test
    fun statusLineCopy() {
        assertEquals("Off — enable to record transfer & network diagnostics", DiagnosticExport.statusLine(false, 0))
        assertEquals("Off · 18.4 KB saved", DiagnosticExport.statusLine(false, 18_842))
        assertEquals("Recording · 18.4 KB", DiagnosticExport.statusLine(true, 18_842))
        assertEquals("Recording · 0 B", DiagnosticExport.statusLine(true, 0))
        assertEquals("1.0 MB", DiagnosticExport.formatSize(1024L * 1024L))
        assertEquals("812 B", DiagnosticExport.formatSize(812))
    }

    @Test
    fun exportFileNameUsesLocalStamp() {
        // 2026-11-26T01:05:12Z
        assertEquals(
            "minifile-android-diag-20261126-090512.txt",
            DiagnosticExport.exportFileName(1_795_655_112_345L, ZoneOffset.ofHours(8)),
        )
    }

    @Test
    fun sessionBannerContainsContext() {
        val b = DiagnosticExport.sessionBanner(env)
        assertTrue(b.startsWith("diagnostic logging enabled · app=1.0.23 (10023) · os=Android 15 (SDK 35) · device=Google Pixel 8"))
        assertTrue(b.contains("discoverable=on"))
        assertTrue(b.contains("http=running :8080"))
        assertTrue(b.contains("ftp=stopped"))
        assertTrue(b.contains("ifaces=[ap0 192.168.49.1/24 (hotspot-capable), wlan0 10.0.0.5/24]"))
        assertTrue(DiagnosticExport.sessionBanner(env, "log cleared").startsWith("log cleared · app="))
    }

    @Test
    fun headerLinesAreCommentedAndEndWithSeparator() {
        val h = DiagnosticExport.header(env, 1_795_655_112_345L, ZoneOffset.ofHours(8), DiagnosticLogStats(totalBytes = 42))
        val lines = h.trimEnd().lines()
        assertTrue(lines.all { it.startsWith("# ") })
        assertEquals("# MiniFile diagnostic log", lines.first())
        assertTrue(lines.contains("# generated: 2026-11-26T09:05:12.345+08:00"))
        assertTrue(lines.contains("# log bytes: 42"))
        assertEquals("# ---", lines.last())
    }

    @Test
    fun composeWithoutCapReturnsEverything() {
        assertEquals("H\nbody\n", DiagnosticExport.compose("H\n", "body\n"))
        assertTrue(DiagnosticExport.compose("H\n", "").contains("no diagnostic log lines"))
    }

    @Test
    fun clipboardCapKeepsNewestWholeLinesAndNeverExceedsLimit() {
        val header = "# header\n# ---\n"
        val body = (0 until 20_000).joinToString("") { "2026-11-26T09:05:12.345+08:00 [NET] line %05d\n".format(it) }
        val max = DiagnosticExport.CLIPBOARD_MAX_CHARS
        assertTrue(header.length + body.length > max)
        val out = DiagnosticExport.compose(header, body, max)
        assertTrue("len=${out.length}", out.length <= max)
        assertTrue(out.startsWith(header + "# … truncated "))
        assertTrue(out.endsWith("line 19999\n"))
        // Every log line after the marker is complete.
        val logLines = out.removePrefix(header).lines().drop(1).filter { it.isNotEmpty() }
        assertTrue(logLines.all { Regex("""^\S+ \[NET] line \d{5}$""").matches(it) })
        // The marker reports exactly how many chars were dropped.
        val dropped = Regex("truncated (\\d+) earlier").find(out)!!.groupValues[1].toInt()
        assertEquals(body.length, dropped + out.substringAfter("earlier chars …\n").length)
        // Parcel-size safety: UTF-16 bytes stay well under the 1 MB Binder buffer.
        assertTrue(out.length * 2 <= 512 * 1024)
    }

    @Test
    fun capNeverSplitsSurrogatePairs() {
        val header = "#\n"
        val body = "😀".repeat(1000) // no newlines → mid-line cut
        val out = DiagnosticExport.compose(header, body, 200)
        assertTrue(out.length <= 200)
        val tail = out.substringAfter("earlier chars …\n")
        assertFalse(Character.isLowSurrogate(tail.first()))
    }

    @Test
    fun headerIsRedactedDefensively() {
        val leaky = env.copy(extra = listOf("note" to "WIFI:T:WPA;S:x;P:oops;;"))
        val h = DiagnosticExport.header(leaky, 0L, ZoneOffset.UTC, DiagnosticLogStats())
        assertFalse(h.contains("oops"))
    }
}
