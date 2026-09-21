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
}
