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
 * Live ISR: fuse temperature AGC with Y detail at native resolution, then
 * bilinear-upsample (2× or 4×). The old 4×4 bicubic-per-plane path was too
 * slow for Tiny1-B's ~23 fps stream.
 *
 * Measurement still samples [RenderedFrame.native], not the upscaled pixels.
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
        val blurY = boxBlur3(yNorm, planes.width, planes.height)
        val fusedNative = FloatArray(tempNorm.size) { i ->
            val detail = yNorm[i] - blurY[i]
            (tempNorm[i] * 0.82f + yNorm[i] * 0.10f + detail * 0.55f).coerceIn(0f, 1f)
        }
        val factor = scale.factor
        val fused = if (factor == 1) {
            fusedNative
        } else {
            bilinearScale(fusedNative, planes.width, planes.height, factor)
        }
        val outW = planes.width * factor
        val outH = planes.height * factor
        val lut = palette.lut
        val argb = IntArray(fused.size) { i ->
            val idx = (fused[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            lut[idx]
        }
        return RenderedFrame(outW, outH, argb, fused, planes)
    }

    internal fun bilinearScale(src: FloatArray, width: Int, height: Int, factor: Int): FloatArray {
        require(factor >= 2)
        val nw = width * factor
        val nh = height * factor
        val dst = FloatArray(nw * nh)
        val inv = 1f / factor
        for (y in 0 until nh) {
            val fy = (y + 0.5f) * inv - 0.5f
            val y0 = fy.toInt().coerceIn(0, height - 1)
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            val ty = (fy - y0).coerceIn(0f, 1f)
            val row0 = y0 * width
            val row1 = y1 * width
            val dstRow = y * nw
            for (x in 0 until nw) {
                val fx = (x + 0.5f) * inv - 0.5f
                val x0 = fx.toInt().coerceIn(0, width - 1)
                val x1 = (x0 + 1).coerceAtMost(width - 1)
                val tx = (fx - x0).coerceIn(0f, 1f)
                val v00 = src[row0 + x0]
                val v10 = src[row0 + x1]
                val v01 = src[row1 + x0]
                val v11 = src[row1 + x1]
                val top = v00 + (v10 - v00) * tx
                val bot = v01 + (v11 - v01) * tx
                dst[dstRow + x] = top + (bot - top) * ty
            }
        }
        return dst
    }

    internal fun boxBlur3(src: FloatArray, w: Int, h: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0f
                var n = 0
                val x0 = (x - 1).coerceAtLeast(0)
                val x1 = (x + 1).coerceAtMost(w - 1)
                for (xx in x0..x1) {
                    acc += src[row + xx]
                    n++
                }
                tmp[row + x] = acc / n
            }
        }
        for (y in 0 until h) {
            val y0 = (y - 1).coerceAtLeast(0)
            val y1 = (y + 1).coerceAtMost(h - 1)
            for (x in 0 until w) {
                var acc = 0f
                var n = 0
                for (yy in y0..y1) {
                    acc += tmp[yy * w + x]
                    n++
                }
                out[y * w + x] = acc / n
            }
        }
        return out
    }
}
