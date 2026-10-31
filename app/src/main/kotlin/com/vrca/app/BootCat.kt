package com.vrca.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import com.vrca.ui.theme.GremlinColors
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The boot rows' state mark: a little VRC-A cat whose face says how the check went (user design,
 * 2026-10-22; head = the tester's ear sketch on a round body). While a check runs it THINKS: a light
 * runs round the head, the eyes look around, ponder and follow the light ([BootCatEyes]). Every loop and
 * transition is drawn in Compose with the clock read only while drawing (the user chose this over pre-built
 * AnimatedVectorDrawables, 2026-10-30: smaller and simpler; a long main-thread stall can pause them, which the
 * debug stall recorder would show).
 *
 * [variant] picks the loop's beat order so rows checking at once don't move in lockstep; [hopDelayMs] is
 * when this row's all-done hop starts after [celebrate] turns true (one after another down the list).
 */
@Composable
internal fun BootCat(mark: Mark, variant: Int, modifier: Modifier, celebrate: Boolean = false, hopDelayMs: Long = 0, startFrom: Mark? = null) {
    val accent = MaterialTheme.colorScheme.primary
    val v = Math.floorMod(variant, BootCatEyes.VARIANTS)
    // The mark before this one (what a transition starts from) and how far the transition is.
    // [startFrom]: appear already landing from that mark (the download cat hands over to Done this way).
    var from by remember { mutableStateOf(startFrom) }
    var shown by remember { mutableStateOf(mark) }
    var looping by remember { mutableStateOf(mark == Mark.Working || mark == Mark.Background) }
    // Skipped, Done and Warn idle (BootCatSkip, BootCatDone, BootCatWarn) once their landing has finished.
    var idle by remember { mutableStateOf(idles(mark) && startFrom == null) }
    val t = remember { Animatable(if (startFrom != null) 0f else Float.MAX_VALUE) }
    // When the thinking / background loop started (written by the loop), so a landing can carry its light on
    // from where it was instead of it snapping off: [lightAt] = its lap position at the switch (-1 = none).
    val loopStart = remember { longArrayOf(0L) }
    var lightAt by remember { mutableFloatStateOf(-1f) }
    var calmLight by remember { mutableStateOf(false) }
    // How strong the outline was before the landing (the loops draw a faint one), so it eases up instead of jumping.
    var fromOutline by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(Unit) {
        if (startFrom != null) {
            val dur = transitionMs(startFrom, mark); t.animateTo(dur, tween(dur.toInt(), easing = LinearEasing))
            if (shown == mark) idle = idles(mark)
        }
    }
    LaunchedEffect(mark) {
        if (mark == shown) return@LaunchedEffect
        val dur = transitionMs(shown, mark)
        if (dur <= 0f) {
            // No landing (Working <-> Background, back to sleep): switch straight over. Turning the loop off for a frame
            // here drew one frame of the result landing (a small solid cat) before the Background loop.
            from = shown; shown = mark
            looping = mark == Mark.Working || mark == Mark.Background
            idle = idles(mark)
            return@LaunchedEffect
        }
        if (looping) {
            val el = (withFrameMillis { it } - loopStart[0]).toFloat()
            calmLight = shown == Mark.Background
            lightAt = if (calmLight) (el / 2400f) % 1f else (el / 1200f + BootCatEyes.LIGHT_PHASE[v]) % 1f
            fromOutline = if (calmLight) 0.18f else 0.25f
        } else { lightAt = -1f; fromOutline = 1f }
        from = shown; shown = mark
        looping = false; idle = false
        t.snapTo(0f)
        t.animateTo(dur, tween(dur.toInt(), easing = LinearEasing))
        looping = mark == Mark.Working || mark == Mark.Background
        idle = idles(mark)
    }
    val hop = remember { Animatable(-1f) }
    LaunchedEffect(celebrate) {
        if (!celebrate) { hop.snapTo(-1f); return@LaunchedEffect }
        delay(hopDelayMs)
        hop.snapTo(0f); hop.animateTo(HOP_MS, tween(HOP_MS.toInt(), easing = LinearEasing))
    }
    Box(modifier) {
        if (looping) {
            Crossfade(shown == Mark.Background, label = "catLoop") { calm ->
                if (calm) BgLoop(v, accent, Modifier.fillMaxSize(), loopStart) else ThinkLoop(v, accent, Modifier.fillMaxSize(), loopStart)
            }
        } else if (shown == Mark.Waiting) {
            // Asleep, waiting for its turn: breathing, nodding off, dreaming (BootCatSleep).
            SleepLoop(v, { markColor(Mark.Waiting, accent) }, Modifier.fillMaxSize())
        } else if (idle && shown == Mark.Skipped) {
            // Skipped, awake and unbothered: yawning, a lazy look, humming (BootCatSkip).
            SkipLoop(v, { markColor(Mark.Skipped, accent) }, Modifier.fillMaxSize())
        } else {
            // The all-done hop rides on top of whichever is showing (the landing or the done idle).
            val hopLayer = Modifier.fillMaxSize().graphicsLayer {
                val h = hop.value
                if (h >= 0f) {
                    val u = h / (HOP_MS * 0.74f)
                    if (u < 1f) { translationY = -2.6f * sin(PI.toFloat() * u) * size.height / 24f; scaleX = 1f + 0.06f * sin(PI.toFloat() * u); scaleY = scaleX }
                    else if (u < 1.35f) { scaleX = 1f - 0.08f * sin(PI.toFloat() * (u - 1f) / 0.35f); scaleY = scaleX }
                }
            }
            if (idle && shown == Mark.Done) {
                // Done, content: a purr wiggle, a content tilt, a small hop (BootCatDone).
                DoneLoop(v, { markColor(Mark.Done, accent) }, hopLayer)
            } else if (idle && shown == Mark.Warn) {
                // Warn, uneasy: a nervous glance, a fidget, an uneasy sigh (BootCatWarn).
                WarnLoop(v, { markColor(Mark.Warn, accent) }, Modifier.fillMaxSize())
            } else if (idle && shown == Mark.Maintenance) {
                // Maintenance, busy fixing: hammering, inspecting, puzzled then aha (BootCatMaint).
                MaintLoop(v, { markColor(Mark.Maintenance, accent) }, Modifier.fillMaxSize())
            } else if (idle && shown == Mark.Failed) {
                // Failed, knocked out: dizzy, a sniffle, peeking while playing dead (BootCatFail).
                FailLoop(v, { markColor(Mark.Failed, accent) }, Modifier.fillMaxSize())
            } else {
                val head = remember { headPath() }
                Canvas(hopLayer) { drawCat(head, from, shown, t.value, accent, lightAt, calmLight, fromOutline) }
            }
        }
    }
}

