package com.vrca.app

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrca.ui.common.GremlinButton
import com.vrca.ui.common.GremlinButtonSize
import com.vrca.ui.common.animatedOutline
import com.vrca.ui.theme.GremlinColors as G
import kotlinx.coroutines.launch

private class TermsSection(
    val title: String, val icon: BootIcon, val short: String,
    val lead: String, val points: List<String> = emptyList(), val image: String? = null,
    val after: String? = null, val callout: Pair<Mark, String>? = null,
)

// No pictures for now (user, 2026-10-31): they come back once the UI revamp is done. While no section names one,
// boot skips downloading the terms' pictures ([TERMS_HAVE_PICTURES]).
private val TERMS_SECTIONS = listOf(
    TermsSection("Welcome to VRC-A", BootIcons.Tutorial, "What's possible with VRC-A",
        "VRC-A is a companion app for VRChat. It runs on your phone or Quest alongside VRChat and handles the things you'd normally have to stop playing for.",
        listOf("Keep your chatbox alive with cycling lines, live info, music and voice to text",
            "Show your VRChat world on your Discord profile",
            "Get alerts for friends, invites, groups and events",
            "See who's in your instance and look back at where you've been",
            "Search, clone and resize avatars")),
    TermsSection("Platform rules", BootIcons.Vrchat, "Third-party guidelines",
        "VRC-A works with VRChat and Discord, so their rules still apply to everything you do through it, on top of these terms.",
        listOf("Follow VRChat's Terms and Community Guidelines", "Follow Discord's Terms when Discord status is on",
            "Don't use the chatbox to spam, harass or impersonate anyone",
            "Don't abuse VRC-A or its servers, for example with bots, scripts or modified versions of the app")),
    TermsSection("Login Centre", BootIcons.VrchatSignIn, "Your connected accounts",
        "Signing in to VRChat powers your status, friends, instances and avatar tools. Signing in to Discord is optional and shows what you're up to on your Discord profile. Your logins are kept encrypted on this device.",
        callout = Mark.Warn to "VRC-A isn't made by VRChat. Using third-party tools with your account is at your own risk."),
    TermsSection("Data and privacy", BootIcons.Privacy, "What we store",
        "To sync your settings and keep VRC-A safe, a small amount is stored with us.",
        listOf("A random ID for this device", "Your app version and settings", "Never your VRChat password")),
    TermsSection("Bugs and reports", BootIcons.Bug, "Crash reports and fixes",
        "If VRC-A crashes, a report is sent to the developer with your device model, the app version and what went wrong. Nothing personal is in it."),
    TermsSection("Account standing", BootIcons.Account, "Moderation, warnings and bans",
        "Accounts that break these terms can be warned, and repeat or serious cases banned from VRC-A.",
        callout = Mark.Failed to "Trying to get around a ban gets every device and account involved banned."),
    TermsSection("Terms and changes", BootIcons.Terms, "Updates to these terms",
        "If these terms change, VRC-A shows them again the next time you open it, and you can read what's new before accepting."),
)

/** Whether any section shows a picture; boot only fetches the terms' pictures when one does. */
internal val TERMS_HAVE_PICTURES: Boolean get() = TERMS_SECTIONS.any { it.image != null }

@Composable
private fun Header(compact: Boolean, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The VRC-A logo (user, 2026-10-25: the boot logo lands here, so it's the logo, not Boot Cat):
        // its spot is reported once it's placed, and it shows as the gliding boot logo lands on it (read
        // in the layer, so it only repaints).
        val logo = com.vrca.ui.common.appLogoPainter()
        androidx.compose.foundation.Image(
            logo, "VRC-A",
            // About the app's top-bar logo size: smaller, the hollow "VRC-A" letters got too thin to read (user).
            Modifier.height(52.dp)
                .aspectRatio(logo.intrinsicSize.width / logo.intrinsicSize.height)
                .onGloballyPositioned { c -> c.boundsInWindow().let { if (BootReveal.termsCat.value != it) BootReveal.termsCat.value = it } }
                .graphicsLayer { alpha = BootReveal.termsCatIn.floatValue }
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Terms of Service", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = G.Text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = G.TextMuted)
        }
    }
}

