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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.vrca.ui.theme.GremlinColors as G
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Lab mock (throwaway, for the user's pick): three eye beats for the COUNTDOWN Boot Cat (outline = the time left,
 * draining back to the top middle; eyes = plain dots). Each beat 2.4 s, starting and ending on the plain dots, on its
 * own clock (never tied to how long the countdown is). Columns: the current cat, A, B, C, then the shuffled beats on a
 * SHORT countdown (5 s) and a LONG one (30 s). `show countdownmock`.
 */
object CountdownMocks {
    private const val Y = 13.6f
    private const val BEAT = 2400f
    private val PIVOT = Offset(12f, 21.5f)

    private fun sm(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
    private fun bump(p: Float, a: Float, b: Float) = if (p <= a || p >= b) 0f else sin(PI.toFloat() * seg(p, a, b))
    private fun blink(p: Float, a: Float, len: Float = 0.10f): Float {
        if (p < a || p > a + len) return 0f
        val k = seg(p, a, a + len)
        return when { k < 0.3f -> sm(k / 0.3f); k < 0.45f -> 1f; else -> 1f - sm((k - 0.45f) / 0.55f) }
    }

    private val head = Path().apply {
        moveTo(3.92f, 10.46f); lineTo(4.3f, 3f); lineTo(8.2f, 7.1f)
        quadraticTo(12f, 5.1f, 15.8f, 7.1f); lineTo(19.9f, 2.6f); lineTo(20.08f, 10.46f)
        arcTo(Rect(Offset(12f, 13.405f), 8.6f), -20.03f, 220.06f, false)
        close()
    }
    private val measure = PathMeasure().apply { setPath(head, false) }
    private val start = PathMeasure().apply {
        setPath(Path().apply { moveTo(3.92f, 10.46f); lineTo(4.3f, 3f); lineTo(8.2f, 7.1f); quadraticTo(10.1f, 6.1f, 12f, 6.1f) }, false)
    }.length
    private val tmp = Path()

    /** The timer: the outline from the top middle clockwise, as long as the time [left]. */
    private fun DrawScope.timer(c: Color, left: Float) {
        drawPath(head, c.copy(alpha = 0.25f), style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (left <= 0.001f) return
        val total = measure.length; val b = start + left * total
        tmp.rewind(); measure.getSegment(start, min(b, total), tmp, true); if (b > total) measure.getSegment(0f, b - total, tmp, true)
        drawPath(tmp, c, style = Stroke(2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    /** Direction from the face centre to the timer's shrinking end (unit vector). */
    private fun endDir(left: Float): Offset {
        val total = measure.length
        val pos = measure.getPosition((start + left.coerceAtLeast(0.01f) * total) % total)
        val dx = pos.x - 12f; val dy = pos.y - 12.5f; val l = hypot(dx, dy).coerceAtLeast(0.01f)
        return Offset(dx / l, dy / l)
    }

    /** The dot eyes: [shut] 0 = round, 1 = closed into a soft line; moved by ([dx], [dy]). */
    private fun DrawScope.eyes(c: Color, shut: Float = 0f, dx: Float = 0f, dy: Float = 0f) {
        val w = 2.6f + 0.6f * shut; val h = 2.6f + (0.8f - 2.6f) * shut; val r = min(w, h) / 2f
        for (x0 in floatArrayOf(9.3f, 14.7f)) {
            val x = x0 + dx; val y = Y + dy + 0.3f * shut
            drawRoundRect(c, Offset(x - w / 2f, y - h / 2f), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(r, r))
        }
    }

    /** A: checking the clock. The eyes glance up to where the time is running out, hold, look back; a blink after. */
    private fun DrawScope.check(c: Color, p: Float, left: Float, frame: DrawScope.(DrawScope.() -> Unit) -> Unit) {
        val k = sm(seg(p, 0.10f, 0.22f)) * (1 - sm(seg(p, 0.56f, 0.68f)))
        val d = endDir(left)
        frame { eyes(c, shut = blink(p, 0.78f), dx = 1.4f * k * d.x, dy = 1.0f * k * d.y) }
    }

    /** B: tapping along. Three small even bobs of the head, like tapping a foot while waiting. No blink. */
    private fun DrawScope.tap(c: Color, p: Float, frame: DrawScope.(DrawScope.() -> Unit) -> Unit) {
        var b = 0f
        for (i in 0 until 3) { val a = 0.10f + i * 0.22f; b += bump(p, a, a + 0.14f) }
        withTransform({ translate(0f, 0.7f * b); rotate(2f * b, PIVOT) }) { frame { eyes(c, dy = 0.15f * b) } }
    }

    /** C: deep breath. The eyes close softly, the head rises a little as if breathing in, then settles and the eyes open. */
    private fun DrawScope.breath(c: Color, p: Float, frame: DrawScope.(DrawScope.() -> Unit) -> Unit) {
        val shut = sm(seg(p, 0.10f, 0.24f)) * (1 - sm(seg(p, 0.62f, 0.76f)))
        val rise = sm(seg(p, 0.16f, 0.44f)) * (1 - sm(seg(p, 0.48f, 0.72f)))
        withTransform({ translate(0f, -0.6f * rise); scale(1f + 0.04f * rise, 1f + 0.05f * rise, PIVOT) }) { frame { eyes(c, shut = shut) } }
    }

    private val order = ArrayList<Char>()
    private val rnd = java.util.Random(23L)
    private fun shuffled(n: Int): Char {
        while (order.size <= n) {
            var run = listOf('A', 'B', 'C').shuffled(rnd)
            while (order.isNotEmpty() && run[0] == order.last()) run = run.shuffled(rnd)
            order += run
        }
        return order[n]
    }

    /** Simulated countdown: 1 → 0 over [lenMs] (stepping whole seconds, eased like the app's), then restarts. */
    private fun left(t: Float, lenMs: Float): Float {
        val e = t % (lenMs + 600f)
        return (1f - e / lenMs).coerceAtLeast(0f)
    }

    @Composable
    private fun Cat(kind: Char, lenMs: Float, ms: () -> Long, modifier: Modifier) {
        val c = MaterialTheme.colorScheme.primary
        Canvas(modifier) {
            val t = ms().toFloat()
            val p = (t % BEAT) / BEAT
            val l = left(t, lenMs)
            val s = size.minDimension / 24f
            withTransform({ scale(s, s, Offset.Zero) }) {
                val frame: DrawScope.(DrawScope.() -> Unit) -> Unit = { inner -> timer(c, l); inner() }
                when (if (kind == 'S') shuffled((t / BEAT).toInt()) else kind) {
                    'A' -> check(c, p, l, frame)
                    'B' -> tap(c, p, frame)
                    'C' -> breath(c, p, frame)
                }
            }
        }
    }

    @Composable
    private fun Cell(kind: Char, lenMs: Float, ms: () -> Long, size: Int) =
        if (kind == 'N') com.vrca.app.BootCatCountdown(left(ms().toFloat(), lenMs), Modifier.size(size.dp))
        else Cat(kind, lenMs, ms, Modifier.size(size.dp))

    @Composable
    fun Sheet() {
        val ms = remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) { val t0 = withFrameMillis { it }; while (true) withFrameMillis { ms.longValue = it - t0 } }
        Box(Modifier.fillMaxSize().background(G.Backdrop), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                listOf(Triple("Countdown now", 'N', 10000f), Triple("A  Checking the clock", 'A', 10000f), Triple("B  Tapping along", 'B', 10000f),
                    Triple("C  Deep breath", 'C', 10000f), Triple("Shuffled, 5 s", 'S', 5000f), Triple("Shuffled, 30 s", 'S', 30000f)).forEach { (label, k, len) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, color = G.Text, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(10.dp))
                        Cell(k, len, { ms.longValue }, 130)
                        Spacer(Modifier.height(18.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Cell(k, len, { ms.longValue }, 36)
                            Spacer(Modifier.width(14.dp))
                            Cell(k, len, { ms.longValue }, 24)
                        }
                    }
                }
            }
        }
    }
}
