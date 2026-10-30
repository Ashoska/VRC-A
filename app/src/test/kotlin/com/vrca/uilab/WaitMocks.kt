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
     * The sleepy face. Eyes: closed lines, scrunched by [sq] (shorter, thicker, nudged together), lowered by [eyeDy],
     * shifted by [eyeDx], curved by [eyeCurve] (+ sinks into a soft "︶", - lifts into a happy "^"). Mouth: [mw] wide at
     * [mouthDx], opened by [open] (the line parts into a little oval), curved into a smile by [smile]. All zero (mw 2) =
     * the plain sleepy face every beat starts and ends on.
     */
    private fun DrawScope.face(
        c: Color, sq: Float = 0f, eyeDy: Float = 0f, eyeDx: Float = 0f, eyeCurve: Float = 0f,
        mw: Float = 2f, mouthDx: Float = 0f, open: Float = 0f, smile: Float = 0f,
    ) {
        val half = 1.2f * (1f - 0.45f * sq); val w = 1.9f + 0.6f * sq; val y = Y + eyeDy
        val st = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        for ((cx, inward) in listOf(9.3f to 1f, 14.7f to -1f)) {
            val x = cx + eyeDx + 0.35f * sq * inward
            drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(x - half, y); quadraticTo(x, y + 2f * eyeCurve, x + half, y) }, c, style = st)
        }
        val my = Y + 3.6f; val mx = 12f + mouthDx; val h = 1.6f * open
        drawPath(androidx.compose.ui.graphics.Path().apply {
            moveTo(mx - mw / 2f, my)
            quadraticTo(mx, my - h + 2f * smile, mx + mw / 2f, my)
            quadraticTo(mx, my + h + 2f * smile, mx - mw / 2f, my)
        }, c, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    private fun DrawScope.outline(c: Color, path: androidx.compose.ui.graphics.Path = HEAD) =
        drawPath(path, c, style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))

    /** A: breathing. Breath in: the head swells from the chin and the eyes sink into soft curves. Out: a little "o" puff. */
    private fun DrawScope.breathe(c: Color, p: Float) {
        val inhale = bump(p, 0.04f, 0.56f)
        val puff = bump(p, 0.50f, 0.92f)
        val b = sin(PI.toFloat() * seg(p, 0.05f, 0.90f)).let { it * it }
        val sq = squeeze(p, 0.30f)
        withTransform({ scale(1f + 0.035f * b, 1f + 0.08f * b, Offset(12f, 21.5f)) }) {
            outline(c)
            face(c, sq, eyeDy = -0.3f * inhale, eyeCurve = 0.45f * inhale, mw = 2f - 0.6f * puff, open = 0.55f * puff)
        }
    }

    /**
     * B: nodding off. The head droops to one side as the eyes grow heavy and the mouth falls open and slides; a jolt
     * catches it (mouth snaps shut, eyes scrunch hard), a little past upright, then it settles.
     */
    private fun DrawScope.nod(c: Color, p: Float) {
        val droop = sm(seg(p, 0.08f, 0.55f))
        val back = sm(seg(p, 0.55f, 0.62f))          // the catch: quick
        val over = bump(p, 0.58f, 0.80f)              // a little past upright, then settling
        val heavy = droop * (1 - back)
        val rot = 8f * heavy - 2.5f * over
        val sq = 1.3f * squeeze(p, 0.555f)
        withTransform({ translate(0f, 0.7f * heavy); rotate(rot, Offset(12f, 21.5f)) }) {
            outline(c)
            face(c, sq, eyeDy = 0.25f * heavy, eyeCurve = 0.5f * heavy, mw = 2f - 0.5f * heavy - 0.5f * over, mouthDx = 0.5f * heavy, open = 0.5f * heavy)
        }
    }

    /**
     * C: dreaming. An ear twitch; the eyes flick side to side under the lids; a happy dream (smile, eyes lift into
     * "^ ^"); then a sleepy chewing mumble.
     */
    private fun DrawScope.dream(c: Color, p: Float) {
        val tw = bump(p, 0.04f, 0.11f) + 0.7f * bump(p, 0.12f, 0.19f)
        val rem = bump(p, 0.20f, 0.38f).let { kotlin.math.sqrt(it) } * sin(2 * PI.toFloat() * seg(p, 0.20f, 0.38f) * 4)
        val happy = bump(p, 0.40f, 0.64f).let { kotlin.math.sqrt(it) }
        val chewEnv = bump(p, 0.66f, 0.92f).let { kotlin.math.sqrt(it) }
        val chew = chewEnv * (0.5f + 0.5f * sin(2 * PI.toFloat() * seg(p, 0.66f, 0.92f) * 3 - PI.toFloat() / 2))
        val sq = squeeze(p, 0.385f)
        outline(c, head(-1.1f * tw, 1.5f * tw))
        face(c, sq, eyeDx = 0.55f * rem, eyeCurve = -0.4f * happy, mw = 2f + 0.4f * happy - 0.5f * chewEnv, open = 0.45f * chew, smile = 0.45f * happy)
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
