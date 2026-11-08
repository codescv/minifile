package com.minifile.explorer.core.qr

/**
 * Minimal pure-JVM 2D bit matrix. `true` means a dark (black) pixel / module.
 * Coordinates are (x = column, y = row).
 */
class QrBitMatrix(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Invalid dimensions ${width}x$height" }
    }

    private val bits = BooleanArray(width * height)

    operator fun get(x: Int, y: Int): Boolean = bits[y * width + x]

    operator fun set(x: Int, y: Int, v: Boolean) {
        bits[y * width + x] = v
    }

    /** Bounds-safe read: anything outside the matrix is reported as light. */
    internal fun getOrLight(x: Int, y: Int): Boolean =
        x in 0 until width && y in 0 until height && bits[y * width + x]
}
