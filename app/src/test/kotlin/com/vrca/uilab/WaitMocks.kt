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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.vrca.ui.theme.GremlinColors as G
import kotlin.math.PI
import kotlin.math.sin

/**
 * Lab mock (throwaway, for the user's pick): three idle animations for the WAITING (asleep) Boot Cat, beside the
 * current still one. Each is one 3.6 s beat that starts and ends on the exact sleepy face, so any order hands over
 * cleanly. He never opens his eyes; in place of a blink the closed eye lines scrunch (a sleepy squeeze), at a
 * different point per beat. `show waitmock`.
 */
object WaitMocks {
    private const val Y = 13.6f
    private const val BEAT = 3600f

    private fun sm(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
    /** A soft there-and-back bump over [a]..[b] (0 -> 1 -> 0). */
    private fun bump(p: Float, a: Float, b: Float) = if (p <= a || p >= b) 0f else sin(PI.toFloat() * seg(p, a, b)).let { it * it }
    /** The sleepy squeeze at [a] of the beat (lasts 0.12): 0 relaxed .. 1 scrunched. */
    private fun squeeze(p: Float, a: Float) = bump(p, a, a + 0.12f)

    /** The head, its left ear tip moved by ([ex], [ey]) (the twitch). */
    private fun head(ex: Float = 0f, ey: Float = 0f) =
        PathParser().parsePathString("M3.92,10.46 L${4.3f + ex},${3f + ey} L8.2,7.1 Q12,5.1 15.8,7.1 L19.9,2.6 L20.08,10.46 A8.6,8.6 0 1,1 3.92,10.46 Z").toPath()
    private val HEAD = head()

    /**
     * The sleepy face: eye lines (scrunched by [sq]: shorter, a touch thicker, nudged together), lowered by [eyeDy];
     * the mouth line [mw] wide, raised by [mouthDy].
     */
    private fun DrawScope.face(c: Color, sq: Float = 0f, eyeDy: Float = 0f, mw: Float = 2f, mouthDy: Float = 0f) {
        val half = 1.2f * (1f - 0.45f * sq); val w = 1.9f + 0.6f * sq; val y = Y + eyeDy
        for ((cx, inward) in listOf(9.3f to 1f, 14.7f to -1f)) {
            val x = cx + 0.35f * sq * inward
            drawLine(c, Offset(x - half, y), Offset(x + half, y), w, StrokeCap.Round)
        }
        drawLine(c, Offset(12f - mw / 2f, Y + 3.6f + mouthDy), Offset(12f + mw / 2f, Y + 3.6f + mouthDy), 1.8f, StrokeCap.Round)
    }

    private fun DrawScope.outline(c: Color, path: androidx.compose.ui.graphics.Path = HEAD) =
        drawPath(path, c, style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))

    /** A: breathing. A slow breath in (the head swells a little from the chin, the eyes ease up) and out. */
    private fun DrawScope.breathe(c: Color, p: Float) {
        val b = sin(PI.toFloat() * seg(p, 0.05f, 0.90f)).let { it * it }
        val sq = squeeze(p, 0.72f)
        withTransform({ scale(1f + 0.035f * b, 1f + 0.08f * b, Offset(12f, 21.5f)) }) {
            outline(c)
            face(c, sq, eyeDy = -0.35f * b, mw = 2f + 0.5f * b)
        }
    }

    /** B: nodding off. The head slowly droops to one side, catches itself with a little jolt, settles back. */
    private fun DrawScope.nod(c: Color, p: Float) {
        val droop = sm(seg(p, 0.08f, 0.55f))
        val back = sm(seg(p, 0.55f, 0.62f))          // the catch: quick
        val over = bump(p, 0.58f, 0.80f)              // a little past upright, then settling
        val rot = 8f * droop * (1 - back) - 2.5f * over
        val dy = 0.7f * droop * (1 - back)
        val sq = squeeze(p, 0.56f)                    // scrunches as he catches himself
        withTransform({ translate(0f, dy); rotate(rot, Offset(12f, 21.5f)) }) {
            outline(c)
            face(c, sq)
        }
    }

    /** C: dreaming. The left ear twitches twice, then the mouth mumbles (narrows and wobbles), a squeeze between. */
    private fun DrawScope.dream(c: Color, p: Float) {
        val tw = bump(p, 0.12f, 0.20f) + 0.7f * bump(p, 0.23f, 0.31f)
        val mum = if (p in 0.48f..0.80f) sin(2 * PI.toFloat() * seg(p, 0.48f, 0.80f) * 3) else 0f
        val env = bump(p, 0.46f, 0.82f).let { kotlin.math.sqrt(it) }
        val sq = squeeze(p, 0.34f)
        outline(c, head(-1.1f * tw, 1.5f * tw))
        face(c, sq, mw = 2f - 0.8f * env + 0.5f * env * mum, mouthDy = -0.4f * env * kotlin.math.abs(mum))
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
        val c = lerp(G.Edge, G.TextMuted, 0.08f) // markColor(Waiting)
        Canvas(modifier) {
            val t = ms().toFloat()
            val p = (t % BEAT) / BEAT
            val s = size.minDimension / 24f
            withTransform({ scale(s, s, Offset.Zero) }) {
                when (if (kind == 'S') shuffled((t / BEAT).toInt()) else kind) {
                    'A' -> breathe(c, p)
                    'B' -> nod(c, p)
                    'C' -> dream(c, p)
                    else -> { outline(c); face(c) }
                }
            }
        }
    }

    @Composable
    fun Sheet() {
        val ms = remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) { val t0 = withFrameMillis { it }; while (true) withFrameMillis { ms.longValue = it - t0 } }
        Box(Modifier.fillMaxSize().background(G.Backdrop), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                listOf("Now" to ' ', "A  Breathing" to 'A', "B  Nodding off" to 'B', "C  Dreaming" to 'C', "Shuffled" to 'S').forEach { (label, k) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, color = G.Text, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(10.dp))
                        Cat(k, { ms.longValue }, Modifier.size(170.dp))
                        Spacer(Modifier.height(18.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Cat(k, { ms.longValue }, Modifier.size(36.dp))
                            Spacer(Modifier.width(16.dp))
                            Cat(k, { ms.longValue }, Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}
