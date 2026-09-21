package com.pipidu.tiny1b.core.uvc

import com.pipidu.tiny1b.core.Tiny1BFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UvcProtocolTest {
    @Test
    fun assemblerBuildsFrameFromHeaderedPackets() {
        val received = ArrayList<ByteArray>()
        val assembler = UvcPayloadAssembler(8) { received += it }
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assembler.offer(uvcPacket(fid = 0, eof = false, data = payload.copyOfRange(0, 4)))
        assembler.offer(uvcPacket(fid = 0, eof = true, data = payload.copyOfRange(4, 8)))
        assertEquals(1, received.size)
        assertEquals(payload.toList(), received[0].toList())
        assertEquals(1L, assembler.completeFrames)
    }

    @Test
    fun assemblerDropsWhenFidTogglesMidFrame() {
        val received = ArrayList<ByteArray>()
        val assembler = UvcPayloadAssembler(8) { received += it }
        assembler.offer(uvcPacket(fid = 0, eof = false, data = byteArrayOf(1, 2, 3, 4)))
        assembler.offer(uvcPacket(fid = 1, eof = true, data = byteArrayOf(9, 9, 9, 9)))
        assertTrue(received.isEmpty())
        assertTrue(assembler.dropped >= 1)
    }

    @Test
    fun probeRoundTripFormatAndPayloadSize() {
        val probe = UvcProbe.empty(UvcProbe.SIZE_UVC11)
        probe.formatIndex = 1
        probe.frameIndex = 2
        probe.maxVideoFrameSize = Tiny1BFormat.UVC_FRAME_BYTES
        probe.maxPayloadTransferSize = 3072
        probe.frameInterval = 400000
        assertEquals(1, probe.formatIndex)
        assertEquals(2, probe.frameIndex)
        assertEquals(Tiny1BFormat.UVC_FRAME_BYTES, probe.maxVideoFrameSize)
        assertEquals(3072, probe.maxPayloadTransferSize)
        assertEquals(400000, probe.frameInterval)
    }

    @Test
    fun parserFindsYuy2AndPrefersBulk() {
        val parsed = UvcDescriptors.parse(sampleDescriptors(bulk = true))
        assertEquals(0x0BDA, parsed.vendorId)
        assertEquals(0x3901, parsed.productId)
        val plan = UvcDescriptors.plan(parsed, 256, 384, Tiny1BFormat.UVC_FRAME_BYTES)
        assertNotNull(plan)
        assertTrue(plan!!.bulk)
        assertEquals(1, plan.formatIndex)
        assertEquals(1, plan.frameIndex)
        assertEquals(256, plan.width)
        assertEquals(384, plan.height)
        assertEquals(0x81, plan.endpointAddress)
        assertEquals(0, plan.streamingAlt)
    }

    @Test
    fun parserFallsBackToLargestIsochronousAlt() {
        val parsed = UvcDescriptors.parse(sampleDescriptors(bulk = false))
        val plan = UvcDescriptors.plan(parsed, 256, 384, Tiny1BFormat.UVC_FRAME_BYTES)
        assertNotNull(plan)
        assertFalse(plan!!.bulk)
        assertEquals(1, plan.streamingAlt)
        assertEquals(3072, plan.maxPacketSize)
        assertTrue(plan.endpointAddress and 0x80 != 0)
    }

    private fun uvcPacket(fid: Int, eof: Boolean, data: ByteArray): ByteArray {
        var info = fid and 1
        if (eof) info = info or UvcPayloadAssembler.HDR_EOF
        return byteArrayOf(2, info.toByte()) + data
    }

    /**
     * Minimal device+config tree: VC if 0, VS if 1 with YUY2 256×384,
     * plus either bulk alt 0 or isoc alt 1 (wMaxPacketSize 0x1400 = 3×1024).
     */
    private fun sampleDescriptors(bulk: Boolean): ByteArray {
        val chunks = ArrayList<ByteArray>()
        chunks += byteArrayOf(
            18, 1, 0x00, 0x02, 0xEF.toByte(), 2, 1, 64,
            0xDA.toByte(), 0x0B, 0x01, 0x39, 0, 1, 0, 0, 0, 1,
        )
        val body = ArrayList<ByteArray>()
        body += byteArrayOf(9, 2, 0, 0, 2, 1, 0, 0x80.toByte(), 50)
        body += byteArrayOf(9, 4, 0, 0, 0, 14, 1, 0, 0)
        body += byteArrayOf(9, 4, 1, 0, if (bulk) 1 else 0, 14, 2, 0, 0)
        if (bulk) {
            body += byteArrayOf(7, 5, 0x81.toByte(), 2, 0x00, 0x02, 0)
        }
        val format = ByteArray(27)
        format[0] = 27
        format[1] = 0x24
        format[2] = 0x04
        format[3] = 1
        format[4] = 1
        GUID_YUY2.copyInto(format, 5)
        format[21] = 16
        format[22] = 1
        body += format
        val frame = ByteArray(30)
        frame[0] = 30
        frame[1] = 0x24
        frame[2] = 0x05
        frame[3] = 1
        put16(frame, 5, 256)
        put16(frame, 7, 384)
        put32(frame, 17, Tiny1BFormat.UVC_FRAME_BYTES)
        put32(frame, 21, 400000)
        frame[25] = 1
        put32(frame, 26, 400000)
        body += frame
        if (!bulk) {
            body += byteArrayOf(9, 4, 1, 1, 1, 14, 2, 0, 0)
            body += byteArrayOf(7, 5, 0x81.toByte(), 5, 0x00, 0x14, 1)
        }
        val total = body.sumOf { it.size }
        put16(body[0], 2, total)
        chunks.addAll(body)
        val out = ByteArray(chunks.sumOf { it.size })
        var o = 0
        for (c in chunks) {
            c.copyInto(out, o)
            o += c.size
        }
        return out
    }

    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v and 0xFF).toByte()
        b[i + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun put32(b: ByteArray, i: Int, v: Int) {
        put16(b, i, v and 0xFFFF)
        put16(b, i + 2, (v ushr 16) and 0xFFFF)
    }
}
