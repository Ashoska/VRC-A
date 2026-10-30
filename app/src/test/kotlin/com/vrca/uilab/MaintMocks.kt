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
 * that face. The soft blink (on the open eye) sits at a different point per beat; C has none. Eyes morph between the squint line and the open dot. `show maintmock`.
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
     * One eye centred at ([x], [y]): [open] 0 = the squint line, 1 = the round open eye (it morphs between them), [big]
     * grows the open eye, [ang] tilts it (the squeezed `> <` look), [shut] the soft blink (flattens to a short line).
     */
    private fun DrawScope.eye(c: Color, x: Float, y: Float, open: Float, big: Float = 0f, ang: Float = 0f, shut: Float = 0f) {
        val d = 2.6f * (1f + big)
        var w = 4.3f + (d - 4.3f) * open; var h = 1.9f + (d - 1.9f) * open
        w += (3.0f - w) * shut; h += (0.85f - h) * shut
        val r = min(w, h) / 2f
        withTransform({ rotate(ang, Offset(x, y)) }) { drawRoundRect(c, Offset(x - w / 2f, y - h / 2f), Size(w, h), CornerRadius(r, r)) }
    }

    /**
     * The fixing face (rest: left squint, right open, flat mouth). [lo]/[ro] how open each eye is, [lb]/[rb] how big,
     * [la]/[ra] tilt, [ex]/[ey] move both eyes (looking), [shut] blinks the right eye. Mouth: [mx] slides it sideways,
     * [mo] turns it into a small round "o", [mw] shortens it.
     */
    private fun DrawScope.face(c: Color, lo: Float = 0f, ro: Float = 1f, lb: Float = 0f, rb: Float = 0f, la: Float = 0f, ra: Float = 0f,
                               ex: Float = 0f, ey: Float = 0f, shut: Float = 0f, mx: Float = 0f, mo: Float = 0f, mw: Float = 0f) {
        eye(c, 9.3f + ex, Y + ey, lo, lb, la)
        eye(c, 14.7f + ex, Y + ey, ro, rb, ra, shut)
        val my = Y + 3.8f
        val w = (5.2f - 1.2f * mw) + (2.3f - (5.2f - 1.2f * mw)) * mo; val h = 1.8f + 0.5f * mo
        drawRoundRect(c, Offset(12f + mx - w / 2f, my - h / 2f), Size(w, h), CornerRadius(h / 2f, h / 2f))
    }

    private inline fun DrawScope.cat(c: Color, f: DrawScope.() -> Unit) {
        val p = headPath()
        drawPath(p, c.copy(alpha = 0.16f))
        drawPath(p, c, style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        f()
    }

    /** A: hammering. Three steady taps: on each the head dips and both eyes squeeze into `> <`; a blink after. */
    private fun DrawScope.hammer(c: Color, p: Float) {
        var k = 0f
        for (i in 0 until 3) {
            val a = 0.10f + i * 0.18f
            if (p in a..(a + 0.16f)) { val q = seg(p, a, a + 0.16f); k = if (q < 0.2f) sm(q / 0.2f) else 1f - sm((q - 0.2f) / 0.8f) }
        }
        withTransform({ translate(0f, 0.9f * k); scale(1f + 0.05f * k, 1f - 0.08f * k, PIVOT) }) {
            cat(c) { face(c, ro = 1f - k, la = 22f * k, ra = -22f * k, mw = 0.6f * k, shut = blink(p, 0.76f)) }
        }
    }

    /** B: inspecting. Leans in peering with a big open eye, then swaps: the other eye opens wide, it leans the other way; back, a blink. */
    private fun DrawScope.inspect(c: Color, p: Float) {
        val k = sm(seg(p, 0.08f, 0.22f)) * (1 - sm(seg(p, 0.70f, 0.82f)))
        val sw = sm(seg(p, 0.42f, 0.52f)) * (1 - sm(seg(p, 0.70f, 0.82f)))
        val scan = sin(2 * PI.toFloat() * seg(p, 0.22f, 0.40f)) * k * (1 - sw)
        withTransform({ rotate((-7f + 14f * sw) * k, PIVOT) }) {
            cat(c) { face(c, lo = sw, ro = 1f - sw, lb = 0.5f * sw * k, rb = 0.5f * (1 - sw) * k, ex = 0.7f * scan, shut = blink(p, 0.88f)) }
        }
    }

    /** C: puzzled, then aha. Both eyes open and look up to one side, mouth slid over; "aha": eyes pop, mouth an "o"; settles. No blink. */
    private fun DrawScope.aha(c: Color, p: Float) {
        val think = sm(seg(p, 0.08f, 0.22f)) * (1 - sm(seg(p, 0.46f, 0.52f)))
        val pop = sm(seg(p, 0.46f, 0.52f)) * (1 - sm(seg(p, 0.68f, 0.84f)))
        val open = sm(seg(p, 0.08f, 0.22f)) * (1 - sm(seg(p, 0.68f, 0.84f)))
        val wob = sin(2 * PI.toFloat() * seg(p, 0.24f, 0.44f) * 2) * think
        withTransform({ translate(0f, -0.5f * pop); rotate(-5f * think, PIVOT) }) {
            cat(c) { face(c, lo = open, ro = 1f, lb = 0.3f * pop, rb = 0.3f * pop, ex = -0.7f * think, ey = -0.7f * think, mx = -0.9f * think + 0.3f * wob, mo = pop) }
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
                    'C' -> aha(c, p)
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
                listOf("Maintenance now" to 'N', "A  Hammering" to 'A', "B  Inspecting" to 'B', "C  Puzzled, aha" to 'C', "Shuffled" to 'S').forEach { (label, k) ->
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
