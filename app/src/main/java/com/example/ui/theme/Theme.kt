package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

// Clean Light Monochrome Color Scheme
private val LightMonochromeColorScheme = lightColorScheme(
    primary = MinPrimary,
    onPrimary = Color(0xFFFFFFFF),
    secondary = MinSecondary,
    onSecondary = Color(0xFFFFFFFF),
    tertiary = MinTertiary,
    onTertiary = Color(0xFFFFFFFF),
    background = MinBackground,
    onBackground = MinTextPrimary,
    surface = MinSurface,
    onSurface = MinTextPrimary,
    surfaceVariant = MinSurfaceVariant,
    onSurfaceVariant = MinTextMuted,
    outline = MinBorder
)

// Clean Dark Monochrome Color Scheme: near-black background, dark-gray cards, white text
private val DarkMonochromeColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFFCCCCCC),
    onSecondary = Color(0xFF000000),
    tertiary = Color(0xFFAAAAAA),
    onTertiary = Color(0xFF000000),
    background = Color(0xFF121212),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = Color(0xFFAAAAAA),
    outline = Color(0xFF333333)
)

@Composable
fun MyApplicationTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> systemDark
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    val colorScheme = if (isDark) DarkMonochromeColorScheme else LightMonochromeColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
                insetsController.show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
