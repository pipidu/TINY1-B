package com.pipidu.tiny1b.core.uvc

/**
 * Assembles UVC payloads (header + data) into fixed-size uncompressed frames.
 *
 * Header: bHeaderLength, bmHeaderInfo (FID bit0, EOF bit1, ERR bit6).
 */
class UvcPayloadAssembler(
    private val frameSize: Int,
    private val onFrame: (ByteArray) -> Unit,
) {
    private val frame = ByteArray(frameSize)
    private var filled = 0
    private var currentFid: Int? = null
    var completeFrames: Long = 0
        private set
    var dropped: Long = 0
        private set

    fun offer(packet: ByteArray, length: Int = packet.size) {
        if (length < 2) return
        val headerLen = packet[0].toInt() and 0xFF
        if (headerLen < 2 || headerLen > length) return
        val info = packet[1].toInt() and 0xFF
        if (info and HDR_ERR != 0) {
            resetFrame()
            dropped++
            return
        }
        val fid = info and HDR_FID
        if (currentFid != null && fid != currentFid && filled > 0) {
            dropped++
            filled = 0
        }
        currentFid = fid
        val dataLen = length - headerLen
        if (dataLen > 0 && filled < frameSize) {
            val copy = minOf(dataLen, frameSize - filled)
            System.arraycopy(packet, headerLen, frame, filled, copy)
            filled += copy
        }
        val eof = info and HDR_EOF != 0
        if (eof || filled >= frameSize) {
            if (filled >= frameSize) {
                onFrame(frame.copyOf())
                completeFrames++
            } else if (eof && filled > 0) {
                dropped++
            }
            resetFrame()
        }
    }

    fun reset() {
        resetFrame()
    }

    private fun resetFrame() {
        filled = 0
        currentFid = null
    }

    companion object {
        const val HDR_FID = 0x01
        const val HDR_EOF = 0x02
        const val HDR_ERR = 0x40
    }
}
