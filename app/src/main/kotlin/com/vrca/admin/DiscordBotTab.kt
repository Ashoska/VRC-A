package com.vrca.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrca.discordbot.ChannelInfoStore
import com.vrca.discordbot.ChannelMemoryStore
import com.vrca.discordbot.DayLogStore
import com.vrca.discordbot.DiscordBotService
import com.vrca.discordbot.DiscordBotState
import com.vrca.discordbot.DiscordBotStore
import com.vrca.discordbot.PersonalityStore
import com.vrca.discordbot.ServerMemoryStore
import com.vrca.discordbot.UserMemoryStore
import com.vrca.discordbot.discordRelTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Admin surface for Cardinal: a status strip that's always visible (state, account, mood, budget, start/stop)
 * and sub-tabs — People (typed memory cards, every item removable), Cardinal (traits by kind), Server (inside
 * jokes + channel bits), Days, Traces, Cost, Settings. Labels only, no explanation text.
 */
@Composable
internal fun DiscordBotTab() {
    var sub by remember { mutableIntStateOf(0) }
    val tabs = listOf("People", "Cardinal", "Server", "Days", "Traces", "Cost", "Settings")
    LazyColumn(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { StatusStrip() }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tabs.forEachIndexed { i, label -> FilterChip(selected = sub == i, onClick = { sub = i }, label = { Text(label) }) }
            }
        }
        item {
            when (sub) {
                0 -> PeopleSection()
                1 -> CardinalSection()
                2 -> ServerSection()
                3 -> DaysSection()
                4 -> TracesSection()
                5 -> CostSection()
                else -> SettingsSection()
            }
        }
    }
}

// ── Status strip ──────────────────────────────────────────────────────────────
@Composable
private fun StatusStrip() {
    val ctx = LocalContext.current
    val status by DiscordBotState.statusFlow.collectAsState()
    val detail by DiscordBotState.detailFlow.collectAsState()
    val botName by DiscordBotState.botNameFlow.collectAsState()
    val mood by DiscordBotState.moodFlow.collectAsState()
    val seen by DiscordBotState.seenFlow.collectAsState()
    val replied by DiscordBotState.repliedFlow.collectAsState()
    val reacted by DiscordBotState.reactedFlow.collectAsState()
    val rung by DiscordBotState.rungFlow.collectAsState()
    val neurons by DiscordBotState.neuronsFlow.collectAsState()
    val running = status != DiscordBotState.Status.IDLE && status != DiscordBotState.Status.FAILED
    val tone = when (status) {
        DiscordBotState.Status.CONNECTED -> AdminTone.Success
        DiscordBotState.Status.CONNECTING, DiscordBotState.Status.RECONNECTING -> AdminTone.Warn
        DiscordBotState.Status.FAILED -> AdminTone.Error
        DiscordBotState.Status.IDLE -> AdminTone.Neutral
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(botName.ifBlank { "Cardinal" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusPill(status.name.lowercase().replaceFirstChar { it.uppercase() }, tone)
                if (running) IconButton(onClick = { DiscordBotService.stop(ctx) }) { Icon(Icons.Filled.Stop, "Stop", tint = MaterialTheme.colorScheme.error) }
                else IconButton(onClick = { DiscordBotStore.setEnabled(ctx, true); DiscordBotService.start(ctx) }) { Icon(Icons.Filled.PlayArrow, "Start", tint = MaterialTheme.colorScheme.primary) }
            }
            if (detail.isNotBlank() && status != DiscordBotState.Status.CONNECTED) Small(detail)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Seen", seen.toString()); Stat("Replied", replied.toString()); Stat("Reacted", reacted.toString())
                Stat("Neurons", "$neurons/${DiscordBotState.budget()}"); Stat("Rung", rung.name.lowercase())
            }
            if (mood.isNotBlank()) Small("mood: $mood")
        }
    }
}

