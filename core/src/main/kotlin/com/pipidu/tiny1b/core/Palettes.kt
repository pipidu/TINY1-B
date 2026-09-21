package com.pipidu.tiny1b.core

enum class PaletteId(val labelZh: String) {
    IRONBOW("铁红"),
    WHITE_HOT("白热"),
    BLACK_HOT("黑热"),
    RAINBOW("彩虹"),
    LAVA("熔岩"),
    ARCTIC("极光"),
    MEDICAL("医疗"),
}

data class Palette(
    val id: PaletteId,
    val lut: IntArray,
) {
    fun argb(t: Float): Int {
        val i = (t.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return lut[i]
    }
}

object Palettes {
    private val cache = PaletteId.entries.associateWith { build(it) }

    fun get(id: PaletteId): Palette = cache.getValue(id)

    fun all(): List<Palette> = PaletteId.entries.map { get(it) }

    private fun build(id: PaletteId): Palette {
        val stops: List<Pair<Float, Int>> = when (id) {
            PaletteId.IRONBOW -> listOf(
                0f to rgb(0, 0, 0),
                0.18f to rgb(32, 0, 80),
                0.36f to rgb(140, 0, 60),
                0.55f to rgb(220, 40, 10),
                0.72f to rgb(255, 140, 0),
                0.88f to rgb(255, 230, 80),
                1f to rgb(255, 255, 255),
            )
            PaletteId.WHITE_HOT -> listOf(
                0f to rgb(0, 0, 0),
                1f to rgb(255, 255, 255),
            )
            PaletteId.BLACK_HOT -> listOf(
                0f to rgb(255, 255, 255),
                1f to rgb(0, 0, 0),
            )
            PaletteId.RAINBOW -> listOf(
                0f to rgb(0, 0, 80),
                0.2f to rgb(0, 80, 255),
                0.4f to rgb(0, 220, 180),
                0.6f to rgb(255, 230, 0),
                0.8f to rgb(255, 80, 0),
                1f to rgb(180, 0, 40),
            )
            PaletteId.LAVA -> listOf(
                0f to rgb(8, 0, 20),
                0.3f to rgb(90, 0, 30),
                0.55f to rgb(200, 20, 0),
                0.8f to rgb(255, 120, 0),
                1f to rgb(255, 250, 180),
            )
            PaletteId.ARCTIC -> listOf(
                0f to rgb(0, 8, 40),
                0.25f to rgb(0, 70, 160),
                0.5f to rgb(0, 180, 200),
                0.75f to rgb(180, 255, 220),
                1f to rgb(255, 255, 255),
            )
            PaletteId.MEDICAL -> listOf(
                0f to rgb(0, 0, 0),
                0.25f to rgb(0, 0, 180),
                0.5f to rgb(0, 200, 0),
                0.75f to rgb(255, 255, 0),
                1f to rgb(255, 0, 0),
            )
        }
        return Palette(id, interpolateLut(stops))
    }

    private fun interpolateLut(stops: List<Pair<Float, Int>>): IntArray {
        val lut = IntArray(256)
        for (i in 0..255) {
            val t = i / 255f
            var a = stops.first()
            var b = stops.last()
            for (s in 0 until stops.size - 1) {
                if (t >= stops[s].first && t <= stops[s + 1].first) {
                    a = stops[s]
                    b = stops[s + 1]
                    break
                }
            }
            val span = (b.first - a.first).let { if (it == 0f) 1f else it }
            val u = ((t - a.first) / span).coerceIn(0f, 1f)
            lut[i] = mix(a.second, b.second, u)
        }
        return lut
    }

    private fun mix(c0: Int, c1: Int, t: Float): Int {
        val r = ((c0 shr 16) and 0xFF) + ((((c1 shr 16) and 0xFF) - ((c0 shr 16) and 0xFF)) * t).toInt()
        val g = ((c0 shr 8) and 0xFF) + ((((c1 shr 8) and 0xFF) - ((c0 shr 8) and 0xFF)) * t).toInt()
        val b = (c0 and 0xFF) + (((c1 and 0xFF) - (c0 and 0xFF)) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
