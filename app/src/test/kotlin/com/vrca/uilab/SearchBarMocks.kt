package com.vrca.uilab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vrca.app.BootCat
import com.vrca.app.BootCatCountdown
import com.vrca.app.BootIconView
import com.vrca.app.BootIcons
import com.vrca.app.Mark
import com.vrca.ui.theme.GremlinColors as G

/**
 * Lab mock (throwaway, for the user's pick): six custom search boxes (no Material field), each idle (placeholder
 * "Avatar / Creator"), typing (focused, text + caret) and cooling down after a search (3 s countdown cat). The Search
 * control is always the same height as the box. `show searchmock`.
 */
object SearchBarMocks {
    private enum class St { Idle, Typing, Cooldown }
    private const val HINT = "Avatar / Creator"
    private const val TEXT = "Moontail"

    @Composable
    private fun Icon(focused: Boolean, size: Dp = 22.dp) =
        BootIconView(BootIcons.Search, { G.TextMuted }, { if (focused) G.Accent else G.TextMuted }, Modifier.size(size))

    @Composable
    private fun Caret(on: Boolean) { if (on) Box(Modifier.padding(start = 1.dp).width(2.dp).height(20.dp).background(G.Accent)) }

    @Composable
    private fun Words(st: St, modifier: Modifier = Modifier) = Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (st == St.Idle) Text(HINT, style = MaterialTheme.typography.bodyLarge, color = G.TextMuted.copy(alpha = 0.7f), maxLines = 1)
        else { Text(TEXT, style = MaterialTheme.typography.bodyLarge, color = G.Text, maxLines = 1); Caret(st == St.Typing) }
    }

    /** The Search control's content: the countdown cat while cooling down, else the label. */
    @Composable
    private fun Go(st: St, color: Color, label: Boolean = true) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (st == St.Cooldown) BootCatCountdown(0.55f, Modifier.size(22.dp), color)
        else if (!label) Icon(true)
        if (label) Text("Search", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = color.copy(alpha = if (st == St.Cooldown) 0.5f else 1f))
    }

    private fun Modifier.outline(color: Color, width: Dp, radius: Dp) = drawBehind {
        val w = width.toPx()
        drawRoundRect(color, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(radius.toPx() - w / 2), style = Stroke(w))
    }

    // A: box + separate button, same 52dp height (what's built now, height fixed).
    @Composable
    private fun A(st: St) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val f = st == St.Typing
        Row(Modifier.weight(1f).height(52.dp).background(G.Raised, RoundedCornerShape(14.dp)).outline(if (f) G.Accent else G.Edge, if (f) 2.dp else 1.5.dp, 14.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) { Icon(f); Spacer(Modifier.width(10.dp)); Words(st) }
        Box(Modifier.height(52.dp).background(G.Card, RoundedCornerShape(14.dp)).outline(G.Accent.copy(alpha = if (st == St.Cooldown) 0.4f else 1f), 1.5.dp, 14.dp).padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center) { Go(st, G.Accent) }
    }

    // B: one joined pill, the Search half filled with the accent.
    @Composable
    private fun B(st: St) {
        val f = st == St.Typing
        Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(G.Raised).outline(if (f) G.Accent else G.Edge, 1.5.dp, 26.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(18.dp)); Icon(f); Spacer(Modifier.width(10.dp)); Words(st, Modifier.weight(1f))
            Box(Modifier.fillMaxHeight().background(G.Accent.copy(alpha = if (st == St.Cooldown) 0.25f else 0.9f)).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) { Go(st, G.Backdrop) }
        }
    }

    // C: no word button: the eared magnifier on the right IS the button (a round accent ring).
    @Composable
    private fun C(st: St) {
        val f = st == St.Typing
        Row(Modifier.fillMaxWidth().height(52.dp).background(G.Raised, RoundedCornerShape(14.dp)).outline(if (f) G.Accent else G.Edge, if (f) 2.dp else 1.5.dp, 14.dp).padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Words(st, Modifier.weight(1f))
            Box(Modifier.size(40.dp).background(G.Card, CircleShape).border(1.5.dp, G.Accent.copy(alpha = if (st == St.Cooldown) 0.4f else 1f), CircleShape), contentAlignment = Alignment.Center) {
                if (st == St.Cooldown) BootCatCountdown(0.55f, Modifier.size(24.dp)) else Icon(true, 22.dp)
            }
        }
    }

    // D: Boot Cat lives in the box: sleepy while idle, awake while typing, the countdown after a search.
    @Composable
    private fun D(st: St) {
        val f = st == St.Typing
        Row(Modifier.fillMaxWidth().height(52.dp).background(G.Raised, RoundedCornerShape(14.dp)).outline(if (f) G.Accent else G.Edge, if (f) 2.dp else 1.5.dp, 14.dp).padding(start = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                when (st) { St.Idle -> BootCat(Mark.Waiting, 0, Modifier.size(28.dp)); St.Typing -> BootCat(Mark.Working, 0, Modifier.size(28.dp)); St.Cooldown -> BootCatCountdown(0.55f, Modifier.size(28.dp)) }
            }
            Spacer(Modifier.width(8.dp)); Words(st, Modifier.weight(1f))
            Box(Modifier.height(40.dp).background(G.Card, RoundedCornerShape(10.dp)).outline(G.Accent.copy(alpha = if (st == St.Cooldown) 0.4f else 1f), 1.5.dp, 10.dp).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text("Search", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = G.Accent.copy(alpha = if (st == St.Cooldown) 0.5f else 1f))
            }
        }
    }

    // E: the logo's ears on the box itself (two small ears on its top edge), button inside on the right.
    @Composable
    private fun E(st: St) {
        val f = st == St.Typing
        val line = if (f) G.Accent else G.Edge
        Box(Modifier.fillMaxWidth().height(62.dp)) {

            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(52.dp).background(G.Raised, RoundedCornerShape(16.dp)).outline(line, if (f) 2.dp else 1.5.dp, 16.dp).padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(f); Spacer(Modifier.width(10.dp)); Words(st, Modifier.weight(1f))
                Box(Modifier.height(40.dp).background(G.Accent.copy(alpha = if (st == St.Cooldown) 0.2f else 0.18f), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) { Go(st, G.Accent) }
            }
            // The ears, drawn over the box's top edge: filled with the box colour, outlined on their two outer sides.
            Box(Modifier.padding(start = 24.dp).size(width = 50.dp, height = 13.dp).align(Alignment.TopStart).drawBehind {
                val w = (if (f) 2.dp else 1.5.dp).toPx(); val base = size.height; val s = 16.dp.toPx()
                for (x0 in floatArrayOf(0f, size.width - s)) {
                    val p = androidx.compose.ui.graphics.Path().apply { moveTo(x0, base + w); lineTo(x0 + 2.dp.toPx(), 0f); lineTo(x0 + s, base + w); close() }
                    drawPath(p, G.Raised)
                    drawLine(line, Offset(x0, base), Offset(x0 + 2.dp.toPx(), 0f), w, StrokeCap.Round)
                    drawLine(line, Offset(x0 + 2.dp.toPx(), 0f), Offset(x0 + s, base), w, StrokeCap.Round)
                }
            })
        }
    }

    // F: flat strip with only a thick bottom line (accent while typing), button as a compact chip.
    @Composable
    private fun F(st: St) {
        val f = st == St.Typing
        Row(Modifier.fillMaxWidth().height(52.dp).background(G.Raised, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).drawBehind {
            val w = (if (f) 3.dp else 2.dp).toPx(); drawLine(if (f) G.Accent else G.Edge, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
        }.padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(f); Spacer(Modifier.width(10.dp)); Words(st, Modifier.weight(1f))
            Box(Modifier.height(36.dp).background(G.Card, RoundedCornerShape(18.dp)).outline(G.Accent.copy(alpha = if (st == St.Cooldown) 0.4f else 1f), 1.5.dp, 18.dp).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) { Go(st, G.Accent) }
        }
    }

    @Composable
    fun Sheet() {
        val rows: List<Pair<String, @Composable (St) -> Unit>> = listOf(
            "A  Box + button" to { s -> A(s) }, "B  Joined pill" to { s -> B(s) }, "C  Icon is the button" to { s -> C(s) },
            "D  Boot Cat in the box" to { s -> D(s) }, "E  Cat ears on the box" to { s -> E(s) }, "F  Underline strip" to { s -> F(s) },
        )
        Column(Modifier.fillMaxSize().background(G.Backdrop).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(150.dp))
                listOf("Idle", "Typing", "After Search (3 s)").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = G.Text) }
            }
            rows.forEach { (label, bar) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(label, Modifier.width(136.dp), style = MaterialTheme.typography.labelLarge, color = G.TextMuted)
                    St.values().forEach { s -> Box(Modifier.weight(1f)) { bar(s) } }
                }
            }
        }
    }
}
