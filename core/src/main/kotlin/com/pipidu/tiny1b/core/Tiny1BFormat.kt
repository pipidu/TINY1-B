package com.pipidu.tiny1b.core

/** Infiray Tiny1-B USB / frame geometry used by the product pipeline. */
object Tiny1BFormat {
    const val VENDOR_ID = 0x0BDA
    const val PRODUCT_ID = 0x3901

    /** UVC stream reports a stacked image+temp frame. */
    const val UVC_WIDTH = 256
    const val UVC_HEIGHT = 384

    const val SENSOR_WIDTH = 256
    const val SENSOR_HEIGHT = 192

    /** After 90° counter-clockwise rotation for portrait live view. */
    const val DISPLAY_WIDTH = SENSOR_HEIGHT
    const val DISPLAY_HEIGHT = SENSOR_WIDTH

    const val BYTES_PER_PIXEL = 2
    const val UVC_FRAME_BYTES = UVC_WIDTH * UVC_HEIGHT * BYTES_PER_PIXEL
    const val PLANE_BYTES = SENSOR_WIDTH * SENSOR_HEIGHT * BYTES_PER_PIXEL

    fun celsiusFromKelvin16(raw: Int): Float = raw / 16f - 273.15f

    fun kelvin16FromCelsius(celsius: Float): Int =
        ((celsius + 273.15f) * 16f).toInt().coerceIn(0, 65535)
}
