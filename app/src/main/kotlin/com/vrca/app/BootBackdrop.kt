package com.vrca.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import com.vrca.ui.theme.GremlinColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** The boot screen's back wall. */
enum class BootBackdrop { None, Lanes }

/**
 * One symbol of the back wall: a line drawing in a unit box (0..1 on both axes), drawn with a
 * [SymbolPen]. Nothing is stored as an image; the wall is built live from [BootSymbols.all].
 */
class BootSymbol(val name: String, val weight: Int = 1, val draw: SymbolPen.() -> Unit) {
    companion object {
        /**
         * A symbol straight from an SVG drawing: paste the `d` of each `<path>` (outline drawings;
         * fills are drawn as outlines). [viewBox] is the drawing's square viewBox size (the
         * community guide asks for 100x100). How community submissions get added.
         */
        fun fromSvg(name: String, vararg pathData: String, viewBox: Float = 100f, weight: Int = 1): BootSymbol {
            val paths = pathData.map { androidx.compose.ui.graphics.vector.PathParser().parsePathString(it).toPath() }
            return BootSymbol(name, weight) { svg(paths, viewBox) }
        }
    }
}

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
    /** SVG paths drawn in a [viewBox]-sized square, scaled to the symbol box. The stroke is
     *  divided by the scale so every symbol keeps the same line weight. */
    fun svg(paths: List<Path>, viewBox: Float) {
        val k = s / viewBox
        val scaled = Stroke(width = width / k, cap = StrokeCap.Round, join = StrokeJoin.Round)
        scope.drawContext.transform.scale(k, k, Offset.Zero)
        paths.forEach { scope.drawPath(it, ink, style = scaled) }
        scope.drawContext.transform.scale(1f / k, 1f / k, Offset.Zero)
    }
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
 * dropped). To add one, add a [BootSymbol] to [all]: hand-drawn with a [SymbolPen], or a community
 * drawing via [BootSymbol.fromSvg] (guide: 100x100 viewBox, outline only, about 4px lines).
 * [BootSymbol.weight] makes it show up more or less often.
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

    /** Niko: a cat-eared girl shouting into a megaphone (fan drawing, traced by tools/symbol-lab; the
     *  mouth-top dip is hand-shaped: it's smaller than the pen line, so tracing averages it away). */
    val Niko = BootSymbol.fromSvg("niko", "M38.9 61.8C37.2 62.1 35.7 62.1 34 61.9M60.6 65.3C59.4 66.9 58.1 67.9 56.2 68M31.3 75.9C31.3 75.9 31.5 75.8 31.6 75.9C31.7 76 31.5 76.4 31.4 76.5C31.2 76.5 31.4 75.9 31.3 75.9M66.5 40.6C66.1 40.4 65.7 40.3 65.4 39.9C64.4 38.5 65.1 35 65.4 33.6M66.5 40.6C64.2 41.9 64.1 44.4 64.3 46.8M57.5 42.5C55.5 41.8 53.6 41.1 51.6 40.7M28 66.8C28.9 66.2 29.7 65.6 30.7 65.1M71.1 42.2C73.3 42.6 74.8 41.9 77 41.3M48.3 33.6C50.8 33.1 53.2 32.7 55.8 32.6M71.1 42.2C69.8 44.8 67.2 46.2 64.3 46.8M72.8 32.7C73.6 32.4 74.4 32.1 75.3 31.8M28 66.8C27.8 67.2 27.9 67.6 27.6 67.9C26.2 69 22.4 65.2 21.7 64.2M53.9 19.1C55.8 21.7 57.4 21.5 59 25.1M14.9 54.5C14.1 55.7 12.3 59.2 13.1 60.6C13.4 61.2 13.8 61.2 14.3 61.5M64.1 61.6C62.3 60.7 60.5 60.6 58.7 61.5M27.7 39.2C31 38 35.1 35.9 38.7 36.4M50.9 60.2C46.8 59.9 43.1 62.5 38.9 61.8M35.3 54.7C34.5 57.1 35.1 60.1 34 61.9M64.3 46.8C62.7 48.9 60.8 50.3 58.2 51M18 68.4C16.7 65.9 16.3 63.3 18.1 61M50.9 60.2C51.6 60.8 52.2 61.3 53.1 61.7M88 55.3C90 57.4 92.6 58.8 95.3 60M28.1 90.3C24.8 90.4 22.2 90.2 19.1 89M51.6 40.7C49.8 38.4 49.4 36.2 48.3 33.6M58.2 51C57.9 48.2 58.2 45.2 57.5 42.5M31.3 75.9C30.3 77.4 29.1 77.7 27.8 78.8C24.5 81.7 21.1 85.1 19.1 89M55.8 32.6C57.5 32.2 63.4 30.9 64.8 32C65.4 32.4 65.3 33 65.4 33.6M18 68.4C19.9 67.1 20.7 66.1 21.7 64.2M15.4 44.9C15.6 47.3 15.7 49.8 16.7 52M29.6 84.1C29.1 86.1 28.4 88.2 28.1 90.3M34.3 69.1C32 72 31.4 72 31.3 75.9M64.1 61.6C67.5 60.7 71.4 61.2 74.6 62.1M30.7 65.1C31.6 66.9 32.8 67.8 34.3 69.1M10 39.3C6.5 33.6 10.7 22 13 16.2C15.1 17.7 17.2 19.6 19.4 20.8C20.8 21.6 22.3 22 23.6 22.7M10 39.3C7.8 41.2 6.6 43.4 5.5 46.1M34 61.9C32.9 63 31.7 63.9 30.7 65.1M24.5 40.6C24.9 42.8 24.3 45.1 24.9 47.3M56.2 68C55.3 70.4 53.4 70.9 51.3 72.1M17.7 33.7C20 33.3 21.8 32.4 23.8 31.1M10 39.3C13.2 37.9 15.6 36.4 17.7 33.7M71.1 42.2C69.6 41.6 68 41.3 66.5 40.6M31.3 75.9C30.8 78.7 30 81.3 29.6 84.1M38.7 36.4C41.4 34.5 45.1 34.1 48.3 33.6M56.2 68C55.6 64.3 54.5 64.7 53.1 61.7M58.1 83.1C63 80.8 67.4 77.2 71.8 74C73.4 72.9 76.1 71.2 76.8 69.2C77.5 67.1 75.8 63.7 74.6 62.1M58.1 83.1C59.3 84.4 59.7 85.8 60.4 87.5C51.7 91.5 47.2 92 37.7 92.2C34.7 92.2 30.5 92.4 28.1 90.3M72.8 32.7C70.3 32.6 67.5 32.2 65.4 33.6M77 41.3C82.6 46.4 82.2 55.2 80.5 61.9C78.4 62.1 76.7 62.3 74.6 62.1M55.3 81.2C53.7 78.2 51.9 75.5 51.3 72.1M37 40.4C37.6 39 38.3 37.8 38.7 36.4M21.7 64.2C21 62.2 20.1 61.2 18.1 61M35.3 54.7C35.9 55.6 37.2 55.9 38.2 55.6C39.3 55.3 40.5 54.9 41.3 54.7M72.8 32.7C73.8 35.9 71.6 37 69.2 38.7C68.3 39.3 67.5 40.1 66.5 40.6M14.9 54.5C15.7 54.6 16.4 54.6 17 55.1C18.4 56.3 18.7 59.4 18.1 61M45.1 40.1C45.2 41.6 45.5 42.9 46 44.2M55.3 81.2C56.3 82 57 82.5 58.1 83.1M15.4 44.9C13.5 45.4 11.6 45.9 9.8 46.7M24.5 40.6C21.4 41 18.7 42.4 15.7 43.1M68.7 26.1C70 20.2 66.5 11.5 62 7.6M51.6 40.7C51.7 42 51.8 43.2 51.9 44.5C49.9 44.8 48.1 44.3 46 44.2M57.5 42.5C57.2 39.2 56.4 35.9 55.8 32.6M41.3 54.7C42.8 54.6 43.8 54.3 45.1 53.6M53.9 19.1C54.8 17.6 56.1 16.3 57.1 14.8C58.8 12.3 59.8 9.7 62 7.6M53.1 61.7C54.8 60.9 56.8 61.3 58.7 61.5M23.6 22.7C27 21.9 29 18.9 33.2 17.8C38 16.5 43.1 16.5 47.9 17.6C49.9 18.1 51.8 18.8 53.9 19.1M23.6 22.7C22 26.9 18.4 28.7 17.7 33.7M27.7 39.2C26.5 39.5 25.5 39.8 24.5 40.6M86.3 48.1C87.9 48.1 89.6 48 91.2 48.1M38.9 61.8C39.5 59.3 40.8 57.1 41.3 54.7M60.6 65.3C62.4 64.5 63.4 63.5 64.1 61.6M14.9 54.5C11.2 54.8 9.3 55.2 5.4 55.1C5.9 53.9 6.1 52.5 6.7 51.4C7.6 49.7 8.9 48.3 9.8 46.7M50.9 60.2C52.6 56.5 56.2 54.5 58.2 51M34 61.9C31.5 61 29.1 59.9 26.4 59.3M5.5 46.1C7 46.2 8.4 46.3 9.8 46.7M83.8 43.4C86 42.2 88 41 90.1 39.7M37 40.4C32.7 42.2 29.6 46.3 24.9 47.3M24.9 47.3C23.8 50.9 24.6 55.9 26.4 59.3M60.6 65.3C59.7 64.1 59.1 63 58.7 61.5M28 66.8C27.2 64.3 26.2 61.9 26.4 59.3M68.7 26.1C65.5 26 62.2 25.4 59 25.1M46 44.2C46 45.3 46 46.3 45.8 47.3C42 46.3 39.5 43.2 37 40.4M68.7 26.1C71.5 28.3 73.4 28.2 75.3 31.8M51.3 72.1C48 72.1 44.8 73.7 41.4 74.1C37.7 74.6 34.4 73.6 31.3 75.9M48 78.4C44.4 77.8 40.9 78.4 37.4 79.1C37.8 81.1 37.9 82.9 38.8 84.7C42.2 84.9 45.9 83.7 49.3 83C48.9 81.2 49 79.9 48 78.4M52.2 47.8a0.6 0.6 0 1 0 1.2 0a0.6 0.6 0 1 0 -1.2 0M32.4 50.4a0.7 0.7 0 1 0 1.3 0a0.7 0.7 0 1 0 -1.3 0")

    val all: List<BootSymbol> = listOf(FishBone, Cheese, Gnarpy, Kitty, Niko)
}

