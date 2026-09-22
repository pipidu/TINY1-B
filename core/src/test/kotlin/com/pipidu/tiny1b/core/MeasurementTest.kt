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
    fun extremaHoldNearbyFlicker() {
        var t = 0L
        val model = MeasurementModel(nowMs = { t })
        model.showCenter = false
        val first = model.snapshot(hotAt(3, 3, celsius = 40f)).hot()
        assertEquals(3, first.pixelX(8))
        assertEquals(3, first.pixelY(8))
        val neighbor = model.snapshot(spots(8, 8, Triple(3, 3, 40f), Triple(5, 3, 40.2f))).hot()
        assertEquals(3, neighbor.pixelX(8))
        assertEquals(3, neighbor.pixelY(8))
    }

    @Test
    fun extremaDwellThenMoveWhenFarPeakKeepsWinning() {
        var t = 0L
        val model = MeasurementModel(nowMs = { t })
        model.showCenter = false
        model.snapshot(hotAt(0, 0, celsius = 30f))
        t = 10L
        val early = model.snapshot(spots(8, 8, Triple(0, 0, 30f), Triple(7, 7, 30.4f))).hot()
        assertEquals(0, early.pixelX(8))
        assertEquals(0, early.pixelY(8))
        t = 10L + MeasurementModel.EXTREMA_DWELL_MS
        val moved = model.snapshot(spots(8, 8, Triple(0, 0, 30f), Triple(7, 7, 30.4f))).hot()
        assertEquals(7, moved.pixelX(8))
        assertEquals(7, moved.pixelY(8))
    }

    @Test
    fun extremaJumpImmediatelyWhenClearlyHotter() {
        var t = 0L
        val model = MeasurementModel(nowMs = { t })
        model.showCenter = false
        model.snapshot(hotAt(0, 0, celsius = 30f))
        val jumped = model.snapshot(spots(8, 8, Triple(0, 0, 30f), Triple(7, 7, 32f))).hot()
        assertEquals(7, jumped.pixelX(8))
        assertEquals(7, jumped.pixelY(8))
    }

    @Test
    fun centerAndUserStayPutWhileExtremaDwell() {
        var t = 0L
        val model = MeasurementModel(nowMs = { t })
        val id = model.addUser(0.25f, 0.5f)!!
        val a = model.snapshot(hotAt(0, 0, celsius = 30f))
        t = 50L
        val b = model.snapshot(spots(8, 8, Triple(0, 0, 30f), Triple(7, 7, 30.3f)))
        assertEquals(0.5f, a.points.first { it.kind == PointKind.CENTER }.nx)
        assertEquals(0.5f, b.points.first { it.kind == PointKind.CENTER }.nx)
        val userA = a.points.first { it.kind == PointKind.USER && it.id == id }
        val userB = b.points.first { it.kind == PointKind.USER && it.id == id }
        assertEquals(0.25f, userA.nx)
        assertEquals(0.25f, userB.nx)
        assertEquals(0, b.hot().pixelX(8))
    }
}

private fun hotAt(x: Int, y: Int, celsius: Float, w: Int = 8, h: Int = 8): ThermalPlanes =
    spots(w, h, Triple(x, y, celsius))

private fun spots(w: Int, h: Int, vararg peaks: Triple<Int, Int, Float>): ThermalPlanes {
    val room = Tiny1BFormat.kelvin16FromCelsius(20f)
    val lum = FloatArray(w * h) { 10f }
    val kel = IntArray(w * h) { room }
    for ((x, y, celsius) in peaks) {
        kel[y * w + x] = Tiny1BFormat.kelvin16FromCelsius(celsius)
    }
    return ThermalPlanes(w, h, lum, kel)
}

private fun MeasurementSnapshot.hot(): MeasurePoint = points.first { it.kind == PointKind.HOT }

private fun MeasurePoint.pixelX(width: Int): Int = (nx * (width - 1) + 0.5f).toInt()

private fun MeasurePoint.pixelY(height: Int): Int = (ny * (height - 1) + 0.5f).toInt()
