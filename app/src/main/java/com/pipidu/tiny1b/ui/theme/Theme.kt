package com.pipidu.tiny1b.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF07080D)
val InkElevated = Color(0xFF12141C)
val InkHigh = Color(0xFF1B2030)
val Sand = Color(0xFFF4F1EA)
val Mist = Color(0xFF9AA3B5)
val Ember = Color(0xFFFF8A3D)
val Hot = Color(0xFFFF4B4B)
val Cold = Color(0xFF5AB6FF)
val Live = Color(0xFF3DFFB0)

private val Scheme = darkColorScheme(
    primary = Ember,
    onPrimary = Color(0xFF1A0B00),
    secondary = Cold,
    background = Ink,
    surface = InkElevated,
    onBackground = Sand,
    onSurface = Sand,
    onSurfaceVariant = Mist,
    outline = Color(0xFF2A3144),
    error = Hot,
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
                color = Sand,
            ),
            titleMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                color = Sand,
            ),
            labelLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = Sand,
            ),
            bodyMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 14.sp,
                color = Sand,
            ),
        ),
        content = content,
    )
}
