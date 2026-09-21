package com.pipidu.tiny1b.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Paper = Color(0xFFF4F6FA)
val Surface = Color(0xFFFFFFFF)
val SurfaceMuted = Color(0xFFE8EDF4)
val Ink = Color(0xFF1C2430)
val Muted = Color(0xFF5B6778)
val Accent = Color(0xFF2563EB)
val AccentSoft = Color(0xFFDBEAFE)
val Outline = Color(0xFFD0D7E2)
val Hot = Color(0xFFE11D48)
val Cold = Color(0xFF0284C7)
val Live = Color(0xFF059669)

private val Scheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Cold,
    background = Paper,
    surface = Surface,
    onBackground = Ink,
    onSurface = Ink,
    onSurfaceVariant = Muted,
    outline = Outline,
    error = Hot,
    surfaceContainer = SurfaceMuted,
    primaryContainer = AccentSoft,
    onPrimaryContainer = Color(0xFF1E3A8A),
)

@Composable
fun Tiny1BTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = MaterialTheme.typography.copy(
            headlineLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                color = Ink,
            ),
            titleMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                color = Ink,
            ),
            labelLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = Ink,
            ),
            bodyMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 14.sp,
                color = Ink,
            ),
        ),
        content = content,
    )
}
