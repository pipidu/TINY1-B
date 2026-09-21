package com.pipidu.tiny1b.core

/** Synthetic Tiny1-B-sized scene for unit tests and optional on-device UI preview. */
object SyntheticScene {
    fun uvcFrame(
        width: Int = Tiny1BFormat.UVC_WIDTH,
        height: Int = Tiny1BFormat.UVC_HEIGHT,
        t: Float = 0f,
    ): ByteArray {
        require(width == Tiny1BFormat.UVC_WIDTH && height == Tiny1BFormat.UVC_HEIGHT)
        val frame = ByteArray(Tiny1BFormat.UVC_FRAME_BYTES)
        val sw = Tiny1BFormat.SENSOR_WIDTH
        val sh = Tiny1BFormat.SENSOR_HEIGHT
        val hotX = 80 + (18 * kotlin.math.sin(t)).toInt()
        val hotY = 70 + (12 * kotlin.math.cos(t * 0.7f)).toInt()
        val coldX = 190
        val coldY = 140
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val i = y * sw + x
                val gx = x / sw.toFloat()
                val gy = y / sh.toFloat()
                val base = 20f + gx * 8f + gy * 4f
                val hot = 42f * gauss(x, y, hotX, hotY, 28f)
                val cold = -18f * gauss(x, y, coldX, coldY, 22f)
                val celsius = base + hot + cold
                val yVal = (16 + celsius * 4f).toInt().coerceIn(0, 255)
                frame[i * 2] = yVal.toByte()
                frame[i * 2 + 1] = 128.toByte()
                val k16 = Tiny1BFormat.kelvin16FromCelsius(celsius)
                val to = Tiny1BFormat.PLANE_BYTES + i * 2
                frame[to] = (k16 and 0xFF).toByte()
                frame[to + 1] = ((k16 shr 8) and 0xFF).toByte()
            }
        }
        return frame
    }

    private fun gauss(x: Int, y: Int, cx: Int, cy: Int, sigma: Float): Float {
        val dx = (x - cx).toFloat()
        val dy = (y - cy).toFloat()
        return kotlin.math.exp(-(dx * dx + dy * dy) / (2f * sigma * sigma))
    }
}
