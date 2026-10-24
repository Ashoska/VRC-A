package com.vrca.uilab

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrca.app.BootIcon
import com.vrca.app.BootIconView
import com.vrca.app.BootIcons
import com.vrca.ui.common.GremlinButton
import com.vrca.ui.common.GremlinButtonSize
import com.vrca.ui.theme.GremlinColors as G

/**
 * TEMPORARY mock-ups for the Terms of Service revamp (Revamp 2, page 2): two layouts on the real
 * Gremlin pieces, `show tosmock a|b`. Not the real screen; removed once a layout is picked and built.
 */
private class Sec(
    val title: String, val icon: BootIcon, val short: String, val body: String,
    val bullets: List<String> = emptyList(), val image: String? = null,
    val callout: Pair<Color, String>? = null,
)

private const val IMG = "/tmp/claude-0/tosimg/"

private val SECTIONS = listOf(
    Sec(
        "Using VRC-A", BootIcons.Vrchat, "Keep the chatbox friendly.",
        "VRC-A writes to your VRChat chatbox for you: the time, your music, your status and what you type.",
        listOf("Don't spam the chatbox in public worlds", "Don't use VRC-A to harass or impersonate anyone"),
        image = IMG + "chatbox.png",
    ),
    Sec(
        "Your VRChat account", BootIcons.VrchatSignIn, "Your login stays on this device.",
        "You sign in to VRChat inside VRC-A. Your login is kept encrypted on this device and only sent to VRChat.",
        image = IMG + "account.png",
        callout = G.Caution to "VRC-A isn't made by VRChat. Using third-party tools with your account is at your own risk.",
    ),
    Sec(
        "What we store", BootIcons.Terms, "Only what the app needs.",
        "To keep your settings in sync and keep VRC-A safe, a little is stored with us:",
        listOf("A random ID for this device", "Your app version and settings", "Never your VRChat password"),
    ),
    Sec(
        "Crash reports", BootIcons.Updates, "Crashes are sent so they get fixed.",
        "When VRC-A crashes, a report is sent to the developer: your device model, the app version and what went wrong. Nothing personal.",
        image = IMG + "crash.png",
    ),
    Sec(
        "Moderation", BootIcons.Account, "Breaking the rules has consequences.",
        "Accounts that break these terms can be warned or banned from VRC-A.",
        callout = G.Negative to "Trying to get around a ban gets every device and account involved banned.",
    ),
)

@Composable
private fun SecImage(path: String, modifier: Modifier) {
    val bmp = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
    if (bmp != null) Image(
        bmp, null, modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, G.Edge, RoundedCornerShape(12.dp)),
        contentScale = ContentScale.Crop
    )
}

@Composable
private fun Callout(c: Pair<Color, String>) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.first.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(3.dp).height(30.dp).background(c.first, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Text(c.second, style = MaterialTheme.typography.bodySmall, color = G.Text)
    }
}

@Composable
private fun SecText(s: Sec) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s.body, style = MaterialTheme.typography.bodyMedium, color = G.Text)
        s.bullets.forEach { b ->
            Row {
                Box(Modifier.padding(top = 8.dp).size(5.dp).background(G.Accent, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(b, style = MaterialTheme.typography.bodyMedium, color = G.Text)
            }
        }
        s.callout?.let { Callout(it) }
    }
}

@Composable
private fun NumberedTitle(n: Int, s: Sec, big: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BootIconView(s.icon, { G.Accent }, { G.Accent }, Modifier.size(if (big) 24.dp else 20.dp))
        Spacer(Modifier.width(10.dp))
        Text("$n  ", style = MaterialTheme.typography.titleMedium, color = G.TextMuted)
        Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = G.Text)
    }
}

@Composable
private fun Header(compact: Boolean, mark: com.vrca.app.Mark = com.vrca.app.Mark.Done) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        com.vrca.app.BootCat(mark, 0, Modifier.size(if (compact) 34.dp else 40.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Terms of Service", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = G.Text)
            Text("Version 4  ·  Updated 24 Oct", style = MaterialTheme.typography.bodySmall, color = G.TextMuted)
        }
    }
}

