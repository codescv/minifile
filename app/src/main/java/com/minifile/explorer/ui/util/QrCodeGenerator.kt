package com.minifile.explorer.ui.util

/**
 * Pure-Kotlin, zero-dependency ISO/IEC 18004 QR Code (Model 2) encoder.
 *
 * Encodes UTF-8 strings in Byte mode (`0100`) with Reed-Solomon error correction
 * across Versions 1–10 (21x21 up to 57x57 modules, supporting up to 213 bytes at
 * EC Level M and up to 271 bytes at EC Level L).
 */
object QrCodeGenerator {

    enum class EccLevel(val formatBits: Int) {
        L(0b01),
        M(0b00),
    }

    class QrMatrix(
        val size: Int,
        val version: Int,
        val eccLevel: EccLevel,
        val mask: Int,
        val modules: Array<BooleanArray>,
    ) {
        operator fun get(x: Int, y: Int): Boolean = modules[y][x]
    }

    const val MIN_VERSION = 1
    const val MAX_VERSION = 10

    // ISO/IEC 18004 Table 9 — EC codewords per block for Versions 1..10 (index 0 unused)
    private val EC_CODEWORDS_PER_BLOCK_L = intArrayOf(0, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18)
    private val NUM_BLOCKS_L = intArrayOf(0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4)

    private val EC_CODEWORDS_PER_BLOCK_M = intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
    private val NUM_BLOCKS_M = intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

    // ISO/IEC 18004 Annex E Table E.1 — Alignment pattern center coordinates for Versions 1..10
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

    fun encode(text: String, preferredEcc: EccLevel = EccLevel.M): QrMatrix {
        return encodeBytes(text.toByteArray(Charsets.UTF_8), preferredEcc)
    }

    fun encodeBytes(data: ByteArray, preferredEcc: EccLevel = EccLevel.M): QrMatrix {
        val candidateLevels = if (preferredEcc == EccLevel.M) {
            listOf(EccLevel.M, EccLevel.L)
        } else {
            listOf(EccLevel.L)
        }

        var chosenVersion = -1
        var chosenEcc = preferredEcc
        outer@ for (ecc in candidateLevels) {
            for (ver in MIN_VERSION..MAX_VERSION) {
                val capBits = getNumDataCodewords(ver, ecc) * 8
                val neededBits = 4 + charCountBits(ver) + data.size * 8
                if (neededBits <= capBits) {
                    chosenVersion = ver
                    chosenEcc = ecc
                    break@outer
                }
            }
        }
        require(chosenVersion in MIN_VERSION..MAX_VERSION) {
            "Payload too large for QR versions $MIN_VERSION..$MAX_VERSION (${data.size} bytes)"
        }

        val dataCodewords = buildDataCodewords(data, chosenVersion, chosenEcc)
        val allCodewords = addEccAndInterleave(dataCodewords, chosenVersion, chosenEcc)
        return buildBestMaskedMatrix(allCodewords, chosenVersion, chosenEcc)
    }

    internal fun dimensionForVersion(version: Int): Int = 17 + 4 * version

    internal fun charCountBits(version: Int): Int = if (version <= 9) 8 else 16

    internal fun ecCodewordsPerBlock(version: Int, ecc: EccLevel): Int = when (ecc) {
        EccLevel.L -> EC_CODEWORDS_PER_BLOCK_L[version]
        EccLevel.M -> EC_CODEWORDS_PER_BLOCK_M[version]
    }

    internal fun numBlocks(version: Int, ecc: EccLevel): Int = when (ecc) {
        EccLevel.L -> NUM_BLOCKS_L[version]
        EccLevel.M -> NUM_BLOCKS_M[version]
    }

    internal fun getNumRawDataModules(version: Int): Int {
        val size = dimensionForVersion(version)
        var result = size * size
        // 3 finder patterns + separators (8x8 each = 192) + format info + dark module (31) - timing lines
        result -= 3 * 64
        result -= 2 * (size - 16)
        result -= 31

        val alignCount = ALIGNMENT_COORDS[version].size
        if (alignCount > 0) {
            val numAlignBoxes = alignCount * alignCount - 3
            result -= numAlignBoxes * 25
            // Subtract timing modules already counted inside alignment patterns along row 6 / col 6
            if (alignCount > 2) {
                result += 2 * (alignCount - 2) * 5
            }
        }
        if (version >= 7) {
            result -= 36
        }
        return result
    }

