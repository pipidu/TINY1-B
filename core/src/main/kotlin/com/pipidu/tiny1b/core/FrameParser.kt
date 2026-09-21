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
    fun parseUvcFrame(frame: ByteArray): ThermalPlanes {
        require(frame.size >= Tiny1BFormat.UVC_FRAME_BYTES) {
            "Tiny1-B UVC frame must be ${Tiny1BFormat.UVC_FRAME_BYTES} bytes, was ${frame.size}"
        }
        val w = Tiny1BFormat.PLANE_WIDTH
        val h = Tiny1BFormat.PLANE_HEIGHT
        val count = w * h
        val luminance = FloatArray(count)
        val kelvin16 = IntArray(count)
        val tempOffset = Tiny1BFormat.PLANE_BYTES
        for (i in 0 until count) {
            // YUYV: Y0 U Y1 V — luminance is the even bytes, same as the demo's Y.
            luminance[i] = (frame[i * 2].toInt() and 0xFF).toFloat()
            val lo = frame[tempOffset + i * 2].toInt() and 0xFF
            val hi = frame[tempOffset + i * 2 + 1].toInt() and 0xFF
            kelvin16[i] = lo or (hi shl 8)
        }
        return ThermalPlanes(
            width = w,
            height = h,
            luminance = luminance,
            kelvin16 = kelvin16,
        )
    }

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
