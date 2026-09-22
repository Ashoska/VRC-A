package com.vrca.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.vrca.discordbot.DiscordBotService
import com.vrca.discordbot.DiscordBotState
import com.vrca.discordbot.DiscordBotStore
import com.vrca.discordbot.PersonalityStore
import com.vrca.discordbot.UserMemoryStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Admin control surface for the Discord AI bot (Cardinal), redesigned as a clean sub-tab area so
 * the operator can freely browse everything the AI is doing: Dashboard (status + counts + mood),
 * Personality (self-grown, view + pin/teach/reset — never a typed persona), Users (per-user memory,
 * moderatable), Traces (why it replied/ignored), Cost (neurons + budget ladder), Controls (shadow /
 * ambient / mute), and Config (secrets). Reads [DiscordBotState] flows; secrets in [DiscordBotStore].
 */
@Composable
internal fun DiscordBotTab() {
    var sub by remember { mutableIntStateOf(0) }
    val tabs = listOf("Dashboard", "Personality", "Users", "Traces", "Cost", "Controls", "Config")

    LazyColumn(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tabs.forEachIndexed { i, label ->
                    FilterChip(selected = sub == i, onClick = { sub = i }, label = { Text(label) })
                }
            }
        }
        item {
            when (sub) {
                0 -> DashboardSection()
                1 -> PersonalitySection()
                2 -> UsersSection()
                3 -> TracesSection()
                4 -> CostSection()
                5 -> ControlsSection()
                else -> ConfigSection()
            }
        }
    }
}

// ── Dashboard ─────────────────────────────────────────────────────────────
@Composable
private fun DashboardSection() {
    val ctx = LocalContext.current
    val status by DiscordBotState.statusFlow.collectAsState()
    val detail by DiscordBotState.detailFlow.collectAsState()
    val botName by DiscordBotState.botNameFlow.collectAsState()
    val mood by DiscordBotState.moodFlow.collectAsState()
    val seen by DiscordBotState.seenFlow.collectAsState()
    val replied by DiscordBotState.repliedFlow.collectAsState()
    val reacted by DiscordBotState.reactedFlow.collectAsState()
    val rung by DiscordBotState.rungFlow.collectAsState()
    val log by DiscordBotState.logFlow.collectAsState()
    val running = status != DiscordBotState.Status.IDLE && status != DiscordBotState.Status.FAILED

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AdminSectionCard(
            title = "Cardinal",
            icon = Icons.Filled.Chat,
            tone = AdminTone.Primary,
            trailing = {
                val tone = when (status) {
                    DiscordBotState.Status.CONNECTED -> AdminTone.Success
                    DiscordBotState.Status.CONNECTING, DiscordBotState.Status.RECONNECTING -> AdminTone.Warn
                    DiscordBotState.Status.FAILED -> AdminTone.Error
                    DiscordBotState.Status.IDLE -> AdminTone.Neutral
                }
                StatusPill(status.name.lowercase().replaceFirstChar { it.uppercase() }, tone)
            }
        ) {
            if (botName.isNotBlank()) AdminLabeledRow("Account", botName)
            if (detail.isNotBlank()) Muted(detail)
            if (mood.isNotBlank()) AdminLabeledRow("Mood", mood)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat("Seen", seen.toString())
                Stat("Replied", replied.toString())
                Stat("Reacted", reacted.toString())
                Stat("Rung", rung.name)
            }
            if (running) {
                Button(
                    onClick = { DiscordBotService.stop(ctx) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop bot") }
            } else {
                Button(
                    onClick = { DiscordBotStore.setEnabled(ctx, true); DiscordBotService.start(ctx) },
                    modifier = Modifier.fillMaxWidth()
                ) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Start bot") }
            }
        }
        AdminSectionCard(title = "Recent activity", icon = Icons.Filled.History, tone = AdminTone.Neutral) {
            if (log.isEmpty()) Muted("Nothing yet.")
            else Mono(log.takeLast(16).reversed().joinToString("\n"))
        }
    }
}

