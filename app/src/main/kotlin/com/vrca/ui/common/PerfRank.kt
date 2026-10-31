package com.vrca.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vrca.ui.theme.GremlinColors
import kotlin.math.cos
import kotlin.math.sin

/** VRChat's performance rank names, by our stored rank (0 = Excellent … 4 = Very Poor, 5 = unknown). */
fun perfRankName(rank: Int): String? = when (rank) {
    0 -> "Excellent"; 1 -> "Good"; 2 -> "Medium"; 3 -> "Poor"; 4 -> "Very Poor"; else -> null
}

/**
 * VRChat's performance rank icon, drawn to the exact proportions of VRChat's own (creators.vrchat.com, measured
 * on the 256 px originals; outer radius 92.5 there): Excellent = green disc with a dark star, Good = green ring
 * with a small dot, Medium = yellow ring with a larger dot, Poor = red ring with a large disc, Very Poor = red
 * double ring with an exclamation mark. Shape carries the rank as well as colour. Nothing is drawn for unknown.
 * Drawn on a Canvas (draw phase only).
 */
@Composable
fun PerfRankIcon(rank: Int, size: Dp = 16.dp, modifier: Modifier = Modifier, label: String? = perfRankName(rank)) {
    if (rank !in 0..4) return
    Canvas(modifier.size(size).semantics { if (label != null) contentDescription = label }) { drawRank(rank) }
}

private fun DrawScope.drawRank(rank: Int) {
    val u = size.minDimension / 185f            // one unit of the 185-unit original (the icon's diameter)
    val c = Offset(size.width / 2f, size.height / 2f)
    val inner = GremlinColors.Performance.Inner
    when (rank) {
        0 -> {
            drawCircle(GremlinColors.Performance.Excellent, 92.5f * u, c)
            // 5-point star, outer radius 61, inner 23.3, centred 4 units below the middle; slightly rounded points.
            val star = Path()
            for (i in 0 until 10) {
                val r = (if (i % 2 == 0) 61f else 23.3f) * u
                val a = Math.toRadians((-90 + i * 36).toDouble())
                val p = Offset(c.x + r * cos(a).toFloat(), c.y + 4f * u + r * sin(a).toFloat())
                if (i == 0) star.moveTo(p.x, p.y) else star.lineTo(p.x, p.y)
            }
            star.close()
            drawPath(star, inner)
            drawPath(star, inner, style = Stroke(width = 4f * u, join = StrokeJoin.Round))
        }
        1, 2 -> {
            val col = if (rank == 1) GremlinColors.Performance.Good else GremlinColors.Performance.Medium
            drawCircle(col, 92.5f * u, c)
            drawCircle(inner, 81f * u, c)
            drawCircle(col, (if (rank == 1) 14f else 38.5f) * u, c)
        }
        3 -> {
            val col = GremlinColors.Performance.Poor
            drawCircle(col, 92.5f * u, c)
            drawCircle(inner, 80.5f * u, c)
            drawCircle(col, 58.5f * u, c)
        }
        else -> {
            val col = GremlinColors.Performance.VeryPoor
            drawCircle(col, 92.5f * u, c)
            drawCircle(inner, 83.5f * u, c)
            drawCircle(col, 71f * u, c)
            // Exclamation: rounded bar (22 wide, 70..149 on the original) + dot (radius 9.5 at 172).
            val w = 21f * u
            drawRoundRect(inner, Offset(c.x - w / 2f, c.y - 58f * u), Size(w, 79f * u), CornerRadius(w / 2f))
            drawCircle(inner, 9.5f * u, Offset(c.x, c.y + 44f * u))
        }
    }
}
