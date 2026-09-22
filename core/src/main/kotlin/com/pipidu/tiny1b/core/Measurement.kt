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
) {
    private val user = LinkedHashMap<Long, Pair<Float, Float>>()
    private var nextId = 1L
    private var selectedId: Long? = null
    var showCenter: Boolean = true
    var showMinMax: Boolean = true

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
        val stats = TemperatureMaps.stats(planes.kelvin16, planes.width, planes.height)
        val points = ArrayList<MeasurePoint>()
        if (showMinMax) {
            points += MeasurePoint(
                id = -1,
                kind = PointKind.COLD,
                nx = stats.min.x / (planes.width - 1).toFloat(),
                ny = stats.min.y / (planes.height - 1).toFloat(),
                celsius = stats.min.celsius,
            )
            points += MeasurePoint(
                id = -2,
                kind = PointKind.HOT,
                nx = stats.max.x / (planes.width - 1).toFloat(),
                ny = stats.max.y / (planes.height - 1).toFloat(),
                celsius = stats.max.celsius,
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
}
