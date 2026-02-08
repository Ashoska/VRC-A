package com.scrapw.chatbox.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

enum class ThemeMode { System, Light, Dark }

fun ThemeMode.isDark(systemIsDark: Boolean): Boolean = when (this) {
    ThemeMode.System -> systemIsDark
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/**
 * “Screenshot-like” feel:
 * - softer contrast
 * - big rounded cards
 * - clean “setup wizard” look
 */
private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
)

/**
 * Spacey palettes: soft contrast, gentle lavender accent.
 * (kept from your current theme approach)
 */
private val SpaceDark = darkColorScheme(
    primary = Color(0xFFCBB7FF),
    secondary = Color(0xFF9AD7FF),
    tertiary = Color(0xFFFFB7D6),
    background = Color(0xFF0B0D14),
    surface = Color(0xFF0F1220),
    surfaceVariant = Color(0xFF171A2B),
    onPrimary = Color(0xFF1B1133),
    onSecondary = Color(0xFF001B2A),
    onTertiary = Color(0xFF2A0A18),
    onBackground = Color(0xFFE9E7FF),
    onSurface = Color(0xFFE9E7FF),
    onSurfaceVariant = Color(0xFFCFCBEA),
    outline = Color(0xFF2B2F45)
)

private val SpaceLight = lightColorScheme(
    primary = Color(0xFF6C4BD6),
    secondary = Color(0xFF1B7AA6),
    tertiary = Color(0xFFC2185B),
    background = Color(0xFFF7F6FF),
    surface = Color(0xFFFBFAFF),
    surfaceVariant = Color(0xFFEAE7F8),
    onPrimary = Color(0xFFFFFFFF),
    onSecondary = Color(0xFFFFFFFF),
    onTertiary = Color(0xFFFFFFFF),
    onBackground = Color(0xFF11111A),
    onSurface = Color(0xFF11111A),
    onSurfaceVariant = Color(0xFF2A2940),
    outline = Color(0xFFB9B3D3)
)

/**
 * Main app theme.
 * UI-only: colors + shapes + system bar styling.
 */
@Composable
fun ChatboxTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = themeMode.isDark(systemDark)

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> SpaceDark
        else -> SpaceLight
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Use surface for a “panel” feel like the screenshots
            window.statusBarColor = colorScheme.surface.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}

/**
 * Overlay theme stays visually consistent with app theme (UI-only).
 */
@Composable
fun OverlayTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = themeMode.isDark(systemDark)

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> SpaceDark
        else -> SpaceLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
