package io.github.cosmicoverlord.boardingpass.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.github.cosmicoverlord.boardingpass.data.ThemeMode
import io.github.cosmicoverlord.boardingpass.ui.components.findActivity

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

/** Dark grey rather than black: easier on the eyes and keeps cards and the cabin map distinct. */
private val Dark = darkColorScheme(
    primary = Color(0xFFB4C5FF),
    onPrimary = Color(0xFF0B1B4D),
    primaryContainer = Color(0xFF34488F),
    onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = Color(0xFF8FD3D9),
    onSecondary = Color(0xFF00363B),
    secondaryContainer = Color(0xFF285A60),
    onSecondaryContainer = Color(0xFFCDEFF1),
    tertiary = Color(0xFFCDBDFF),
    onTertiary = Color(0xFF2E1A66),
    tertiaryContainer = Color(0xFF4A3D73),
    onTertiaryContainer = Color(0xFFE8DEFF),
    background = Color(0xFF26272B),
    onBackground = Color(0xFFE4E5EA),
    surface = Color(0xFF26272B),
    onSurface = Color(0xFFE4E5EA),
    surfaceContainerLowest = Color(0xFF3A3C42),
    surfaceContainerLow = Color(0xFF2F3135),
    surfaceContainer = Color(0xFF2B2D31),
    surfaceContainerHigh = Color(0xFF34363B),
    surfaceContainerHighest = Color(0xFF3D3F45),
    surfaceVariant = Color(0xFF45474E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D9099),
    outlineVariant = Color(0xFF52555D),
)

@Immutable
data class StatusColors(val good: Color, val warn: Color, val bad: Color, val info: Color, val neutral: Color)

val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(Color(0xFF1B8A4B), Color(0xFFB86E00), Color(0xFFC62828), Color(0xFF1D5FD1), Color(0xFF6B7080))
}

/** Gradient used for the boarding pass "hero" header. */
val HeroGradient = listOf(Color(0xFF1D3A8A), Color(0xFF0E7C86))

@Composable
fun BoardingTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        // The app's choice can differ from the system's, so set bar icon contrast explicitly.
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
