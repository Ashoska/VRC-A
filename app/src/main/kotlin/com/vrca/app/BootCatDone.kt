package com.vrca.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The done Boot Cat's idle (user picks, 2026-10-29): on its happy face (closed happy arcs, the "ω" mouth, green with
 * the soft fill), three 3 s beats in a shuffled order, each starting and ending on that face. The eyes keep their exact
 * shape throughout (a squeeze/blink on the closed arcs read as pulsing, user):
 *  - A, purr wiggle: three little side-to-side shimmies, the "ω" widening and deepening.
 *  - B, content tilt: slowly tilts to one side as the "ω" grows into a bigger grin, holds, slowly comes back.
 *  - C, small hop: a crouch, a low hop (kept small so a row-size cat stays in its row), a squash on landing.
 * Drawn in Compose, the clock read only while drawing; the same code runs in the app and the UI Lab.
 */
internal object BootCatDone {
    const val BEAT_MS = 3000f
    private val ORDERS = arrayOf("ABCBAC", "BCACBA", "CABACB", "ACBCAB")
    private const val Y = 13.6f

    private fun sm(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
    private fun bump(p: Float, a: Float, b: Float) = if (p <= a || p >= b) 0f else sin(PI.toFloat() * seg(p, a, b)).let { it * it }

    /** Reused paths, so drawing a frame allocates nothing. */
    class Paths { val head = Path(); val eye = Path(); val mouth = Path() }

    private fun Path.head() {
        rewind()
        moveTo(3.92f, 10.46f); lineTo(4.3f, 3f); lineTo(8.2f, 7.1f)
        quadraticTo(12f, 5.1f, 15.8f, 7.1f); lineTo(19.9f, 2.6f); lineTo(20.08f, 10.46f)
        arcTo(Rect(Offset(12f, 13.405f), 8.6f), -20.03f, 220.06f, false)
        close()
    }

    /** The happy face (the app's Face.Happy), the "ω" [mw] wide and [md] deep (1 = as it rests). */
    private fun DrawScope.face(ps: Paths, c: Color, mw: Float = 1f, md: Float = 1f) {
        val st = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (cx in floatArrayOf(9.15f, 14.85f)) {
            ps.eye.rewind(); ps.eye.moveTo(cx - 1.25f, Y); ps.eye.quadraticTo(cx, Y - 1.6f, cx + 1.25f, Y)
            drawPath(ps.eye, c, style = st)
        }
        val hw = 1.8f * mw; val d = 1.2f * md
        ps.mouth.rewind()
        ps.mouth.moveTo(12f - hw, Y + 3f); ps.mouth.quadraticTo(12f - hw / 2f, Y + 3f + d, 12f, Y + 3f)
        ps.mouth.quadraticTo(12f + hw / 2f, Y + 3f + d, 12f + hw, Y + 3f)
        drawPath(ps.mouth, c, style = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    private inline fun DrawScope.cat(ps: Paths, c: Color, f: DrawScope.() -> Unit) {
        ps.head.head()
        drawPath(ps.head, c.copy(alpha = c.alpha * 0.16f))
        drawPath(ps.head, c, style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        f()
    }

    /** Draws loop [variant] at [ms] since it started (24-unit grid). */
    fun DrawScope.drawDone(ps: Paths, c: Color, variant: Int, ms: Float) {
        val n = ((ms / BEAT_MS).toInt()) % 6
        val p = (ms % BEAT_MS) / BEAT_MS
        when (ORDERS[Math.floorMod(variant, ORDERS.size)][n]) {
            'A' -> {
                val env = sqrt(bump(p, 0.10f, 0.70f))
                val wig = env * sin(2 * PI.toFloat() * seg(p, 0.10f, 0.70f) * 3)
                rotate(4.5f * wig, Offset(12f, 21.5f)) { cat(ps, c) { face(ps, c, 1f + 0.3f * env, 1f + 0.35f * env) } }
            }
            'B' -> {
                val k = sm(seg(p, 0.10f, 0.34f)) * (1 - sm(seg(p, 0.62f, 0.86f)))
                rotate(-9f * k, Offset(12f, 21.5f)) { cat(ps, c) { face(ps, c, 1f + 0.25f * k, 1f + 0.5f * k) } }
            }
            else -> {
                val air = sqrt(bump(p, 0.18f, 0.42f)); val crouch = bump(p, 0.10f, 0.20f); val land = bump(p, 0.41f, 0.56f)
                val sx = 1f + 0.05f * crouch + 0.07f * land; val sy = 1f - 0.06f * crouch - 0.08f * land + 0.02f * air
                withTransform({ translate(0f, -1.2f * air); scale(sx, sy, Offset(12f, 21.5f)) }) {
                    cat(ps, c) { face(ps, c, md = 1f + 0.3f * air) }
                }
            }
        }
    }
}

/** The done cat idling, looping from when it appears; [color] is read while drawing. */
@Composable
internal fun DoneLoop(variant: Int, color: () -> Color, modifier: Modifier) {
    val ps = remember { BootCatDone.Paths() }
    val ms = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { val t0 = withFrameMillis { it }; while (true) withFrameMillis { ms.longValue = it - t0 } }
    Canvas(modifier) {
        val s = size.minDimension / 24f
        withTransform({ scale(s, s, Offset.Zero) }) { with(BootCatDone) { drawDone(ps, color(), variant, ms.longValue.toFloat()) } }
    }
}