// ── People ────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeopleSection() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val cards = remember(tick, query) { UserMemoryStore.search(ctx, query) }
    var open by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.weight(1f))
            IconButton(onClick = { tick++ }) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        Small("${cards.size} people")
        cards.take(80).forEach { card ->
            val isOpen = open == card.id
            Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { open = if (isOpen) null else card.id }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(card.name.ifBlank { card.id }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            val line = listOfNotNull(
                                card.pronouns.ifBlank { null },
                                UserMemoryStore.tzLine(card, now).substringBefore(',').ifBlank { null },
                                discordRelTime(card.lastSeenMs, now).ifBlank { null }?.let { "seen $it" },
                                UserMemoryStore.factLines(card).size.takeIf { it > 0 }?.let { "$it notes" },
                            ).joinToString(" · ")
                            if (line.isNotBlank()) Small(line)
                        }
                        if (card.pinned) Icon(Icons.Filled.PushPin, "pinned", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Icon(if (isOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (isOpen) PersonDetail(card) { tick++ }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonDetail(card: UserMemoryStore.Card, changed: () -> Unit) {
    val ctx = LocalContext.current
    val now = System.currentTimeMillis()
    var add by remember(card.id) { mutableStateOf("") }
    var rel by remember(card.id) { mutableStateOf(card.relationship) }
    var treat by remember(card.id) { mutableStateOf(card.howToTreat) }
    var calls by remember(card.id) { mutableStateOf(card.preferredNick) }
    var confirmDelete by remember(card.id) { mutableStateOf(false) }

    Header("Identity")
    Removable("pronouns", card.pronouns) { UserMemoryStore.clearPronouns(ctx, card.id); changed() }
    Removable("time", UserMemoryStore.tzLine(card, now).let { if (it.isBlank()) "" else "$it (${card.tz})" }) { UserMemoryStore.clearTz(ctx, card.id); changed() }
    val langs = UserMemoryStore.spokenLanguages(card)
    if (langs.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
        Key("speaks")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { langs.forEach { l -> Chip(l) { UserMemoryStore.removeLanguage(ctx, card.id, l); changed() } } }
    }
    val nicks = card.nicknames.filterNot { it.equals(card.preferredNick, true) }
    if (nicks.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
        Key("nicknames")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { nicks.forEach { n -> Chip(n) { UserMemoryStore.removeNick(ctx, card.id, n); changed() } } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Small("replied ${card.interactions}×"); discordRelTime(card.lastSeenMs, now).takeIf { it.isNotBlank() }?.let { Small("· last message $it") }
    }

    Header("Notes")
    val any = UserMemoryStore.SLOTS.any { card.notes[it].orEmpty().isNotEmpty() }
    if (!any) Small("—")
    UserMemoryStore.SLOTS.forEach { slot ->
        val vals = card.notes[slot].orEmpty()
        if (vals.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Key(UserMemoryStore.LABEL[slot] ?: slot)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                vals.forEach { v -> Chip(v) { UserMemoryStore.removeNote(ctx, card.id, slot, v, force = true); changed() } }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = add, onValueChange = { add = it }, label = { Text("slot: value") }, singleLine = true, modifier = Modifier.weight(1f))
        Button(onClick = { if (add.isNotBlank()) { UserMemoryStore.noteFromText(ctx, card.id, card.name, add); add = ""; changed() } }) { Text("Add") }
    }
    if (card.avoid.isNotEmpty()) {
        Header("Asked to stop")
        card.avoid.forEach { a -> Removable("", a) { UserMemoryStore.removeAvoid(ctx, card.id, a); changed() } }
    }
    if (card.bits.isNotEmpty()) { Header("Bits"); card.bits.forEach { Small("• $it") } }

    Header("Edit")
    OutlinedTextField(value = rel, onValueChange = { rel = it }, label = { Text("role here") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = calls, onValueChange = { calls = it }, label = { Text("calls them") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = treat, onValueChange = { treat = it }, label = { Text("with them") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { UserMemoryStore.editFields(ctx, card.id, rel, treat, calls); changed() }) { Text("Save") }
        TextButton(onClick = { UserMemoryStore.setPinned(ctx, card.id, !card.pinned); changed() }) { Text(if (card.pinned) "Unpin" else "Pin") }
        Spacer(Modifier.weight(1f))
        if (confirmDelete) TextButton(onClick = { UserMemoryStore.delete(ctx, card.id); changed() }) { Text("Confirm delete", color = MaterialTheme.colorScheme.error) }
        else TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
    }
    Text(card.id, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ── Cardinal ─────────────────────────────────────────────────────────────────
@Composable
private fun CardinalSection() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val self = remember(tick) { PersonalityStore.load(ctx) }
    var teach by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    val kinds = listOf("title" to "Titles", "bit" to "Bits", "taste" to "Tastes", "habit" to "Habits", "" to "Other")

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(PersonalityStore.ANCHOR, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { tick++ }) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        if (self.mood.isNotBlank()) Removable("mood", self.mood) {}
        if (self.traits.isEmpty()) Small("No traits yet")
        kinds.forEach { (k, label) ->
            val list = self.traits.filter { (it.kind.ifBlank { "" }) == k || (k == "" && it.kind !in setOf("title", "bit", "taste", "habit")) }
                .sortedByDescending { (if (it.pinned) 100 else 0) + it.strength }
            if (list.isEmpty()) return@forEach
            Header("$label (${list.size})")
            list.forEach { t ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { PersonalityStore.setTraitPinned(ctx, t.text, !t.pinned); tick++ }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.PushPin, if (t.pinned) "Unpin" else "Pin", modifier = Modifier.size(18.dp),
                            tint = if (t.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(t.text, style = MaterialTheme.typography.bodyMedium)
                        Small("strength ${t.strength}" + (discordRelTime(t.lastMs, System.currentTimeMillis()).takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
                            (if (t.was.isNotBlank()) " · was ${t.was.substringAfterLast(';').trim()}" else ""))
                    }
                    IconButton(onClick = { PersonalityStore.removeTrait(ctx, t.text); tick++ }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (self.style.isNotEmpty()) { Header("Speech"); self.style.forEach { Small("• $it") } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = teach, onValueChange = { teach = it }, label = { Text("Add trait") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { if (teach.isNotBlank()) { PersonalityStore.teachTrait(ctx, teach); teach = ""; tick++ } }) { Text("Add") }
        }
        if (confirmReset) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { PersonalityStore.reset(ctx); confirmReset = false; tick++ },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Reset all") }
            TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
        } else TextButton(onClick = { confirmReset = true }) { Text("Reset personality", color = MaterialTheme.colorScheme.error) }
        Header("Sent with replies")
        Mono(PersonalityStore.snapshot(ctx).ifBlank { "—" })
    }
}

// ── Server (inside jokes + channel bits) ─────────────────────────────────────
@Composable
private fun ServerSection() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val mems = remember(tick) { ServerMemoryStore.list(ctx) }
    val chBits = remember(tick) { ChannelMemoryStore.listAll(ctx) }
    var teach by remember { mutableStateOf("") }
    val now = System.currentTimeMillis()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Header("Inside jokes (${mems.size})"); Spacer(Modifier.weight(1f))
            IconButton(onClick = { tick++ }) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        if (mems.isEmpty()) Small("—")
        mems.forEach { m ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.text, style = MaterialTheme.typography.bodySmall)
                    Small("strength ${m.strength} · ${discordRelTime(m.lastMs, now)}")
                }
                IconButton(onClick = { ServerMemoryStore.delete(ctx, m.text); tick++ }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = teach, onValueChange = { teach = it }, label = { Text("Add") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { if (teach.isNotBlank()) { ServerMemoryStore.teach(ctx, teach); teach = ""; tick++ } }) { Text("Add") }
        }
        Header("Channel bits (${chBits.values.sumOf { it.size }})")
        if (chBits.isEmpty()) Small("—")
        chBits.forEach { (channelId, bits) ->
            Text(ChannelInfoStore.name(channelId)?.let { "#$it" } ?: channelId, style = MaterialTheme.typography.labelLarge)
            bits.forEach { b ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.text, style = MaterialTheme.typography.bodySmall)
                        Small("strength ${b.strength} · ${discordRelTime(b.lastMs, now)}")
                    }
                    IconButton(onClick = { ChannelMemoryStore.delete(ctx, channelId, b.text); tick++ }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

// ── Days ───────────────────────────────────────────────────────────────────────
@Composable
private fun DaysSection() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val days = remember(tick) { DayLogStore.days(ctx) }
    val today = DayLogStore.dateOf(System.currentTimeMillis())
    val open = remember { mutableStateMapOf<String, Boolean>() }
    var confirmClear by remember { mutableStateOf(false) }
    val timeFmt = remember { java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneOffset.UTC) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Header("Days (${days.size}, UTC)"); Spacer(Modifier.weight(1f))
            IconButton(onClick = { tick++ }) { Icon(Icons.Filled.Refresh, "Refresh") }
        }
        if (days.isEmpty()) Small("—")
        days.forEachIndexed { i, day ->
            val k = day.date.toString()
            val expanded = open[k] ?: (i < 2)
            Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { open[k] = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(DayLogStore.label(day.date, today), style = MaterialTheme.typography.titleSmall)
                            Small("${day.entries.count { it.moment }} moments · ${day.entries.count { !it.moment }} topics" + if (day.digest.isNotBlank()) " · recap" else "")
                        }
                        IconButton(onClick = { DayLogStore.delete(ctx, day.date); tick++ }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Delete, "Delete day", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        }
                        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                    }
                    if (expanded) {
                        if (day.digest.isNotBlank()) Text(day.digest, style = MaterialTheme.typography.bodySmall)
                        day.entries.sortedBy { it.atMs }.forEach { e ->
                            Text((if (e.moment) "★ " else "· ") + "${timeFmt.format(java.time.Instant.ofEpochMilli(e.atMs))} #${e.channel.removePrefix("#")} ${e.text}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (e.moment) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (days.isNotEmpty()) {
            if (confirmClear) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { DayLogStore.clear(ctx); confirmClear = false; tick++ },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Clear all") }
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            } else TextButton(onClick = { confirmClear = true }) { Text("Clear day log", color = MaterialTheme.colorScheme.error) }
        }
    }
}

// ── Traces ─────────────────────────────────────────────────────────────────────
@Composable
private fun TracesSection() {
    val traces by DiscordBotState.tracesFlow.collectAsState()
    val log by DiscordBotState.logFlow.collectAsState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Header("Decisions")
        if (traces.isEmpty()) Small("—")
        traces.reversed().take(40).forEach { t ->
            Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Small(fmt.format(Date(t.atMs))); Text(t.author, style = MaterialTheme.typography.labelLarge)
                        StatusPill(t.action, when (t.action) { "reply" -> AdminTone.Success; "react" -> AdminTone.Info; "ignore", "quiet" -> AdminTone.Neutral; else -> AdminTone.Warn })
                        Small("${t.score} · ${t.plan}")
                    }
                    if (t.detail.isNotBlank()) Text(t.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Header("Log")
        Mono(if (log.isEmpty()) "—" else log.takeLast(30).reversed().joinToString("\n"))
    }
}

// ── Cost ─────────────────────────────────────────────────────────────────────
@Composable
private fun CostSection() {
    val neurons by DiscordBotState.neuronsFlow.collectAsState()
    val rung by DiscordBotState.rungFlow.collectAsState()
    val replied by DiscordBotState.repliedFlow.collectAsState()
    val reacted by DiscordBotState.reactedFlow.collectAsState()
    val budget = DiscordBotState.budget()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AdminLabeledRow("Neurons today", "$neurons / $budget")
        AdminLabeledRow("Used", if (budget > 0) "${(neurons * 100 / budget)}%" else "—")
        AdminLabeledRow("Rung", rung.name.lowercase())
        AdminLabeledRow("Replies / reactions", "$replied / $reacted")
        AdminLabeledRow("Per reply", if (replied > 0) "%.1f".format(neurons.toDouble() / replied) else "—")
    }
}

// ── Settings (behaviour, budget, channels, secrets) ────────────────────────────
@Composable
private fun SettingsSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = remember { DiscordBotStore.load(ctx) }
    var shadow by remember { mutableStateOf(cfg.shadowMode) }
    var ambient by remember { mutableStateOf(cfg.ambientPercent.toString()) }
    var cooldown by remember { mutableStateOf(cfg.ambientCooldownSec.toString()) }
    var turns by remember { mutableStateOf(cfg.contextTurns.toString()) }
    var muteId by remember { mutableStateOf("") }
    var muted by remember { mutableStateOf(cfg.mutedChannels) }
    var budget by remember { mutableStateOf(cfg.dailyBudget.toString()) }
    var trimOn by remember { mutableStateOf(cfg.trimEnabled) }
    var cheapOn by remember { mutableStateOf(cfg.cheapEnabled) }
    var reactOn by remember { mutableStateOf(cfg.reactOnlyEnabled) }
    var stopOn by remember { mutableStateOf(cfg.hardStopEnabled) }
    var botToken by remember { mutableStateOf(cfg.botToken) }
    var cfAccount by remember { mutableStateOf(cfg.cfAccountId) }
    var cfToken by remember { mutableStateOf(cfg.cfApiToken) }
    var cfGateway by remember { mutableStateOf(cfg.cfGatewayId) }
    var analytics by remember { mutableStateOf(cfg.analyticsToken) }
    var model by remember { mutableStateOf(cfg.model) }
    var savedAt by remember { mutableStateOf("") }

    fun pushLadder() {
        val b = budget.toLongOrNull()?.coerceAtLeast(100L) ?: DiscordBotStore.DEFAULT_DAILY_BUDGET
        DiscordBotStore.setDailyBudget(ctx, b)
        DiscordBotStore.setLadderRungEnabled(ctx, DiscordBotState.Rung.TRIM, trimOn)
        DiscordBotStore.setLadderRungEnabled(ctx, DiscordBotState.Rung.CHEAP, cheapOn)
        DiscordBotStore.setLadderRungEnabled(ctx, DiscordBotState.Rung.REACT_ONLY, reactOn)
        DiscordBotStore.setLadderRungEnabled(ctx, DiscordBotState.Rung.SILENT, stopOn)
        DiscordBotState.configureLadder(b, trimOn, cheapOn, reactOn, stopOn)
    }
    fun saveAll(section: String) {
        DiscordBotStore.save(ctx, botToken, cfAccount, cfToken, cfGateway, analytics, model,
            ambient.toIntOrNull() ?: DiscordBotStore.DEFAULT_AMBIENT_PCT,
            cooldown.toIntOrNull() ?: DiscordBotStore.DEFAULT_AMBIENT_COOLDOWN_SEC,
            turns.toIntOrNull() ?: DiscordBotStore.DEFAULT_CONTEXT_TURNS)
        savedAt = section; restartIfRunning(ctx, scope)
    }
    val num = KeyboardOptions(keyboardType = KeyboardType.Number)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Header("Behaviour")
        ToggleRow("Shadow mode", shadow) { shadow = it; DiscordBotStore.setShadowMode(ctx, it); restartIfRunning(ctx, scope) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(ambient, { ambient = it.filter(Char::isDigit).take(3) }, label = { Text("Ambient %") }, keyboardOptions = num, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(cooldown, { cooldown = it.filter(Char::isDigit).take(5) }, label = { Text("Cooldown s") }, keyboardOptions = num, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(turns, { turns = it.filter(Char::isDigit).take(2) }, label = { Text("Context") }, keyboardOptions = num, singleLine = true, modifier = Modifier.weight(1f))
        }
        Button(onClick = { saveAll("behaviour") }, modifier = Modifier.fillMaxWidth()) { Text(if (savedAt == "behaviour") "Saved" else "Save") }

        Header("Budget")
        OutlinedTextField(budget, { budget = it.filter(Char::isDigit).take(8) }, label = { Text("Daily neurons") }, keyboardOptions = num, singleLine = true, modifier = Modifier.fillMaxWidth())
        ToggleRow("Trim at 80%", trimOn) { trimOn = it; pushLadder() }
        ToggleRow("8B replies at 92%", cheapOn) { cheapOn = it; pushLadder() }
        ToggleRow("React only at 99%", reactOn) { reactOn = it; pushLadder() }
        ToggleRow("Stop at 100%", stopOn) { stopOn = it; pushLadder() }
        Button(onClick = { pushLadder(); savedAt = "budget" }, modifier = Modifier.fillMaxWidth()) { Text(if (savedAt == "budget") "Saved" else "Save budget") }

        Header("Muted channels")
        muted.forEach { id ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(ChannelInfoStore.name(id)?.let { "#$it" } ?: id, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { muted = DiscordBotStore.toggleMutedChannel(ctx, id); restartIfRunning(ctx, scope) }) { Icon(Icons.Filled.Close, "Unmute") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(muteId, { muteId = it }, label = { Text("Channel id") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { if (muteId.isNotBlank()) { muted = DiscordBotStore.toggleMutedChannel(ctx, muteId); muteId = ""; restartIfRunning(ctx, scope) } }) { Text("Mute") }
        }

        Header("Connection")
        Secret("Discord bot token", botToken) { botToken = it }
        OutlinedTextField(cfAccount, { cfAccount = it }, label = { Text("Cloudflare account id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Secret("Workers AI token", cfToken) { cfToken = it }
        OutlinedTextField(cfGateway, { cfGateway = it }, label = { Text("AI Gateway id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Secret("Analytics token", analytics) { analytics = it }
        OutlinedTextField(model, { model = it }, label = { Text("Reply model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { saveAll("connection") }, modifier = Modifier.fillMaxWidth()) { Text(if (savedAt == "connection") "Saved" else "Save connection") }
    }
}

// ── small shared bits ─────────────────────────────────────────────────────────
private fun restartIfRunning(ctx: android.content.Context, scope: kotlinx.coroutines.CoroutineScope) {
    if (DiscordBotState.isRunning) scope.launch {
        DiscordBotService.stop(ctx); delay(1200)
        DiscordBotStore.setEnabled(ctx, true); DiscordBotService.start(ctx)
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Small(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Header(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun Key(text: String) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(76.dp))

@Composable
private fun Mono(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())

/** "key  value  ✕" — hidden when the value is blank. */
@Composable
private fun Removable(key: String, value: String, onRemove: () -> Unit) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (key.isNotBlank()) Key(key)
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Close, "Remove", modifier = Modifier.size(16.dp)) }
    }
}

/** A value chip with its own remove tap target. */
@Composable
private fun Chip(text: String, onRemove: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), shape = MaterialTheme.shapes.small) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            IconButton(onClick = onRemove, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Filled.Close, "Remove $text", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Secret(label: String, value: String, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())

