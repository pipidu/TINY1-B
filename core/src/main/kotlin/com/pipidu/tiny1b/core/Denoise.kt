package com.pipidu.tiny1b.core

/**
 * Software speckle denoise for the **display** path only (native 192×256,
 * before ISR). Never mutates [ThermalPlanes.kelvin16]; measurement stays on
 * the unfiltered native grid.
 *
 * Live path: separable 3-tap median (horizontal then vertical), not a 5×5
 * insertion-sort median. Strength 0–100 blends with the unfiltered plane;
 * ≥80 runs a second Y pass. Reuses caller buffers — no per-frame alloc
 * when [destY] / [destT] / [tmp] are sized to the plane.
 */
object Denoise {
    fun mix(amount: Int): Float = amount.coerceIn(0, 100) / 100f

    fun yPasses(amount: Int): Int = when {
        amount <= 0 -> 0
        amount < 80 -> 1
        else -> 2
    }

    fun apply(
        luminance01: FloatArray,
        tempNorm: FloatArray,
        width: Int,
        height: Int,
        amount: Int = 100,
        destY: FloatArray? = null,
        destT: FloatArray? = null,
        tmp: FloatArray? = null,
    ): Pair<FloatArray, FloatArray> {
        val n = width * height
        val yOut = buf(destY, n)
        val tOut = buf(destT, n)
        val work = buf(tmp, n)
        val a = mix(amount)
        if (a <= 0f) {
            luminance01.copyInto(yOut)
            tempNorm.copyInto(tOut)
            return yOut to tOut
        }
        val passes = yPasses(amount)
        var ySrc = luminance01
        repeat(passes) {
            separableMedian3(ySrc, width, height, yOut, work)
            ySrc = yOut
        }
        if (a < 1f) {
            blend(luminance01, yOut, a, yOut)
        }
        separableMedian3(tempNorm, width, height, tOut, work)
        if (a < 1f) {
            blend(tempNorm, tOut, a, tOut)
        }
        return yOut to tOut
    }

    internal fun separableMedian3(
        src: FloatArray,
        width: Int,
        height: Int,
        dest: FloatArray,
        tmp: FloatArray,
    ) {
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val xl = (x - 1).coerceAtLeast(0)
                val xr = (x + 1).coerceAtMost(width - 1)
                tmp[row + x] = median3(src[row + xl], src[row + x], src[row + xr])
            }
        }
        for (y in 0 until height) {
            val yu = (y - 1).coerceAtLeast(0)
            val yd = (y + 1).coerceAtMost(height - 1)
            val row = y * width
            val rowU = yu * width
            val rowD = yd * width
            for (x in 0 until width) {
                dest[row + x] = median3(tmp[rowU + x], tmp[row + x], tmp[rowD + x])
            }
        }
    }

    internal fun median3(a: Float, b: Float, c: Float): Float =
        maxOf(minOf(a, b), minOf(maxOf(a, b), c))

    private fun blend(src: FloatArray, filtered: FloatArray, amount: Float, dest: FloatArray) {
        val iw = 1f - amount
        for (i in src.indices) {
            dest[i] = src[i] * iw + filtered[i] * amount
        }
    }

    private fun buf(existing: FloatArray?, n: Int): FloatArray =
        if (existing != null && existing.size == n) existing else FloatArray(n)
}
