package com.minifile.explorer.core.qr

/**
 * Reed-Solomon error correction over GF(256) with primitive polynomial 0x11D and generator
 * roots α^0..α^(ecCount-1), as used by ISO/IEC 18004 QR codes.
 *
 * Pipeline: syndromes → Berlekamp–Massey (error locator) → Chien search (error positions)
 * → Forney (error magnitudes).
 */
object ReedSolomon {
    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    private fun div(a: Int, b: Int): Int {
        require(b != 0)
        return if (a == 0) 0 else EXP[(LOG[a] + 255 - LOG[b]) % 255]
    }

    /** Evaluates a polynomial stored highest-degree-first (codeword order) at [x]. */
    private fun evalHighFirst(poly: IntArray, x: Int): Int {
        var s = 0
        for (c in poly) s = mul(s, x) xor c
        return s
    }

    /** Evaluates a polynomial stored lowest-degree-first at [x]. */
    private fun evalLowFirst(poly: IntArray, len: Int, x: Int): Int {
        var s = 0
        for (i in len - 1 downTo 0) s = mul(s, x) xor poly[i]
        return s
    }

    private fun syndromes(cw: IntArray, ecCount: Int): IntArray? {
        val s = IntArray(ecCount)
        var nonZero = false
        for (i in 0 until ecCount) {
            s[i] = evalHighFirst(cw, EXP[i])
            if (s[i] != 0) nonZero = true
        }
        return if (nonZero) s else null
    }

    /**
     * Corrects [codewords] (data followed by [ecCount] EC codewords, values 0..255) in place.
     * Returns false (leaving the array untouched) when the block is uncorrectable.
     */
    fun correct(codewords: IntArray, ecCount: Int): Boolean {
        val n = codewords.size
        if (ecCount == 0) return true
        if (ecCount < 0 || ecCount >= n || n > 255) return false
        for (c in codewords) if (c !in 0..255) return false

        val s = syndromes(codewords, ecCount) ?: return true

        // Berlekamp–Massey.
        val nn = ecCount
        var c = IntArray(nn + 1).also { it[0] = 1 }
        var b = IntArray(nn + 1).also { it[0] = 1 }
        var l = 0
        var m = 1
        var bScale = 1
        for (k in 0 until nn) {
            var d = s[k]
            for (i in 1..l) d = d xor mul(c[i], s[k - i])
            if (d == 0) {
                m++
                continue
            }
            val coef = div(d, bScale)
            if (2 * l <= k) {
                val t = c.copyOf()
                for (i in 0..nn - m) c[i + m] = c[i + m] xor mul(coef, b[i])
                l = k + 1 - l
                b = t
                bScale = d
                m = 1
            } else {
                for (i in 0..nn - m) c[i + m] = c[i + m] xor mul(coef, b[i])
                m++
            }
        }
        if (l == 0 || 2 * l > ecCount) return false
        val lambdaLen = l + 1
        // Sanity: the locator must truly have degree l.
        if (c[l] == 0) return false

        // Chien search over the n possible positions (position p = degree of x^p).
        val positions = IntArray(l)
        var found = 0
        for (p in 0 until n) {
            val xInv = EXP[(255 - p) % 255]
            if (evalLowFirst(c, lambdaLen, xInv) == 0) {
                if (found == l) return false
                positions[found++] = p
            }
        }
        if (found != l) return false

        // Ω(x) = S(x)·Λ(x) mod x^ecCount.
        val omega = IntArray(ecCount)
        for (i in 0 until ecCount) {
            var v = 0
            for (j in 0..minOf(i, l)) v = v xor mul(c[j], s[i - j])
            omega[i] = v
        }
        // Formal derivative Λ'(x): only odd-degree terms survive in characteristic 2.
        val deriv = IntArray(l)
        for (i in 0 until l) deriv[i] = if ((i + 1) and 1 == 1) c[i + 1] else 0

        val fixed = codewords.copyOf()
        for (p in positions) {
            val x = EXP[p]
            val xInv = EXP[(255 - p) % 255]
            val num = evalLowFirst(omega, ecCount, xInv)
            val den = evalLowFirst(deriv, l, xInv)
            if (den == 0) return false
            val magnitude = mul(x, div(num, den))
            val idx = n - 1 - p
            fixed[idx] = fixed[idx] xor magnitude
        }
        if (syndromes(fixed, ecCount) != null) return false
        System.arraycopy(fixed, 0, codewords, 0, n)
        return true
    }
}