@Composable
private fun AcceptBar(compact: Boolean) {
    Row(
        Modifier.fillMaxWidth().background(G.Panel).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(true, {}, colors = CheckboxDefaults.colors(checkedColor = G.Accent, uncheckedColor = G.TextMuted))
        Text(
            "I've read and agree to the terms", style = MaterialTheme.typography.bodyMedium, color = G.Text,
            modifier = Modifier.weight(1f), maxLines = 2
        )
        GremlinButton("Accept", {}, size = if (compact) GremlinButtonSize.Medium else GremlinButtonSize.Large, bootIcon = BootIcons.Start)
    }
}

/** A: guided sections. Summary up top, each section a card; images beside the text when wide, above it when narrow. */
@Composable
internal fun TosMockA() {
    BoxWithConstraints(Modifier.fillMaxSize().background(G.Backdrop)) {
        val wide = maxWidth >= 700.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f)) {
                if (wide) {
                    // Contents rail: jump to a section (the one on screen is lit).
                    Column(Modifier.width(230.dp).fillMaxHeight().padding(start = 20.dp, top = 20.dp, end = 8.dp)) {
                        Header(compact = true)
                        Spacer(Modifier.height(22.dp))
                        Text("On this page", style = MaterialTheme.typography.labelMedium, color = G.TextMuted)
                        Spacer(Modifier.height(8.dp))
                        listOf("In short").plus(SECTIONS.map { it.title }).forEachIndexed { i, t ->
                            val lit = i == 1
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (lit) G.Card else Color.Transparent)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.width(3.dp).height(16.dp).background(if (lit) G.Accent else Color.Transparent, RoundedCornerShape(2.dp)))
                                Spacer(Modifier.width(8.dp))
                                Text(t, style = MaterialTheme.typography.bodyMedium, color = if (lit) G.Text else G.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(horizontal = if (wide) 12.dp else 14.dp, vertical = if (wide) 20.dp else 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!wide) Header(compact = false)
                    // The short version: one line per section, so it's readable at a glance.
                    com.vrca.app.Layer(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("In short", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = G.Text)
                            SECTIONS.forEach { s ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    BootIconView(s.icon, { G.Accent }, { G.Accent }, Modifier.size(20.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(s.short, style = MaterialTheme.typography.bodyMedium, color = G.Text)
                                }
                            }
                        }
                    }
                    SECTIONS.forEachIndexed { i, s ->
                        com.vrca.app.Layer(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                NumberedTitle(i + 1, s)
                                if (s.image != null && wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Box(Modifier.weight(1.15f)) { SecText(s) }
                                    SecImage(s.image, Modifier.weight(1f).aspectRatio(16f / 10f))
                                } else {
                                    s.image?.let { SecImage(it, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }
                                    SecText(s)
                                }
                            }
                        }
                    }
                }
            }
            AcceptBar(compact = !wide)
        }
    }
}

/** B: list + reader. Wide: sections on the left (ticked once read), the open one on the right. Narrow: expandable cards. */
@Composable
internal fun TosMockB() {
    val open = 1
    BoxWithConstraints(Modifier.fillMaxSize().background(G.Backdrop)) {
        val wide = maxWidth >= 700.dp
        Column(Modifier.fillMaxSize()) {
            if (wide) Row(Modifier.weight(1f).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.width(330.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Header(compact = true)
                    Spacer(Modifier.height(4.dp))
                    Text("Read 1 of 5", style = MaterialTheme.typography.labelMedium, color = G.TextMuted)
                    SECTIONS.forEachIndexed { i, s -> ListRow(i, s, selected = i == open, read = i < open) }
                }
                com.vrca.app.Layer(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        val s = SECTIONS[open]
                        NumberedTitle(open + 1, s)
                        s.image?.let { SecImage(it, Modifier.fillMaxWidth().height(230.dp)) }
                        SecText(s)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            com.vrca.ui.common.GremlinButton(
                                "Next: ${SECTIONS[open + 1].title}", {}, kind = com.vrca.ui.common.GremlinButtonKind.Secondary,
                                size = GremlinButtonSize.Medium
                            )
                        }
                    }
                }
            } else Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Header(compact = false)
                Text("Tap a section to read it  ·  Read 1 of 5", style = MaterialTheme.typography.labelMedium, color = G.TextMuted)
                SECTIONS.forEachIndexed { i, s ->
                    if (i == open) com.vrca.app.Layer(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            NumberedTitle(i + 1, s)
                            s.image?.let { SecImage(it, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }
                            SecText(s)
                        }
                    } else ListRow(i, s, selected = false, read = i < open)
                }
            }
            AcceptBar(compact = !wide)
        }
    }
}

