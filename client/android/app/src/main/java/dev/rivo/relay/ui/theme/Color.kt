package dev.rivo.relay.ui.theme

import androidx.compose.ui.graphics.Color

// Neutral interface palette; semantic status colors remain distinct.
val NeutralPrimaryDark = Color(0xFFEEEEEE)
val NeutralPrimaryLight = Color(0xFF111111)
val NeutralContainer = Color(0xFFEAEAEA)
val MoonlightSuccess = Color(0xFF45C58A)
val MoonlightWarning = Color(0xFFF3AE62)
val MoonlightDanger = Color(0xFFFF909A)

// Near-black canvas with subtle neutral layers matching the reference interface.
val NightBackground = Color(0xFF111111)
val NightSurface = Color(0xFF1F1F1F)
val NightSurfaceHigh = Color(0xFF262626)
val NightSurfaceHighest = Color(0xFF303030)
val NightOutline = Color(0xFF737373)
val NightOutlineSoft = Color(0xFF2C2C2C)
val NightText = Color(0xFFE0E0E0)
val NightTextMuted = Color(0xFFAAAAAA)

val DayBackground = Color(0xFFFAFAFA)
val DaySurface = Color(0xFFFFFFFF)
val DaySurfaceHigh = Color(0xFFF0F0F0)
val DaySurfaceHighest = Color(0xFFE6E6E6)
val DayOutline = Color(0xFF777777)
val DayOutlineSoft = Color(0xFFDDDDDD)
val DayText = Color(0xFF111111)
val DayTextMuted = Color(0xFF686868)

// List rows stay quieter than search fields and navigation surfaces.
val androidx.compose.material3.ColorScheme.listItemBackground: Color
    get() = if (background == NightBackground) Color(0xFF191919) else surfaceContainerLow
