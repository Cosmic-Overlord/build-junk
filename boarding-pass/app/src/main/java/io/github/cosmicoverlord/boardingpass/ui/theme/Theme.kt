package io.github.cosmicoverlord.boardingpass.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF1D3A8A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE3FF),
    onPrimaryContainer = Color(0xFF0B1B4D),
    secondary = Color(0xFF0E7C86),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDEFF1),
    onSecondaryContainer = Color(0xFF002A2E),
    tertiary = Color(0xFF5B3FA8),
    background = Color(0xFFF6F7FB),
    surface = Color(0xFFF6F7FB),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEFF1F7),
    surfaceContainerHigh = Color(0xFFE8EBF3),
    surfaceVariant = Color(0xFFE1E4EE),
    onSurfaceVariant = Color(0xFF4A5063),
    outlineVariant = Color(0xFFD0D4E0),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB4C5FF),
    onPrimary = Color(0xFF0B1B4D),
    primaryContainer = Color(0xFF253F8F),
    onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = Color(0xFF7FD6DD),
    onSecondary = Color(0xFF00363B),
    secondaryContainer = Color(0xFF00505A),
    onSecondaryContainer = Color(0xFFCDEFF1),
    tertiary = Color(0xFFCDBDFF),
    background = Color(0xFF101318),
    surface = Color(0xFF101318),
    surfaceContainerLowest = Color(0xFF0B0E12),
    surfaceContainerLow = Color(0xFF181B21),
    surfaceContainer = Color(0xFF1C2027),
    surfaceContainerHigh = Color(0xFF262A32),
    surfaceVariant = Color(0xFF30343D),
    onSurfaceVariant = Color(0xFFC3C7D3),
    outlineVariant = Color(0xFF3F4450),
)

@Immutable
data class StatusColors(val good: Color, val warn: Color, val bad: Color, val info: Color, val neutral: Color)

val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(Color(0xFF1B8A4B), Color(0xFFB86E00), Color(0xFFC62828), Color(0xFF1D5FD1), Color(0xFF6B7080))
}

/** Gradient used for the boarding pass "hero" header. */
val HeroGradient = listOf(Color(0xFF1D3A8A), Color(0xFF0E7C86))

@Composable
fun BoardingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
