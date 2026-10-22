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

    /** Niko: a cat-eared girl shouting into a megaphone (fan drawing, traced by tools/symbol-lab). */
    val Niko = BootSymbol.fromSvg("niko", "M38.9 61.8L34 61.9M60.6 65.3C59.8 66 59.2 66.8 58.4 67.3C57.7 67.7 56.9 67.8 56.2 68M31.3 75.9L31.3 75.9M66.5 40.6C66 40 65.2 39.5 65.1 38.7C64.9 37 65.3 35.3 65.4 33.6M66.5 40.6C65.9 41.4 64.9 42 64.6 42.9C64.2 44.1 64.4 45.5 64.3 46.8M57.5 42.5L51.6 40.7M28 66.8L30.7 65.1M71.1 42.2C71.8 42.2 72.6 42.4 73.3 42.3C74.5 42.1 75.7 41.7 77 41.3M48.3 33.6L55.8 32.6M71.1 42.2C70.4 43.1 69.8 44.2 68.9 44.8C67.5 45.8 65.9 46.1 64.3 46.8M72.8 32.7L75.3 31.8M28 66.8L26.9 68.1C26.1 67.7 25.3 67.4 24.6 66.9C23.5 66.1 22.6 65.1 21.7 64.2M53.9 19.1C55 20.2 56.2 21.1 57.2 22.3C58 23.1 58.4 24.2 59 25.1M14.9 54.5L12.8 60.2L14.3 61.5M64.1 61.6C63.2 61.4 62.3 60.9 61.3 60.9C60.4 60.9 59.6 61.3 58.7 61.5M27.7 39.2C30.4 38.3 33 37.2 35.8 36.5C36.7 36.3 37.7 36.4 38.7 36.4M50.9 60.2C47.9 60.7 44.9 61.5 41.8 61.9C40.9 62 39.9 61.8 38.9 61.8M35.3 54.7L34 61.9M64.3 46.8C63.4 47.7 62.6 48.8 61.5 49.5C60.5 50.2 59.3 50.5 58.2 51M18 68.4C17.7 67.1 16.9 65.8 16.9 64.5C16.9 63.3 17.7 62.1 18.1 61M50.9 60.2L53.1 61.7M88 55.3C89 56.1 89.9 57 91 57.7C92.4 58.6 93.9 59.2 95.3 60M28.1 90.3C26.5 90.2 24.8 90.4 23.2 90.2C21.8 90 20.5 89.4 19.1 89M51.6 40.7C50.9 39.7 50.2 38.8 49.7 37.8C49.1 36.5 48.8 35 48.3 33.6M58.2 51L57.5 42.5M31.3 75.9C28.4 78.6 25.4 81.1 22.7 83.9C21.3 85.4 20.3 87.3 19.1 89M55.8 32.6L63.9 31.5C64.2 31.8 64.7 32 64.9 32.3C65.2 32.7 65.3 33.2 65.4 33.6M18 68.4C18.8 67.7 19.7 67.2 20.3 66.4C20.9 65.7 21.2 64.9 21.7 64.2M15.4 44.9L16.7 52M29.6 84.1L28.1 90.3M34.3 69.1C33.5 70.2 32.5 71.1 31.9 72.4C31.4 73.5 31.5 74.8 31.3 75.9M64.1 61.6C65.7 61.5 67.2 61.2 68.8 61.2C70.8 61.3 72.7 61.8 74.6 62.1M30.7 65.1L34.3 69.1M10 39.3C9.6 38.1 8.9 36.9 8.7 35.6C8.5 34.3 8.6 32.8 8.8 31.4C9.2 28.5 9.8 25.6 10.6 22.8C11.3 20.5 12.4 18.3 13.3 16.1C15 17.5 16.6 19.1 18.5 20.3C20.1 21.3 21.9 21.9 23.6 22.7M10 39.3C9.2 40.2 8.2 41 7.5 42C6.7 43.3 6.2 44.7 5.5 46.1M34 61.9L30.7 65.1M24.5 40.6L24.9 47.3M56.2 68C55.7 68.7 55.5 69.5 54.9 70C53.8 70.9 52.5 71.4 51.3 72.1M17.7 33.7C18.6 33.4 19.6 33.3 20.5 33C21.6 32.5 22.7 31.7 23.8 31.1M10 39.3C11.7 38.4 13.5 37.6 15.1 36.5C16.2 35.7 16.9 34.6 17.7 33.7M71.1 42.2L66.5 40.6M31.3 75.9L29.6 84.1M38.7 36.4C39.6 35.9 40.4 35.3 41.4 35C43.7 34.4 46 34.1 48.3 33.6M56.2 68C55.9 67.1 55.9 66.1 55.5 65.2C54.8 64 53.9 62.9 53.1 61.7M58.1 83.1C61.7 80.8 65.5 78.6 69 76.1C71.5 74.3 73.8 72.4 76 70.3C76.4 69.9 76.6 69.4 76.7 68.9C76.9 68.4 76.9 67.8 76.8 67.3C76.2 65.5 75.3 63.8 74.6 62.1M58.1 83.1L59.9 87C59.5 87.4 59.1 87.9 58.6 88.2C57.7 88.8 56.6 89.3 55.5 89.6C52.3 90.6 49.1 91.6 45.8 92C41.6 92.4 37.3 92.1 33 92C31.9 91.9 30.8 91.7 29.8 91.3C29.2 91.1 28.7 90.6 28.1 90.3M72.8 32.7C71.1 32.7 69.4 32.5 67.8 32.7C67 32.9 66.2 33.3 65.4 33.6M77 41.3C77.8 42.4 79 43.3 79.6 44.5C80.4 46.2 80.9 48.1 81.2 49.9C81.6 52.1 81.7 54.4 81.6 56.6C81.5 58.4 80.8 60.2 80.4 62L74.6 62.1M55.3 81.2C54.4 79.5 53.3 77.8 52.5 76C51.9 74.8 51.7 73.4 51.3 72.1M37 40.4L38.7 36.4M21.7 64.2C21.2 63.4 20.8 62.5 20.2 61.8C19.6 61.3 18.8 61.3 18.1 61M35.3 54.7C36.9 54.5 38.4 54.3 40 54.3C40.5 54.3 40.9 54.5 41.3 54.7M72.8 32.7C72.9 33.3 73.1 33.9 73.1 34.5C73 35.1 72.8 35.8 72.4 36.2C70.6 37.9 68.5 39.2 66.5 40.6M14.9 54.5C15.5 54.7 16.1 54.8 16.6 55.1C17 55.4 17.3 55.8 17.5 56.2C17.9 57.1 18.3 58.1 18.4 59C18.5 59.7 18.2 60.3 18.1 61M45.1 40.1L46 44.2M55.3 81.2L58.1 83.1M15.4 44.9L9.8 46.7M24.5 40.6L15.7 43.1M68.7 26.1C68.7 24.6 68.9 23 68.7 21.4C68.5 19.6 68 17.8 67.4 16.1C66.8 14.3 66.1 12.6 65.1 11C64.2 9.7 63 8.7 62 7.6M51.6 40.7L51.9 44.5L46 44.2M57.5 42.5L55.8 32.6M41.3 54.7L45.1 53.6M53.9 19.1L62 7.6M53.1 61.7C53.7 61.6 54.2 61.2 54.8 61.2C56.1 61.2 57.4 61.4 58.7 61.5M23.6 22.7C24.3 22.4 25 22.3 25.6 22C27.5 20.9 29 19.4 31 18.5C33.2 17.6 35.6 17.2 38 16.9C40.1 16.7 42.2 16.7 44.3 17C47.5 17.4 50.7 18.4 53.9 19.1M23.6 22.7C22 25.1 20.1 27.4 18.7 30C18.2 31.1 18.1 32.4 17.7 33.7M27.7 39.2L24.5 40.6M86.3 48.1L91.2 48.1M38.9 61.8L41.3 54.7M60.6 65.3C61.3 64.8 62.2 64.4 62.9 63.7C63.5 63.1 63.7 62.3 64.1 61.6M14.9 54.5C13.2 54.8 11.4 55.1 9.6 55.2C8.2 55.3 6.8 55.1 5.4 55C5.9 53.8 6.2 52.5 6.8 51.4C7.7 49.7 8.8 48.2 9.8 46.7M50.9 60.2C51.4 59.3 51.8 58.3 52.4 57.6C54.2 55.3 56.3 53.2 58.2 51M34 61.9L26.4 59.3M5.5 46.1L9.8 46.7M83.8 43.4L90.1 39.7M37 40.4C34 42.3 31.1 44.4 28 46.1C27 46.7 25.9 46.9 24.9 47.3M24.9 47.3C24.7 48.1 24.5 49 24.5 49.8C24.5 51.4 24.5 52.9 24.8 54.3C25.2 56 25.9 57.6 26.4 59.3M60.6 65.3L58.7 61.5M28 66.8C27.5 65.2 26.9 63.7 26.6 62C26.4 61.1 26.4 60.2 26.4 59.3M68.7 26.1L59 25.1M46 44.2L45.8 47.3C44.5 46.6 43.1 46.2 41.9 45.3C40.1 43.9 38.6 42 37 40.4M68.7 26.1C70.2 27 71.8 27.7 73.1 28.9C74.1 29.7 74.6 30.8 75.3 31.8M51.3 72.1C48 72.8 44.6 73.6 41.2 74.1C38.9 74.4 36.5 74.1 34.1 74.5C33.1 74.7 32.3 75.5 31.3 75.9M48 78.4C45.6 78.4 43.2 78.2 40.9 78.4C39.8 78.4 38.6 78.8 37.5 79L38.8 84.7C41.3 84.3 43.8 84.1 46.3 83.6C47.2 83.5 48 83 48.9 82.8C48.9 81.9 49 81.1 48.8 80.2C48.7 79.6 48.2 79 48 78.4ZM52.2 47.8a0.6 0.6 0 1 0 1.2 0a0.6 0.6 0 1 0 -1.2 0M32.4 50.4a0.7 0.7 0 1 0 1.3 0a0.7 0.7 0 1 0 -1.3 0")

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
            while (x < world - sym) {
                var pick = pool[rnd.nextInt(pool.size)]
                if (pick == last && pool.size > 1) pick = pool[rnd.nextInt(pool.size)] // fewer twins
                last = pick
                val size = sym * (0.78f + rnd.nextFloat() * 0.5f)
                picks += Triple(pick, size, x)
                // Even-ish spacing; now and then a wider gap (rare, so no stretch of wall goes empty).
                x += size * (1.2f + rnd.nextFloat() * 1.1f) * (if (rnd.nextFloat() < 0.08f) 1.5f else 1f)
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

private val LANE_HEIGHT = 86.dp
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
