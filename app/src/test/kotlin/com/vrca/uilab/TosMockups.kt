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
private fun Header(compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        com.vrca.app.BootCat(com.vrca.app.Mark.Done, 0, Modifier.size(if (compact) 34.dp else 40.dp))
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
