package com.example.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode(val storageKey: String, val displayName: String) {
    SYSTEM("system", "System default"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark")
}

/**
 * Manages app theme preference and state.
 * Synchronously loads from SharedPreferences before the first frame to avoid theme flashing.
 */
object ThemeManager {
    private const val PREFS_NAME = "flip_prefs"
    private const val KEY_THEME = "app_theme"

    private var prefs: SharedPreferences? = null
    private val _themeMode = MutableStateFlow(AppThemeMode.SYSTEM)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            val saved = p.getString(KEY_THEME, AppThemeMode.SYSTEM.storageKey)
            _themeMode.value = AppThemeMode.entries.find { it.storageKey == saved } ?: AppThemeMode.SYSTEM
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        _themeMode.value = mode
        prefs?.edit()?.putString(KEY_THEME, mode.storageKey)?.apply()
    }
}
