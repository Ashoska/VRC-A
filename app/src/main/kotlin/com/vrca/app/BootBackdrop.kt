package com.vrca.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import com.vrca.ui.theme.GremlinColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The boot screen's back wall. Two looks being tried (user's brainstorm, 2026-10-16). */
enum class BootBackdrop {
    None,
    /** WhatsApp-style: thin line doodles tiled over the whole wall, faint, at small random tilts. */
    Doodles,
    /** A fixed set of bigger flat characters peeking in from the bottom edge and corners. */
    Crowd
}

/**
 * The community motifs (fish bone from the Kipfel beanie, the cheese joke, a Gnarpy, a kitty) plus
 * small fillers, drawn as simple line art in code. Stand-ins: the user may replace the characters
 * with their own drawings. Every motif draws inside a unit box scaled to [s] px, top-left at 0,0.
 */
private enum class Motif { FishBone, Cheese, Gnarpy, Kitty, Sparkle, Heart, Note, Bubble, Headset, Dot }

@Composable
fun BootBackdropLayer(style: BootBackdrop, modifier: Modifier = Modifier.fillMaxSize()) {
    if (style == BootBackdrop.None) return
    Canvas(modifier) {
        when (style) {
            BootBackdrop.Doodles -> drawDoodles()
            BootBackdrop.Crowd -> drawCrowd()
            BootBackdrop.None -> Unit
        }
    }
}

/** Tiled doodle sheet. Deterministic (same layout every open), one cell per motif. */
private fun DrawScope.drawDoodles() {
    val cell = 78.dp.toPx()
    val ink = GremlinColors.Edge.copy(alpha = 0.6f)
    val stroke = 1.6.dp.toPx()
    // Characters more often than fillers, so the wall reads as "ours".
    val pool = listOf(
        Motif.FishBone, Motif.Cheese, Motif.Gnarpy, Motif.Kitty,
        Motif.Sparkle, Motif.FishBone, Motif.Heart, Motif.Cheese, Motif.Note,
        Motif.Kitty, Motif.Bubble, Motif.Gnarpy, Motif.Headset, Motif.Dot
    )
    val cols = (size.width / cell).toInt() + 2
    val rows = (size.height / cell).toInt() + 2
    for (r in 0 until rows) for (c in 0 until cols) {
        val h = hash(r * 131 + c * 17 + 7)
        val motif = pool[(h % pool.size).toInt()]
        val small = motif == Motif.Dot || motif == Motif.Sparkle || motif == Motif.Heart || motif == Motif.Note
        val s = cell * (if (small) 0.34f else 0.62f) * (0.85f + (h shr 8 and 0xFF) / 255f * 0.3f)
        // Brick offset every other row + jitter, so it never looks like a grid.
        val x = c * cell + (if (r % 2 == 1) cell / 2 else 0f) + ((h shr 16 and 0xFF) / 255f - 0.5f) * cell * 0.3f - cell / 2
        val y = r * cell + ((h shr 24 and 0xFF) / 255f - 0.5f) * cell * 0.3f - cell / 2
        val deg = ((h shr 4 and 0xFF) / 255f - 0.5f) * 50f
        translate(x + (cell - s) / 2, y + (cell - s) / 2) {
            rotate(deg, pivot = Offset(s / 2, s / 2)) { drawMotif(motif, s, ink, null, stroke) }
        }
    }
}

/** A set layout: characters peeking up from the bottom edge, props in the top corners. */
private fun DrawScope.drawCrowd() {
    val fill = GremlinColors.Panel
    val ink = GremlinColors.Edge
    val stroke = 2.dp.toPx()
    val w = size.width; val h = size.height
    fun put(m: Motif, cx: Float, cy: Float, sizeDp: Float, deg: Float = 0f) {
        val s = sizeDp.dp.toPx()
        translate(cx - s / 2, cy - s / 2) {
            rotate(deg, pivot = Offset(s / 2, s / 2)) { drawMotif(m, s, ink, fill, stroke) }
        }
    }
    // The crowd peeks in from the side edges (the panels cover the middle and the bottom),
    // half off-screen, tilted toward the content as if watching the boot.
    put(Motif.Gnarpy, w * 0.015f, h * 0.50f, 150f, 28f)
    put(Motif.Kitty, w * 0.02f, h * 0.86f, 130f, 18f)
    put(Motif.Kitty, w * 0.985f, h * 0.48f, 130f, -22f)
    put(Motif.Gnarpy, w * 0.985f, h * 0.84f, 150f, -26f)
    // Props up top, tucked into the corners.
    put(Motif.FishBone, w * 0.08f, h * 0.12f, 120f, -24f)
    put(Motif.Cheese, w * 0.92f, h * 0.12f, 110f, 14f)
    put(Motif.Sparkle, w * 0.2f, h * 0.27f, 30f)
    put(Motif.Sparkle, w * 0.8f, h * 0.29f, 24f)
    put(Motif.Heart, w * 0.31f, h * 0.12f, 24f, -12f)
    put(Motif.Note, w * 0.7f, h * 0.15f, 28f, 10f)
}

