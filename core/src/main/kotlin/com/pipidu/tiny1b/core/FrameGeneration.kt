package com.pipidu.tiny1b.core

/**
 * Software frame generation (帧生成). The vendor demo has no interpolate /
 * synthesize UI — native UVC fps only. This product inserts extra display
 * frames by temporally blending the last two native [ThermalPlanes].
 *
 * History is capped at two native planes plus one reusable blend destination
 * (see the live engine). Measurement samples the blended Kelvin grid so
 * markers move smoothly with the extra frames.
 */
enum class FrameGenScale(val labelZh: String, val extraFrames: Int) {
    OFF("关闭", 0),
    X2("2× 插帧", 1),
    X3("3× 插帧", 2),
}

object FrameGeneration {
    /** Skip interpolation when the module is already sending frames this fast. */
    const val FAST_NATIVE_MS = 35L

    fun blend(
        prev: ThermalPlanes,
        next: ThermalPlanes,
        t: Float,
        dst: ThermalPlanes? = null,
    ): ThermalPlanes {
        require(prev.width == next.width && prev.height == next.height) {
            "frame-gen blend size mismatch ${prev.width}x${prev.height} vs ${next.width}x${next.height}"
        }
        val n = prev.pixelCount
        val w = t.coerceIn(0f, 1f)
        val iw = 1f - w
        val lum = if (dst != null && dst.luminance.size == n) dst.luminance else FloatArray(n)
        val kel = if (dst != null && dst.kelvin16.size == n) dst.kelvin16 else IntArray(n)
        for (i in 0 until n) {
            lum[i] = prev.luminance[i] * iw + next.luminance[i] * w
            kel[i] = (prev.kelvin16[i] * iw + next.kelvin16[i] * w + 0.5f).toInt()
        }
        return if (
            dst != null &&
            dst.width == prev.width &&
            dst.height == prev.height &&
            dst.luminance === lum &&
            dst.kelvin16 === kel
        ) {
            dst
        } else {
            ThermalPlanes(prev.width, prev.height, lum, kel)
        }
    }
}
