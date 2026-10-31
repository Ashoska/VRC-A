package com.vrca.uilab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vrca.app.BootCat
import com.vrca.app.BootIconView
import com.vrca.app.BootIcons
import com.vrca.app.Mark
import com.vrca.ui.theme.GremlinColors as G

/**
 * UI Lab mock-up (revamp 2, account standing, 2026-10-31): the user's Account standing page, Discord-style.
 * `show standingmock <good|limited|risk|banned>`. Mock data only; nothing here ships.
 */
object StandingMocks {
    /** The five levels, worst last, each with its colour, Boot Cat mark and plain line. */
    private enum class Level(val label: String, val color: Color, val mark: Mark, val line: String) {
        Good("All good", G.Success, Mark.Done, "Your account is in good standing. Thanks for following the rules."),
        Limited("Limited", G.Warning, Mark.Warn, "Some features are switched off for a while."),
        VeryLimited("Very limited", G.Caution, Mark.Warn, "Several features are switched off for a while."),
        AtRisk("At risk", G.Error, Mark.Failed, "One more serious action could get you banned from VRC-A."),
        Banned("Banned", G.Alert, Mark.Failed, "Your access to VRC-A is suspended."),
    }

    private class Off(val feature: String, val until: String, val left: Float = 0.5f)
    private class Action(val kind: String, val title: String, val date: String, val note: String, val ends: String, val evidence: List<Boolean>, val active: Boolean)

    private fun data(state: String): Triple<Level, List<Off>, List<Action>> = when (state) {
        "clean" -> Triple(Level.Good, emptyList(), emptyList())
        "good" -> Triple(Level.Good, emptyList(), listOf(
            Action("Warning", "Spam through the chatbox", "12 Sep", "Repeated the same message in a busy instance.", "Ended 12 Oct", emptyList(), false)))
        "risk" -> Triple(Level.AtRisk, listOf(Off("Chatbox: custom text", "2 days left", 0.67f), Off("Avatar clone", "28 days left", 0.93f), Off("Avatar search", "28 days left", 0.93f)), listOf(
            Action("Restriction", "Harassment through the chatbox", "29 Oct", "Targeted messages at another player after being asked to stop.", "Ends 28 Nov", listOf(true, false), true),
            Action("Warning", "Spam through the chatbox", "12 Sep", "Repeated the same message in a busy instance.", "Ended 12 Oct", emptyList(), false)))
        "banned" -> Triple(Level.Banned, listOf(Off("Everything in VRC-A", "Permanent", 1f)), listOf(
            Action("Ban", "Abusing VRC-A's servers", "31 Oct", "Automated requests from a modified app.", "Permanent", listOf(true, true, false), true),
            Action("Restriction", "Harassment through the chatbox", "29 Oct", "Targeted messages at another player after being asked to stop.", "Ends 28 Nov", listOf(true), true)))
        else -> Triple(Level.Limited, listOf(Off("Avatar search", "6 days left", 0.86f)), listOf(
            Action("Restriction", "Abusing VRC-A's servers", "30 Oct", "Too many searches in a short time.", "Ends 6 Nov", listOf(true), true)))
    }