@Composable
private fun ListRow(i: Int, s: Sec, selected: Boolean, read: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) G.Raised else G.Card)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) G.Accent else G.Edge, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BootIconView(s.icon, { G.Accent }, { G.Accent }, Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${i + 1}  ${s.title}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = G.Text)
            Text(s.short, style = MaterialTheme.typography.bodySmall, color = G.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (read) com.vrca.app.BootCat(com.vrca.app.Mark.Done, 0, Modifier.size(22.dp))
    }
}


// ---------------------------------------------------------------- B, round 2 (user: left panel good,
// fill its blank space, better topic copy; the reader was cramped and badly formatted).

private class Sec2(
    val title: String, val icon: BootIcon, val short: String,
    val lead: String, val points: List<String> = emptyList(), val image: String? = null,
    val after: String? = null, val callout: Pair<com.vrca.app.Mark, String>? = null,
)

private val SECTIONS2 = listOf(
    Sec2("Welcome to VRC-A", BootIcons.Tutorial, "What's possible with VRC-A",
        "VRC-A is a free companion app for VRChat. It runs on your phone or Quest and talks to VRChat for you, so your chatbox, status and music can update while you play."),
    Sec2("Platform rules", BootIcons.Vrchat, "Third-party guidelines",
        "VRC-A works with VRChat and Discord, so their rules apply to everything you do through it, on top of these terms.",
        listOf("Follow VRChat's Terms and Community Guidelines", "Follow Discord's Terms when Discord status is on", "No spam or harassment through the chatbox"),
        image = IMG + "chatbox.png"),
    Sec2("Login Centre", BootIcons.VrchatSignIn, "Your connected accounts",
        "Signing in to VRChat powers your status, friends, instances and avatar tools. Signing in to Discord is optional and shows what you're up to on your Discord profile. Your logins are kept encrypted on this device.",
        image = IMG + "account.png",
        callout = com.vrca.app.Mark.Warn to "VRC-A isn't made by VRChat. Using third-party tools with your account is at your own risk."),
    Sec2("Data and privacy", BootIcons.Privacy, "What we store",
        "To sync your settings and keep VRC-A safe, a small amount is stored with us.",
        listOf("A random ID for this device", "Your app version and settings", "Never your VRChat password")),
    Sec2("Bugs and reports", BootIcons.Bug, "Crash reports and fixes",
        "If VRC-A crashes, a report is sent to the developer with your device model, the app version and what went wrong. Nothing personal is in it.",
        image = IMG + "crash.png"),
    Sec2("Account standing", BootIcons.Account, "Moderation, warnings and bans",
        "Accounts that break these terms can be warned, and repeat or serious cases banned from VRC-A.",
        callout = com.vrca.app.Mark.Failed to "Trying to get around a ban gets every device and account involved banned."),
    Sec2("Terms and changes", BootIcons.Terms, "Updates to these terms",
        "If these terms change, VRC-A shows them again the next time you open it, and you can read what's new before accepting."),
)

@Composable
private fun ReaderBody(s: Sec2, wide: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        val lead = @Composable {
            Text(s.lead, style = MaterialTheme.typography.bodyLarge, color = G.Text, lineHeight = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp))
        }
        // An image sits beside the paragraph it belongs to (above it on a narrow screen).
        if (s.image != null && wide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { lead() }
            SecImage(s.image, Modifier.weight(1f).aspectRatio(4f / 3f))
        } else {
            s.image?.let { SecImage(it, Modifier.fillMaxWidth().aspectRatio(16f / 10f)) }
            lead()
        }
        if (s.points.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.points.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.Raised).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).background(G.Accent, CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Text(p, style = MaterialTheme.typography.bodyLarge, color = G.Text)
                }
            }
        }
        s.callout?.let { (mark, text) ->
            val tone = if (mark == com.vrca.app.Mark.Failed) G.Negative else G.Caution
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tone.copy(alpha = 0.10f))
                    .border(1.dp, tone.copy(alpha = 0.45f), RoundedCornerShape(14.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.vrca.app.BootCat(mark, 0, Modifier.size(30.dp))
                Spacer(Modifier.width(12.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = G.Text)
            }
        }
    }
}

@Composable
private fun ReaderTitle(i: Int, s: Sec2, total: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("SECTION ${i + 1} OF $total", style = MaterialTheme.typography.labelMedium, color = G.Accent, letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp))
        Text(s.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = G.Text)
        Text(s.short, style = MaterialTheme.typography.bodyLarge, color = G.TextMuted)
    }
}

