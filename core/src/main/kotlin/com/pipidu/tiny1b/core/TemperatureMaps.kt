package com.pipidu.tiny1b.core

data class PixelRef(
    val x: Int,
    val y: Int,
    val kelvin16: Int,
) {
    val celsius: Float get() = Tiny1BFormat.celsiusFromKelvin16(kelvin16)
}

data class FrameStats(
    val min: PixelRef,
    val max: PixelRef,
    val averageCelsius: Float,
)

object TemperatureMaps {
    fun sampleCelsius(kelvin16: IntArray, width: Int, height: Int, nx: Float, ny: Float): Float {
        val x = (nx.coerceIn(0f, 1f) * (width - 1)).toInt()
        val y = (ny.coerceIn(0f, 1f) * (height - 1)).toInt()
        return Tiny1BFormat.celsiusFromKelvin16(kelvin16[y * width + x])
    }

    fun stats(kelvin16: IntArray, width: Int, height: Int): FrameStats {
        require(kelvin16.isNotEmpty())
        var minV = Int.MAX_VALUE
        var maxV = Int.MIN_VALUE
        var minI = 0
        var maxI = 0
        var sum = 0L
        for (i in kelvin16.indices) {
            val v = kelvin16[i]
            sum += v
            if (v < minV) {
                minV = v
                minI = i
            }
            if (v > maxV) {
                maxV = v
                maxI = i
            }
        }
        return FrameStats(
            min = PixelRef(minI % width, minI / width, minV),
            max = PixelRef(maxI % width, maxI / width, maxV),
            averageCelsius = Tiny1BFormat.celsiusFromKelvin16((sum / kelvin16.size).toInt()),
        )
    }

    /**
     * Percentile stretch of Kelvin-16 values into 0..1 for palette mapping.
     * Uses a 1024-bin histogram so a 50k-pixel frame stays cheap.
     */
    fun normalize(kelvin16: IntArray, lowPercentile: Float = 0.02f, highPercentile: Float = 0.98f): FloatArray {
        val (lo, hi) = percentileBounds(kelvin16, lowPercentile, highPercentile)
        val range = (hi - lo).coerceAtLeast(1)
        val out = FloatArray(kelvin16.size)
        for (i in kelvin16.indices) {
            out[i] = ((kelvin16[i] - lo).toFloat() / range).coerceIn(0f, 1f)
        }
        return out
    }

    fun percentileBounds(values: IntArray, low: Float, high: Float): Pair<Int, Int> {
        val bins = IntArray(1024)
        var minV = Int.MAX_VALUE
        var maxV = Int.MIN_VALUE
        for (v in values) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        if (minV == Int.MAX_VALUE || minV == maxV) return minV to minV
        val span = (maxV - minV).coerceAtLeast(1)
        for (v in values) {
            val b = ((v - minV) * 1023L / span).toInt().coerceIn(0, 1023)
            bins[b]++
        }
        val n = values.size
        val lowCount = (n * low).toInt().coerceIn(0, n - 1)
        val highCount = (n * high).toInt().coerceIn(0, n - 1)
        return binToValue(bins, minV, span, lowCount) to binToValue(bins, minV, span, highCount)
    }

    private fun binToValue(bins: IntArray, minV: Int, span: Int, target: Int): Int {
        var acc = 0
        for (i in bins.indices) {
            acc += bins[i]
            if (acc > target) {
                return minV + (i * span / 1023)
            }
        }
        return minV + span
    }
}
