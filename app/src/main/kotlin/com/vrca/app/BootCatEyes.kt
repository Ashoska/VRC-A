package com.vrca.app

import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The thinking Boot Cat's eyes (user picks, 2026-10-29): three eye animations, each one 2.4 s beat that starts and
 * ends on plain centred eyes, played one after another in a shuffled order so the cat never repeats himself and
 * rows thinking at once don't move together.
 *  - A, look around: a little wind-up, a quick glance left (the eyes stretch the way they move, then settle with a
 *    small overshoot), the same to the right, back.
 *  - B, ponder: the eyes drift up to the right and narrow into soft tipped ovals (the left a touch more), wiggle
 *    while he thinks, come back. Replaced the flat "hmm" line, which read as the sleeping face.
 *  - C, follow the light: one lap of the eyes circling the way the running light goes, at a steady speed (tracking
 *    the light's exact spot stuttered on the ears, where the outline is longer).
 * The blink (the eye flattens into a short soft line that sinks a little: shuts fast, holds, opens slower) sits at a
 * different point in each beat, and some beats have none, so it never lands on a fixed rhythm.
 *
 * This is the one source of the eyes: the thinking loop (`ThinkLoop` in BootCat) and the countdown cat draw it.
 */
internal object BootCatEyes {
    const val BEAT_MS = 2400f
    /** Each loop variant's order of beats; a run never repeats a beat, wrap-around included. */
    private val ORDERS = arrayOf("ABCBAC", "BCACBA", "CABACB", "ACBCAB")
    /** Where each beat's blink starts (fraction of the beat; it lasts 0.10); -1 = no blink in that beat. */
    private val BLINKS = arrayOf(
        floatArrayOf(0.70f, 0.50f, 0.80f, -1f, 0.33f, 0.35f),
        floatArrayOf(0.88f, 0.80f, 0.33f, -1f, 0.50f, 0.70f),
        floatArrayOf(0.35f, 0.70f, -1f, 0.33f, 0.80f, 0.50f),
        floatArrayOf(0.33f, -1f, 0.88f, 0.80f, 0.70f, 0.50f),
    )
    /** One full run of a variant's beats (the AVD loops this). */
    val SEQUENCE_MS = BEAT_MS * 6
    const val VARIANTS = 4
    /** The running light's lap start along the head per variant (as the AVDs' trimPathOffset). */
    val LIGHT_PHASE = floatArrayOf(0f, 0.3f, 0.6f, 0.9f)
    /**
     * Angle (radians, from the face centre, y down) of the light's leading tip at the start of a lap, fitted so a
     * steady turn of one lap per 1.2 s stays with it (measured from the head path; see C).
     */
    private const val LIGHT_TIP_ANGLE = -1.4183f

    const val Y0 = 13.6f
    private val X = floatArrayOf(9.3f, 14.7f)

    private fun sm(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
    /** Ease in-out with a little wind-up and a little overshoot (a glance that lands and settles). */
    private fun glance(x: Float): Float {
        val c = x.coerceIn(0f, 1f); val c2 = 1.2f * 1.525f
        return if (c < 0.5f) (2 * c) * (2 * c) * ((c2 + 1) * 2 * c - c2) / 2f
        else ((2 * c - 2) * (2 * c - 2) * ((c2 + 1) * (2 * c - 2) + c2) + 2) / 2f
    }
    /** How shut the eyes are (0..1) in a blink starting at [a] of the beat. */
    private fun shutAt(p: Float, a: Float): Float {
        if (a < 0f || p < a || p > a + 0.10f) return 0f
        val k = seg(p, a, a + 0.10f)
        return when { k < 0.3f -> sm(k / 0.3f); k < 0.45f -> 1f; else -> 1f - sm((k - 0.45f) / 0.55f) }
    }

    /**
     * Writes both eyes for loop [variant] at [ms] since the loop started into [out]: per eye centre x, centre y,
     * width, height, turn (degrees), left eye first. An eye is a capsule: [width] x [height] with round ends
     * (width >= height always; 2.6 x 2.6 = the round open eye).
     */
    fun pose(variant: Int, ms: Float, out: FloatArray) {
        val v = Math.floorMod(variant, VARIANTS)
        val n = ((ms / BEAT_MS).toInt()) % 6
        val p = (ms % BEAT_MS) / BEAT_MS
        val shut = shutAt(p, BLINKS[v][n])
        when (ORDERS[v][n]) {
            'A' -> {
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
                for (i in 0..1) eye(out, i, X[i] + dx, Y0, 2.6f * (1f + 0.4f * speed), 2.6f * (1f - 0.2f * speed), 0f, shut)
            }
            'B' -> {
                val k = sm(seg(p, 0.10f, 0.30f)) * (1 - sm(seg(p, 0.74f, 0.88f)))
                val wig = if (p in 0.34f..0.70f) 0.35f * sin(2 * PI.toFloat() * seg(p, 0.34f, 0.70f) * 2) else 0f
                val dx = (1.0f + wig) * k; val dy = -0.8f * k
                eye(out, 0, X[0] + dx, Y0 + dy, 2.6f + 0.3f * k, 2.6f - 1.0f * k, -10f * k, shut)
                eye(out, 1, X[1] + dx, Y0 + dy, 2.6f + 0.2f * k, 2.6f - 0.7f * k, -6f * k, shut)
            }
            else -> {
                val env = sm(seg(p, 0.06f, 0.18f)) * (1 - sm(seg(p, 0.60f, 0.76f)))
                val lap = (ms / 1200f + LIGHT_PHASE[v]) % 1f
                val ang = LIGHT_TIP_ANGLE + 2 * PI.toFloat() * lap
                for (i in 0..1) eye(out, i, X[i] + 1.3f * env * cos(ang), Y0 + 1.0f * env * sin(ang), 2.6f, 2.6f, 0f, shut)
            }
        }
    }

    /** An open eye [w] x [h] turned [rot], closed by [shut] into a short soft line that sinks a little. */
    private fun eye(out: FloatArray, i: Int, x: Float, y: Float, w: Float, h: Float, rot: Float, shut: Float) {
        val o = i * 5
        out[o] = x; out[o + 1] = y + 0.35f * shut
        out[o + 2] = w + (3.0f - w) * shut; out[o + 3] = h + (0.85f - h) * shut
        out[o + 4] = rot * (1 - shut)
    }

    /** Draws the eyes from [pose]'s output (24-unit grid). */
    fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEyes(e: FloatArray, c: androidx.compose.ui.graphics.Color) {
        for (i in 0..1) {
            val o = i * 5
            val x = e[o]; val y = e[o + 1]; val w = e[o + 2]; val h = e[o + 3]
            rotate(e[o + 4], androidx.compose.ui.geometry.Offset(x, y)) {
                val r = min(w, h) / 2f
                drawRoundRect(c, androidx.compose.ui.geometry.Offset(x - w / 2f, y - h / 2f), androidx.compose.ui.geometry.Size(w, h),
                    androidx.compose.ui.geometry.CornerRadius(r, r))
            }
        }
    }
}