@Composable
private fun ListRow2(i: Int, s: Sec2, selected: Boolean, read: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) G.Raised else G.Card)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) G.Accent else G.Edge, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BootIconView(s.icon, { G.Accent }, { G.Accent }, Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = G.Text, maxLines = 1)
            Text(s.short, style = MaterialTheme.typography.bodySmall, color = G.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (read) com.vrca.app.BootCat(com.vrca.app.Mark.Done, 0, Modifier.size(20.dp))
    }
}

@Composable
private fun Progress(read: Int, total: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text("Read $read of $total", style = MaterialTheme.typography.labelMedium, color = G.TextMuted, modifier = Modifier.weight(1f))
            Text("~3 min", style = MaterialTheme.typography.labelMedium, color = G.TextMuted)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(G.Raised)) {
            Box(Modifier.fillMaxWidth(read / total.toFloat()).height(4.dp).background(G.Accent))
        }
    }
}

@Composable
internal fun TosMockB2(open: Int = 2) {
    val total = SECTIONS2.size
    BoxWithConstraints(Modifier.fillMaxSize().background(G.Backdrop)) {
        val wide = maxWidth >= 700.dp
        Column(Modifier.fillMaxSize()) {
            if (wide) Row(Modifier.weight(1f).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.width(310.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Header(compact = true)
                    Spacer(Modifier.height(2.dp))
                    Progress(open, total)
                    Spacer(Modifier.height(2.dp))
                    SECTIONS2.forEachIndexed { i, s -> ListRow2(i, s, selected = i == open, read = i < open) }
                }
                com.vrca.app.Layer(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            ReaderTitle(open, SECTIONS2[open], total)
                            ReaderBody(SECTIONS2[open], wide = true)
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            com.vrca.ui.common.GremlinButton("Back", {}, kind = com.vrca.ui.common.GremlinButtonKind.Quiet, size = GremlinButtonSize.Medium)
                            Spacer(Modifier.weight(1f))
                            com.vrca.ui.common.GremlinButton(
                                "Next: ${SECTIONS2[open + 1].title}", {}, kind = com.vrca.ui.common.GremlinButtonKind.Secondary,
                                size = GremlinButtonSize.Medium
                            )
                        }
                    }
                }
            } else Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Header(compact = false)
                Progress(open, total)
                SECTIONS2.forEachIndexed { i, s ->
                    if (i == open) com.vrca.app.Layer(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            ReaderTitle(i, s, total)
                            ReaderBody(s, wide = false)
                        }
                    } else ListRow2(i, s, selected = false, read = i < open)
                }
            }
            AcceptBar(compact = !wide)
        }
    }
}

// ---------------------------------------------------------------- B, round 3 (user): no gap under the list,
// tick + Accept only on the last section, every section must be gone through (later ones locked), no
// time estimate, the boot's segmented bar with its shine, and boot cats on the cards: thinking on the one
// being read, asleep on the ones not reached, happy once read; the hop + sweep when everything's agreed.

@Composable
private fun TermsBar(marks: List<com.vrca.app.Mark>, done: Boolean) {
    val accent = G.Accent
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "tp").animateFloat(
        0.45f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "tpa"
    )
    val sweep = remember { androidx.compose.animation.core.Animatable(-2f) }
    val chipPop = remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(done) {
        if (!done) { sweep.snapTo(-2f); return@LaunchedEffect }
        sweep.snapTo(-1f)
        kotlinx.coroutines.coroutineScope {
            launch { kotlinx.coroutines.delay(480); chipPop.animateTo(1.18f, androidx.compose.animation.core.tween(140)); chipPop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.4f)) }
            sweep.animateTo(marks.size + 1f, androidx.compose.animation.core.tween(650, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }
    val tone by androidx.compose.animation.animateColorAsState(if (done) G.Positive else accent, label = "tt")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).height(6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            marks.forEachIndexed { i, m ->
                val seg = androidx.compose.animation.animateColorAsState(
                    when (m) { com.vrca.app.Mark.Done -> G.Positive; com.vrca.app.Mark.Working -> accent; else -> G.Raised }, label = "seg"
                )
                val working = m == com.vrca.app.Mark.Working
                fun glow() = (1f - kotlin.math.abs(sweep.value - i - 0.5f)).coerceIn(0f, 1f)
                Box(
                    Modifier.weight(1f).fillMaxHeight().graphicsLayer { scaleY = 1f + glow() * 0.6f }
                        .drawBehind {
                            val base = seg.value
                            val a = if (working) pulse.value else 1f
                            drawRoundRect(androidx.compose.ui.graphics.lerp(base.copy(alpha = base.alpha * a), G.Text, glow() * 0.75f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2, size.height / 2))
                        }
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "v4", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = tone,
            modifier = Modifier.graphicsLayer { scaleX = chipPop.value; scaleY = chipPop.value }
                .background(tone.copy(alpha = 0.14f), CircleShape).padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun ListRow3(i: Int, s: Sec2, mark: com.vrca.app.Mark, selected: Boolean, locked: Boolean, celebrate: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) G.Raised else G.Card)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) G.Accent else G.Edge, RoundedCornerShape(14.dp))
            .then(if (locked) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BootIconView(s.icon, { if (locked) G.TextMuted else G.Accent }, { if (locked) G.TextMuted else G.Accent }, Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (locked) G.TextMuted else G.Text, maxLines = 1)
            Text(s.short, style = MaterialTheme.typography.bodyMedium, color = G.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        com.vrca.app.BootCat(mark, i, Modifier.size(24.dp), celebrate = celebrate, hopDelayMs = 250L + 130L * i)
    }
}


