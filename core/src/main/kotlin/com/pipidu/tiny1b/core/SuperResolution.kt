package com.pipidu.tiny1b.core

enum class IsrScale(val labelZh: String, val factor: Int) {
    OFF("关闭", 1),
    X2("2× 超分", 2),
    X4("4× 超分", 4),
}

data class RenderedFrame(
    val width: Int,
    val height: Int,
    val argb: IntArray,
    val fused: FloatArray,
    val native: ThermalPlanes,
)

/**
 * Software ISR: bicubic upsample of temperature AGC, fused with YUYV luminance
 * detail (high-pass) so edges stay sharper than a plain stretch.
 */
object SuperResolution {
    fun enhance(
        planes: ThermalPlanes,
        scale: IsrScale,
        palette: Palette,
        denoise: Boolean = false,
    ): RenderedFrame {
        var tempNorm = TemperatureMaps.normalize(planes.kelvin16)
        var yNorm = FloatArray(planes.luminance.size) { i ->
            (planes.luminance[i] / 255f).coerceIn(0f, 1f)
        }
        if (denoise) {
            val cleaned = Denoise.apply(yNorm, tempNorm, planes.width, planes.height)
            yNorm = cleaned.first
            tempNorm = cleaned.second
        }
        var fusedW = planes.width
        var fusedH = planes.height
        var tempHr = tempNorm
        var yHr = yNorm
        var passes = when (scale) {
            IsrScale.OFF -> 0
            IsrScale.X2 -> 1
            IsrScale.X4 -> 2
        }
        repeat(passes) {
            tempHr = bicubic2x(tempHr, fusedW, fusedH)
            yHr = bicubic2x(yHr, fusedW, fusedH)
            fusedW *= 2
            fusedH *= 2
            yHr = unsharp(yHr, fusedW, fusedH, 0.55f)
        }
        val detail = if (passes == 0) {
            FloatArray(yHr.size)
        } else {
            val blur = boxBlur3(yHr, fusedW, fusedH)
            FloatArray(yHr.size) { i -> yHr[i] - blur[i] }
        }
        val fused = FloatArray(tempHr.size) { i ->
            (tempHr[i] * 0.78f + yHr[i] * 0.14f + detail[i] * 0.72f).coerceIn(0f, 1f)
        }
        val argb = IntArray(fused.size) { i -> palette.argb(fused[i]) }
        return RenderedFrame(fusedW, fusedH, argb, fused, planes)
    }

    internal fun bicubic2x(src: FloatArray, width: Int, height: Int): FloatArray {
        val nw = width * 2
        val nh = height * 2
        val dst = FloatArray(nw * nh)
        for (y in 0 until nh) {
            val fy = (y + 0.5f) / 2f - 0.5f
            val y0 = kotlin.math.floor(fy).toInt()
            val ty = fy - y0
            for (x in 0 until nw) {
                val fx = (x + 0.5f) / 2f - 0.5f
                val x0 = kotlin.math.floor(fx).toInt()
                val tx = fx - x0
                var acc = 0f
                for (j in -1..2) {
                    val wy = cubic(ty - j)
                    for (i in -1..2) {
                        acc += sample(src, width, height, x0 + i, y0 + j) * cubic(tx - i) * wy
                    }
                }
                dst[y * nw + x] = acc
            }
        }
        return dst
    }

    private fun cubic(t: Float): Float {
        val a = -0.5f
        val x = kotlin.math.abs(t)
        return when {
            x < 1f -> ((a + 2f) * x - (a + 3f)) * x * x + 1f
            x < 2f -> (((a * x - 5f * a) * x) + 8f * a) * x - 4f * a
            else -> 0f
        }
    }

    private fun sample(src: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        val xx = x.coerceIn(0, w - 1)
        val yy = y.coerceIn(0, h - 1)
        return src[yy * w + xx]
    }

    internal fun unsharp(src: FloatArray, w: Int, h: Int, amount: Float): FloatArray {
        val blur = boxBlur3(src, w, h)
        return FloatArray(src.size) { i ->
            (src[i] + amount * (src[i] - blur[i])).coerceIn(0f, 1f)
        }
    }

    internal fun boxBlur3(src: FloatArray, w: Int, h: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0f
                var n = 0
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx in 0 until w) {
                        acc += src[y * w + xx]
                        n++
                    }
                }
                tmp[y * w + x] = acc / n
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0f
                var n = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy in 0 until h) {
                        acc += tmp[yy * w + x]
                        n++
                    }
                }
                out[y * w + x] = acc / n
            }
        }
        return out
    }
}
