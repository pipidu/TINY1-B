package com.pipidu.tiny1b.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DenoiseTest {
    @Test
    fun medianRemovesIsolatedSpeckle() {
        val w = 7
        val h = 7
        val src = FloatArray(w * h) { 0.2f }
        src[3 * w + 3] = 1f
        val out = Denoise.median(src, w, h, radius = 1)
        assertTrue(out[3 * w + 3] < 0.4f)
        assertEquals(0.2f, out[0], 1e-5f)
    }

    @Test
    fun denoiseDoesNotMutateNativeTemperatureGrid() {
        val planes = FrameParser.parseUvcFrame(SyntheticScene.uvcFrame())
        val original = planes.kelvin16.copyOf()
        val rendered = SuperResolution.enhance(
            planes,
            IsrScale.OFF,
            Palettes.get(PaletteId.WHITE_HOT),
            denoise = true,
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
}