    internal fun getNumDataCodewords(version: Int, ecc: EccLevel): Int {
        val totalCodewords = getNumRawDataModules(version) / 8
        return totalCodewords - ecCodewordsPerBlock(version, ecc) * numBlocks(version, ecc)
    }

    private fun buildDataCodewords(data: ByteArray, version: Int, ecc: EccLevel): ByteArray {
        val dataCapacityBytes = getNumDataCodewords(version, ecc)
        val capacityBits = dataCapacityBytes * 8
        val bitBuf = BitBuffer(capacityBits)

        // Byte mode indicator: 0100
        bitBuf.appendBits(0b0100, 4)
        bitBuf.appendBits(data.size, charCountBits(version))
        for (b in data) {
            bitBuf.appendBits(b.toInt() and 0xFF, 8)
        }

        // Terminator (up to 4 zero bits)
        val terminatorBits = minOf(4, capacityBits - bitBuf.bitLength)
        bitBuf.appendBits(0, terminatorBits)

        // Pad to byte boundary
        val remBits = (8 - (bitBuf.bitLength % 8)) % 8
        bitBuf.appendBits(0, remBits)

        // Pad bytes 0xEC, 0x11 alternating
        var padToggle = true
        while (bitBuf.bitLength < capacityBits) {
            bitBuf.appendBits(if (padToggle) 0xEC else 0x11, 8)
            padToggle = !padToggle
        }

        return bitBuf.toByteArray()
    }

    private fun addEccAndInterleave(data: ByteArray, version: Int, ecc: EccLevel): ByteArray {
        val totalCodewords = getNumRawDataModules(version) / 8
        val blocksCount = numBlocks(version, ecc)
        val blockEcLen = ecCodewordsPerBlock(version, ecc)

        val numShortBlocks = blocksCount - (totalCodewords % blocksCount)
        val shortBlockLen = totalCodewords / blocksCount
        val shortDataLen = shortBlockLen - blockEcLen

        val rsDivisor = computeReedSolomonDivisor(blockEcLen)
        val dataBlocks = Array(blocksCount) { ByteArray(0) }
        val ecBlocks = Array(blocksCount) { ByteArray(0) }

        var offset = 0
        for (b in 0 until blocksCount) {
            val dataLen = shortDataLen + if (b < numShortBlocks) 0 else 1
            val blockData = data.copyOfRange(offset, offset + dataLen)
            offset += dataLen
            dataBlocks[b] = blockData
            ecBlocks[b] = computeReedSolomonRemainder(blockData, rsDivisor)
        }
        check(offset == data.size) { "Consumed $offset of ${data.size} data codewords" }

        val out = ByteArray(totalCodewords)
        var pos = 0
        val maxDataLen = shortDataLen + if (numShortBlocks < blocksCount) 1 else 0
        for (col in 0 until maxDataLen) {
            for (b in 0 until blocksCount) {
                if (col < dataBlocks[b].size) {
                    out[pos++] = dataBlocks[b][col]
                }
            }
        }
        for (col in 0 until blockEcLen) {
            for (b in 0 until blocksCount) {
                out[pos++] = ecBlocks[b][col]
            }
        }
        check(pos == totalCodewords) { "Interleaved $pos of $totalCodewords codewords" }
        return out
    }

    private fun buildBestMaskedMatrix(codewords: ByteArray, version: Int, ecc: EccLevel): QrMatrix {
        val size = dimensionForVersion(version)
        val baseModules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        drawFunctionPatterns(version, size, ecc, baseModules, isFunction)
        drawCodewords(codewords, size, baseModules, isFunction)

        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        var bestGrid: Array<BooleanArray>? = null

        for (mask in 0..7) {
            val candidate = Array(size) { y -> baseModules[y].clone() }
            applyMask(mask, size, candidate, isFunction)
            drawFormatBits(ecc, mask, size, candidate, null)
            val penalty = computePenalty(size, candidate)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
                bestGrid = candidate
            }
        }

