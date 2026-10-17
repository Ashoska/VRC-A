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

    val all: List<BootSymbol> = listOf(FishBone, Cheese, Gnarpy, Kitty)
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
