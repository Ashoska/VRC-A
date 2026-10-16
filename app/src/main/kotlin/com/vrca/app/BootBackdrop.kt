package com.vrca.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import com.vrca.ui.theme.GremlinColors
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** The boot screen's back wall. */
enum class BootBackdrop { None, Lanes }

/**
 * One symbol of the back wall: a line drawing in a unit box (0..1 on both axes), drawn with a
 * [SymbolPen]. Nothing is stored as an image; the wall is built live from [BootSymbols.all].
 */
class BootSymbol(val name: String, val weight: Int = 1, val draw: SymbolPen.() -> Unit)

/** Drawing helpers for a symbol, in unit-box coordinates; [s] is the box size in px. */
class SymbolPen(private val scope: DrawScope, private val s: Float, private val ink: Color, private val width: Float) {
    private val line = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        scope.drawLine(ink, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), width, StrokeCap.Round)
    fun dot(x: Float, y: Float, r: Float) = scope.drawCircle(ink, r * s, Offset(x * s, y * s))
    fun ring(x: Float, y: Float, r: Float) = scope.drawCircle(ink, r * s, Offset(x * s, y * s), style = line)
    fun oval(l: Float, t: Float, r: Float, b: Float) =
        scope.drawOval(ink, Offset(l * s, t * s), Size((r - l) * s, (b - t) * s), style = line)
    fun path(build: PathPen.() -> Unit) = scope.drawPath(PathPen(s).apply(build).path, ink, style = line)
}

/** Path building in unit coordinates. */
class PathPen(private val s: Float) {
    val path = Path()
    fun moveTo(x: Float, y: Float) = path.moveTo(x * s, y * s)
    fun lineTo(x: Float, y: Float) = path.lineTo(x * s, y * s)
    fun quadTo(cx: Float, cy: Float, x: Float, y: Float) = path.quadraticBezierTo(cx * s, cy * s, x * s, y * s)
    fun close() = path.close()
}

/**
 * The symbols on the wall: the community's own (the user's picks, 2026-10-16; generic fillers were
 * dropped). To add one, add a [BootSymbol] to [all]; [BootSymbol.weight] makes it show up more or
 * less often.
 */
object BootSymbols {
    /** The fish bone from the Kipfel beanie. */
    val FishBone = BootSymbol("fish bone") {
        path { moveTo(0.18f, 0.5f); lineTo(0.04f, 0.34f); lineTo(0.04f, 0.66f); close() }
        line(0.18f, 0.5f, 0.7f, 0.5f)
        for (x in listOf(0.3f, 0.42f, 0.54f)) { line(x, 0.5f, x + 0.07f, 0.32f); line(x, 0.5f, x + 0.07f, 0.68f) }
        oval(0.66f, 0.36f, 0.96f, 0.64f)
        dot(0.86f, 0.47f, 0.028f)
    }

    /** The community's cheese joke: an emoji-style wedge. */
    val Cheese = BootSymbol("cheese") {
        path {
            moveTo(0.06f, 0.54f); lineTo(0.92f, 0.30f); lineTo(0.94f, 0.54f)
            lineTo(0.94f, 0.76f); lineTo(0.06f, 0.76f); close()
        }
        line(0.06f, 0.54f, 0.94f, 0.54f)
        ring(0.28f, 0.66f, 0.05f); ring(0.55f, 0.66f, 0.04f); ring(0.78f, 0.63f, 0.045f); ring(0.7f, 0.44f, 0.03f)
    }

