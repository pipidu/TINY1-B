package com.pipidu.tiny1b.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrameParserTest {
    @Test
    fun parseSplitsImageAndTemperatureAndRotatesToPortrait() {
        val frame = SyntheticScene.uvcFrame()
        val planes = FrameParser.parseUvcFrame(frame)
        assertEquals(Tiny1BFormat.DISPLAY_WIDTH, planes.width)
        assertEquals(Tiny1BFormat.DISPLAY_HEIGHT, planes.height)
        assertEquals(planes.width * planes.height, planes.kelvin16.size)
        val stats = TemperatureMaps.stats(planes.kelvin16, planes.width, planes.height)
        assertTrue(stats.max.celsius > stats.min.celsius + 10f)
        assertTrue(stats.max.celsius in 20f..80f)
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