/**
 * One symbol on the wall, as a small physics body: it drifts at its lane's speed, a spring pulls
 * it back to its lane's height, and it bounces off any symbol it touches (they never overlap).
 */
private class Sprite(
    val symbol: BootSymbol, var x: Float, var y: Float, val laneY: Float, val laneSpeed: Float,
    val size: Float, val deg: Float, val mirror: Boolean, val phase: Float
) {
    var vx = laneSpeed
    var vy = 0f
    /** Collision radius: the drawing fills most of its box. */
    val r = size * 0.46f
}

/** The wall's state: built once per size (shuffled each open), then stepped every frame. */
private class Wall(private val seed: Int) {
    var sprites: List<Sprite> = emptyList(); private set
    private var builtFor = 0L
    private var w = 0f
    private var margin = 0f

    fun ensure(width: Float, height: Float, density: Float) {
        val key = (width.toLong() shl 32) or height.toLong()
        if (key == builtFor) return
        builtFor = key
        val rnd = Random(seed)
        val sym = SYMBOL_SIZE.value * density
        val laneH = LANE_HEIGHT.value * density
        w = width; margin = sym * 1.5f
        val world = width + 2 * margin
        val pool = BootSymbols.all.flatMap { b -> List(b.weight.coerceAtLeast(1)) { b } }
        val out = mutableListOf<Sprite>()
        // Rows of uneven height, from just above the top edge to just below the bottom.
        var y = -laneH * 0.3f
        while (y < height + laneH * 0.3f) {
            val rowH = laneH * (0.8f + rnd.nextFloat() * 0.5f)
            val laneY = y + rowH / 2
            // 6..20 dp/s, direction random per lane.
            val speed = (6f + rnd.nextFloat() * 14f) * density * (if (rnd.nextBoolean()) 1f else -1f)
            // Spaced so lane-mates (which move together) never touch, with now and then a big gap.
            val picks = mutableListOf<Triple<BootSymbol, Float, Float>>() // symbol, size, x along lane
            var x = 0f
            var last: BootSymbol? = null
            var size = sym * (0.78f + rnd.nextFloat() * 0.5f)
            while (x < world - sym) {
                var pick = pool[rnd.nextInt(pool.size)]
                if (pick == last && pool.size > 1) pick = pool[rnd.nextInt(pool.size)] // fewer twins
                last = pick
                picks += Triple(pick, size, x)
                val next = sym * (0.78f + rnd.nextFloat() * 0.5f)
                // Centre-to-centre from the pair's mean size: 1.0x clears their collision radii
                // (0.46 each), so lane-mates still never touch. Tight (user 2026-10-22: the wall had
                // a lot of blank space at 1.2-2.3x of one size), now and then a slightly wider gap.
                x += (size + next) / 2 * (1.0f + rnd.nextFloat() * 0.45f) * (if (rnd.nextFloat() < 0.06f) 1.4f else 1f)
                size = next
            }
            // Stretch the lane to exactly the world width, so it wraps seamlessly.
            val stretch = world / x
            for ((pick, size, at) in picks) {
                out += Sprite(
                    symbol = pick, x = -margin + at * stretch, y = laneY + (rnd.nextFloat() - 0.5f) * rowH * 0.5f,
                    laneY = laneY, laneSpeed = speed, size = size,
                    // Any angle, upside down included (user: they never were, it read too tidy).
                    deg = rnd.nextFloat() * 360f, mirror = rnd.nextBoolean(), phase = rnd.nextFloat() * 6.283f
                )
            }
            y += rowH
        }
        sprites = out
    }