    /** A Gnarpy (stand-in drawing): spiky body, cat ears, antennae, :3 face, whiskers. */
    val Gnarpy = BootSymbol("gnarpy") {
        line(0.42f, 0.24f, 0.36f, 0.04f); line(0.58f, 0.24f, 0.64f, 0.04f)
        dot(0.36f, 0.04f, 0.035f); dot(0.64f, 0.04f, 0.035f)
        line(0.4f, 0.86f, 0.38f, 0.97f); line(0.6f, 0.86f, 0.62f, 0.97f)
        path {
            val n = 18
            for (i in 0..n) {
                val a = (i.toFloat() / n) * 2 * PI - PI / 2
                val r = if (i % 2 == 0) 0.37f else 0.31f
                val x = 0.5f + r * cos(a).toFloat(); val y = 0.56f + r * 0.92f * sin(a).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        path { moveTo(0.24f, 0.36f); lineTo(0.22f, 0.16f); lineTo(0.38f, 0.26f) }
        path { moveTo(0.76f, 0.36f); lineTo(0.78f, 0.16f); lineTo(0.62f, 0.26f) }
        dot(0.4f, 0.52f, 0.03f); dot(0.6f, 0.52f, 0.03f)
        path { moveTo(0.42f, 0.6f); quadTo(0.46f, 0.66f, 0.5f, 0.6f); quadTo(0.54f, 0.66f, 0.58f, 0.6f) }
        line(0.2f, 0.58f, 0.06f, 0.54f); line(0.2f, 0.62f, 0.06f, 0.66f)
        line(0.8f, 0.58f, 0.94f, 0.54f); line(0.8f, 0.62f, 0.94f, 0.66f)
    }

    /** A sitting kitty. */
    val Kitty = BootSymbol("kitty") {
        oval(0.26f, 0.44f, 0.74f, 0.94f)
        path { moveTo(0.7f, 0.84f); quadTo(0.98f, 0.84f, 0.88f, 0.56f) }
        path {
            moveTo(0.32f, 0.36f); lineTo(0.33f, 0.08f); lineTo(0.46f, 0.2f)
            lineTo(0.54f, 0.2f); lineTo(0.67f, 0.08f); lineTo(0.68f, 0.36f)
            quadTo(0.68f, 0.52f, 0.5f, 0.52f); quadTo(0.32f, 0.52f, 0.32f, 0.36f); close()
        }
        dot(0.43f, 0.33f, 0.022f); dot(0.57f, 0.33f, 0.022f)
        line(0.47f, 0.42f, 0.5f, 0.44f); line(0.5f, 0.44f, 0.53f, 0.42f)
    }

    val all: List<BootSymbol> = listOf(FishBone, Cheese, Gnarpy, Kitty)
}

/** One symbol's slot in a lane: which symbol, where along the lane, and its little tilt/size. */
private class Slot(val symbol: BootSymbol, val x: Float, val dy: Float, val deg: Float, val scale: Float)

/** A lane: a looping strip of slots that slides sideways at [speed] px/s (negative = left). */
private class Lane(val slots: List<Slot>, val length: Float, val speed: Float)

private val LANE_HEIGHT = 76.dp
private val SYMBOL_SIZE = 46.dp

/**
 * The back wall as lanes of symbols, like traffic lanes: each row slides sideways at its own slow
 * speed, neighbouring rows in opposite directions. The order is shuffled every open, so the wall
 * never looks the same twice. Built live from [BootSymbols.all]; the layout is made once per size
 * (drawWithCache) and each frame only moves it.
 */
@Composable
fun BootBackdropLayer(style: BootBackdrop, modifier: Modifier = Modifier.fillMaxSize()) {
    if (style == BootBackdrop.None) return
    val seed = remember { Random.nextInt() }
    val clock = rememberInfiniteTransition(label = "lanes")
    // Seconds, looping every 10 min (far longer than a boot screen is ever up).
    val t: State<Float> = clock.animateFloat(
        0f, 600f, infiniteRepeatable(tween(600_000, easing = LinearEasing)), label = "laneClock"
    )
    Box(
        modifier.drawWithCache {
            val laneH = LANE_HEIGHT.toPx()
            val sym = SYMBOL_SIZE.toPx()
            val lanes = buildLanes(seed, size.width, size.height, laneH, sym, density)
            val ink = GremlinColors.Edge.copy(alpha = 0.6f)
            val width = 1.6.dp.toPx()
            onDrawBehind {
                val now = t.value
                lanes.forEachIndexed { row, lane ->
                    val y = row * laneH + (laneH - sym) / 2 - laneH / 2
                    // Slide, wrapped to the lane's loop so it never runs out.
                    val shift = ((lane.speed * now) % lane.length + lane.length) % lane.length
                    val copies = ceil(size.width / lane.length).toInt() + 2
                    for (k in -1 until copies) for (slot in lane.slots) {
                        val x = slot.x + shift + (k - 1) * lane.length
                        if (x < -sym * 2 || x > size.width + sym) continue
                        val s = sym * slot.scale
                        translate(x, y + slot.dy) {
                            rotate(slot.deg, pivot = Offset(s / 2, s / 2)) {
                                SymbolPen(this, s, ink, width).apply(slot.symbol.draw)
                            }
                        }
                    }
                }
            }
        }
    )
}

private fun buildLanes(seed: Int, w: Float, h: Float, laneH: Float, sym: Float, density: Float): List<Lane> {
    val rnd = Random(seed)
    val pool = BootSymbols.all.flatMap { s -> List(s.weight.coerceAtLeast(1)) { s } }
    val rows = (h / laneH).toInt() + 2
    return List(rows) { row ->
        val slots = mutableListOf<Slot>()
        var x = 0f
        var last: BootSymbol? = null
        // A loop a bit wider than the screen, so the repeat isn't noticeable.
        while (x < w * 1.3f) {
            var pick = pool[rnd.nextInt(pool.size)]
            if (pick == last && pool.size > 1) pick = pool[rnd.nextInt(pool.size)] // fewer twins
            last = pick
            slots += Slot(
                symbol = pick, x = x,
                dy = (rnd.nextFloat() - 0.5f) * laneH * 0.22f,
                deg = (rnd.nextFloat() - 0.5f) * 36f,
                scale = 0.85f + rnd.nextFloat() * 0.3f
            )
            x += sym * (1.55f + rnd.nextFloat() * 0.6f)
        }
        // 9..17 dp/s, alternating direction lane to lane.
        val speed = (9f + rnd.nextFloat() * 8f) * density * (if (row % 2 == 0) 1f else -1f)
        Lane(slots, x, speed)
    }
}
