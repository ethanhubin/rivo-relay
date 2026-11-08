package dev.rivo.relay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val NeutralDarkColorScheme =
  darkColorScheme(
    primary = NeutralPrimaryDark,
    inversePrimary = NeutralPrimaryLight,
    surfaceTint = Color.Transparent,
    onPrimary = Color(0xFF161616),
    primaryContainer = NightSurfaceHighest,
    onPrimaryContainer = NeutralContainer,
    secondary = Color(0xFFCCCCCC),
    onSecondary = Color(0xFF202020),
    secondaryContainer = Color(0xFF212121),
    onSecondaryContainer = Color(0xFFEEEEEE),
    tertiary = MoonlightSuccess,
    onTertiary = Color(0xFF072D20),
    tertiaryContainer = Color(0xFF153E30),
    onTertiaryContainer = Color(0xFFB8F1D5),
    background = NightBackground,
    onBackground = NightText,
    surface = NightBackground,
    onSurface = NightText,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = NightTextMuted,
    surfaceDim = NightBackground,
    surfaceBright = NightSurfaceHighest,
    surfaceContainerLowest = NightBackground,
    surfaceContainerLow = NightSurface,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurfaceHigh,
    surfaceContainerHighest = NightSurfaceHighest,
    inverseSurface = NightText,
    inverseOnSurface = Color(0xFF262626),
    outline = NightOutline,
    outlineVariant = NightOutlineSoft,
    error = MoonlightDanger,
    onError = Color(0xFF4A0910),
    errorContainer = Color(0xFF4A1F27),
    onErrorContainer = Color(0xFFFFDADD),
  )

private val NeutralLightColorScheme =
  lightColorScheme(
    primary = NeutralPrimaryLight,
    inversePrimary = NeutralPrimaryDark,
    surfaceTint = NeutralPrimaryLight,
    onPrimary = Color.White,
    primaryContainer = NeutralContainer,
    onPrimaryContainer = Color(0xFF202020),
    secondary = Color(0xFF555555),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEEEEE),
    onSecondaryContainer = Color(0xFF222222),
    tertiary = Color(0xFF167553),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC6F4DE),
    onTertiaryContainer = Color(0xFF0A3526),
    background = DayBackground,
    onBackground = DayText,
    surface = DayBackground,
    onSurface = DayText,
    surfaceVariant = DaySurfaceHigh,
    onSurfaceVariant = DayTextMuted,
    surfaceDim = Color(0xFFE4E4E4),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = DaySurface,
    surfaceContainer = Color(0xFFF5F5F5),
    surfaceContainerHigh = DaySurfaceHigh,
    surfaceContainerHighest = DaySurfaceHighest,
    inverseSurface = Color(0xFF303030),
    inverseOnSurface = Color(0xFFF7F7F7),
    outline = DayOutline,
    outlineVariant = DayOutlineSoft,
    error = Color(0xFFBA3343),
    onError = Color.White,
    errorContainer = Color(0xFFFFDADD),
    onErrorContainer = Color(0xFF410009),
  )

@Composable
fun RivoTheme(content: @Composable () -> Unit) {
    val (mode) = rememberThemePreference()
    val darkTheme = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (darkTheme) NeutralDarkColorScheme else NeutralLightColorScheme,
        typography = Typography,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp)),
        content = content,
    )
}
