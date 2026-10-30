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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.vrca.ui.theme.GremlinColors as G
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Lab mock (throwaway, for the user's pick): the SKIPPED Boot Cat made awake-but-unbothered (so it no longer reads as
 * the sleeping Waiting cat): a new resting face (relaxed half-open eyes, a small content smile) and three 3 s idle
 * beats that start and end on it. Beside the new Waiting cat and the current Skipped one for comparison.
 * `show skipmock`.
 */
object SkipMocks {
    private const val Y = 13.6f
    private const val BEAT = 3000f

    private fun sm(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
    private fun bump(p: Float, a: Float, b: Float) = if (p <= a || p >= b) 0f else sin(PI.toFloat() * seg(p, a, b)).let { it * it }
    /** A blink over [a]..[a]+[len]: shuts fast (30%), holds, opens slower. 0 open .. 1 shut. */
    private fun blink(p: Float, a: Float, len: Float): Float {
        if (p < a || p > a + len) return 0f
        val k = seg(p, a, a + len)
        return when { k < 0.3f -> sm(k / 0.3f); k < 0.45f -> 1f; else -> 1f - sm((k - 0.45f) / 0.55f) }
    }

    private val head = Path()
    private val mouth = Path()

    /** The head with each ear tip moved outward by [ears] (0 = the app's HEAD path; the yawn lays them back). */
    private fun headPath(ears: Float): Path = head.apply {
        rewind()
        moveTo(3.92f, 10.46f); lineTo(4.3f - 0.9f * ears, 3f + 1.0f * ears); lineTo(8.2f, 7.1f)
        quadraticTo(12f, 5.1f, 15.8f, 7.1f); lineTo(19.9f + 0.9f * ears, 2.6f + 1.0f * ears); lineTo(20.08f, 10.46f)
        arcTo(Rect(Offset(12f, 13.405f), 8.6f), -20.03f, 220.06f, false)
        close()
    }

    /**
     * The awake, unbothered face. Eyes: soft capsules [eh] tall (2.05 = relaxed, just a little narrowed from the 2.6 round eye; 0.85 shut), moved
     * by ([dx], [dy]). Mouth: [mw] wide, curved by [smile], opened by [open] into an oval.
     */
    private fun DrawScope.face(c: Color, eh: Float = 2.05f, dx: Float = 0f, dy: Float = 0f, eyeW: Float = 2.6f, mw: Float = 2.2f, smile: Float = 0.45f, open: Float = 0f) {
        for (x in floatArrayOf(9.3f, 14.7f)) {
            val w = eyeW
            val r = min(w, eh) / 2f
            drawRoundRect(c, Offset(x + dx - w / 2f, Y + 0.3f + dy - eh / 2f), Size(w, eh), CornerRadius(r, r))
        }
        val my = Y + 3.5f; val h = 1.6f * open
        mouth.rewind()
        mouth.moveTo(12f - mw / 2f, my)
        mouth.quadraticTo(12f, my - h + 2f * smile, 12f + mw / 2f, my)
        mouth.quadraticTo(12f, my + h + 2f * smile, 12f - mw / 2f, my)
        drawPath(mouth, c, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    private fun DrawScope.cat(c: Color, ears: Float = 0f, face: DrawScope.() -> Unit) {
        val p = headPath(ears)
        drawPath(p, c.copy(alpha = 0.16f))
        drawPath(p, c, style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        face()
    }

    /** A: yawn and stretch. Stretches tall, ears lay back, eyes squeeze shut, mouth opens wide; settles pleased. */
    private fun DrawScope.yawn(c: Color, p: Float) {
        val stretch = bump(p, 0.08f, 0.62f)
        val shut = sm(seg(p, 0.14f, 0.24f)) * (1 - sm(seg(p, 0.52f, 0.62f)))
        val wide = bump(p, 0.16f, 0.56f)
        val pleased = bump(p, 0.60f, 0.92f)
        withTransform({ scale(1f - 0.03f * stretch, 1f + 0.09f * stretch, Offset(12f, 21.5f)) }) {
            cat(c, ears = stretch) {
                face(c, eh = 2.05f - 1.2f * shut - 0.5f * pleased, eyeW = 2.6f + 0.4f * shut, mw = 2.2f + 0.5f * wide, smile = 0.45f * (1 - wide) + 0.2f * pleased, open = 1.1f * wide)
            }
        }
    }

    /** B: lazy look. The eyes slide slowly to one side, a slow lazy blink there, then back. */
    private fun DrawScope.lazyLook(c: Color, p: Float) {
        val look = sm(seg(p, 0.08f, 0.30f)) * (1 - sm(seg(p, 0.66f, 0.86f)))
        val shut = blink(p, 0.40f, 0.22f)
        cat(c) { face(c, eh = 2.05f - 1.2f * shut - 0.3f * look, dx = 1.3f * look, dy = -0.15f * look, smile = 0.45f - 0.15f * look) }
    }

    /** C: humming. Sways side to side in rhythm, eyes narrowed happily, mouth a little whistling "o"; a quick blink. */
    private fun DrawScope.hum(c: Color, p: Float) {
        val env = sm(seg(p, 0.06f, 0.18f)) * (1 - sm(seg(p, 0.70f, 0.86f)))
        val sway = env * sin(2 * PI.toFloat() * seg(p, 0.06f, 0.86f) * 2)
        val shut = blink(p, 0.88f, 0.09f)
        rotate(5f * sway, Offset(12f, 21.5f)) {
            cat(c) { face(c, eh = 2.05f - 0.6f * env - 0.6f * shut, dx = 0.4f * sway, mw = 2.2f - 1.0f * env, smile = 0.45f * (1 - env), open = 0.45f * env) }
        }
    }

    private val order = ArrayList<Char>()
    private val rnd = java.util.Random(5L)
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
        val c = lerp(G.Edge, G.TextMuted, 0.45f) // markColor(Skipped)
        Canvas(modifier) {
            val t = ms().toFloat()
            val p = (t % BEAT) / BEAT
            val s = size.minDimension / 24f
            withTransform({ scale(s, s, Offset.Zero) }) {
                when (if (kind == 'S') shuffled((t / BEAT).toInt()) else kind) {
                    'A' -> yawn(c, p)
                    'B' -> lazyLook(c, p)
                    'C' -> hum(c, p)
                    else -> cat(c) { face(c) }
                }
            }
        }
    }

    @Composable
    private fun Cell(kind: Char, ms: () -> Long, size: Int) = when (kind) {
        'W' -> com.vrca.app.SleepLoop(0, { lerp(G.Edge, G.TextMuted, 0.08f) }, Modifier.size(size.dp))
        'N' -> com.vrca.app.BootCat(com.vrca.app.Mark.Skipped, 0, Modifier.size(size.dp))
        else -> Cat(kind, ms, Modifier.size(size.dp))
    }

    @Composable
    fun Sheet() {
        val ms = remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) { val t0 = withFrameMillis { it }; while (true) withFrameMillis { ms.longValue = it - t0 } }
        Box(Modifier.fillMaxSize().background(G.Backdrop), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                listOf("Waiting (new)" to 'W', "Skipped now" to 'N', "Resting face" to ' ', "A  Yawn" to 'A', "B  Lazy look" to 'B', "C  Humming" to 'C', "Shuffled" to 'S').forEach { (label, k) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, color = G.Text, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(10.dp))
                        Cell(k, { ms.longValue }, 126)
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
