// app/src/main/kotlin/com/scrapw/chatbox/ui/theme/Theme.kt
package com.scrapw.chatbox.ui.theme

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

/**
 * Slime-like: big rounded panels/cards.
 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * SlimeVR-ish Dark Scheme (matches screenshots vibe: deep navy + purple accent).
 * NOTE: This intentionally avoids "dynamic colors" unless you enable them.
 */
private val SlimeDark = darkColorScheme(
    primary = SlimePurple,
    onPrimary = Color(0xFF140B2E),

    secondary = SlimeCyan,
    onSecondary = Color(0xFF001521),

    tertiary = SlimePurpleSoft,
    onTertiary = Color(0xFF120A27),

    background = SlimeBg,
    onBackground = SlimeText,

    surface = SlimeSurface,
    onSurface = SlimeText,

    surfaceVariant = SlimeSurface2,
    onSurfaceVariant = SlimeTextMuted,

    outline = SlimeOutline,
    outlineVariant = SlimeOutline.copy(alpha = 0.65f),

    error = SlimeError,
    onError = Color(0xFF2A0B0B)
)

/**
 * Optional Light Scheme: still “Slime” accent, but readable.
 * (You can ignore this if you stay dark-only.)
 */
private val SlimeLight = lightColorScheme(
    primary = Color(0xFF5E3DFF),
    onPrimary = Color.White,

    secondary = Color(0xFF0B6AA0),
    onSecondary = Color.White,

    tertiary = Color(0xFF7A5CFF),
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

/**
 * Main app theme.
 * UI-only: colors + shapes + system bar styling.
 */
@Composable
fun ChatboxTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false, // ✅ default OFF so it looks like Slime (not device theme)
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
            // Slime-style: bars match surface (not pure black)
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
        shapes = AppShapes,
        content = content
    )
}
