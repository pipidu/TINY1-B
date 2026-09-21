package com.pipidu.tiny1b.device

import android.hardware.usb.UsbDeviceConnection

/**
 * Tiny1-B vendor control transfers (UVC extension unit), same opcodes as the
 * Infiray Android JNI demo used for shutter / KB calibration.
 */
class Tiny1BCommands(private val connection: UsbDeviceConnection) {
    fun manualShutter() {
        connection.controlTransfer(0x41, 0x20, 0x0000, 0x0345, null, 0, TIMEOUT_MS)
    }

    fun setKbCalibrate(enabled: Boolean) {
        val value = if (enabled) 1 else 0
        connection.controlTransfer(0x41, 0x20, value, 0x0341, null, 0, TIMEOUT_MS)
    }

    fun getShutterMaxTime(): Int {
        val data = ByteArray(1)
        val n = connection.controlTransfer(0xC1, 0x19, 0x0103, 0x038A, data, 1, TIMEOUT_MS)
        return if (n > 0) data[0].toInt() and 0xFF else -1
    }

    fun setShutterMaxTime(seconds: Int) {
        val data = byteArrayOf(seconds.coerceIn(1, 120).toByte())
        connection.controlTransfer(0x41, 0x20, 0x0103, 0x03C4, data, 1, TIMEOUT_MS)
    }

    companion object {
        private const val TIMEOUT_MS = 1000
    }
}