    fun step(dt: Float) {
        if (dt <= 0f || sprites.isEmpty()) return
        val world = w + 2 * margin
        for (p in sprites) {
            // Ease back to the lane's speed and height (springy, damped).
            p.vx += (p.laneSpeed - p.vx) * 1.6f * dt
            p.vy += ((p.laneY - p.y) * 3.5f - p.vy * 2.4f) * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            if (p.x < -margin) p.x += world else if (p.x >= w + margin) p.x -= world
        }
        // Bounce: push touching pairs apart and swap the part of their velocity along the hit.
        val list = sprites
        for (i in list.indices) {
            val a = list[i]
            for (j in i + 1 until list.size) {
                val b = list[j]
                val dx = b.x - a.x
                val min = a.r + b.r
                if (dx > min || dx < -min) continue
                val dy = b.y - a.y
                if (dy > min || dy < -min) continue
                val d2 = dx * dx + dy * dy
                if (d2 >= min * min || d2 < 0.0001f) continue
                val d = kotlin.math.sqrt(d2)
                val nx = dx / d; val ny = dy / d
                val push = (min - d) / 2
                a.x -= nx * push; a.y -= ny * push
                b.x += nx * push; b.y += ny * push
                val vn = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny
                if (vn < 0f) {
                    val k = -(1f + 0.7f) * vn / 2
                    a.vx -= k * nx; a.vy -= k * ny
                    b.vx += k * nx; b.vy += k * ny
                }
            }
        }
    }
}

