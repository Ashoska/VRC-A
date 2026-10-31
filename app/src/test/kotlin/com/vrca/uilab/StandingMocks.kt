package com.vrca.uilab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.vectorResource
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.graphicsLayer
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
    /** [disables]: the features this action switched off (their time left lives in the Disabled list, so an
     *  action shows no end date of its own: user, two dates on one card were confusing). */
    private class Action(val kind: String, val title: String, val date: String, val note: String, val disables: List<String>, val evidence: List<Boolean>, val active: Boolean)

    private fun data(state: String): Triple<Level, List<Off>, List<Action>> = when (state) {
        "clean" -> Triple(Level.Good, emptyList(), emptyList())
        "good" -> Triple(Level.Good, emptyList(), listOf(
            Action("Warning", "Spam through the chatbox", "12 Sep", "Repeated the same message in a busy instance.", emptyList(), emptyList(), false)))
        "risk" -> Triple(Level.AtRisk, listOf(Off("Chatbox: custom text", "2 days left", 0.67f), Off("Avatar clone", "28 days left", 0.93f), Off("Avatar search", "28 days left", 0.93f)), listOf(
            Action("Restriction", "Harassment through the chatbox", "29 Oct", "Targeted messages at another player after being asked to stop.", listOf("Chatbox: custom text", "Avatar clone", "Avatar search"), listOf(true, false), true),
            Action("Warning", "Spam through the chatbox", "12 Sep", "Repeated the same message in a busy instance.", emptyList(), emptyList(), false)))
        "warned" -> Triple(Level.Good, emptyList(), listOf(
            Action("Warning", "Spam through the chatbox", "31 Oct", "Repeated the same message in a busy instance. Next time features get disabled.", emptyList(), listOf(true), true)))
        "banned" -> Triple(Level.Banned, listOf(Off("Everything in VRC-A", "Permanent", 1f)), listOf(
            Action("Ban", "Abusing VRC-A's servers", "31 Oct", "Automated requests from a modified app.", listOf("Everything in VRC-A"), listOf(true, true, false), true),
            Action("Restriction", "Harassment through the chatbox", "29 Oct", "Targeted messages at another player after being asked to stop.", listOf("Chatbox: custom text", "Avatar clone"), listOf(true), true)))
        else -> Triple(Level.Limited, listOf(Off("Avatar search", "6 days left", 0.86f)), listOf(
            Action("Restriction", "Abusing VRC-A's servers", "30 Oct", "Too many searches in a short time.", listOf("Avatar search"), listOf(true), true)))
    }

    /** Full-screen still (`show standingmock <state>[open]`): over a dim stand-in app, fully in. */
    @Composable
    fun Page(state: String) = Box(Modifier.fillMaxSize().background(G.Panel)) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(5) { Box(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(16.dp)).background(G.Card)) }
        }
        androidx.compose.runtime.key(state) { Standing(state, onClosed = {}, animate = false) }
    }

    /**
     * The standing popup floating over the app (opened from Settings, or popped up by itself when an action is new
     * or an admin changed one; a warning is just this popup, no separate warn screen). Everything moves while
     * drawing from one 0..1 value [p]: the scrim fades in, the standing card rises and settles, the actions card
     * slides out from behind it to the right, and the buttons open underneath last (like boot's Retry pill).
     * Close (or Back) runs the same thing backwards, then [onClosed]. No actions: the standing card alone, centred.
     */
    @Composable
    fun Standing(state: String, onClosed: () -> Unit, animate: Boolean = true) {
        val openFirst = state.endsWith("open")
        val (level, off, actions) = data(state.removeSuffix("open"))
        val p = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (animate) 0f else 1f) }
        var closing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(closing) {
            if (!closing) p.animateTo(1f, androidx.compose.animation.core.tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            else { p.animateTo(0f, androidx.compose.animation.core.tween(260, easing = androidx.compose.animation.core.FastOutLinearInEasing)); onClosed() }
        }
        val close = { closing = true }
        androidx.activity.compose.BackHandler(enabled = level != Level.Banned && !closing) { close() }
        fun part(from: Float, to: Float) = ((p.value - from) / (to - from)).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize()) {
            // Scrim: blocks taps on the app underneath; tapping it does nothing (Close is explicit).
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = p.value }.background(Color.Black.copy(alpha = 0.62f))
                .clickable(interactionSource = null, indication = null) {})
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val wide = maxWidth >= 700.dp
                val rise = Modifier.graphicsLayer {
                    val t = part(0f, 0.8f); alpha = t
                    val s = 0.94f + 0.06f * t; scaleX = s; scaleY = s; translationY = (1f - t) * 28.dp.toPx()
                }
                val slide = Modifier.graphicsLayer {
                    val t = part(0.25f, 1f); alpha = t
                    if (wide) translationX = -(1f - t) * 120.dp.toPx() else translationY = (1f - t) * 40.dp.toPx()
                }
                val buttonsIn = Modifier.graphicsLayer { val t = part(0.5f, 1f); alpha = t; translationY = (1f - t) * -12.dp.toPx() }
                val buttons: @Composable () -> Unit = {
                    Row(buttonsIn.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (level != Level.Banned) com.vrca.ui.common.GremlinButton("Close", { close() }, size = com.vrca.ui.common.GremlinButtonSize.Large)
                        if (actions.isNotEmpty()) com.vrca.ui.common.GremlinButton(
                            "Support server", {}, kind = com.vrca.ui.common.GremlinButtonKind.Secondary, size = com.vrca.ui.common.GremlinButtonSize.Large,
                            icon = androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.vrca.R.drawable.ic_discord)
                        )
                    }
                }
                val scroll = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp)
                if (wide) Column(scroll, horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        // The standing card sits on top (zIndex) so the actions card slides out from behind it.
                        StandingCard(level, off, Modifier.width(440.dp).fillMaxHeight().zIndex(1f).then(rise))
                        if (actions.isNotEmpty()) Box(Modifier.width(440.dp).fillMaxHeight().then(slide)) {
                            ActionsCard(actions, openFirst, Modifier.fillMaxSize())
                        }
                    }
                    buttons()
                } else Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().then(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
                    StandingCard(level, off, Modifier.fillMaxWidth().then(rise))
                    if (actions.isNotEmpty()) ActionsCard(actions, openFirst, Modifier.padding(top = 16.dp).fillMaxWidth().then(slide))
                    buttons()
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
            Text("Disabled", style = MaterialTheme.typography.labelLarge, color = G.TextMuted, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.Card).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (off.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    BootCat(Mark.Done, 0, Modifier.size(22.dp)); Spacer(Modifier.width(12.dp))
                    Text("Nothing. Every feature is on.", style = MaterialTheme.typography.bodyMedium, color = G.Text)
                }
                off.forEach { OffRow(it, level.color) }
            }
        }
    }

    @Composable
    private fun ActionsCard(actions: List<Action>, openFirst: Boolean, modifier: Modifier) = com.vrca.app.Layer(modifier) {
        val (active, past) = actions.partition { it.active }
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Actions on your account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = G.Text, modifier = Modifier.weight(1f))
                if (active.isNotEmpty()) Text("${active.size} active", style = MaterialTheme.typography.bodySmall, color = G.TextMuted)
            }
            // Like the website's contributions feed: one collapsed line each, tap to open the details.
            Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.Card)) {
                active.forEachIndexed { k, a -> if (k > 0) Divider(); ActionRow(a, startOpen = openFirst && k == 0) }
                if (past.isNotEmpty()) { Divider(); past.forEach { ActionRow(it, startOpen = false) } }
            }
        }
    }

    @Composable
    private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(G.Edge))

    /** One action as a feed line: type colour, type, title and the date it was given; tapping opens its note,
     *  evidence and what it disabled (the time left on those is in the Disabled list). */
    @Composable
    private fun ActionRow(a: Action, startOpen: Boolean) {
        var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(startOpen) }
        val tone = if (!a.active) G.TextMuted else when (a.kind) { "Ban" -> G.Alert; "Restriction" -> G.Caution; else -> G.Warning }
        Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 11.dp)
            .animateContentSize(androidx.compose.animation.core.tween(220))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(tone, CircleShape)); Spacer(Modifier.width(10.dp))
                Text(a.kind, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = tone)
                Spacer(Modifier.width(8.dp))
                Text(a.title, style = MaterialTheme.typography.bodyMedium, color = if (a.active) G.Text else G.TextMuted, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(a.date, style = MaterialTheme.typography.bodySmall, color = G.TextMuted)
                Spacer(Modifier.width(10.dp))
                val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, label = "chev")
                androidx.compose.foundation.Canvas(Modifier.size(12.dp).graphicsLayer { rotationZ = turn }) {
                    val w = 1.6.dp.toPx(); val c = G.TextMuted
                    drawLine(c, androidx.compose.ui.geometry.Offset(size.width * 0.2f, size.height * 0.38f),
                        androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.66f), w, androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(c, androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.66f),
                        androidx.compose.ui.geometry.Offset(size.width * 0.8f, size.height * 0.38f), w, androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
            if (open) Column(Modifier.padding(start = 18.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(a.note, style = MaterialTheme.typography.bodySmall, color = G.Text)
                if (a.evidence.any { it }) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    a.evidence.filter { it }.forEachIndexed { k, _ -> Evidence(video = k == 1) }
                }
                if (a.disables.isNotEmpty()) Text("Disabled " + a.disables.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = G.TextMuted)
            }
        }
    }

    /** One disabled feature: name, time left, and the countdown Boot Cat in front whose outline drains as the
     *  time runs out (our own drain, the same one as the Retry and Accept countdowns; no stock progress bar). */
    @Composable
    private fun OffRow(o: Off, tone: Color) = Row(verticalAlignment = Alignment.CenterVertically) {
        com.vrca.app.BootCatCountdown(o.left, Modifier.size(22.dp), tone, smooth = false)
        Spacer(Modifier.width(12.dp))
        Text(o.feature, style = MaterialTheme.typography.bodyMedium, color = G.Text, modifier = Modifier.weight(1f))
        Text(o.until, style = MaterialTheme.typography.bodySmall, color = G.TextMuted)
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

    /** A placeholder evidence thumbnail (a picture, or a video with a play mark). */
    @Composable
    private fun Evidence(video: Boolean) = Box(Modifier.width(72.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(8.dp))
        .background(G.Panel).border(1.dp, G.Edge, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        if (video) BootIconView(BootIcons.Start, { G.TextMuted }, { G.TextMuted }, Modifier.size(22.dp))
        else Text("Pic", style = MaterialTheme.typography.labelSmall, color = G.TextMuted)
    }
}
