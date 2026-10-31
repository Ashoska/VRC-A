package com.vrca.uilab

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrca.app.BootCat
import com.vrca.app.BootIconView
import com.vrca.app.BootIcons
import com.vrca.app.Mark
import com.vrca.ui.common.GremlinButton
import com.vrca.ui.common.GremlinButtonKind
import com.vrca.ui.common.GremlinButtonSize
import com.vrca.ui.common.GremlinChip
import com.vrca.ui.theme.GremlinColors as G

/**
 * UI Lab mock-up (revamp 2, account standing, 2026-10-31): the admin's Add / Edit action page.
 * `show modactionmock <new|edit|ban>`. Admin UI rule: labels, values and controls only, no helper text.
 * Mock data only; nothing here ships.
 */
@OptIn(ExperimentalLayoutApi::class)
object ModActionMocks {
    private val TYPES = listOf("Warning", "Restriction", "Ban")
    private val DURATIONS = listOf("1 h", "1 d", "3 d", "7 d", "30 d", "Forever")
    private val LEVELS = listOf("Auto", "All good", "Limited", "Very limited", "At risk", "Banned")
    private val REASONS = listOf("Spam", "Harassment", "Server abuse", "Ban evasion", "Modified app", "Other")
    private val FEATURES = listOf(
        "Chatbox" to listOf("All sending", "Custom text", "Manual send", "Voice to text"),
        "Avatars" to listOf("Search", "Clone", "Contributing", "Size tool"),
        "Discord" to listOf("Discord status"),
        "VRChat tools" to listOf("Alerts", "Friends", "Instance roster", "Join / invite"),
        "Account" to listOf("Extra devices", "New sign-ins", "Crash reports"),
    )

    private class Ev(val kind: String, val shown: Boolean)

