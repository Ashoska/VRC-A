package com.vrca.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The downloading Boot Cat's eyes (user picks, 2026-10-30): small v's, playing three 2.4 s beats in a shuffled order,
 * each starting and ending on the plain v's. They run on their own clock, never tied to the bar's speed (a download can
 * fill in 2 s or 2 min; user):
 *  - A, download nod: the v's glide down and fade (the data going in), drop back in from above, a small nod as they
 *    land (the whole cat, bar included); a blink.
 *  - B, watching the bar: the eyes turn to the bar's growing tip (or the running light's, size unknown) and follow
 *    it, then come back; a blink mid-watch.
 *  - C, excited: the v's flip into happy ^ ^ with a tiny bounce, hold, flip back. No blink.
 * Drawn in Compose by [BootCatDownload], the clock read only while drawing. Lab mock: `show downloadmock`.
 */
internal object BootCatDl {
    const val BEAT_MS = 2400f
    private const val ORDER = "ABCBAC"
    private const val Y = 13.6f
    private val PIVOT = Offset(12f, 21.5f)

    private fun sm(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
    private fun bump(p: Float, a: Float, b: Float) = if (p <= a || p >= b) 0f else sin(PI.toFloat() * seg(p, a, b))
    private fun blink(p: Float, a: Float, len: Float = 0.12f): Float {
        if (p < a || p > a + len) return 0f
        val k = seg(p, a, a + len)
        return when { k < 0.3f -> sm(k / 0.3f); k < 0.45f -> 1f; else -> 1f - sm((k - 0.45f) / 0.55f) }
    }

    private val eyePath = Path()

    /**
     * Draws the cat at [ms]: [bar] draws the outline (and its fill or light) inside the cat's nod/bounce transform,
     * then the eyes. [tipAt] is where on the outline (0..1 along [m]) the eyes watch in beat B: the bar's growing end.
     */
    fun DrawScope.drawDownloadCat(c: Color, ms: Float, m: PathMeasure, tipAt: Float, bar: DrawScope.() -> Unit) {
        val p = (ms % BEAT_MS) / BEAT_MS
        var tx = 0f; var ty = 0f; var rot = 0f; var sc = 1f
        var open = 1f; var dx = 0f; var dy = 0f; var a = 1f
        when (ORDER[((ms / BEAT_MS).toInt()) % ORDER.length]) {
            'A' -> {
                when {
                    p < 0.12f -> {}
                    p < 0.38f -> { val k = sm(seg(p, 0.12f, 0.38f)); dy = 1.6f * k; a = 1f - k }
                    p < 0.58f -> { val k = sm(seg(p, 0.38f, 0.58f)); dy = -1.6f * (1f - k); a = k }
                }
                val nod = bump(p, 0.52f, 0.70f)
                ty = 1.0f * nod; rot = 3f * nod; open = 1f - blink(p, 0.80f)
            }
            'B' -> {
                val k = sm(seg(p, 0.08f, 0.24f)) * (1 - sm(seg(p, 0.72f, 0.88f)))
                val pos = m.getPosition(tipAt * m.length)
                val vx = pos.x - 12f; val vy = pos.y - 12.5f; val l = hypot(vx, vy).coerceAtLeast(0.01f)
                dx = 1.5f * k * vx / l; dy = 1.1f * k * vy / l; open = 1f - blink(p, 0.46f)
            }
            else -> {
                val f = sm(seg(p, 0.14f, 0.26f)) * (1 - sm(seg(p, 0.62f, 0.74f)))
                val hop = bump(p, 0.18f, 0.40f)
                ty = -0.7f * hop; sc = 1f + 0.03f * hop; open = 1f - 2f * f
            }
        }
        withTransform({ translate(tx, ty); rotate(rot, PIVOT); scale(sc, sc, PIVOT) }) {
            bar()
            val st = Stroke(1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            for (x0 in floatArrayOf(9.3f, 14.7f)) {
                val x = x0 + dx; val y = Y - 0.1f + dy
                eyePath.rewind(); eyePath.moveTo(x - 1.25f, y - 0.6f * open); eyePath.lineTo(x, y + 0.65f * open); eyePath.lineTo(x + 1.25f, y - 0.6f * open)
                drawPath(eyePath, c.copy(alpha = c.alpha * a), style = st)
            }
        }
    }
}
