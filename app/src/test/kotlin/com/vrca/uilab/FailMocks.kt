package com.vrca.uilab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.vrca.ui.theme.GremlinColors as G
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Lab mock (throwaway, for the user's pick): three idle beats for the FAILED Boot Cat on its knocked-out face (X eyes,
 * a frown, red with the soft fill), each 3 s and starting/ending on that face. The eyes clearly change in every beat.
 * The X eyes can't blink, so a quick squeeze (the X shrinks and springs back) stands in, placed differently per beat.
 * `show failmock`.
 */
object FailMocks {
    private const val Y = 13.6f
    private const val BEAT = 3000f
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

    private val head = Path(); private val path = Path()
    private fun headPath() = head.apply {
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
    private fun DrawScope.eye(c: Color, x: Float, y: Float, xs: Float = 1f, rot: Float = 0f, sad: Float = 0f, dot: Float = 0f) {
        if (xs > 0.01f) withTransform({ rotate(rot, Offset(x, y)); scale(xs, xs, Offset(x, y)) }) {
            line(c, x - 1.1f, y - 1.1f, x + 1.1f, y + 1.1f); line(c, x + 1.1f, y - 1.1f, x - 1.1f, y + 1.1f)
        }
        if (sad > 0.01f) withTransform({ scale(sad, sad, Offset(x, y)) }) {
            path.rewind(); path.moveTo(x - 1.3f, y + 0.5f); path.quadraticTo(x, y - 1.1f, x + 1.3f, y + 0.5f)
            drawPath(path, c, style = Stroke(1.8f, cap = StrokeCap.Round))
        }
        if (dot > 0.01f) drawCircle(c, 1.3f * dot, Offset(x, y))
    }

    /** The frown; [deep] deepens it, [flat] straightens it, [mx] slides it. */
    private fun DrawScope.mouth(c: Color, deep: Float = 0f, flat: Float = 0f, mx: Float = 0f) {
        val ends = Y + 4.2f + 0.3f * deep; val mid = Y + 3.1f - 0.6f * deep
        val m = mid + (ends - mid) * flat
        path.rewind(); path.moveTo(10.3f + mx, ends); path.quadraticTo(12f + mx, m, 13.7f + mx, ends)
        drawPath(path, c, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    private inline fun DrawScope.cat(c: Color, f: DrawScope.() -> Unit) {
        val p = headPath()
        drawPath(p, c.copy(alpha = 0.16f))
        drawPath(p, c, style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        f()
    }

    /** A: dizzy. The X eyes spin round (opposite ways) as the head wobbles in a small circle; it shakes it off; a squeeze. */
    private fun DrawScope.dizzy(c: Color, p: Float) {
        val q = seg(p, 0.08f, 0.62f)
        val env = sm(seg(p, 0.08f, 0.18f)) * (1 - sm(seg(p, 0.50f, 0.62f)))
        val spin = 360f * sm(q) * 1.5f
        val ang = 2 * PI.toFloat() * q * 2
        val shake = bump(p, 0.63f, 0.71f) * sin(2 * PI.toFloat() * seg(p, 0.63f, 0.71f) * 2)
        val sq = 1f - 0.75f * squeeze(p, 0.80f)
        withTransform({ translate(0.7f * env * kotlin.math.cos(ang) + 0.5f * shake, 0.5f * env * sin(ang)); rotate(4f * env * sin(ang) + 6f * shake, PIVOT) }) {
            cat(c) {
                eye(c, 9.3f, Y, xs = sq, rot = spin); eye(c, 14.7f, Y, xs = sq, rot = -spin)
                mouth(c, mx = 0.5f * env * sin(ang * 1.5f))
            }
        }
    }

    /** B: sniffle. The X eyes soften into sad closed arcs, a tear rolls down, a sniff; back to X. No squeeze. */
    private fun DrawScope.sniffle(c: Color, p: Float) {
        val s = sm(seg(p, 0.08f, 0.20f)) * (1 - sm(seg(p, 0.74f, 0.86f)))
        val x = 1f - sm(seg(p, 0.08f, 0.14f)) * (1 - sm(seg(p, 0.80f, 0.86f)))
        val sniff = bump(p, 0.60f, 0.68f)
        withTransform({ translate(0f, 0.4f * s - 0.6f * sniff); scale(1f, 1f + 0.04f * sniff, PIVOT) }) {
            cat(c) {
                for (ex in floatArrayOf(9.3f, 14.7f)) eye(c, ex, Y, xs = x, sad = s)
                mouth(c, deep = s)
                val t = seg(p, 0.26f, 0.58f)
                if (t > 0f && t < 1f) {
                    val ty = Y + 1.6f + 3.6f * t * t; val a = (if (t < 0.15f) t / 0.15f else 1f) * (1f - sm((t - 0.8f) / 0.2f))
                    val r = 0.8f + 0.15f * t
                    drawCircle(c.copy(alpha = a), r, Offset(15.6f, ty))
                    path.rewind(); path.moveTo(15.6f - r * 0.9f, ty - r * 0.3f); path.lineTo(15.6f, ty - r * 2.1f); path.lineTo(15.6f + r * 0.9f, ty - r * 0.3f); path.close()
                    drawPath(path, c.copy(alpha = a))
                }
            }
        }
    }

    /** C: glitch. Tries to start back up: the eyes flicker to normal dots with a jitter, holds a hopeful look, then fails back to X. */
    private fun DrawScope.glitch(c: Color, p: Float) {
        val on = when {
            p in 0.16f..0.19f -> 1f
            p in 0.24f..0.26f -> 1f
            p in 0.30f..0.48f -> sm(seg(p, 0.30f, 0.32f))
            else -> 0f
        }
        val jit = if (p in 0.15f..0.33f || p in 0.48f..0.56f) sin(p * 900f) else 0f
        val lift = sm(seg(p, 0.32f, 0.38f)) * (1 - sm(seg(p, 0.48f, 0.52f)))
        val droop = sm(seg(p, 0.52f, 0.60f)) * (1 - sm(seg(p, 0.66f, 0.80f)))
        val sq = 1f - 0.75f * squeeze(p, 0.84f)
        withTransform({ translate(0.45f * jit, -0.5f * lift + 0.6f * droop); rotate(-4f * droop, PIVOT) }) {
            cat(c) {
                for (ex in floatArrayOf(9.3f, 14.7f)) eye(c, ex, Y, xs = (1f - on) * sq, dot = on)
                mouth(c, flat = on, deep = droop)
            }
        }
    }

    private val order = ArrayList<Char>()
    private val rnd = java.util.Random(13L)
    private fun shuffled(n: Int): Char {
        while (order.size <= n) {
            var run = listOf('A', 'B', 'C').shuffled(rnd)
            while (order.isNotEmpty() && run[0] == order.last()) run = run.shuffled(rnd)
            order += run
        }
        return order[n]
    }

    @Composable
    private fun Cat(kind: Char, ms: () -> Long, modifier: Modifier) {
        val c = G.Negative
        Canvas(modifier) {
            val t = ms().toFloat()
            val p = (t % BEAT) / BEAT
            val s = size.minDimension / 24f
            withTransform({ scale(s, s, Offset.Zero) }) {
                when (if (kind == 'S') shuffled((t / BEAT).toInt()) else kind) {
                    'A' -> dizzy(c, p)
                    'B' -> sniffle(c, p)
                    'C' -> glitch(c, p)
                    else -> cat(c) { eye(c, 9.3f, Y); eye(c, 14.7f, Y); mouth(c) }
                }
            }
        }
    }

    @Composable
    private fun Cell(kind: Char, ms: () -> Long, size: Int) =
        if (kind == 'N') com.vrca.app.BootCat(com.vrca.app.Mark.Failed, 0, Modifier.size(size.dp)) else Cat(kind, ms, Modifier.size(size.dp))

    @Composable
    fun Sheet() {
        val ms = remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) { val t0 = withFrameMillis { it }; while (true) withFrameMillis { ms.longValue = it - t0 } }
        Box(Modifier.fillMaxSize().background(G.Backdrop), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                listOf("Failed now" to 'N', "A  Dizzy" to 'A', "B  Sniffle" to 'B', "C  Glitch" to 'C', "Shuffled" to 'S').forEach { (label, k) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, color = G.Text, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(10.dp))
                        Cell(k, { ms.longValue }, 140)
                        Spacer(Modifier.height(18.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Cell(k, { ms.longValue }, 36)
                            Spacer(Modifier.width(14.dp))
                            Cell(k, { ms.longValue }, 24)
                        }
                    }
                }
            }
        }
    }
}
