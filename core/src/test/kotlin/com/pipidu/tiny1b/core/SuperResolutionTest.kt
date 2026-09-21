package com.pipidu.tiny1b.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuperResolutionTest {
    @Test
    fun x2DoublesSpatialSize() {
        val planes = FrameParser.parseUvcFrame(SyntheticScene.uvcFrame())
        val off = SuperResolution.enhance(planes, IsrScale.OFF, Palettes.get(PaletteId.IRONBOW))
        val x2 = SuperResolution.enhance(planes, IsrScale.X2, Palettes.get(PaletteId.IRONBOW))
        assertEquals(planes.width, off.width)
        assertEquals(planes.width * 2, x2.width)
        assertEquals(planes.height * 2, x2.height)
        assertEquals(x2.width * x2.height, x2.argb.size)
        assertTrue(x2.argb.any { (it ushr 24) == 0xFF })
    }

    @Test
    fun x4QuadruplesSpatialSize() {
        val planes = FrameParser.parseUvcFrame(SyntheticScene.uvcFrame())
        val x4 = SuperResolution.enhance(planes, IsrScale.X4, Palettes.get(PaletteId.IRONBOW))
        assertEquals(planes.width * 4, x4.width)
        assertEquals(planes.height * 4, x4.height)
    }

    @Test
    fun bilinear2xKeepsHotCenter() {
        val src = floatArrayOf(
            0f, 0f, 0f, 0f,
            0f, 1f, 1f, 0f,
            0f, 1f, 1f, 0f,
            0f, 0f, 0f, 0f,
        )
        val dst = SuperResolution.bilinearScale(src, 4, 4, 2)
        assertEquals(8, kotlin.math.sqrt(dst.size.toDouble()).toInt())
        assertEquals(1f, dst[3 * 8 + 3], 1e-4f)
    }

    @Test
    fun kelvin16ConvertsAroundRoomTemp() {
        val raw = Tiny1BFormat.kelvin16FromCelsius(25f)
        val back = Tiny1BFormat.celsiusFromKelvin16(raw)
        assertTrue(kotlin.math.abs(back - 25f) < 0.1f)
    }
}
