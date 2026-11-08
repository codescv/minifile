package com.minifile.explorer.core.qr

import com.minifile.explorer.ui.util.QrCodeGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ReedSolomonTest {

    /** Builds a valid block (data + EC) using the existing generator as the oracle. */
    private fun block(rnd: Random, dataLen: Int, ecLen: Int): IntArray {
        val data = ByteArray(dataLen).also { rnd.nextBytes(it) }
        val ec = QrCodeGenerator.computeReedSolomonRemainder(data, QrCodeGenerator.computeReedSolomonDivisor(ecLen))
        return IntArray(dataLen + ecLen) { i ->
            if (i < dataLen) data[i].toInt() and 0xFF else ec[i - dataLen].toInt() and 0xFF
        }
    }

    private fun corrupt(rnd: Random, cw: IntArray, errors: Int): IntArray {
        val out = cw.copyOf()
        val positions = (cw.indices).shuffled(rnd).take(errors)
        for (p in positions) out[p] = out[p] xor (1 + rnd.nextInt(255))
        return out
    }

    // (data, ec) shapes used by QR versions 1–10.
    private val shapes = listOf(19 to 7, 16 to 10, 13 to 13, 9 to 17, 34 to 10, 22 to 22, 16 to 28, 43 to 26, 68 to 18, 15 to 28)

    @Test
    fun cleanBlockIsUntouched() {
        val rnd = Random(1)
        for ((d, e) in shapes) {
            val cw = block(rnd, d, e)
            val copy = cw.copyOf()
            assertTrue(ReedSolomon.correct(copy, e))
            assertArrayEquals(cw, copy)
        }
    }

    @Test
    fun correctsUpToTErrors() {
        val rnd = Random(2)
        for ((d, e) in shapes) {
            val t = e / 2
            repeat(20) {
                val cw = block(rnd, d, e)
                for (errors in 1..t) {
                    val bad = corrupt(rnd, cw, errors)
                    assertTrue("d=$d e=$e errors=$errors", ReedSolomon.correct(bad, e))
                    assertArrayEquals(cw, bad)
                }
            }
        }
    }

    @Test
    fun rejectsMoreThanTErrorsAndLeavesInputUntouched() {
        val rnd = Random(3)
        for ((d, e) in shapes) {
            val t = e / 2
            repeat(10) {
                val cw = block(rnd, d, e)
                for (errors in t + 1..minOf(t + 3, d + e)) {
                    val bad = corrupt(rnd, cw, errors)
                    val before = bad.copyOf()
                    assertFalse("d=$d e=$e errors=$errors", ReedSolomon.correct(bad, e))
                    assertArrayEquals(before, bad)
                }
            }
        }
    }

    @Test
    fun invalidArgumentsAreRejected() {
        assertFalse(ReedSolomon.correct(intArrayOf(1, 2, 3), 3))
        assertFalse(ReedSolomon.correct(intArrayOf(1, 2, 300, 4), 2))
        assertFalse(ReedSolomon.correct(IntArray(300), 10))
        assertTrue(ReedSolomon.correct(intArrayOf(1, 2, 3), 0))
    }
}