    /** Floats over the app (opened from Settings): the app dimmed behind, the standing card in the crash screen's
     *  style. No actions on record: the standing card alone, centred. With actions (active or past): on a wide screen
     *  the standing card moves left and the actions card sits beside it; on a phone they stack. */
    @Composable
    fun Page(state: String) {
        val (level, off, actions) = data(state)
        Box(Modifier.fillMaxSize().background(G.Panel)) {
            // Stand-in for the app underneath, dimmed.
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(5) { Box(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(16.dp)).background(G.Card)) }
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)))
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val wide = maxWidth >= 700.dp
                val scroll = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp)
                if (wide) Row(scroll.height(androidx.compose.foundation.layout.IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StandingCard(level, off, Modifier.width(440.dp).fillMaxHeight())
                    if (actions.isNotEmpty()) ActionsCard(actions, Modifier.width(440.dp).fillMaxHeight())
                } else Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().then(scroll), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    StandingCard(level, off, Modifier.fillMaxWidth())
                    if (actions.isNotEmpty()) ActionsCard(actions, Modifier.fillMaxWidth())
                }
            }
        }
    }

    @Composable
    private fun StandingCard(level: Level, off: List<Off>, modifier: Modifier) = com.vrca.app.Layer(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BootCat(level.mark, 0, Modifier.size(52.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Account standing", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = G.Text)
                    Text("Updated 31 Oct 14:20", style = MaterialTheme.typography.bodyMedium, color = G.TextMuted)
                }
            }
            Text(level.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = level.color, modifier = Modifier.padding(top = 16.dp))
            Text(level.line, style = MaterialTheme.typography.bodyMedium, color = G.Text, modifier = Modifier.padding(top = 2.dp))
            Meter(level, Modifier.padding(top = 14.dp))
            Text("Switched off", style = MaterialTheme.typography.labelLarge, color = G.TextMuted, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            if (off.isEmpty()) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.Card).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BootCat(Mark.Done, 0, Modifier.size(22.dp)); Spacer(Modifier.width(10.dp))
                Text("Nothing. Every feature is on.", style = MaterialTheme.typography.bodyMedium, color = G.Text)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { off.forEach { OffTile(it, level.color) } }
            Spacer(Modifier.weight(1f))
            if (level != Level.Banned) Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                com.vrca.ui.common.GremlinButton("Close", {})
            }
        }
    }

    @Composable
    private fun ActionsCard(actions: List<Action>, modifier: Modifier) = com.vrca.app.Layer(modifier) {
        val (active, past) = actions.partition { it.active }
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Actions on your account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = G.Text, modifier = Modifier.weight(1f))
                if (active.isNotEmpty()) Pill("${active.size} active", G.AccentSoft)
            }
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                active.forEach { ActionTile(it) }
                if (past.isNotEmpty()) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.Card).padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("History", style = MaterialTheme.typography.bodyMedium, color = G.Text, modifier = Modifier.weight(1f))
                    Text("${past.size} past", style = MaterialTheme.typography.bodySmall, color = G.TextMuted)
                    Text("  ›", style = MaterialTheme.typography.bodyLarge, color = G.TextMuted)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                com.vrca.ui.common.GremlinButton("Support server", {}, kind = com.vrca.ui.common.GremlinButtonKind.Secondary)
            }
        }
    }

    /** A small rounded label in [color] on a soft tint of it. */
    @Composable
    private fun Pill(text: String, color: Color) = Text(text, style = MaterialTheme.typography.labelMedium, color = color,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp))

    /** One switched-off feature: name, time left, and a bar draining as the time runs out. */
    @Composable
    private fun OffTile(o: Off, tone: Color) = Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.Card).padding(horizontal = 14.dp, vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(o.feature, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = G.Text, modifier = Modifier.weight(1f))
            Pill(o.until, tone)
        }
        Box(Modifier.padding(top = 9.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(G.Raised)) {
            Box(Modifier.fillMaxWidth(o.left).height(4.dp).clip(RoundedCornerShape(2.dp)).background(tone.copy(alpha = 0.8f)))
        }
    }

    /** One active action: a stripe in its type's colour, the type and date, what it was for, the note, its evidence
     *  (only what's shown to the user) and when it ends. */
    @Composable
    private fun ActionTile(a: Action) {
        val tone = when (a.kind) { "Ban" -> G.Alert; "Restriction" -> G.Caution; else -> G.Warning }
        Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).clip(RoundedCornerShape(12.dp)).background(G.Card)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(tone))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(a.kind, tone); Spacer(Modifier.weight(1f))
                    Text(a.date, style = MaterialTheme.typography.bodySmall, color = G.TextMuted)
                }
                Text(a.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = G.Text)
                Text(a.note, style = MaterialTheme.typography.bodySmall, color = G.TextMuted)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
                    a.evidence.filter { it }.forEachIndexed { k, _ -> Evidence(video = k == 1) }
                    Spacer(Modifier.weight(1f))
                    Pill(a.ends, if (a.ends == "Permanent") G.Alert else G.TextMuted)
                }
            }
        }
    }

    private fun title(l: Level) = when (l) {
        Level.Good -> "Your account is in good standing"
        Level.Banned -> "You're banned from VRC-A"
        else -> "Your account is " + l.label.lowercase()
    }

    @Composable
    private fun Meter(level: Level, modifier: Modifier) = Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Level.entries.forEach { l ->
            val on = l == level
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (l.ordinal <= level.ordinal) l.color.copy(alpha = if (on) 1f else 0.35f) else G.Raised))
                Text(l.label, style = MaterialTheme.typography.bodySmall, color = if (on) G.Text else G.TextMuted, maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    @Composable
    private fun Panel(modifier: Modifier, content: @Composable () -> Unit) = Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.Card).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) { content() }

    @Composable
    private fun KeyValue(label: String, value: String) = Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = G.TextMuted, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = G.Text)
    }

    /** One active action: what it was for, the note, when it ends, and its evidence (if shown to the user). */
    @Composable
    private fun ActionBlock(a: Action, modifier: Modifier) = Panel(modifier) {
        val tone = when (a.kind) { "Ban" -> G.Alert; "Restriction" -> G.Caution; else -> G.Warning }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${a.kind}: ${a.title}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = tone, modifier = Modifier.weight(1f))
            Text(a.date, style = MaterialTheme.typography.bodySmall, color = G.TextMuted)
        }
        Text(a.note, style = MaterialTheme.typography.bodySmall, color = G.Text)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            a.evidence.filter { it }.forEachIndexed { i, _ -> Evidence(video = i == 1) }
            Spacer(Modifier.weight(1f))
            Text(a.ends, style = MaterialTheme.typography.bodySmall, color = G.TextMuted)
        }
    }

    /** A placeholder evidence thumbnail (a picture, or a video with a play mark). */
    @Composable
    private fun Evidence(video: Boolean) = Box(Modifier.width(72.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(8.dp))
        .background(G.Panel).border(1.dp, G.Edge, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        if (video) BootIconView(BootIcons.Start, { G.TextMuted }, { G.TextMuted }, Modifier.size(22.dp))
        else Text("Pic", style = MaterialTheme.typography.labelSmall, color = G.TextMuted)
    }
}
