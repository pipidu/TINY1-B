package com.pipidu.tiny1b.core

enum class DisplayRotation(val degrees: Int, val labelZh: String) {
    DEG_0(0, "0°"),
    DEG_90(90, "90°"),
    DEG_180(180, "180°"),
    DEG_270(270, "270°");

    fun nextClockwise(): DisplayRotation = entries[(ordinal + 1) % entries.size]

    /** Map a display-normalized point back to the unrotated native plane. */
    fun toNative(nx: Float, ny: Float): Pair<Float, Float> = when (this) {
        DEG_0 -> nx to ny
        DEG_90 -> ny to (1f - nx)
        DEG_180 -> (1f - nx) to (1f - ny)
        DEG_270 -> (1f - ny) to nx
    }

    /** Map a native-plane point into this display rotation. */
    fun fromNative(nx: Float, ny: Float): Pair<Float, Float> = when (this) {
        DEG_0 -> nx to ny
        DEG_90 -> (1f - ny) to nx
        DEG_180 -> (1f - nx) to (1f - ny)
        DEG_270 -> ny to (1f - nx)
    }
}
