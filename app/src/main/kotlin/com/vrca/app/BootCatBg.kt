package com.vrca.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The background Boot Cat (a check still running after the boot moved on; user picks, 2026-10-30): faint outline, a
 * light circling at half speed (one lap per 2.4 s, unbroken across beats), half-lidded relaxed eyes. Three 4.8 s beats
 * (two light laps each) in a shuffled order per variant, each starting and ending on that face:
 *  - A, lazy watch: the sleepy eyes slowly follow the light round the head, drift back; a soft blink.
 *  - B, almost dozing: the lids sink until nearly shut and the head droops; it catches itself (eyes pop wide, a bob).
 *  - C, slow cat blink: a long content blink; the eyes close into soft happy curves, a small smile shows, a slight tilt.
 *
 * Drawn in Compose (`BgLoop` in BootCat), the clock read only while drawing; the same code runs in the app and the UI Lab.
 */
internal object BootCatBg {
    const val BEAT_MS = 4800f
    const val LAP_MS = 2400f
    private val ORDERS = arrayOf("ABCBAC", "BCACBA", "CABACB", "ACBCAB")
    const val Y = 13.6f
    val X = floatArrayOf(9.3f, 14.7f)
    val PIVOT = Offset(12f, 21.5f)

    // pose() slots.
    const val TX = 0; const val TY = 1; const val ROT = 2; const val DX = 3; const val DY = 4
    const val LOWER = 5; const val CURVE = 6; const val SMILE = 7; const val SIZE = 8

