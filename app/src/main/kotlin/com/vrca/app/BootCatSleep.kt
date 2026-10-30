package com.vrca.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The waiting (asleep) Boot Cat's idle animations (user picks, 2026-10-29): three 3.6 s beats played one after another
 * in a shuffled order, each starting and ending on the exact plain sleepy face so any order hands over cleanly.
 *  - A, breathing: breath in (the head swells a little from the chin, the closed eyes sink into soft curves), out (the
 *    mouth puffs into a little "o").
 *  - B, nodding off: the head droops to one side, the eyes grow heavy and the mouth falls open and slides; a jolt
 *    catches it (mouth snaps shut, eyes scrunch hard), a little past upright, then it settles.
 *  - C, dreaming: an ear twitch, the eyes flicking side to side under the lids, a happy dream (eyes lift into "^ ^",
 *    a smile), a sleepy chewing mumble.
 * He never opens his eyes: in place of a blink the closed eye lines scrunch (a sleepy squeeze), at a different point
 * per beat. Drawn in Compose (the shapes change, which an AnimatedVectorDrawable can't keyframe), the clock read only
 * while drawing; the same code runs in the app and the UI Lab.
 */
internal object BootCatSleep {
    const val BEAT_MS = 3600f
    private val ORDERS = arrayOf("ABCBAC", "BCACBA", "CABACB", "ACBCAB")
    private const val Y = 13.6f

    private fun sm(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
    /** A soft there-and-back bump over [a]..[b] (0 -> 1 -> 0). */
    private fun bump(p: Float, a: Float, b: Float) = if (p <= a || p >= b) 0f else sin(PI.toFloat() * seg(p, a, b)).let { it * it }
    /** The sleepy squeeze at [a] of the beat (lasts 0.12): 0 relaxed .. 1 scrunched. */
    private fun squeeze(p: Float, a: Float) = bump(p, a, a + 0.12f)

    /** Reused paths, so drawing a frame allocates nothing. */
    class Paths { val head = Path(); val eye = Path(); val mouth = Path() }

    /** The head outline with its left ear tip moved by ([ex], [ey]) (the twitch); (0, 0) = the app's HEAD path. */
    private fun Path.head(ex: Float, ey: Float) {
        rewind()
        moveTo(3.92f, 10.46f); lineTo(4.3f + ex, 3f + ey); lineTo(8.2f, 7.1f)
        quadraticTo(12f, 5.1f, 15.8f, 7.1f); lineTo(19.9f, 2.6f); lineTo(20.08f, 10.46f)
        // A8.6,8.6 0 1,1 back to the start: centre (12, 13.405), from -20.03 deg clockwise through the chin.
        arcTo(Rect(Offset(12f, 13.405f), 8.6f), -20.03f, 220.06f, false)
        close()
    }

    /**
     * The sleepy face. Eyes: closed lines, scrunched by [sq] (shorter, thicker, nudged together), lowered by [eyeDy],
     * shifted by [eyeDx], curved by [eyeCurve] (+ sinks into a soft "︶", - lifts into a happy "^"). Mouth: [mw] wide at
     * [mouthDx], opened by [open] (the line parts into a little oval), curved into a smile by [smile]. All zero (mw 2) =
     * the plain sleepy face (the static Waiting face in BootCat).
     */
    private fun DrawScope.face(
        ps: Paths, c: Color, sq: Float = 0f, eyeDy: Float = 0f, eyeDx: Float = 0f, eyeCurve: Float = 0f,
        mw: Float = 2f, mouthDx: Float = 0f, open: Float = 0f, smile: Float = 0f,
    ) {
        val half = 1.2f * (1f - 0.45f * sq); val y = Y + eyeDy
        val st = Stroke(1.9f + 0.6f * sq, cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (i in 0..1) {
            val x = (if (i == 0) 9.3f else 14.7f) + eyeDx + 0.35f * sq * (if (i == 0) 1f else -1f)
            ps.eye.rewind(); ps.eye.moveTo(x - half, y); ps.eye.quadraticTo(x, y + 2f * eyeCurve, x + half, y)
            drawPath(ps.eye, c, style = st)
        }
        val my = Y + 3.6f; val mx = 12f + mouthDx; val h = 1.6f * open
        ps.mouth.rewind()
        ps.mouth.moveTo(mx - mw / 2f, my)
        ps.mouth.quadraticTo(mx, my - h + 2f * smile, mx + mw / 2f, my)
        ps.mouth.quadraticTo(mx, my + h + 2f * smile, mx - mw / 2f, my)
        drawPath(ps.mouth, c, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    private fun DrawScope.outline(ps: Paths, c: Color, ex: Float = 0f, ey: Float = 0f) {
        ps.head.head(ex, ey)
        drawPath(ps.head, c, style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    /** Draws loop [variant] at [ms] since it started (24-unit grid). */
    fun DrawScope.drawSleeping(ps: Paths, c: Color, variant: Int, ms: Float) {
        val n = ((ms / BEAT_MS).toInt()) % 6
        val p = (ms % BEAT_MS) / BEAT_MS
        when (ORDERS[Math.floorMod(variant, ORDERS.size)][n]) {
            'A' -> {
                val inhale = bump(p, 0.04f, 0.56f); val puff = bump(p, 0.50f, 0.92f)
                val b = sin(PI.toFloat() * seg(p, 0.05f, 0.90f)).let { it * it }
                withTransform({ scale(1f + 0.035f * b, 1f + 0.08f * b, Offset(12f, 21.5f)) }) {
                    outline(ps, c)
                    face(ps, c, squeeze(p, 0.30f), eyeDy = -0.3f * inhale, eyeCurve = 0.45f * inhale, mw = 2f - 0.6f * puff, open = 0.55f * puff)
                }
            }
            'B' -> {
                val droop = sm(seg(p, 0.08f, 0.55f)); val back = sm(seg(p, 0.55f, 0.62f)); val over = bump(p, 0.58f, 0.80f)
                val heavy = droop * (1 - back)
                withTransform({ translate(0f, 0.7f * heavy); rotate(8f * heavy - 2.5f * over, Offset(12f, 21.5f)) }) {
                    outline(ps, c)
                    face(ps, c, 1.3f * squeeze(p, 0.555f), eyeDy = 0.25f * heavy, eyeCurve = 0.5f * heavy,
                        mw = 2f - 0.5f * heavy - 0.5f * over, mouthDx = 0.5f * heavy, open = 0.5f * heavy)
                }
            }
            else -> {
                val tw = bump(p, 0.04f, 0.11f) + 0.7f * bump(p, 0.12f, 0.19f)
                val rem = sqrt(bump(p, 0.20f, 0.38f)) * sin(2 * PI.toFloat() * seg(p, 0.20f, 0.38f) * 4)
                val happy = sqrt(bump(p, 0.40f, 0.64f))
                val chewEnv = sqrt(bump(p, 0.66f, 0.92f))
                val chew = chewEnv * (0.5f + 0.5f * sin(2 * PI.toFloat() * seg(p, 0.66f, 0.92f) * 3 - PI.toFloat() / 2))
                outline(ps, c, -1.1f * tw, 1.5f * tw)
                face(ps, c, squeeze(p, 0.385f), eyeDx = 0.55f * rem, eyeCurve = -0.4f * happy,
                    mw = 2f + 0.4f * happy - 0.5f * chewEnv, open = 0.45f * chew, smile = 0.45f * happy)
            }
        }
    }
}

/** The sleeping cat, looping from when it appears; [color] is read while drawing. */
@Composable
internal fun SleepLoop(variant: Int, color: () -> Color, modifier: Modifier) {
    val ps = remember { BootCatSleep.Paths() }
    val ms = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { val t0 = withFrameMillis { it }; while (true) withFrameMillis { ms.longValue = it - t0 } }
    Canvas(modifier) {
        val s = size.minDimension / 24f
        withTransform({ scale(s, s, Offset.Zero) }) { with(BootCatSleep) { drawSleeping(ps, color(), variant, ms.longValue.toFloat()) } }
    }
}