/** Draws [m] in an [s]-px box: [ink] outline, plus a flat [fill] under it when given. */
private fun DrawScope.drawMotif(m: Motif, s: Float, ink: Color, fill: Color?, stroke: Float) {
    val line = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    fun shape(path: Path) {
        if (fill != null) drawPath(path, fill, style = Fill)
        drawPath(path, ink, style = line)
    }
    fun seg(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(ink, p(x1, y1), p(x2, y2), stroke, StrokeCap.Round)
    fun dot(x: Float, y: Float, r: Float) = drawCircle(ink, r * s, p(x, y))
    fun ring(x: Float, y: Float, r: Float) {
        if (fill != null) drawCircle(fill, r * s, p(x, y))
        drawCircle(ink, r * s, p(x, y), style = line)
    }
    when (m) {
        Motif.FishBone -> {
            // Tail fin, spine, ribs, and a round head with an eye.
            shape(Path().apply { moveTo(0.18f * s, 0.5f * s); lineTo(0.04f * s, 0.34f * s); lineTo(0.04f * s, 0.66f * s); close() })
            seg(0.18f, 0.5f, 0.7f, 0.5f)
            for (x in listOf(0.3f, 0.42f, 0.54f)) { seg(x, 0.5f, x + 0.07f, 0.32f); seg(x, 0.5f, x + 0.07f, 0.68f) }
            shape(Path().apply { addOval(androidx.compose.ui.geometry.Rect(0.66f * s, 0.36f * s, 0.96f * s, 0.64f * s)) })
            dot(0.86f, 0.47f, 0.028f)
        }
        Motif.Cheese -> {
            // Emoji-style wedge: top face, front face, holes.
            shape(Path().apply {
                moveTo(0.06f * s, 0.54f * s); lineTo(0.92f * s, 0.30f * s); lineTo(0.94f * s, 0.54f * s)
                lineTo(0.94f * s, 0.76f * s); lineTo(0.06f * s, 0.76f * s); close()
            })
            seg(0.06f, 0.54f, 0.94f, 0.54f)
            ring(0.28f, 0.66f, 0.05f); ring(0.55f, 0.66f, 0.04f); ring(0.78f, 0.63f, 0.045f); ring(0.7f, 0.44f, 0.03f)
        }
        Motif.Kitty -> {
            // Sitting cat: body, tail, head with ears, face.
            shape(Path().apply { addOval(androidx.compose.ui.geometry.Rect(0.26f * s, 0.44f * s, 0.74f * s, 0.94f * s)) })
            val tail = Path().apply { moveTo(0.7f * s, 0.84f * s); quadraticBezierTo(0.98f * s, 0.84f * s, 0.88f * s, 0.56f * s) }
            drawPath(tail, ink, style = line)
            shape(Path().apply {
                moveTo(0.32f * s, 0.36f * s); lineTo(0.33f * s, 0.08f * s); lineTo(0.46f * s, 0.2f * s)
                lineTo(0.54f * s, 0.2f * s); lineTo(0.67f * s, 0.08f * s); lineTo(0.68f * s, 0.36f * s)
                quadraticBezierTo(0.68f * s, 0.52f * s, 0.5f * s, 0.52f * s)
                quadraticBezierTo(0.32f * s, 0.52f * s, 0.32f * s, 0.36f * s); close()
            })
            dot(0.43f, 0.33f, 0.022f); dot(0.57f, 0.33f, 0.022f)
            seg(0.47f, 0.42f, 0.5f, 0.44f); seg(0.5f, 0.44f, 0.53f, 0.42f)
        }
        Motif.Gnarpy -> {
            // Spiky round body with cat ears, two antennae, :3 face, whiskers, stubby legs.
            seg(0.42f, 0.24f, 0.36f, 0.04f); seg(0.58f, 0.24f, 0.64f, 0.04f)
            dot(0.36f, 0.04f, 0.035f); dot(0.64f, 0.04f, 0.035f)
            seg(0.4f, 0.86f, 0.38f, 0.97f); seg(0.6f, 0.86f, 0.62f, 0.97f)
            val body = Path()
            val n = 18
            for (i in 0..n) {
                val a = (i.toFloat() / n) * 2 * PI - PI / 2
                val r = if (i % 2 == 0) 0.37f else 0.31f
                val x = 0.5f + r * cos(a).toFloat(); val y = 0.56f + r * 0.92f * sin(a).toFloat()
                if (i == 0) body.moveTo(x * s, y * s) else body.lineTo(x * s, y * s)
            }
            body.close()
            shape(body)
            shape(Path().apply { moveTo(0.24f * s, 0.36f * s); lineTo(0.22f * s, 0.16f * s); lineTo(0.38f * s, 0.26f * s); close() })
            shape(Path().apply { moveTo(0.76f * s, 0.36f * s); lineTo(0.78f * s, 0.16f * s); lineTo(0.62f * s, 0.26f * s); close() })
            dot(0.4f, 0.52f, 0.03f); dot(0.6f, 0.52f, 0.03f)
            // ":3" mouth
            drawPath(Path().apply {
                moveTo(0.42f * s, 0.6f * s); quadraticBezierTo(0.46f * s, 0.66f * s, 0.5f * s, 0.6f * s)
                quadraticBezierTo(0.54f * s, 0.66f * s, 0.58f * s, 0.6f * s)
            }, ink, style = line)
            seg(0.2f, 0.58f, 0.06f, 0.54f); seg(0.2f, 0.62f, 0.06f, 0.66f)
            seg(0.8f, 0.58f, 0.94f, 0.54f); seg(0.8f, 0.62f, 0.94f, 0.66f)
        }
        Motif.Sparkle -> shape(Path().apply {
            moveTo(0.5f * s, 0f); quadraticBezierTo(0.56f * s, 0.44f * s, s, 0.5f * s)
            quadraticBezierTo(0.56f * s, 0.56f * s, 0.5f * s, s); quadraticBezierTo(0.44f * s, 0.56f * s, 0f, 0.5f * s)
            quadraticBezierTo(0.44f * s, 0.44f * s, 0.5f * s, 0f); close()
        })
        Motif.Heart -> shape(Path().apply {
            moveTo(0.5f * s, 0.9f * s)
            cubicTo(0.05f * s, 0.6f * s, 0.05f * s, 0.15f * s, 0.5f * s, 0.32f * s)
            cubicTo(0.95f * s, 0.15f * s, 0.95f * s, 0.6f * s, 0.5f * s, 0.9f * s); close()
        })
        Motif.Note -> {
            ring(0.32f, 0.78f, 0.14f)
            seg(0.46f, 0.78f, 0.46f, 0.1f)
            seg(0.46f, 0.1f, 0.8f, 0.24f)
        }
        Motif.Bubble -> {
            shape(Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(0.06f * s, 0.14f * s, 0.94f * s, 0.7f * s, 0.18f * s, 0.18f * s))
            })
            shape(Path().apply { moveTo(0.26f * s, 0.68f * s); lineTo(0.22f * s, 0.9f * s); lineTo(0.44f * s, 0.68f * s) })
            dot(0.32f, 0.42f, 0.04f); dot(0.5f, 0.42f, 0.04f); dot(0.68f, 0.42f, 0.04f)
        }
        Motif.Headset -> {
            // VR headset: visor with two lenses and a strap.
            seg(0.04f, 0.46f, 0.1f, 0.46f); seg(0.9f, 0.46f, 0.96f, 0.46f)
            shape(Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(0.1f * s, 0.28f * s, 0.9f * s, 0.68f * s, 0.14f * s, 0.14f * s))
            })
            ring(0.33f, 0.48f, 0.09f); ring(0.67f, 0.48f, 0.09f)
        }
        Motif.Dot -> ring(0.5f, 0.5f, 0.16f)
    }
}

/** Small integer hash (stable across runs) for the doodle layout. */
private fun hash(v: Int): Long {
    var x = v.toLong() * 0x9E3779B1L
    x = x xor (x ushr 15); x *= 0x85EBCA77L; x = x xor (x ushr 13)
    return x and 0xFFFFFFFFL
}
