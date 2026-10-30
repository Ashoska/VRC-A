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
 * Lab mock (throwaway, for the user's pick): three idle beats for the MAINTENANCE Boot Cat on its fixing face (left
 * eye a squint line, right eye an open dot, flat mouth, amber with the soft fill), each 3 s and starting/ending on
 * that face. The soft blink (on the open eye) sits at a different point per beat; C has none. `show maintmock`.
 */
object MaintMocks {
    private const val Y = 13.6f
    private const val BEAT = 3000f
    private val PIVOT = Offset(12f, 21.5f)

    private fun sm(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
    private fun blink(p: Float, a: Float, len: Float = 0.10f): Float {
        if (p < a || p > a + len) return 0f
        val k = seg(p, a, a + len)
        return when { k < 0.3f -> sm(k / 0.3f); k < 0.45f -> 1f; else -> 1f - sm((k - 0.45f) / 0.55f) }
    }

    private val head = Path()
    private fun headPath() = head.apply {
        rewind()
        moveTo(3.92f, 10.46f); lineTo(4.3f, 3f); lineTo(8.2f, 7.1f)
        quadraticTo(12f, 5.1f, 15.8f, 7.1f); lineTo(19.9f, 2.6f); lineTo(20.08f, 10.46f)
        arcTo(Rect(Offset(12f, 13.405f), 8.6f), -20.03f, 220.06f, false)
        close()
    }

    /**
     * The fixing face. [dx] moves the open eye, [big] grows it (peering), [squeeze] flattens it a little (a tap
     * landing), [shut] the blink. [tight] tilts the squint down at its inner end. [tongue] pokes a tongue out of the
     * right side of the mouth, [lick] moves it sideways.
     */
    private fun DrawScope.face(c: Color, dx: Float = 0f, big: Float = 0f, squeeze: Float = 0f, shut: Float = 0f,
                               tight: Float = 0f, tongue: Float = 0f, lick: Float = 0f) {
        drawLine(c, Offset(8.1f, Y - 0.35f * tight), Offset(10.5f, Y + 0.45f * tight), 1.9f, StrokeCap.Round)
        val d = 2.6f + 1.2f * big
        val w = d + 0.4f * shut; val h0 = d * (1f - 0.3f * squeeze); val h = h0 + (0.85f - h0) * shut
        val y = Y + 0.35f * shut
        val r = min(w, h) / 2f
        drawRoundRect(c, Offset(14.7f + dx - w / 2f, y - h / 2f), Size(w, h), CornerRadius(r, r))
        val my = Y + 3.8f
        if (tongue > 0f) {
            val tw = 1.5f; val th = 1.7f * tongue; val tx = 12.9f + 0.35f * lick
            drawRoundRect(c, Offset(tx - tw / 2f, my - 0.2f), Size(tw, th), CornerRadius(tw / 2f, tw / 2f))
        }
        drawLine(c, Offset(10.3f, my), Offset(13.7f, my), 1.8f, StrokeCap.Round)
    }

    private inline fun DrawScope.cat(c: Color, f: DrawScope.() -> Unit) {
        val p = headPath()
        drawPath(p, c.copy(alpha = 0.16f))
        drawPath(p, c, style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        f()
    }

    /** A: hammering. Three steady taps: the head dips and squashes a touch, the open eye winces with each; a blink. */
    private fun DrawScope.hammer(c: Color, p: Float) {
        var k = 0f
        for (i in 0 until 3) {
            val a = 0.10f + i * 0.17f
            val q = seg(p, a, a + 0.15f)
            if (p in a..(a + 0.15f)) k = if (q < 0.25f) sm(q / 0.25f) else 1f - sm((q - 0.25f) / 0.75f)
        }
        withTransform({ translate(0f, 0.5f * k); scale(1f + 0.03f * k, 1f - 0.05f * k, PIVOT) }) {
            cat(c) { face(c, squeeze = k, tight = 0.6f * k, shut = blink(p, 0.76f)) }
        }
    }

    /** B: inspecting. Leans in, the open eye grows like it's peering through a loupe and scans; back, a blink. */
    private fun DrawScope.inspect(c: Color, p: Float) {
        val k = sm(seg(p, 0.08f, 0.26f)) * (1 - sm(seg(p, 0.66f, 0.80f)))
        val scan = sin(2 * PI.toFloat() * seg(p, 0.28f, 0.64f)) * k
        withTransform({ rotate(-6f * k, PIVOT); scale(1f + 0.03f * k, 1f + 0.03f * k, PIVOT) }) {
            cat(c) { face(c, dx = 0.8f * scan, big = k, tight = 0.4f * k, shut = blink(p, 0.86f)) }
        }
    }

    /** C: concentrating. The tongue pokes out the side, the squint tightens, a small head tilt; it relaxes. No blink. */
    private fun DrawScope.concentrate(c: Color, p: Float) {
        val k = sm(seg(p, 0.08f, 0.28f)) * (1 - sm(seg(p, 0.66f, 0.84f)))
        val lick = sin(2 * PI.toFloat() * seg(p, 0.30f, 0.62f) * 2) * k
        withTransform({ rotate(4f * k, PIVOT) }) {
            cat(c) { face(c, tight = k, tongue = k, lick = lick, squeeze = 0.25f * k) }
        }
    }

    private val order = ArrayList<Char>()
    private val rnd = java.util.Random(11L)
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
                    'A' -> hammer(c, p)
                    'B' -> inspect(c, p)
                    'C' -> concentrate(c, p)
                    else -> cat(c) { face(c) }
                }
            }
        }
    }

    @Composable
    private fun Cell(kind: Char, ms: () -> Long, size: Int) =
        if (kind == 'N') com.vrca.app.BootCat(com.vrca.app.Mark.Maintenance, 0, Modifier.size(size.dp)) else Cat(kind, ms, Modifier.size(size.dp))

    @Composable
    fun Sheet() {
        val ms = remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) { val t0 = withFrameMillis { it }; while (true) withFrameMillis { ms.longValue = it - t0 } }
        Box(Modifier.fillMaxSize().background(G.Backdrop), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                listOf("Maintenance now" to 'N', "A  Hammering" to 'A', "B  Inspecting" to 'B', "C  Concentrating" to 'C', "Shuffled" to 'S').forEach { (label, k) ->
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
