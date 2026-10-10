package com.vrca.uilab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
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
import java.io.File
import java.time.Duration

/**
 * Drives the real app like a user and captures what's on screen, dialogs and menus included.
 * Commands (one per line, `;` also separates):
 *
 *   shot <name>                 PNG of everything on screen (dialogs/menus drawn over, dimmed)
 *   tap <text> [#n]             click the n-th element whose text / description matches
 *   long <text> [#n]            long-press
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
            else -> error("unknown command: $cmd")
        }
        if (cmd !in setOf("wait", "shot", "tree", "get")) settle(SETTLE_AFTER_ACTION_MS)
        return r.toString()
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
        val wins = windows()
        val main = wins.firstOrNull { it.view === act.window.decorView } ?: error("activity window not attached")
        val w = main.view.width; val h = main.view.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        main.view.draw(c)
        for (win in wins) {
            if (win === main) continue
            val p = win.params
            if (p.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                c.drawColor(Color.argb((p.dimAmount.coerceIn(0f, 1f) * 255).toInt(), 0, 0, 0))
            }
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
            c.save(); c.translate(x.toFloat(), y.toFloat()); win.view.draw(c); c.restore()
        }
        val f = File(outDir, if (name.endsWith(".png")) name else "$name.png")
        f.parentFile?.mkdirs()
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "shot ${f.absolutePath} (${w}x$h, ${wins.size} window${if (wins.size == 1) "" else "s"})"
    }

    // -------------------------------------------------------------- semantics

    private fun composeRoots(v: View, out: MutableList<RootForTest>) {
        if (v is RootForTest) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) composeRoots(v.getChildAt(i), out)
    }

    /** Merged semantics nodes, TOP window first (a dialog's buttons win over the screen under it). */
    private fun nodes(): List<SemanticsNode> {
        val roots = mutableListOf<RootForTest>()
        windows().reversed().forEach { composeRoots(it.view, roots) }
        val out = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) { out += n; n.children.forEach { walk(it) } }
        roots.forEach { walk(it.semanticsOwner.rootSemanticsNode) }
        return out
    }

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
        repeat(25) {
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
                val target = scrollables().maxByOrNull { it.boundsInRoot.height } ?: error("\"$query\" not found")
                target.config[SemanticsActions.ScrollBy].action?.invoke(0f, target.boundsInRoot.height * 0.7f)
                settle(150)
            }
        }
        error("\"$query\" not found after scrolling")
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
    }
}