    private fun sm(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
    private fun bump(p: Float, a: Float, b: Float) = if (p <= a || p >= b) 0f else sin(PI.toFloat() * seg(p, a, b))
    private fun blink(p: Float, a: Float, len: Float = 0.07f): Float {
        if (p < a || p > a + len) return 0f
        val k = seg(p, a, a + len)
        return when { k < 0.3f -> sm(k / 0.3f); k < 0.45f -> 1f; else -> 1f - sm((k - 0.45f) / 0.55f) }
    }

    fun headPath() = Path().apply {
        moveTo(3.92f, 10.46f); lineTo(4.3f, 3f); lineTo(8.2f, 7.1f)
        quadraticTo(12f, 5.1f, 15.8f, 7.1f); lineTo(19.9f, 2.6f); lineTo(20.08f, 10.46f)
        arcTo(Rect(Offset(12f, 13.405f), 8.6f), -20.03f, 220.06f, false)
        close()
    }

    /** Direction (unit vector from the face centre) of the light's leading tip, per 1/256 of a lap. */
    private val TIP: Array<Offset> by lazy {
        val m = PathMeasure().apply { setPath(headPath(), false) }
        Array(256) { i ->
            val pos = m.getPosition(((i / 256f + 0.28f) % 1f) * m.length)
            val dx = pos.x - 12f; val dy = pos.y - 12.5f; val l = hypot(dx, dy).coerceAtLeast(0.01f)
            Offset(dx / l, dy / l)
        }
    }

    /** Writes the pose of loop [variant] at [ms] since it started into [out] (see the slot constants). */
    fun pose(variant: Int, ms: Float, out: FloatArray) {
        out.fill(0f)
        val n = ((ms / BEAT_MS).toInt()) % 6
        val p = (ms % BEAT_MS) / BEAT_MS
        when (ORDERS[Math.floorMod(variant, ORDERS.size)][n]) {
            'A' -> {
                val k = sm(seg(p, 0.06f, 0.20f)) * (1 - sm(seg(p, 0.70f, 0.84f)))
                val d = TIP[((ms / LAP_MS % 1f) * 256).toInt().coerceIn(0, 255)]
                out[DX] = 1.2f * k * d.x; out[DY] = 0.8f * k * d.y; out[LOWER] = blink(p, 0.90f)
            }
            'B' -> {
                val sink = sm(seg(p, 0.06f, 0.56f)) * (1 - sm(seg(p, 0.58f, 0.62f)))
                val wide = sm(seg(p, 0.58f, 0.62f)) * (1 - sm(seg(p, 0.66f, 0.84f)))
                val bob = bump(p, 0.58f, 0.70f)
                out[TY] = 0.5f * sink - 0.5f * bob; out[ROT] = 4f * sink; out[LOWER] = 0.85f * sink - 0.45f * wide
            }
            else -> {
                val k = sm(seg(p, 0.14f, 0.36f)) * (1 - sm(seg(p, 0.58f, 0.80f)))
                val happy = sm(seg(k, 0.6f, 1f))
                out[ROT] = -4f * k; out[LOWER] = k; out[CURVE] = happy; out[SMILE] = happy
            }
        }
    }

    /** Lid line y for [lower] (0 = the resting half lid, 1 = shut, negative = wider). */
    fun lidY(lower: Float) = Y - 0.3f + 1.4f * lower
    /** The visible eye is the dot below the lid: a circle of this radius centred here. */
    const val DOT_R = 1.05f
    val DOT_Y = Y + 0.35f
    /** Bottom of the eye clip (below the dot). */
    val CLIP_BOTTOM = DOT_Y + 1.2f
    /**
     * The happy closed lid, drawn at lid offset 0 (shift it by [lidY] - lidY(0)): a quad from x-1.5 to x+1.5, ends
     * 0.3 up, middle 1.3 down. It fades in over the flat lid by [CURVE].
     */
    fun curvedLid(x: Float) = floatArrayOf(x - 1.5f, lidY(0f) - 0.3f, x, lidY(0f) + 1.3f, x + 1.5f, lidY(0f) - 0.3f)
    /** The small smile under the eyes (fades in by [SMILE]). */
    val SMILE_Q = floatArrayOf(10.9f, Y + 3.5f, 12f, Y + 4.6f, 13.1f, Y + 3.5f)

    private val pose = FloatArray(SIZE)
    private val tmp = Path()

    private fun DrawScope.quad(q: FloatArray, dy: Float, c: Color, w: Float) {
        tmp.rewind(); tmp.moveTo(q[0], q[1] + dy); tmp.quadraticTo(q[2], q[3] + dy, q[4], q[5] + dy)
        drawPath(tmp, c, style = Stroke(w, cap = StrokeCap.Round))
    }

    /** Draws loop [variant] at [ms] (24-unit grid). [light] draws the running light. */
    fun DrawScope.drawBg(head: Path, light: DrawScope.(Float) -> Unit, c: Color, variant: Int, ms: Float) {
        pose(variant, ms, pose)
        val eye = c.copy(alpha = c.alpha * 0.7f)
        withTransform({ translate(pose[TX], pose[TY]); rotate(pose[ROT], PIVOT) }) {
            drawPath(head, c.copy(alpha = c.alpha * 0.18f), style = Stroke(1.9f, cap = StrokeCap.Round))
            light((ms / LAP_MS) % 1f)
            val dx = pose[DX]; val dy = pose[DY]; val lid = lidY(pose[LOWER]); val shift = lid - lidY(0f); val cv = pose[CURVE]
            for (x0 in X) {
                val x = x0 + dx
                // The dot starts below the lid line's bottom edge: the two see-through layers never overlap (a
                // brighter second shade showed where they did).
                clipRect(x - 2f, lid + 0.75f + dy, x + 2f, CLIP_BOTTOM + dy) { drawCircle(eye, DOT_R, Offset(x, DOT_Y + dy)) }
                if (cv < 1f) drawLine(eye.copy(alpha = eye.alpha * (1f - cv)), Offset(x - 1.5f, lid + dy), Offset(x + 1.5f, lid + dy), 1.5f, StrokeCap.Round)
                if (cv > 0f) quad(curvedLid(x), shift + dy, eye.copy(alpha = eye.alpha * cv), 1.5f)
            }
            if (pose[SMILE] > 0f) quad(SMILE_Q, 0f, eye.copy(alpha = eye.alpha * pose[SMILE]), 1.4f)
        }
    }
}