@Composable
private fun TermsBar(marks: List<Mark>, done: Boolean, version: Int) {
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
                    when (m) { Mark.Done -> G.Positive; Mark.Working -> accent; else -> G.Raised }, label = "seg"
                )
                val working = m == Mark.Working
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
            "v$version", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = tone,
            modifier = Modifier.graphicsLayer { scaleX = chipPop.value; scaleY = chipPop.value }
                .background(tone.copy(alpha = 0.14f), CircleShape).padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun ListRow3(i: Int, s: TermsSection, mark: Mark, selected: Boolean, locked: Boolean, celebrate: Boolean, modifier: Modifier, onClick: () -> Unit) {
    // Selecting a card eases its face and outline over (read while drawing, so it only repaints);
    // unlocking eases its icon and text from muted to normal (a one-off change on leaves).
    val sel = androidx.compose.animation.core.animateFloatAsState(if (selected) 1f else 0f, androidx.compose.animation.core.tween(220), label = "rowSel")
    val ink by androidx.compose.animation.animateColorAsState(if (locked) G.TextMuted else G.Text, label = "rowInk")
    val icon by androidx.compose.animation.animateColorAsState(if (locked) G.TextMuted else G.Accent, label = "rowIcon")
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .drawBehind { drawRect(androidx.compose.ui.graphics.lerp(G.Card, G.Raised, sel.value)) }
            .then(Modifier.animatedOutline({ androidx.compose.ui.graphics.lerp(G.Edge, G.Accent, sel.value) }, 1.5.dp, 14.dp))
            .then(if (locked) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BootIconView(s.icon, { icon }, { icon }, Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
            Text(s.short, style = MaterialTheme.typography.bodyMedium, color = G.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BootCat(mark, i, Modifier.size(24.dp), celebrate = celebrate, hopDelayMs = 250L + 130L * i)
    }
}

@Composable
private fun ReaderTitle(i: Int, s: TermsSection, total: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("SECTION ${i + 1} OF $total", style = MaterialTheme.typography.labelMedium, color = G.Accent, letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp))
        Text(s.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = G.Text)
        Text(s.short, style = MaterialTheme.typography.bodyLarge, color = G.TextMuted)
    }
}

@Composable
private fun ReaderBody(s: TermsSection, wide: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        val lead = @Composable {
            Text(s.lead, style = MaterialTheme.typography.bodyLarge, color = G.Text, lineHeight = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp))
        }
        // An image sits beside the paragraph it belongs to (above it on a narrow screen).
        if (s.image != null && wide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { lead() }
            com.vrca.ui.onboarding.TosImage(s.image, null, Modifier.weight(1f).aspectRatio(4f / 3f))
        } else {
            s.image?.let { com.vrca.ui.onboarding.TosImage(it, null, Modifier.fillMaxWidth().aspectRatio(16f / 10f)) }
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
            val tone = if (mark == Mark.Failed) G.Negative else G.Caution
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tone.copy(alpha = 0.10f))
                    .border(1.dp, tone.copy(alpha = 0.45f), RoundedCornerShape(14.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BootCat(mark, 0, Modifier.size(30.dp))
                Spacer(Modifier.width(12.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = G.Text)
            }
        }
    }
}

/**
 * The Terms of Service (revamp 2, user's layout "B3", 2026-10-24/25): a list of sections on the left
 * (headset) or folding cards (phone), each section read in turn (later ones locked until reached), the
 * boot's segmented bar and Boot Cat marks, Back/Next together on the right, and on the last section an
 * Accept that waits [ACCEPT_WAIT] s (Boot Cat draining) with the agree line under it. Accepting plays the
 * boot's hop + sweep, then the screen fades out and [onAccept] runs. Shown right after boot on both
 * paths (new users before the tutorial, returning users when the terms changed). Pictures come from
 * TosImageStore, fetched during boot. The section copy is built in until the admin terms editor exists.
 */
