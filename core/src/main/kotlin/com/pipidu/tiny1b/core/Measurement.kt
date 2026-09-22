package com.pipidu.tiny1b.core

enum class PointKind {
    CENTER,
    USER,
    HOT,
    COLD,
}

data class MeasurePoint(
    val id: Long,
    val kind: PointKind,
    val nx: Float,
    val ny: Float,
    val celsius: Float,
    val selected: Boolean = false,
)

data class MeasurementSnapshot(
    val points: List<MeasurePoint>,
    val stats: FrameStats?,
)

class MeasurementModel(
    private val maxUserPoints: Int = 8,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val user = LinkedHashMap<Long, Pair<Float, Float>>()
    private var nextId = 1L
    private var selectedId: Long? = null
    var showCenter: Boolean = true
    var showMinMax: Boolean = true
    private val hotLock = ExtremaLock()
    private val coldLock = ExtremaLock()
    private var lockWidth = 0
    private var lockHeight = 0

    fun addUser(nx: Float, ny: Float): Long? {
        if (user.size >= maxUserPoints) return null
        val id = nextId++
        user[id] = nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
        selectedId = id
        return id
    }

    fun move(id: Long, nx: Float, ny: Float) {
        if (user.containsKey(id)) {
            user[id] = nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
        }
    }

    fun remove(id: Long) {
        user.remove(id)
        if (selectedId == id) selectedId = user.keys.lastOrNull()
    }

    fun select(id: Long?) {
        selectedId = id
    }

    fun clearUsers() {
        user.clear()
        selectedId = null
    }

    fun remapUsers(from: DisplayRotation, to: DisplayRotation) {
        if (from == to) return
        resetExtremaLocks()
        for (id in user.keys.toList()) {
            val p = user[id] ?: continue
            val native = from.toNative(p.first, p.second)
            val mapped = to.fromNative(native.first, native.second)
            user[id] = mapped.first.coerceIn(0f, 1f) to mapped.second.coerceIn(0f, 1f)
        }
    }

    fun nearestUser(nx: Float, ny: Float, threshold: Float = 0.045f): Long? {
        var best: Long? = null
        var bestD = threshold
        for ((id, p) in user) {
            val dx = p.first - nx
            val dy = p.second - ny
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d <= bestD) {
                bestD = d
                best = id
            }
        }
        return best
    }

    fun snapshot(planes: ThermalPlanes?): MeasurementSnapshot {
        if (planes == null) {
            return MeasurementSnapshot(emptyList(), null)
        }
        if (planes.width != lockWidth || planes.height != lockHeight) {
            resetExtremaLocks()
            lockWidth = planes.width
            lockHeight = planes.height
        }
        val stats = TemperatureMaps.stats(planes.kelvin16, planes.width, planes.height)
        val points = ArrayList<MeasurePoint>()
        if (showMinMax) {
            val cold = settleExtrema(
                lock = coldLock,
                raw = stats.min,
                planes = planes,
                hotter = false,
            )
            val hot = settleExtrema(
                lock = hotLock,
                raw = stats.max,
                planes = planes,
                hotter = true,
            )
            points += MeasurePoint(
                id = -1,
                kind = PointKind.COLD,
                nx = cold.x / (planes.width - 1).toFloat(),
                ny = cold.y / (planes.height - 1).toFloat(),
                celsius = Tiny1BFormat.celsiusFromKelvin16(cold.kelvin16),
            )
            points += MeasurePoint(
                id = -2,
                kind = PointKind.HOT,
                nx = hot.x / (planes.width - 1).toFloat(),
                ny = hot.y / (planes.height - 1).toFloat(),
                celsius = Tiny1BFormat.celsiusFromKelvin16(hot.kelvin16),
            )
        }
        if (showCenter) {
            points += MeasurePoint(
                id = 0,
                kind = PointKind.CENTER,
                nx = 0.5f,
                ny = 0.5f,
                celsius = TemperatureMaps.sampleCelsius(planes.kelvin16, planes.width, planes.height, 0.5f, 0.5f),
            )
        }
        for ((id, p) in user) {
            points += MeasurePoint(
                id = id,
                kind = PointKind.USER,
                nx = p.first,
                ny = p.second,
                celsius = TemperatureMaps.sampleCelsius(
                    planes.kelvin16, planes.width, planes.height, p.first, p.second
                ),
                selected = id == selectedId,
            )
        }
        return MeasurementSnapshot(points, stats)
    }

    private fun resetExtremaLocks() {
        hotLock.reset()
        coldLock.reset()
    }

    private fun settleExtrema(
        lock: ExtremaLock,
        raw: PixelRef,
        planes: ThermalPlanes,
        hotter: Boolean,
    ): PixelRef {
        val w = planes.width
        val h = planes.height
        if (!lock.held || lock.x !in 0 until w || lock.y !in 0 until h) {
            lock.hold(raw.x, raw.y)
            return raw
        }
        val lockedK = planes.kelvin16[lock.y * w + lock.x]
        if (withinRadius(raw.x, raw.y, lock.x, lock.y, EXTREMA_HOLD_RADIUS_PX)) {
            lock.clearCandidate()
            return PixelRef(lock.x, lock.y, raw.kelvin16)
        }
        val advantage = if (hotter) raw.kelvin16 - lockedK else lockedK - raw.kelvin16
        if (advantage >= EXTREMA_JUMP_KELVIN16) {
            lock.hold(raw.x, raw.y)
            return raw
        }
        val now = nowMs()
        val sameCandidate = lock.hasCandidate &&
            withinRadius(raw.x, raw.y, lock.candX, lock.candY, EXTREMA_HOLD_RADIUS_PX)
        if (!sameCandidate) {
            lock.candX = raw.x
            lock.candY = raw.y
            lock.candSinceMs = now
            return PixelRef(lock.x, lock.y, lockedK)
        }
        if (now - lock.candSinceMs >= EXTREMA_DWELL_MS) {
            lock.hold(raw.x, raw.y)
            return raw
        }
        return PixelRef(lock.x, lock.y, lockedK)
    }

    private class ExtremaLock {
        var x: Int = Int.MIN_VALUE
        var y: Int = Int.MIN_VALUE
        var candX: Int = Int.MIN_VALUE
        var candY: Int = Int.MIN_VALUE
        var candSinceMs: Long = 0L
        val held: Boolean get() = x != Int.MIN_VALUE
        val hasCandidate: Boolean get() = candX != Int.MIN_VALUE

        fun hold(nx: Int, ny: Int) {
            x = nx
            y = ny
            clearCandidate()
        }

        fun clearCandidate() {
            candX = Int.MIN_VALUE
            candY = Int.MIN_VALUE
            candSinceMs = 0L
        }

        fun reset() {
            x = Int.MIN_VALUE
            y = Int.MIN_VALUE
            clearCandidate()
        }
    }

    companion object {
        /** A new extrema pixel must keep winning this long before the marker moves. */
        const val EXTREMA_DWELL_MS = 400L

        /** Neighbors inside this radius are the same blob; the marker does not hop. */
        const val EXTREMA_HOLD_RADIUS_PX = 4

        /** Move immediately when the new extrema is ≥ 1.0 °C more extreme than the lock. */
        const val EXTREMA_JUMP_KELVIN16 = 16

        private fun withinRadius(x0: Int, y0: Int, x1: Int, y1: Int, radius: Int): Boolean {
            val dx = x0 - x1
            val dy = y0 - y1
            return dx * dx + dy * dy <= radius * radius
        }
    }
}
