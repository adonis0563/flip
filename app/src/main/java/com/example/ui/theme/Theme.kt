package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val CleanMinimalismColorScheme = lightColorScheme(
    primary = MinPrimary,
    secondary = MinSecondary,
    tertiary = MinTertiary,
    background = MinBackground,
    surface = MinSurface,
    onBackground = MinTextPrimary,
    onSurface = MinTextPrimary,
    surfaceVariant = MinSurfaceVariant,
    onSurfaceVariant = MinTextMuted,
    outline = MinBorder
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false, // Force light-first mode for the Clean Minimalism theme
    dynamicColor: Boolean = false, // Set false to maintain our hand-crafted brand theme
    content: @Composable () -> Unit,
) {
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        CleanMinimalismColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
