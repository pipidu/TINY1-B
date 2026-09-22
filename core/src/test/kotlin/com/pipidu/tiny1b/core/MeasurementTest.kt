package com.pipidu.tiny1b.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MeasurementTest {
    @Test
    fun addMoveRemoveUserPointsAndAlwaysHasCenterAndExtrema() {
        val planes = FrameParser.parseUvcFrame(SyntheticScene.uvcFrame())
        val model = MeasurementModel()
        val id = model.addUser(0.25f, 0.4f)
        requireNotNull(id)
        model.move(id, 0.3f, 0.5f)
        val snap = model.snapshot(planes)
        assertTrue(snap.points.any { it.kind == PointKind.CENTER && it.nx == 0.5f })
        assertTrue(snap.points.any { it.kind == PointKind.HOT })
        assertTrue(snap.points.any { it.kind == PointKind.COLD })
        val user = snap.points.first { it.kind == PointKind.USER }
        assertEquals(0.3f, user.nx)
        assertEquals(0.5f, user.ny)
        assertTrue(user.selected)
        model.remove(id)
        assertTrue(model.snapshot(planes).points.none { it.kind == PointKind.USER })
    }

    @Test
    fun nearestSelectsWithinThreshold() {
        val model = MeasurementModel()
        val id = model.addUser(0.2f, 0.2f)!!
        assertEquals(id, model.nearestUser(0.21f, 0.19f))
        assertNull(model.nearestUser(0.9f, 0.9f))
    }

    @Test
    fun remapUsersFollowsClockwiseRotation() {
        val model = MeasurementModel()
        val id = model.addUser(0.25f, 0.4f)!!
        model.remapUsers(DisplayRotation.DEG_0, DisplayRotation.DEG_90)
        val planes = FrameParser.parseUvcFrame(SyntheticScene.uvcFrame())
        val user = model.snapshot(planes).points.first { it.kind == PointKind.USER && it.id == id }
        assertEquals(0.6f, user.nx, 1e-4f)
        assertEquals(0.25f, user.ny, 1e-4f)
    }

    @Test
    fun extremaFollowCurrentFrameWithoutDwell() {
        val model = MeasurementModel()
        model.showCenter = false
        val first = model.snapshot(hotAt(0, 0, 40f))
        val hot0 = first.points.first { it.kind == PointKind.HOT }
        assertEquals(0, (hot0.nx * 7 + 0.5f).toInt())
        val second = model.snapshot(hotAt(7, 7, 40.2f))
        val hot1 = second.points.first { it.kind == PointKind.HOT }
        assertEquals(7, (hot1.nx * 7 + 0.5f).toInt())
        assertEquals(7, (hot1.ny * 7 + 0.5f).toInt())
    }
}

private fun hotAt(x: Int, y: Int, celsius: Float, w: Int = 8, h: Int = 8): ThermalPlanes {
    val room = Tiny1BFormat.kelvin16FromCelsius(20f)
    val hot = Tiny1BFormat.kelvin16FromCelsius(celsius)
    val lum = FloatArray(w * h) { 10f }
    val kel = IntArray(w * h) { room }
    kel[y * w + x] = hot
    return ThermalPlanes(w, h, lum, kel)
}
