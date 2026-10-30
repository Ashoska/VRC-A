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
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The maintenance Boot Cat's idle (user picks, 2026-10-30): on its fixing face (left eye a squint line, right eye an
 * open dot, flat mouth, amber with the soft fill), three 3 s beats in a shuffled order, each starting and ending on
 * that face. The eyes do the work (a first try that barely changed them read as three of the same):
 *  - A, hammering: three steady taps; on each the head dips and both eyes squeeze into tilted slits; a blink.
 *  - B, inspecting: leans in peering with a big open eye that scans, then swaps (the squint opens wide, the open eye
 *    squints) and leans the other way; back, a blink.
 *  - C, puzzled then aha: both eyes open and look up to one side, mouth slid over; "aha": eyes pop, mouth a small "o";
 *    it settles. No blink.
 * Each eye morphs between the squint line and the open dot (the rest pose matches the landing's Face.Fix exactly).
 * Drawn in Compose, the clock read only while drawing; the same code runs in the app and the UI Lab.
 */
internal object BootCatMaint {
    const val BEAT_MS = 3000f
    private val ORDERS = arrayOf("ABCBAC", "BCACBA", "CABACB", "ACBCAB")
    private const val Y = 13.6f
    private val PIVOT = Offset(12f, 21.5f)

    private fun sm(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
    private fun blink(p: Float, a: Float, len: Float = 0.10f): Float {
        if (p < a || p > a + len) return 0f
        val k = seg(p, a, a + len)
        return when { k < 0.3f -> sm(k / 0.3f); k < 0.45f -> 1f; else -> 1f - sm((k - 0.45f) / 0.55f) }
    }

    /** Reused path, so drawing a frame allocates nothing. */
    class Paths { val head = Path() }

    private fun Path.head() {
        rewind()
        moveTo(3.92f, 10.46f); lineTo(4.3f, 3f); lineTo(8.2f, 7.1f)
        quadraticTo(12f, 5.1f, 15.8f, 7.1f); lineTo(19.9f, 2.6f); lineTo(20.08f, 10.46f)
        arcTo(Rect(Offset(12f, 13.405f), 8.6f), -20.03f, 220.06f, false)
        close()
    }

    /** One eye at ([x], [y]): [open] 0 = squint line, 1 = round open eye; [big] grows it, [ang] tilts it, [shut] the blink. */
    private fun DrawScope.eye(c: Color, x: Float, y: Float, open: Float, big: Float, ang: Float, shut: Float) {
        val d = 2.6f * (1f + big)
        var w = 4.3f + (d - 4.3f) * open; var h = 1.9f + (d - 1.9f) * open
        w += (3.0f - w) * shut; h += (0.85f - h) * shut
        val r = min(w, h) / 2f
        withTransform({ rotate(ang, Offset(x, y)) }) { drawRoundRect(c, Offset(x - w / 2f, y - h / 2f), Size(w, h), CornerRadius(r, r)) }
    }

    /**
     * The fixing face. [lo]/[ro] how open each eye is, [lb]/[rb] how big, [la]/[ra] tilt, [ex]/[ey] move both eyes,
     * [shut] blinks the right eye. Mouth: [mx] slides it, [mo] turns it into a small "o", [mw] shortens it.
     */
    private fun DrawScope.face(c: Color, lo: Float = 0f, ro: Float = 1f, lb: Float = 0f, rb: Float = 0f, la: Float = 0f, ra: Float = 0f,
                               ex: Float = 0f, ey: Float = 0f, shut: Float = 0f, mx: Float = 0f, mo: Float = 0f, mw: Float = 0f) {
        eye(c, 9.3f + ex, Y + ey, lo, lb, la, 0f)
        eye(c, 14.7f + ex, Y + ey, ro, rb, ra, shut)
        val my = Y + 3.8f
        val w0 = 5.2f - 1.2f * mw
        val w = w0 + (2.3f - w0) * mo; val h = 1.8f + 0.5f * mo
        drawRoundRect(c, Offset(12f + mx - w / 2f, my - h / 2f), Size(w, h), CornerRadius(h / 2f, h / 2f))
    }

    private inline fun DrawScope.cat(ps: Paths, c: Color, f: DrawScope.() -> Unit) {
        ps.head.head()
        drawPath(ps.head, c.copy(alpha = c.alpha * 0.16f))
        drawPath(ps.head, c, style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        f()
    }

    /** Draws loop [variant] at [ms] since it started (24-unit grid). */
    fun DrawScope.drawMaint(ps: Paths, c: Color, variant: Int, ms: Float) {
        val n = ((ms / BEAT_MS).toInt()) % 6
        val p = (ms % BEAT_MS) / BEAT_MS
        when (ORDERS[Math.floorMod(variant, ORDERS.size)][n]) {
            'A' -> {
                var k = 0f
                for (i in 0 until 3) {
                    val a = 0.10f + i * 0.18f
                    if (p in a..(a + 0.16f)) { val q = seg(p, a, a + 0.16f); k = if (q < 0.2f) sm(q / 0.2f) else 1f - sm((q - 0.2f) / 0.8f) }
                }
                withTransform({ translate(0f, 0.9f * k); scale(1f + 0.05f * k, 1f - 0.08f * k, PIVOT) }) {
                    cat(ps, c) { face(c, ro = 1f - k, la = 22f * k, ra = -22f * k, mw = 0.6f * k, shut = blink(p, 0.76f)) }
                }
            }
            'B' -> {
                val k = sm(seg(p, 0.08f, 0.22f)) * (1 - sm(seg(p, 0.70f, 0.82f)))
                val sw = sm(seg(p, 0.42f, 0.52f)) * (1 - sm(seg(p, 0.70f, 0.82f)))
                val scan = sin(2 * PI.toFloat() * seg(p, 0.22f, 0.40f)) * k * (1 - sw)
                withTransform({ rotate((-7f + 14f * sw) * k, PIVOT) }) {
                    cat(ps, c) { face(c, lo = sw, ro = 1f - sw, lb = 0.5f * sw * k, rb = 0.5f * (1 - sw) * k, ex = 0.7f * scan, shut = blink(p, 0.88f)) }
                }
            }
            else -> {
                val think = sm(seg(p, 0.08f, 0.22f)) * (1 - sm(seg(p, 0.46f, 0.52f)))
                val pop = sm(seg(p, 0.46f, 0.52f)) * (1 - sm(seg(p, 0.68f, 0.84f)))
                val open = sm(seg(p, 0.08f, 0.22f)) * (1 - sm(seg(p, 0.68f, 0.84f)))
                val wob = sin(2 * PI.toFloat() * seg(p, 0.24f, 0.44f) * 2) * think
                withTransform({ translate(0f, -0.5f * pop); rotate(-5f * think, PIVOT) }) {
                    cat(ps, c) { face(c, lo = open, lb = 0.3f * pop, rb = 0.3f * pop, ex = -0.7f * think, ey = -0.7f * think, mx = -0.9f * think + 0.3f * wob, mo = pop) }
                }
            }
        }
    }
}

/** The maintenance cat idling, looping from when it appears; [color] is read while drawing. */
@Composable
internal fun MaintLoop(variant: Int, color: () -> Color, modifier: Modifier) {
    val ps = remember { BootCatMaint.Paths() }
    val ms = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { val t0 = withFrameMillis { it }; while (true) withFrameMillis { ms.longValue = it - t0 } }
    Canvas(modifier) {
        val s = size.minDimension / 24f
        withTransform({ scale(s, s, Offset.Zero) }) { with(BootCatMaint) { drawMaint(ps, color(), variant, ms.longValue.toFloat()) } }
    }
}
