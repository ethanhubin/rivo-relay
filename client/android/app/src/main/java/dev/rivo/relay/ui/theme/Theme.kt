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

private val MoonlightDarkColorScheme =
  darkColorScheme(
    primary = MoonlightPurple,
    onPrimary = Color(0xFF21183D),
    primaryContainer = Color(0xFF332B57),
    onPrimaryContainer = MoonlightPurpleSoft,
    secondary = Color(0xFFC8BEFF),
    onSecondary = Color(0xFF292047),
    secondaryContainer = Color(0xFF29243E),
    onSecondaryContainer = Color(0xFFE8E1FF),
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
    surfaceContainerLowest = Color(0xFF0A0A0E),
    surfaceContainerLow = Color(0xFF131319),
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurfaceHigh,
    surfaceContainerHighest = NightSurfaceHighest,
    inverseSurface = NightText,
    inverseOnSurface = Color(0xFF26232C),
    outline = NightOutline,
    outlineVariant = NightOutlineSoft,
    error = MoonlightDanger,
    onError = Color(0xFF4A0910),
    errorContainer = Color(0xFF4A1F27),
    onErrorContainer = Color(0xFFFFDADD),
  )

private val MoonlightLightColorScheme =
  lightColorScheme(
    primary = MoonlightPurpleStrong,
    onPrimary = Color.White,
    primaryContainer = MoonlightPurpleSoft,
    onPrimaryContainer = Color(0xFF2C2350),
    secondary = Color(0xFF665A91),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E3FA),
    onSecondaryContainer = Color(0xFF2B2441),
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
    surfaceDim = Color(0xFFE4E1EA),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = DaySurface,
    surfaceContainer = Color(0xFFF4F2F8),
    surfaceContainerHigh = DaySurfaceHigh,
    surfaceContainerHighest = DaySurfaceHighest,
    inverseSurface = Color(0xFF302E37),
    inverseOnSurface = Color(0xFFF7F4FC),
    outline = DayOutline,
    outlineVariant = DayOutlineSoft,
    error = Color(0xFFBA3343),
    onError = Color.White,
    errorContainer = Color(0xFFFFDADD),
    onErrorContainer = Color(0xFF410009),
  )

@Composable
fun RivoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) MoonlightDarkColorScheme else MoonlightLightColorScheme,
        typography = Typography,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp)),
        content = content,
    )
}
