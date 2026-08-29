package com.vrca.admin

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val PREFS_ADMIN = "vrca_admin_local"
private const val KEY_ROLE_SLOTS = "avatar_role_slots"   // CSV of 4 ints, -1 = auto

private fun loadRoleSlots(prefs: SharedPreferences): IntArray {
    val csv = prefs.getString(KEY_ROLE_SLOTS, null) ?: return IntArray(4) { -1 }
    val parts = csv.split(",")
    return IntArray(4) { i -> parts.getOrNull(i)?.toIntOrNull() ?: -1 }
}

private fun saveRoleSlots(prefs: SharedPreferences, arr: IntArray) {
    prefs.edit().putString(KEY_ROLE_SLOTS, arr.joinToString(",")).apply()
}

@Composable
fun BotsTab() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS_ADMIN, Context.MODE_PRIVATE) }
    var adminKey by remember { mutableStateOf(prefs.getString("avatar_admin_key", "") ?: "") }
    var roleSlots by remember { mutableStateOf(loadRoleSlots(prefs)) }
    var paused by remember { mutableStateOf(prefs.getBoolean("bots_paused", false)) }
    var avtrdbCrawl by remember { mutableStateOf(prefs.getBoolean("avtrdb_crawl_enabled", false)) }

    LaunchedEffect(Unit) { BotController.start(ctx) }
    val bots by BotController.bots.collectAsState()
    val views by BotController.views.collectAsState()
    val totalQueued by BotController.totalQueued.collectAsState()
    val added24h by BotController.added24h.collectAsState()
    val lastPush by BotController.lastPush.collectAsState()
    val blitz by BotController.blitz.collectAsState()
    val blitzViews by BotController.blitzViews.collectAsState()
    val blitzShards by BotController.blitzShards.collectAsState()
    val sweepAlive by BotController.sweepAlive.collectAsState()
    val sweepAgoMs by BotController.sweepLastCycleAgoMs.collectAsState()
    // The sweep lifecycle is owned by BotController (reads the saved key/assignment/pause
    // every couple seconds), so it auto-runs from app launch. The UI just writes those
    // prefs; nudge it to re-apply immediately on a change.
    LaunchedEffect(adminKey, roleSlots.joinToString(","), paused, avtrdbCrawl) { BotController.applySweepConfig(ctx) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
    ) {
        item { CatalogHealthCard(added24h, lastPush, adminKey) }
        item {
            MaintenanceCard(
                adminKey = adminKey,
                onKeyChange = { adminKey = it; prefs.edit().putString("avatar_admin_key", it).apply() },
                totalQueued = totalQueued,
                blitz = blitz, blitzShards = blitzShards,
                sweepAlive = sweepAlive,
                sweepAgoMs = sweepAgoMs,
                paused = paused,
                onTogglePause = { paused = !paused; prefs.edit().putBoolean("bots_paused", paused).apply() },
                avtrdbCrawl = avtrdbCrawl,
                onToggleCrawl = { avtrdbCrawl = !avtrdbCrawl; prefs.edit().putBoolean("avtrdb_crawl_enabled", avtrdbCrawl).apply() },
                roleSlots = roleSlots,
                slotLabels = List(BotVrchatSession.SLOTS) { s -> bots.getOrNull(s)?.name ?: "" },
                onRolePick = { roleOrdinal, slot ->
                    roleSlots = roleSlots.copyOf().also { it[roleOrdinal] = slot }
                    saveRoleSlots(prefs, roleSlots)
                }
            )
        }
        item { BotsCard(bots, views, blitzViews) }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

// ---- catalog health ---------------------------------------------------------

private data class RecentBatch(val by: String, val n: Int, val ts: Long, val names: List<String>)

private fun agoLabel(fromMs: Long, now: Long): String {
    val s = ((now - fromMs) / 1000L).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        else -> "${s / 3600}h ago"
    }
}

