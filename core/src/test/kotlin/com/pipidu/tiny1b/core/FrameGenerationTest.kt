package com.pipidu.tiny1b.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrameGenerationTest {
    @Test
    fun blendZeroIsPrevAndOneIsNext() {
        val prev = ThermalPlanes(
            width = 2,
            height = 1,
            luminance = floatArrayOf(0f, 10f),
            kelvin16 = intArrayOf(100, 200),
        )
        val next = ThermalPlanes(
            width = 2,
            height = 1,
            luminance = floatArrayOf(10f, 20f),
            kelvin16 = intArrayOf(300, 400),
        )
        val a = FrameGeneration.blend(prev, next, 0f)
        assertEquals(0f, a.luminance[0], 1e-4f)
        assertEquals(100, a.kelvin16[0])
        val b = FrameGeneration.blend(prev, next, 1f)
        assertEquals(10f, b.luminance[0], 1e-4f)
        assertEquals(300, b.kelvin16[0])
    }

    @Test
    fun blendHalfAveragesPlanes() {
        val prev = ThermalPlanes(2, 1, floatArrayOf(0f, 4f), intArrayOf(10, 20))
        val next = ThermalPlanes(2, 1, floatArrayOf(10f, 8f), intArrayOf(30, 40))
        val mid = FrameGeneration.blend(prev, next, 0.5f)
        assertEquals(5f, mid.luminance[0], 1e-4f)
        assertEquals(6f, mid.luminance[1], 1e-4f)
        assertEquals(20, mid.kelvin16[0])
        assertEquals(30, mid.kelvin16[1])
    }

    @Test
    fun blendReusesDestinationArrays() {
        val prev = ThermalPlanes(2, 1, floatArrayOf(0f, 0f), intArrayOf(0, 0))
        val next = ThermalPlanes(2, 1, floatArrayOf(8f, 8f), intArrayOf(8, 8))
        val dst = ThermalPlanes(2, 1, FloatArray(2), IntArray(2))
        val out = FrameGeneration.blend(prev, next, 0.25f, dst)
        assertSame(dst, out)
        assertSame(dst.luminance, out.luminance)
        assertEquals(2f, out.luminance[0], 1e-4f)
        assertEquals(2, out.kelvin16[0])
    }

    @Test
    fun extraFrameCountsMatchScale() {
        assertEquals(0, FrameGenScale.OFF.extraFrames)
        assertEquals(1, FrameGenScale.X2.extraFrames)
        assertEquals(2, FrameGenScale.X3.extraFrames)
        assertTrue(FrameGeneration.FAST_NATIVE_MS in 20L..50L)
    }
}
