package com.vrca.uilab

import com.vrca.app.BootCatEyes
import java.io.File
import java.util.Locale

/**
 * Writes the thinking Boot Cat's AnimatedVectorDrawables (`res/drawable/boot_cat_think_0..3.xml`) by sampling
 * [BootCatEyes], so the device plays exactly what the countdown cat and the lab draw. Lab command `genavd`
 * (`tools/ui-lab/ui.sh run headset genavd`); re-run it whenever BootCatEyes changes. The calm loop still comes
 * from tools/symbol-lab/boot_cat_avd.py.
 *
 * Each eye is a round-capped line inside a group: the line's stroke width is the eye's height, its trimmed length
 * the width minus the height (a capsule, like drawEyes), the group moves and turns it. Every property is sampled at
 * 60 fps over one run of the variant's six beats and thinned to the keyframes that matter.
 */
object BootCatAvdGen {
    private const val HEAD = "M3.92,10.46 L4.3,3 L8.2,7.1 Q12,5.1 15.8,7.1 L19.9,2.6 L20.08,10.46 A8.6,8.6 0 1,1 3.92,10.46 Z"
    private val X = floatArrayOf(9.3f, 14.7f)
    private const val HALF = 1.5f // half the eye line's full length (3 units)

    private fun f(v: Float) = String.format(Locale.ROOT, "%.4f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    /** Ramer-Douglas-Peucker on (fraction, value): keeps the points a straight-line keyframe can't skip within [tol]. */
    private fun thin(pts: List<Pair<Float, Float>>, tol: Float): List<Pair<Float, Float>> {
        val keep = BooleanArray(pts.size); keep[0] = true; keep[pts.size - 1] = true
        val stack = ArrayDeque(listOf(0 to pts.size - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var worst = -1; var dist = tol
            for (i in a + 1 until b) {
                val t = (pts[i].first - pts[a].first) / (pts[b].first - pts[a].first)
                val d = kotlin.math.abs(pts[i].second - (pts[a].second + t * (pts[b].second - pts[a].second)))
                if (d > dist) { dist = d; worst = i }
            }
            if (worst >= 0) { keep[worst] = true; stack.add(a to worst); stack.add(worst to b) }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    private fun anim(prop: String, pts: List<Pair<Float, Float>>, durMs: Int, repeat: Boolean = true) =
        """<objectAnimator android:duration="$durMs" ${if (repeat) "android:repeatCount=\"infinite\"" else ""} android:interpolator="@android:anim/linear_interpolator">""" +
            """<propertyValuesHolder android:propertyName="$prop" android:valueType="floatType">""" +
            pts.joinToString("") { """<keyframe android:fraction="${f(it.first)}" android:value="${f(it.second)}"/>""" } +
            "</propertyValuesHolder></objectAnimator>"

    /** The light's trimPathOffset: two laps per 2.4 s from [phase], wrapping 1 -> 0 cleanly (as boot_cat_avd.py). */
    private fun lightOffset(phase: Float): List<Pair<Float, Float>> {
        val pts = mutableListOf(0f to phase)
        for (k in 1..2) { val t = (k - phase) / 2f; if (t < 1f - 1e-6f) { pts += (t - 1e-4f) to 1f; pts += t to 0f } }
        pts += 1f to (if (phase == 0f) 1f else phase)
        return pts
    }

    fun think(v: Int): String {
        val dur = BootCatEyes.SEQUENCE_MS
        val n = (dur / (1000f / 60f)).toInt()
        val e = FloatArray(10)
        // tx, ty, rotation, strokeWidth, trimPathStart, trimPathEnd per eye
        val tracks = Array(2) { Array(6) { ArrayList<Pair<Float, Float>>(n + 1) } }
        for (k in 0..n) {
            val ms = dur * k / n
            BootCatEyes.pose(v, if (k == n) 0f else ms, e) // the last keyframe = the first: a seamless loop
            for (i in 0..1) {
                val o = i * 5
                val len = (e[o + 2] - e[o + 3]).coerceAtLeast(0.01f) / (2 * HALF)
                val fr = k / n.toFloat()
                tracks[i][0] += fr to (e[o] - X[i]); tracks[i][1] += fr to (e[o + 1] - BootCatEyes.Y0)
                tracks[i][2] += fr to e[o + 4]; tracks[i][3] += fr to e[o + 3]
                tracks[i][4] += fr to (0.5f - len / 2f); tracks[i][5] += fr to (0.5f + len / 2f)
            }
        }
        // About a fifth of a pixel at row size (one grid unit is 1-1.7 dp there).
        val tol = floatArrayOf(0.05f, 0.05f, 0.5f, 0.04f, 0.012f, 0.012f)
        BootCatEyes.pose(v, 0f, e)
        val eyes = (0..1).joinToString("\n") { i ->
            val o = i * 5; val len = (e[o + 2] - e[o + 3]).coerceAtLeast(0.01f) / (2 * HALF)
            """        <group android:name="eye$i" android:pivotX="${f(X[i])}" android:pivotY="${f(BootCatEyes.Y0)}">
          <path android:name="eyeLine$i" android:pathData="M${f(X[i] - HALF)},${f(BootCatEyes.Y0)} L${f(X[i] + HALF)},${f(BootCatEyes.Y0)}" android:strokeColor="#FFFFFFFF" android:strokeWidth="${f(e[o + 3])}" android:strokeLineCap="round" android:trimPathStart="${f(0.5f - len / 2f)}" android:trimPathEnd="${f(0.5f + len / 2f)}"/>
        </group>"""
        }
        // Both eyes always move together (only their shape and turn differ), so one parent group moves them.
        val d = dur.toInt()
        val move = """  <target android:name="eyes"><aapt:attr name="android:animation"><set>
    ${anim("translateX", thin(tracks[0][0], tol[0]), d)}
    ${anim("translateY", thin(tracks[0][1], tol[1]), d)}
  </set></aapt:attr></target>"""
        val targets = move + "\n" + (0..1).joinToString("\n") { i ->
            val t = tracks[i].mapIndexed { j, pts -> thin(pts, tol[j]) }
            """  <target android:name="eye$i"><aapt:attr name="android:animation">${anim("rotation", t[2], d)}</aapt:attr></target>
  <target android:name="eyeLine$i"><aapt:attr name="android:animation"><set>
    ${anim("strokeWidth", t[3], d)}
    ${anim("trimPathStart", t[4], d)}
    ${anim("trimPathEnd", t[5], d)}
  </set></aapt:attr></target>"""
        }
        val ph = BootCatEyes.LIGHT_PHASE[v]
        return """<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED by the UI Lab (`genavd`, BootCatAvdGen.kt) from BootCatEyes: the boot cat thinking while a check runs,
     loop variant $v. An AnimatedVectorDrawable so Android plays it on the RenderThread (keeps moving while the main
     thread is busy). A light runs round the head every 1.2 s (grows in over 0.4 s); the eyes play the look-around,
     ponder and follow-the-light beats in this variant's shuffled order. White, tinted from code. Don't edit by hand. -->
<animated-vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt">
  <aapt:attr name="android:drawable">
    <vector android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
      <path android:pathData="$HEAD" android:strokeColor="#FFFFFFFF" android:strokeAlpha="0.25" android:strokeWidth="1.9" android:strokeLineCap="round" android:strokeLineJoin="round"/>
      <path android:name="light" android:pathData="$HEAD" android:strokeColor="#FFFFFFFF" android:strokeAlpha="0" android:strokeWidth="2.3" android:strokeLineCap="round" android:strokeLineJoin="round" android:trimPathStart="0" android:trimPathEnd="0" android:trimPathOffset="${f(ph)}"/>
      <group android:name="eyes">
$eyes
      </group>
    </vector>
  </aapt:attr>
  <target android:name="light"><aapt:attr name="android:animation"><set>
    ${anim("trimPathOffset", lightOffset(ph), 2400)}
    ${anim("trimPathEnd", listOf(0f to 0f, 1f to 0.28f), 400, repeat = false)}
    ${anim("strokeAlpha", listOf(0f to 0f, 1f to 1f), 400, repeat = false)}
  </set></aapt:attr></target>
$targets
</animated-vector>
"""
    }

    fun write(resDir: File): String =
        (0 until BootCatEyes.VARIANTS).joinToString(", ") { v ->
            val f = File(resDir, "boot_cat_think_$v.xml"); f.writeText(think(v)); "${f.name} ${f.length() / 1024} KB"
        }
}
