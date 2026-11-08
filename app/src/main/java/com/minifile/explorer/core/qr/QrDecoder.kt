package com.minifile.explorer.core.qr

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure-JVM, dependency-free QR Code (Model 2) decoder for versions 1–10, all ECC levels.
 *
 * Pipeline: luminance → (downscale) → hybrid block binarizer → 1:1:3:1:1 finder detection
 * → module size / dimension estimate → alignment pattern (v ≥ 2) → perspective sampling
 * → format/version info → unmask → codeword read → de-interleave → Reed–Solomon → segments.
 */
object QrDecoder {
    private const val MAX_IMAGE_DIM = 640
    private const val MIN_VERSION = 1
    private const val MAX_VERSION = 10

    // ---------------------------------------------------------------- tables (ISO/IEC 18004)

    /** Total codewords per version (index 0 unused). */
    private val TOTAL_CODEWORDS = intArrayOf(0, 26, 44, 70, 100, 134, 172, 196, 242, 292, 346)

    /** EC codewords per block, [eccIndex L,M,Q,H][version]. */
    private val EC_PER_BLOCK = arrayOf(
        intArrayOf(0, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18),
        intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26),
        intArrayOf(0, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24),
        intArrayOf(0, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28),
    )

    /** Number of RS blocks, [eccIndex L,M,Q,H][version]. */
    private val NUM_BLOCKS = arrayOf(
        intArrayOf(0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4),
        intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5),
        intArrayOf(0, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8),
        intArrayOf(0, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8),
    )

    private val ALIGNMENT_COORDS: Array<IntArray> = arrayOf(
        intArrayOf(),
        intArrayOf(),
        intArrayOf(6, 18),
        intArrayOf(6, 22),
        intArrayOf(6, 26),
        intArrayOf(6, 30),
        intArrayOf(6, 34),
        intArrayOf(6, 22, 38),
        intArrayOf(6, 24, 42),
        intArrayOf(6, 26, 46),
        intArrayOf(6, 28, 50),
    )

    private const val ALNUM_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

    /** All 32 valid (masked) 15-bit format words, indexed by 5-bit data (ecBits<<3 | mask). */
    private val FORMAT_WORDS = IntArray(32) { data ->
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        ((data shl 10) or rem) xor 0x5412
    }

    /** 18-bit version words for versions 7..40 (index = version). */
    private val VERSION_WORDS = IntArray(41) { v ->
        if (v < 7) 0 else {
            var rem = v
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            (v shl 12) or rem
        }
    }

    // ---------------------------------------------------------------- public API

    /**
     * Decodes a QR code from a tight `width × height` 8-bit luminance buffer.
     * Returns null when nothing decodable is found; never throws.
     */
    fun decode(luminance: ByteArray, width: Int, height: Int): String? {
        return try {
            if (width < 21 || height < 21) return null
            if (luminance.size.toLong() < width.toLong() * height.toLong()) return null
            val scaled = downscale(luminance, width, height)
            val lum = scaled.first
            val w = scaled.second
            val h = scaled.third
            decodeLuminance(lum, w, h) ?: decodeLuminance(invert(lum), w, h)
        } catch (e: Exception) {
            null
        } catch (e: StackOverflowError) {
            null
        }
    }

    /**
     * Decodes an already-sampled square module grid (`modules[y][x]`, true = dark) in its
     * canonical orientation (finders at top-left, top-right, bottom-left). Never throws.
     */
    fun decodeGrid(modules: Array<BooleanArray>): String? {
        return try {
            decodeGridInternal(modules)
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- image pre-processing

    private fun downscale(lum: ByteArray, w: Int, h: Int): Triple<ByteArray, Int, Int> {
        val f = ceil(max(w, h).toDouble() / MAX_IMAGE_DIM).toInt()
        if (f <= 1) {
            return Triple(if (lum.size == w * h) lum else lum.copyOf(w * h), w, h)
        }
        val nw = w / f
        val nh = h / f
        val out = ByteArray(nw * nh)
        val area = f * f
        for (y in 0 until nh) {
            for (x in 0 until nw) {
                var sum = 0
                val by = y * f
                val bx = x * f
                for (yy in 0 until f) {
                    val row = (by + yy) * w + bx
                    for (xx in 0 until f) sum += lum[row + xx].toInt() and 0xFF
                }
                out[y * nw + x] = (sum / area).toByte()
            }
        }
        return Triple(out, nw, nh)
    }

    private fun invert(lum: ByteArray): ByteArray =
        ByteArray(lum.size) { (255 - (lum[it].toInt() and 0xFF)).toByte() }

    /** zxing-style hybrid binarizer (8×8 blocks, 5×5 block neighbourhood average). */
    internal fun binarize(lum: ByteArray, w: Int, h: Int): QrBitMatrix {
        if (w < 40 || h < 40) return globalBinarize(lum, w, h)
        val subW = (w + 7) shr 3
        val subH = (h + 7) shr 3
        val black = Array(subH) { IntArray(subW) }
        for (y in 0 until subH) {
            val yOff = min(y shl 3, h - 8)
            for (x in 0 until subW) {
                val xOff = min(x shl 3, w - 8)
                var sum = 0
                var mn = 255
                var mx = 0
                for (yy in 0 until 8) {
                    val row = (yOff + yy) * w + xOff
                    for (xx in 0 until 8) {
                        val p = lum[row + xx].toInt() and 0xFF
                        sum += p
                        if (p < mn) mn = p
                        if (p > mx) mx = p
                    }
                }
                var avg = sum shr 6
                if (mx - mn <= 24) {
                    avg = mn / 2
                    if (y > 0 && x > 0) {
                        val nb = (black[y - 1][x] + 2 * black[y][x - 1] + black[y - 1][x - 1]) / 4
                        if (mn < nb) avg = nb
                    }
                }
                black[y][x] = avg
            }
        }
        val bm = QrBitMatrix(w, h)
        for (y in 0 until subH) {
            val yOff = min(y shl 3, h - 8)
            val top = y.coerceIn(2, subH - 3)
            for (x in 0 until subW) {
                val xOff = min(x shl 3, w - 8)
                val left = x.coerceIn(2, subW - 3)
                var sum = 0
                for (dy in -2..2) {
                    val r = black[top + dy]
                    for (dx in -2..2) sum += r[left + dx]
                }
                val threshold = sum / 25
                for (yy in 0 until 8) {
                    val row = (yOff + yy) * w + xOff
                    for (xx in 0 until 8) {
                        if ((lum[row + xx].toInt() and 0xFF) <= threshold) bm[xOff + xx, yOff + yy] = true
                    }
                }
            }
        }
        return bm
    }

    /** Otsu global threshold for tiny images. */
    private fun globalBinarize(lum: ByteArray, w: Int, h: Int): QrBitMatrix {
        val hist = IntArray(256)
        val n = w * h
        for (i in 0 until n) hist[lum[i].toInt() and 0xFF]++
        var sumAll = 0.0
        for (i in 0 until 256) sumAll += i.toDouble() * hist[i]
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var threshold = 127
        for (t in 0 until 256) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = n - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = t
            }
        }
        val bm = QrBitMatrix(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            if ((lum[y * w + x].toInt() and 0xFF) <= threshold) bm[x, y] = true
        }
        return bm
    }

    // ---------------------------------------------------------------- detection

    private class Finder(var x: Float, var y: Float, var ms: Float, var count: Int)

    private class Pt(val x: Float, val y: Float)

    private fun decodeLuminance(lum: ByteArray, w: Int, h: Int): String? {
        val bm = binarize(lum, w, h)
        val finders = FinderScanner(bm).scan()
        for (triple in bestTriples(finders)) {
            val ordered = order(triple) ?: continue
            val r = detectAndDecode(bm, ordered[0], ordered[1], ordered[2])
            if (r != null) return r
        }
        return null
    }

    private class FinderScanner(private val bm: QrBitMatrix) {
        val centers = ArrayList<Finder>()
        private val w = bm.width
        private val h = bm.height

        fun scan(): List<Finder> {
            val sc = IntArray(5)
            for (i in 0 until h) {
                sc.fill(0)
                var state = 0
                var j = 0
                while (j < w) {
                    if (bm[j, i]) {
                        if (state and 1 == 1) state++
                        sc[state]++
                    } else {
                        if (state and 1 == 0) {
                            if (state == 4) {
                                if (foundPatternCross(sc) && handlePossibleCenter(sc, i, j)) {
                                    sc.fill(0)
                                    state = 0
                                } else {
                                    shiftTwo(sc)
                                    state = 3
                                }
                            } else {
                                state++
                                sc[state]++
                            }
                        } else {
                            sc[state]++
                        }
                    }
                    j++
                }
                if (foundPatternCross(sc)) handlePossibleCenter(sc, i, w)
            }
            return centers
        }

        private fun shiftTwo(sc: IntArray) {
            sc[0] = sc[2]
            sc[1] = sc[3]
            sc[2] = sc[4]
            sc[3] = 1
            sc[4] = 0
        }

        private fun centerFromEnd(sc: IntArray, end: Int): Float =
            (end - sc[4] - sc[3]).toFloat() - sc[2] / 2f

        private fun handlePossibleCenter(sc: IntArray, i: Int, j: Int): Boolean {
            val total = sc.sum()
            var centerJ = centerFromEnd(sc, j)
            val centerI = crossCheckVertical(i, centerJ.toInt(), sc[2], total)
            if (centerI.isNaN()) return false
            centerJ = crossCheckHorizontal(centerJ.toInt(), centerI.toInt(), sc[2], total)
            if (centerJ.isNaN()) return false
            if (!crossCheckDiagonal(centerI.toInt(), centerJ.toInt())) return false
            val ms = total / 7f
            for (c in centers) {
                if (abs(centerI - c.y) <= ms && abs(centerJ - c.x) <= ms) {
                    val d = abs(ms - c.ms)
                    if (d <= 1f || d <= c.ms) {
                        val n = c.count + 1
                        c.x = (c.count * c.x + centerJ) / n
                        c.y = (c.count * c.y + centerI) / n
                        c.ms = (c.count * c.ms + ms) / n
                        c.count = n
                        return true
                    }
                }
            }
            centers.add(Finder(centerJ, centerI, ms, 1))
            return true
        }

        private fun crossCheckVertical(startI: Int, cj: Int, maxCount: Int, originalTotal: Int): Float {
            if (cj !in 0 until w) return Float.NaN
            val sc = IntArray(5)
            var i = startI
            while (i >= 0 && bm[cj, i]) { sc[2]++; i-- }
            if (i < 0) return Float.NaN
            while (i >= 0 && !bm[cj, i] && sc[1] <= maxCount) { sc[1]++; i-- }
            if (i < 0 || sc[1] > maxCount) return Float.NaN
            while (i >= 0 && bm[cj, i] && sc[0] <= maxCount) { sc[0]++; i-- }
            if (sc[0] > maxCount) return Float.NaN
            i = startI + 1
            while (i < h && bm[cj, i]) { sc[2]++; i++ }
            if (i == h) return Float.NaN
            while (i < h && !bm[cj, i] && sc[3] < maxCount) { sc[3]++; i++ }
            if (i == h || sc[3] >= maxCount) return Float.NaN
            while (i < h && bm[cj, i] && sc[4] < maxCount) { sc[4]++; i++ }
            if (sc[4] >= maxCount) return Float.NaN
            val total = sc.sum()
            if (5 * abs(total - originalTotal) >= 2 * originalTotal) return Float.NaN
            return if (foundPatternCross(sc)) centerFromEnd(sc, i) else Float.NaN
        }

        private fun crossCheckHorizontal(startJ: Int, ci: Int, maxCount: Int, originalTotal: Int): Float {
            if (ci !in 0 until h) return Float.NaN
            val sc = IntArray(5)
            var j = startJ
            while (j >= 0 && bm[j, ci]) { sc[2]++; j-- }
            if (j < 0) return Float.NaN
            while (j >= 0 && !bm[j, ci] && sc[1] <= maxCount) { sc[1]++; j-- }
            if (j < 0 || sc[1] > maxCount) return Float.NaN
            while (j >= 0 && bm[j, ci] && sc[0] <= maxCount) { sc[0]++; j-- }
            if (sc[0] > maxCount) return Float.NaN
            j = startJ + 1
            while (j < w && bm[j, ci]) { sc[2]++; j++ }
            if (j == w) return Float.NaN
            while (j < w && !bm[j, ci] && sc[3] < maxCount) { sc[3]++; j++ }
            if (j == w || sc[3] >= maxCount) return Float.NaN
            while (j < w && bm[j, ci] && sc[4] < maxCount) { sc[4]++; j++ }
            if (sc[4] >= maxCount) return Float.NaN
            val total = sc.sum()
            if (5 * abs(total - originalTotal) >= originalTotal) return Float.NaN
            return if (foundPatternCross(sc)) centerFromEnd(sc, j) else Float.NaN
        }

        private fun crossCheckDiagonal(ci: Int, cj: Int): Boolean {
            val sc = IntArray(5)
            var i = 0
            while (ci >= i && cj >= i && bm[cj - i, ci - i]) { sc[2]++; i++ }
            if (sc[2] == 0) return false
            while (ci >= i && cj >= i && !bm[cj - i, ci - i]) { sc[1]++; i++ }
            if (sc[1] == 0) return false
            while (ci >= i && cj >= i && bm[cj - i, ci - i]) { sc[0]++; i++ }
            if (sc[0] == 0) return false
            i = 1
            while (ci + i < h && cj + i < w && bm[cj + i, ci + i]) { sc[2]++; i++ }
            while (ci + i < h && cj + i < w && !bm[cj + i, ci + i]) { sc[3]++; i++ }
            if (sc[3] == 0) return false
            while (ci + i < h && cj + i < w && bm[cj + i, ci + i]) { sc[4]++; i++ }
            if (sc[4] == 0) return false
            return foundPatternDiagonal(sc)
        }
    }

    private fun foundPatternCross(sc: IntArray): Boolean {
        var total = 0
        for (c in sc) {
            if (c == 0) return false
            total += c
        }
        if (total < 7) return false
        val ms = total / 7f
        val v = ms / 2f
        return abs(ms - sc[0]) < v && abs(ms - sc[1]) < v && abs(3f * ms - sc[2]) < 3f * v &&
            abs(ms - sc[3]) < v && abs(ms - sc[4]) < v
    }

    private fun foundPatternDiagonal(sc: IntArray): Boolean {
        var total = 0
        for (c in sc) {
            if (c == 0) return false
            total += c
        }
        if (total < 7) return false
        val ms = total / 7f
        val v = ms / 1.333f
        return abs(ms - sc[0]) < v && abs(ms - sc[1]) < v && abs(3f * ms - sc[2]) < 3f * v &&
            abs(ms - sc[3]) < v && abs(ms - sc[4]) < v
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    /** Returns up to 3 plausible finder triples, best first. */
    private fun bestTriples(all: List<Finder>): List<List<Finder>> {
        if (all.size < 3) return emptyList()
        var cands = all.filter { it.count >= 2 }
        if (cands.size < 3) cands = all
        cands = cands.sortedByDescending { it.count }.take(12)
        val scored = ArrayList<Pair<Double, List<Finder>>>()
        for (a in 0 until cands.size) for (b in a + 1 until cands.size) for (c in b + 1 until cands.size) {
            val p = cands[a]
            val q = cands[b]
            val r = cands[c]
            val msMax = maxOf(p.ms, q.ms, r.ms)
            val msMin = minOf(p.ms, q.ms, r.ms)
            if (msMax > 1.6f * msMin) continue
            val d = doubleArrayOf(
                sq(p.x - q.x, p.y - q.y), sq(q.x - r.x, q.y - r.y), sq(p.x - r.x, p.y - r.y),
            ).also { it.sort() }
            val avgMs = (p.ms + q.ms + r.ms) / 3.0
            val shortSide = sqrt(d[0]) / avgMs
            val longLeg = sqrt(d[1]) / avgMs
            if (shortSide < 10.0 || longLeg > 75.0) continue
            val score = abs(d[2] - (d[0] + d[1])) / d[2] + abs(d[0] - d[1]) / (d[0] + d[1]) +
                (msMax - msMin) / msMax * 0.5
            scored.add(score to listOf(p, q, r))
        }
        scored.sortBy { it.first }
        return scored.take(3).map { it.second }
    }

    private fun sq(dx: Float, dy: Float): Double = dx.toDouble() * dx + dy.toDouble() * dy

    /** Orders three finders as [topLeft, topRight, bottomLeft] (image y axis pointing down). */
    private fun order(t: List<Finder>): Array<Finder>? {
        val p0 = t[0]
        val p1 = t[1]
        val p2 = t[2]
        val d01 = dist(p0.x, p0.y, p1.x, p1.y)
        val d12 = dist(p1.x, p1.y, p2.x, p2.y)
        val d02 = dist(p0.x, p0.y, p2.x, p2.y)
        var a: Finder
        val b: Finder
        var c: Finder
        if (d12 >= d01 && d12 >= d02) {
            b = p0; a = p1; c = p2
        } else if (d02 >= d12 && d02 >= d01) {
            b = p1; a = p0; c = p2
        } else {
            b = p2; a = p0; c = p1
        }
        val cross = (c.x - b.x) * (a.y - b.y) - (c.y - b.y) * (a.x - b.x)
        if (cross == 0f) return null
        if (cross < 0f) {
            val tmp = a; a = c; c = tmp
        }
        // a = bottom-left, b = top-left, c = top-right
        return arrayOf(b, c, a)
    }

    private fun detectAndDecode(bm: QrBitMatrix, tl: Finder, tr: Finder, bl: Finder): String? {
        val ms = (moduleSizeOneWay(bm, tl, tr) + moduleSizeOneWay(bm, tl, bl)) / 2f
        if (ms.isNaN() || ms < 1f) return null
        val tltr = (dist(tl.x, tl.y, tr.x, tr.y) / ms).roundToInt()
        val tlbl = (dist(tl.x, tl.y, bl.x, bl.y) / ms).roundToInt()
        val base = (tltr + tlbl) / 2 + 7
        val first = when (base and 3) {
            0 -> listOf(base + 1)
            1 -> listOf(base)
            2 -> listOf(base - 1)
            else -> listOf(base + 2, base - 2)
        }
        val dims = LinkedHashSet<Int>()
        for (d in first) dims.add(d)
        for (d in first) { dims.add(d - 4); dims.add(d + 4) }

        for (dim in dims) {
            val version = (dim - 17) / 4
            if (version !in MIN_VERSION..MAX_VERSION) continue
            var align: Pt? = null
            if (version >= 2) {
                val brX = tr.x - tl.x + bl.x
                val brY = tr.y - tl.y + bl.y
                val correction = 1f - 3f / (dim - 7)
                val estX = (tl.x + correction * (brX - tl.x)).toInt()
                val estY = (tl.y + correction * (brY - tl.y)).toInt()
                for (allow in intArrayOf(4, 8, 16)) {
                    align = findAlignment(bm, ms, estX, estY, allow.toFloat())
                    if (align != null) break
                }
            }
            if (align != null) {
                sample(bm, tl, tr, bl, align.x.toDouble(), align.y.toDouble(), dim - 6.5, dim)
                    ?.let { g -> decodeGridInternal(g)?.let { return it } }
            }
            val brX = (tr.x - tl.x + bl.x).toDouble()
            val brY = (tr.y - tl.y + bl.y).toDouble()
            sample(bm, tl, tr, bl, brX, brY, dim - 3.5, dim)?.let { g -> decodeGridInternal(g)?.let { return it } }
            if (align == null) {
                // No alignment anchor (v1, or not found): the parallelogram estimate of the
                // bottom-right corner is off under perspective, so probe nearby positions and let
                // format-BCH + Reed–Solomon validate the result.
                for (step in 1..3) for (oy in -step..step) for (ox in -step..step) {
                    if (maxOf(abs(ox), abs(oy)) != step) continue
                    sample(bm, tl, tr, bl, brX + ox * 0.5 * ms, brY + oy * 0.5 * ms, dim - 3.5, dim)
                        ?.let { g -> decodeGridInternal(g)?.let { return it } }
                }
            }
        }
        return null
    }

    private fun moduleSizeOneWay(bm: QrBitMatrix, p: Finder, o: Finder): Float {
        val e1 = runBothWays(bm, p.x.toInt(), p.y.toInt(), o.x.toInt(), o.y.toInt())
        val e2 = runBothWays(bm, o.x.toInt(), o.y.toInt(), p.x.toInt(), p.y.toInt())
        if (e1.isNaN()) return e2 / 7f
        if (e2.isNaN()) return e1 / 7f
        return (e1 + e2) / 14f
    }

    private fun runBothWays(bm: QrBitMatrix, fromX: Int, fromY: Int, toX: Int, toY: Int): Float {
        var result = blackWhiteBlackRun(bm, fromX, fromY, toX, toY)
        var scale = 1f
        var otherToX = fromX - (toX - fromX)
        if (otherToX < 0) {
            scale = fromX.toFloat() / (fromX - otherToX)
            otherToX = 0
        } else if (otherToX >= bm.width) {
            scale = (bm.width - 1 - fromX).toFloat() / (otherToX - fromX)
            otherToX = bm.width - 1
        }
        var otherToY = (fromY - (toY - fromY) * scale).toInt()
        scale = 1f
        if (otherToY < 0) {
            scale = fromY.toFloat() / (fromY - otherToY)
            otherToY = 0
        } else if (otherToY >= bm.height) {
            scale = (bm.height - 1 - fromY).toFloat() / (otherToY - fromY)
            otherToY = bm.height - 1
        }
        otherToX = (fromX + (otherToX - fromX) * scale).toInt()
        result += blackWhiteBlackRun(bm, fromX, fromY, otherToX, otherToY)
        return result - 1f
    }

    private fun blackWhiteBlackRun(bm: QrBitMatrix, fx: Int, fy: Int, tx: Int, ty: Int): Float {
        var fromX = fx
        var fromY = fy
        var toX = tx
        var toY = ty
        val steep = abs(toY - fromY) > abs(toX - fromX)
        if (steep) {
            var t = fromX; fromX = fromY; fromY = t
            t = toX; toX = toY; toY = t
        }
        val dx = abs(toX - fromX)
        val dy = abs(toY - fromY)
        var error = -dx / 2
        val xStep = if (fromX < toX) 1 else -1
        val yStep = if (fromY < toY) 1 else -1
        var state = 0
        val xLimit = toX + xStep
        var x = fromX
        var y = fromY
        while (x != xLimit) {
            val realX = if (steep) y else x
            val realY = if (steep) x else y
            if ((state == 1) == bm.getOrLight(realX, realY)) {
                if (state == 2) return dist(x.toFloat(), y.toFloat(), fromX.toFloat(), fromY.toFloat())
                state++
            }
            error += dy
            if (error > 0) {
                if (y == toY) break
                y += yStep
                error -= dx
            }
            x += xStep
        }
        if (state == 2) {
            return dist((toX + xStep).toFloat(), toY.toFloat(), fromX.toFloat(), fromY.toFloat())
        }
        return Float.NaN
    }

    // ---------------------------------------------------------------- alignment pattern

    private fun findAlignment(bm: QrBitMatrix, ms: Float, estX: Int, estY: Int, factor: Float): Pt? {
        val allowance = (factor * ms).toInt()
        val left = max(0, estX - allowance)
        val right = min(bm.width - 1, estX + allowance)
        if (right - left < ms * 3) return null
        val top = max(0, estY - allowance)
        val bottom = min(bm.height - 1, estY + allowance)
        if (bottom - top < ms * 3) return null
        return AlignmentScanner(bm, left, top, right - left, bottom - top, ms).find()
    }

    private class AlignmentScanner(
        private val bm: QrBitMatrix,
        private val startX: Int,
        private val startY: Int,
        private val width: Int,
        private val height: Int,
        private val ms: Float,
    ) {
        private val centers = ArrayList<Finder>()

        fun find(): Pt? {
            val maxJ = startX + width
            val middleI = startY + height / 2
            val sc = IntArray(3)
            for (iGen in 0 until height) {
                val i = middleI + if (iGen and 1 == 0) (iGen + 1) / 2 else -((iGen + 1) / 2)
                if (i !in 0 until bm.height) continue
                sc.fill(0)
                var j = startX
                while (j < maxJ && !bm[j, i]) j++
                var state = 0
                while (j < maxJ) {
                    if (bm[j, i]) {
                        if (state == 1) {
                            sc[1]++
                        } else if (state == 2) {
                            if (cross(sc)) handle(sc, i, j)?.let { return it }
                            sc[0] = sc[2]
                            sc[1] = 1
                            sc[2] = 0
                            state = 1
                        } else {
                            state++
                            sc[state]++
                        }
                    } else {
                        if (state == 1) state++
                        sc[state]++
                    }
                    j++
                }
                if (cross(sc)) handle(sc, i, maxJ)?.let { return it }
            }
            return centers.firstOrNull()?.let { Pt(it.x, it.y) }
        }

        private fun cross(sc: IntArray): Boolean {
            val v = ms / 2f
            for (c in sc) if (abs(ms - c) >= v) return false
            return true
        }

        private fun centerFromEnd(sc: IntArray, end: Int): Float = (end - sc[2]).toFloat() - sc[1] / 2f

        private fun handle(sc: IntArray, i: Int, j: Int): Pt? {
            val total = sc.sum()
            val centerJ = centerFromEnd(sc, j)
            val centerI = crossCheckVertical(i, centerJ.toInt(), 2 * sc[1], total)
            if (centerI.isNaN()) return null
            val est = total / 3f
            for (c in centers) {
                if (abs(centerI - c.y) <= est && abs(centerJ - c.x) <= est) {
                    val d = abs(est - c.ms)
                    if (d <= 1f || d <= c.ms) {
                        return Pt((c.x + centerJ) / 2f, (c.y + centerI) / 2f)
                    }
                }
            }
            centers.add(Finder(centerJ, centerI, est, 1))
            return null
        }

        private fun crossCheckVertical(startI: Int, cj: Int, maxCount: Int, originalTotal: Int): Float {
            if (cj !in 0 until bm.width) return Float.NaN
            val maxI = bm.height
            val sc = IntArray(3)
            var i = startI
            while (i >= 0 && bm[cj, i] && sc[1] <= maxCount) { sc[1]++; i-- }
            if (i < 0 || sc[1] > maxCount) return Float.NaN
            while (i >= 0 && !bm[cj, i] && sc[0] <= maxCount) { sc[0]++; i-- }
            if (sc[0] > maxCount) return Float.NaN
            i = startI + 1
            while (i < maxI && bm[cj, i] && sc[1] <= maxCount) { sc[1]++; i++ }
            if (i == maxI || sc[1] > maxCount) return Float.NaN
            while (i < maxI && !bm[cj, i] && sc[2] <= maxCount) { sc[2]++; i++ }
            if (sc[2] > maxCount) return Float.NaN
            val total = sc.sum()
            if (5 * abs(total - originalTotal) >= 2 * originalTotal) return Float.NaN
            return if (cross(sc)) centerFromEnd(sc, i) else Float.NaN
        }
    }

    // ---------------------------------------------------------------- sampling

    /** Maps module space → image space: x' = (h0 u + h1 v + h2)/(h6 u + h7 v + 1), etc. */
    private fun solveHomography(src: DoubleArray, dst: DoubleArray): DoubleArray? {
        val a = Array(8) { DoubleArray(9) }
        for (k in 0 until 4) {
            val u = src[2 * k]
            val v = src[2 * k + 1]
            val x = dst[2 * k]
            val y = dst[2 * k + 1]
            a[2 * k] = doubleArrayOf(u, v, 1.0, 0.0, 0.0, 0.0, -u * x, -v * x, x)
            a[2 * k + 1] = doubleArrayOf(0.0, 0.0, 0.0, u, v, 1.0, -u * y, -v * y, y)
        }
        for (col in 0 until 8) {
            var piv = col
            for (r in col + 1 until 8) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            if (abs(a[piv][col]) < 1e-10) return null
            val t = a[col]; a[col] = a[piv]; a[piv] = t
            for (r in 0 until 8) {
                if (r == col) continue
                val f = a[r][col] / a[col][col]
                if (f == 0.0) continue
                for (c in col until 9) a[r][c] -= f * a[col][c]
            }
        }
        return DoubleArray(8) { a[it][8] / a[it][it] }
    }

    private fun sample(
        bm: QrBitMatrix,
        tl: Finder,
        tr: Finder,
        bl: Finder,
        brX: Double,
        brY: Double,
        brSrc: Double,
        dim: Int,
    ): Array<BooleanArray>? {
        val d3 = dim - 3.5
        val h = solveHomography(
            doubleArrayOf(3.5, 3.5, d3, 3.5, brSrc, brSrc, 3.5, d3),
            doubleArrayOf(
                tl.x.toDouble(), tl.y.toDouble(), tr.x.toDouble(), tr.y.toDouble(),
                brX, brY, bl.x.toDouble(), bl.y.toDouble(),
            ),
        ) ?: return null
        val grid = Array(dim) { BooleanArray(dim) }
        val maxX = bm.width - 1
        val maxY = bm.height - 1
        var outside = 0
        for (y in 0 until dim) {
            val v = y + 0.5
            for (x in 0 until dim) {
                val u = x + 0.5
                val wDen = h[6] * u + h[7] * v + 1.0
                if (wDen == 0.0) return null
                val px = ((h[0] * u + h[1] * v + h[2]) / wDen).toInt()
                val py = ((h[3] * u + h[4] * v + h[5]) / wDen).toInt()
                if (px < 0 || py < 0 || px > maxX || py > maxY) outside++
                grid[y][x] = bm[px.coerceIn(0, maxX), py.coerceIn(0, maxY)]
            }
        }
        if (outside > dim * 2) return null
        return grid
    }

    // ---------------------------------------------------------------- grid decoding

    internal fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (y + x) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (y + x) % 3 == 0
        4 -> (y / 2 + x / 3) % 2 == 0
        5 -> (y * x) % 2 + (y * x) % 3 == 0
        6 -> ((y * x) % 2 + (y * x) % 3) % 2 == 0
        else -> ((y + x) % 2 + (y * x) % 3) % 2 == 0
    }

    /** Function-pattern map (`[y][x]`, true = not a data module) for [version]. */
    internal fun functionPatternMask(version: Int): Array<BooleanArray> {
        val size = 17 + 4 * version
        val f = Array(size) { BooleanArray(size) }
        fun rect(x0: Int, y0: Int, w: Int, h: Int) {
            for (y in y0 until y0 + h) for (x in x0 until x0 + w) {
                if (x in 0 until size && y in 0 until size) f[y][x] = true
            }
        }
        // Finders + separators + format areas.
        rect(0, 0, 9, 9)
        rect(size - 8, 0, 8, 9)
        rect(0, size - 8, 9, 8)
        // Timing patterns.
        rect(6, 0, 1, size)
        rect(0, 6, size, 1)
        // Alignment patterns.
        val coords = ALIGNMENT_COORDS[version]
        val n = coords.size
        for (i in 0 until n) for (j in 0 until n) {
            if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
            rect(coords[i] - 2, coords[j] - 2, 5, 5)
        }
        // Version information.
        if (version >= 7) {
            rect(size - 11, 0, 3, 6)
            rect(0, size - 11, 6, 3)
        }
        return f
    }

    private fun readFormat(g: Array<BooleanArray>): Int? {
        val size = g.size
        fun b(x: Int, y: Int, i: Int): Int = if (g[y][x]) 1 shl i else 0
        var f1 = 0
        for (i in 0..5) f1 = f1 or b(8, i, i)
        f1 = f1 or b(8, 7, 6) or b(8, 8, 7) or b(7, 8, 8)
        for (i in 9..14) f1 = f1 or b(14 - i, 8, i)
        var f2 = 0
        for (i in 0..7) f2 = f2 or b(size - 1 - i, 8, i)
        for (i in 8..14) f2 = f2 or b(8, size - 15 + i, i)
        var best = -1
        var bestDist = Int.MAX_VALUE
        for (d in 0 until 32) {
            val w = FORMAT_WORDS[d]
            val dd = min(Integer.bitCount(w xor f1), Integer.bitCount(w xor f2))
            if (dd < bestDist) {
                bestDist = dd
                best = d
            }
        }
        return if (bestDist <= 3) best else null
    }

    /** Reads the version information block (v ≥ 7); returns the decoded version or null. */
    internal fun readVersionInfo(g: Array<BooleanArray>): Int? {
        val size = g.size
        var v1 = 0
        var v2 = 0
        for (i in 0 until 18) {
            val a = size - 11 + (i % 3)
            val b = i / 3
            if (g[b][a]) v1 = v1 or (1 shl i)
            if (g[a][b]) v2 = v2 or (1 shl i)
        }
        var best = -1
        var bestDist = Int.MAX_VALUE
        for (v in 7..40) {
            val w = VERSION_WORDS[v]
            val dd = min(Integer.bitCount(w xor v1), Integer.bitCount(w xor v2))
            if (dd < bestDist) {
                bestDist = dd
                best = v
            }
        }
        return if (bestDist <= 3) best else null
    }

    private fun decodeGridInternal(modules: Array<BooleanArray>): String? {
        val size = modules.size
        if (size < 21 || (size - 17) % 4 != 0) return null
        for (row in modules) if (row.size != size) return null
        val version = (size - 17) / 4
        if (version !in MIN_VERSION..MAX_VERSION) return null
        if (version >= 7) {
            val vi = readVersionInfo(modules)
            if (vi != null && vi != version) return null
        }
        val fmt = readFormat(modules) ?: return null
        val ecIdx = when (fmt ushr 3) {
            1 -> 0 // L
            0 -> 1 // M
            3 -> 2 // Q
            else -> 3 // H
        }
        val mask = fmt and 7
        val func = functionPatternMask(version)

        val total = TOTAL_CODEWORDS[version]
        val raw = IntArray(total)
        var bitIdx = 0
        val totalBits = total * 8
        var right = size - 1
        var upward = true
        while (right >= 1) {
            if (right == 6) right = 5
            for (k in 0 until size) {
                val y = if (upward) size - 1 - k else k
                for (dx in 0..1) {
                    val x = right - dx
                    if (func[y][x]) continue
                    if (bitIdx < totalBits) {
                        val bit = modules[y][x] xor maskBit(mask, x, y)
                        if (bit) raw[bitIdx ushr 3] = raw[bitIdx ushr 3] or (1 shl (7 - (bitIdx and 7)))
                    }
                    bitIdx++
                }
            }
            upward = !upward
            right -= 2
        }
        if (bitIdx < totalBits) return null

        val data = deinterleaveAndCorrect(raw, version, ecIdx) ?: return null
        return decodeSegments(data, version)
    }

    private fun deinterleaveAndCorrect(raw: IntArray, version: Int, ecIdx: Int): IntArray? {
        val total = TOTAL_CODEWORDS[version]
        val numBlocks = NUM_BLOCKS[ecIdx][version]
        val ec = EC_PER_BLOCK[ecIdx][version]
        val shortLen = total / numBlocks
        val numShort = numBlocks - total % numBlocks
        val blocks = Array(numBlocks) { b -> IntArray(shortLen + if (b < numShort) 0 else 1) }
        val dataLens = IntArray(numBlocks) { blocks[it].size - ec }
        if (dataLens.any { it <= 0 }) return null
        val maxData = dataLens.max()
        var idx = 0
        for (i in 0 until maxData) for (b in 0 until numBlocks) {
            if (i < dataLens[b]) blocks[b][i] = raw[idx++]
        }
        for (i in 0 until ec) for (b in 0 until numBlocks) {
            blocks[b][dataLens[b] + i] = raw[idx++]
        }
        val out = IntArray(dataLens.sum())
        var o = 0
        for (b in 0 until numBlocks) {
            if (!ReedSolomon.correct(blocks[b], ec)) return null
            for (i in 0 until dataLens[b]) out[o++] = blocks[b][i]
        }
        return out
    }

    // ---------------------------------------------------------------- segments

    private class BitReader(private val bytes: IntArray) {
        var pos = 0
        fun available(): Int = bytes.size * 8 - pos
        fun read(n: Int): Int {
            var r = 0
            repeat(n) {
                r = (r shl 1) or ((bytes[pos ushr 3] ushr (7 - (pos and 7))) and 1)
                pos++
            }
            return r
        }
    }

    /** Parses the data-codeword bitstream into text. Returns null on malformed/unsupported data. */
    internal fun decodeSegments(data: IntArray, version: Int): String? {
        val r = BitReader(data)
        val sb = StringBuilder()
        val pending = ByteArrayOutputStream()
        var eciCharset: Charset? = null
        val large = version >= 10

        fun flush() {
            if (pending.size() == 0) return
            sb.append(decodeBytes(pending.toByteArray(), eciCharset))
            pending.reset()
        }

        while (r.available() >= 4) {
            val mode = r.read(4)
            when (mode) {
                0x0 -> break
                0x1 -> { // numeric
                    flush()
                    val bits = if (large) 12 else 10
                    if (r.available() < bits) return null
                    var count = r.read(bits)
                    while (count >= 3) {
                        if (r.available() < 10) return null
                        val v = r.read(10)
                        if (v >= 1000) return null
                        sb.append(v.toString().padStart(3, '0'))
                        count -= 3
                    }
                    if (count == 2) {
                        if (r.available() < 7) return null
                        val v = r.read(7)
                        if (v >= 100) return null
                        sb.append(v.toString().padStart(2, '0'))
                    } else if (count == 1) {
                        if (r.available() < 4) return null
                        val v = r.read(4)
                        if (v >= 10) return null
                        sb.append(v)
                    }
                }
                0x2 -> { // alphanumeric
                    flush()
                    val bits = if (large) 11 else 9
                    if (r.available() < bits) return null
                    var count = r.read(bits)
                    while (count >= 2) {
                        if (r.available() < 11) return null
                        val v = r.read(11)
                        if (v >= 45 * 45) return null
                        sb.append(ALNUM_CHARS[v / 45]).append(ALNUM_CHARS[v % 45])
                        count -= 2
                    }
                    if (count == 1) {
                        if (r.available() < 6) return null
                        val v = r.read(6)
                        if (v >= 45) return null
                        sb.append(ALNUM_CHARS[v])
                    }
                }
                0x4 -> { // byte
                    val bits = if (large) 16 else 8
                    if (r.available() < bits) return null
                    val count = r.read(bits)
                    if (r.available() < count * 8) return null
                    repeat(count) { pending.write(r.read(8)) }
                }
                0x7 -> { // ECI
                    flush()
                    if (r.available() < 8) return null
                    val first = r.read(8)
                    val value = when {
                        first and 0x80 == 0 -> first and 0x7F
                        first and 0xC0 == 0x80 -> {
                            if (r.available() < 8) return null
                            ((first and 0x3F) shl 8) or r.read(8)
                        }
                        first and 0xE0 == 0xC0 -> {
                            if (r.available() < 16) return null
                            ((first and 0x1F) shl 16) or r.read(16)
                        }
                        else -> return null
                    }
                    eciCharset = charsetForEci(value)
                }
                0x3 -> { // structured append: sequence + parity, no text
                    if (r.available() < 16) return null
                    r.read(16)
                }
                0x5 -> Unit // FNC1 first position
                0x9 -> { // FNC1 second position: application indicator
                    if (r.available() < 8) return null
                    r.read(8)
                }
                else -> return null // Kanji (0x8), Hanzi (0xD) and reserved modes unsupported
            }
        }
        flush()
        return sb.toString()
    }

    private fun charsetForEci(value: Int): Charset? {
        val name = when (value) {
            0, 2 -> "Cp437"
            1, 3 -> "ISO-8859-1"
            in 4..9 -> "ISO-8859-${value - 2}"
            in 10..13 -> "ISO-8859-${value - 2}"
            in 15..18 -> "ISO-8859-${value - 2}"
            20 -> "Shift_JIS"
            21 -> "windows-1250"
            22 -> "windows-1251"
            23 -> "windows-1252"
            24 -> "windows-1256"
            25 -> "UTF-16BE"
            26 -> "UTF-8"
            27, 170 -> "US-ASCII"
            28 -> "Big5"
            29 -> "GB18030"
            30 -> "EUC-KR"
            else -> return null
        }
        return try {
            Charset.forName(name)
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeBytes(bytes: ByteArray, eci: Charset?): String {
        if (eci != null) return String(bytes, eci)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }
}
