package com.vrca.uilab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.animateContentSize
import com.vrca.ui.common.widenIn
import kotlinx.coroutines.launch
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
     * or an admin changed one; a warning is just this popup, no separate warn screen). It opens like the boot
     * screen (SAO style, the same [com.vrca.app.Unfold]): the app darkens, a line stretches out across each card's
     * middle and the card unfolds up and down from it (standing first, actions just after), then the card's pieces
     * widen in one by one and the buttons last. Close (or Back) folds them shut again (actions first), the dark
     * fades, then [onClosed]. Everything moves while drawing. No actions: the standing card alone, centred.
     */
    @Composable
    fun Standing(state: String, onClosed: () -> Unit, animate: Boolean = true) {
        val openFirst = state.endsWith("open")
        val (level, off, actions) = data(state.removeSuffix("open"))
        val start = if (animate) 0f else 1f
        val dim = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(start) }
        val panelL = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(start) }
        val panelR = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(start) }
        val partsL = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(start) }
        val partsR = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(start) }
        var closing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(closing) {
            val ease = androidx.compose.animation.core.FastOutSlowInEasing
            fun t(ms: Int) = androidx.compose.animation.core.tween<Float>(ms, easing = ease)
            if (!closing) kotlinx.coroutines.coroutineScope {
                if (dim.value >= 1f) return@coroutineScope
                launch { dim.animateTo(1f, t(450)) }
                kotlinx.coroutines.delay(180)
                launch { panelL.animateTo(1f, t(700)) }
                kotlinx.coroutines.delay(130)
                launch { panelR.animateTo(1f, t(700)) }
                kotlinx.coroutines.delay(470)
                launch { partsL.animateTo(1f, androidx.compose.animation.core.tween(700)) }
                kotlinx.coroutines.delay(130)
                partsR.animateTo(1f, androidx.compose.animation.core.tween(700))
            } else kotlinx.coroutines.coroutineScope {
                // Like boot's exit: the pieces go, the cards fold shut (actions first), then the dark lifts.
                launch { partsR.animateTo(0f, t(200)) }
                launch { partsL.animateTo(0f, t(200)) }
                kotlinx.coroutines.delay(120)
                launch { panelR.animateTo(0f, t(420)) }
                kotlinx.coroutines.delay(90)
                panelL.animateTo(0f, t(420))
                dim.animateTo(0f, t(300))
                onClosed()
            }
        }
        val close = { closing = true }
        androidx.activity.compose.BackHandler(enabled = level != Level.Banned && !closing) { close() }
        Box(Modifier.fillMaxSize()) {
            // Darkens the app well behind it and blocks taps on it (tapping it does nothing: Close is explicit).
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = dim.value }.background(Color.Black.copy(alpha = 0.82f))
                .clickable(interactionSource = null, indication = null) {})
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val wide = maxWidth >= 700.dp
                val closeBtn: @Composable () -> Unit = {
                    com.vrca.ui.common.GremlinButton("Close", { close() }, size = com.vrca.ui.common.GremlinButtonSize.Large)
                }
                val supportBtn: @Composable () -> Unit = {
                    com.vrca.ui.common.GremlinButton(
                        "Support server", {}, kind = com.vrca.ui.common.GremlinButtonKind.Secondary, size = com.vrca.ui.common.GremlinButtonSize.Large,
                        icon = androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.vrca.R.drawable.ic_discord)
                    )
                }
                // The buttons widen in last, once each card's pieces are in.
                val btnL = Modifier.widenIn { stagger(partsL.value, 4, 5) }
                val btnR = Modifier.widenIn { stagger(partsR.value, 2, 3) }
                val scroll = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp)
                if (wide) Column(scroll, horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        com.vrca.app.Unfold({ panelL.value }, Modifier.width(440.dp).fillMaxHeight()) {
                            StandingCard(level, off, { partsL.value }, Modifier.fillMaxSize())
                        }
                        if (actions.isNotEmpty()) com.vrca.app.Unfold({ panelR.value }, Modifier.width(440.dp).fillMaxHeight()) {
                            ActionsCard(actions, openFirst, { partsR.value }, Modifier.fillMaxSize())
                        }
                    }
                    // The pair meets across the gap between the cards (like boot's Retry pill): Close's right edge on the
                    // standing card's right edge, Support server's left edge on the actions card's left edge. With one
                    // card only, its button is centred under it.
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        val one = actions.isEmpty()
                        Box(Modifier.width(440.dp).then(btnL), contentAlignment = if (one) Alignment.Center else Alignment.CenterEnd) { if (level != Level.Banned) closeBtn() }
                        if (!one) Box(Modifier.width(440.dp).then(btnR), contentAlignment = Alignment.CenterStart) { supportBtn() }
                    }
                } else Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().then(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
                    com.vrca.app.Unfold({ panelL.value }, Modifier.fillMaxWidth()) { StandingCard(level, off, { partsL.value }, Modifier.fillMaxWidth()) }
                    if (actions.isNotEmpty()) com.vrca.app.Unfold({ panelR.value }, Modifier.padding(top = 16.dp).fillMaxWidth()) {
                        ActionsCard(actions, openFirst, { partsR.value }, Modifier.fillMaxWidth())
                    }
                    Row(Modifier.padding(top = 12.dp).then(btnR), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (level != Level.Banned) closeBtn()
                        if (actions.isNotEmpty()) supportBtn()
                    }
                }
            }
        }
    }

    /** How far piece [i] of [n] has appeared when its card's pieces are [reveal] through (boot's stagger). */
    private fun stagger(reveal: Float, i: Int, n: Int): Float {
        val span = 0.45f
        val start = if (n <= 1) 0f else (1f - span) * i / (n - 1)
        return ((reveal - start) / span).coerceIn(0f, 1f)
    }

    @Composable
    private fun StandingCard(level: Level, off: List<Off>, parts: () -> Float, modifier: Modifier) = com.vrca.app.Layer(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(Modifier.widenIn { stagger(parts(), 0, 5) }, verticalAlignment = Alignment.CenterVertically) {
                BootCat(level.mark, 0, Modifier.size(52.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Account standing", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = G.Text)
                    Text("Updated 31 Oct 14:20", style = MaterialTheme.typography.bodyMedium, color = G.TextMuted)
                }
            }
            Column(Modifier.widenIn { stagger(parts(), 1, 5) }) {
                Text(level.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = level.color, modifier = Modifier.padding(top = 16.dp))
                Text(level.line, style = MaterialTheme.typography.bodyMedium, color = G.Text, modifier = Modifier.padding(top = 2.dp))
            }
            Meter(level, Modifier.padding(top = 14.dp).widenIn { stagger(parts(), 2, 5) })
            Text("Disabled", style = MaterialTheme.typography.labelLarge, color = G.TextMuted, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp).widenIn { stagger(parts(), 3, 5) })
            Column(Modifier.widenIn { stagger(parts(), 3, 5) }.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.Card).padding(horizontal = 14.dp, vertical = 10.dp),
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
    private fun ActionsCard(actions: List<Action>, openFirst: Boolean, parts: () -> Float, modifier: Modifier) = com.vrca.app.Layer(modifier) {
        val (active, past) = actions.partition { it.active }
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(Modifier.widenIn { stagger(parts(), 0, 3) }, verticalAlignment = Alignment.CenterVertically) {
                Text("Actions on your account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = G.Text, modifier = Modifier.weight(1f))
                if (active.isNotEmpty()) Text("${active.size} active", style = MaterialTheme.typography.bodySmall, color = G.TextMuted)
            }
            // Like the website's contributions feed: one collapsed line each, tap to open the details.
            Column(Modifier.padding(top = 12.dp).widenIn { stagger(parts(), 1, 3) }.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.Card)) {
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
