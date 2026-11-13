package io.anyvideo.downloader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF8B5CF6)
private val AccentSoft = Color(0xFFB79CFF)
private val Background = Color(0xFF0B0B12)
private val Surface = Color(0xFF15151F)
private val SurfaceHigh = Color(0xFF1E1E2B)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A2140),
    onPrimaryContainer = AccentSoft,
    secondary = Color(0xFF4ADE80),
    onSecondary = Color(0xFF06240F),
    background = Background,
    onBackground = Color(0xFFF3F2F8),
    surface = Surface,
    onSurface = Color(0xFFF3F2F8),
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Color(0xFFB9B6C8),
    outline = Color(0xFF3A3748),
)

private val LightScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE6FF),
    onPrimaryContainer = Color(0xFF2A2140),
    secondary = Color(0xFF16A34A),
    background = Color(0xFFF7F6FB),
    onBackground = Color(0xFF16141F),
    surface = Color.White,
    onSurface = Color(0xFF16141F),
    surfaceVariant = Color(0xFFECEAF3),
    onSurfaceVariant = Color(0xFF4A4757),
    outline = Color(0xFFC9C5D6),
)

@Composable
fun AnyVideoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