@Composable
internal fun TosMockB3() {
    val total = SECTIONS2.size
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var reached by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var agreed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // Read = passed with Next; the furthest one reached is being read (thinking); later ones are asleep.
    val marks = List(total) { i ->
        when {
            agreed || i < reached -> com.vrca.app.Mark.Done
            i == reached -> com.vrca.app.Mark.Working
            else -> com.vrca.app.Mark.Waiting
        }
    }
    fun go(i: Int) { open = i; if (i > reached) reached = i }
    BoxWithConstraints(Modifier.fillMaxSize().background(G.Backdrop)) {
        val wide = maxWidth >= 700.dp
        val last = open == total - 1
        val reader: @Composable (Boolean) -> Unit = { w ->
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                ReaderTitle(open, SECTIONS2[open], total)
                ReaderBody(SECTIONS2[open], wide = w)
            }
        }
        val footer: @Composable () -> Unit = {
            if (last) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.Raised).clickable { agreed = !agreed }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(agreed, { agreed = it }, colors = CheckboxDefaults.colors(checkedColor = G.Accent, uncheckedColor = G.TextMuted))
                    Text("I've read and agree to these terms", style = MaterialTheme.typography.bodyLarge, color = G.Text)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.vrca.ui.common.GremlinButton("Back", { go(open - 1) }, kind = com.vrca.ui.common.GremlinButtonKind.Quiet, size = GremlinButtonSize.Medium)
                    Spacer(Modifier.weight(1f))
                    GremlinButton("Accept", {}, enabled = agreed, size = GremlinButtonSize.Large, bootIcon = BootIcons.Start)
                }
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                if (open > 0) com.vrca.ui.common.GremlinButton("Back", { go(open - 1) }, kind = com.vrca.ui.common.GremlinButtonKind.Quiet, size = GremlinButtonSize.Medium)
                Spacer(Modifier.weight(1f))
                com.vrca.ui.common.GremlinButton("Next: ${SECTIONS2[open + 1].title}", { go(open + 1) }, size = GremlinButtonSize.Medium)
            }
        }
        if (wide) Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.width(310.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Header(compact = true, mark = if (agreed) com.vrca.app.Mark.Done else com.vrca.app.Mark.Working)
                TermsBar(marks, done = agreed)
                // The list fills the panel: each card takes an equal share of the height.
                SECTIONS2.forEachIndexed { i, s ->
                    ListRow3(i, s, marks[i], selected = i == open, locked = i > reached, celebrate = agreed, modifier = Modifier.weight(1f)) { go(i) }
                }
            }
            com.vrca.app.Layer(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp)) { reader(true) }
                    Box(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) { footer() }
                }
            }
        } else Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Header(compact = false, mark = if (agreed) com.vrca.app.Mark.Done else com.vrca.app.Mark.Working)
            TermsBar(marks, done = agreed)
            SECTIONS2.forEachIndexed { i, s ->
                if (i == open) com.vrca.app.Layer(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        reader(false)
                        footer()
                    }
                } else ListRow3(i, s, marks[i], selected = false, locked = i > reached, celebrate = agreed, modifier = Modifier.height(58.dp)) { go(i) }
            }
        }
    }
}
