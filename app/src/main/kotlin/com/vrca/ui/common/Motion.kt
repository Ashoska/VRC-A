package com.vrca.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

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

/**
 * An indeterminate spinner that keeps turning while the main thread is busy: an
 * AnimatedVectorDrawable (res/drawable/boot_spinner.xml) in an ImageView, which Android animates
 * on the RenderThread. Compose's CircularProgressIndicator is driven from the main thread and
 * freezes with it. Call [preloadRenderThreadSpinner] early (e.g. before a screen's first frame):
 * the drawable's first load parses its XML and animators (~0.2 s on the Quest).
 */
@Composable
fun RenderThreadSpinner(color: Color, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { c ->
            android.widget.ImageView(c).apply {
                val d = c.getDrawable(com.vrca.R.drawable.boot_spinner)!!.mutate()
                setImageDrawable(d)
                (d as? android.graphics.drawable.Animatable)?.start()
                // (Re)start whenever it's attached, so it can never sit as a still arc.
                addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: android.view.View) { (d as? android.graphics.drawable.Animatable)?.start() }
                    override fun onViewDetachedFromWindow(v: android.view.View) {}
                })
            }
        },
        update = { it.imageTintList = android.content.res.ColorStateList.valueOf(color.toArgb()) },
        onRelease = { (it.drawable as? android.graphics.drawable.Animatable)?.stop() },
        modifier = modifier
    )
}

/** Loads the spinner drawable once so the first [RenderThreadSpinner] doesn't stall. */
fun preloadRenderThreadSpinner(ctx: Context) {
    runCatching { ctx.getDrawable(com.vrca.R.drawable.boot_spinner) }
}
