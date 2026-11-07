package dev.rivo.relay.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"), LIGHT("亮色"), DARK("暗色");
}

private const val THEME_MODE = "theme_mode"
private fun SharedPreferences.readThemeMode(): ThemeMode =
    ThemeMode.entries.firstOrNull { it.name == getString(THEME_MODE, null) } ?: ThemeMode.SYSTEM

@Composable
fun rememberThemePreference(): Pair<ThemeMode, (ThemeMode) -> Unit> {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { context.getSharedPreferences("appearance", Context.MODE_PRIVATE) }
    var mode by remember(preferences) { mutableStateOf(preferences.readThemeMode()) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == THEME_MODE) mode = preferences.readThemeMode()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        mode = preferences.readThemeMode()
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return mode to { selected ->
        preferences.edit().putString(THEME_MODE, selected.name).apply()
        mode = selected
    }
}
