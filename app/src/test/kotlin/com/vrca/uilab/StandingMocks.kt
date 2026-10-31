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

    @Composable
    fun Page(state: String) {
        val (level, off, actions) = data(state)
        BoxWithConstraints(Modifier.fillMaxSize().background(G.Backdrop)) {
            val wide = maxWidth >= 700.dp
            if (wide) Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(0.42f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Header(); StandingCard(level); OffCard(off)
                }
                Column(Modifier.weight(0.58f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionsCard(actions)
                }
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Header(); StandingCard(level); OffCard(off); ActionsCard(actions)
            }
        }
    }

    @Composable
    private fun Header() = Row(verticalAlignment = Alignment.CenterVertically) {
        BootIconView(BootIcons.Account, { G.Accent }, { G.AccentSoft }, Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Text("Account standing", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = G.Text)
    }

    @Composable
    private fun Card(content: @Composable () -> Unit) = Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(G.Card).border(1.dp, G.Edge, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) { content() }

    /** The meter: five steps, the current one lit in its colour with Boot Cat on it. */
    @Composable
    private fun StandingCard(level: Level) = Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BootCat(level.mark, 0, Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(level.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = level.color)
                Text(level.line, style = MaterialTheme.typography.bodyMedium, color = G.TextMuted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Level.entries.forEach { l ->
                val on = l == level
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(if (on) 10.dp else 6.dp).clip(RoundedCornerShape(5.dp))
                        .background(if (l.ordinal <= level.ordinal) l.color.copy(alpha = if (on) 1f else 0.35f) else G.Raised))
                    Spacer(Modifier.height(6.dp))
                    Text(l.label, style = MaterialTheme.typography.labelSmall, color = if (on) G.Text else G.TextMuted, maxLines = 1)
                }
            }
        }
    }

    @Composable
    private fun OffCard(off: List<Off>) = Card {
        Text("Switched off", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = G.Text)
        if (off.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            BootCat(Mark.Done, 0, Modifier.size(24.dp)); Spacer(Modifier.width(10.dp))
            Text("Everything is on", style = MaterialTheme.typography.bodyMedium, color = G.TextMuted)
        }
        off.forEach { o ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.Raised).padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(G.Error, CircleShape)); Spacer(Modifier.width(12.dp))
                Text(o.feature, style = MaterialTheme.typography.bodyMedium, color = G.Text, modifier = Modifier.weight(1f))
                Text(o.until, style = MaterialTheme.typography.labelMedium, color = G.TextMuted)
            }
        }
    }

    @Composable
    private fun ActionsCard(actions: List<Action>) = Card {
        val (active, past) = actions.partition { it.active }
        Text("Actions on your account", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = G.Text)
        if (active.isEmpty()) Text("No active actions", style = MaterialTheme.typography.bodyMedium, color = G.TextMuted)
        active.forEach { ActionRow(it) }
        if (past.isNotEmpty()) {
            Text("History", style = MaterialTheme.typography.labelLarge, color = G.TextMuted)
            past.forEach { ActionRow(it) }
        }
        Text("If you think an action is a mistake, contact us in the VRC-A support server on Discord.",
            style = MaterialTheme.typography.bodySmall, color = G.TextMuted)
    }

    @Composable
    private fun ActionRow(a: Action) {
        val tone = when (a.kind) { "Ban" -> G.Alert; "Restriction" -> G.Caution; else -> G.Warning }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.Raised)
            .border(1.dp, if (a.active) tone.copy(alpha = 0.5f) else G.Edge, RoundedCornerShape(12.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.kind, style = MaterialTheme.typography.labelMedium, color = if (a.active) tone else G.TextMuted,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background((if (a.active) tone else G.TextMuted).copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 2.dp))
                Spacer(Modifier.width(8.dp))
                Text(a.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = if (a.active) G.Text else G.TextMuted, modifier = Modifier.weight(1f))
                Text(a.date, style = MaterialTheme.typography.labelMedium, color = G.TextMuted)
            }
            Text(a.note, style = MaterialTheme.typography.bodyMedium, color = G.TextMuted)
            if (a.evidence.any { it }) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                a.evidence.filter { it }.forEachIndexed { i, _ -> Evidence(video = i == 1) }
            }
            Text(a.ends, style = MaterialTheme.typography.labelMedium, color = if (a.active) tone else G.TextMuted)
        }
    }

    /** A placeholder evidence thumbnail (a picture, or a video with a play mark). */
    @Composable
    private fun Evidence(video: Boolean) = Box(Modifier.width(96.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(8.dp))
        .background(G.Panel).border(1.dp, G.Edge, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        if (video) BootIconView(BootIcons.Start, { G.TextMuted }, { G.TextMuted }, Modifier.size(22.dp))
        else Text("Picture", style = MaterialTheme.typography.labelSmall, color = G.TextMuted)
    }
}