        return QrMatrix(
            size = size,
            version = version,
            eccLevel = ecc,
            mask = bestMask,
            modules = bestGrid!!,
        )
    }

    private fun drawFunctionPatterns(
        version: Int,
        size: Int,
        ecc: EccLevel,
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
    ) {
        fun setFunc(x: Int, y: Int, isDark: Boolean) {
            modules[y][x] = isDark
            isFunction[y][x] = true
        }

        // Timing patterns along row 6 and column 6
        for (i in 0 until size) {
            setFunc(6, i, i % 2 == 0)
            setFunc(i, 6, i % 2 == 0)
        }

        // 3 Finder patterns + 1-module white separators (centered at (3,3), (size-4,3), (3,size-4))
        fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val xx = cx + dx
                    val yy = cy + dy
                    if (xx in 0 until size && yy in 0 until size) {
                        val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                        setFunc(xx, yy, dist != 2 && dist != 4)
                    }
                }
            }
        }
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)

        // Alignment patterns (5x5)
        val coords = ALIGNMENT_COORDS[version]
        val nCoords = coords.size
        for (i in 0 until nCoords) {
            for (j in 0 until nCoords) {
                val overlapsFinder = (i == 0 && j == 0) ||
                    (i == 0 && j == nCoords - 1) ||
                    (i == nCoords - 1 && j == 0)
                if (overlapsFinder) continue
                val cx = coords[i]
                val cy = coords[j]
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                        setFunc(cx + dx, cy + dy, dist != 1)
                    }
                }
            }
        }

        // Reserve Format Information & Dark Module
        drawFormatBits(ecc, 0, size, modules, isFunction)

        // Version Information (for version >= 7)
        if (version >= 7) {
            var rem = version
            repeat(12) {
                rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
            }
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val bit = ((bits ushr i) and 1) != 0
                val a = size - 11 + (i % 3)
                val b = i / 3
                setFunc(a, b, bit)
                setFunc(b, a, bit)
            }
        }
    }

    internal fun computeFormatBits(ecc: EccLevel, mask: Int): Int {
        val data = (ecc.formatBits shl 3) or mask
        var rem = data
        repeat(10) {
            rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        }
        return ((data shl 10) or rem) xor 0x5412
    }

    private fun drawFormatBits(
        ecc: EccLevel,
        mask: Int,
        size: Int,
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>?,
    ) {
        val bits = computeFormatBits(ecc, mask)
        fun put(x: Int, y: Int, isDark: Boolean) {
            modules[y][x] = isDark
            if (isFunction != null) isFunction[y][x] = true
        }
        fun bit(i: Int): Boolean = ((bits ushr i) and 1) != 0

        // Copy 1: around top-left finder
        for (i in 0..5) put(8, i, bit(i))
        put(8, 7, bit(6))
        put(8, 8, bit(7))
        put(7, 8, bit(8))
        for (i in 9..14) put(14 - i, 8, bit(i))

        // Copy 2: top-right horizontal + bottom-left vertical
        for (i in 0..7) put(size - 1 - i, 8, bit(i))
        for (i in 8..14) put(8, size - 15 + i, bit(i))

        // Always-dark module
        put(8, size - 8, true)
    }

    private fun drawCodewords(
        codewords: ByteArray,
        size: Int,
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
    ) {
        var bitIdx = 0
        val totalBits = codewords.size * 8
        var right = size - 1
        var upward = true
        while (right >= 1) {
            if (right == 6) right = 5 // Skip vertical timing column
            val yProgression = if (upward) (size - 1 downTo 0) else (0 until size)
            for (y in yProgression) {
                for (dx in 0..1) {
                    val x = right - dx
                    if (!isFunction[y][x]) {
                        val isDark = if (bitIdx < totalBits) {
                            ((codewords[bitIdx ushr 3].toInt() ushr (7 - (bitIdx and 7))) and 1) != 0
                        } else {
                            false // Remainder bits are zero
                        }
                        modules[y][x] = isDark
                        bitIdx++
                    }
                }
            }
            upward = !upward
            right -= 2
        }
    }

    internal fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (y + x) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (y + x) % 3 == 0
        4 -> (y / 2 + x / 3) % 2 == 0
        5 -> (y * x) % 2 + (y * x) % 3 == 0
        6 -> ((y * x) % 2 + (y * x) % 3) % 2 == 0
        7 -> ((y + x) % 2 + (y * x) % 3) % 2 == 0
        else -> error("Invalid mask $mask")
    }

    private fun applyMask(
        mask: Int,
        size: Int,
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
    ) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (!isFunction[y][x] && maskBit(mask, x, y)) {
                    modules[y][x] = !modules[y][x]
                }
            }
        }
    }

    private fun computePenalty(size: Int, modules: Array<BooleanArray>): Int {
        var score = 0

        // Rule N1 & N3 along rows
        for (y in 0 until size) {
            var runColor = false
            var runLen = 0
            for (x in 0 until size) {
                val c = modules[y][x]
                if (x == 0 || c != runColor) {
                    if (runLen >= 5) score += 3 + (runLen - 5)
                    runColor = c
                    runLen = 1
                } else {
                    runLen++
                }
            }
            if (runLen >= 5) score += 3 + (runLen - 5)

            for (x in 0..size - 11) {
                if (matchesFinderLikeRow(modules[y], x)) score += 40
            }
        }

        // Rule N1 & N3 along columns
        for (x in 0 until size) {
            var runColor = false
            var runLen = 0
            for (y in 0 until size) {
                val c = modules[y][x]
                if (y == 0 || c != runColor) {
                    if (runLen >= 5) score += 3 + (runLen - 5)
                    runColor = c
                    runLen = 1
                } else {
                    runLen++
                }
            }
            if (runLen >= 5) score += 3 + (runLen - 5)

            for (y in 0..size - 11) {
                if (matchesFinderLikeCol(modules, x, y)) score += 40
            }
        }

        // Rule N2: 2x2 same-color blocks
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) {
                    score += 3
                }
            }
        }

        // Rule N4: dark/light module ratio
        var dark = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (modules[y][x]) dark++
            }
        }
        val total = size * size
        val k = ((kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total - 1).coerceAtLeast(0)
        score += k * 10

        return score
    }

    private fun matchesFinderLikeRow(row: BooleanArray, start: Int): Boolean {
        // 10111010000 or 00001011101
        val p1 = row[start] && !row[start + 1] && row[start + 2] && row[start + 3] && row[start + 4] &&
            !row[start + 5] && row[start + 6] && !row[start + 7] && !row[start + 8] && !row[start + 9] && !row[start + 10]
        if (p1) return true
        return !row[start] && !row[start + 1] && !row[start + 2] && !row[start + 3] &&
            row[start + 4] && !row[start + 5] && row[start + 6] && row[start + 7] && row[start + 8] &&
            !row[start + 9] && row[start + 10]
    }

    private fun matchesFinderLikeCol(grid: Array<BooleanArray>, x: Int, start: Int): Boolean {
        val p1 = grid[start][x] && !grid[start + 1][x] && grid[start + 2][x] && grid[start + 3][x] && grid[start + 4][x] &&
            !grid[start + 5][x] && grid[start + 6][x] && !grid[start + 7][x] && !grid[start + 8][x] && !grid[start + 9][x] && !grid[start + 10][x]
        if (p1) return true
        return !grid[start][x] && !grid[start + 1][x] && !grid[start + 2][x] && !grid[start + 3][x] &&
            grid[start + 4][x] && !grid[start + 5][x] && grid[start + 6][x] && grid[start + 7][x] && grid[start + 8][x] &&
            !grid[start + 9][x] && grid[start + 10][x]
    }

    internal fun gfMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    internal fun computeReedSolomonDivisor(degree: Int): ByteArray {
        val result = ByteArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                result[j] = gfMultiply(result[j].toInt() and 0xFF, root).toByte()
                if (j + 1 < degree) {
                    result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
                }
            }
            root = gfMultiply(root, 0x02)
        }
        return result
    }

    internal fun computeReedSolomonRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
        val result = ByteArray(divisor.size)
        for (b in data) {
            val factor = (b.toInt() xor result[0].toInt()) and 0xFF
            System.arraycopy(result, 1, result, 0, result.size - 1)
            result[result.size - 1] = 0
            for (j in divisor.indices) {
                result[j] = (result[j].toInt() xor gfMultiply(divisor[j].toInt() and 0xFF, factor)).toByte()
            }
        }
        return result
    }

    private class BitBuffer(capacityBits: Int) {
        private val bytes = ByteArray((capacityBits + 7) / 8)
        var bitLength: Int = 0
            private set

        fun appendBits(value: Int, numBits: Int) {
            for (i in numBits - 1 downTo 0) {
                val bit = (value ushr i) and 1
                if (bit != 0) {
                    bytes[bitLength ushr 3] = (bytes[bitLength ushr 3].toInt() or (1 shl (7 - (bitLength and 7)))).toByte()
                }
                bitLength++
            }
        }

        fun toByteArray(): ByteArray = bytes.copyOf((bitLength + 7) / 8)
    }
}
