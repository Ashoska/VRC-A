package com.vrca.uilab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.vrca.app.VrcaApplication
import com.vrca.ui.viewmodel.VrcaViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.time.Duration
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Drives the real app like a user and captures what's on screen, dialogs and menus included.
 * Commands (one per line, `;` also separates):
 *
 *   shot <name>                 PNG of everything on screen (dialogs/menus drawn over, dimmed)
 *   tap <text> [#n]             click the n-th element whose text / description matches (runs its
 *                               action: reaches it even if something covers it; `touch` doesn't)
 *   long <text> [#n]            long-press (the same way)
 *   touch <where> [hold MS]     a REAL tap: pointer events to the window under the point, Compose
 *                               hit-tests it; says what it landed on ("(!)" when something else
 *                               covers the element, or nothing there takes a touch)
 *   press <where> [hold MS]     a real long press (700 ms)
 *   drag <where> to|-> <where>  real drag, a move every frame; `by dx,dy` (dp) instead of a target;
 *        [hold MS] [over MS] [stay MS]  hold first (long-press drags), move time, rest before lifting
 *   swipe up|down|left|right [on <where>] [dist DP] [over MS]   a quick flick the finger's way
 *   hover <where> | hover off   the headset pointer resting there (hover highlight)
 *   targets [minDp] [name]      tappable elements that are covered or under minDp (48); with a name,
 *                               also <name>.png with them boxed
 *                               <where> = <text> [#n] [@fx,fy] (fx,fy: point in its box, default
 *                               the centre) | slider [#n] [@fx,fy] | x,y (dp on screen)
 *   type <value>                replace the text of the focused (else first) text field
 *   typein <field> | <value>    replace the text of the field whose label/text matches
 *   scroll down|up [n]          scroll the main scrollable area n screens (default 1)
 *   scrollto <text>             scroll until the element is in view
 *   back                        dismiss the top dialog, else system back
 *   wait <ms>                   let the app run (app time, 16 ms frames; never faster than real time)
 *   tree                        list what's on screen and what can be tapped / typed / scrolled
 *   set <prop> <value>          force state: VM field (warned, oscSending, …) or Object.prop
 *   get <prop>                  read it
 *   preset <name>               ready-made states (see UiLabScenes.PRESETS)
 *   root screen|app             what the activity shows: the main screen, or the full app
 *                               (boot, ToS, onboarding and update gates included)
 *   show <name> [args]          a screen/dialog that's normally gated (see UiLabScenes.SHOWS)
 *   hide                        remove what `show` put on top
 *   record start <name> [ms]    capture a frame every [ms] (default 32) of app time from now on
 *                               (every `wait`, and the settle after each action) …
 *   record stop                 … then write <name>-frames.png (a timestamped filmstrip: the
 *                               animation readable as one image) + <name>-frames/ (every frame;
 *                               ui.sh / uictl.sh turn it into <name>.gif at real speed)
 */
class UiLabDriver(
    val act: ComponentActivity,
    val app: VrcaApplication,
    val vm: VrcaViewModel,
    private val outDir: File,
) {
    /** "screen" | "app" | "show:<name> <args>" — the base of the composition. */
    val base: MutableState<String> = mutableStateOf("screen")
    /** A dialog-style `show` drawn over the base, or null. */
    val overlay: MutableState<String?> = mutableStateOf(null)

    private val looper = shadowOf(Looper.getMainLooper())
    private val log = File(outDir, ".lab.log").also { it.parentFile?.mkdirs() }
    /**
     * Lets the app run for [ms] of app time, one 16 ms frame at a time, like a headset's frame loop:
     *  - The app clock is the looper's: animations AND `delay()` (moved onto the main looper by
     *    `kotlinx.coroutines.main.delay` in app/build.gradle) both run on it, so they stay in step
     *    however slow a JVM frame is. With wall-time delays, Manual Send's 120 ms bring-into-view
     *    fired before the slow first expand frame had grown the card, and didn't scroll.
     *  - Layout after every frame. Robolectric never draws on its own, and Compose measures +
     *    lays out inside draw, so without [layoutRoots] the layout only moved when `shot` drew
     *    the screen (scroll extents, bring-into-view and `tree` positions were all stale).
     *  - The sleep keeps app time from running ahead of real time, so network replies still
     *    land within a `wait`.
     */
    fun settle(ms: Long) {
        var left = ms
        while (left > 0) {
            val frameStart = System.nanoTime()
            looper.idleFor(Duration.ofMillis(FRAME_MS))
            layoutRoots()
            clockMs += FRAME_MS
            trackWindows()
            trackToasts()
            rec?.let { r -> r.appMs += FRAME_MS; if (r.appMs - r.lastAt >= r.everyMs) capture(r) }
            left -= FRAME_MS
            val spentMs = (System.nanoTime() - frameStart) / 1_000_000
            if (spentMs < FRAME_MS) Thread.sleep(FRAME_MS - spentMs)
        }
    }

    /** Measure + lay out every Compose window now (what a device does in each frame's draw pass). */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun layoutRoots() {
        val roots = mutableListOf<RootForTest>()
        windows().forEach { composeRoots(it.view, roots) }
        roots.forEach { runCatching { it.measureAndLayoutForTest() } }
    }

    fun run(script: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in script.split('\n', ';')) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val t0 = System.currentTimeMillis()
            val res = try { exec(line) } catch (t: Throwable) { "ERROR $line: ${t.javaClass.simpleName}: ${t.message}" }
            out += res
            log.appendText("${System.currentTimeMillis() - t0} ms  $line  →  ${res.lineSequence().first()}\n")
        }
        return out
    }

    fun exec(line: String): String {
        val cmd = line.substringBefore(' ').lowercase()
        val arg = line.substringAfter(' ', "").trim()
        layoutRoots() // act on what a device would show now, not the last frame's layout
        val r = when (cmd) {
            "shot" -> shot(arg.ifBlank { "shot" })
            "tap", "click" -> act(arg, SemanticsActions.OnClick.name) { n -> n.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() }
            "long" -> act(arg, SemanticsActions.OnLongClick.name) { n -> n.config.getOrNull(SemanticsActions.OnLongClick)?.action?.invoke() }
            "type" -> type(null, arg)
            "typein" -> type(arg.substringBefore('|').trim(), arg.substringAfter('|', "").trim())
            "scroll" -> scroll(arg)
            "scrollto" -> scrollTo(arg)
            "back" -> back()
            "wait" -> { settle(arg.toLongOrNull() ?: 500); "waited" }
            "tree" -> tree()
            "set" -> UiLabScenes.set(this, arg.substringBefore(' '), arg.substringAfter(' ', ""))
            "get" -> UiLabScenes.get(this, arg)
            "preset" -> UiLabScenes.preset(this, arg)
            "root" -> { overlay.value = null; base.value = arg.ifBlank { "screen" }; "root $arg" }
            "show" -> UiLabScenes.show(this, arg)
            "hide" -> { overlay.value = null; if (base.value.startsWith("show:")) base.value = "screen"; "hidden" }
            "record" -> record(arg)
            "touch" -> touch(arg, TAP_HOLD_MS, "touch")
            "press" -> touch(arg, PRESS_HOLD_MS, "press")
            "drag" -> drag(arg)
            "swipe" -> swipe(arg)
            "hover" -> hover(arg)
            "targets" -> targets(arg)
            else -> error("unknown command: $cmd")
        }
        if (cmd !in setOf("wait", "shot", "tree", "get", "record", "targets")) settle(SETTLE_AFTER_ACTION_MS)
        // Toasts that came up meanwhile (Robolectric shows none; the lab draws them, see trackToasts).
        val shown = newToasts.joinToString(" | ") { "toast \"${it.take(80)}\"" }
        newToasts.clear()
        return if (shown.isEmpty()) r.toString() else "$r | $shown"
    }

    // ---------------------------------------------------------------- windows

    private data class Win(val view: View, val params: WindowManager.LayoutParams)

    @Suppress("UNCHECKED_CAST")
    private fun windows(): List<Win> {
        val wmgClass = Class.forName("android.view.WindowManagerGlobal")
        val wmg = wmgClass.getMethod("getInstance").invoke(null)
        fun <T> field(name: String) = wmgClass.getDeclaredField(name).apply { isAccessible = true }.get(wmg) as T
        val views = field<List<View>>("mViews").toList()
        val params = field<List<WindowManager.LayoutParams>>("mParams").toList()
        return views.zip(params).map { Win(it.first, it.second) }
            .filter { it.view.visibility == View.VISIBLE && it.view.width > 0 && it.view.height > 0 }
    }

    fun shot(name: String): String {
        val (bmp, count) = render()
        val f = File(outDir, if (name.endsWith(".png")) name else "$name.png")
        f.parentFile?.mkdirs()
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "shot ${f.absolutePath} (${bmp.width}x${bmp.height}, $count window${if (count == 1) "" else "s"})"
    }

    /** Where [win] sits on screen (px). Robolectric doesn't place windows, so the lab does it like
     *  the window manager, from its gravity + x/y; drawing and aiming touches both use this. */
    private fun placeOf(win: Win): Pair<Int, Int> {
        val main = act.window.decorView
        if (win.view === main) return 0 to 0
        val w = main.width; val h = main.height
        val p = win.params
        val vw = win.view.width; val vh = win.view.height
        val g = p.gravity
        val hg = g and Gravity.HORIZONTAL_GRAVITY_MASK
        val vg = g and Gravity.VERTICAL_GRAVITY_MASK
        val x = when {
            hg == Gravity.LEFT || hg == Gravity.START || (g and Gravity.LEFT) == Gravity.LEFT && hg != Gravity.CENTER_HORIZONTAL -> p.x
            hg == Gravity.RIGHT || hg == Gravity.END -> w - vw - p.x
            else -> (w - vw) / 2 + p.x
        }
        val y = when (vg) {
            Gravity.TOP -> p.y
            Gravity.BOTTOM -> h - vh - p.y
            else -> (h - vh) / 2 + p.y
        }
        return x to y
    }

    /** Everything on screen as one bitmap: the activity, then every other window over it in
     *  z-order at its position (dialogs dimmed behind as on a device), toasts on top. */
    private fun render(): Pair<Bitmap, Int> {
        val wins = windows()
        val main = wins.firstOrNull { it.view === act.window.decorView } ?: error("activity window not attached")
        val w = main.view.width; val h = main.view.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        main.view.draw(c)
        for (win in wins) {
            if (win === main) continue
            val p = win.params
            // A dialog window that just opened is still animating in (see trackWindows).
            val dialog = p.type == WindowManager.LayoutParams.TYPE_APPLICATION
            val age = clockMs - (shownAt[win.view] ?: LONG_AGO)
            val t = if (dialog && age in 0 until WINDOW_ANIM_MS) age.toFloat() / WINDOW_ANIM_MS else 1f
            val k = 1f - (1f - t) * (1f - t) // decelerate
            val dim = if (p.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) p.dimAmount.coerceIn(0f, 1f) else 0f
            if (dim > 0f) c.drawColor(Color.argb((dim * k * 255).toInt(), 0, 0, 0))
            val vw = win.view.width; val vh = win.view.height
            val (x, y) = placeOf(win)
            if (dialog) {
                // Drawn on its own layer: faded/scaled as it animates, and kept for its exit.
                val layer = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
                win.view.draw(Canvas(layer))
                lastDrawn.put(win.view, Snapshot(layer, x, y, dim))?.bmp?.recycle()
                drawLayer(c, layer, x, y, scale = 0.9f + 0.1f * k, alpha = k)
            } else {
                c.save(); c.translate(x.toFloat(), y.toFloat()); win.view.draw(c); c.restore()
            }
        }
        // Dialogs that just closed, still animating out on top (Robolectric removed them at once).
        for (l in leaving) {
            val q = ((clockMs - l.at).toFloat() / WINDOW_ANIM_MS).coerceIn(0f, 1f)
            val a = 1f - q * q // accelerate
            if (l.snap.dim > 0f) c.drawColor(Color.argb((l.snap.dim * a * 255).toInt(), 0, 0, 0))
            drawLayer(c, l.snap.bmp, l.snap.x, l.snap.y, scale = 1f - 0.1f * q, alpha = a)
        }
        toasts.firstOrNull()?.takeIf { it.at >= 0 }?.let { drawToast(c, it, w, h) }
        return bmp to wins.size
    }

    // ------------------------------------------- window animations (emulated)
    // Robolectric adds and removes windows instantly; a device animates a dialog's window in
    // and out (Android's dialog_enter / dialog_exit: fade + 90%→100% scale over 200 ms,
    // decelerating in, accelerating out, the dim behind fading with it). Emulated on the
    // lab's own clock so recordings show it; a plain `shot` comes after an action's 400 ms
    // settle, when it has finished. Compose popups (menus) animate inside Compose already.

    /** App time: [FRAME_MS] per frame of [settle]. */
    private var clockMs = 0L
    /** When each window (other than the activity's) first showed. */
    private val shownAt = java.util.IdentityHashMap<View, Long>()
    private class Snapshot(val bmp: Bitmap, val x: Int, val y: Int, val dim: Float)
    /** Each open dialog's last rendered look, drawn while it animates out once it's gone. */
    private val lastDrawn = java.util.IdentityHashMap<View, Snapshot>()
    private class Leaving(val snap: Snapshot, val at: Long)
    private val leaving = mutableListOf<Leaving>()
    private var tracking = false

    /** Every frame: note windows that appeared (start their enter animation) and dialogs that
     *  closed (start their exit from the last rendered look). Windows already up when the lab
     *  starts count as long open. */
    private fun trackWindows() {
        val views = windows().map { it.view }.filter { it !== act.window.decorView }
        if (!tracking) { views.forEach { shownAt[it] = LONG_AGO }; tracking = true }
        views.forEach { shownAt.putIfAbsent(it, clockMs) }
        val gone = shownAt.keys.filter { k -> views.none { it === k } }
        gone.forEach { v -> shownAt.remove(v); lastDrawn.remove(v)?.let { leaving += Leaving(it, clockMs) } }
        leaving.removeAll { l -> (clockMs - l.at >= WINDOW_ANIM_MS).also { if (it) l.snap.bmp.recycle() } }
    }

    private fun drawLayer(c: Canvas, b: Bitmap, x: Int, y: Int, scale: Float, alpha: Float) {
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        val m = android.graphics.Matrix()
        m.postScale(scale, scale, b.width / 2f, b.height / 2f)
        m.postTranslate(x.toFloat(), y.toFloat())
        c.drawBitmap(b, m, paint)
    }

    // -------------------------------------------------------------- recording

    /** Frames being captured (`record start`): app time since start, and when the last frame was. */
    private class Recording(val name: String, val everyMs: Long) {
        val frames = mutableListOf<Bitmap>()
        val times = mutableListOf<Long>()
        var appMs = 0L
        var lastAt = 0L
    }
    private var rec: Recording? = null

    /** A full-screen `show` stands in for that screen opening fresh (like an app launch), so a
     *  recording started just before it must not keep the previous screen as its first frame:
     *  that frame never happens on a device (the user saw Home flash before the boot intro). */
    fun startRecordingFresh() {
        val r = rec ?: return
        if (r.frames.size == 1) {
            r.frames.removeAt(0).recycle(); r.times.removeAt(0)
        }
    }

    private fun record(arg: String): String {
        val parts = arg.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when (parts.firstOrNull()) {
            "start" -> {
                val name = parts.getOrNull(1) ?: error("record start <name> [everyMs]")
                val every = (parts.getOrNull(2)?.toLongOrNull() ?: 32L).coerceAtLeast(FRAME_MS)
                rec = Recording(name, every).also { capture(it) } // the "before" frame
                "recording $name every $every ms"
            }
            "stop" -> {
                val r = rec ?: error("not recording")
                rec = null
                val frames = File(outDir, "${r.name}-frames").also { writeFrames(it, r) }
                val sheet = File(outDir, "${r.name}-frames.png").also { writeFilmstrip(it, r) }
                r.frames.forEach { it.recycle() }
                "recorded ${r.times.size} frames over ${r.appMs} ms: ${sheet.absolutePath} (+ ${r.name}.gif from ${frames.name}/)"
            }
            else -> error("record start <name> [everyMs] | record stop")
        }
    }

    /** One frame, scaled so its long side is at most [MAX_FRAME_SIDE] (a GIF at full headset
     *  resolution is tens of MB). Stops adding past [MAX_FRAMES]. */
    private fun capture(r: Recording) {
        r.lastAt = r.appMs
        if (r.frames.size >= MAX_FRAMES) return
        val (full, _) = render()
        val k = minOf(1f, MAX_FRAME_SIDE.toFloat() / maxOf(full.width, full.height))
        val frame = if (k < 1f) Bitmap.createScaledBitmap(full, (full.width * k).toInt(), (full.height * k).toInt(), true)
            .also { full.recycle() } else full
        r.frames += frame
        r.times += r.appMs
    }

    /** Every frame as `<name>-frames/0000.png…` + `times.txt` (app ms of each); `gif.py` (run by
     *  ui.sh / uictl.sh afterwards) makes the GIF from them. The unit-test classpath is the
     *  Android SDK's, which has no javax.imageio/java.awt to write a GIF here. */
    private fun writeFrames(dir: File, r: Recording) {
        dir.deleteRecursively(); dir.mkdirs()
        r.frames.forEachIndexed { i, bmp ->
            File(dir, "%04d.png".format(i)).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File(dir, "times.txt").writeText(r.times.joinToString("\n", postfix = "\n"))
    }

    /** All frames (evenly thinned to [SHEET_FRAMES]) in a grid, each stamped with its app time:
     *  the whole animation in one image, so it can be checked frame by frame. */
    private fun writeFilmstrip(file: File, r: Recording) {
        if (r.frames.isEmpty()) return
        val n = r.frames.size
        val pick = if (n <= SHEET_FRAMES) (0 until n).toList()
            else (0 until SHEET_FRAMES).map { it * (n - 1) / (SHEET_FRAMES - 1) }.distinct()
        val first = r.frames[0]
        val cw = SHEET_CELL_W
        val ch = (first.height * cw.toFloat() / first.width).toInt()
        val cols = minOf(SHEET_COLS, pick.size)
        val rows = (pick.size + cols - 1) / cols
        val gap = 6
        val sheet = Bitmap.createBitmap(cols * (cw + gap) + gap, rows * (ch + gap) + gap, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.rgb(24, 24, 28))
        val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f }
        val tag = android.graphics.Paint().apply { color = Color.argb(190, 0, 0, 0) }
        pick.forEachIndexed { k, i ->
            val x = gap + (k % cols) * (cw + gap)
            val y = gap + (k / cols) * (ch + gap)
            c.drawBitmap(r.frames[i], null, android.graphics.Rect(x, y, x + cw, y + ch), null)
            val text = "+${r.times[i]} ms"
            c.drawRect(x.toFloat(), y.toFloat(), x + label.measureText(text) + 12f, y + 30f, tag)
            c.drawText(text, x + 6f, y + 23f, label)
        }
        file.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        sheet.recycle()
    }

    // ------------------------------------------------------------- real input
    // `tap`/`long` run an element's own click action: they always reach it, even when something
    // else is drawn over it. touch/press/drag/swipe/hover send real pointer events instead, the
    // way a device does: the window under the point gets them (a dialog or an open menu takes
    // EVERY touch, which is how a touch outside it closes it), then Compose's own hit testing
    // picks the element. So a covered button stays dead, drag-and-drop and sliders work, swipes
    // fling and the pointer's hover highlight shows. All on the app clock: velocity, long-press
    // and hold timers (the Automations editor's 2 s hover-to-arm) run as on the headset, and
    // `record` captures the gesture frame by frame.

    private val density get() = act.resources.displayMetrics.density
    private fun dp(px: Float) = (px / density).roundToInt()

    /** A point on screen (px), and the element (+ its window) it was aimed at; null for `x,y`. */
    private class Aim(val x: Float, val y: Float, val node: SemanticsNode?, val win: Win?, val what: String)

    /** `x,y` (dp on screen) | `<text> [#n] [@fx,fy]` (that element's visible box at the fraction
     *  fx,fy; default its centre) | `slider [#n] [@fx,fy]` (sliders have no text of their own). */
    private fun aim(spec: String): Aim {
        val s = spec.trim()
        Regex("^(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)$").find(s)?.let { m ->
            val (x, y) = m.groupValues[1] to m.groupValues[2]
            return Aim(x.toFloat() * density, y.toFloat() * density, null, null, "$x,$y")
        }
        var q = s; var fx = 0.5f; var fy = 0.5f
        Regex("^(.*?)\\s*@\\s*(\\d*\\.?\\d+)\\s*,\\s*(\\d*\\.?\\d+)$").find(s)?.let { m ->
            q = m.groupValues[1]; fx = m.groupValues[2].toFloat(); fy = m.groupValues[3].toFloat()
        }
        val (text, idx) = parseIndex(q.trim())
        val all = placedNodes()
        val found = if (text.equals("slider", ignoreCase = true)) all.filter { it.first.config.getOrNull(SemanticsActions.SetProgress) != null }
        else {
            val t = text.lowercase()
            val exact = all.filter { label(it.first).lowercase() == t }
            val partial = all.filter { label(it.first).lowercase().contains(t) && it !in exact }
            innermost(exact) + innermost(partial)
        }
        val (n, win) = found.getOrNull(idx - 1)
            ?: error("nothing on screen matching \"$text\"" + if (found.isNotEmpty()) " (only ${found.size})" else "")
        val b = screenBounds(n, win)
        if (b.width <= 0f || b.height <= 0f) error("\"$text\" is off-screen: scrollto it first")
        return Aim(b.left + b.width * fx, b.top + b.height * fy, n, win, "\"${label(n).take(40).ifBlank { text }}\"")
    }

    /** Drops matches that only matched through a child that matched too (a card whose merged
     *  text includes the label): aim at the element itself, not the middle of its container. */
    private fun innermost(found: List<Pair<SemanticsNode, Win>>): List<Pair<SemanticsNode, Win>> {
        fun key(n: SemanticsNode, w: Win) = "${System.identityHashCode(w.view)}:${n.id}"
        val keys = found.map { key(it.first, it.second) }.toSet()
        val containers = HashSet<String>()
        for ((n, w) in found) {
            var p = n.parent
            while (p != null) { key(p, w).let { if (it in keys) containers += it }; p = p.parent }
        }
        return found.filter { key(it.first, it.second) !in containers }
    }

    /** [n]'s visible box on screen (px): its box in its window, moved to where the window sits. */
    private fun screenBounds(n: SemanticsNode, win: Win): Rect {
        val (wx, wy) = placeOf(win)
        return n.boundsInWindow.translate(wx.toFloat(), wy.toFloat())
    }

    /** The window a touch at (x, y) goes to, picked like Android's input dispatcher: from the top,
     *  the first that contains the point or is touch-modal (a dialog or an open menu: it takes
     *  every touch, and one outside it is what closes it). */
    private fun windowAt(x: Float, y: Float): Win? {
        for (win in windows().reversed()) {
            val f = win.params.flags
            if (f and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0) continue
            val (wx, wy) = placeOf(win)
            val inside = x >= wx && x < wx + win.view.width && y >= wy && y < wy + win.view.height
            val modal = f and (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) == 0
            if (inside || modal) return win
        }
        return null
    }

    private fun kind(win: Win): String = when {
        win.view === act.window.decorView -> "screen"
        win.params.type == WindowManager.LayoutParams.TYPE_APPLICATION -> "dialog"
        else -> "menu/popup"
    }

    /** One pointer event to [win] at screen point (x, y), in window coordinates as the input
     *  dispatcher hands it over (the raw position stays on screen), through the window's root
     *  view as ViewRootImpl delivers it (DecorView → Activity/Dialog → views → Compose). */
    private fun send(win: Win, action: Int, x: Float, y: Float, downAt: Long, mouse: Boolean = false): Boolean {
        if (!win.view.isAttachedToWindow) return false // closed mid-gesture (a menu that went away)
        val props = MotionEvent.PointerProperties().apply {
            id = 0; toolType = if (mouse) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = if (mouse) 0f else 1f; size = 0.05f }
        val ev = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, if (mouse) InputDevice.SOURCE_MOUSE else InputDevice.SOURCE_TOUCHSCREEN, 0)
        val (wx, wy) = placeOf(win)
        ev.offsetLocation(-wx.toFloat(), -wy.toFloat())
        val hovering = action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_HOVER_EXIT
        return try {
            if (hovering) win.view.dispatchGenericMotionEvent(ev) else win.view.dispatchTouchEvent(ev)
        } finally { ev.recycle() }
    }

    /** One finger: down at [from], held [holdMs], moved to (toX, toY) over [overMs] (a move every
     *  frame), held [stayMs] there, lifted. The window under [from] gets the whole gesture, as on
     *  a device. Returns that window and whether anything in it took the touch. */
    private fun gesture(from: Aim, toX: Float, toY: Float, holdMs: Long, overMs: Long, stayMs: Long): Pair<Win, Boolean> {
        val win = windowAt(from.x, from.y) ?: error("no window at ${dp(from.x)},${dp(from.y)} dp")
        val downAt = SystemClock.uptimeMillis()
        // Windows above it that watch for outside touches hear of it (how a non-focusable popup closes).
        for (w in windows().reversed()) {
            if (w.view === win.view) break
            if (w.params.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH != 0) send(w, MotionEvent.ACTION_OUTSIDE, from.x, from.y, downAt)
        }
        val took = send(win, MotionEvent.ACTION_DOWN, from.x, from.y, downAt)
        settle(maxOf(FRAME_MS, holdMs))
        val steps = (overMs / FRAME_MS).toInt()
        if (steps == 0 && (toX != from.x || toY != from.y)) send(win, MotionEvent.ACTION_MOVE, toX, toY, downAt)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            send(win, MotionEvent.ACTION_MOVE, from.x + (toX - from.x) * t, from.y + (toY - from.y) * t, downAt)
            settle(FRAME_MS)
        }
        if (stayMs > 0) settle(stayMs)
        send(win, MotionEvent.ACTION_UP, toX, toY, downAt)
        return win to took
    }

    /** Trailing `<key> <number>` options (`hold 600 stay 2000`), and the text before them. */
    private fun options(arg: String, vararg keys: String): Pair<String, Map<String, Long>> {
        var rest = arg.trim()
        val opts = HashMap<String, Long>()
        val re = Regex("\\s+(${keys.joinToString("|")})\\s+(\\d+)$", RegexOption.IGNORE_CASE)
        while (true) {
            val m = re.find(rest) ?: break
            opts[m.groupValues[1].lowercase()] = m.groupValues[2].toLong()
            rest = rest.substring(0, m.range.first).trim()
        }
        return rest to opts
    }

    /** touch/press <element|x,y> [hold MS]: a real tap (or long press), then what it landed on. */
    private fun touch(arg: String, holdMs: Long, verb: String): String {
        val (spec, opts) = options(arg, "hold")
        val a = aim(spec)
        val hit = whatsAt(a.x, a.y)
        val (win, took) = gesture(a, a.x, a.y, opts["hold"] ?: holdMs, 0, 0)
        return "$verb ${a.what} at ${dp(a.x)},${dp(a.y)} dp -> " + landed(a, win, took, hit) + sliderNote(a)
    }

    /** drag <from> to|-> <to> | drag <from> by dx,dy  [hold MS] [over MS] [stay MS]
     *  (`->` when a label itself contains " to "). */
    private fun drag(arg: String): String {
        val (body, opts) = options(arg, "hold", "over", "stay")
        val by = Regex("^(.*?)\\s+by\\s+(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)$", RegexOption.IGNORE_CASE).find(body)
        val from: Aim
        val toX: Float
        val toY: Float
        val toWhat: String
        if (by != null) {
            from = aim(by.groupValues[1])
            toX = from.x + by.groupValues[2].toFloat() * density
            toY = from.y + by.groupValues[3].toFloat() * density
            toWhat = "by ${by.groupValues[2]},${by.groupValues[3]} dp"
        } else {
            val arrow = body.indexOf("->")
            val split = if (arrow >= 0) arrow to 2 else body.lastIndexOf(" to ") to 4
            if (split.first < 0) error("drag <from> to <to> | drag <from> by dx,dy [hold ms] [over ms] [stay ms]")
            from = aim(body.substring(0, split.first))
            val to = aim(body.substring(split.first + split.second))
            toX = to.x; toY = to.y; toWhat = "to ${to.what}"
        }
        val hit = whatsAt(from.x, from.y)
        val (win, took) = gesture(from, toX, toY, opts["hold"] ?: 0L, opts["over"] ?: DRAG_MS, opts["stay"] ?: DRAG_STAY_MS)
        return "drag ${from.what} $toWhat (${dp(from.x)},${dp(from.y)} to ${dp(toX)},${dp(toY)} dp) -> " +
            landed(from, win, took, hit) + sliderNote(from)
    }

    /** swipe up|down|left|right [on <element>] [dist DP] [over MS]: a quick flick the finger's
     *  way (`swipe up` moves a list's content up, i.e. scrolls down), from the element's centre,
     *  else the middle of the biggest scroll area. */
    private fun swipe(arg: String): String {
        val (body, opts) = options(arg, "dist", "over")
        val dir = body.substringBefore(' ').lowercase()
        val on = body.substringAfter(' ', "").trim().removePrefix("on ").trim()
        val (dx, dy) = when (dir) {
            "up" -> 0f to -1f; "down" -> 0f to 1f; "left" -> -1f to 0f; "right" -> 1f to 0f
            else -> error("swipe up|down|left|right [on <element>] [dist dp] [over ms]")
        }
        val main = act.window.decorView
        val from = if (on.isNotEmpty()) aim(on) else {
            val area = placedNodes().filter { it.first.config.getOrNull(SemanticsActions.ScrollBy) != null }
                .map { Triple(it.first, it.second, screenBounds(it.first, it.second)) }
                .filter { it.third.width > 0f && it.third.height > 0f && windowAt(it.third.center.x, it.third.center.y)?.view === it.second.view }
                .maxByOrNull { it.third.width * it.third.height }
            if (area != null) Aim(area.third.center.x, area.third.center.y, area.first, area.second, "the scroll area")
            else Aim(main.width / 2f, main.height / 2f, null, null, "the screen")
        }
        val dist = opts["dist"]?.let { it * density } ?: ((if (dy != 0f) main.height else main.width) * 0.4f)
        val over = opts["over"] ?: SWIPE_MS
        val (_, took) = gesture(from, from.x + dx * dist, from.y + dy * dist, 0L, over, 0L)
        return "swipe $dir on ${from.what} (${dp(dist)} dp in $over ms)" + if (!took) " -> (!) nothing there takes a touch" else ""
    }

    private var hoverWin: Win? = null
    private var hoverX = 0f
    private var hoverY = 0f

    /** hover <element|x,y> | hover off: the headset pointer resting on something (mouse-style
     *  hover events: Compose's hover highlight and anything else that reacts to hovering). It
     *  glides there over a few frames, like a pointer does. */
    private fun hover(arg: String): String {
        fun move(win: Win, action: Int, x: Float, y: Float) = send(win, action, x, y, SystemClock.uptimeMillis(), mouse = true)
        if (arg.trim().equals("off", ignoreCase = true)) {
            hoverWin?.let { move(it, MotionEvent.ACTION_HOVER_EXIT, hoverX, hoverY) }
            hoverWin = null
            return "hover off"
        }
        val a = aim(arg)
        val win = windowAt(a.x, a.y) ?: error("no window at ${dp(a.x)},${dp(a.y)} dp")
        if (hoverWin?.view !== win.view) {
            hoverWin?.let { move(it, MotionEvent.ACTION_HOVER_EXIT, hoverX, hoverY) }
            hoverX = a.x; hoverY = a.y
            move(win, MotionEvent.ACTION_HOVER_ENTER, a.x, a.y)
        }
        val sx = hoverX; val sy = hoverY
        for (i in 1..HOVER_STEPS) {
            val t = i.toFloat() / HOVER_STEPS
            move(win, MotionEvent.ACTION_HOVER_MOVE, sx + (a.x - sx) * t, sy + (a.y - sy) * t)
            settle(FRAME_MS)
        }
        hoverWin = win; hoverX = a.x; hoverY = a.y
        val over = whatsAt(a.x, a.y)
        return "hover ${a.what} at ${dp(a.x)},${dp(a.y)} dp" + (over?.let { " -> over \"${label(it).take(40).ifBlank { describe(it) }}\"" } ?: "")
    }

    /** The element a touch at (x, y) lands on, as far as the layout shows: in the window that gets
     *  it, the deepest element there that takes touches (later in the tree = drawn on top).
     *  Something catching touches without semantics (a bare pointerInput) isn't visible here,
     *  but the real touch still hits it. */
    private fun whatsAt(x: Float, y: Float): SemanticsNode? {
        val win = windowAt(x, y) ?: return null
        val p = Offset(x, y)
        return nodesOf(win).lastOrNull { takesTouch(it) && screenBounds(it, win).contains(p) }
    }

    private fun takesTouch(n: SemanticsNode) = n.config.getOrNull(SemanticsActions.OnClick) != null ||
        n.config.getOrNull(SemanticsActions.OnLongClick) != null || n.config.getOrNull(SemanticsActions.SetProgress) != null

    private fun related(a: SemanticsNode, b: SemanticsNode): Boolean {
        fun under(n: SemanticsNode, top: SemanticsNode): Boolean {
            var p: SemanticsNode? = n
            while (p != null) { if (p.id == top.id) return true; p = p.parent }
            return false
        }
        return under(a, b) || under(b, a)
    }

    private fun describe(n: SemanticsNode): String = when {
        n.config.getOrNull(SemanticsActions.SetProgress) != null -> "slider"
        n.config.getOrNull(SemanticsProperties.ToggleableState) != null -> "switch"
        n.config.getOrNull(SemanticsActions.SetText) != null -> "text field"
        else -> "(no text)"
    }

    /** What the touch aimed at [a] reached, with a warning when it isn't what was aimed at. */
    private fun landed(a: Aim, win: Win, took: Boolean, hit: SemanticsNode?): String {
        val where = if (kind(win) == "screen") "" else " (${kind(win)})"
        val (wx, wy) = placeOf(win)
        val outside = a.x < wx || a.x >= wx + win.view.width || a.y < wy || a.y >= wy + win.view.height
        if (outside) {
            // A dialog/menu on top takes touches outside itself too; it closes on the lift
            // if it closes on outside taps (so the press itself isn't "taken").
            var closed = false
            for (i in 0 until 6) { if (windows().none { it.view === win.view }) { closed = true; break }; settle(FRAME_MS) }
            return "(!) went to the ${kind(win)} on top (outside it): " + if (closed) "it closed" else "it stayed open"
        }
        if (a.win != null && a.win.view !== win.view) return "(!) went to the ${kind(win)} on top of ${a.what} instead"
        return when {
            !took -> "(!) nothing there takes a touch"
            hit == null -> "no tappable element there (something without a label took it: a scroll area or a gesture)$where"
            a.node != null && hit.id != a.node.id && !related(hit, a.node) ->
                "(!) landed on \"${label(hit).take(40).ifBlank { describe(hit) }}\"$where, which covers ${a.what}"
            else -> "\"${label(hit).take(40).ifBlank { describe(hit) }}\"$where"
        }
    }

    /** A slider's value after the gesture (it moves on the frame after the touch). */
    private fun sliderNote(a: Aim): String {
        val n0 = a.node ?: return ""
        if (n0.config.getOrNull(SemanticsActions.SetProgress) == null) return ""
        settle(2 * FRAME_MS)
        val n = placedNodes().firstOrNull { it.first.id == n0.id }?.first ?: return ""
        val r = n.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) ?: return ""
        return " | slider now %.2f (%.2f..%.2f)".format(Locale.US, r.current, r.range.start, r.range.endInclusive)
    }

    // ----------------------------------------------------------------- targets

    /**
     * targets [minDp] [name]: every element that can be tapped right now, checked the way a finger
     * or the headset pointer meets it: covered at its centre by another tappable element (that one
     * gets the touch), or smaller than [minDp] (48 by default, Android's minimum; Compose widens a
     * smaller one's touch area to 48 dp where nothing else is near, so those are worth a look, not
     * certain misses). With a name it also saves <name>.png with each one boxed (red = covered,
     * amber = small).
     */
    private fun targets(arg: String): String {
        val parts = arg.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val minDp = parts.firstNotNullOfOrNull { it.toIntOrNull() } ?: MIN_TARGET_DP
        val name = parts.firstOrNull { it.toIntOrNull() == null }
        class Issue(val box: Rect, val covered: Boolean, val size: Float, val tiny: Boolean, val text: String)
        val issues = mutableListOf<Issue>()
        var checked = 0
        for ((n, win) in placedNodes()) {
            if (!takesTouch(n)) continue
            val b = screenBounds(n, win)
            if (b.width <= 0f || b.height <= 0f) continue
            val c = b.center
            if (windowAt(c.x, c.y)?.view !== win.view) continue // under a dialog or menu: can't be touched now
            checked++
            val what = "\"${label(n).take(40).ifBlank { describe(n) }}\" at ${dp(b.left)},${dp(b.top)} dp"
            val top = whatsAt(c.x, c.y)
            if (top != null && top.id != n.id && !related(top, n)) {
                issues += Issue(b, true, 0f, false, "covered: $what, by \"${label(top).take(40).ifBlank { describe(top) }}\"")
            }
            val w = b.width / density; val h = b.height / density
            if (w < minDp - 0.5f || h < minDp - 0.5f) {
                // Tiny = small both ways (an icon); a wide but short row is just small.
                val tiny = maxOf(w, h) < TINY_TARGET_DP
                issues += Issue(b, false, minOf(w, h), tiny, "${if (tiny) "tiny" else "small"} ${w.roundToInt()}x${h.roundToInt()} dp: $what")
            }
        }
        val sorted = issues.sortedWith(compareBy<Issue>({ !it.covered }, { !it.tiny }, { it.size }))
        val covered = sorted.count { it.covered }
        val tiny = sorted.count { it.tiny }
        var saved = ""
        if (name != null) {
            val (bmp, _) = render()
            val c = Canvas(bmp)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f * density
            }
            for (i in sorted) {
                paint.color = when {
                    i.covered -> Color.rgb(244, 67, 54)
                    i.tiny -> Color.rgb(255, 120, 0)
                    else -> Color.rgb(255, 214, 0)
                }
                c.drawRect(i.box.left, i.box.top, i.box.right, i.box.bottom, paint)
            }
            val f = File(outDir, "$name.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            saved = " | ${f.absolutePath}"
        }
        return "$checked tappable: $covered covered, $tiny tiny (both sides < $TINY_TARGET_DP dp), ${sorted.size - covered - tiny} small (a side < $minDp dp)$saved" +
            sorted.joinToString("") { "\n- " + it.text }
    }

    // ------------------------------------------------------------------ toasts
    // Robolectric keeps toasts in a list and never draws them. The lab queues each one like the
    // system does (one at a time: 2 s short, 3.5 s long), draws it over everything like Android's
    // text toast (a dark rounded box above the bottom edge, fading in and out), and names it in
    // the result of the command that brought it up.

    private class ShownToast(val toast: Toast, val text: String, val ms: Long) { var at = -1L }
    private val toasts = ArrayDeque<ShownToast>()
    private var toastsSeen = 0
    private val newToasts = mutableListOf<String>()

    private fun trackToasts() {
        val shown = runCatching { shadowOf(app).shownToasts }.getOrNull() ?: return
        while (toastsSeen < shown.size) {
            val t = shown[toastsSeen++]
            val text = runCatching { toastText(t) }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            toasts.addLast(ShownToast(t, text, if (t.duration == Toast.LENGTH_LONG) TOAST_LONG_MS else TOAST_SHORT_MS))
            newToasts += text
        }
        while (true) {
            val cur = toasts.firstOrNull() ?: return
            if (cur.at < 0) cur.at = clockMs
            val cancelled = runCatching { Shadow.extract<ShadowToast>(cur.toast).isCancelled }.getOrDefault(false)
            if (!cancelled && clockMs - cur.at < cur.ms + TOAST_FADE_MS) return
            toasts.removeFirst() // done: the next one starts
        }
    }

    private fun toastText(t: Toast): String? =
        ShadowToast::class.java.getDeclaredField("text").apply { isAccessible = true }.get(Shadow.extract(t)) as String?

    private fun drawToast(c: Canvas, t: ShownToast, w: Int, h: Int) {
        val age = clockMs - t.at
        val a = when {
            age < TOAST_FADE_MS -> age.toFloat() / TOAST_FADE_MS
            age > t.ms -> 1f - (age - t.ms).toFloat() / TOAST_FADE_MS
            else -> 1f
        }.coerceIn(0f, 1f)
        if (a <= 0f) return
        val d = density
        val tp = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 14f, act.resources.displayMetrics)
            alpha = (a * 255).toInt()
        }
        val maxW = (minOf(w - 48 * d, 400 * d) - 32 * d).toInt().coerceAtLeast(1)
        val layout = android.text.StaticLayout.Builder.obtain(t.text, 0, t.text.length, tp, maxW)
            .setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        val textW = (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }
        val bw = textW + 32 * d
        val bh = layout.height + 24 * d
        val left = (w - bw) / 2f
        val top = h - 64 * d - bh
        val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb((a * 235).toInt(), 50, 50, 50) }
        val r = minOf(24 * d, bh / 2f)
        c.drawRoundRect(android.graphics.RectF(left, top, left + bw, top + bh), r, r, bg)
        c.save(); c.translate(left + 16 * d, top + 12 * d); layout.draw(c); c.restore()
    }

    // -------------------------------------------------------------- semantics

    private fun composeRoots(v: View, out: MutableList<RootForTest>) {
        if (v is RootForTest) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) composeRoots(v.getChildAt(i), out)
    }

    /** One window's merged semantics nodes, in tree order (parents first, later = drawn on top). */
    private fun nodesOf(win: Win): List<SemanticsNode> {
        val roots = mutableListOf<RootForTest>().also { composeRoots(win.view, it) }
        val out = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) { out += n; n.children.forEach { walk(it) } }
        roots.forEach { walk(it.semanticsOwner.rootSemanticsNode) }
        return out
    }

    /** Merged semantics nodes, TOP window first (a dialog's buttons win over the screen under it). */
    private fun nodes(): List<SemanticsNode> = windows().reversed().flatMap { nodesOf(it) }

    /** The same, each with its window (for where it is on screen). */
    private fun placedNodes(): List<Pair<SemanticsNode, Win>> = windows().reversed().flatMap { w -> nodesOf(w).map { it to w } }

    private fun label(n: SemanticsNode): String {
        val parts = mutableListOf<String>()
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { parts += it }
        n.config.getOrNull(SemanticsProperties.Text)?.let { t -> parts += t.map { it.text } }
        n.config.getOrNull(SemanticsProperties.EditableText)?.let { parts += it.text }
        return parts.joinToString(" ").replace('\n', ' ').trim()
    }

    private fun parseIndex(q: String): Pair<String, Int> {
        val m = Regex("^(.*?)\\s+#(\\d+)$").find(q) ?: return q to 1
        return m.groupValues[1] to m.groupValues[2].toInt()
    }

    /** Elements matching [query] that support [action] (climbing to the clickable parent). */
    private fun matches(query: String, action: String): List<SemanticsNode> {
        val q = query.lowercase()
        val all = nodes()
        fun hasAction(n: SemanticsNode) = n.config.any { it.key.name == action }
        fun withAction(n: SemanticsNode): SemanticsNode? {
            var cur: SemanticsNode? = n
            while (cur != null) { if (hasAction(cur)) return cur; cur = cur.parent }
            // A label beside its own control (ToggleRow = text + Switch): the unlabeled control
            // on the same line, to the right — what "tap Scroll" means on the device.
            val b = n.boundsInRoot
            return all.filter { hasAction(it) && label(it).isBlank() }
                .filter { val c = it.boundsInRoot; c.top < b.bottom && c.bottom > b.top && c.left >= b.left }
                .minByOrNull { it.boundsInRoot.left - b.right }
        }
        val exact = all.filter { label(it).lowercase() == q }
        val partial = all.filter { label(it).lowercase().contains(q) && it !in exact }
        return (exact + partial).mapNotNull { withAction(it) }.distinct()
    }

    private fun act(query: String, action: String, invoke: (SemanticsNode) -> Any?): String {
        val (q, idx) = parseIndex(query)
        val found = matches(q, action)
        val n = found.getOrNull(idx - 1)
            ?: error("nothing to ${action.lowercase()} matching \"$q\"" + if (found.isNotEmpty()) " (only ${found.size})" else "")
        invoke(n)
        return "${action.lowercase()} \"${label(n).take(60)}\""
    }

    private fun type(field: String?, value: String): String {
        val editable = nodes().filter { it.config.getOrNull(SemanticsActions.SetText) != null }
        val n = when {
            field != null -> {
                val (q, idx) = parseIndex(field)
                editable.filter { label(it).lowercase().contains(q.lowercase()) }.getOrNull(idx - 1)
                    ?: editable.getOrNull((q.toIntOrNull() ?: 0) - 1)
            }
            else -> editable.firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true } ?: editable.firstOrNull()
        } ?: error("no text field" + (field?.let { " matching \"$it\"" } ?: ""))
        n.config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke()
        n.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value))
        return "typed into \"${label(n).take(40)}\""
    }

    private fun scrollables(): List<SemanticsNode> =
        nodes().filter { it.config.getOrNull(SemanticsActions.ScrollBy) != null }

    private fun scroll(arg: String): String {
        val parts = arg.split(' ').filter { it.isNotBlank() }
        val dir = if (parts.firstOrNull() == "up") -1f else 1f
        val times = parts.getOrNull(1)?.toFloatOrNull() ?: 1f
        val target = scrollables().maxByOrNull { it.boundsInRoot.height * it.boundsInRoot.width } ?: error("nothing scrollable")
        target.config[SemanticsActions.ScrollBy].action?.invoke(0f, dir * times * target.boundsInRoot.height * 0.85f)
        return "scrolled ${if (dir > 0) "down" else "up"} $times"
    }

    private fun scrollTo(query: String): String {
        repeat(60) {
            val q = query.lowercase()
            val n = nodes().firstOrNull { label(it).lowercase().contains(q) }
            if (n != null) {
                var anc: SemanticsNode? = n.parent
                while (anc != null && anc.config.getOrNull(SemanticsActions.ScrollBy) == null) anc = anc.parent
                if (anc == null) return "\"$query\" is on screen (not inside a scroll area)"
                val view: Rect = anc.boundsInRoot
                // Unclipped: boundsInRoot of a node scrolled out of view is all zeros, which
                // sent the scroll the wrong way (the target never came into view).
                val b = unclipped(n)
                if (b.top >= view.top && b.bottom <= view.bottom) return "\"$query\" in view"
                anc.config[SemanticsActions.ScrollBy].action?.invoke(0f, b.top - view.top - view.height * 0.2f)
                settle(150)
            } else {
                // Not built yet (lazy lists only build rows as they scroll in) or further
                // down: step through each scroll area that can still go down, TOP window
                // first and in screen order (a dialog's list before its details pane). Only
                // scrolling the largest area missed lists next to a bigger pane.
                val target = scrollables().firstOrNull { canScrollDown(it) } ?: error("\"$query\" not found after scrolling")
                target.config[SemanticsActions.ScrollBy].action?.invoke(0f, target.boundsInRoot.height * 0.7f)
                settle(150)
            }
        }
        error("\"$query\" not found after scrolling")
    }

    /** Whether a scroll area isn't at its end yet (lists without a range count as scrollable). */
    private fun canScrollDown(n: SemanticsNode): Boolean {
        val r = n.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) ?: return true
        return r.value() < r.maxValue() - 0.5f
    }

    /** Where [n] really is, even when scrolled out of view (boundsInRoot clips to zero). */
    private fun unclipped(n: SemanticsNode): Rect {
        val p = n.positionInRoot
        return Rect(p.x, p.y, p.x + n.size.width, p.y + n.size.height)
    }

    private fun back(): String {
        val dismiss = nodes().firstOrNull { it.config.getOrNull(SemanticsActions.Dismiss) != null }
        if (dismiss != null) { dismiss.config[SemanticsActions.Dismiss].action?.invoke(); return "dismissed" }
        val top = windows().lastOrNull()
        if (top != null && top.view !== act.window.decorView) {
            // A dialog window on top: a real BACK key to it, like the headset's back button.
            top.view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            top.view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            return "back (closed the top window)"
        }
        act.onBackPressedDispatcher.onBackPressed()
        return "back"
    }

    private fun tree(): String {
        val sb = StringBuilder()
        val seen = HashSet<String>()
        for (n in nodes()) {
            val l = label(n)
            val acts = buildList {
                if (n.config.getOrNull(SemanticsActions.OnClick) != null) add("tap")
                if (n.config.getOrNull(SemanticsActions.OnLongClick) != null) add("long")
                if (n.config.getOrNull(SemanticsActions.SetText) != null) add("type")
                if (n.config.getOrNull(SemanticsActions.ScrollBy) != null) add("scroll")
                if (n.config.getOrNull(SemanticsActions.SetProgress) != null) {
                    add("slider" + (n.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.let { "=%.2f".format(Locale.US, it.current) } ?: ""))
                }
                if (n.config.getOrNull(SemanticsProperties.ToggleableState) != null) add("toggle=${n.config[SemanticsProperties.ToggleableState]}")
                if (n.config.getOrNull(SemanticsProperties.Selected) == true) add("selected")
                if (n.config.getOrNull(SemanticsProperties.Disabled) != null) add("disabled")
            }
            if (l.isBlank() && acts.isEmpty()) continue
            val key = "$l|$acts"
            if (!seen.add(key)) continue
            val b = n.boundsInRoot
            sb.append("- ").append(l.take(90).ifBlank { "(no text)" })
            if (acts.isNotEmpty()) sb.append("  [").append(acts.joinToString(",")).append("]")
            // Real position; "off-screen" when it's scrolled/clipped out of view.
            sb.append("  @").append(unclipped(n).top.toInt())
            if (b.width <= 0f || b.height <= 0f) sb.append(" (off-screen)")
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    /** Reflection helpers shared with the scenes. */
    internal fun setState(owner: Any, name: String, raw: String): String {
        val cls = owner.javaClass
        fun convert(cur: Any?): Any? = when {
            raw == "null" -> null
            // A null field has no type to copy (generics are erased), so take a Kotlin-style
            // literal: 12L Long, 12 Int, 1.5f Float, 1.5 Double, true/false (else a String).
            // Writing "1240" into a MutableState<Long?> crashed the composition.
            cur == null -> when {
                Regex("-?\\d+L").matches(raw) -> raw.dropLast(1).toLong()
                Regex("-?\\d+").matches(raw) -> raw.toInt()
                Regex("-?\\d*\\.?\\d+[fF]").matches(raw) -> raw.dropLast(1).toFloat()
                Regex("-?\\d*\\.\\d+").matches(raw) -> raw.toDouble()
                raw == "true" || raw == "false" -> raw.toBooleanStrict()
                else -> raw
            }
            cur is Boolean -> raw.toBooleanStrict()
            cur is Int -> raw.toInt()
            cur is Long -> raw.toLong()
            cur is Float -> raw.toFloat()
            cur is Double -> raw.toDouble()
            cur is Enum<*> -> cur.javaClass.enumConstants!!.first { (it as Enum<*>).name.equals(raw, true) }
            else -> raw
        }
        allFields(cls).firstOrNull { it.name == "$name\$delegate" }?.let { f ->
            f.isAccessible = true
            @Suppress("UNCHECKED_CAST") val st = f.get(owner) as MutableState<Any?>
            st.value = convert(st.value); return "$name = ${st.value}"
        }
        allFields(cls).firstOrNull { it.name == "_$name" }?.let { f ->
            f.isAccessible = true
            val v = f.get(owner)
            if (v is MutableStateFlow<*>) {
                @Suppress("UNCHECKED_CAST") val flow = v as MutableStateFlow<Any?>
                flow.value = convert(flow.value); return "$name = ${flow.value}"
            }
        }
        val setter = cls.methods.firstOrNull { it.name == "set" + name.replaceFirstChar { c -> c.uppercase() } && it.parameterCount == 1 }
        if (setter != null) {
            val getter = cls.methods.firstOrNull { (it.name == "get" + name.replaceFirstChar { c -> c.uppercase() } || it.name == "is" + name.replaceFirstChar { c -> c.uppercase() }) && it.parameterCount == 0 }
            val cur = getter?.invoke(owner)
            setter.invoke(owner, convert(cur)); return "$name = ${getter?.invoke(owner)}"
        }
        allFields(cls).firstOrNull { it.name == name }?.let { f ->
            f.isAccessible = true; f.set(owner, convert(f.get(owner))); return "$name = ${f.get(owner)}"
        }
        error("no settable \"$name\" on ${cls.simpleName}")
    }

    internal fun getState(owner: Any, name: String): Any? {
        val cls = owner.javaClass
        allFields(cls).firstOrNull { it.name == "$name\$delegate" }?.let { it.isAccessible = true; return (it.get(owner) as MutableState<*>).value }
        cls.methods.firstOrNull { (it.name == "get" + name.replaceFirstChar { c -> c.uppercase() } || it.name == "is" + name.replaceFirstChar { c -> c.uppercase() }) && it.parameterCount == 0 }
            ?.let { return it.invoke(owner) }
        allFields(cls).firstOrNull { it.name == name || it.name == "_$name" }?.let { f ->
            f.isAccessible = true; val v = f.get(owner); return if (v is MutableStateFlow<*>) v.value else v
        }
        error("no \"$name\" on ${cls.simpleName}")
    }

    private fun allFields(c: Class<*>): List<java.lang.reflect.Field> =
        generateSequence(c) { it.superclass }.flatMap { it.declaredFields.asSequence() }.toList()

    companion object {
        const val SETTLE_AFTER_ACTION_MS = 400L
        private const val FRAME_MS = 16L
        // Real input: how long a finger rests for a tap / a long press (Android's long-press
        // timeout is 400-500 ms), a drag's default move time and its rest before lifting, a
        // swipe's flick time, the frames a hover glides over, and the touch-target minimum.
        private const val TAP_HOLD_MS = 80L
        private const val PRESS_HOLD_MS = 700L
        private const val DRAG_MS = 500L
        private const val DRAG_STAY_MS = 100L
        private const val SWIPE_MS = 150L
        private const val HOVER_STEPS = 4
        private const val MIN_TARGET_DP = 48
        private const val TINY_TARGET_DP = 32
        // Toasts: Android's short/long durations and the fade at each end.
        private const val TOAST_SHORT_MS = 2000L
        private const val TOAST_LONG_MS = 3500L
        private const val TOAST_FADE_MS = 200L
        // Android's dialog window animation (config_shortAnimTime), emulated for dialogs.
        private const val WINDOW_ANIM_MS = 200L
        private const val LONG_AGO = Long.MIN_VALUE / 4
        // Recording: frame size, how many are kept, and the filmstrip's grid.
        private const val MAX_FRAME_SIDE = 1024
        private const val MAX_FRAMES = 240
        private const val SHEET_FRAMES = 24
        private const val SHEET_COLS = 4
        private const val SHEET_CELL_W = 480
    }
}
