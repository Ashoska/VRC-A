package com.vrca.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Motion building blocks that are cheap by construction: each takes its animated value as a
 * LAMBDA and reads it only while drawing (or in a graphics layer), so an animation repaints instead
 * of rebuilding the composable every frame. Use these instead of passing `animatable.value` /
 * `by animate*AsState` values into modifiers. Why: on the Quest, with VRChat holding most of the
 * CPU, rebuild-every-frame animations were the boot screen's lag (docs/systems/ui-kit.md
 * "Performance rules").
 */

/** A [width] outline with [corner] corners, inside the bounds, in [color] (read at draw time; transparent = none). */
fun Modifier.animatedOutline(color: () -> Color, width: Dp = 1.dp, corner: Dp = 12.dp): Modifier = drawWithContent {
    drawContent()
    val c = color()
    if (c.alpha <= 0f) return@drawWithContent
    val w = width.toPx()
    val r = corner.toPx() - w / 2
    drawRoundRect(
        c, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(r, r), style = Stroke(w)
    )
}

/** Fades in and widens from 20 % as [progress] goes 0 → 1 (read in the layer). */
fun Modifier.widenIn(progress: () -> Float): Modifier = graphicsLayer {
    val v = progress()
    scaleX = 0.2f + 0.8f * v
    alpha = v
}

/** Alpha read in the layer, so a fade only repaints. */
fun Modifier.layerAlpha(alpha: () -> Float): Modifier = graphicsLayer { this.alpha = alpha() }