private val LANE_HEIGHT = 74.dp
private val SYMBOL_SIZE = 58.dp

/** Below this the wall isn't drawn, so its drift isn't stepped either. */
private const val INVISIBLE = 0.001f
/** The drift runs at about 30 fps: symbols move 6-20 dp/s, so that's under 1 dp a step. */
private const val STEP_NS = 33_000_000L

/**
 * Each symbol drawn ONCE into a small image per size step, instead of re-stroking every symbol
 * (and rebuilding its paths) on every frame: ~130 symbols on the headset panel made the wall the
 * boot screen's biggest per-frame cost, on the main thread of a Quest whose CPU VRChat mostly owns.
 */
private class SymbolImages(private val ink: Color, private val width: Float, private val density: Float) {
    private val images = HashMap<String, ImageBitmap>()
    /** Sizes are snapped to steps of this many px, so a handful of images serve every sprite. */
    private val step = 12f * density

    /** The image for [p] and the symbol size it was drawn at (the image is padded around it). */
    fun of(p: Sprite): Pair<ImageBitmap, Float> {
        val base = (kotlin.math.round(p.size / step) * step).coerceAtLeast(step)
        val img = images.getOrPut(p.symbol.name + "#" + base.toInt()) {
            val side = kotlin.math.ceil(base * 1.3f).toInt()
            val pad = (side - base) / 2f
            ImageBitmap(side, side).also { bmp ->
                CanvasDrawScope().draw(
                    Density(density), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bmp),
                    Size(side.toFloat(), side.toFloat())
                ) { translate(pad, pad) { SymbolPen(this, base, ink, width).apply(p.symbol.draw) } }
            }
        }
        return img to base
    }
}

