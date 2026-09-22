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
    fun scratchReusesArgbAcrossFrames() {
        val planes = FrameParser.parseUvcFrame(SyntheticScene.uvcFrame())
        val scratch = IspScratch()
        val a = SuperResolution.enhance(planes, IsrScale.X2, Palettes.get(PaletteId.IRONBOW), scratch = scratch)
        val argb = a.argb
        val fused = a.fused
        val b = SuperResolution.enhance(planes, IsrScale.X2, Palettes.get(PaletteId.IRONBOW), scratch = scratch)
        assertTrue(argb === b.argb)
        assertTrue(fused === b.fused)
        assertEquals(planes.width * 2, b.width)
    }

    @Test
    fun kelvin16ConvertsAroundRoomTemp() {
        val raw = Tiny1BFormat.kelvin16FromCelsius(25f)
        val back = Tiny1BFormat.celsiusFromKelvin16(raw)
        assertTrue(kotlin.math.abs(back - 25f) < 0.1f)
    }

    @Test
    fun fixedSpanClampsOutsideRangeToPaletteEnds() {
        val lo = Tiny1BFormat.kelvin16FromCelsius(10f)
        val hi = Tiny1BFormat.kelvin16FromCelsius(20f)
        val mid = Tiny1BFormat.kelvin16FromCelsius(15f)
        val below = Tiny1BFormat.kelvin16FromCelsius(0f)
        val above = Tiny1BFormat.kelvin16FromCelsius(40f)
        val out = TemperatureMaps.normalizeFixed(intArrayOf(below, lo, mid, hi, above), 10f, 20f)
        assertEquals(0f, out[0], 1e-4f)
        assertEquals(0f, out[1], 1e-4f)
        assertEquals(1f, out[3], 1e-4f)
        assertEquals(1f, out[4], 1e-4f)
        assertTrue(out[2] in 0.4f..0.6f)
    }

    @Test
    fun sharpenDoesNotMutateNativeKelvin() {
        val planes = FrameParser.parseUvcFrame(SyntheticScene.uvcFrame())
        val original = planes.kelvin16.copyOf()
        SuperResolution.enhance(
            planes,
            IsrScale.OFF,
            Palettes.get(PaletteId.IRONBOW),
            sharpenAmount = 80,
        )
        assertTrue(original.contentEquals(planes.kelvin16))
    }

    @Test
    fun fixedSpanEnhanceStillLeavesNativeGridAlone() {
        val planes = FrameParser.parseUvcFrame(SyntheticScene.uvcFrame())
        val original = planes.kelvin16.copyOf()
        val rendered = SuperResolution.enhance(
            planes,
            IsrScale.OFF,
            Palettes.get(PaletteId.IRONBOW),
            spanLowC = 0f,
            spanHighC = 30f,
        )
        assertTrue(original.contentEquals(rendered.native.kelvin16))
        assertTrue(rendered.argb.isNotEmpty())
    }
}
