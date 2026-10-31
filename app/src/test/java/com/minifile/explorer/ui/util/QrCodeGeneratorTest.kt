package com.minifile.explorer.ui.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeGeneratorTest {

    @Test
    fun capacitiesMatchIso18004Table7AcrossVersions1To10() {
        val expectedByteCapM = listOf(14, 26, 42, 62, 84, 106, 122, 152, 180, 213)
        val expectedByteCapL = listOf(17, 32, 53, 78, 106, 134, 154, 192, 230, 271)

        for (v in 1..10) {
            val headerBits = 4 + QrCodeGenerator.charCountBits(v)
            val actualM = (QrCodeGenerator.getNumDataCodewords(v, QrCodeGenerator.EccLevel.M) * 8 - headerBits) / 8
            val actualL = (QrCodeGenerator.getNumDataCodewords(v, QrCodeGenerator.EccLevel.L) * 8 - headerBits) / 8
            assertEquals("Version $v-M Byte capacity", expectedByteCapM[v - 1], actualM)
            assertEquals("Version $v-L Byte capacity", expectedByteCapL[v - 1], actualL)
        }
    }

    @Test
    fun encodesFtpUrlsUpTo130PlusCharsWithValidStructureSyndromesAndRoundTripDecode() {
        val samples = listOf(
            "",
            "ftp://127.0.0.1:2121/",
            "ftp://192.168.1.42:2121/",
            "ftp://minifile:s3cr3t%40pass@192.168.1.108:2121/",
            "ftp://user:VeryLongPassword_0123456789_ABCDEFGHIJKLMNOPQRSTUVWXYZ@100.64.12.34:2121/storage/emulated/0/Download/shared-files-2026/",
            "ftp://用户:密码@192.168.43.1:2121/",
            "A".repeat(130),
        )

        for (payload in samples) {
            val qr = QrCodeGenerator.encode(payload)
            assertEquals(17 + 4 * qr.version, qr.size)
            assertFinderAndDarkModuleInvariants(qr)
            val decoded = decodeMatrixAndVerifyReedSolomon(qr)
            assertEquals("Round-trip mismatch for payload length=${payload.length}", payload, decoded)
        }
    }

    @Test
    fun everyVersion1To6RoundTripsAtExactBoundaryLength() {
        val boundaryLengthsM = listOf(14, 26, 42, 62, 84, 106)
        for ((idx, len) in boundaryLengthsM.withIndex()) {
            val expectedVer = idx + 1
            val text = buildString(len) {
                for (i in 0 until len) append("0123456789abcdef"[i % 16])
            }
            val qr = QrCodeGenerator.encode(text, QrCodeGenerator.EccLevel.M)
            assertEquals(expectedVer, qr.version)
            assertEquals(QrCodeGenerator.EccLevel.M, qr.eccLevel)
            assertFinderAndDarkModuleInvariants(qr)
            assertEquals(text, decodeMatrixAndVerifyReedSolomon(qr))
        }

        // 132-byte payload fits in Version 6 when using EccLevel.L (cap = 134 bytes)
        val v6Long = "ftp://minifile:" + "x".repeat(98) + "@192.168.1.42:2121/"
        assertEquals(132, v6Long.length)
        val qrV6 = QrCodeGenerator.encode(v6Long, QrCodeGenerator.EccLevel.L)
        assertEquals(6, qrV6.version)
        assertEquals(41, qrV6.size)
        assertEquals(v6Long, decodeMatrixAndVerifyReedSolomon(qrV6))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedPayloadBeyondMaxSupportedVersion() {
        QrCodeGenerator.encode("X".repeat(300))
    }

    private fun assertFinderAndDarkModuleInvariants(qr: QrCodeGenerator.QrMatrix) {
        val n = qr.size
        // Top-left, top-right, bottom-left 7x7 finder centers must be dark (3,3), (n-4,3), (3,n-4)
        for ((cx, cy) in listOf(3 to 3, (n - 4) to 3, 3 to (n - 4))) {
            assertTrue(qr[cx, cy])
            // Outer 7x7 ring dark, inner 5x5 ring light, 3x3 center dark
            for (dy in -3..3) {
                for (dx in -3..3) {
                    val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    val expectedDark = dist != 2
                    assertEquals("Finder ($cx,$cy)+($dx,$dy)", expectedDark, qr[cx + dx, cy + dy])
                }
            }
        }
        // White separators immediately adjacent to top-left finder
        for (i in 0..7) {
            assertFalse("Top-left horizontal separator at ($i,7)", qr[i, 7])
            assertFalse("Top-left vertical separator at (7,$i)", qr[7, i])
        }
        // Dark module at (8, 4 * version + 9) == (8, n - 8)
        assertTrue("Dark module missing", qr[8, 4 * qr.version + 9])
    }

    /**
     * Independent ISO/IEC 18004 matrix extractor + BCH format verifier + Reed-Solomon syndrome
     * verifier + Byte-mode stream decoder.
     */
    private fun decodeMatrixAndVerifyReedSolomon(qr: QrCodeGenerator.QrMatrix): String {
        val n = qr.size
        val version = (n - 17) / 4

        // 1. Read both 15-bit Format Information copies from the matrix and verify they match & pass BCH
        var fmtCopy1 = 0
        for (i in 0..5) if (qr[8, i]) fmtCopy1 = fmtCopy1 or (1 shl i)
        if (qr[8, 7]) fmtCopy1 = fmtCopy1 or (1 shl 6)
        if (qr[8, 8]) fmtCopy1 = fmtCopy1 or (1 shl 7)
        if (qr[7, 8]) fmtCopy1 = fmtCopy1 or (1 shl 8)
        for (i in 9..14) if (qr[14 - i, 8]) fmtCopy1 = fmtCopy1 or (1 shl i)

        var fmtCopy2 = 0
        for (i in 0..7) if (qr[n - 1 - i, 8]) fmtCopy2 = fmtCopy2 or (1 shl i)
        for (i in 8..14) if (qr[8, n - 15 + i]) fmtCopy2 = fmtCopy2 or (1 shl i)

        assertEquals("Both Format Information copies must match", fmtCopy1, fmtCopy2)

        val unmaskedFmt = fmtCopy1 xor 0x5412
        // Verify BCH(15, 5) polynomial division remainder is 0
        var bchRem = unmaskedFmt
        for (bit in 14 downTo 10) {
            if (((bchRem ushr bit) and 1) != 0) {
                bchRem = bchRem xor (0x537 shl (bit - 10))
            }
        }
        assertEquals("Format BCH remainder must be 0", 0, bchRem)

        val data5 = unmaskedFmt ushr 10
        val ecBits = (data5 ushr 3) and 0b11
        val mask = data5 and 0b111
        assertEquals(qr.mask, mask)
        val ecc = QrCodeGenerator.EccLevel.entries.first { it.formatBits == ecBits }
        assertEquals(qr.eccLevel, ecc)

        // 2. Build function-module map independently to extract & unmask zigzag codeword bits
        val isFunc = Array(n) { BooleanArray(n) }
        for (i in 0 until n) {
            isFunc[i][6] = true
            isFunc[6][i] = true
        }
        for ((cx, cy) in listOf(3 to 3, (n - 4) to 3, 3 to (n - 4))) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val xx = cx + dx
                    val yy = cy + dy
                    if (xx in 0 until n && yy in 0 until n) isFunc[yy][xx] = true
                }
            }
        }
        val alignCoords = when (version) {
            1 -> intArrayOf()
            in 2..6 -> intArrayOf(6, 10 + 4 * version)
            7 -> intArrayOf(6, 22, 38)
            8 -> intArrayOf(6, 24, 42)
            9 -> intArrayOf(6, 26, 46)
            10 -> intArrayOf(6, 28, 50)
            else -> error("Unexpected version $version")
        }
        for (i in alignCoords.indices) {
            for (j in alignCoords.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == alignCoords.lastIndex) || (i == alignCoords.lastIndex && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) isFunc[alignCoords[j] + dy][alignCoords[i] + dx] = true
            }
        }
        for (i in 0..8) {
            isFunc[i][8] = true
            isFunc[8][i] = true
        }
        for (i in 0..7) isFunc[8][n - 1 - i] = true
        for (i in 0..7) isFunc[n - 1 - i][8] = true
        if (version >= 7) {
            for (i in 0 until 6) {
                for (j in 0 until 3) {
                    isFunc[i][n - 11 + j] = true
                    isFunc[n - 11 + j][i] = true
                }
            }
        }

        val rawBits = ArrayList<Int>()
        var right = n - 1
        var upward = true
        while (right >= 1) {
            if (right == 6) right = 5
            val ys = if (upward) (n - 1 downTo 0) else (0 until n)
            for (y in ys) {
                for (dx in 0..1) {
                    val x = right - dx
                    if (!isFunc[y][x]) {
                        val unmasked = qr[x, y] xor QrCodeGenerator.maskBit(mask, x, y)
                        rawBits.add(if (unmasked) 1 else 0)
                    }
                }
            }
            upward = !upward
            right -= 2
        }

        val totalCodewords = rawBits.size / 8
        val interleaved = ByteArray(totalCodewords)
        for (i in 0 until totalCodewords) {
            var b = 0
            for (k in 0..7) b = (b shl 1) or rawBits[i * 8 + k]
            interleaved[i] = b.toByte()
        }

        // 3. De-interleave blocks & verify ALL Reed-Solomon syndromes equal zero!
        val blocks = QrCodeGenerator.numBlocks(version, ecc)
        val ecLen = QrCodeGenerator.ecCodewordsPerBlock(version, ecc)
        val numShort = blocks - (totalCodewords % blocks)
        val shortDataLen = (totalCodewords / blocks) - ecLen

        val dataBlocks = Array(blocks) { b -> ByteArray(shortDataLen + if (b < numShort) 0 else 1) }
        val ecBlocks = Array(blocks) { ByteArray(ecLen) }
        var pos = 0
        val maxDataLen = shortDataLen + if (numShort < blocks) 1 else 0
        for (col in 0 until maxDataLen) {
            for (b in 0 until blocks) {
                if (col < dataBlocks[b].size) dataBlocks[b][col] = interleaved[pos++]
            }
        }
        for (col in 0 until ecLen) {
            for (b in 0 until blocks) {
                ecBlocks[b][col] = interleaved[pos++]
            }
        }
        assertEquals(totalCodewords, pos)

        val fullData = ArrayList<Byte>()
        for (b in 0 until blocks) {
            val cw = dataBlocks[b] + ecBlocks[b]
            // Evaluate C(alpha^r) for r = 0 until ecLen in GF(256) — every syndrome MUST be 0!
            var alphaR = 1
            for (r in 0 until ecLen) {
                var eval = 0
                for (byteVal in cw) {
                    eval = QrCodeGenerator.gfMultiply(eval, alphaR) xor (byteVal.toInt() and 0xFF)
                }
                assertEquals("Non-zero RS syndrome at block=$b root=$r", 0, eval)
                alphaR = QrCodeGenerator.gfMultiply(alphaR, 0x02)
            }
            // Also cross-check remainder directly
            val expectedEc = QrCodeGenerator.computeReedSolomonRemainder(
                dataBlocks[b],
                QrCodeGenerator.computeReedSolomonDivisor(ecLen),
            )
            assertArrayEquals(expectedEc, ecBlocks[b])
            for (d in dataBlocks[b]) fullData.add(d)
        }

        // 4. Parse Byte-mode bit stream
        val dataBits = IntArray(fullData.size * 8)
        for (i in fullData.indices) {
            val v = fullData[i].toInt() and 0xFF
            for (k in 0..7) dataBits[i * 8 + k] = (v ushr (7 - k)) and 1
        }
        var bp = 0
        fun readBits(count: Int): Int {
            var acc = 0
            repeat(count) { acc = (acc shl 1) or dataBits[bp++] }
            return acc
        }
        val mode = readBits(4)
        assertEquals("Expected Byte mode 0b0100", 0b0100, mode)
        val byteLen = readBits(QrCodeGenerator.charCountBits(version))
        val payloadBytes = ByteArray(byteLen)
        for (i in 0 until byteLen) {
            payloadBytes[i] = readBits(8).toByte()
        }
        return String(payloadBytes, Charsets.UTF_8)
    }
}