/** The thinking cat ([BootCatEyes]), looping from when it appears. */
@Composable
private fun ThinkLoop(v: Int, accent: Color, modifier: Modifier, loopStart: LongArray) {
    val head = remember { headPath() }
    val eyes = remember { FloatArray(10) }
    val ms = remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { val start = androidx.compose.runtime.withFrameMillis { it }; loopStart[0] = start; while (true) androidx.compose.runtime.withFrameMillis { ms.value = it - start } }
    Canvas(modifier) {
        val t = ms.value.toFloat()
        val s = size.minDimension / 24f
        withTransform({ scale(s, s, Offset.Zero) }) {
            val stroke = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(head, accent.copy(alpha = 0.25f), style = stroke)
            val grow = (t / 400f).coerceIn(0f, 1f) // the light grows in, linear
            drawLight(head, accent.copy(alpha = grow), ((t / 1200f) + BootCatEyes.LIGHT_PHASE[v]) % 1f, 0.28f * grow)
            BootCatEyes.pose(v, t, eyes)
            with(BootCatEyes) { drawEyes(eyes, accent) }
        }
    }
}

/** The background cat ([BootCatBg]), looping from when it appears. */
@Composable
private fun BgLoop(v: Int, accent: Color, modifier: Modifier, loopStart: LongArray) {
    val head = remember { headPath() }
    val ms = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { val t0 = androidx.compose.runtime.withFrameMillis { it }; loopStart[0] = t0; while (true) androidx.compose.runtime.withFrameMillis { ms.longValue = it - t0 } }
    Canvas(modifier) {
        val s = size.minDimension / 24f
        withTransform({ scale(s, s, Offset.Zero) }) {
            with(BootCatBg) { drawBg(head, { at -> drawLight(head, accent.copy(alpha = 0.5f), at, 0.28f) }, accent, v, ms.longValue.toFloat()) }
        }
    }
}