    @Composable
    fun Page(arg: String) {
        val mode = arg.trim().ifBlank { "new" }
        val edit = mode == "edit"
        var type by remember { mutableStateOf(if (mode == "ban") "Ban" else "Restriction") }
        var reason by remember { mutableStateOf(if (mode == "new") "" else "Harassment") }
        var title by remember { mutableStateOf(if (mode == "new") "" else "Harassment through the chatbox") }
        var message by remember { mutableStateOf(if (mode == "new") "" else "Targeted messages at another player after being asked to stop.") }
        var adminNote by remember { mutableStateOf(if (mode == "new") "" else "Reported by 3 people in the hangout world.") }
        val off = remember { mutableStateMapOf<String, String>().apply { if (mode != "new") { put("Custom text", "3 d"); put("Clone", "30 d"); put("Search", "30 d") } } }
        var banFor by remember { mutableStateOf("Forever") }
        var banAccount by remember { mutableStateOf(true) }
        var banDevice by remember { mutableStateOf(true) }
        var banLinked by remember { mutableStateOf(false) }
        var level by remember { mutableStateOf("Auto") }
        val evidence = remember { mutableStateListOf<Ev>().apply { if (mode != "new") { add(Ev("IMG", true)); add(Ev("VID", false)); add(Ev("GIF", true)) } } }

        Box(Modifier.fillMaxSize().background(G.Backdrop)) {
            Column(Modifier.fillMaxSize()) {
                // Top bar: back, page title, the user.
                Row(Modifier.fillMaxWidth().background(G.Panel).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chevron(left = true)
                    Spacer(Modifier.width(10.dp))
                    Text(if (edit) "Edit action" else "New action", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = G.Text, modifier = Modifier.weight(1f))
                    if (edit) Text("Live", style = MaterialTheme.typography.labelMedium, color = G.Success,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(G.Success.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp))
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Target(type, level, off.size)
                        Section("Type") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TYPES.forEach { t -> GremlinChip(t, type == t, { type = t }, leading = { Dot(tone(t)) }) }
                            }
                        }
                        Section("Reason") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                REASONS.forEach { r -> GremlinChip(r, reason == r, { reason = r }) }
                            }
                            Field("Title", title, { title = it }, "Shown on the user's standing")
                            Field("Message to user", message, { message = it }, "Shown when they open it", lines = 3)
                            Field("Admin note", adminNote, { adminNote = it }, "Admins only", lines = 2, lock = true)
                        }
                        if (type == "Ban") Section("Ban") {
                            Label("Length")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                DURATIONS.drop(1).forEach { d -> GremlinChip(d, banFor == d, { banFor = d }) }
                            }
                            SwitchRow("VRChat account", banAccount) { banAccount = it }
                            SwitchRow("This device", banDevice) { banDevice = it }
                            SwitchRow("Other devices", banLinked, value = "1") { banLinked = it }
                        } else Section("Disable", trailing = if (off.isEmpty()) null else "${off.size} off") {
                            // Every feature its own switch; switched on, its own length opens under it.
                            FEATURES.forEach { (group, items) ->
                                Label(group)
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.Card)) {
                                    items.forEachIndexed { k, f ->
                                        if (k > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(G.Edge))
                                        FeatureRow(f, off[f], { on -> if (on) off[f] = "1 d" else off.remove(f) }, { d -> off[f] = d })
                                    }
                                }
                            }
                        }
                        Section("Standing") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LEVELS.forEach { l -> GremlinChip(l, level == l, { level = l }) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Label("Result", Modifier.weight(1f))
                                val result = if (level != "Auto") level else autoLevel(type, off.size)
                                Text("At risk", style = MaterialTheme.typography.bodyMedium, color = G.TextMuted)
                                Chevron(left = false, tint = G.TextMuted)
                                Text(result, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = levelTone(result))
                            }
                        }
                        Section("Evidence", trailing = if (evidence.isEmpty()) null else "${evidence.count { it.shown }} of ${evidence.size} shown") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 3) {
                                evidence.forEachIndexed { i, e -> EvidenceTile(e, Modifier.weight(1f)) { evidence[i] = Ev(e.kind, it) } }
                                AddTile(Modifier.weight(1f)) { evidence.add(Ev("IMG", false)) }
                                repeat((3 - (evidence.size + 1) % 3) % 3) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        if (edit) Section("History") {
                            Fact("Given", "29 Oct 18:04 · Ashoska")
                            Fact("Edited", "31 Oct 14:20 · Ashoska")
                            Fact("Seen by user", "31 Oct 14:22")
                            Row(Modifier.padding(top = 4.dp)) {
                                GremlinButton("Remove action", {}, kind = GremlinButtonKind.Danger, size = GremlinButtonSize.Small)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                // Bottom bar: always in reach.
                Row(Modifier.fillMaxWidth().background(G.Panel).padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    GremlinButton("Cancel", {}, kind = GremlinButtonKind.Secondary)
                    GremlinButton(if (edit) "Save changes" else if (type == "Ban") "Ban" else "Apply", {},
                        kind = if (type == "Ban") GremlinButtonKind.Danger else GremlinButtonKind.Primary)
                }
            }
        }
    }

    private fun tone(type: String) = when (type) { "Ban" -> G.Alert; "Restriction" -> G.Caution; else -> G.Warning }
    private fun levelTone(l: String) = when (l) { "All good" -> G.Success; "Limited" -> G.Warning; "Very limited" -> G.Caution; "At risk" -> G.Error; else -> G.Alert }
    private fun autoLevel(type: String, offCount: Int) = when {
        type == "Ban" -> "Banned"; offCount >= 3 -> "At risk"; offCount == 2 -> "Very limited"; offCount == 1 -> "Limited"; else -> "All good"
    }

    /** Who the action is for, and their standing now. */
    @Composable
    private fun Target(type: String, level: String, offCount: Int) = com.vrca.app.Layer(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(G.Raised), contentAlignment = Alignment.Center) {
                Text("F", style = MaterialTheme.typography.titleMedium, color = G.TextMuted)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Frosty", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = G.Text)
                Text("usr_5f1c…a93e · 2 devices", style = MaterialTheme.typography.bodySmall, color = G.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BootCat(Mark.Failed, 0, Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Text("At risk", style = MaterialTheme.typography.labelLarge, color = G.Error)
        }
    }

    @Composable
    private fun Section(title: String, trailing: String? = null, content: @Composable () -> Unit) = com.vrca.app.Layer(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = G.Text, modifier = Modifier.weight(1f))
                if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodySmall, color = G.TextMuted)
            }
            content()
        }
    }

    @Composable
    private fun Label(text: String, modifier: Modifier = Modifier) =
        Text(text, style = MaterialTheme.typography.labelLarge, color = G.TextMuted, modifier = modifier)

    @Composable
    private fun Fact(label: String, value: String) = Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = G.TextMuted, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = G.Text)
    }

    @Composable
    private fun Dot(c: Color) = Box(Modifier.size(8.dp).background(c, CircleShape))

    /** A text field in the search field's look: Raised fill, Edge outline (accent while typing). */
    @Composable
    private fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String, lines: Int = 1, lock: Boolean = false) = Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(label)
            if (lock) { Spacer(Modifier.width(6.dp)); BootIconView(BootIcons.Privacy, { G.TextMuted }, { G.TextMuted }, Modifier.size(14.dp)) }
        }
        val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val edge by animateColorAsState(if (focused) G.Accent else G.Edge, label = "fieldEdge")
        BasicTextField(
            value, onChange, interactionSource = interaction, minLines = lines, maxLines = lines + 2,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = G.Text), cursorBrush = SolidColor(G.Accent),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(G.Raised).border(1.dp, edge, RoundedCornerShape(12.dp)),
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = G.TextMuted.copy(alpha = 0.6f))
                    inner()
                }
            }
        )
    }

    /** VRC-A's own switch: a pill track with a knob, colours fading while drawing. */
    @Composable
    private fun Switch(on: Boolean, onChange: (Boolean) -> Unit) {
        val p by animateFloatAsState(if (on) 1f else 0f, label = "sw")
        Canvas(Modifier.size(width = 40.dp, height = 24.dp).clip(RoundedCornerShape(12.dp)).clickable { onChange(!on) }) {
            val r = size.height / 2f
            drawRoundRect(androidx.compose.ui.graphics.lerp(G.Raised, G.Accent.copy(alpha = 0.35f), p), cornerRadius = CornerRadius(r))
            drawRoundRect(androidx.compose.ui.graphics.lerp(G.Edge, G.Accent, p), cornerRadius = CornerRadius(r),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            val k = r - 4.dp.toPx()
            drawCircle(androidx.compose.ui.graphics.lerp(G.TextMuted, G.Text, p), k, Offset(r + (size.width - 2 * r) * p, r))
        }
    }

    @Composable
    private fun SwitchRow(label: String, on: Boolean, value: String? = null, onChange: (Boolean) -> Unit) =
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = G.Text, modifier = Modifier.weight(1f))
            if (value != null) { Text(value, style = MaterialTheme.typography.bodySmall, color = G.TextMuted); Spacer(Modifier.width(10.dp)) }
            Switch(on, onChange)
        }

    /** One feature: its switch; switched on, its own length chips open underneath. */
    @Composable
    private fun FeatureRow(name: String, length: String?, onToggle: (Boolean) -> Unit, onLength: (String) -> Unit) =
        Column(Modifier.fillMaxWidth().animateContentSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodyMedium, color = if (length != null) G.Text else G.TextMuted, modifier = Modifier.weight(1f))
                if (length != null) { Text(length, style = MaterialTheme.typography.bodySmall, color = G.Caution); Spacer(Modifier.width(10.dp)) }
                Switch(length != null, onToggle)
            }
            if (length != null) FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DURATIONS.forEach { d -> SmallChip(d, length == d) { onLength(d) } }
            }
        }

    @Composable
    private fun SmallChip(label: String, selected: Boolean, onClick: () -> Unit) {
        val edge by animateColorAsState(if (selected) G.Accent else G.Edge, label = "sc")
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) G.AccentSoft else G.TextMuted,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) G.Accent.copy(alpha = 0.16f) else G.Raised)
                .border(1.dp, edge, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp))
    }

    /** One file: its thumbnail and type, and who can see it (one switch: user + admins, or admins only). */
    @Composable
    private fun EvidenceTile(e: Ev, modifier: Modifier, onShown: (Boolean) -> Unit) = Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 11f).clip(RoundedCornerShape(10.dp)).background(G.Card)
            .border(1.dp, if (e.shown) G.Accent.copy(alpha = 0.6f) else G.Edge, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            if (e.kind == "VID") BootIconView(BootIcons.Start, { G.TextMuted }, { G.TextMuted }, Modifier.size(24.dp))
            Text(e.kind, style = MaterialTheme.typography.labelSmall, color = G.TextMuted,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(5.dp)).background(G.Raised).padding(horizontal = 5.dp, vertical = 1.dp))
            Text("✕", style = MaterialTheme.typography.labelSmall, color = G.TextMuted, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BootIconView(if (e.shown) BootIcons.Unlocked else BootIcons.Privacy, { if (e.shown) G.AccentSoft else G.TextMuted }, { if (e.shown) G.AccentSoft else G.TextMuted }, Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (e.shown) "User" else "Admin", style = MaterialTheme.typography.labelMedium, color = if (e.shown) G.AccentSoft else G.TextMuted, modifier = Modifier.weight(1f))
            Switch(e.shown, onShown)
        }
    }

    @Composable
    private fun AddTile(modifier: Modifier, onClick: () -> Unit) = Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 11f).clip(RoundedCornerShape(10.dp)).border(1.dp, G.Edge, RoundedCornerShape(10.dp)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center) {
            Text("+ Add", style = MaterialTheme.typography.labelLarge, color = G.AccentSoft)
        }
    }

    @Composable
    private fun Chevron(left: Boolean, tint: Color = G.Text) = Canvas(Modifier.size(if (left) 22.dp else 14.dp)) {
        val w = 1.8.dp.toPx(); val s = size.width
        val (a, b, c) = if (left) Triple(Offset(s * 0.62f, s * 0.22f), Offset(s * 0.32f, s * 0.5f), Offset(s * 0.62f, s * 0.78f))
            else Triple(Offset(s * 0.38f, s * 0.22f), Offset(s * 0.68f, s * 0.5f), Offset(s * 0.38f, s * 0.78f))
        drawLine(tint, a, b, w, androidx.compose.ui.graphics.StrokeCap.Round); drawLine(tint, b, c, w, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}