@Composable
internal fun TermsScreen(version: Int, updatedAtMs: Long, onAccept: () -> Unit) {
    val total = TERMS_SECTIONS.size
    val subtitle = androidx.compose.runtime.remember(version, updatedAtMs) {
        if (updatedAtMs <= 0L) "Version $version"
        else "Version $version  ·  Updated " + java.text.SimpleDateFormat("d MMM", java.util.Locale.US).format(java.util.Date(updatedAtMs))
    }
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var reached by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var agreed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // Accepted: the cats hop and the bar sweeps green, then the screen fades out and the next one takes over.
    val leave = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(agreed) {
        if (!agreed) return@LaunchedEffect
        kotlinx.coroutines.delay(ACCEPT_CELEBRATE_MS)
        leave.animateTo(0f, androidx.compose.animation.core.tween(LEAVE_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        onAccept()
    }
    // Read = passed with Next; the furthest one reached is being read (thinking); later ones are asleep.
    val marks = List(total) { i ->
        when {
            agreed || i < reached -> Mark.Done
            i == reached -> Mark.Working
            else -> Mark.Waiting
        }
    }
    val go: (Int) -> Unit = { i -> open = i; if (i > reached) reached = i }
    BoxWithConstraints(Modifier.fillMaxSize().background(G.Backdrop)) {
        val wide = maxWidth >= 700.dp
        val fade = Modifier.graphicsLayer { alpha = leave.value; val k = 0.98f + 0.02f * leave.value; scaleX = k; scaleY = k }
        if (wide) Row(Modifier.fillMaxSize().then(fade).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.width(310.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Header(compact = true, subtitle = subtitle)
                TermsBar(marks, done = agreed, version = version)
                // The list fills the panel: each card takes an equal share of the height.
                TERMS_SECTIONS.forEachIndexed { i, s ->
                    ListRow3(i, s, marks[i], selected = i == open, locked = i > reached, celebrate = agreed, modifier = Modifier.weight(1f)) { go(i) }
                }
            }
            Layer(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize()) {
                    // Changing section: the old one fades out quickly, the new one fades in and rises a little
                    // (a one-off transition; each section has its own scroll, so a new one starts at the top).
                    // The buttons stay put outside it, so they don't blink on every Next.
                    androidx.compose.animation.AnimatedContent(
                        open, Modifier.weight(1f).fillMaxWidth(), transitionSpec = { sectionSwap() }, label = "tosSection"
                    ) { o ->
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp)) {
                            Reader(o, wide = true)
                        }
                    }
                    Box(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) { Footer(open, total, go) { if (!agreed) agreed = true } }
                }
            }
        } else {
            val scroll = rememberScrollState()
            Column(Modifier.fillMaxSize().then(fade).verticalScroll(scroll).padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Header(compact = false, subtitle = subtitle)
                TermsBar(marks, done = agreed, version = version)
                TERMS_SECTIONS.forEachIndexed { i, s ->
                    PhoneSection(i, s, marks[i], open = i == open, locked = i > reached, celebrate = agreed, total, go) { if (!agreed) agreed = true }
                }
            }
        }
    }
}

private fun androidx.compose.animation.AnimatedContentTransitionScope<Int>.sectionSwap(): androidx.compose.animation.ContentTransform {
    val down = targetState > initialState
    return (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 70)) +
        androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(260, delayMillis = 70)) { h -> if (down) h / 24 else -h / 24 })
        .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(90)))
}

@Composable
private fun Reader(o: Int, wide: Boolean) = Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
    ReaderTitle(o, TERMS_SECTIONS[o], TERMS_SECTIONS.size)
    ReaderBody(TERMS_SECTIONS[o], wide = wide)
}

/** Back + Next, or on the last section Back + Accept (countdown) with the agree line under them. */
@Composable
private fun Footer(o: Int, total: Int, go: (Int) -> Unit, onAccept: () -> Unit) {
    if (o == total - 1) Row { Spacer(Modifier.weight(1f)); UnderButtons({ go(o - 1) }, onAccept) }
    else Row(verticalAlignment = Alignment.CenterVertically) {
        // Back and Next together on the right (user: split far apart on the wide headset card they
        // felt disconnected); Back is the real button on every section, greyed out on the first.
        Spacer(Modifier.weight(1f))
        com.vrca.ui.common.GremlinButton("Back", { go(o - 1) }, kind = com.vrca.ui.common.GremlinButtonKind.Secondary, size = GremlinButtonSize.Medium, enabled = o > 0)
        Spacer(Modifier.width(10.dp))
        com.vrca.ui.common.GremlinButton("Next", { go(o + 1) }, size = GremlinButtonSize.Medium)
    }
}

/**
 * Phone: every section is one card. Closed it's the list row; opening it, the row folds away and the
 * reading unfolds under it (size animated, so the cards below slide instead of jumping), and the page
 * scrolls the opened card into view once it has grown.
 */