/**
 * The back wall: lanes of symbols drifting sideways like traffic, neighbouring lanes at their own
 * speeds and directions; symbols that meet bounce off each other, then settle back into their lane.
 * Shuffled every open and built live from [BootSymbols.all] (nothing stored). [alpha] fades it
 * (applied to the ink, not a layer: this layer draws past its bounds into a resized panel's
 * margins, and a see-through layer would clip that).
 */
@Composable
fun BootBackdropLayer(style: BootBackdrop, alpha: () -> Float = { 1f }, modifier: Modifier = Modifier.fillMaxSize()) {
    if (style == BootBackdrop.None) return
    val seed = remember { Random.nextInt() }
    val wall = remember { Wall(seed) }
    var frame by remember { mutableLongStateOf(0L) }
    // Built on first draw (it needs the density); a plain holder, not state (drawing writes it).
    val images = remember { arrayOfNulls<SymbolImages>(1) }
    val alphaNow by androidx.compose.runtime.rememberUpdatedState(alpha)
    LaunchedEffect(wall) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                // Nothing to do while the wall is invisible (before the intro brings it in, after
                // the fold): no stepping, no redraws.
                if (alphaNow() <= INVISIBLE) { last = 0L; return@withFrameNanos }
                if (last != 0L && now - last < STEP_NS) return@withFrameNanos
                // Capped step: a hitch (or the app pausing) can't fling everything across the wall.
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
                wall.step(dt)
                frame = now
            }
        }
    }
    Box(
        modifier.drawBehind {
            frame // redraw on each drift step
            val a = alpha()
            if (a <= INVISIBLE) return@drawBehind
            wall.ensure(size.width, size.height, density)
            val cache = images[0] ?: SymbolImages(GremlinColors.Edge.copy(alpha = 0.6f), 1.6.dp.toPx(), density).also { images[0] = it }
            val bob = 3.dp.toPx()
            val t = frame / 1e9f
            for (p in wall.sprites) {
                val s = p.size
                if (p.x < -s || p.x > size.width + s) continue
                val (img, base) = cache.of(p)
                val k = s / base
                // Each symbol's own slow sway and bob on top of the physics.
                val sway = 8f * sin(0.9f * t + p.phase)
                val lift = bob * sin(0.7f * t + p.phase * 1.7f)
                translate(p.x, p.y + lift) {
                    rotate(p.deg + sway, pivot = Offset.Zero) {
                        scale(if (p.mirror) -k else k, k, pivot = Offset.Zero) {
                            drawImage(img, topLeft = Offset(-img.width / 2f, -img.height / 2f), alpha = a)
                        }
                    }
                }
            }
        }
    )
}
