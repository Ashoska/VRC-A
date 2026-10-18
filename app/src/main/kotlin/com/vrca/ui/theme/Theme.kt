package com.vrca.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.vrca.BuildConfig
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

enum class ThemeMode { System, Light, Dark }

/**
 * Headset-only ripple setup that ZEROES the HOVER alpha. On Meta Quest the
 * controller laser generates pointer-hover events but the hover-EXIT often isn't
 * delivered when the laser moves off quickly, so Compose's hover highlight gets
 * "stuck" on buttons/pills. Making the hovered ripple invisible means there is
 * nothing to stick, while press/focus feedback is preserved. Phone/admin builds
 * keep the normal ripple (a touchscreen has no hover state anyway). Material 3's
 * RippleConfiguration (the old RippleTheme / LocalRippleTheme were retired in Material 3 1.3).
 */
private val NoHoverRippleAlpha = RippleAlpha(
    draggedAlpha = 0.16f,
    focusedAlpha = 0.12f,
    hoveredAlpha = 0f,
    pressedAlpha = 0.12f
)

fun ThemeMode.isDark(systemIsDark: Boolean): Boolean = when (this) {
    ThemeMode.System -> systemIsDark
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

private val GremlinShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val GremlinDark = darkColorScheme(
    primary = GremlinColors.Accent,
    onPrimary = Color(0xFF120B2A),

    secondary = GremlinColors.AccentSoft,
    onSecondary = Color(0xFF130B2C),

    tertiary = GremlinColors.AccentSoft,
    onTertiary = Color(0xFF130B2C),

    background = GremlinColors.Backdrop,
    onBackground = GremlinColors.Text,

    surface = GremlinColors.Panel,
    onSurface = GremlinColors.Text,

    surfaceVariant = GremlinColors.Card,
    onSurfaceVariant = GremlinColors.TextMuted,

    outline = GremlinColors.Edge,
    outlineVariant = GremlinColors.Edge.copy(alpha = 0.7f),

    error = GremlinColors.Error,
    onError = Color(0xFF2B0C0C)
)

private val GremlinLight = lightColorScheme(
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

/** Gremlin, VRC-A's own design system: colours in [GremlinColors], plus shapes and type. */
@Composable
fun GremlinTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false, // OFF: the app always uses the Gremlin palette
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = themeMode.isDark(systemDark)

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> GremlinDark
        else -> GremlinLight
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
        typography = GremlinTypography,
        shapes = GremlinShapes
    ) {
        // Headset build: kill the stuck-hover highlight (Quest laser hover-exit is
        // unreliable), and keep the default panel layout at any window size (FixedPanel:
        // a resize zooms instead of reflowing). Other builds render the content unchanged.
        if (BuildConfig.IS_HEADSET_BUILD) {
            val onSurface = colorScheme.onSurface
            val ripple = remember(onSurface) { RippleConfiguration(color = onSurface, rippleAlpha = NoHoverRippleAlpha) }
            CompositionLocalProvider(LocalRippleConfiguration provides ripple) {
                com.vrca.ui.common.FixedPanel(content)
            }
        } else {
            content()
        }
    }
}

@Composable
fun GremlinOverlayTheme(
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
        darkTheme -> GremlinDark
        else -> GremlinLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = GremlinTypography,
        shapes = MaterialTheme.shapes,
        content = content
    )
}
