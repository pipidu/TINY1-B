package com.pipidu.tiny1b.data

import android.content.Context
import com.pipidu.tiny1b.core.DisplayRotation
import com.pipidu.tiny1b.core.FrameGenScale
import com.pipidu.tiny1b.core.IsrScale
import com.pipidu.tiny1b.core.PaletteId

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("tiny1b_settings", Context.MODE_PRIVATE)

    var paletteId: PaletteId
        get() = PaletteId.entries.getOrElse(prefs.getInt(KEY_PALETTE, 0)) { PaletteId.IRONBOW }
        set(value) { prefs.edit().putInt(KEY_PALETTE, value.ordinal).apply() }

    var isrScale: IsrScale
        get() = IsrScale.entries.getOrElse(prefs.getInt(KEY_ISR, 1)) { IsrScale.X2 }
        set(value) { prefs.edit().putInt(KEY_ISR, value.ordinal).apply() }

    var showCenter: Boolean
        get() = prefs.getBoolean(KEY_CENTER, true)
        set(value) { prefs.edit().putBoolean(KEY_CENTER, value).apply() }

    var showMinMax: Boolean
        get() = prefs.getBoolean(KEY_MINMAX, true)
        set(value) { prefs.edit().putBoolean(KEY_MINMAX, value).apply() }

    var mirror: Boolean
        get() = prefs.getBoolean(KEY_MIRROR, false)
        set(value) { prefs.edit().putBoolean(KEY_MIRROR, value).apply() }

    var useFahrenheit: Boolean
        get() = prefs.getBoolean(KEY_F, false)
        set(value) { prefs.edit().putBoolean(KEY_F, value).apply() }

    var samplePreview: Boolean
        get() = prefs.getBoolean(KEY_SAMPLE, false)
        set(value) { prefs.edit().putBoolean(KEY_SAMPLE, value).apply() }

    var shutterMaxSeconds: Int
        get() = prefs.getInt(KEY_SHUTTER, 30)
        set(value) { prefs.edit().putInt(KEY_SHUTTER, value.coerceIn(1, 120)).apply() }

    var denoiseAmount: Int
        get() {
            if (prefs.contains(KEY_DENOISE_AMOUNT)) {
                return prefs.getInt(KEY_DENOISE_AMOUNT, 0).coerceIn(0, 100)
            }
            return if (prefs.getBoolean(KEY_DENOISE, false)) 100 else 0
        }
        set(value) {
            prefs.edit().putInt(KEY_DENOISE_AMOUNT, value.coerceIn(0, 100)).apply()
        }

    var rotation: DisplayRotation
        get() = DisplayRotation.entries.getOrElse(prefs.getInt(KEY_ROTATION, 0)) { DisplayRotation.DEG_0 }
        set(value) { prefs.edit().putInt(KEY_ROTATION, value.ordinal).apply() }

    var frameGenScale: FrameGenScale
        get() = FrameGenScale.entries.getOrElse(prefs.getInt(KEY_FRAME_GEN, 0)) { FrameGenScale.OFF }
        set(value) { prefs.edit().putInt(KEY_FRAME_GEN, value.ordinal).apply() }

    var markerOpacity: Int
        get() = prefs.getInt(KEY_MARKER_OPACITY, 100).coerceIn(0, 100)
        set(value) { prefs.edit().putInt(KEY_MARKER_OPACITY, value.coerceIn(0, 100)).apply() }

    var sharpenAmount: Int
        get() = prefs.getInt(KEY_SHARPEN, 0).coerceIn(0, 100)
        set(value) { prefs.edit().putInt(KEY_SHARPEN, value.coerceIn(0, 100)).apply() }

    var spanFixed: Boolean
        get() = prefs.getBoolean(KEY_SPAN_FIXED, false)
        set(value) { prefs.edit().putBoolean(KEY_SPAN_FIXED, value).apply() }

    var spanLowC: Float
        get() = prefs.getFloat(KEY_SPAN_LOW, 0f).coerceIn(SPAN_MIN_C, SPAN_MAX_C)
        set(value) { prefs.edit().putFloat(KEY_SPAN_LOW, value.coerceIn(SPAN_MIN_C, SPAN_MAX_C)).apply() }

    var spanHighC: Float
        get() = prefs.getFloat(KEY_SPAN_HIGH, 40f).coerceIn(SPAN_MIN_C, SPAN_MAX_C)
        set(value) { prefs.edit().putFloat(KEY_SPAN_HIGH, value.coerceIn(SPAN_MIN_C, SPAN_MAX_C)).apply() }

    var useDownloadMirror: Boolean
        get() = prefs.getBoolean(KEY_UPDATE_MIRROR, true)
        set(value) { prefs.edit().putBoolean(KEY_UPDATE_MIRROR, value).apply() }

    companion object {
        private const val KEY_PALETTE = "palette"
        private const val KEY_ISR = "isr"
        private const val KEY_CENTER = "center"
        private const val KEY_MINMAX = "minmax"
        private const val KEY_MIRROR = "mirror"
        private const val KEY_F = "fahrenheit"
        private const val KEY_SAMPLE = "sample"
        private const val KEY_SHUTTER = "shutter_max"
        private const val KEY_DENOISE = "denoise"
        private const val KEY_DENOISE_AMOUNT = "denoise_amount"
        private const val KEY_ROTATION = "rotation"
        private const val KEY_FRAME_GEN = "frame_gen"
        private const val KEY_MARKER_OPACITY = "marker_opacity"
        private const val KEY_SHARPEN = "sharpen"
        private const val KEY_SPAN_FIXED = "span_fixed"
        private const val KEY_SPAN_LOW = "span_low_c"
        private const val KEY_SPAN_HIGH = "span_high_c"
        private const val KEY_UPDATE_MIRROR = "update_mirror"
        const val SPAN_MIN_C = -20f
        const val SPAN_MAX_C = 200f
        const val SPAN_MIN_GAP_C = 1f
    }
}
