package com.vrca.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/**
 * Utility composable for an always-animating preview line.
 * (Safe even if your project doesn't have getValue delegate imports.)
 */
@Composable
fun NowPlayingPreviewText(
    previewTextProvider: (Float) -> String
) {
    val infinite = rememberInfiniteTransition(label = "NowPlayingPreviewText")
    val tState = infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "NowPlayingPreviewTextT"
    )

    Text(
        text = previewTextProvider(tState.value),
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace
    )
}
