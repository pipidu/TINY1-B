package com.pipidu.tiny1b.core

/**
 * Software frame generation (帧生成). The vendor demo has no interpolate /
 * synthesize UI — native UVC fps only. This product inserts extra display
 * frames by temporally blending the last two native [ThermalPlanes].
 *
 * History is capped at two native planes plus one reusable blend destination
 * (see the live engine). Measurement samples the blended Kelvin grid so
 * markers move smoothly with the extra frames.
 *
 * Pacing: extra blends are scheduled **across** the native period (Handler /
 * Choreographer), not dumped when the next UVC plane arrives. 2× of 25 fps
 * → ~50 display fps; 3× of 25 fps → ~75, capped at the panel refresh.
 */
enum class FrameGenScale(val labelZh: String, val extraFrames: Int) {
    OFF("关闭", 0),
    X2("2× 插帧", 1),
    X3("3× 插帧", 2),
}

object FrameGeneration {
    const val DEFAULT_NATIVE_MS = 40L

    /**
     * How many extra (non-native) display frames to emit per native interval.
     * Never skip a ~25 fps (40 ms) module. Skip only when native fps already
     * meets the panel refresh. Cap so equal time steps are not faster than vsync.
     */
    fun pacedExtraFrames(
        requestedExtra: Int,
        nativeIntervalMs: Long,
        refreshHz: Float,
    ): Int {
        if (requestedExtra <= 0 || nativeIntervalMs <= 0L) return 0
        val hz = refreshHz.coerceIn(30f, 120f)
        val nativeFps = 1000f / nativeIntervalMs.toFloat()
        if (nativeFps >= hz - 0.5f) return 0
        val minStepMs = 1000f / hz
        val maxSlots = (nativeIntervalMs / minStepMs).toInt().coerceAtLeast(1)
        val slots = (requestedExtra + 1).coerceAtMost(maxSlots)
        return (slots - 1).coerceAtLeast(0)
    }

    fun blendT(stepIndex: Int, extras: Int): Float {
        val total = extras + 1
        if (total <= 1) return 1f
        return stepIndex.coerceIn(1, extras).toFloat() / total.toFloat()
    }

    /**
     * Which equally spaced display step is due at [elapsedMs] into a native
     * period. `0` = nothing yet; `1..extras` = blend k of extras; `extras+1` =
     * show the new native plane.
     */
    fun dueDisplayStep(elapsedMs: Long, nativeIntervalMs: Long, extras: Int): Int {
        val total = extras + 1
        if (extras <= 0 || nativeIntervalMs <= 0L) return total.coerceAtLeast(1)
        if (elapsedMs >= nativeIntervalMs) return total
        var due = 0
        for (k in 1..total) {
            val at = nativeIntervalMs * k / total
            if (elapsedMs >= at) due = k else break
        }
        return due
    }

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
