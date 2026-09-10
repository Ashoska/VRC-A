package com.vrca.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.vrca.discordbot.DiscordBotService
import com.vrca.discordbot.DiscordBotState
import com.vrca.discordbot.DiscordBotStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Admin tab to configure and run the Discord AI bot ([DiscordBotService]).
 * Secrets are entered here and stored encrypted on-device ([DiscordBotStore]) — never
 * in source. Mirrors the [BotsTab] layout language (AdminSectionCard + StatusPill).
 */
@Composable
internal fun DiscordBotTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val status by DiscordBotState.statusFlow.collectAsState()
    val detail by DiscordBotState.detailFlow.collectAsState()
    val botName by DiscordBotState.botNameFlow.collectAsState()
    val log by DiscordBotState.logFlow.collectAsState()

    // Config fields, seeded once from the encrypted store.
    val initial = remember { DiscordBotStore.load(ctx) }
    var botToken by remember { mutableStateOf(initial.botToken) }
    var cfAccount by remember { mutableStateOf(initial.cfAccountId) }
    var cfToken by remember { mutableStateOf(initial.cfApiToken) }
    var cfGateway by remember { mutableStateOf(initial.cfGatewayId) }
    var model by remember { mutableStateOf(initial.model) }
    var systemPrompt by remember { mutableStateOf(initial.systemPrompt) }
    var ambientPct by remember { mutableStateOf(initial.ambientPercent.toString()) }
    var cooldown by remember { mutableStateOf(initial.ambientCooldownSec.toString()) }
    var saved by remember { mutableStateOf(false) }

    val running = status != DiscordBotState.Status.IDLE && status != DiscordBotState.Status.FAILED

    LazyColumn(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Status + Start/Stop + live log ──
        item {
            AdminSectionCard(
                title = "Discord Bot",
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
                if (detail.isNotBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) {
                        Button(
                            onClick = { DiscordBotService.stop(ctx) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop bot")
                        }
                    } else {
                        Button(
                            onClick = {
                                DiscordBotStore.setEnabled(ctx, true)
                                DiscordBotService.start(ctx)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Start bot")
                        }
                    }
                }
                // Live activity log (newest last).
                if (log.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            log.takeLast(12).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth().padding(10.dp)
                        )
                    }
                }
            }
        }

        // ── Configuration ──
        item {
            AdminSectionCard(title = "Configuration", icon = Icons.Filled.Settings, tone = AdminTone.Info) {
                OutlinedTextField(
                    value = botToken, onValueChange = { botToken = it; saved = false },
                    label = { Text("Discord bot token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = cfAccount, onValueChange = { cfAccount = it; saved = false },
                    label = { Text("Cloudflare account id") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = cfToken, onValueChange = { cfToken = it; saved = false },
                    label = { Text("Workers AI API token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = cfGateway, onValueChange = { cfGateway = it; saved = false },
                    label = { Text("AI Gateway id (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it; saved = false },
                    label = { Text("Model") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = systemPrompt, onValueChange = { systemPrompt = it; saved = false },
                    label = { Text("System prompt (persona)") },
                    minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = ambientPct, onValueChange = { ambientPct = it.filter(Char::isDigit).take(3); saved = false },
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
                }
                Text(
                    "Ambient % = chance to jump into an unaddressed message; cooldown limits how " +
                        "often it does so per channel. Mentions and replies always answer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        val pct = ambientPct.toIntOrNull() ?: DiscordBotStore.DEFAULT_AMBIENT_PCT
                        val cd = cooldown.toIntOrNull() ?: DiscordBotStore.DEFAULT_AMBIENT_COOLDOWN_SEC
                        DiscordBotStore.save(
                            ctx, botToken, cfAccount, cfToken, cfGateway, model, systemPrompt, pct, cd
                        )
                        saved = true
                        // Apply live config to a running bot by cycling it.
                        if (running) scope.launch {
                            DiscordBotService.stop(ctx)
                            delay(1200)
                            DiscordBotStore.setEnabled(ctx, true)
                            DiscordBotService.start(ctx)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (saved) "Saved" else "Save configuration") }
            }
        }

        // ── Setup checklist ──
        item {
            AdminSectionCard(title = "Setup", icon = Icons.Filled.Settings, tone = AdminTone.Neutral) {
                Text(
                    "1. Discord Developer Portal → your app → Bot → enable the MESSAGE CONTENT intent " +
                        "(required for ambient replies).\n" +
                        "2. Invite the bot with the bot scope + View Channels, Send Messages, Read " +
                        "Message History.\n" +
                        "3. Cloudflare: an account id + a scoped Workers AI (Read/Run) token. Optionally " +
                        "an AI Gateway id for cost/observability.\n" +
                        "Secrets are stored encrypted on this device only — rotate the bot token if it " +
                        "was ever shared.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
