package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

object ThemeSettings {
    var themeMode by mutableStateOf("system") // "light", "dark", "system"

    fun initialize(context: android.content.Context) {
        val prefs = context.getSharedPreferences("flip_theme_prefs", android.content.Context.MODE_PRIVATE)
        themeMode = prefs.getString("theme_mode", "system") ?: "system"
    }

    fun updateTheme(context: android.content.Context, mode: String) {
        themeMode = mode
        val prefs = context.getSharedPreferences("flip_theme_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("theme_mode", mode).apply()
    }
}

object ThemeColors {
    var textPrimary by mutableStateOf(Color(0xFF111111))
    var textMuted by mutableStateOf(Color(0xFF555555))
    var textSubtle by mutableStateOf(Color(0xFF888888))
    var accentBlueLight by mutableStateOf(Color(0xFFEBEBEB))
    var accentBlueDark by mutableStateOf(Color(0xFF111111))
    var accentGreen by mutableStateOf(Color(0xFF111111))
    var accentRed by mutableStateOf(Color(0xFF888888))
    var accentOrange by mutableStateOf(Color(0xFF555555))
    
    fun update(isDark: Boolean) {
        if (isDark) {
            textPrimary = Color(0xFFFFFFFF)
            textMuted = Color(0xFFB0B0B0)
            textSubtle = Color(0xFF777777)
            accentBlueLight = Color(0xFF161616)
            accentBlueDark = Color(0xFFFFFFFF)
            accentGreen = Color(0xFFAAFFAA)
            accentRed = Color(0xFFFF6B6B)
            accentOrange = Color(0xFFFFB74D)
        } else {
            textPrimary = Color(0xFF111111)
            textMuted = Color(0xFF555555)
            textSubtle = Color(0xFF888888)
            accentBlueLight = Color(0xFFEBEBEB)
            accentBlueDark = Color(0xFF111111)
            accentGreen = Color(0xFF111111)
            accentRed = Color(0xFF888888)
            accentOrange = Color(0xFF555555)
        }
    }
}

private val CleanMinimalismColorScheme = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF333333),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF555555),
    background = Color(0xFFF8F8F8),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111111),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFEEEEEE),
    onSurfaceVariant = Color(0xFF555555),
    outline = Color(0xFFDCDCDC)
)

private val DarkMinimalismColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFFCCCCCC),
    onSecondary = Color(0xFF000000),
    tertiary = Color(0xFF888888),
    background = Color(0xFF000000),
    surface = Color(0xFF121212),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF333333)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    
    // Dynamic state updates for local MinColors
    ThemeColors.update(darkTheme)

    val colorScheme = if (darkTheme) DarkMinimalismColorScheme else CleanMinimalismColorScheme

    // Set Status bar edge-to-edge transparent with light/dark appearance
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        val window = (context as? android.app.Activity)?.window
        if (window != null) {
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
