package com.pipidu.tiny1b.core

data class ThermalPlanes(
    val width: Int,
    val height: Int,
    val luminance: FloatArray,
    val kelvin16: IntArray,
) {
    val pixelCount: Int get() = width * height

    fun copyPlanes(): ThermalPlanes = ThermalPlanes(
        width = width,
        height = height,
        luminance = luminance.copyOf(),
        kelvin16 = kelvin16.copyOf(),
    )
}

object FrameParser {
    /**
     * Vendor demo path: `arraycopy(frame, 0, image, 0, length/2)` then treat that
     * plane as YUYV [PLANE_WIDTH]×[PLANE_HEIGHT] (192×256). Temperature is the
     * second half as little-endian Kelvin-16 at the same geometry. Palette, ISR,
     * denoise, and measurement all use this native grid — not the raw YUYV as RGB.
     */
    fun parseUvcFrame(
        frame: ByteArray,
        luminance: FloatArray? = null,
        kelvin16: IntArray? = null,
    ): ThermalPlanes {
        require(frame.size >= Tiny1BFormat.UVC_FRAME_BYTES) {
            "Tiny1-B UVC frame must be ${Tiny1BFormat.UVC_FRAME_BYTES} bytes, was ${frame.size}"
        }
        val w = Tiny1BFormat.PLANE_WIDTH
        val h = Tiny1BFormat.PLANE_HEIGHT
        val count = w * h
        val y = if (luminance != null && luminance.size == count) luminance else FloatArray(count)
        val k = if (kelvin16 != null && kelvin16.size == count) kelvin16 else IntArray(count)
        val tempOffset = Tiny1BFormat.PLANE_BYTES
        for (i in 0 until count) {
            y[i] = (frame[i * 2].toInt() and 0xFF).toFloat()
            val lo = frame[tempOffset + i * 2].toInt() and 0xFF
            val hi = frame[tempOffset + i * 2 + 1].toInt() and 0xFF
            k[i] = lo or (hi shl 8)
        }
        return ThermalPlanes(
            width = w,
            height = h,
            luminance = y,
            kelvin16 = k,
        )
    }

    fun applyOrientation(
        planes: ThermalPlanes,
        rotation: DisplayRotation,
        mirror: Boolean,
    ): ThermalPlanes {
        var out = when (rotation) {
            DisplayRotation.DEG_0 -> planes
            DisplayRotation.DEG_90 -> rotate90Cw(planes)
            DisplayRotation.DEG_180 -> rotate180(planes)
            DisplayRotation.DEG_270 -> rotate90Ccw(planes)
        }
        if (mirror) out = mirrorHorizontal(out)
        return out
    }

    fun rotate90Ccw(planes: ThermalPlanes): ThermalPlanes = ThermalPlanes(
        width = planes.height,
        height = planes.width,
        luminance = rotate90Ccw(planes.luminance, planes.width, planes.height),
        kelvin16 = rotate90Ccw(planes.kelvin16, planes.width, planes.height),
    )

    fun rotate90Cw(planes: ThermalPlanes): ThermalPlanes = ThermalPlanes(
        width = planes.height,
        height = planes.width,
        luminance = rotate90Cw(planes.luminance, planes.width, planes.height),
        kelvin16 = rotate90Cw(planes.kelvin16, planes.width, planes.height),
    )

    fun rotate180(planes: ThermalPlanes): ThermalPlanes = ThermalPlanes(
        width = planes.width,
        height = planes.height,
        luminance = rotate180(planes.luminance, planes.width, planes.height),
        kelvin16 = rotate180(planes.kelvin16, planes.width, planes.height),
    )

    fun rotate90Ccw(src: FloatArray, width: Int, height: Int): FloatArray {
        val dstW = height
        val dstH = width
        val dst = FloatArray(dstW * dstH)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val nx = y
                val ny = width - 1 - x
                dst[ny * dstW + nx] = src[y * width + x]
            }
        }
        return dst
    }

    fun rotate90Ccw(src: IntArray, width: Int, height: Int): IntArray {
        val dstW = height
        val dstH = width
        val dst = IntArray(dstW * dstH)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val nx = y
                val ny = width - 1 - x
                dst[ny * dstW + nx] = src[y * width + x]
            }
        }
        return dst
    }

    fun rotate90Cw(src: FloatArray, width: Int, height: Int): FloatArray {
        val dstW = height
        val dstH = width
        val dst = FloatArray(dstW * dstH)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val nx = height - 1 - y
                val ny = x
                dst[ny * dstW + nx] = src[y * width + x]
            }
        }
        return dst
    }

    fun rotate90Cw(src: IntArray, width: Int, height: Int): IntArray {
        val dstW = height
        val dstH = width
        val dst = IntArray(dstW * dstH)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val nx = height - 1 - y
                val ny = x
                dst[ny * dstW + nx] = src[y * width + x]
            }
        }
        return dst
    }

    fun rotate180(src: FloatArray, width: Int, height: Int): FloatArray {
        val dst = FloatArray(src.size)
        val last = src.size - 1
        for (i in src.indices) dst[last - i] = src[i]
        return dst
    }

    fun rotate180(src: IntArray, width: Int, height: Int): IntArray {
        val dst = IntArray(src.size)
        val last = src.size - 1
        for (i in src.indices) dst[last - i] = src[i]
        return dst
    }

    fun mirrorHorizontal(planes: ThermalPlanes): ThermalPlanes {
        val w = planes.width
        val h = planes.height
        val y = FloatArray(planes.pixelCount)
        val t = IntArray(planes.pixelCount)
        for (row in 0 until h) {
            for (x in 0 until w) {
                val src = row * w + x
                val dst = row * w + (w - 1 - x)
                y[dst] = planes.luminance[src]
                t[dst] = planes.kelvin16[src]
            }
        }
        return ThermalPlanes(w, h, y, t)
    }
}
