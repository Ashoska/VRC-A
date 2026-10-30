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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.vrca.ui.theme.GremlinColors as G
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Lab mock (throwaway, for the user's pick): three idle beats for the WARN Boot Cat on its worried face (round open
 * eyes, a small frown, amber with the soft fill), each 3 s and starting/ending on that face. Open eyes, so the soft
 * blink (shuts fast, holds, opens slower; the eye flattens into a short line) sits at a different point per beat.
 * `show warnmock`.
 */
object WarnMocks {
    private const val Y = 13.6f
    private const val BEAT = 3000f

    private fun sm(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
    private fun bump(p: Float, a: Float, b: Float) = if (p <= a || p >= b) 0f else sin(PI.toFloat() * seg(p, a, b)).let { it * it }
    private fun blink(p: Float, a: Float, len: Float = 0.10f): Float {
        if (p < a || p > a + len) return 0f
        val k = seg(p, a, a + len)
        return when { k < 0.3f -> sm(k / 0.3f); k < 0.45f -> 1f; else -> 1f - sm((k - 0.45f) / 0.55f) }
    }

    private val head = Path(); private val mouth = Path()
    private fun headPath() = head.apply {
        rewind()
        moveTo(3.92f, 10.46f); lineTo(4.3f, 3f); lineTo(8.2f, 7.1f)
        quadraticTo(12f, 5.1f, 15.8f, 7.1f); lineTo(19.9f, 2.6f); lineTo(20.08f, 10.46f)
        arcTo(Rect(Offset(12f, 13.405f), 8.6f), -20.03f, 220.06f, false)
        close()
    }

    /**
     * The worried face. Eyes: round dots moved by ([dx], [dy]), [droop] lowers their tops (heavy, uneasy lids),
     * [shut] the blink. Mouth: the frown, deepened by [deep] and bobbed by [wob].
     */
    private fun DrawScope.face(c: Color, dx: Float = 0f, dy: Float = 0f, droop: Float = 0f, shut: Float = 0f, deep: Float = 0f, wob: Float = 0f) {
        val h0 = 2.6f - 0.9f * droop
        val w = 2.6f + 0.4f * shut; val h = h0 + (0.85f - h0) * shut
        val y = Y + dy + 0.45f * droop + 0.35f * shut
        val r = min(w, h) / 2f
        for (x in floatArrayOf(9.3f, 14.7f)) drawRoundRect(c, Offset(x + dx - w / 2f, y - h / 2f), Size(w, h), CornerRadius(r, r))
        val ends = Y + 4.2f + 0.35f * deep; val mid = Y + 2.9f - 0.7f * deep + wob
        mouth.rewind(); mouth.moveTo(10.2f, ends); mouth.quadraticTo(12f, mid, 13.8f, ends)
        drawPath(mouth, c, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    private inline fun DrawScope.cat(c: Color, f: DrawScope.() -> Unit) {
        val p = headPath()
        drawPath(p, c.copy(alpha = 0.16f))
        drawPath(p, c, style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        f()
    }

    /** A: nervous glance. Quick darts to one side, then the other, back, and a blink. */
    private fun DrawScope.glance(c: Color, p: Float) {
        val dx = when {
            p < 0.12f -> 0f
            p < 0.17f -> -1.4f * sm(seg(p, 0.12f, 0.17f))
            p < 0.34f -> -1.4f
            p < 0.40f -> -1.4f + 2.8f * sm(seg(p, 0.34f, 0.40f))
            p < 0.54f -> 1.4f
            p < 0.62f -> 1.4f * (1 - sm(seg(p, 0.54f, 0.62f)))
            else -> 0f
        }
        cat(c) { face(c, dx = dx, shut = blink(p, 0.68f)) }
    }

    /** B: fidget. A few small quick jitters of the head, the frown wobbling with them. */
    private fun DrawScope.fidget(c: Color, p: Float) {
        val env = kotlin.math.sqrt(bump(p, 0.12f, 0.58f))
        val j = env * sin(2 * PI.toFloat() * seg(p, 0.12f, 0.58f) * 6)
        rotate(3f * j, Offset(12f, 21.5f)) {
            cat(c) { face(c, dx = 0.3f * j, wob = 0.35f * j, shut = blink(p, 0.74f)) }
        }
    }

    /** C: uneasy sigh. The head sinks a little as the frown deepens and the eyes droop; it straightens up and blinks. */
    private fun DrawScope.sigh(c: Color, p: Float) {
        val k = sm(seg(p, 0.10f, 0.40f)) * (1 - sm(seg(p, 0.56f, 0.76f)))
        withTransform({ translate(0f, 0.6f * k); scale(1f + 0.02f * k, 1f - 0.04f * k, Offset(12f, 21.5f)) }) {
            cat(c) { face(c, droop = k, deep = k, shut = blink(p, 0.82f)) }
        }
    }

    private val order = ArrayList<Char>()
    private val rnd = java.util.Random(9L)
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
        val c = G.Caution
        Canvas(modifier) {
            val t = ms().toFloat()
            val p = (t % BEAT) / BEAT
            val s = size.minDimension / 24f
            withTransform({ scale(s, s, Offset.Zero) }) {
                when (if (kind == 'S') shuffled((t / BEAT).toInt()) else kind) {
                    'A' -> glance(c, p)
                    'B' -> fidget(c, p)
                    'C' -> sigh(c, p)
                    else -> cat(c) { face(c) }
                }
            }
        }
    }

    @Composable
    private fun Cell(kind: Char, ms: () -> Long, size: Int) =
        if (kind == 'N') com.vrca.app.BootCat(com.vrca.app.Mark.Warn, 0, Modifier.size(size.dp)) else Cat(kind, ms, Modifier.size(size.dp))

    @Composable
    fun Sheet() {
        val ms = remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) { val t0 = withFrameMillis { it }; while (true) withFrameMillis { ms.longValue = it - t0 } }
        Box(Modifier.fillMaxSize().background(G.Backdrop), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                listOf("Warn now" to 'N', "A  Nervous glance" to 'A', "B  Fidget" to 'B', "C  Uneasy sigh" to 'C', "Shuffled" to 'S').forEach { (label, k) ->
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