@Composable
private fun PhoneSection(
    i: Int, s: TermsSection, mark: Mark, open: Boolean, locked: Boolean, celebrate: Boolean, total: Int,
    go: (Int) -> Unit, onAccept: () -> Unit
) {
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    androidx.compose.runtime.LaunchedEffect(open) { if (open) { kotlinx.coroutines.delay(UNFOLD_MS.toLong()); bring.bringIntoView() } }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(bring)) {
        androidx.compose.animation.AnimatedVisibility(
            !open,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(UNFOLD_MS)) + androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(UNFOLD_MS), expandFrom = Alignment.Top),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(UNFOLD_MS / 2)) + androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(UNFOLD_MS), shrinkTowards = Alignment.Top)
        ) { ListRow3(i, s, mark, selected = false, locked = locked, celebrate = celebrate, modifier = Modifier.height(58.dp)) { go(i) } }
        androidx.compose.animation.AnimatedVisibility(
            open,
            // The reading fades in as it unfolds (a delayed fade left a blank gap mid-unfold).
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(UNFOLD_MS)) + androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(UNFOLD_MS), expandFrom = Alignment.Top),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(UNFOLD_MS / 2)) + androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(UNFOLD_MS), shrinkTowards = Alignment.Top)
        ) {
            Layer(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Reader(i, wide = false)
                    Footer(i, total, go, onAccept)
                }
            }
        }
    }
}

private const val UNFOLD_MS = 300

private const val AGREE_NOTE = "By tapping Accept you agree to these terms"

/**
 * Back + Accept, Accept waiting [ACCEPT_WAIT] s once the last section opens (user: someone speeding through
 * mustn't accept by accident): Boot Cat's outline drains like the boot Retry, and only once it has fully
 * drained (user: he vanished before finishing) does he crossfade into the Start icon and Accept wake up.
 * Remembered only while the last section is open: Back leaves it, so coming back starts the wait again (user).
 */
@Composable
private fun CountdownButtons(onBack: () -> Unit, onAccept: () -> Unit) {
    var left by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(ACCEPT_WAIT) }
    var ready by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (left > 0) { kotlinx.coroutines.delay(1000); left-- }
        kotlinx.coroutines.delay(1000) // the last second's drain
        ready = true
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        com.vrca.ui.common.GremlinButton("Back", onBack, kind = com.vrca.ui.common.GremlinButtonKind.Secondary, size = GremlinButtonSize.Large)
        Spacer(Modifier.width(10.dp))
        GremlinButton(
            // Wide enough for "Accept  5", so the pair doesn't shift as the number goes.
            if (left > 0) "Accept  $left" else "Accept", onAccept, Modifier.widthIn(min = 158.dp), enabled = ready, size = GremlinButtonSize.Large,
            leading = {
                // One box size for both, so the button doesn't change width at the swap.
                androidx.compose.animation.Crossfade(ready, Modifier.size(28.dp), label = "acceptReady") { r ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (r) BootIconView(BootIcons.Start, { G.Accent }, { G.Accent }, Modifier.size(22.dp))
                        else BootCatCountdown(left.toFloat() / ACCEPT_WAIT, Modifier.fillMaxSize(), G.Accent.copy(alpha = 0.55f))
                    }
                }
            }
        )
    }
}

@Composable
private fun UnderButtons(onBack: () -> Unit, onAccept: () -> Unit) = androidx.compose.ui.layout.Layout(
    content = {
        CountdownButtons(onBack, onAccept)
        Text(AGREE_NOTE, style = MaterialTheme.typography.bodySmall, color = G.TextMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
) { (buttons, note), c ->
    val b = buttons.measure(c.copy(minWidth = 0))
    val n = note.measure(c.copy(minWidth = b.width, maxWidth = b.width))
    val gap = 8.dp.roundToPx()
    layout(b.width, b.height + gap + n.height) { b.place(0, 0); n.place(0, b.height + gap) }
}

/** Seconds before Accept can be tapped once the last section opens. */
private const val ACCEPT_WAIT = 5

/** How long the accept celebration (hop + sweep) plays before the screen leaves, and its fade. */
private const val ACCEPT_CELEBRATE_MS = 1_150L
private const val LEAVE_MS = 260
