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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.vrca.ui.theme.GremlinColors as G
import kotlin.math.PI
import kotlin.math.sin

/**
 * Lab mock (throwaway, for the user's pick): three idle beats for the DONE Boot Cat on its happy face (closed happy
 * arcs, the "ω" mouth, green with the soft fill), each 3 s and starting/ending on that face.
 * v2 (user): the eyes keep their exact shape (no squeeze or pulsing); B is a content tilt; C a small hop. `show donemock`.
 */
object DoneMocks {
    private const val Y = 13.6f
    private const val BEAT = 3000f

    private fun sm(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
    private fun bump(p: Float, a: Float, b: Float) = if (p <= a || p >= b) 0f else sin(PI.toFloat() * seg(p, a, b)).let { it * it }

    private val head = Path(); private val eye = Path(); private val mouth = Path()

    /** The head, each ear tip moved by (∓[ex], [ey]) (outward when ex > 0); 0 = the app's HEAD path. */
    private fun headPath(ex: Float = 0f, ey: Float = 0f) = head.apply {
        rewind()
        moveTo(3.92f, 10.46f); lineTo(4.3f - ex, 3f + ey); lineTo(8.2f, 7.1f)
        quadraticTo(12f, 5.1f, 15.8f, 7.1f); lineTo(19.9f + ex, 2.6f + ey); lineTo(20.08f, 10.46f)
        arcTo(Rect(Offset(12f, 13.405f), 8.6f), -20.03f, 220.06f, false)
        close()
    }

    /**
     * The happy face. Eyes: closed arcs [lift] high (1.6 = the app's), [sq] squeezes them (higher, narrower, thicker);
     * [open] on the right eye turns it into an open dot (the wink). Mouth: the "ω", [mw] wide (1 = the app's) and
     * [md] deep (1 = the app's).
     */
    private fun DrawScope.face(c: Color, sq: Float = 0f, lift: Float = 1.6f, open: Float = 0f, mw: Float = 1f, md: Float = 1f, dy: Float = 0f) {
        val y = Y + dy
        val st = Stroke(1.9f + 0.4f * sq, cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (i in 0..1) {
            val cx = if (i == 0) 9.15f else 14.85f
            val k = if (i == 1) open else 0f
            val half = 1.25f * (1f - 0.2f * sq) * (1f - k)
            if (half > 0.05f) {
                eye.rewind(); eye.moveTo(cx - half, y); eye.quadraticTo(cx, y - (lift + 0.8f * sq) * (1f - k), cx + half, y)
                drawPath(eye, c, style = st)
            }
            if (k > 0f) drawCircle(c, 1.3f * k, Offset(cx, y - 0.2f))
        }
        val hw = 1.8f * mw; val d = 1.2f * md
        mouth.rewind()
        mouth.moveTo(12f - hw, y + 3f); mouth.quadraticTo(12f - hw / 2f, y + 3f + d, 12f, y + 3f)
        mouth.quadraticTo(12f + hw / 2f, y + 3f + d, 12f + hw, y + 3f)
        drawPath(mouth, c, style = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    private inline fun DrawScope.cat(c: Color, ex: Float = 0f, ey: Float = 0f, f: DrawScope.() -> Unit) {
        val p = headPath(ex, ey)
        drawPath(p, c.copy(alpha = 0.16f))
        drawPath(p, c, style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        f()
    }

    /** A: purr wiggle. A little side-to-side shimmy, the "ω" widening and deepening; the eyes keep their shape. */
    private fun DrawScope.purr(c: Color, p: Float) {
        val env = kotlin.math.sqrt(bump(p, 0.10f, 0.70f))
        val wig = env * sin(2 * PI.toFloat() * seg(p, 0.10f, 0.70f) * 3)
        rotate(4.5f * wig, Offset(12f, 21.5f)) {
            cat(c) { face(c, mw = 1f + 0.3f * env, md = 1f + 0.35f * env) }
        }
    }

    /** B: content tilt. Slowly tilts to one side as the "ω" grows into a bigger grin, holds, slowly comes back. */
    private fun DrawScope.tilt(c: Color, p: Float) {
        val k = sm(seg(p, 0.10f, 0.34f)) * (1 - sm(seg(p, 0.62f, 0.86f)))
        rotate(-9f * k, Offset(12f, 21.5f)) {
            cat(c) { face(c, mw = 1f + 0.25f * k, md = 1f + 0.5f * k) }
        }
    }

    /** C: a small happy hop with a squash on landing; kept low so a row-size cat stays in its row. */
    private fun DrawScope.hop(c: Color, p: Float) {
        val air = kotlin.math.sqrt(bump(p, 0.18f, 0.42f))
        val crouch = bump(p, 0.10f, 0.20f)
        val land = bump(p, 0.41f, 0.56f)
        val sx = 1f + 0.05f * crouch + 0.07f * land; val sy = 1f - 0.06f * crouch - 0.08f * land + 0.02f * air
        withTransform({ translate(0f, -1.2f * air); scale(sx, sy, Offset(12f, 21.5f)) }) {
            cat(c) { face(c, md = 1f + 0.3f * air) }
        }
    }

    private val order = ArrayList<Char>()
    private val rnd = java.util.Random(3L)
    private fun shuffled(n: Int, set: List<Char>): Char {
        while (order.size <= n) {
            var run = set.shuffled(rnd)
            while (order.isNotEmpty() && run[0] == order.last()) run = run.shuffled(rnd)
            order += run
        }
        return order[n]
    }

    @Composable
    private fun Cat(kind: Char, ms: () -> Long, modifier: Modifier) {
        val c = G.Positive
        Canvas(modifier) {
            val t = ms().toFloat()
            val p = (t % BEAT) / BEAT
            val s = size.minDimension / 24f
            withTransform({ scale(s, s, Offset.Zero) }) {
                when (if (kind == 'S') shuffled((t / BEAT).toInt(), listOf('A', 'B', 'H')) else kind) {
                    'A' -> purr(c, p)
                    'B' -> tilt(c, p)
                    'H' -> hop(c, p)
                    else -> cat(c) { face(c) }
                }
            }
        }
    }

    @Composable
    private fun Cell(kind: Char, ms: () -> Long, size: Int) =
        if (kind == 'N') com.vrca.app.BootCat(com.vrca.app.Mark.Done, 0, Modifier.size(size.dp)) else Cat(kind, ms, Modifier.size(size.dp))

    @Composable
    fun Sheet() {
        val ms = remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) { val t0 = withFrameMillis { it }; while (true) withFrameMillis { ms.longValue = it - t0 } }
        Box(Modifier.fillMaxSize().background(G.Backdrop), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                listOf("Done now" to 'N', "A  Purr wiggle" to 'A', "B  Content tilt" to 'B', "C  Small hop" to 'H', "Shuffled" to 'S').forEach { (label, k) ->
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
