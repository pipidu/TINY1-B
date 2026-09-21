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
    fun parseUvcFrame(frame: ByteArray): ThermalPlanes {
        require(frame.size >= Tiny1BFormat.UVC_FRAME_BYTES) {
            "Tiny1-B UVC frame must be ${Tiny1BFormat.UVC_FRAME_BYTES} bytes, was ${frame.size}"
        }
        val sensorW = Tiny1BFormat.SENSOR_WIDTH
        val sensorH = Tiny1BFormat.SENSOR_HEIGHT
        val count = sensorW * sensorH
        val luminance = FloatArray(count)
        val kelvin16 = IntArray(count)
        val image = frame
        val tempOffset = Tiny1BFormat.PLANE_BYTES
        for (i in 0 until count) {
            luminance[i] = (image[i * 2].toInt() and 0xFF).toFloat()
            val lo = frame[tempOffset + i * 2].toInt() and 0xFF
            val hi = frame[tempOffset + i * 2 + 1].toInt() and 0xFF
            kelvin16[i] = lo or (hi shl 8)
        }
        val rotatedY = rotate90Ccw(luminance, sensorW, sensorH)
        val rotatedT = rotate90Ccw(kelvin16, sensorW, sensorH)
        return ThermalPlanes(
            width = Tiny1BFormat.DISPLAY_WIDTH,
            height = Tiny1BFormat.DISPLAY_HEIGHT,
            luminance = rotatedY,
            kelvin16 = rotatedT,
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