// ── Personality ───────────────────────────────────────────────────────────
@Composable
private fun PersonalitySection() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val self = remember(tick) { PersonalityStore.load(ctx) }
    var teach by remember { mutableStateOf("") }

    AdminSectionCard(
        title = "Personality (self-grown)",
        icon = Icons.Filled.Face,
        tone = AdminTone.Primary,
        trailing = { IconButton(onClick = { tick++ }) { Icon(Icons.Filled.Refresh, "Refresh") } }
    ) {
        Muted("Fixed: ${PersonalityStore.ANCHOR}")
        if (self.mood.isNotBlank()) AdminLabeledRow("Mood", self.mood)
        if (self.style.isNotEmpty()) {
            Label("How he talks")
            self.style.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
        Label("Traits (tap the pin to protect from decay)")
        if (self.traits.isEmpty()) Muted("None yet — evolves from the chat every ~20 min.")
        self.traits.forEach { t ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = { PersonalityStore.setTraitPinned(ctx, t.text, !t.pinned); tick++ }) {
                    Icon(
                        Icons.Filled.PushPin, if (t.pinned) "Unpin" else "Pin",
                        tint = if (t.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("${t.text}  ·  ${t.strength}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
        if (self.episodes.isNotEmpty()) {
            Label("Remembers")
            self.episodes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = teach, onValueChange = { teach = it },
                label = { Text("Teach a trait (correction)") },
                singleLine = true, modifier = Modifier.weight(1f)
            )
            Button(onClick = { if (teach.isNotBlank()) { PersonalityStore.teachTrait(ctx, teach); teach = ""; tick++ } }) { Text("Add") }
        }
        OutlinedButton(
            onClick = { PersonalityStore.reset(ctx); tick++ },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Reset personality") }
    }
}

// ── Users (per-user memory) ─────────────────────────────────────────────────
@Composable
private fun UsersSection() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val cards = remember(tick, query) { UserMemoryStore.search(ctx, query) }
    var expanded by remember { mutableStateOf<String?>(null) }

    AdminSectionCard(
        title = "User memory (${UserMemoryStore.count(ctx)})",
        icon = Icons.Filled.Person,
        tone = AdminTone.Info,
        trailing = { IconButton(onClick = { tick++ }) { Icon(Icons.Filled.Refresh, "Refresh") } }
    ) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("Search name / fact / id") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        if (cards.isEmpty()) Muted("No memory cards yet.")
        cards.take(60).forEach { card ->
            val open = expanded == card.id
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .clickable { expanded = if (open) null else card.id }
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                card.name.ifBlank { card.id },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            val akas = (listOfNotNull(card.preferredNick.ifBlank { null }) + card.nicknames).distinct()
                            if (akas.isNotEmpty()) Muted("aka ${akas.joinToString(", ")}")
                        }
                        if (card.pinned) Icon(
                            Icons.Filled.PushPin, "pinned",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        if (card.sentiment.isNotBlank()) MemChip(card.sentiment)
                        Icon(
                            if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!open) {
                        val preview = card.relationship.ifBlank { card.facts.firstOrNull().orEmpty() }
                        if (preview.isNotBlank()) Text(
                            preview, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    } else {
                        if (card.relationship.isNotBlank()) KV("Relationship", card.relationship)
                        if (card.preferredNick.isNotBlank()) KV("Calls them", card.preferredNick)
                        if (card.language.isNotBlank())
                            KV("Language", card.language + (if (card.alsoSpeaks.isNotEmpty()) " (+ ${card.alsoSpeaks.joinToString(", ")})" else ""))
                        if (card.howToTreat.isNotBlank()) KV("With them", card.howToTreat)
                        if (card.facts.isNotEmpty()) {
                            Label("Knows")
                            card.facts.forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall) }
                        }
                        if (card.bits.isNotEmpty()) KV("Running bits", card.bits.joinToString(", "))
                        KV("Interactions", "${card.interactions}")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButtonSmall(if (card.pinned) "Unpin" else "Pin") {
                                UserMemoryStore.setPinned(ctx, card.id, !card.pinned); tick++
                            }
                            TextButtonSmall("Delete") { UserMemoryStore.delete(ctx, card.id); expanded = null; tick++ }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(end = 6.dp)
    ) {
        Text(
            text, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), maxLines = 1
        )
    }
}

@Composable
private fun KV(key: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "$key:", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("$value", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

// ── Traces (Why) ────────────────────────────────────────────────────────────
@Composable
private fun TracesSection() {
    val traces by DiscordBotState.tracesFlow.collectAsState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    AdminSectionCard(title = "Traces (why it acted)", icon = Icons.Filled.History, tone = AdminTone.Neutral) {
        if (traces.isEmpty()) Muted("No decisions recorded yet.")
        traces.reversed().take(40).forEach { t ->
            Mono("${fmt.format(Date(t.atMs))} [${t.action}] ${t.author} · ${t.score} · ${t.plan}\n  ${t.detail}")
        }
    }
}

// ── Cost ─────────────────────────────────────────────────────────────────────
@Composable
private fun CostSection() {
    val neurons by DiscordBotState.neuronsFlow.collectAsState()
    val rung by DiscordBotState.rungFlow.collectAsState()
    val replied by DiscordBotState.repliedFlow.collectAsState()
    val reacted by DiscordBotState.reactedFlow.collectAsState()
    val budget = com.vrca.discordbot.DiscordBotLimits.DAILY_NEURON_BUDGET
    AdminSectionCard(title = "Cost today", icon = Icons.Filled.Payments, tone = AdminTone.Info) {
        AdminLabeledRow("Neurons today", "$neurons / $budget")
        AdminLabeledRow("Budget rung", rung.name)
        AdminLabeledRow("Replies / reactions", "$replied / $reacted")
        Muted(
            "Ladder degrades automatically as the budget fills: FULL → TRIM (trimmed context) → " +
            "CHEAP (8B replies) → REACT_ONLY → SILENT. Resets at UTC midnight. Shows REAL Cloudflare " +
            "usage when an Analytics token is set, otherwise a persisted calibrated estimate."
        )
    }
}

// ── Controls ─────────────────────────────────────────────────────────────────
@Composable
private fun ControlsSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = remember { DiscordBotStore.load(ctx) }
    var shadow by remember { mutableStateOf(cfg.shadowMode) }
    var ambient by remember { mutableStateOf(cfg.ambientPercent.toString()) }
    var cooldown by remember { mutableStateOf(cfg.ambientCooldownSec.toString()) }
    var turns by remember { mutableStateOf(cfg.contextTurns.toString()) }
    var muteId by remember { mutableStateOf("") }
    var muted by remember { mutableStateOf(cfg.mutedChannels) }
    var saved by remember { mutableStateOf(false) }

    AdminSectionCard(title = "Controls", icon = Icons.Filled.Tune, tone = AdminTone.Neutral) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Shadow mode (compute, don't send)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = shadow, onCheckedChange = {
                shadow = it; DiscordBotStore.setShadowMode(ctx, it); restartIfRunning(ctx, scope)
            })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ambient, onValueChange = { ambient = it.filter(Char::isDigit).take(3); saved = false },
                label = { Text("Ambient %") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = cooldown, onValueChange = { cooldown = it.filter(Char::isDigit).take(5); saved = false },
                label = { Text("Cooldown (s)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = turns, onValueChange = { turns = it.filter(Char::isDigit).take(2); saved = false },
                label = { Text("Context") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.weight(1f)
            )
        }
        Button(
            onClick = {
                val c = DiscordBotStore.load(ctx)  // keep the encrypted secrets
                DiscordBotStore.save(
                    ctx, c.botToken, c.cfAccountId, c.cfApiToken, c.cfGatewayId, c.analyticsToken, c.model,
                    ambient.toIntOrNull() ?: DiscordBotStore.DEFAULT_AMBIENT_PCT,
                    cooldown.toIntOrNull() ?: DiscordBotStore.DEFAULT_AMBIENT_COOLDOWN_SEC,
                    turns.toIntOrNull() ?: DiscordBotStore.DEFAULT_CONTEXT_TURNS,
                )
                saved = true; restartIfRunning(ctx, scope)
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (saved) "Saved" else "Save controls") }

        Label("Muted channels")
        if (muted.isEmpty()) Muted("None.")
        else muted.forEach { id ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Mono(id); Spacer(Modifier.weight(1f))
                TextButtonSmall("Unmute") { muted = DiscordBotStore.toggleMutedChannel(ctx, id); restartIfRunning(ctx, scope) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = muteId, onValueChange = { muteId = it }, label = { Text("Channel id to mute") },
                singleLine = true, modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                if (muteId.isNotBlank()) { muted = DiscordBotStore.toggleMutedChannel(ctx, muteId); muteId = ""; restartIfRunning(ctx, scope) }
            }) { Text("Mute") }
        }
        Muted("Ambient % = chance to consider an unaddressed message; cooldown limits it per channel. " +
            "Context = recent messages read for a reply. Changes restart a running bot.")
    }
}

// ── Config (secrets) ──────────────────────────────────────────────────────────
@Composable
private fun ConfigSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember { DiscordBotStore.load(ctx) }
    var botToken by remember { mutableStateOf(initial.botToken) }
    var cfAccount by remember { mutableStateOf(initial.cfAccountId) }
    var cfToken by remember { mutableStateOf(initial.cfApiToken) }
    var cfGateway by remember { mutableStateOf(initial.cfGatewayId) }
    var analytics by remember { mutableStateOf(initial.analyticsToken) }
    var model by remember { mutableStateOf(initial.model) }
    var saved by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AdminSectionCard(title = "Configuration", icon = Icons.Filled.Settings, tone = AdminTone.Info) {
            OutlinedTextField(
                value = botToken, onValueChange = { botToken = it; saved = false },
                label = { Text("Discord bot token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = cfAccount, onValueChange = { cfAccount = it; saved = false },
                label = { Text("Cloudflare account id") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = cfToken, onValueChange = { cfToken = it; saved = false },
                label = { Text("Workers AI API token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = cfGateway, onValueChange = { cfGateway = it; saved = false },
                label = { Text("AI Gateway id (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = analytics, onValueChange = { analytics = it; saved = false },
                label = { Text("Analytics token (optional, real usage)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = model, onValueChange = { model = it; saved = false },
                label = { Text("Reply model") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val c = DiscordBotStore.load(ctx)
                    DiscordBotStore.save(
                        ctx, botToken, cfAccount, cfToken, cfGateway, analytics, model,
                        c.ambientPercent, c.ambientCooldownSec, c.contextTurns
                    )
                    saved = true; restartIfRunning(ctx, scope)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (saved) "Saved" else "Save configuration") }
        }
        AdminSectionCard(title = "Setup", icon = Icons.Filled.Settings, tone = AdminTone.Neutral) {
            Muted(
                "1. Discord Developer Portal → your app → Bot → enable MESSAGE CONTENT + SERVER MEMBERS " +
                "intents (message content is required; reactions ride the gateway).\n" +
                "2. Invite with the bot scope + View Channels, Send Messages, Read Message History, " +
                "Add Reactions.\n" +
                "3. Cloudflare: an account id + a scoped Workers AI (Read/Run) token. Optionally an AI " +
                "Gateway id, and an Analytics token (Account Analytics: Read) so the budget shows REAL " +
                "neuron usage instead of the estimate. Cardinal grows his own personality — no persona to type.\n" +
                "Secrets are stored encrypted on this device only."
            )
        }
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
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Muted(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Mono(text: String) = Text(
    text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
    modifier = Modifier.fillMaxWidth()
)

@Composable
private fun TextButtonSmall(label: String, onClick: () -> Unit) =
    androidx.compose.material3.TextButton(onClick = onClick) { Text(label, style = MaterialTheme.typography.labelMedium) }
