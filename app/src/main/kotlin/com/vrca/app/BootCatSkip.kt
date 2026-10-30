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

/**
 * The skipped Boot Cat (user picks, 2026-10-29): awake but unbothered, so it never reads as the sleeping Waiting cat.
 * Resting face: open, slightly relaxed eyes and a small content smile, on the grey cat with its soft fill. Idles in
 * three 3 s beats played in a shuffled order, each starting and ending on the resting face:
 *  - A, yawn: stretches tall with both ears laid back, eyes squeezed shut, mouth wide; settles with a pleased look.
 *  - B, lazy look: the eyes slide slowly to one side, a slow lazy blink there, drift back.
 *  - C, humming: sways side to side in rhythm, eyes narrowed happily, mouth a little whistling "o"; a quick blink.
 * Awake blinks (the eye shuts and reopens: fast, hold, slower), unlike Waiting's sleepy squeeze. Drawn in Compose,
 * the clock read only while drawing; the same code runs in the app and the UI Lab.
 */
internal object BootCatSkip {
    const val BEAT_MS = 3000f
    private val ORDERS = arrayOf("ABCBAC", "BCACBA", "CABACB", "ACBCAB")
    private const val Y = 13.6f

    private fun sm(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
    private fun bump(p: Float, a: Float, b: Float) = if (p <= a || p >= b) 0f else sin(PI.toFloat() * seg(p, a, b)).let { it * it }
    /** A blink over [a]..[a]+[len]: shuts fast (30%), holds, opens slower. 0 open .. 1 shut. */
    private fun blink(p: Float, a: Float, len: Float): Float {
        if (p < a || p > a + len) return 0f
        val k = seg(p, a, a + len)
        return when { k < 0.3f -> sm(k / 0.3f); k < 0.45f -> 1f; else -> 1f - sm((k - 0.45f) / 0.55f) }
    }

    /** Reused paths, so drawing a frame allocates nothing. */
    class Paths { val head = Path(); val mouth = Path() }

    /** The head with each ear tip moved outward by [ears] (0 = the app's HEAD path; the yawn lays them back). */
    private fun Path.head(ears: Float) {
        rewind()
        moveTo(3.92f, 10.46f); lineTo(4.3f - 0.9f * ears, 3f + 1.0f * ears); lineTo(8.2f, 7.1f)
        quadraticTo(12f, 5.1f, 15.8f, 7.1f); lineTo(19.9f + 0.9f * ears, 2.6f + 1.0f * ears); lineTo(20.08f, 10.46f)
        arcTo(Rect(Offset(12f, 13.405f), 8.6f), -20.03f, 220.06f, false)
        close()
    }

    /**
     * The awake, unbothered face. Eyes: soft capsules [eh] tall (2.05 = relaxed, a little narrowed from the 2.6 round
     * eye; 0.85 shut), [ew] wide, moved by ([dx], [dy]). Mouth: [mw] wide, curved by [smile], opened by [open] into an
     * oval. The defaults are the resting face.
     */
    fun DrawScope.face(ps: Paths, c: Color, eh: Float = 2.05f, ew: Float = 2.6f, dx: Float = 0f, dy: Float = 0f, mw: Float = 2.2f, smile: Float = 0.45f, open: Float = 0f) {
        val r = min(ew, eh) / 2f
        for (x in floatArrayOf(9.3f, 14.7f)) drawRoundRect(c, Offset(x + dx - ew / 2f, Y + 0.3f + dy - eh / 2f), Size(ew, eh), CornerRadius(r, r))
        val my = Y + 3.5f; val h = 1.6f * open
        ps.mouth.rewind()
        ps.mouth.moveTo(12f - mw / 2f, my)
        ps.mouth.quadraticTo(12f, my - h + 2f * smile, 12f + mw / 2f, my)
        ps.mouth.quadraticTo(12f, my + h + 2f * smile, 12f - mw / 2f, my)
        drawPath(ps.mouth, c, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    private inline fun DrawScope.cat(ps: Paths, c: Color, ears: Float = 0f, face: DrawScope.() -> Unit) {
        ps.head.head(ears)
        drawPath(ps.head, c.copy(alpha = c.alpha * 0.16f))
        drawPath(ps.head, c, style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        face()
    }

    /** Draws loop [variant] at [ms] since it started (24-unit grid). */
    fun DrawScope.drawSkipped(ps: Paths, c: Color, variant: Int, ms: Float) {
        val n = ((ms / BEAT_MS).toInt()) % 6
        val p = (ms % BEAT_MS) / BEAT_MS
        when (ORDERS[Math.floorMod(variant, ORDERS.size)][n]) {
            'A' -> {
                val stretch = bump(p, 0.08f, 0.62f)
                val shut = sm(seg(p, 0.14f, 0.24f)) * (1 - sm(seg(p, 0.52f, 0.62f)))
                val wide = bump(p, 0.16f, 0.56f); val pleased = bump(p, 0.60f, 0.92f)
                withTransform({ scale(1f - 0.03f * stretch, 1f + 0.09f * stretch, Offset(12f, 21.5f)) }) {
                    cat(ps, c, stretch) {
                        face(ps, c, eh = 2.05f - 1.2f * shut - 0.5f * pleased, ew = 2.6f + 0.4f * shut, mw = 2.2f + 0.5f * wide,
                            smile = 0.45f * (1 - wide) + 0.2f * pleased, open = 1.1f * wide)
                    }
                }
            }
            'B' -> {
                val look = sm(seg(p, 0.08f, 0.30f)) * (1 - sm(seg(p, 0.66f, 0.86f)))
                val shut = blink(p, 0.40f, 0.22f)
                cat(ps, c) { face(ps, c, eh = 2.05f - 1.2f * shut - 0.3f * look, dx = 1.3f * look, dy = -0.15f * look, smile = 0.45f - 0.15f * look) }
            }
            else -> {
                val env = sm(seg(p, 0.06f, 0.18f)) * (1 - sm(seg(p, 0.70f, 0.86f)))
                val sway = env * sin(2 * PI.toFloat() * seg(p, 0.06f, 0.86f) * 2)
                val shut = blink(p, 0.88f, 0.09f)
                rotate(5f * sway, Offset(12f, 21.5f)) {
                    cat(ps, c) { face(ps, c, eh = 2.05f - 0.6f * env - 0.6f * shut, dx = 0.4f * sway, mw = 2.2f - 1.0f * env, smile = 0.45f * (1 - env), open = 0.45f * env) }
                }
            }
        }
    }
}

/** The skipped cat idling, looping from when it appears; [color] is read while drawing. */
@Composable
internal fun SkipLoop(variant: Int, color: () -> Color, modifier: Modifier) {
    val ps = remember { BootCatSkip.Paths() }
    val ms = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { val t0 = withFrameMillis { it }; while (true) withFrameMillis { ms.longValue = it - t0 } }
    Canvas(modifier) {
        val s = size.minDimension / 24f
        withTransform({ scale(s, s, Offset.Zero) }) { with(BootCatSkip) { drawSkipped(ps, color(), variant, ms.longValue.toFloat()) } }
    }
}
