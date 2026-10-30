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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.vrca.ui.theme.GremlinColors as G
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lab mock (throwaway, for the user's pick): three eye animations for the thinking Boot Cat, beside the
 * current loop. The head and the running light are the app's; only the eyes differ. Each animation is one
 * 2.4 s beat (two laps of the light) that starts and ends on plain centred eyes, so any order can follow.
 * `show thinkmock`.
 */
object ThinkMocks {
    private const val HEAD = "M3.92,10.46 L4.3,3 L8.2,7.1 Q12,5.1 15.8,7.1 L19.9,2.6 L20.08,10.46 A8.6,8.6 0 1,1 3.92,10.46 Z"
    private const val Y0 = 13.6f
    private const val BEAT = 2400f

    private fun sm(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    /** Ease in-out with a little wind-up and a little overshoot (a glance that lands and settles). */
    private fun glance(x: Float): Float {
        val c = x.coerceIn(0f, 1f); val c2 = 1.2f * 1.525f
        return if (c < 0.5f) (2 * c) * (2 * c) * ((c2 + 1) * 2 * c - c2) / 2f
        else ((2 * c - 2) * (2 * c - 2) * ((c2 + 1) * (2 * c - 2) + c2) + 2) / 2f
    }
    /** 0..1 position within [a, b] of the beat. */
    private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)

    /**
     * One eye, a soft rounded shape centred at (x, y): [w] x [h] (2.6 x 2.6 = the round open eye), turned [rot] degrees.
     * Narrowing it keeps the round ends, so a squint or a closed eye never shows a flat cut.
     */
    private fun DrawScope.eye(c: Color, x: Float, y: Float, w: Float = 2.6f, h: Float = 2.6f, rot: Float = 0f) {
        withTransform({ rotate(rot, Offset(x, y)) }) {
            val r = min(w, h) / 2f
            drawRoundRect(c, Offset(x - w / 2f, y - h / 2f), Size(w, h), CornerRadius(r, r))
        }
    }

    /**
     * A blink inside [a]..[b] of the beat: how closed the eyes are (0 open .. 1 shut). Shuts quickly (the first 30%),
     * holds a moment, opens a little slower.
     */
    private fun blinkAt(p: Float, a: Float, b: Float): Float {
        if (p !in a..b) return 0f
        val k = seg(p, a, b)
        return when { k < 0.3f -> sm(k / 0.3f); k < 0.45f -> 1f; else -> 1f - sm((k - 0.45f) / 0.55f) }
    }
    /** An open eye [w] x [h] closing by [shut]: it flattens into a short soft line (lid comes down, so it sinks a little). */
    private fun DrawScope.blinkEye(c: Color, x: Float, y: Float, shut: Float, w: Float = 2.6f, h: Float = 2.6f, rot: Float = 0f) =
        eye(c, x, y + 0.35f * shut, w + (3.0f - w) * shut, h + (0.85f - h) * shut, rot * (1 - shut))

    /** A: look around. Glance left, settle, glance right, settle, back, blink. Eyes stretch the way they move. */
    private fun DrawScope.lookAround(c: Color, p: Float) {
        val dx: Float; val speed: Float
        when {
            p < 0.12f -> { dx = 0f; speed = 0f }
            p < 0.26f -> { val k = seg(p, 0.12f, 0.26f); dx = -1.5f * glance(k); speed = sin(PI.toFloat() * k) }
            p < 0.46f -> { dx = -1.5f; speed = 0f }
            p < 0.64f -> { val k = seg(p, 0.46f, 0.64f); dx = -1.5f + 3f * glance(k); speed = sin(PI.toFloat() * k) }
            p < 0.80f -> { dx = 1.5f; speed = 0f }
            p < 0.88f -> { val k = seg(p, 0.80f, 0.88f); dx = 1.5f * (1 - sm(k)); speed = 0.6f * sin(PI.toFloat() * k) }
            else -> { dx = 0f; speed = 0f }
        }
        val shut = blinkAt(p, 0.88f, 0.98f)
        for (x in floatArrayOf(9.3f, 14.7f)) blinkEye(c, x + dx, Y0, shut, 2.6f * (1f + 0.4f * speed), 2.6f * (1f - 0.2f * speed))
    }

    /** B: ponder. Eyes drift up to the right and narrow into soft tilted ovals, wiggle while they think, come back, blink. */
    private fun DrawScope.ponder(c: Color, p: Float) {
        val inK = sm(seg(p, 0.10f, 0.30f)); val outK = sm(seg(p, 0.74f, 0.88f))
        val k = inK * (1 - outK)
        val wig = if (p in 0.34f..0.70f) 0.35f * sin(2 * PI.toFloat() * seg(p, 0.34f, 0.70f) * 2) else 0f
        val dx = 1.0f * k + wig * k; val dy = -0.8f * k
        val shut = blinkAt(p, 0.88f, 0.98f)
        // Narrowed, the left eye a touch more and both tipped a little: "hmm" without a flat line.
        blinkEye(c, 9.3f + dx, Y0 + dy, shut, 2.6f + 0.3f * k, 2.6f - 1.0f * k, -10f * k)
        blinkEye(c, 14.7f + dx, Y0 + dy, shut, 2.6f + 0.2f * k, 2.6f - 0.7f * k, -6f * k)
    }

    /**
     * C: follow the light. For one lap the eyes circle the way the light goes, then back to centre and blink. They
     * turn at a steady speed matched to the light's average (following its exact spot stuttered on the ears, where
     * the outline is longer).
     */
    private fun DrawScope.followLight(c: Color, p: Float, phase: Float) {
        val env = sm(seg(p, 0.06f, 0.18f)) * (1 - sm(seg(p, 0.60f, 0.76f)))
        val ang = phase + 2 * PI.toFloat() * ((2 * p) % 1f)
        val dx = 1.3f * env * kotlin.math.cos(ang); val dy = 1.0f * env * sin(ang)
        val shut = blinkAt(p, 0.80f, 0.90f)
        for (x in floatArrayOf(9.3f, 14.7f)) blinkEye(c, x + dx, Y0 + dy, shut)
    }

    /** Where the light's tip points from the face centre at the start of its lap, fitted so a steady turn tracks it. */
    private fun lightPhase(m: PathMeasure): Float {
        val n = 96; var prev = 0f; var acc = 0f; var sum = 0f
        for (i in 0 until n) {
            val u = i / n.toFloat()
            val q = m.getPosition(((u + 0.28f) % 1f) * m.length)
            var a = kotlin.math.atan2(q.y - 13f, q.x - 12f)
            if (i > 0) { while (a - prev > PI) a -= 2 * PI.toFloat(); while (a - prev < -PI) a += 2 * PI.toFloat() }
            prev = a; acc = a
            sum += a - 2 * PI.toFloat() * u
        }
        return sum / n
    }

    private fun DrawScope.light(head: Path, m: PathMeasure, c: Color, at: Float, len: Float) {
        val total = m.length; val seg = Path()
        val a = at * total; val b = a + len * total
        m.getSegment(a, min(b, total), seg, true)
        if (b > total) m.getSegment(0f, b - total, seg, true)
        drawPath(seg, c, style = Stroke(2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    /**
     * Beat [n]'s animation in the shuffled order: each run of three plays A, B and C once in a random order, and a
     * run never starts with the one the last run ended on, so the same beat never plays twice in a row.
     */
    private val order = ArrayList<Char>()
    private val rnd = java.util.Random(7L)
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
        val c = MaterialTheme.colorScheme.primary
        val head = remember { PathParser().parsePathString(HEAD).toPath() }
        val measure = remember { PathMeasure().apply { setPath(head, false) } }
        val phase = remember { lightPhase(measure) }
        Canvas(modifier) {
            val t = ms().toFloat()
            val p = (t % BEAT) / BEAT
            val s = size.minDimension / 24f
            withTransform({ scale(s, s, Offset.Zero) }) {
                drawPath(head, c.copy(alpha = 0.25f), style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                light(head, measure, c, (t / 1200f) % 1f, 0.28f)
                when (if (kind == 'S') shuffled((t / BEAT).toInt()) else kind) {
                    'A' -> lookAround(c, p)
                    'B' -> ponder(c, p)
                    else -> followLight(c, p, phase)
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
                listOf("Now" to ' ', "A  Look around" to 'A', "B  Ponder" to 'B', "C  Follow the light" to 'C', "Shuffled" to 'S').forEach { (label, k) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, color = G.Text, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(10.dp))
                        if (k == ' ') com.vrca.app.BootCat(com.vrca.app.Mark.Working, 0, Modifier.size(170.dp))
                        else Cat(k, { ms.longValue }, Modifier.size(170.dp))
                        Spacer(Modifier.height(18.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (k == ' ') com.vrca.app.BootCat(com.vrca.app.Mark.Working, 0, Modifier.size(36.dp))
                            else Cat(k, { ms.longValue }, Modifier.size(36.dp))
                            Spacer(Modifier.width(16.dp))
                            if (k == ' ') com.vrca.app.BootCat(com.vrca.app.Mark.Working, 0, Modifier.size(24.dp))
                            else Cat(k, { ms.longValue }, Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}
