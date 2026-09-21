package com.pipidu.tiny1b.core

/** Infiray Tiny1-B USB / frame geometry used by the product pipeline. */
object Tiny1BFormat {
    const val VENDOR_ID = 0x0BDA
    const val PRODUCT_ID = 0x3901

    /** UVC stream is stacked image+temp in one YUYV buffer. */
    const val UVC_WIDTH = 256
    const val UVC_HEIGHT = 384

    /**
     * Demo `onFrame` split (MainActivity imageWidth/imageHeight):
     * `imageWidth = cameraHeight/2 = 192`, `imageHeight = cameraWidth = 256`.
     * First half of the 256×384 YUYV frame is already portrait YUYV 192×256;
     * second half is Kelvin-16 at the same size. Do **not** treat the half-frame
     * as 256×192 and rotate — that wraps stride (LCM 192/256 = 4 bands + 64 px side stripes).
     */
    const val PLANE_WIDTH = 192
    const val PLANE_HEIGHT = 256

    const val SENSOR_WIDTH = PLANE_WIDTH
    const val SENSOR_HEIGHT = PLANE_HEIGHT

    const val DISPLAY_WIDTH = PLANE_WIDTH
    const val DISPLAY_HEIGHT = PLANE_HEIGHT

    const val BYTES_PER_PIXEL = 2
    const val UVC_FRAME_BYTES = UVC_WIDTH * UVC_HEIGHT * BYTES_PER_PIXEL
    const val PLANE_BYTES = PLANE_WIDTH * PLANE_HEIGHT * BYTES_PER_PIXEL

    fun celsiusFromKelvin16(raw: Int): Float = raw / 16f - 273.15f

    fun kelvin16FromCelsius(celsius: Float): Int =
        ((celsius + 273.15f) * 16f).toInt().coerceIn(0, 65535)
}