/**
 * Boot Cat downloading (user, 2026-10-23): his outline is the progress bar, filling clockwise from
 * the top of his head ([progress] 0..1, eased toward each new value), and his eyes are two little
 * v's playing their own beats (nod, watching the bar, excited; [BootCatDl]). [progress] null = size unknown:
 * the thinking light runs round instead. Drawn in Compose, every animation read while drawing.
 */
@Composable
internal fun BootCatDownload(progress: Float?, modifier: Modifier, color: Color = MaterialTheme.colorScheme.primary, done: Boolean = false) {
    // Finished: he lands into the Done face, the same blink-and-pop the boot rows land with.
    if (done) { BootCat(Mark.Done, 0, modifier, startFrom = Mark.Working); return }
    val head = remember { headPath() }
    // Where the fill starts: the top middle of the head (its prefix up to there, measured once).
    val start = remember {
        androidx.compose.ui.graphics.PathMeasure().apply {
            setPath(PathParser().parsePathString(HEAD_TO_TOP).toPath(), false)
        }.length
    }
    val shown = androidx.compose.animation.core.animateFloatAsState(
        (progress ?: 0f).coerceIn(0f, 1f),
        // A spring follows a steadily rising value smoothly (a tween restarting every update lagged behind).
        androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow), label = "catDownload"
    )
    val ms = remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { val t0 = androidx.compose.runtime.withFrameMillis { it }; while (true) androidx.compose.runtime.withFrameMillis { ms.value = it - t0 } }
    Canvas(modifier) {
        val t = ms.value.toFloat()
        val s = size.minDimension / 24f
        val m = androidx.compose.ui.graphics.PathMeasure().apply { setPath(head, false) }
        val total = m.length
        // Where the eyes look in their "watching the bar" beat: the bar's growing end, or the running light's.
        val tipAt = if (progress == null) ((t / 1200f) % 1f + 0.28f) % 1f else ((start + shown.value * total) / total) % 1f
        withTransform({ scale(s, s, Offset.Zero) }) {
            with(BootCatDl) {
                drawDownloadCat(color, t, m, tipAt) {
                    drawPath(head, color.copy(alpha = 0.25f), style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    if (progress == null) {
                        drawLight(head, color, (t / 1200f) % 1f, 0.28f)
                    } else if (shown.value > 0.001f) {
                        val a = start; val b = start + shown.value * total
                        val seg = Path()
                        m.getSegment(a, min(b, total), seg, true)
                        if (b > total) m.getSegment(0f, b - total, seg, true)
                        drawPath(seg, color, style = Stroke(2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        // A glint runs along the filled part every 1.4 s, so a slow or paused download never looks frozen.
                        val fill = b - a
                        if (fill > total * 0.04f) {
                            val gl = min(total * 0.1f, fill)
                            val g0 = a + ((t / 1400f) % 1f) * (fill + gl) - gl
                            val glint = Path()
                            val from = max(g0, a); val to = min(g0 + gl, b)
                            if (to > from) {
                                if (from < total) m.getSegment(from, min(to, total), glint, true)
                                if (to > total) m.getSegment(max(from, total) - total, to - total, glint, true)
                                drawPath(glint, androidx.compose.ui.graphics.lerp(color, GremlinColors.Text, 0.6f), style = Stroke(2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Boot Cat counting down (the boot's Retry button, the terms' Accept wait; user, 2026-10-24: like the
 * download cat, instead of a plain timer circle): his head outline is the time left, draining back to the
 * top middle as the seconds pass ([left], 0..1, eased between whole seconds). His eyes play their own beats
 * (checking the clock, tapping along, a deep breath; [BootCatCd]) on their own clock, whatever the countdown's
 * length. Everything is read while drawing.
 */
@Composable
internal fun BootCatCountdown(left: Float, modifier: Modifier, color: Color = MaterialTheme.colorScheme.primary, smooth: Boolean = true) {
    val head = remember { headPath() }
    val start = remember {
        androidx.compose.ui.graphics.PathMeasure().apply { setPath(PathParser().parsePathString(HEAD_TO_TOP).toPath(), false) }.length
    }
    // A second's worth of draining takes a second, so the outline shrinks steadily instead of in steps.
    // [smooth] = false when [left] is already a smooth value (e.g. the search cooldown): easing it again would
    // trail it by up to a second, so the outline would still show time left when the wait is over.
    val eased = androidx.compose.animation.core.animateFloatAsState(
        left.coerceIn(0f, 1f), tween(if (smooth) 1000 else 0, easing = LinearEasing), label = "catCountdown"
    )
    val shown = if (smooth) eased else androidx.compose.runtime.rememberUpdatedState(left.coerceIn(0f, 1f))
    val ms = remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { val t0 = androidx.compose.runtime.withFrameMillis { it }; while (true) androidx.compose.runtime.withFrameMillis { ms.value = it - t0 } }
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val m = androidx.compose.ui.graphics.PathMeasure().apply { setPath(head, false) }
        val total = m.length
        val len = shown.value * total
        // Where the time is running out (the outline's end), where the eyes glance in their "checking the clock" beat.
        val endAt = ((start + len) / total) % 1f
        withTransform({ scale(s, s, Offset.Zero) }) {
            with(BootCatCd) {
                drawCountdownCat(color, ms.value.toFloat(), m, endAt) {
                    drawPath(head, color.copy(alpha = 0.25f), style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    if (len > 0.01f) {
                        // From the top middle clockwise, as long as the time left (its end runs back toward the top).
                        val seg = Path()
                        val b = start + len
                        m.getSegment(start, min(b, total), seg, true)
                        if (b > total) m.getSegment(0f, b - total, seg, true)
                        drawPath(seg, color, style = Stroke(2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }
        }
    }
}

/** The head path from its start to the top middle of the forehead (half the forehead curve). */
private const val HEAD_TO_TOP = "M3.92,10.46 L4.3,3 L8.2,7.1 Q10.1,6.1 12,6.1"

/** The running light: a [len] slice of the head outline starting [at] (0..1) along it. */
private fun DrawScope.drawLight(head: Path, c: Color, at: Float, len: Float) {
    if (len <= 0.001f) return
    val m = androidx.compose.ui.graphics.PathMeasure().apply { setPath(head, false) }
    val total = m.length
    val seg = Path()
    val a = at * total; val b = a + len * total
    m.getSegment(a, min(b, total), seg, true)
    if (b > total) m.getSegment(0f, b - total, seg, true)
    drawPath(seg, c, style = Stroke(2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}


private const val HEAD = "M3.92,10.46 L4.3,3 L8.2,7.1 Q12,5.1 15.8,7.1 L19.9,2.6 L20.08,10.46 A8.6,8.6 0 1,1 3.92,10.46 Z"
private const val Y0 = 13.6f
private const val FRAME = 1000f / 30f
private const val HOP_MS = 540f

private fun headPath(): Path = PathParser().parsePathString(HEAD).toPath()

/** Marks that idle with their own loop once their landing has finished. */
private fun idles(m: Mark) = m == Mark.Skipped || m == Mark.Done || m == Mark.Warn || m == Mark.Maintenance || m == Mark.Failed

/** The skipped landing's face paths (drawing is single-threaded, so one reused set is enough). */
private val skipPaths = BootCatSkip.Paths()

private fun transitionMs(from: Mark?, to: Mark): Float = when {
    to == Mark.Working && from == Mark.Failed -> 24 * FRAME
    to == Mark.Working || to == Mark.Background -> if (from == Mark.Waiting) 14 * FRAME else 0f
    to == Mark.Waiting -> 0f
    else -> 30 * FRAME
}

private fun sm(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
private fun easeOut(x: Float): Float = 1f - (1f - x.coerceIn(0f, 1f)).pow(3)

/** The colour a mark settles on. Muted tones come from mixing the theme's own layers. */
private fun markColor(m: Mark?, accent: Color): Color = when (m) {
    Mark.Done -> GremlinColors.Positive
    Mark.Warn, Mark.Maintenance -> GremlinColors.Caution
    Mark.Failed -> GremlinColors.Negative
    Mark.Skipped -> lerp(GremlinColors.Edge, GremlinColors.TextMuted, 0.45f)
    Mark.Working, Mark.Background -> accent
    Mark.Waiting, null -> lerp(GremlinColors.Edge, GremlinColors.TextMuted, 0.08f)
}

private enum class Face { Look, Line, Sleep, Determined, Happy, Worry, Fix, Dead }

private fun resultFace(m: Mark): Face = when (m) {
    Mark.Done -> Face.Happy
    Mark.Warn -> Face.Worry
    Mark.Maintenance -> Face.Fix
    Mark.Failed -> Face.Dead
    Mark.Skipped, Mark.Waiting -> Face.Sleep
    Mark.Working, Mark.Background -> Face.Look
}

/**
 * Draws the cat for [to] at [ms] into the transition from [from] (all in the 24-unit grid). [lightAt] >= 0: the loop's
 * running light was there at the switch ([calm]: the background one); it carries on round and fades out over 9 frames
 * instead of snapping off. [fromOutline]: the outline's strength before (the loops draw a faint one); it eases up.
 */
private fun DrawScope.drawCat(head: Path, from: Mark?, to: Mark, ms: Float, accent: Color, lightAt: Float = -1f, calm: Boolean = false, fromOutline: Float = 1f) {
    val f = ms / FRAME
    val s = size.minDimension / 24f
    val lightFade = if (lightAt >= 0f) 1f - sm(f / 9f) else 0f
    fun DrawScope.fadingLight(c: Color) {
        if (lightFade <= 0f) return
        val at = (lightAt + ms / (if (calm) 2400f else 1200f)) % 1f
        drawLight(head, c.copy(alpha = c.alpha * (if (calm) 0.5f else 1f) * lightFade), at, 0.28f * (0.4f + 0.6f * lightFade))
    }
    // Outline and the soft fill ease in from the loop's faint outline (no fill) over 6 frames.
    val outIn = fromOutline + (1f - fromOutline) * sm(f / 6f)
    withTransform({ scale(s, s, Offset.Zero) }) {
        when {
            // Asleep, waiting for its turn.
            to == Mark.Waiting -> cat(head, markColor(Mark.Waiting, accent), outline = 1f) { face(Face.Sleep, it) }
            // Waking up: lids lift, colour warms to the accent, a tiny lift. The light comes after (the loop grows it in).
            (to == Mark.Working || to == Mark.Background) && from != Mark.Failed -> {
                val u = sm(f / 14f)
                val c = lerp(markColor(Mark.Waiting, accent), accent, u)
                withTransform({ translate(0f, -0.5f * sin(PI.toFloat() * u)) }) {
                    cat(head, c, outline = 1f - 0.75f * u) {
                        if (u < 0.5f) face(Face.Line, it)
                        else squash(0.25f + 0.75f * sm((u - 0.5f) / 0.5f)) { face(Face.Look, it) }
                    }
                }
            }
            // Retry: shake off the x x eyes, a determined squint, then thinking again.
            to == Mark.Working -> {
                val neg = markColor(Mark.Failed, accent)
                // Quicker than it was (38 frames, user: too long next to the others): shake 8, squint 10, open 6.
                when {
                    f < 8 -> {
                        val u = f / 8f
                        val c = lerp(neg, accent, sm(u))
                        withTransform({ translate(1.2f * sin(u * PI.toFloat() * 3) * (1 - u), 0f) }) {
                            cat(head, c, outline = 1f - 0.75f * sm(u), fill = 0.16f * (1 - u)) { squash(1f - 0.8f * sm(u)) { face(Face.Dead, it) } }
                        }
                    }
                    f < 18 -> cat(head, accent, outline = 0.25f) { squash(min(1f, 0.2f + sm((f - 8f) / 4f))) { face(Face.Determined, it) } }
                    else -> cat(head, accent, outline = 0.25f) { squash(0.3f + 0.7f * sm((f - 18f) / 6f)) { face(Face.Look, it) } }
                }
            }
            // Settling into skipped: no pop, a blink into the unbothered resting face with a slow nod (the transitions pass will rework this).
            to == Mark.Skipped -> {
                val c = lerp(markColor(from, accent), markColor(Mark.Skipped, accent), sm(f / 6f))
                val u = min(1f, f / 26f)
                withTransform({
                    translate(0f, 0.9f * sin(PI.toFloat() * u))
                    rotate(-5f * sin(PI.toFloat() * u), Offset(12f, 13f))
                }) {
                    fadingLight(c)
                    cat(head, c, outline = outIn, fill = 0.16f * sm(f / 6f)) {
                        // It opens its eyes into the skipped cat's resting face, where its idle loop (BootCatSkip) carries on.
                        squash(blink(f)) { if (f < 5) face(Face.Look, it) else with(BootCatSkip) { face(skipPaths, it) } }
                    }
                }
            }
            // Landing on a result: blink into the new face, pop, a tint flash, and the face's own reaction.
            else -> {
                val c = lerp(markColor(from, accent), markColor(to, accent), sm(f / 6f))
                // The pop starts from full size: a small squash as the eyes shut, a spring past full, a settle. It used to
                // start at 0.7 on frame one, which read as a one-frame flicker (user, 2026-10-30).
                var sc = when {
                    f < 4 -> 1f - 0.12f * sm(f / 4f)
                    f < 9 -> 0.88f + 0.26f * easeOut((f - 4f) / 5f)
                    f < 14 -> 1.14f - 0.14f * easeOut((f - 9f) / 5f)
                    else -> 1f
                }
                var rot = 0f; var tx = 0f
                when (to) {
                    Mark.Done -> if (f in 14f..24f) sc = 1f + 0.07f * sin((f - 14f) / 10f * PI.toFloat() * 2) * (1 - (f - 14f) / 10f)
                    Mark.Warn -> if (f in 6f..24f) rot = -14f * sin((f - 6f) / 18f * PI.toFloat())
                    Mark.Maintenance -> if (f in 6f..26f) rot = 10f * sin((f - 6f) / 20f * PI.toFloat() * 3) * (1 - (f - 6f) / 20f)
                    Mark.Failed -> if (f in 6f..22f) tx = 1.6f * sin((f - 6f) / 16f * PI.toFloat() * 4) * (1 - (f - 6f) / 16f)
                    else -> {}
                }
                // The tint flash rises over 3 frames, then fades (it used to start at full on frame one).
                val flash = if (f < 3) 0.45f * sm(f / 3f) else if (f < 14) 0.45f * (1 - (f - 3f) / 11f) else 0f
                withTransform({
                    translate(tx, 0f)
                    rotate(rot, Offset(12f, 13f))
                    scale(sc, sc, Offset(12f, 13f))
                }) {
                    fadingLight(c)
                    cat(head, c, outline = outIn, fill = 0.16f * sm(f / 6f) + flash) { squash(blink(f)) { face(if (f < 5) Face.Look else resultFace(to), it) } }
                }
            }
        }
    }
}

/** Eyes shut over 5 frames, open into the new face over the next 6. */
private fun blink(f: Float): Float = if (f < 5) 1f - 0.85f * sm(f / 5f) else if (f < 11) 0.15f + 0.85f * sm((f - 5f) / 6f) else 1f

private inline fun DrawScope.cat(head: Path, c: Color, outline: Float, fill: Float = 0f, face: DrawScope.(Color) -> Unit) {
    if (fill > 0f) drawPath(head, c.copy(alpha = c.alpha * fill.coerceAtMost(1f)))
    drawPath(head, c.copy(alpha = c.alpha * outline), style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    face(c)
}

private inline fun DrawScope.squash(v: Float, block: DrawScope.() -> Unit) =
    withTransform({ scale(1f, v, Offset(0f, Y0)) }, block)

private fun DrawScope.face(kind: Face, c: Color, dx: Float = 0f) {
    val y = Y0; val l = 9.3f + dx; val r = 14.7f + dx
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, w: Float = 1.9f) = drawLine(c, Offset(x1, y1), Offset(x2, y2), w, StrokeCap.Round)
    fun quad(x1: Float, y1: Float, cx: Float, cy: Float, x2: Float, y2: Float, w: Float) =
        drawPath(Path().apply { moveTo(x1, y1); quadraticTo(cx, cy, x2, y2) }, c, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    fun dot(x: Float) = drawCircle(c, 1.3f, Offset(x, y))
    when (kind) {
        Face.Look -> { dot(l); dot(r) }
        Face.Line -> { line(l - 1.2f, y, l + 1.2f, y); line(r - 1.2f, y, r + 1.2f, y) }
        Face.Sleep -> { line(8.1f, y, 10.5f, y); line(13.5f, y, 15.9f, y); line(11f, y + 3.6f, 13f, y + 3.6f, 1.8f) }
        Face.Determined -> { line(l - 1.3f, y - 0.6f, l + 1.2f, y + 0.3f); line(r + 1.3f, y - 0.6f, r - 1.2f, y + 0.3f) }
        Face.Happy -> {
            quad(7.9f, y, 9.15f, y - 1.6f, 10.4f, y, 1.9f); quad(13.6f, y, 14.85f, y - 1.6f, 16.1f, y, 1.9f)
            drawPath(Path().apply { moveTo(10.2f, y + 3f); quadraticTo(11.1f, y + 4.2f, 12f, y + 3f); quadraticTo(12.9f, y + 4.2f, 13.8f, y + 3f) },
                c, style = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Face.Worry -> { dot(9.3f); dot(14.7f); quad(10.2f, y + 4.2f, 12f, y + 2.9f, 13.8f, y + 4.2f, 1.8f) }
        Face.Fix -> { line(8.1f, y, 10.5f, y); dot(14.7f); line(10.3f, y + 3.8f, 13.7f, y + 3.8f, 1.8f) }
        Face.Dead -> {
            for (x in floatArrayOf(8.2f, 13.6f)) { line(x, y - 1.1f, x + 2.2f, y + 1.1f, 1.8f); line(x + 2.2f, y - 1.1f, x, y + 1.1f, 1.8f) }
            quad(10.3f, y + 4.2f, 12f, y + 3.1f, 13.7f, y + 4.2f, 1.8f)
        }
    }
}

