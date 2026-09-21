package com.pipidu.tiny1b.core.uvc

/**
 * UVC Video Streaming PROBE/COMMIT control (26, 34, or 48 bytes).
 */
class UvcProbe(val bytes: ByteArray) {
    val size: Int get() = bytes.size

    var formatIndex: Int
        get() = u8(2)
        set(value) { bytes[2] = value.toByte() }

    var frameIndex: Int
        get() = u8(3)
        set(value) { bytes[3] = value.toByte() }

    var frameInterval: Int
        get() = u32(4)
        set(value) { put32(4, value) }

    var maxVideoFrameSize: Int
        get() = u32(18)
        set(value) { put32(18, value) }

    var maxPayloadTransferSize: Int
        get() = u32(22)
        set(value) { put32(22, value) }

    fun hintFrameInterval() {
        bytes[0] = 0x01
        bytes[1] = 0x00
    }

    fun copy(): UvcProbe = UvcProbe(bytes.copyOf())

    private fun u8(i: Int): Int = if (i < bytes.size) bytes[i].toInt() and 0xFF else 0

    private fun u32(i: Int): Int {
        if (i + 3 >= bytes.size) return 0
        return (bytes[i].toInt() and 0xFF) or
            ((bytes[i + 1].toInt() and 0xFF) shl 8) or
            ((bytes[i + 2].toInt() and 0xFF) shl 16) or
            ((bytes[i + 3].toInt() and 0xFF) shl 24)
    }

    private fun put32(i: Int, value: Int) {
        if (i + 3 >= bytes.size) return
        bytes[i] = (value and 0xFF).toByte()
        bytes[i + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[i + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[i + 3] = ((value shr 24) and 0xFF).toByte()
    }

    companion object {
        const val SIZE_UVC10 = 26
        const val SIZE_UVC11 = 34
        const val SIZE_UVC15 = 48

        fun empty(size: Int): UvcProbe = UvcProbe(ByteArray(size.coerceIn(SIZE_UVC10, SIZE_UVC15)))

        /**
         * PROBE/COMMIT buffer sizes to try. Prefer GET_LEN, then the three
         * standard UVC lengths so a Tiny1-B that lies about GET_LEN still
         * negotiates.
         */
        fun candidateSizes(reportedLen: Int): List<Int> {
            val preferred = when {
                reportedLen >= SIZE_UVC15 -> SIZE_UVC15
                reportedLen >= SIZE_UVC11 -> SIZE_UVC11
                reportedLen >= SIZE_UVC10 -> SIZE_UVC10
                else -> SIZE_UVC10
            }
            return linkedSetOf(preferred, SIZE_UVC10, SIZE_UVC11, SIZE_UVC15).toList()
        }
    }
}
