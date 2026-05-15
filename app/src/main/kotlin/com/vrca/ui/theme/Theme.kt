package com.vrca.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
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

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val SlimeDark = darkColorScheme(
    primary = SlimePurple,
    onPrimary = Color(0xFF120B2A),

    secondary = SlimePurple2,
    onSecondary = Color(0xFF130B2C),

    tertiary = SlimePurple2,
    onTertiary = Color(0xFF130B2C),

    background = SlimeBg,
    onBackground = SlimeText,

    surface = SlimeSurface,
    onSurface = SlimeText,

    surfaceVariant = SlimeSurface2,
    onSurfaceVariant = SlimeMuted,

    outline = SlimeOutline,
    outlineVariant = SlimeOutline.copy(alpha = 0.7f),

    error = SlimeError,
    onError = Color(0xFF2B0C0C)
)

private val SlimeLight = lightColorScheme(
    primary = Color(0xFF5A3BFF),
    onPrimary = Color.White,

    secondary = Color(0xFF6A52FF),
    onSecondary = Color.White,

    tertiary = Color(0xFF6A52FF),
    onTertiary = Color.White,

    background = Color(0xFFF5F8FF),
    onBackground = Color(0xFF0B1320),

    surface = Color(0xFFFAFBFF),
    onSurface = Color(0xFF0B1320),

    surfaceVariant = Color(0xFFE8EEFF),
    onSurfaceVariant = Color(0xFF2A3852),

    outline = Color(0xFFB6C5E5),
    outlineVariant = Color(0xFFCCD7F2),

    error = Color(0xFFD83B3B),
    onError = Color.White
)

@Composable
fun VrcaTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false, // IMPORTANT: OFF by default to match Slime theme
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = themeMode.isDark(systemDark)

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> SlimeDark
        else -> SlimeLight
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
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

@Composable
fun OverlayTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = themeMode.isDark(systemDark)

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> SlimeDark
        else -> SlimeLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = MaterialTheme.shapes,
        content = content
    )
}
