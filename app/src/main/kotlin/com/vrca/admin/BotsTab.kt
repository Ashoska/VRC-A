package com.vrca.admin

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The "Bots" admin tab — everything for the crowdsourced avatar catalog's maintenance
 * bots: the live Worker health, the four bot login slots (each showing its account
 * name, a still-authed indicator, AND the queue + processed numbers for the role(s)
 * that bot is running), the shared ADMIN_KEY, and the full-catalog blitz button with
 * a global "to process" counter.
 */
@Composable
fun BotsTab() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("vrca_admin_local", Context.MODE_PRIVATE) }
    var adminKey by remember { mutableStateOf(prefs.getString("avatar_admin_key", "") ?: "") }

    // Track the logged-in slot set (as a signature) so the sweep is (re)started with the
    // right role assignment whenever a bot logs in/out.
    var liveSig by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            liveSig = (0 until BotVrchatSession.SLOTS)
                .joinToString(",") { if (BotVrchatSession.isLoggedIn(ctx, it)) "1" else "0" }
            delay(2000)
        }
    }
    LaunchedEffect(liveSig, adminKey) {
        delay(700)
        val key = adminKey.trim()
        if (BotVrchatSession.loggedInCount(ctx) > 0 && key.isNotBlank()) {
            AvatarCatalogSweep.stop(); AvatarCatalogSweep.start(ctx, key)
        } else {
            AvatarCatalogSweep.stop()
        }
    }

    // Central poll of the per-role views (queued backlog + processed counts). The live
    // report backlog comes from the Worker every ~12s; the fill/liveness backlogs are
    // computed locally every 2s.
    var pendingReports by remember { mutableIntStateOf(0) }
    var views by remember { mutableStateOf(AvatarCatalogSweep.roleViews(0)) }
    var blitz by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var i = 0
        while (true) {
            if (i % 6 == 0) pendingReports = runCatching {
                withContext(Dispatchers.IO) { com.vrca.vrchat.AvatarGlobalDb.pendingReportCount() }
            }.getOrDefault(pendingReports)
            views = AvatarCatalogSweep.roleViews(pendingReports)
            blitz = AvatarCatalogSweep.blitzActive()
            i++; delay(2000)
        }
    }
    val totalQueued = views.sumOf { it.queued }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
    ) {
        item { CatalogHealthCard() }
        item {
            SweepStatusCard(
                adminKey = adminKey,
                onKeyChange = { adminKey = it; prefs.edit().putString("avatar_admin_key", it).apply() },
                assignment = AvatarCatalogSweep.assignmentLabel,
                totalQueued = totalQueued,
                blitz = blitz
            )
        }
        item {
            Text(
                "Log in up to ${BotVrchatSession.SLOTS} dedicated bot accounts. Roles are split " +
                    "across them: reports, filling new avatars, and two liveness sweepers that never " +
                    "check the same avatar. Each bot below shows the queue + progress for its role.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
        items(BotVrchatSession.SLOTS) { slot ->
            BotLoginCard(slot, views.filter { it.bot == "bot ${slot + 1}" })
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

// ---- catalog health ---------------------------------------------------------

@Composable
private fun CatalogHealthCard() {
    var status by remember { mutableStateOf("loading…") }
    var entries by remember { mutableStateOf("—") }
    var pending by remember { mutableStateOf("—") }
    var totals by remember { mutableStateOf("—") }
    var lastFlush by remember { mutableStateOf("—") }
    var lastCommit by remember { mutableStateOf("—") }
    var adminKeySet by remember { mutableStateOf("—") }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        while (true) {
            runCatching {
                withContext(Dispatchers.IO) {
                    val conn = (java.net.URL("${com.vrca.vrchat.AvatarGlobalDb.WORKER_URL}/health")
                        .openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 10_000; readTimeout = 10_000
                        setRequestProperty("User-Agent", "VRC-A")
                    }
                    conn.inputStream.bufferedReader().readText()
                }
            }.onSuccess { body ->
                val j = JSONObject(body)
                entries = j.optInt("entries").toString()
                pending = "${j.optInt("pendingBatches")} batch / ${j.optInt("reports")} rep"
                totals = "＋${j.optInt("totalAdded")} added  ·  －${j.optInt("totalRemoved")} removed"
                lastFlush = j.optString("lastFlush", "—")
                lastCommit = j.optString("lastCommit", "—")
                adminKeySet = if (j.optBoolean("adminKeySet")) "set" else "NOT set"
                status = "live"
            }.onFailure { status = "unreachable (${it.javaClass.simpleName})" }
            delay(30_000)
        }
    }
    AdminSectionCard(
        title = "Avatar catalog",
        icon = Icons.Filled.Storage,
        tone = AdminTone.Info,
        trailing = { IconButton(onClick = { tick++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") } }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AdminLabeledRow("Worker", status)
            AdminLabeledRow("Avatars", entries)
            AdminLabeledRow("Totals", totals)
            AdminLabeledRow("Pending", pending)
            AdminLabeledRow("Last flush", lastFlush)
            AdminLabeledRow("Last commit", lastCommit, mono = true)
            AdminLabeledRow("Admin key", adminKeySet)
        }
    }
}

// ---- admin key + blitz + global progress -----------------------------------

@Composable
private fun SweepStatusCard(
    adminKey: String, onKeyChange: (String) -> Unit,
    assignment: String, totalQueued: Int, blitz: Boolean
) {
    AdminSectionCard(title = "Catalog maintenance", icon = Icons.Filled.SportsEsports, tone = AdminTone.Warn) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (assignment.isNotBlank()) {
                Text(assignment, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (AvatarCatalogSweep.pushError.isNotBlank()) {
                Text(AvatarCatalogSweep.pushError, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }

            Divider()

            OutlinedTextField(
                value = adminKey,
                onValueChange = onKeyChange,
                label = { Text("Admin key") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = {
                        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
                        onKeyChange("vrca-" + (1..24).map { chars.random() }.joinToString(""))
                    }) { Text("Generate") }
                }
            )
            Text(
                "Generate one, then copy it into a Cloudflare Worker secret named ADMIN_KEY " +
                    "(Settings → Variables → add → Secret). The two must match — it's the password " +
                    "that lets ONLY your app remove/refresh catalog entries.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = { AvatarCatalogSweep.requestFullBlitz() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(if (blitz) "Blitz running — extend" else "Check entire catalog (blitz)")
            }
            // Global progress: how much is left across ALL roles right now. During a
            // blitz you watch this count down as the bots chew through the catalog.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("To process (all roles)", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                StatusPill("$totalQueued", if (totalQueued == 0) AdminTone.Success else AdminTone.Warn)
            }
            Text(
                "The blitz makes all bots catch up the WHOLE catalog for ~30 min (fill every " +
                    "avatar's info + dead-check). Re-press to extend.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** One role's queue + progress line (shown inside the bot card that runs it). */
@Composable
private fun RoleRow(v: AvatarCatalogSweep.RoleView) {
    val queuedTone = when {
        v.queued <= 0 -> AdminTone.Success
        v.queued > 500 -> AdminTone.Error
        else -> AdminTone.Warn
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(v.role.label, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            StatusPill("queued ${v.queued}", queuedTone)
        }
        val processedLabel = if (v.role == AvatarCatalogSweep.Role.FILL) "filled" else "refreshed"
        Text(
            "checked ${v.checked} · removed ${v.removed} · $processedLabel ${v.refreshedOrFilled}",
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (v.status.isNotBlank()) {
            Text(v.status, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- per-bot login card -----------------------------------------------------

@Composable
private fun BotLoginCard(slot: Int, roleViews: List<AvatarCatalogSweep.RoleView>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var loggedIn by remember { mutableStateOf(BotVrchatSession.isLoggedIn(ctx, slot)) }
    var botName by remember { mutableStateOf(BotVrchatSession.botName(ctx, slot)) }
    var auth by remember { mutableStateOf(BotVrchatSession.Auth.UNKNOWN) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var needs2fa by remember { mutableStateOf(false) }
    var is2faEmail by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(slot) {
        var i = 0
        while (true) {
            if (!needs2fa) loggedIn = BotVrchatSession.isLoggedIn(ctx, slot)
            if (loggedIn && (i % 20 == 0)) {   // validate ~every 40s
                auth = BotVrchatSession.validate(ctx, slot)
                botName = BotVrchatSession.botName(ctx, slot)
            }
            if (!loggedIn) auth = BotVrchatSession.Auth.UNKNOWN
            i++; delay(2000)
        }
    }

    val tone = when {
        !loggedIn -> AdminTone.Neutral
        auth == BotVrchatSession.Auth.EXPIRED -> AdminTone.Error
        auth == BotVrchatSession.Auth.AUTHED -> AdminTone.Success
        else -> AdminTone.Warn
    }
    AdminSectionCard(
        title = "Bot ${slot + 1}",
        icon = Icons.Filled.SportsEsports,
        tone = tone,
        trailing = {
            when {
                !loggedIn -> StatusPill("Signed out", AdminTone.Neutral)
                auth == BotVrchatSession.Auth.AUTHED -> StatusPill("Authed", AdminTone.Success)
                auth == BotVrchatSession.Auth.EXPIRED -> StatusPill("Expired", AdminTone.Error)
                else -> StatusPill("Checking…", AdminTone.Warn)
            }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                needs2fa -> {
                    OutlinedTextField(
                        value = code, onValueChange = { code = it },
                        label = { Text("2FA code") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        enabled = !busy && code.isNotBlank(),
                        onClick = {
                            busy = true
                            scope.launch {
                                val r = BotVrchatSession.verify2FA(ctx, slot, code.trim(), is2faEmail)
                                msg = when (r) {
                                    is BotVrchatSession.LoginResult.Success -> {
                                        loggedIn = true; needs2fa = false; code = ""
                                        botName = BotVrchatSession.botName(ctx, slot)
                                        auth = BotVrchatSession.validate(ctx, slot); "Logged in"
                                    }
                                    is BotVrchatSession.LoginResult.Error -> r.message
                                    else -> ""
                                }
                                busy = false
                            }
                        }
                    ) { Text("Verify") }
                }
                loggedIn -> {
                    AdminLabeledRow("Account", botName.ifBlank { "(name unknown)" })
                    if (auth == BotVrchatSession.Auth.EXPIRED) {
                        Text("Session expired — log out and back in.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    // This bot's role queue + progress (only the roles it actually runs).
                    if (roleViews.isNotEmpty()) {
                        Divider()
                        roleViews.forEach { RoleRow(it) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = {
                            BotVrchatSession.logout(ctx, slot)
                            loggedIn = false; botName = ""; auth = BotVrchatSession.Auth.UNKNOWN
                        }) { Text("Log out") }
                        if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                else -> {
                    OutlinedTextField(
                        value = user, onValueChange = { user = it },
                        label = { Text("Bot username / email") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = pass, onValueChange = { pass = it },
                        label = { Text("Bot password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        enabled = !busy && user.isNotBlank() && pass.isNotBlank(),
                        onClick = {
                            busy = true
                            scope.launch {
                                val r = BotVrchatSession.login(ctx, slot, user.trim(), pass)
                                msg = when (r) {
                                    is BotVrchatSession.LoginResult.Success -> {
                                        loggedIn = true; pass = ""
                                        botName = BotVrchatSession.botName(ctx, slot)
                                        auth = BotVrchatSession.validate(ctx, slot); "Logged in"
                                    }
                                    is BotVrchatSession.LoginResult.Needs2FA -> {
                                        needs2fa = true; is2faEmail = r.email; "Enter the 2FA code"
                                    }
                                    is BotVrchatSession.LoginResult.Error -> r.message
                                }
                                busy = false
                            }
                        }
                    ) { Text("Log in") }
                }
            }
            if (msg.isNotBlank()) {
                Text(msg, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
