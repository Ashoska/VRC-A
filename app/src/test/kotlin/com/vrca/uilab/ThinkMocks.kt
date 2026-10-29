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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
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

    /** One eye: a dot at (x, y), stretched [sx] x [sy], its lower part covered up to [lid] (0 open .. 1 shut from below). */
    private fun DrawScope.eye(c: Color, x: Float, y: Float, sx: Float = 1f, sy: Float = 1f, lowerLid: Float = 0f) {
        val r = 1.3f
        withTransform({ scale(sx, sy, Offset(x, y)) }) {
            if (lowerLid <= 0.001f) drawCircle(c, r, Offset(x, y))
            else clipRect(x - 2 * r, y - 2 * r, x + 2 * r, y + r - 2 * r * lowerLid) { drawCircle(c, r, Offset(x, y)) }
        }
    }

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
        val blink = if (p in 0.88f..0.97f) { val k = seg(p, 0.88f, 0.97f); 1f - 0.9f * sin(PI.toFloat() * k) } else 1f
        val sx = 1f + 0.45f * speed; val sy = (1f - 0.22f * speed) * blink
        for (x in floatArrayOf(9.3f, 14.7f)) eye(c, x + dx, Y0, sx, sy)
    }

    /** B: ponder. Eyes drift up to the right and squint from below into half-moons, wiggle while they think, come back. */
    private fun DrawScope.ponder(c: Color, p: Float) {
        val inK = sm(seg(p, 0.10f, 0.30f)); val outK = sm(seg(p, 0.74f, 0.90f))
        val k = inK * (1 - outK)
        val wig = if (p in 0.34f..0.70f) 0.35f * sin(2 * PI.toFloat() * seg(p, 0.34f, 0.70f) * 2) else 0f
        val dx = 1.0f * k + wig * k; val dy = -0.9f * k
        val blink = if (p in 0.90f..0.98f) 1f - 0.85f * sin(PI.toFloat() * seg(p, 0.90f, 0.98f)) else 1f
        // The left eye squints a touch more: a "hmm" without a flat line.
        eye(c, 9.3f + dx, Y0 + dy, sy = blink, lowerLid = 0.42f * k)
        eye(c, 14.7f + dx, Y0 + dy, sy = blink, lowerLid = 0.3f * k)
    }

    /** C: follow the light. For one lap the eyes look toward wherever the light is, then back to centre and blink. */
    private fun DrawScope.followLight(c: Color, p: Float, head: Path, measure: PathMeasure) {
        val env = sm(seg(p, 0.06f, 0.16f)) * (1 - sm(seg(p, 0.62f, 0.74f)))
        val at = (2 * p) % 1f // the light's own position along the head (two laps per beat)
        val tip = measure.getPosition(((at + 0.28f) % 1f) * measure.length)
        val vx = tip.x - 12f; val vy = tip.y - 13f
        val len = sqrt(vx * vx + vy * vy).coerceAtLeast(0.01f)
        val dx = 1.3f * env * vx / len; val dy = 1.0f * env * vy / len
        val blink = if (p in 0.80f..0.88f) 1f - 0.9f * sin(PI.toFloat() * seg(p, 0.80f, 0.88f)) else 1f
        for (x in floatArrayOf(9.3f, 14.7f)) eye(c, x + dx, Y0 + dy, sy = blink)
    }

    private fun DrawScope.light(head: Path, m: PathMeasure, c: Color, at: Float, len: Float) {
        val total = m.length; val seg = Path()
        val a = at * total; val b = a + len * total
        m.getSegment(a, min(b, total), seg, true)
        if (b > total) m.getSegment(0f, b - total, seg, true)
        drawPath(seg, c, style = Stroke(2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    @Composable
    private fun Cat(kind: Char, ms: () -> Long, modifier: Modifier) {
        val c = MaterialTheme.colorScheme.primary
        val head = remember { PathParser().parsePathString(HEAD).toPath() }
        val measure = remember { PathMeasure().apply { setPath(head, false) } }
        Canvas(modifier) {
            val t = ms().toFloat()
            val p = (t % BEAT) / BEAT
            val s = size.minDimension / 24f
            withTransform({ scale(s, s, Offset.Zero) }) {
                drawPath(head, c.copy(alpha = 0.25f), style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                light(head, measure, c, (t / 1200f) % 1f, 0.28f)
                when (kind) {
                    'A' -> lookAround(c, p)
                    'B' -> ponder(c, p)
                    else -> followLight(c, p, head, measure)
                }
            }
        }
    }

    @Composable
    fun Sheet() {
        val ms = remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) { val t0 = withFrameMillis { it }; while (true) withFrameMillis { ms.longValue = it - t0 } }
        Box(Modifier.fillMaxSize().background(G.Backdrop), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.Top) {
                listOf("Now" to ' ', "A  Look around" to 'A', "B  Ponder" to 'B', "C  Follow the light" to 'C').forEach { (label, k) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, color = G.Text, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(10.dp))
                        if (k == ' ') com.vrca.app.BootCat(com.vrca.app.Mark.Working, 0, Modifier.size(190.dp))
                        else Cat(k, { ms.longValue }, Modifier.size(190.dp))
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