@Composable
private fun CatalogHealthCard(added24h: Pair<Int, Int>? = null, lastPush: Pair<String, Long>? = null, adminKey: String = "") {
    var status by remember { mutableStateOf("loading…") }
    var entries by remember { mutableStateOf("—") }
    var pending by remember { mutableStateOf("—") }
    var totals by remember { mutableStateOf("—") }
    var lastFlush by remember { mutableStateOf("—") }
    var adminKeySet by remember { mutableStateOf("—") }
    var recent by remember { mutableStateOf<List<RecentBatch>>(emptyList()) }
    var expanded by remember { mutableStateOf<Int?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    // Live clock so relative-time labels tick and the card refreshes on its own (no manual refresh).
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(2000); nowMs = System.currentTimeMillis() } }
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
                totals = "＋${j.optInt("totalAdded")}  ·  －${j.optInt("totalRemoved")}"
                lastFlush = j.optString("lastFlush", "—")
                adminKeySet = if (j.optBoolean("adminKeySet")) "set" else "NOT set"
                status = "live"
            }.onFailure { status = "unreachable (${it.javaClass.simpleName})" }
            delay(15_000)   // auto-refreshes every 15s
        }
    }
    // Recent USER contributions — fetched from the admin-gated /admin/recent (NOT /health, so the
    // bot/client health polls stay tiny). One cheap KV read per 15s while this card is on screen.
    LaunchedEffect(tick, adminKey) {
        if (adminKey.isBlank()) { recent = emptyList(); return@LaunchedEffect }
        while (true) {
            runCatching {
                withContext(Dispatchers.IO) {
                    val conn = (java.net.URL("${com.vrca.vrchat.AvatarGlobalDb.WORKER_URL}/admin/recent?key=" +
                        java.net.URLEncoder.encode(adminKey, "UTF-8"))
                        .openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 10_000; readTimeout = 10_000
                        setRequestProperty("User-Agent", "VRC-A")
                    }
                    if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().readText()
                }
            }.getOrNull()?.let { body ->
                val ra = JSONObject(body).optJSONArray("recent")
                val now = System.currentTimeMillis()
                recent = if (ra == null) emptyList() else (0 until ra.length()).mapNotNull { idx ->
                    ra.optJSONObject(idx)?.let { o ->
                        val names = o.optJSONArray("names")?.let { na ->
                            (0 until na.length()).mapNotNull { na.optString(it, null) }.filter { it.isNotBlank() }
                        }.orEmpty()
                        RecentBatch(o.optString("by", "").ifBlank { "someone" }, o.optInt("n", names.size),
                            o.optLong("ts", now), names)
                    }
                }
            }
            delay(15_000)
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
            added24h?.let { (delta, windowH) ->
                val sign = if (delta >= 0) "＋" else "－"
                val label = if (windowH >= 23) "Added (24h)" else if (windowH >= 1) "Added (${windowH}h)" else "Added"
                AdminLabeledRow(label, "$sign${kotlin.math.abs(delta)}")
            }
            AdminLabeledRow("Totals", totals)
            AdminLabeledRow("Pending", pending)
            AdminLabeledRow("Last flush", lastFlush)
            AdminLabeledRow("Admin key", adminKeySet)
            lastPush?.let { (info, atMs) ->
                Text("Last bot push (${agoLabel(atMs, nowMs)})", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(info, style = MaterialTheme.typography.bodySmall)
            }
            if (recent.isNotEmpty()) {
                androidx.compose.material3.Divider(Modifier.padding(vertical = 4.dp))
                Text("Recent user contributions", style = MaterialTheme.typography.labelMedium)
                recent.forEachIndexed { i, b ->
                    val isOpen = expanded == i
                    Text(
                        "${if (isOpen) "▾" else "▸"} ${b.by} · ${b.n} avatars · ${agoLabel(b.ts, nowMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = if (isOpen) null else i }
                            .padding(vertical = 2.dp)
                    )
                    if (isOpen) {
                        Text(
                            if (b.names.isEmpty()) "(names unavailable)"
                            else b.names.joinToString(", ") +
                                (if (b.n > b.names.size) "  (+${b.n - b.names.size} more not stored)" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

// ---- admin key + role assignment + blitz -----------------------------------

@Composable
private fun MaintenanceCard(
    adminKey: String, onKeyChange: (String) -> Unit,
    totalQueued: Int, blitz: Boolean, blitzShards: Pair<Int, Int>? = null,
    sweepAlive: Boolean, sweepAgoMs: Long,
    paused: Boolean, onTogglePause: () -> Unit,
    avtrdbCrawl: Boolean, onToggleCrawl: () -> Unit,
    roleSlots: IntArray, slotLabels: List<String>, onRolePick: (Int, Int) -> Unit
) {
    AdminSectionCard(title = "Maintenance", icon = Icons.Filled.SportsEsports, tone = AdminTone.Warn) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Master pause: stop ALL bots so you can log every account in first, then
            // resume them together. Sessions stay authed while paused.
            Button(
                onClick = onTogglePause,
                modifier = Modifier.fillMaxWidth(),
                colors = if (paused) androidx.compose.material3.ButtonDefaults.buttonColors()
                         else androidx.compose.material3.ButtonDefaults.buttonColors(
                             containerColor = MaterialTheme.colorScheme.errorContainer,
                             contentColor = MaterialTheme.colorScheme.onErrorContainer
                         )
            ) { Text(if (paused) "Resume all bots" else "Pause all bots") }
            if (paused) {
                Text("Paused — no bot is doing anything.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (AvatarCatalogSweep.pushError.isNotBlank()) {
                Text(AvatarCatalogSweep.pushError, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
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

            Divider()

            AvatarCatalogSweep.Role.values().forEach { role ->
                RoleAssignRow(role, roleSlots.getOrElse(role.ordinal) { -1 }, slotLabels) { slot ->
                    onRolePick(role.ordinal, slot)
                }
            }

            Divider()

            Button(onClick = { AvatarCatalogSweep.requestFullBlitz() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(if (blitz) "Blitz running — extend" else "Check entire catalog (blitz)")
            }
            // Blitz shard coverage — "N / 4096 shards checked (M left)".
            blitzShards?.let { (done, total) ->
                val left = (total - done).coerceAtLeast(0)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = if (total > 0) done.toFloat() / total else 0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Blitz: $done / $total shards checked · $left left",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("To process", style = MaterialTheme.typography.bodyMedium)
                StatusPill("$totalQueued", if (totalQueued == 0) AdminTone.Success else AdminTone.Warn)
            }
            // Proof-of-life: shows the sweep loop is alive even when the backlog is flat,
            // so "caught up / idle" is distinguishable from "stopped". Updates every ~2s.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Worker loop", style = MaterialTheme.typography.bodyMedium)
                when {
                    paused -> StatusPill("Paused", AdminTone.Neutral)
                    sweepAlive -> {
                        val ago = if (sweepAgoMs in 0..600_000) "${sweepAgoMs / 1000}s ago" else "active"
                        val label = if (totalQueued == 0) "Running · idle · $ago" else "Running · $ago"
                        StatusPill(label, AdminTone.Success)
                    }
                    else -> StatusPill("Stopped", AdminTone.Error)
                }
            }

            Divider()

            // avtrdb digestion crawl — OFF by default. Uses a BOT session to resolve + absorb
            // avtrdb into the catalog. Keep OFF until the sharding migration lands (it's a
            // volume firehose into the Worker flush).
            Button(
                onClick = onToggleCrawl,
                modifier = Modifier.fillMaxWidth(),
                colors = if (avtrdbCrawl) androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ) else androidx.compose.material3.ButtonDefaults.buttonColors()
            ) { Text(if (avtrdbCrawl) "Absorbing avtrdb — ON (tap to stop)" else "Absorb avtrdb (crawl) — OFF") }
            Text(
                "Crawl: ${AvatarCatalogSweep.avtrdbCrawlStatus}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (avtrdbCrawl) {
                Text(
                    "Warning: leave OFF until sharding — it firehoses the Worker flush.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun RoleAssignRow(role: AvatarCatalogSweep.Role, slot: Int, slotLabels: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun label(s: Int): String {
        val name = slotLabels.getOrNull(s)?.takeIf { it.isNotBlank() }
        return "Bot ${s + 1}" + (name?.let { " · $it" } ?: "")
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(role.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Box {
            OutlinedButton(onClick = { open = true }) { Text(if (slot < 0) "Auto" else label(slot)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Auto") }, onClick = { onPick(-1); open = false })
                (0 until BotVrchatSession.SLOTS).forEach { s ->
                    DropdownMenuItem(text = { Text(label(s)) }, onClick = { onPick(s); open = false })
                }
            }
        }
    }
}

// ---- ONE card, four bots separated by function ------------------------------

@Composable
private fun BotsCard(
    bots: List<BotController.BotUi>,
    views: List<AvatarCatalogSweep.RoleView>,
    blitzViews: Map<Int, AvatarCatalogSweep.BlitzView>
) {
    AdminSectionCard(title = "Bots", icon = Icons.Filled.SportsEsports, tone = AdminTone.Primary) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            bots.forEachIndexed { idx, bot ->
                if (idx > 0) Divider()
                BotSection(bot, views.filter { it.bot == "bot ${bot.slot + 1}" }, blitzViews[bot.slot])
            }
        }
    }
}

@Composable
private fun BotSection(
    bot: BotController.BotUi,
    roleViews: List<AvatarCatalogSweep.RoleView>,
    blitzView: AvatarCatalogSweep.BlitzView?
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val slot = bot.slot

    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var needs2fa by remember { mutableStateOf(false) }
    var is2faEmail by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (bot.loggedIn && bot.name.isNotBlank()) bot.name else "Bot ${slot + 1}",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold
            )
            when {
                !bot.loggedIn -> StatusPill("Signed out", AdminTone.Neutral)
                bot.auth == BotVrchatSession.Auth.AUTHED -> StatusPill("Authed", AdminTone.Success)
                bot.auth == BotVrchatSession.Auth.EXPIRED -> StatusPill("Expired", AdminTone.Error)
                else -> StatusPill("Checking…", AdminTone.Warn)
            }
        }

        when {
            bot.loggedIn && !needs2fa -> {
                if (bot.auth == BotVrchatSession.Auth.EXPIRED) {
                    Text("Session expired and couldn't auto-recover — log out and back in.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                // During a blitz every bot shares the fill+dead-check work, so show its
                // blitz progress instead of the idle assigned-role rows.
                if (blitzView != null) BlitzRow(blitzView) else roleViews.forEach { RoleRow(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        BotVrchatSession.logout(ctx, slot); BotController.refreshSlot(ctx, slot)
                        needs2fa = false; msg = ""
                    }) { Text("Log out") }
                    if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            needs2fa -> {
                OutlinedTextField(
                    value = code, onValueChange = { code = it },
                    label = { Text("2FA code") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = !busy && code.isNotBlank(),
                    onClick = {
                        busy = true
                        BotController.beginLogin()
                        scope.launch {
                            try {
                                val r = BotVrchatSession.verify2FA(ctx, slot, code.trim(), is2faEmail)
                                msg = when (r) {
                                    is BotVrchatSession.LoginResult.Success -> { needs2fa = false; code = ""; BotController.refreshSlot(ctx, slot); "" }
                                    is BotVrchatSession.LoginResult.Error -> r.message
                                    else -> ""
                                }
                            } finally { BotController.endLogin(); busy = false }
                        }
                    }
                ) { Text("Verify") }
            }
            else -> {
                OutlinedTextField(
                    value = user, onValueChange = { user = it },
                    label = { Text("Bot username / email") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it },
                    label = { Text("Bot password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = !busy && user.isNotBlank() && pass.isNotBlank(),
                    onClick = {
                        busy = true; msg = "Signing in…"
                        BotController.beginLogin()   // the other bots go silent while this logs in
                        scope.launch {
                            try {
                                val r = BotVrchatSession.login(ctx, slot, user.trim(), pass) { p -> msg = p }
                                msg = when (r) {
                                    is BotVrchatSession.LoginResult.Success -> { pass = ""; BotController.refreshSlot(ctx, slot); "" }
                                    is BotVrchatSession.LoginResult.Needs2FA -> { needs2fa = true; is2faEmail = r.email; "" }
                                    is BotVrchatSession.LoginResult.Error -> r.message
                                }
                            } finally { BotController.endLogin(); busy = false }
                        }
                    }
                ) { Text("Log in") }
            }
        }
        if (msg.isNotBlank()) {
            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun BlitzRow(v: AvatarCatalogSweep.BlitzView) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⚡ Blitz", style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            StatusPill("queued ${v.queued}", if (v.queued <= 0) AdminTone.Success else AdminTone.Warn)
        }
        Text(
            "checked ${v.checked} · filled ${v.filled} · refreshed ${v.refreshed} · removed ${v.removed}",
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RoleRow(v: AvatarCatalogSweep.RoleView) {
    val queuedTone = when {
        v.queued <= 0 -> AdminTone.Success
        v.queued > 500 -> AdminTone.Error
        else -> AdminTone.Warn
    }
    Column(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (v.helping.isNotBlank()) "${v.role.label}  →  helping ${v.helping}" else v.role.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
            )
            StatusPill("queued ${v.queued}", queuedTone)
        }
        val processedLabel = if (v.role == AvatarCatalogSweep.Role.FILL || v.helping == "Fill") "filled" else "refreshed"
        Text(
            "checked ${v.checked} · removed ${v.removed} · $processedLabel ${v.refreshedOrFilled}",
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
