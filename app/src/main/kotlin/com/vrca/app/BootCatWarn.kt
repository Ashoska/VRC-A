package com.vrca.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
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
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The warn Boot Cat's idle (user picks, 2026-10-29): on its worried face (round open eyes, a small frown, amber with
 * the soft fill), three 3 s beats in a shuffled order, each starting and ending on that face:
 *  - A, nervous glance: quick darts to one side, then the other, back, a blink.
 *  - B, fidget: a few small quick jitters of the head, the eyes and frown wobbling with them, a blink.
 *  - C, uneasy sigh: the head sinks a little as the eyes droop and the frown deepens; it straightens up and blinks.
 * The soft blink (shuts fast, holds, opens slower; the eye flattens into a short line) sits at a different point per
 * beat. Drawn in Compose, the clock read only while drawing; the same code runs in the app and the UI Lab.
 */
internal object BootCatWarn {
    const val BEAT_MS = 3000f
    private val ORDERS = arrayOf("ABCBAC", "BCACBA", "CABACB", "ACBCAB")
    private const val Y = 13.6f

    private fun sm(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
    private fun bump(p: Float, a: Float, b: Float) = if (p <= a || p >= b) 0f else sin(PI.toFloat() * seg(p, a, b)).let { it * it }
    private fun blink(p: Float, a: Float, len: Float = 0.10f): Float {
        if (p < a || p > a + len) return 0f
        val k = seg(p, a, a + len)
        return when { k < 0.3f -> sm(k / 0.3f); k < 0.45f -> 1f; else -> 1f - sm((k - 0.45f) / 0.55f) }
    }

    /** Reused paths, so drawing a frame allocates nothing. */
    class Paths { val head = Path(); val mouth = Path() }

    private fun Path.head() {
        rewind()
        moveTo(3.92f, 10.46f); lineTo(4.3f, 3f); lineTo(8.2f, 7.1f)
        quadraticTo(12f, 5.1f, 15.8f, 7.1f); lineTo(19.9f, 2.6f); lineTo(20.08f, 10.46f)
        arcTo(Rect(Offset(12f, 13.405f), 8.6f), -20.03f, 220.06f, false)
        close()
    }

    /**
     * The worried face (the app's Face.Worry at rest). Eyes: round dots moved by ([dx], [dy]), [droop] lowers their
     * tops, [shut] the blink. Mouth: the frown, deepened by [deep] and bobbed by [wob].
     */
    private fun DrawScope.face(ps: Paths, c: Color, dx: Float = 0f, dy: Float = 0f, droop: Float = 0f, shut: Float = 0f, deep: Float = 0f, wob: Float = 0f) {
        val h0 = 2.6f - 0.9f * droop
        val w = 2.6f + 0.4f * shut; val h = h0 + (0.85f - h0) * shut
        val y = Y + dy + 0.45f * droop + 0.35f * shut
        val r = min(w, h) / 2f
        for (x in floatArrayOf(9.3f, 14.7f)) drawRoundRect(c, Offset(x + dx - w / 2f, y - h / 2f), Size(w, h), CornerRadius(r, r))
        val ends = Y + 4.2f + 0.35f * deep; val mid = Y + 2.9f - 0.7f * deep + wob
        ps.mouth.rewind(); ps.mouth.moveTo(10.2f, ends); ps.mouth.quadraticTo(12f, mid, 13.8f, ends)
        drawPath(ps.mouth, c, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    private inline fun DrawScope.cat(ps: Paths, c: Color, f: DrawScope.() -> Unit) {
        ps.head.head()
        drawPath(ps.head, c.copy(alpha = c.alpha * 0.16f))
        drawPath(ps.head, c, style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        f()
    }

    /** Draws loop [variant] at [ms] since it started (24-unit grid). */
    fun DrawScope.drawWarn(ps: Paths, c: Color, variant: Int, ms: Float) {
        val n = ((ms / BEAT_MS).toInt()) % 6
        val p = (ms % BEAT_MS) / BEAT_MS
        when (ORDERS[Math.floorMod(variant, ORDERS.size)][n]) {
            'A' -> {
                val dx = when {
                    p < 0.12f -> 0f
                    p < 0.17f -> -1.4f * sm(seg(p, 0.12f, 0.17f))
                    p < 0.34f -> -1.4f
                    p < 0.40f -> -1.4f + 2.8f * sm(seg(p, 0.34f, 0.40f))
                    p < 0.54f -> 1.4f
                    p < 0.62f -> 1.4f * (1 - sm(seg(p, 0.54f, 0.62f)))
                    else -> 0f
                }
                cat(ps, c) { face(ps, c, dx = dx, shut = blink(p, 0.68f)) }
            }
            'B' -> {
                val env = sqrt(bump(p, 0.12f, 0.58f))
                val j = env * sin(2 * PI.toFloat() * seg(p, 0.12f, 0.58f) * 6)
                rotate(3f * j, Offset(12f, 21.5f)) { cat(ps, c) { face(ps, c, dx = 0.3f * j, wob = 0.35f * j, shut = blink(p, 0.74f)) } }
            }
            else -> {
                val k = sm(seg(p, 0.10f, 0.40f)) * (1 - sm(seg(p, 0.56f, 0.76f)))
                withTransform({ translate(0f, 0.6f * k); scale(1f + 0.02f * k, 1f - 0.04f * k, Offset(12f, 21.5f)) }) {
                    cat(ps, c) { face(ps, c, droop = k, deep = k, shut = blink(p, 0.82f)) }
                }
            }
        }
    }
}

/** The warn cat idling, looping from when it appears; [color] is read while drawing. */
@Composable
internal fun WarnLoop(variant: Int, color: () -> Color, modifier: Modifier) {
    val ps = remember { BootCatWarn.Paths() }
    val ms = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { val t0 = withFrameMillis { it }; while (true) withFrameMillis { ms.longValue = it - t0 } }
    Canvas(modifier) {
        val s = size.minDimension / 24f
        withTransform({ scale(s, s, Offset.Zero) }) { with(BootCatWarn) { drawWarn(ps, color(), variant, ms.longValue.toFloat()) } }
    }
}
