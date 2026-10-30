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
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The failed Boot Cat's idle (user picks, 2026-10-30): on its knocked-out face (X eyes, a frown, red with the soft
 * fill), three 3 s beats in a shuffled order, each starting and ending on that face (the landing's Face.Dead exactly):
 *  - A, dizzy: the X eyes spin opposite ways while the head wobbles in a small circle; it shakes it off; a squeeze.
 *  - B, sniffle: the X eyes soften into sad closed arcs, the frown quivers, two small sniffs; back to X. No squeeze.
 *  - C, peek (playing dead): one X opens into a normal eye, checks left and right, snaps back to X; a squeeze.
 * X eyes can't blink, so a quick squeeze (the X shrinks and springs back) stands in. Dropped in the mock round: a tear
 * in B and a "glitch" C (eyes flickering to dots), both read odd (user). Drawn in Compose, the clock read only while
 * drawing; the same code runs in the app and the UI Lab.
 */
internal object BootCatFail {
    const val BEAT_MS = 3000f
    private val ORDERS = arrayOf("ABCBAC", "BCACBA", "CABACB", "ACBCAB")
    private const val Y = 13.6f
    private val PIVOT = Offset(12f, 21.5f)

    private fun sm(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
    private fun bump(p: Float, a: Float, b: Float) = if (p <= a || p >= b) 0f else sin(PI.toFloat() * seg(p, a, b))
    /** The squeeze that stands in for a blink: shrinks fast, springs back slower. */
    private fun squeeze(p: Float, a: Float, len: Float = 0.10f): Float {
        if (p < a || p > a + len) return 0f
        val k = seg(p, a, a + len)
        return when { k < 0.3f -> sm(k / 0.3f); k < 0.45f -> 1f; else -> 1f - sm((k - 0.45f) / 0.55f) }
    }

    /** Reused paths, so drawing a frame allocates nothing. */
    class Paths { val head = Path(); val path = Path() }

    private fun Path.head() {
        rewind()
        moveTo(3.92f, 10.46f); lineTo(4.3f, 3f); lineTo(8.2f, 7.1f)
        quadraticTo(12f, 5.1f, 15.8f, 7.1f); lineTo(19.9f, 2.6f); lineTo(20.08f, 10.46f)
        arcTo(Rect(Offset(12f, 13.405f), 8.6f), -20.03f, 220.06f, false)
        close()
    }

    private fun DrawScope.line(c: Color, x1: Float, y1: Float, x2: Float, y2: Float, w: Float = 1.8f) =
        drawLine(c, Offset(x1, y1), Offset(x2, y2), w, StrokeCap.Round)

    /**
     * One eye at ([x], [y]). [x1] how much of the X shows (it shrinks to nothing), [rot] spins it, [sad] how much of the
     * sad closed arc shows, [dot] how much of a normal round open eye shows.
     */
    private fun DrawScope.eye(ps: Paths, c: Color, x: Float, y: Float, xs: Float = 1f, rot: Float = 0f, sad: Float = 0f, dot: Float = 0f) {
        if (xs > 0.01f) withTransform({ rotate(rot, Offset(x, y)); scale(xs, xs, Offset(x, y)) }) {
            line(c, x - 1.1f, y - 1.1f, x + 1.1f, y + 1.1f); line(c, x + 1.1f, y - 1.1f, x - 1.1f, y + 1.1f)
        }
        if (sad > 0.01f) withTransform({ scale(sad, sad, Offset(x, y)) }) {
            ps.path.rewind(); ps.path.moveTo(x - 1.3f, y + 0.5f); ps.path.quadraticTo(x, y - 1.1f, x + 1.3f, y + 0.5f)
            drawPath(ps.path, c, style = Stroke(1.8f, cap = StrokeCap.Round))
        }
        if (dot > 0.01f) drawCircle(c, 1.3f * dot, Offset(x, y))
    }

    /** The frown; [deep] deepens it, [flat] straightens it, [mx] slides it. */
    private fun DrawScope.mouth(ps: Paths, c: Color, deep: Float = 0f, flat: Float = 0f, mx: Float = 0f) {
        val ends = Y + 4.2f + 0.3f * deep; val mid = Y + 3.1f - 0.6f * deep
        val m = mid + (ends - mid) * flat
        ps.path.rewind(); ps.path.moveTo(10.3f + mx, ends); ps.path.quadraticTo(12f + mx, m, 13.7f + mx, ends)
        drawPath(ps.path, c, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    private inline fun DrawScope.cat(ps: Paths, c: Color, f: DrawScope.() -> Unit) {
        ps.head.head()
        drawPath(ps.head, c.copy(alpha = c.alpha * 0.16f))
        drawPath(ps.head, c, style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        f()
    }

    /** A: dizzy. The X eyes spin round (opposite ways) as the head wobbles in a small circle; it shakes it off; a squeeze. */
    private fun DrawScope.dizzy(ps: Paths, c: Color, p: Float) {
        val q = seg(p, 0.08f, 0.62f)
        val env = sm(seg(p, 0.08f, 0.18f)) * (1 - sm(seg(p, 0.50f, 0.62f)))
        val spin = 360f * sm(q) * 1.5f
        val ang = 2 * PI.toFloat() * q * 2
        val shake = bump(p, 0.63f, 0.71f) * sin(2 * PI.toFloat() * seg(p, 0.63f, 0.71f) * 2)
        val sq = 1f - 0.75f * squeeze(p, 0.80f)
        withTransform({ translate(0.7f * env * cos(ang) + 0.5f * shake, 0.5f * env * sin(ang)); rotate(4f * env * sin(ang) + 6f * shake, PIVOT) }) {
            cat(ps, c) {
                eye(ps, c, 9.3f, Y, xs = sq, rot = spin); eye(ps, c, 14.7f, Y, xs = sq, rot = -spin)
                mouth(ps, c, mx = 0.5f * env * sin(ang * 1.5f))
            }
        }
    }

    /** B: sniffle. The X eyes soften into sad closed arcs, the frown quivers, two small sniffs; back to X. No squeeze. */
    private fun DrawScope.sniffle(ps: Paths, c: Color, p: Float) {
        val s = sm(seg(p, 0.08f, 0.20f)) * (1 - sm(seg(p, 0.74f, 0.86f)))
        val x = 1f - sm(seg(p, 0.08f, 0.14f)) * (1 - sm(seg(p, 0.80f, 0.86f)))
        val quiver = if (p in 0.22f..0.66f) sin(p * 260f) * bump(p, 0.22f, 0.66f) else 0f
        val sniff = bump(p, 0.36f, 0.42f) + bump(p, 0.50f, 0.56f)
        withTransform({ translate(0f, 0.4f * s - 0.5f * sniff); scale(1f, 1f + 0.035f * sniff, PIVOT) }) {
            cat(ps, c) {
                for (ex in floatArrayOf(9.3f, 14.7f)) eye(ps, c, ex, Y - 0.25f * sniff, xs = x, sad = s)
                mouth(ps, c, deep = s + 0.25f * quiver)
            }
        }
    }

    /** C: peek (playing dead). One X opens into a normal eye, checks left and right, then snaps back to X. */
    private fun DrawScope.peek(ps: Paths, c: Color, p: Float) {
        val o = sm(seg(p, 0.14f, 0.22f)) * (1 - sm(seg(p, 0.62f, 0.66f)))
        val look = when {
            p < 0.26f -> 0f
            p < 0.32f -> -0.8f * sm(seg(p, 0.26f, 0.32f))
            p < 0.40f -> -0.8f
            p < 0.46f -> -0.8f + 1.6f * sm(seg(p, 0.40f, 0.46f))
            p < 0.54f -> 0.8f
            else -> 0.8f * (1 - sm(seg(p, 0.54f, 0.60f)))
        } * o
        val flop = bump(p, 0.64f, 0.74f)
        val sq = 1f - 0.75f * squeeze(p, 0.84f)
        withTransform({ translate(0f, 0.35f * flop); rotate(-3f * o, PIVOT) }) {
            cat(ps, c) {
                eye(ps, c, 9.3f, Y, xs = sq)
                eye(ps, c, 14.7f + look, Y, xs = (1f - o) * sq, dot = o)
                mouth(ps, c, flat = 0.6f * o)
            }
        }
    }

    /** Draws loop [variant] at [ms] since it started (24-unit grid). */
    fun DrawScope.drawFail(ps: Paths, c: Color, variant: Int, ms: Float) {
        val n = ((ms / BEAT_MS).toInt()) % 6
        val p = (ms % BEAT_MS) / BEAT_MS
        when (ORDERS[Math.floorMod(variant, ORDERS.size)][n]) {
            'A' -> dizzy(ps, c, p)
            'B' -> sniffle(ps, c, p)
            else -> peek(ps, c, p)
        }
    }
}

/** The failed cat idling, looping from when it appears; [color] is read while drawing. */
@Composable
internal fun FailLoop(variant: Int, color: () -> Color, modifier: Modifier) {
    val ps = remember { BootCatFail.Paths() }
    val ms = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { val t0 = withFrameMillis { it }; while (true) withFrameMillis { ms.longValue = it - t0 } }
    Canvas(modifier) {
        val s = size.minDimension / 24f
        withTransform({ scale(s, s, Offset.Zero) }) { with(BootCatFail) { drawFail(ps, color(), variant, ms.longValue.toFloat()) } }
    }
}
