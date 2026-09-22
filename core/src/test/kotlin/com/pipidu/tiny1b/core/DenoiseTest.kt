package com.pipidu.tiny1b.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DenoiseTest {
    @Test
    fun separableMedianRemovesIsolatedSpeckle() {
        val w = 7
        val h = 7
        val src = FloatArray(w * h) { 0.2f }
        src[3 * w + 3] = 1f
        val dest = FloatArray(w * h)
        val tmp = FloatArray(w * h)
        Denoise.separableMedian3(src, w, h, dest, tmp)
        assertTrue(dest[3 * w + 3] < 0.4f)
        assertEquals(0.2f, dest[0], 1e-5f)
    }

    @Test
    fun strengthZeroCopiesUnfiltered() {
        val w = 4
        val h = 4
        val y = FloatArray(w * h) { it.toFloat() }
        val t = FloatArray(w * h) { it * 0.5f }
        val destY = FloatArray(w * h)
        val destT = FloatArray(w * h)
        val tmp = FloatArray(w * h)
        Denoise.apply(y, t, w, h, amount = 0, destY = destY, destT = destT, tmp = tmp)
        assertTrue(y.contentEquals(destY))
        assertTrue(t.contentEquals(destT))
    }

    @Test
    fun strengthScalesBlendBetweenSrcAndFiltered() {
        val w = 7
        val h = 7
        val src = FloatArray(w * h) { 0.2f }
        src[3 * w + 3] = 1f
        val filtered = FloatArray(w * h)
        val halfY = FloatArray(w * h)
        val t = FloatArray(w * h) { 0.2f }
        val destT = FloatArray(w * h)
        val tmp = FloatArray(w * h)
        Denoise.separableMedian3(src, w, h, filtered, tmp)
        Denoise.apply(src, t, w, h, amount = 50, destY = halfY, destT = destT, tmp = tmp)
        val i = 3 * w + 3
        assertTrue(filtered[i] < 0.4f)
        assertEquals(src[i] * 0.5f + filtered[i] * 0.5f, halfY[i], 1e-4f)
        assertTrue(halfY[i] > filtered[i])
        assertTrue(halfY[i] < src[i])
    }

    @Test
    fun applyReusesDestinationAndTmp() {
        val w = 5
        val h = 5
        val y = FloatArray(w * h) { 0.3f }
        val t = FloatArray(w * h) { 0.4f }
        val destY = FloatArray(w * h)
        val destT = FloatArray(w * h)
        val tmp = FloatArray(w * h)
        val out = Denoise.apply(y, t, w, h, amount = 100, destY = destY, destT = destT, tmp = tmp)
        assertSame(destY, out.first)
        assertSame(destT, out.second)
        Denoise.apply(y, t, w, h, amount = 80, destY = destY, destT = destT, tmp = tmp)
        val again = Denoise.apply(y, t, w, h, amount = 40, destY = destY, destT = destT, tmp = tmp)
        assertSame(destY, again.first)
        assertSame(destT, again.second)
    }

    @Test
    fun highStrengthUsesTwoYPasses() {
        assertEquals(0, Denoise.yPasses(0))
        assertEquals(1, Denoise.yPasses(50))
        assertEquals(1, Denoise.yPasses(79))
        assertEquals(2, Denoise.yPasses(80))
        assertEquals(2, Denoise.yPasses(100))
        assertEquals(0f, Denoise.mix(0), 1e-6f)
        assertEquals(0.5f, Denoise.mix(50), 1e-6f)
        assertEquals(1f, Denoise.mix(100), 1e-6f)
    }

    @Test
    fun denoiseDoesNotMutateNativeTemperatureGrid() {
        val planes = FrameParser.parseUvcFrame(SyntheticScene.uvcFrame())
        val original = planes.kelvin16.copyOf()
        val rendered = SuperResolution.enhance(
            planes,
            IsrScale.OFF,
            Palettes.get(PaletteId.WHITE_HOT),
            denoiseAmount = 100,
        )
        assertTrue(original.contentEquals(rendered.native.kelvin16))
        assertTrue(original.contentEquals(planes.kelvin16))
        val snap = MeasurementModel().snapshot(rendered.native)
        assertEquals(
            TemperatureMaps.sampleCelsius(original, planes.width, planes.height, 0.5f, 0.5f),
            snap.points.first { it.kind == PointKind.CENTER }.celsius,
            1e-4f,
        )
    }

    @Test
    fun scratchReusesDenoiseBuffers() {
        val planes = FrameParser.parseUvcFrame(SyntheticScene.uvcFrame())
        val scratch = IspScratch()
        SuperResolution.enhance(
            planes,
            IsrScale.X2,
            Palettes.get(PaletteId.IRONBOW),
            denoiseAmount = 80,
            scratch = scratch,
        )
        val y = scratch.denoiseY
        val t = scratch.denoiseT
        val tmp = scratch.blurTmp
        SuperResolution.enhance(
            planes,
            IsrScale.X2,
            Palettes.get(PaletteId.IRONBOW),
            denoiseAmount = 40,
            scratch = scratch,
        )
        assertSame(y, scratch.denoiseY)
        assertSame(t, scratch.denoiseT)
        assertSame(tmp, scratch.blurTmp)
    }
}
