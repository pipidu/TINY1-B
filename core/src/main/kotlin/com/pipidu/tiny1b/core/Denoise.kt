package com.pipidu.tiny1b.core

/**
 * Software speckle / impulse denoise for the **display** path only.
 * Never mutates [ThermalPlanes.kelvin16]; measurement stays on the native grid.
 */
object Denoise {
    fun apply(
        luminance01: FloatArray,
        tempNorm: FloatArray,
        width: Int,
        height: Int,
        destY: FloatArray? = null,
        destT: FloatArray? = null,
    ): Pair<FloatArray, FloatArray> {
        // 5×5 median kills salt-and-pepper speckle on Y; 3×3 median on AGC temp
        // removes isolated false-color sparkles without smearing edges as much as a blur.
        val y = median(luminance01, width, height, radius = 2, dest = destY)
        val t = median(tempNorm, width, height, radius = 1, dest = destT)
        return y to t
    }

    internal fun median(
        src: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        dest: FloatArray? = null,
    ): FloatArray {
        if (radius <= 0) {
            val copy = if (dest != null && dest.size == src.size) dest else FloatArray(src.size)
            src.copyInto(copy)
            return copy
        }
        val dst = if (dest != null && dest.size == src.size) dest else FloatArray(src.size)
        val window = FloatArray((2 * radius + 1) * (2 * radius + 1))
        for (y in 0 until height) {
            for (x in 0 until width) {
                var n = 0
                val y0 = (y - radius).coerceAtLeast(0)
                val y1 = (y + radius).coerceAtMost(height - 1)
                val x0 = (x - radius).coerceAtLeast(0)
                val x1 = (x + radius).coerceAtMost(width - 1)
                for (yy in y0..y1) {
                    val row = yy * width
                    for (xx in x0..x1) {
                        window[n++] = src[row + xx]
                    }
                }
                dst[y * width + x] = selectMedian(window, n)
            }
        }
        return dst
    }

    /** In-place Floyd-Rivest-ish selection via insertion on a tiny window. */
    internal fun selectMedian(values: FloatArray, count: Int): Float {
        for (i in 1 until count) {
            val v = values[i]
            var j = i - 1
            while (j >= 0 && values[j] > v) {
                values[j + 1] = values[j]
                j--
            }
            values[j + 1] = v
        }
        return if (count % 2 == 1) {
            values[count / 2]
        } else {
            0.5f * (values[count / 2 - 1] + values[count / 2])
        }
    }
}
