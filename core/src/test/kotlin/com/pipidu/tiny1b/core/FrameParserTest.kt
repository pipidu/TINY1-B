package com.pipidu.tiny1b.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class FrameParserTest {
    @Test
    fun parseSplitsImageAndTemperatureAsDemo192x256() {
        val frame = SyntheticScene.uvcFrame()
        val planes = FrameParser.parseUvcFrame(frame)
        assertEquals(192, planes.width)
        assertEquals(256, planes.height)
        assertEquals(Tiny1BFormat.DISPLAY_WIDTH, planes.width)
        assertEquals(Tiny1BFormat.DISPLAY_HEIGHT, planes.height)
        assertEquals(planes.width * planes.height, planes.kelvin16.size)
        val stats = TemperatureMaps.stats(planes.kelvin16, planes.width, planes.height)
        assertTrue(stats.max.celsius > stats.min.celsius + 10f)
        assertTrue(stats.max.celsius in 20f..80f)
    }

    @Test
    fun markerStaysAtNative192x256Coordinate() {
        val frame = ByteArray(Tiny1BFormat.UVC_FRAME_BYTES)
        val w = Tiny1BFormat.PLANE_WIDTH
        val h = Tiny1BFormat.PLANE_HEIGHT
        val room = Tiny1BFormat.kelvin16FromCelsius(21f)
        for (i in 0 until w * h) {
            frame[i * 2] = 16
            frame[i * 2 + 1] = 128.toByte()
            val to = Tiny1BFormat.PLANE_BYTES + i * 2
            frame[to] = (room and 0xFF).toByte()
            frame[to + 1] = ((room shr 8) and 0xFF).toByte()
        }
        val x = 40
        val y = 80
        val i = y * w + x
        frame[i * 2] = 200.toByte()
        val hot = Tiny1BFormat.kelvin16FromCelsius(42f)
        frame[Tiny1BFormat.PLANE_BYTES + i * 2] = (hot and 0xFF).toByte()
        frame[Tiny1BFormat.PLANE_BYTES + i * 2 + 1] = ((hot shr 8) and 0xFF).toByte()

        val planes = FrameParser.parseUvcFrame(frame)
        assertEquals(200f, planes.luminance[y * w + x])
        assertEquals(42f, Tiny1BFormat.celsiusFromKelvin16(planes.kelvin16[y * w + x]), 0.1f)
        assertTrue(abs(Tiny1BFormat.celsiusFromKelvin16(planes.kelvin16[0]) - 21f) < 0.2f)
    }

    @Test
    fun treatingHalfFrameAs256x192AndRotatingScramblesMarker() {
        val frame = ByteArray(Tiny1BFormat.UVC_FRAME_BYTES)
        val w = Tiny1BFormat.PLANE_WIDTH
        val x = 40
        val y = 80
        val i = y * w + x
        frame[i * 2] = 200.toByte()
        val planes = FrameParser.parseUvcFrame(frame)
        val wrong = FrameParser.rotate90Ccw(
            FloatArray(256 * 192) { idx -> (frame[idx * 2].toInt() and 0xFF).toFloat() },
            256,
            192,
        )
        assertEquals(200f, planes.luminance[y * w + x])
        assertNotEquals(200f, wrong[y * w + x])
    }

    @Test
    fun applyOrientation90CwSwapsSizeAndMovesMarker() {
        val src = ThermalPlanes(
            width = 3,
            height = 2,
            luminance = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f),
            kelvin16 = intArrayOf(1, 2, 3, 4, 5, 6),
        )
        val rotated = FrameParser.applyOrientation(src, DisplayRotation.DEG_90, mirror = false)
        assertEquals(2, rotated.width)
        assertEquals(3, rotated.height)
        // 1 2 3      4 1
        // 4 5 6  ->  5 2
        //            6 3
        assertEquals(listOf(4f, 1f, 5f, 2f, 6f, 3f), rotated.luminance.toList())
    }

    @Test
    fun rotate90CcwMovesOriginToBottomLeft() {
        val src = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
        val dst = FrameParser.rotate90Ccw(src, 3, 2)
        // 1 2 3      3 6
        // 4 5 6  ->  2 5
        //            1 4
        assertEquals(listOf(3f, 6f, 2f, 5f, 1f, 4f), dst.toList())
    }
}
