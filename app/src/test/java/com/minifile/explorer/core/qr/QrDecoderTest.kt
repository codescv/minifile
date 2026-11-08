package com.minifile.explorer.core.qr

import com.minifile.explorer.ui.util.QrCodeGenerator
import com.minifile.explorer.ui.util.QrCodeGenerator.EccLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class QrDecoderTest {

    // Max byte-mode payload per version (index = version) for ECC L and M.
    private val capL = intArrayOf(0, 17, 32, 53, 78, 106, 134, 154, 192, 230, 271)
    private val capM = intArrayOf(0, 14, 26, 42, 62, 84, 106, 122, 152, 180, 213)

    private class Image(val lum: ByteArray, val w: Int, val h: Int)

    private fun rotate90(g: Array<BooleanArray>): Array<BooleanArray> {
        val n = g.size
        return Array(n) { y -> BooleanArray(n) { x -> g[n - 1 - x][y] } }
    }

    private fun rotated(g: Array<BooleanArray>, quarterTurns: Int): Array<BooleanArray> {
        var r = g
        repeat(quarterTurns and 3) { r = rotate90(r) }
        return r
    }

    /**
     * Renders [grid] with a 4-module quiet zone at [px] pixels per module, a ±20 % diagonal
     * brightness gradient and Gaussian noise. With [perspective] the image is inverse-mapped
     * through a mild projective warp (2×2 supersampled).
     */
    private fun render(
        grid: Array<BooleanArray>,
        px: Int,
        seed: Long,
        noise: Double = 8.0,
        perspective: Boolean = false,
        invertColors: Boolean = false,
        canvasW: Int = 0,
        canvasH: Int = 0,
    ): Image {
        val size = grid.size
        val n = size + 8
        val codePx = n * px
        val w = maxOf(codePx, canvasW)
        val h = maxOf(codePx, canvasH)
        val offX = (w - codePx) / 2
        val offY = (h - codePx) / 2
        val rnd = Random(seed)
        val lum = ByteArray(w * h)

        fun darkAtModule(u: Double, v: Double): Boolean {
            val mx = Math.floor(u).toInt() - 4
            val my = Math.floor(v).toInt() - 4
            return mx in 0 until size && my in 0 until size && grid[my][mx]
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val darkness: Double = if (!perspective) {
                    val u = (x - offX).toDouble() / px
                    val v = (y - offY).toDouble() / px
                    if (darkAtModule(u, v)) 1.0 else 0.0
                } else {
                    var acc = 0.0
                    for (sy in 0..1) for (sx in 0..1) {
                        val s = (x - offX + 0.25 + 0.5 * sx) / codePx
                        val t = (y - offY + 0.25 + 0.5 * sy) / codePx
                        val wDen = 1.0 + 0.10 * (s - 0.5) - 0.08 * (t - 0.5)
                        val zoom = 1.15
                        val uu = ((s - 0.5 + 0.03 * (t - 0.5)) * zoom / wDen + 0.5) * n
                        val vv = ((t - 0.5) * zoom / wDen + 0.5) * n
                        if (darkAtModule(uu, vv)) acc += 0.25
                    }
                    acc
                }
                val dark = if (invertColors) 1.0 - darkness else darkness
                val base = 210.0 - dark * (210.0 - 45.0)
                val gradient = 0.8 + 0.4 * (x + y).toDouble() / (w + h)
                val value = base * gradient + rnd.nextGaussian() * noise
                lum[y * w + x] = value.toInt().coerceIn(0, 255).toByte()
            }
        }
        return Image(lum, w, h)
    }

    private fun payloadOfLength(len: Int, seed: Int): String {
        val prefix = "https://example.com/s/$seed?q="
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        val sb = StringBuilder(prefix)
        var i = seed
        while (sb.length < len) {
            sb.append(alphabet[(i * 31 + 7) % alphabet.length])
            i++
        }
        return sb.toString().take(len)
    }

    private fun roundTrip(
        text: String,
        ecc: EccLevel,
        px: Int,
        rot: Int = 0,
        perspective: Boolean = false,
        seed: Long = 1L,
    ): String? {
        val m = QrCodeGenerator.encode(text, ecc)
        val img = render(rotated(m.modules, rot), px, seed, perspective = perspective)
        return QrDecoder.decode(img.lum, img.w, img.h)
    }

    @Test
    fun allVersionsBothEccLevelsRoundTrip() {
        for (v in 1..10) {
            for (ecc in listOf(EccLevel.L, EccLevel.M)) {
                val len = if (ecc == EccLevel.L) capL[v] else capM[v]
                val text = payloadOfLength(len, v)
                val m = QrCodeGenerator.encode(text, ecc)
                assertEquals("generator version for v$v $ecc", v, m.version)
                assertEquals(ecc, m.eccLevel)
                val px = 3 + (v + ecc.ordinal) % 6 // 3..8 px per module
                val img = render(m.modules, px, seed = v * 10L + ecc.ordinal)
                assertEquals("v$v $ecc px=$px", text, QrDecoder.decode(img.lum, img.w, img.h))
            }
        }
    }

    @Test
    fun cleanGridsDecodeForAllVersions() {
        for (v in 1..10) {
            for (ecc in listOf(EccLevel.L, EccLevel.M)) {
                val text = payloadOfLength(if (ecc == EccLevel.L) capL[v] else capM[v], v + 100)
                val m = QrCodeGenerator.encode(text, ecc)
                assertEquals(text, QrDecoder.decodeGrid(m.modules))
            }
        }
    }

    @Test
    fun rotations90_180_270() {
        val texts = listOf(
            "WIFI:T:WPA;S:AndroidShare_1234;P:k2Jd9xQ1;;",
            payloadOfLength(capM[7], 7),
            payloadOfLength(capL[4], 4),
        )
        for ((i, t) in texts.withIndex()) {
            for (rot in 1..3) {
                val ecc = if (i == 2) EccLevel.L else EccLevel.M
                assertEquals("text#$i rot=${rot * 90}", t, roundTrip(t, ecc, px = 4 + i, rot = rot, seed = 40L + rot))
            }
        }
    }

    @Test
    fun mildPerspectiveWarp() {
        for ((v, px) in listOf(1 to 6, 3 to 6, 6 to 6, 9 to 7)) {
            val text = payloadOfLength(capM[v], v + 50)
            for (rot in listOf(0, 1)) {
                assertEquals("v$v perspective rot=$rot", text, roundTrip(text, EccLevel.M, px, rot = rot, perspective = true, seed = v.toLong()))
            }
        }
    }

    @Test
    fun wifiPayloadsRoundTrip() {
        val cases = listOf(
            QrPayloads.buildWifi("AndroidShare_7781", "k2Jd9xQ1", "wpa2"),
            QrPayloads.buildWifi("Android;Share,1234:x", "p:a\\s;s,\"q\"", "wpa2"),
            QrPayloads.buildWifi("我的热点-文件传输", "密码12345678", "wpa3"),
            QrPayloads.buildWifi(
                "A very long hotspot SSID 32 chr",
                "a-very-long-passphrase-that-is-63-characters-long-0123456789abc",
                "wpa2",
            ),
            QrPayloads.buildWifi("OpenNet", "", "nopass"),
        )
        for ((i, text) in cases.withIndex()) {
            val decoded = roundTrip(text, EccLevel.M, px = 3 + i, seed = 70L + i)
            assertEquals(text, decoded)
            assertTrue(QrPayloads.parse(decoded!!) is QrPayload.Wifi)
        }
        val chinese = QrPayloads.parse(roundTrip(cases[2], EccLevel.M, px = 5)!!) as QrPayload.Wifi
        assertEquals("我的热点-文件传输", chinese.ssid)
        assertEquals("wpa3", chinese.sec)
        val escaped = QrPayloads.parse(roundTrip(cases[1], EccLevel.M, px = 5)!!) as QrPayload.Wifi
        assertEquals("Android;Share,1234:x", escaped.ssid)
        assertEquals("p:a\\s;s,\"q\"", escaped.password)
    }

    @Test
    fun minifileUrisAndLongUrlRoundTrip() {
        val hotspot = "minifile://hotspot?v=1&s=AndroidShare_7781&p=k2Jd9xQ1&sec=wpa2&ip=192.168.197.1" +
            "&port=8080&id=ab12cd&n=Pixel%208&tok=0123456789abcdef0123456789abcdef"
        val peer = QrPayloads.buildPeerUri(QrPayload.Peer("172.20.10.1", 8080, "ab12cd", "iPhone 15"))
        val url200 = payloadOfLength(200, 200)
        assertEquals(200, url200.length)
        assertEquals(hotspot, roundTrip(hotspot, EccLevel.M, px = 4, seed = 3))
        assertEquals(peer, roundTrip(peer, EccLevel.M, px = 5, seed = 4))
        assertEquals(url200, roundTrip(url200, EccLevel.M, px = 3, seed = 5))
        assertEquals(url200, roundTrip(url200, EccLevel.L, px = 6, rot = 2, seed = 6))
    }

    @Test
    fun invertedImageDecodes() {
        val text = "http://192.168.1.20:8080/"
        val m = QrCodeGenerator.encode(text, EccLevel.M)
        val img = render(m.modules, 5, seed = 9, invertColors = true)
        assertEquals(text, QrDecoder.decode(img.lum, img.w, img.h))
    }

    @Test
    fun largeCameraFrameIsDownscaled() {
        val text = payloadOfLength(capM[4], 44)
        val m = QrCodeGenerator.encode(text, EccLevel.M)
        val img = render(m.modules, 12, seed = 11, canvasW = 1280, canvasH = 960)
        assertEquals(1280, img.w)
        assertEquals(text, QrDecoder.decode(img.lum, img.w, img.h))
    }

    @Test
    fun reedSolomonCorrectsFlippedDataModules() {
        val rnd = Random(1234)
        for (v in 1..10) {
            for (ecc in listOf(EccLevel.L, EccLevel.M)) {
                val text = payloadOfLength(if (ecc == EccLevel.L) capL[v] else capM[v], v + 300)
                val m = QrCodeGenerator.encode(text, ecc)
                val func = QrDecoder.functionPatternMask(v)
                val size = m.size
                val grid = Array(size) { m.modules[it].clone() }
                // ≤ 3 flipped modules ⇒ ≤ 3 bad codewords per block ≤ t (min t = 3 at v1-L).
                var flipped = 0
                while (flipped < 3) {
                    val x = rnd.nextInt(size)
                    val y = rnd.nextInt(size)
                    if (func[y][x]) continue
                    grid[y][x] = !grid[y][x]
                    flipped++
                }
                // Also damage one full format-info copy (the other copy still decodes).
                for (i in 0..7) grid[8][size - 1 - i] = !grid[8][size - 1 - i]
                assertEquals("v$v $ecc", text, QrDecoder.decodeGrid(grid))
            }
        }
    }

    @Test
    fun heavilyDamagedGridIsRejected() {
        val text = payloadOfLength(capM[5], 5)
        val m = QrCodeGenerator.encode(text, EccLevel.M)
        val func = QrDecoder.functionPatternMask(5)
        val grid = Array(m.size) { m.modules[it].clone() }
        val rnd = Random(77)
        for (y in 0 until m.size) for (x in 0 until m.size) {
            if (!func[y][x] && rnd.nextInt(3) == 0) grid[y][x] = !grid[y][x]
        }
        val r = QrDecoder.decodeGrid(grid)
        assertNotEquals(text, r)
        assertNull(r)
    }

    @Test
    fun garbageInputNeverThrows() {
        val rnd = Random(5)
        val noise = ByteArray(320 * 240).also { rnd.nextBytes(it) }
        assertNull(QrDecoder.decode(noise, 320, 240))
        assertNull(QrDecoder.decode(ByteArray(640 * 480) { 0x7F }, 640, 480))
        assertNull(QrDecoder.decode(ByteArray(10), 320, 240))
        assertNull(QrDecoder.decode(ByteArray(0), 0, 0))
        assertNull(QrDecoder.decode(ByteArray(100), -10, -10))
        assertNull(QrDecoder.decodeGrid(arrayOf()))
        assertNull(QrDecoder.decodeGrid(Array(22) { BooleanArray(22) }))
        assertNull(QrDecoder.decodeGrid(Array(21) { BooleanArray(20) }))
        assertNull(QrDecoder.decodeGrid(Array(21) { BooleanArray(21) }))
        assertNull(QrDecoder.decodeGrid(Array(101) { BooleanArray(101) }))
    }

    // ---------------------------------------------------------------- segment parsing

    private class BitWriter {
        private val bits = ArrayList<Boolean>()
        fun put(value: Int, n: Int): BitWriter {
            for (i in n - 1 downTo 0) bits += (value ushr i) and 1 == 1
            return this
        }
        fun toCodewords(total: Int): IntArray {
            while (bits.size % 8 != 0) bits += false
            val out = IntArray(total)
            for (i in 0 until bits.size / 8) {
                var b = 0
                for (k in 0 until 8) b = (b shl 1) or if (bits[i * 8 + k]) 1 else 0
                out[i] = b
            }
            var pad = true
            for (i in bits.size / 8 until total) {
                out[i] = if (pad) 0xEC else 0x11
                pad = !pad
            }
            return out
        }
    }

    @Test
    fun numericSegment() {
        // "01234567": 012 → 12, 345 → 345 (10 bits each), 67 → 67 (7 bits).
        val cw = BitWriter().put(0b0001, 4).put(8, 10).put(12, 10).put(345, 10).put(67, 7).put(0, 4)
            .toCodewords(16)
        assertEquals("01234567", QrDecoder.decodeSegments(cw, 1))
        // Single trailing digit + v10 count width (12 bits).
        val cw10 = BitWriter().put(0b0001, 4).put(4, 12).put(987, 10).put(6, 4).put(0, 4).toCodewords(20)
        assertEquals("9876", QrDecoder.decodeSegments(cw10, 10))
    }

    @Test
    fun alphanumericSegment() {
        // "AC-42": AC = 10*45+12, "-4" = 41*45+4, "2" = 2.
        val cw = BitWriter().put(0b0010, 4).put(5, 9).put(10 * 45 + 12, 11).put(41 * 45 + 4, 11).put(2, 6)
            .put(0, 4).toCodewords(16)
        assertEquals("AC-42", QrDecoder.decodeSegments(cw, 1))
    }

    @Test
    fun mixedSegmentsWithEci() {
        val utf8 = "é中".toByteArray(Charsets.UTF_8)
        val w = BitWriter()
            .put(0b0010, 4).put(4, 9).put(17 * 45 + 29, 11).put(29 * 45 + 25, 11) // "HTTP" → "HT","TP"
            .put(0b0001, 4).put(3, 10).put(123, 10) // "123"
            .put(0b0111, 4).put(26, 8) // ECI 26 = UTF-8
            .put(0b0100, 4).put(utf8.size, 8)
        for (b in utf8) w.put(b.toInt() and 0xFF, 8)
        w.put(0, 4)
        assertEquals("HTTP123é中", QrDecoder.decodeSegments(w.toCodewords(28), 2))
    }

    @Test
    fun byteSegmentFallsBackToLatin1() {
        val w = BitWriter().put(0b0100, 4).put(3, 8).put(0x41, 8).put(0xE9, 8).put(0x42, 8).put(0, 4)
        assertEquals("AéB", QrDecoder.decodeSegments(w.toCodewords(16), 1))
    }

    @Test
    fun kanjiAndTruncatedSegmentsReturnNull() {
        assertNull(QrDecoder.decodeSegments(BitWriter().put(0b1000, 4).put(1, 8).put(0, 13).toCodewords(16), 1))
        // Byte segment claiming more bytes than available.
        assertNull(QrDecoder.decodeSegments(BitWriter().put(0b0100, 4).put(200, 8).toCodewords(4), 1))
    }

    @Test
    fun versionInfoDecodesForV7ToV10() {
        for (v in 7..10) {
            val m = QrCodeGenerator.encode(payloadOfLength(capM[v], v), EccLevel.M)
            assertEquals(v, QrDecoder.readVersionInfo(m.modules))
        }
    }
}
