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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.vrca.ui.theme.GremlinColors as G
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Lab mock (throwaway, for the user's pick): three eye beats for the DOWNLOADING Boot Cat (outline = the progress bar
 * filling clockwise from the top middle, a glint running along it; eyes = small v's). Each beat 2.4 s, starting and
 * ending on the plain v's, on their own clock (never tied to the bar's speed). Columns: the current cat, A, B, C,
 * then the shuffled beats on a FAST bar (fills in 3 s) and a SLOW one (30 s). `show downloadmock`.
 */
object DownloadMocks {
    private const val Y = 13.6f
    private const val BEAT = 2400f
    private val PIVOT = Offset(12f, 21.5f)

    private fun sm(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
    private fun bump(p: Float, a: Float, b: Float) = if (p <= a || p >= b) 0f else sin(PI.toFloat() * seg(p, a, b))
    /** The v's blink: they flatten fast, hold, open slower. */
    private fun blink(p: Float, a: Float, len: Float = 0.12f): Float {
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
    private val tmp = Path(); private val eyePath = Path()

    /** The bar: the outline filled to [prog] from the top middle, with the glint. */
    private fun DrawScope.bar(c: Color, prog: Float, t: Float) {
        drawPath(head, c.copy(alpha = 0.25f), style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (prog <= 0.001f) return
        val total = measure.length; val a = start; val b = start + prog * total
        tmp.rewind(); measure.getSegment(a, min(b, total), tmp, true); if (b > total) measure.getSegment(0f, b - total, tmp, true)
        drawPath(tmp, c, style = Stroke(2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val fill = b - a
        if (fill > total * 0.04f) {
            val gl = min(total * 0.1f, fill)
            val g0 = a + ((t / 1400f) % 1f) * (fill + gl) - gl
            val from = max(g0, a); val to = min(g0 + gl, b)
            if (to > from) {
                tmp.rewind()
                if (from < total) measure.getSegment(from, min(to, total), tmp, true)
                if (to > total) measure.getSegment(max(from, total) - total, to - total, tmp, true)
                drawPath(tmp, lerp(c, G.Text, 0.6f), style = Stroke(2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
    /** Direction from the face centre to the bar's growing tip (unit vector). */
    private fun tipDir(prog: Float): Offset {
        val total = measure.length
        val pos = measure.getPosition((start + prog.coerceAtLeast(0.01f) * total) % total)
        val dx = pos.x - 12f; val dy = pos.y - 12.5f; val l = hypot(dx, dy).coerceAtLeast(0.01f)
        return Offset(dx / l, dy / l)
    }

    /** The v eyes: [open] 1 = v, 0 = flat (blink / squint), -1 = flipped into a happy ^. Moved by ([dx], [dy]), faded by [a]. */
    private fun DrawScope.eyes(c: Color, open: Float = 1f, dx: Float = 0f, dy: Float = 0f, a: Float = 1f) {
        val st = Stroke(1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (x0 in floatArrayOf(9.3f, 14.7f)) {
            val x = x0 + dx; val y = Y - 0.1f + dy
            eyePath.rewind(); eyePath.moveTo(x - 1.25f, y - 0.6f * open); eyePath.lineTo(x, y + 0.65f * open); eyePath.lineTo(x + 1.25f, y - 0.6f * open)
            drawPath(eyePath, c.copy(alpha = c.alpha * a), style = st)
        }
    }

    /** A: download nod. The v's glide down and fade (the data going in), drop back in from above, a small nod as they land; a blink. */
    private fun DrawScope.nod(c: Color, p: Float, frame: DrawScope.(DrawScope.() -> Unit) -> Unit) {
        var dy = 0f; var a = 1f
        when {
            p < 0.12f -> {}
            p < 0.38f -> { val k = sm(seg(p, 0.12f, 0.38f)); dy = 1.6f * k; a = 1f - k }
            p < 0.58f -> { val k = sm(seg(p, 0.38f, 0.58f)); dy = -1.6f * (1f - k); a = k }
        }
        val nod = bump(p, 0.52f, 0.70f)
        withTransform({ translate(0f, 1.0f * nod); rotate(3f * nod, PIVOT) }) { frame { eyes(c, open = 1f - blink(p, 0.80f), dy = dy, a = a) } }
    }

    /** B: watching the bar. The eyes turn to the bar's growing tip and follow it, then come back; a blink mid-watch. */
    private fun DrawScope.watch(c: Color, p: Float, prog: Float, frame: DrawScope.(DrawScope.() -> Unit) -> Unit) {
        val k = sm(seg(p, 0.08f, 0.24f)) * (1 - sm(seg(p, 0.72f, 0.88f)))
        val d = tipDir(prog)
        frame { eyes(c, open = 1f - blink(p, 0.46f), dx = 1.5f * k * d.x, dy = 1.1f * k * d.y) }
    }

    /** C: excited. The v's flip into happy ^ ^ with a tiny bounce, hold, flip back. No blink. */
    private fun DrawScope.excited(c: Color, p: Float, frame: DrawScope.(DrawScope.() -> Unit) -> Unit) {
        val f = sm(seg(p, 0.14f, 0.26f)) * (1 - sm(seg(p, 0.62f, 0.74f)))
        val hop = bump(p, 0.18f, 0.40f)
        withTransform({ translate(0f, -0.7f * hop); scale(1f + 0.03f * hop, 1f + 0.03f * hop, PIVOT) }) { frame { eyes(c, open = 1f - 2f * f) } }
    }

    private val order = ArrayList<Char>()
    private val rnd = java.util.Random(19L)
    private fun shuffled(n: Int): Char {
        while (order.size <= n) {
            var run = listOf('A', 'B', 'C').shuffled(rnd)
            while (order.isNotEmpty() && run[0] == order.last()) run = run.shuffled(rnd)
            order += run
        }
        return order[n]
    }

    /** Simulated download: 0 → 1 over [fillMs], holds full for a moment, restarts. */
    private fun progress(t: Float, fillMs: Float) = ((t % (fillMs + 800f)) / fillMs).coerceAtMost(1f)

    @Composable
    private fun Cat(kind: Char, fillMs: Float, ms: () -> Long, modifier: Modifier) {
        val c = MaterialTheme.colorScheme.primary
        Canvas(modifier) {
            val t = ms().toFloat()
            val p = (t % BEAT) / BEAT
            val prog = progress(t, fillMs)
            val s = size.minDimension / 24f
            withTransform({ scale(s, s, Offset.Zero) }) {
                val frame: DrawScope.(DrawScope.() -> Unit) -> Unit = { inner -> bar(c, prog, t); inner() }
                when (if (kind == 'S') shuffled((t / BEAT).toInt()) else kind) {
                    'A' -> nod(c, p, frame)
                    'B' -> watch(c, p, prog, frame)
                    'C' -> excited(c, p, frame)
                }
            }
        }
    }

    @Composable
    private fun Cell(kind: Char, fillMs: Float, ms: () -> Long, size: Int) =
        if (kind == 'N') com.vrca.app.BootCatDownload(progress(ms().toFloat(), fillMs), Modifier.size(size.dp))
        else Cat(kind, fillMs, ms, Modifier.size(size.dp))

    @Composable
    fun Sheet() {
        val ms = remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) { val t0 = withFrameMillis { it }; while (true) withFrameMillis { ms.longValue = it - t0 } }
        Box(Modifier.fillMaxSize().background(G.Backdrop), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                listOf(Triple("Downloading now", 'N', 9000f), Triple("A  Download nod", 'A', 9000f), Triple("B  Watching the bar", 'B', 9000f),
                    Triple("C  Excited", 'C', 9000f), Triple("Shuffled, fast bar", 'S', 3000f), Triple("Shuffled, slow bar", 'S', 30000f)).forEach { (label, k, fill) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, color = G.Text, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(10.dp))
                        Cell(k, fill, { ms.longValue }, 130)
                        Spacer(Modifier.height(18.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Cell(k, fill, { ms.longValue }, 36)
                            Spacer(Modifier.width(14.dp))
                            Cell(k, fill, { ms.longValue }, 24)
                        }
                    }
                }
            }
        }
    }
}
