package com.vrca.app

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The counting-down Boot Cat's eyes (user picks, 2026-10-30): plain dots, playing three 2.4 s beats in a shuffled
 * order, each starting and ending on the plain dots. They run on their own clock, never tied to how long the countdown
 * is (a 5 s Retry or a longer terms wait; user):
 *  - A, checking the clock: the eyes glance toward where the time is running out (the outline's shrinking end), hold,
 *    look back; a blink.
 *  - B, tapping along: three small even head bobs, like tapping a foot while waiting (the whole cat). No blink.
 *  - C, deep breath: the eyes close softly, the head rises a little as if breathing in, settles, the eyes open.
 * Drawn in Compose by [BootCatCountdown], the clock read only while drawing. Lab mock: `show countdownmock`.
 */
internal object BootCatCd {
    const val BEAT_MS = 2400f
    private const val ORDER = "ABCBAC"
    private const val Y = 13.6f
    private val PIVOT = Offset(12f, 21.5f)

    private fun sm(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
    private fun bump(p: Float, a: Float, b: Float) = if (p <= a || p >= b) 0f else sin(PI.toFloat() * seg(p, a, b))
    private fun blink(p: Float, a: Float, len: Float = 0.10f): Float {
        if (p < a || p > a + len) return 0f
        val k = seg(p, a, a + len)
        return when { k < 0.3f -> sm(k / 0.3f); k < 0.45f -> 1f; else -> 1f - sm((k - 0.45f) / 0.55f) }
    }

    /**
     * Draws the cat at [ms]: [timer] draws the outline (time left) inside the cat's bob/breath transform, then the
     * eyes. [endAt] is where on the outline (0..1 along [m]) the time is running out, where beat A glances.
     */
    fun DrawScope.drawCountdownCat(c: Color, ms: Float, m: PathMeasure, endAt: Float, timer: DrawScope.() -> Unit) {
        val p = (ms % BEAT_MS) / BEAT_MS
        var ty = 0f; var rot = 0f; var sx = 1f; var sy = 1f
        var shut = 0f; var dx = 0f; var dy = 0f
        when (ORDER[((ms / BEAT_MS).toInt()) % ORDER.length]) {
            'A' -> {
                val k = sm(seg(p, 0.10f, 0.22f)) * (1 - sm(seg(p, 0.56f, 0.68f)))
                val pos = m.getPosition(endAt * m.length)
                val vx = pos.x - 12f; val vy = pos.y - 12.5f; val l = hypot(vx, vy).coerceAtLeast(0.01f)
                dx = 1.4f * k * vx / l; dy = 1.0f * k * vy / l; shut = blink(p, 0.78f)
            }
            'B' -> {
                var b = 0f
                for (i in 0 until 3) { val a = 0.10f + i * 0.22f; b += bump(p, a, a + 0.14f) }
                ty = 0.7f * b; rot = 2f * b; dy = 0.15f * b
            }
            else -> {
                shut = sm(seg(p, 0.10f, 0.24f)) * (1 - sm(seg(p, 0.62f, 0.76f)))
                val rise = sm(seg(p, 0.16f, 0.44f)) * (1 - sm(seg(p, 0.48f, 0.72f)))
                ty = -0.6f * rise; sx = 1f + 0.04f * rise; sy = 1f + 0.05f * rise
            }
        }
        withTransform({ translate(0f, ty); rotate(rot, PIVOT); scale(sx, sy, PIVOT) }) {
            timer()
            val w = 2.6f + 0.6f * shut; val h = 2.6f + (0.8f - 2.6f) * shut; val r = min(w, h) / 2f
            for (x0 in floatArrayOf(9.3f, 14.7f)) {
                val x = x0 + dx; val y = Y + dy + 0.3f * shut
                drawRoundRect(c, Offset(x - w / 2f, y - h / 2f), Size(w, h), CornerRadius(r, r))
            }
        }
    }
}
