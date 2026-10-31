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

    private class Off(val feature: String, val until: String)
    private class Action(val kind: String, val title: String, val date: String, val note: String, val ends: String, val evidence: List<Boolean>, val active: Boolean)

    private fun data(state: String): Triple<Level, List<Off>, List<Action>> = when (state) {
        "good" -> Triple(Level.Good, emptyList(), listOf(
            Action("Warning", "Spam through the chatbox", "12 Sep", "Repeated the same message in a busy instance.", "Ended 12 Oct", emptyList(), false)))
        "risk" -> Triple(Level.AtRisk, listOf(Off("Chatbox: custom text", "for 2 days"), Off("Avatar clone", "for 28 days"), Off("Avatar search", "for 28 days")), listOf(
            Action("Restriction", "Harassment through the chatbox", "29 Oct", "Targeted messages at another player after being asked to stop.", "Ends 28 Nov", listOf(true, false), true),
            Action("Warning", "Spam through the chatbox", "12 Sep", "Repeated the same message in a busy instance.", "Ended 12 Oct", emptyList(), false)))
        "banned" -> Triple(Level.Banned, listOf(Off("Everything in VRC-A", "permanently")), listOf(
            Action("Ban", "Abusing VRC-A's servers", "31 Oct", "Automated requests from a modified app.", "Permanent", listOf(true, true, false), true),
            Action("Restriction", "Harassment through the chatbox", "29 Oct", "Targeted messages at another player after being asked to stop.", "Ends 28 Nov", listOf(true), true)))
        else -> Triple(Level.Limited, listOf(Off("Avatar search", "for 6 days")), listOf(
            Action("Restriction", "Abusing VRC-A's servers", "30 Oct", "Too many searches in a short time.", "Ends 6 Nov", listOf(true), true)))
    }

    /** Built like the crash screen: one centred block (max 640dp), Boot Cat + title above a single layer card. */
    @Composable
    fun Page(state: String) {
        val (level, off, actions) = data(state)
        val (active, past) = actions.partition { it.active }
        Box(Modifier.fillMaxSize().background(G.Backdrop), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BootCat(level.mark, 0, Modifier.size(52.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(title(level), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = G.Text)
                        Text("Account standing  ·  31 Oct 14:20", style = MaterialTheme.typography.bodyMedium, color = G.TextMuted)
                    }
                }
                Spacer(Modifier.height(18.dp))
                com.vrca.app.Layer(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
                        Text(level.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = level.color)
                        Text(level.line, style = MaterialTheme.typography.bodyMedium, color = G.Text, modifier = Modifier.padding(top = 2.dp))
                        Meter(level, Modifier.padding(top = 14.dp))
                        // What's switched off, as the crash screen's details panel (label -> time left).
                        Panel(Modifier.padding(top = 14.dp)) {
                            if (off.isEmpty()) KeyValue("Switched off", "Nothing")
                            off.forEachIndexed { k, o -> KeyValue(if (k == 0) "Switched off" else "", "${o.feature}  ·  ${o.until}") }
                        }
                        active.forEach { ActionBlock(it, Modifier.padding(top = 14.dp)) }
                        if (past.isNotEmpty()) Text("History: " + past.joinToString { "${it.kind} on ${it.date} (${it.ends.replaceFirstChar { c -> c.lowercase() }})" },
                            style = MaterialTheme.typography.bodySmall, color = G.TextMuted, modifier = Modifier.padding(top = 12.dp))
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                            com.vrca.ui.common.GremlinButton("Support server", {}, kind = com.vrca.ui.common.GremlinButtonKind.Secondary)
                            if (level != Level.Banned) com.vrca.ui.common.GremlinButton("Close", {})
                        }
                    }
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
    private fun Evidence(video: Boolean) = Box(Modifier.width(64.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(8.dp))
        .background(G.Panel).border(1.dp, G.Edge, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        if (video) BootIconView(BootIcons.Start, { G.TextMuted }, { G.TextMuted }, Modifier.size(22.dp))
        else Text("Pic", style = MaterialTheme.typography.labelSmall, color = G.TextMuted)
    }
}
