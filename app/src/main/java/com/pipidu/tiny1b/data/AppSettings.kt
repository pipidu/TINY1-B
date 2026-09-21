package com.pipidu.tiny1b.data

import android.content.Context
import com.pipidu.tiny1b.core.DisplayRotation
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

    var denoise: Boolean
        get() = prefs.getBoolean(KEY_DENOISE, false)
        set(value) { prefs.edit().putBoolean(KEY_DENOISE, value).apply() }

    var rotation: DisplayRotation
        get() = DisplayRotation.entries.getOrElse(prefs.getInt(KEY_ROTATION, 0)) { DisplayRotation.DEG_0 }
        set(value) { prefs.edit().putInt(KEY_ROTATION, value.ordinal).apply() }

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
        private const val KEY_ROTATION = "rotation"
    }
}
