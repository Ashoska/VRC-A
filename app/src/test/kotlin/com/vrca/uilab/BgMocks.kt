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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.vrca.ui.theme.GremlinColors as G
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Lab mock (throwaway, for the user's pick): three idle beats for the BACKGROUND Boot Cat (a check still running after
 * the boot moved on): faint outline, the light circling at half speed (continuous across beats), half-lidded relaxed
 * eyes. Each beat 4.8 s (two light laps), starting/ending on that face. `show bgmock`.
 */
object BgMocks {
    private const val Y = 13.6f
    private const val BEAT = 4800f
    private const val LAP = 2400f
    private val PIVOT = Offset(12f, 21.5f)

    private fun sm(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
    private fun bump(p: Float, a: Float, b: Float) = if (p <= a || p >= b) 0f else sin(PI.toFloat() * seg(p, a, b))
    private fun blink(p: Float, a: Float, len: Float = 0.07f): Float {
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
    private val segPath = Path()

    private fun DrawScope.light(c: Color, at: Float) {
        val total = measure.length; segPath.rewind()
        val a = at * total; val b = a + 0.28f * total
        measure.getSegment(a, min(b, total), segPath, true)
        if (b > total) measure.getSegment(0f, b - total, segPath, true)
        drawPath(segPath, c, style = Stroke(2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    /** Where the light's leading tip is, as a direction from the face centre (unit vector). */
    private fun tipDir(at: Float): Offset {
        val total = measure.length
        val pos = measure.getPosition(((at + 0.28f) % 1f) * total)
        val dx = pos.x - 12f; val dy = pos.y - 12.5f; val l = hypot(dx, dy).coerceAtLeast(0.01f)
        return Offset(dx / l, dy / l)
    }

    /**
     * The half-lidded eyes. [lower] moves the lid: 0 = the resting half lid, 1 = shut (only the lid line left),
     * negative = wider open. ([dx], [dy]) moves both eyes.
     */
    private fun DrawScope.eyes(c: Color, lower: Float = 0f, dx: Float = 0f, dy: Float = 0f, curve: Float = 0f) {
        val cy = Y + 0.35f + dy
        val lid = Y - 0.3f + dy + 1.4f * lower
        for (x0 in floatArrayOf(9.3f, 14.7f)) {
            val x = x0 + dx
            clipRect(x - 2f, lid, x + 2f, cy + 1.2f) { drawCircle(c, 1.05f, Offset(x, cy)) }
            if (curve <= 0f) drawLine(c, Offset(x - 1.5f, lid), Offset(x + 1.5f, lid), 1.5f, StrokeCap.Round)
            else {
                segPath.rewind(); segPath.moveTo(x - 1.5f, lid - 0.3f * curve); segPath.quadraticTo(x, lid + 1.3f * curve, x + 1.5f, lid - 0.3f * curve)
                drawPath(segPath, c, style = Stroke(1.5f, cap = StrokeCap.Round))
            }
        }
    }

    /** A: lazy watch. The sleepy eyes slowly follow the light round the head, then drift back; a soft blink. */
    private fun DrawScope.watch(c: Color, p: Float, at: Float) {
        val k = sm(seg(p, 0.06f, 0.20f)) * (1 - sm(seg(p, 0.70f, 0.84f)))
        val d = tipDir(at)
        eyes(c, lower = blink(p, 0.90f), dx = 1.2f * k * d.x, dy = 0.8f * k * d.y)
    }

    /** B: almost dozing. The lids sink until nearly shut and the head droops; it catches itself (eyes pop wide, a bob), settles. */
    private fun DrawScope.doze(c: Color, p: Float, draw: DrawScope.(DrawScope.() -> Unit) -> Unit) {
        val sink = sm(seg(p, 0.06f, 0.56f)) * (1 - sm(seg(p, 0.58f, 0.62f)))
        val wide = sm(seg(p, 0.58f, 0.62f)) * (1 - sm(seg(p, 0.66f, 0.84f)))
        val bob = bump(p, 0.58f, 0.70f)
        withTransform({ translate(0f, 0.5f * sink - 0.5f * bob); rotate(4f * sink, PIVOT) }) {
            draw { eyes(c, lower = 0.85f * sink - 0.45f * wide) }
        }
    }

    /** C: slow cat blink. A long, slow, content blink: the eyes close into soft happy curves, a small smile shows, a slight tilt; reopen. */
    private fun DrawScope.slowBlink(c: Color, p: Float, draw: DrawScope.(DrawScope.() -> Unit) -> Unit) {
        val k = sm(seg(p, 0.14f, 0.36f)) * (1 - sm(seg(p, 0.58f, 0.80f)))
        val happy = sm(seg(k, 0.6f, 1f))
        withTransform({ rotate(-4f * k, PIVOT) }) {
            draw {
                eyes(c, lower = k, curve = happy)
                if (happy > 0f) {
                    segPath.rewind(); segPath.moveTo(10.9f, Y + 3.5f); segPath.quadraticTo(12f, Y + 3.5f + 1.1f * happy, 13.1f, Y + 3.5f)
                    drawPath(segPath, c.copy(alpha = c.alpha * happy), style = Stroke(1.4f, cap = StrokeCap.Round))
                }
            }
        }
    }

    private val order = ArrayList<Char>()
    private val rnd = java.util.Random(17L)
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
        val accent = MaterialTheme.colorScheme.primary
        Canvas(modifier) {
            val t = ms().toFloat()
            val p = (t % BEAT) / BEAT
            val at = (t / LAP) % 1f
            val s = size.minDimension / 24f
            withTransform({ scale(s, s, Offset.Zero) }) {
                val frame: DrawScope.(DrawScope.() -> Unit) -> Unit = { inner ->
                    drawPath(head, accent.copy(alpha = 0.18f), style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    light(accent.copy(alpha = 0.5f), at)
                    inner()
                }
                val c = accent.copy(alpha = 0.7f)
                when (if (kind == 'S') shuffled((t / BEAT).toInt()) else kind) {
                    'A' -> frame { watch(c, p, at) }
                    'B' -> doze(c, p, frame)
                    'C' -> slowBlink(c, p, frame)
                    else -> frame { eyes(c) }
                }
            }
        }
    }

    @Composable
    private fun Cell(kind: Char, ms: () -> Long, size: Int) =
        if (kind == 'N') com.vrca.app.BootCat(com.vrca.app.Mark.Background, 0, Modifier.size(size.dp)) else Cat(kind, ms, Modifier.size(size.dp))

    @Composable
    fun Sheet() {
        val ms = remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) { val t0 = withFrameMillis { it }; while (true) withFrameMillis { ms.longValue = it - t0 } }
        Box(Modifier.fillMaxSize().background(G.Backdrop), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                listOf("Background now" to 'N', "A  Lazy watch" to 'A', "B  Almost dozing" to 'B', "C  Slow cat blink" to 'C', "Shuffled" to 'S').forEach { (label, k) ->
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
