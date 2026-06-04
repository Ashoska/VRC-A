package com.vrca.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrca.discord.DiscordLoginWebView
import com.vrca.discord.DiscordRpcService
import com.vrca.discord.DiscordRpcState
import com.vrca.discord.DiscordRpcStatus
import com.vrca.ui.settings.NotificationToggleSection
import com.vrca.ui.settings.ToggleRow
import com.vrca.ui.viewmodel.VrcaViewModel
import com.vrca.vrchat.InAppAlertGroup
import com.vrca.vrchat.InAppAlertState
import com.vrca.vrchat.VrchatAuthManager
import com.vrca.vrchat.VrchatPipelineService
import com.vrca.vrchat.VrchatPipelineState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun VrchatStatusPage(
    vm: VrcaViewModel,
    onOpenLogin: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val isLinked = remember { mutableStateOf(VrchatAuthManager.isLoggedIn(ctx)) }
    val displayName = remember { mutableStateOf(VrchatAuthManager.getStoredDisplayName(ctx) ?: "") }
    val presence by VrchatPipelineState.presenceFlow.collectAsState()
    val isConnected by VrchatPipelineState.isConnectedFlow.collectAsState()

    var showLogoutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { InAppAlertState.load(ctx) }

    PageContainer {
        VrchatStatusBanner()

        // In-app alerts (persistent until dismissed)
        InAppAlertCards()

        // Connection status header
        ElevatedCard {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isLinked.value) displayName.value.ifBlank { "VRChat account" }
                        else "Not signed in",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (isConnected) "Live connection active"
                        else if (isLinked.value) "Connecting..."
                        else "Sign in to enable notifications and presence",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isLinked.value) {
                    OutlinedButton(onClick = { showLogoutDialog = true }) { Text("Sign out") }
                } else {
                    Button(onClick = onOpenLogin) { Text("Sign in") }
                }
            }
        }

        // Presence card
        val p = presence
        if (p != null && isLinked.value) {
            val statusColor = if (p.isOnlineInVRChat) {
                when (p.status) {
                    "ask me" -> Color(0xFFFF9800)
                    "busy"   -> Color(0xFFF44336)
                    "join me" -> Color(0xFF2196F3)
                    else     -> Color(0xFF4CAF50)
                }
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            val statusText = if (p.isOnlineInVRChat) {
                when (p.status) {
                    "ask me" -> "Ask Me"
                    "busy"   -> "Do Not Disturb"
                    "join me" -> "Join Me"
                    else     -> "Online"
                }
            } else "Offline"

            val platform = when (p.platform) {
                "standalonewindows" -> "Desktop"
                "android"           -> "Android/Quest"
                "ios"               -> "iOS"
                else                -> ""
            }

            ElevatedCard {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Status header with colored dot
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Canvas(Modifier.size(12.dp)) {
                                drawCircle(color = statusColor)
                            }
                            Column {
                                Text(
                                    p.displayName,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    statusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = statusColor
                                )
                            }
                        }
                        if (platform.isNotBlank()) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(platform, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    if (p.statusDescription.isNotBlank()) {
                        Text(
                            p.statusDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // World info
                    if (p.isOnlineInVRChat) {
                        Divider()
                        if (p.worldName.isNotBlank()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    p.worldName,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                val count = if (p.instanceCapacity > 0)
                                    "${p.instancePlayerCount} / ${p.instanceCapacity} players"
                                else "${p.instancePlayerCount} players"
                                Text(
                                    count,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                // TEMPORARY DIAGNOSTIC: raw instance-count fields so
                                // we can see which one matches the in-game panel.
                                val countDebug by com.vrca.vrchat.VrchatAuthManager
                                    .instanceCountDebug.collectAsState()
                                if (countDebug.isNotBlank()) {
                                    Text(
                                        "DEBUG: $countDebug",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        } else {
                            Text(
                                when (p.location) {
                                    "private"   -> "In a private world"
                                    "traveling" -> "Traveling between worlds..."
                                    else        -> "In a world"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // VRChat profile link
                    if (p.userId.isNotBlank()) {
                        Text(
                            text = "View VRChat Profile",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                val intent = Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://vrchat.com/home/user/${p.userId}"))
                                ctx.startActivity(intent)
                            }
                        )
                    }
                }
            }
        } else if (isLinked.value) {
            ElevatedCard {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Fetching presence...", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // -- VRChat Notification Toggles (collapsible categories) --
        val repo = vm.userPreferencesRepository
        NotificationToggleSection(vm = vm)

        // -- Discord Rich Presence --
        val discordEnabled by repo.discordRpcEnabled.collectAsState(initial = false)
        val discordSeeded by repo.discordSessionSeeded.collectAsState(initial = false)
        val discordRiskAccepted by repo.discordRiskAccepted.collectAsState(initial = false)
        val discordStatus by DiscordRpcState.statusFlow.collectAsState()
        val discordFailureMsg by DiscordRpcState.failureMessageFlow.collectAsState()
        var showDiscordLogin by remember { mutableStateOf(false) }
        var showRiskConsent by remember { mutableStateOf(false) }
        SectionCard(
            title = "Discord Rich Presence",
            subtitle = "Show VRChat activity on your Discord profile."
        ) {
            Text(
                "Uses a hidden Discord web session to set your activity. " +
                "Sign in below to connect your Discord account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))

            if (discordSeeded) {
                // Status indicator
                val (statusColor, statusLabel) = when (discordStatus) {
                    DiscordRpcStatus.CONNECTED -> MaterialTheme.colorScheme.primary to "Connected"
                    DiscordRpcStatus.CONNECTING -> MaterialTheme.colorScheme.tertiary to "Connecting..."
                    DiscordRpcStatus.RECONNECTING -> MaterialTheme.colorScheme.tertiary to "Reconnecting..."
                    DiscordRpcStatus.SESSION_EXPIRED -> MaterialTheme.colorScheme.error to "Session Expired"
                    DiscordRpcStatus.FAILED -> MaterialTheme.colorScheme.error to "Failed"
                    DiscordRpcStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant to "Idle"
                }
                Card(colors = CardDefaults.cardColors(
                    containerColor = if (discordStatus == DiscordRpcStatus.SESSION_EXPIRED || discordStatus == DiscordRpcStatus.FAILED)
                        MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer
                )) {
                    Column(Modifier.padding(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Canvas(Modifier.size(8.dp)) {
                                drawCircle(color = statusColor)
                            }
                            Text(statusLabel, style = MaterialTheme.typography.bodySmall,
                                color = statusColor)
                        }
                        if (discordFailureMsg != null) {
                            Text(discordFailureMsg!!, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }

                // Re-login button when session expired
                if (discordStatus == DiscordRpcStatus.SESSION_EXPIRED) {
                    Button(onClick = { showDiscordLogin = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )) {
                        Text("Sign in again")
                    }
                }

                ToggleRow("Enable Discord RPC", discordEnabled) { enabled ->
                    if (enabled && !discordRiskAccepted) {
                        showRiskConsent = true
                    } else {
                        scope.launch {
                            repo.saveDiscordRpcEnabled(enabled)
                            val svcIntent = Intent(ctx, DiscordRpcService::class.java)
                            if (enabled) {
                                svcIntent.action = DiscordRpcService.ACTION_START
                                ctx.startForegroundService(svcIntent)
                            } else {
                                svcIntent.action = DiscordRpcService.ACTION_STOP
                                ctx.startService(svcIntent)
                            }
                        }
                    }
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        repo.saveDiscordRpcEnabled(false)
                        repo.saveDiscordSessionSeeded(false)
                        android.webkit.CookieManager.getInstance().removeAllCookies(null)
                        val svcIntent = Intent(ctx, DiscordRpcService::class.java)
                        svcIntent.action = DiscordRpcService.ACTION_STOP
                        ctx.startService(svcIntent)
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Disconnect Discord")
                }
            } else {
                Button(
                    onClick = {
                        if (!discordRiskAccepted) {
                            showRiskConsent = true
                        } else {
                            showDiscordLogin = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Sign in to Discord") }
            }
            Text(
                "Your session is stored securely on-device only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Risk consent dialog
        if (showRiskConsent) {
            var riskChecked by remember { mutableStateOf(false) }
            var confirmEnabled by remember { mutableStateOf(false) }
            LaunchedEffect(riskChecked) {
                if (riskChecked) {
                    confirmEnabled = false
                    delay(4000)
                    confirmEnabled = true
                } else {
                    confirmEnabled = false
                }
            }
            AlertDialog(
                onDismissRequest = { showRiskConsent = false },
                title = { Text("Discord Rich Presence") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "This feature runs a hidden Discord web session on your device to show " +
                            "VRChat activity on your Discord profile.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Please be aware:",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            "• A background Discord web session will be active while enabled\n" +
                            "• This uses additional battery and data\n" +
                            "• Your Discord session cookies are stored on-device only\n" +
                            "• While unlikely, Discord could flag unusual client behavior\n" +
                            "• Disconnecting clears your Discord session — Discord may also " +
                            "invalidate your sessions on other devices when it detects an " +
                            "unauthorized client, logging you out everywhere\n" +
                            "• You can disable this at any time from settings",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { riskChecked = !riskChecked }) {
                            Checkbox(checked = riskChecked, onCheckedChange = { riskChecked = it })
                            Text("I understand and accept these risks",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                repo.saveDiscordRiskAccepted(true)
                                showRiskConsent = false
                                if (discordSeeded) {
                                    repo.saveDiscordRpcEnabled(true)
                                    val svcIntent = Intent(ctx, DiscordRpcService::class.java)
                                    svcIntent.action = DiscordRpcService.ACTION_START
                                    ctx.startForegroundService(svcIntent)
                                } else {
                                    showDiscordLogin = true
                                }
                            }
                        },
                        enabled = confirmEnabled
                    ) {
                        Text(if (confirmEnabled) "Continue" else "Please wait...")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRiskConsent = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Discord login dialog
        if (showDiscordLogin) {
            AlertDialog(
                onDismissRequest = { showDiscordLogin = false },
                confirmButton = {},
                text = {
                    Box(Modifier.fillMaxWidth().height(500.dp)) {
                        DiscordLoginWebView(
                            onLoginComplete = {
                                scope.launch {
                                    repo.saveDiscordSessionSeeded(true)
                                    showDiscordLogin = false
                                }
                            },
                            onDismiss = { showDiscordLogin = false }
                        )
                    }
                }
            )
        }

        // Info card
        ElevatedCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("About", style = MaterialTheme.typography.titleSmall)
                Text(
                    "VRC-A uses VRChat's API for status, notifications, and friend tracking. Discord RPC shows your VRChat activity on your Discord profile via a hidden web session.\n\nYour VRChat password is only used to get a session cookie - it is never stored. Your Discord session stays on-device only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Sign out confirmation
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Sign out of VRChat?") },
            text = {
                Text("Notifications and presence will stop until you sign back in. The app will require you to sign in again before you can use it.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    VrchatAuthManager.logout(ctx)
                    isLinked.value = false
                    displayName.value = ""
                    // Stop pipeline service
                    ctx.stopService(
                        Intent(ctx, VrchatPipelineService::class.java)
                    )
                }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Cancel") }
            }
        )
    }
}

private const val VISIBLE_ALERT_LIMIT = 3

@Composable
private fun InAppAlertCards() {
    val ctx = LocalContext.current
    val groups by InAppAlertState.groups.collectAsState()
    if (groups.isEmpty()) return

    var sectionExpanded by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }

    Surface(
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { sectionExpanded = !sectionExpanded }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Notifications (${groups.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    if (sectionExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (sectionExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            AnimatedVisibility(
                visible = sectionExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val visible = if (showAll || groups.size <= VISIBLE_ALERT_LIMIT) groups
                        else groups.take(VISIBLE_ALERT_LIMIT)
                    val hiddenCount = groups.size - visible.size

                    for (group in visible) {
                        // Key by group identity so each card's expanded state stays
                        // with ITS group. Without this, Compose tracks state by list
                        // position, so a new alert prepended to the list would steal
                        // the expanded state from the card the user was viewing.
                        key(group.groupId) {
                            AlertGroupCard(group = group, onDismiss = {
                                InAppAlertState.dismiss(ctx, group.groupId)
                                // Also dismiss the linked Android notification
                                val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                                nm.cancel(group.groupId.hashCode())
                            })
                        }
                    }

                    if (hiddenCount > 0) {
                        OutlinedButton(
                            onClick = { showAll = true },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text(
                                "+$hiddenCount more alert${if (hiddenCount > 1) "s" else ""}",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatRelativeTime(timestampMs: Long): String {
    val delta = System.currentTimeMillis() - timestampMs
    val sec = delta / 1000L
    return when {
        sec < 5 -> "just now"
        sec < 60 -> "${sec}s ago"
        sec < 3600 -> "${sec / 60}m ago"
        sec < 86400 -> "${sec / 3600}h ago"
        else -> "${sec / 86400}d ago"
    }
}

private fun wordDiff(before: String, after: String): Pair<androidx.compose.ui.text.AnnotatedString, androidx.compose.ui.text.AnnotatedString> {
    val removedColor = Color(0xFFEF5350)
    val addedColor = Color(0xFF4CAF50)
    val neutralColor = Color(0xFFB0B0B0)
    val movedColor = Color(0xFFB388FF) // purple — line was moved, not added/removed

    // Diff at the LINE level, not the word level. Bios are usually multi-line
    // lists; word-level LCS matched common words ("the", "my", "get") across
    // DIFFERENT lines and painted the result an inconsistent red/green/purple
    // mess. Whole lines are far more unique, so a line-level diff is stable and
    // readable: each line is unchanged, removed, added, or moved.
    fun lcs(b: List<String>, a: List<String>): Pair<HashSet<Int>, HashSet<Int>> {
        val m = b.size; val n = a.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) for (j in 1..n) {
            dp[i][j] = if (b[i - 1] == a[j - 1]) dp[i - 1][j - 1] + 1
            else maxOf(dp[i - 1][j], dp[i][j - 1])
        }
        val sb = HashSet<Int>(); val sa = HashSet<Int>()
        var i = m; var j = n
        while (i > 0 && j > 0) {
            when {
                b[i - 1] == a[j - 1] -> { sb.add(i - 1); sa.add(j - 1); i--; j-- }
                dp[i - 1][j] >= dp[i][j - 1] -> i--
                else -> j--
            }
        }
        return sb to sa
    }

    // Word-level diff for a SINGLE edited line: short, single-line text doesn't
    // suffer the cross-line scramble, so a plain red/green token diff is clean.
    fun lineWordDiff(b: String, a: String): Pair<androidx.compose.ui.text.AnnotatedString, androidx.compose.ui.text.AnnotatedString> {
        val tk = Regex("\\s+|\\S+")
        val bw = tk.findAll(b).map { it.value }.toList()
        val aw = tk.findAll(a).map { it.value }.toList()
        val (cb, ca) = lcs(bw, aw)
        val bs = buildAnnotatedString {
            for ((idx, w) in bw.withIndex()) {
                val c = if (w.isBlank() || idx in cb) neutralColor else removedColor
                withStyle(SpanStyle(color = c)) { append(w) }
            }
        }
        val asr = buildAnnotatedString {
            for ((idx, w) in aw.withIndex()) {
                val c = if (w.isBlank() || idx in ca) neutralColor else addedColor
                withStyle(SpanStyle(color = c)) { append(w) }
            }
        }
        return bs to asr
    }

    val bLines = before.split("\n")
    val aLines = after.split("\n")
    val (lcsB, lcsA) = lcs(bLines, aLines)

    val removedLineIdx = bLines.indices.filter { it !in lcsB }
    val addedLineIdx = aLines.indices.filter { it !in lcsA }

    // Move detection on whole lines: a removed line whose exact trimmed content
    // reappears as an added line was moved (purple), not deleted+added. Whole-line
    // matching is unambiguous, so no blocklist is needed.
    val movedB = HashSet<Int>(); val movedA = HashSet<Int>()
    val addedByContent = HashMap<String, ArrayDeque<Int>>()
    for (k in addedLineIdx) {
        val key = aLines[k].trim()
        if (key.isNotEmpty()) addedByContent.getOrPut(key) { ArrayDeque() }.add(k)
    }
    for (k in removedLineIdx) {
        val key = bLines[k].trim()
        if (key.isEmpty()) continue
        val dq = addedByContent[key] ?: continue
        if (dq.isNotEmpty()) { movedA.add(dq.removeFirst()); movedB.add(k) }
    }

    // Pair each remaining removed line with the most similar remaining added line
    // (token Jaccard > 0.3) so a single edited line shows word-level changes
    // instead of an entire red line plus an entire green line.
    fun tokens(s: String) = s.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
    fun sim(b: String, a: String): Double {
        val tb = tokens(b); val ta = tokens(a)
        val union = tb.union(ta).size
        return if (union == 0) 0.0 else tb.intersect(ta).size.toDouble() / union
    }
    val realRemoved = removedLineIdx.filter { it !in movedB }
    val realAdded = addedLineIdx.filter { it !in movedA }
    val pairBtoA = HashMap<Int, Int>()
    val usedA = HashSet<Int>()
    for (rb in realRemoved) {
        var best = -1; var bestScore = 0.3
        for (ra in realAdded) {
            if (ra in usedA) continue
            val s = sim(bLines[rb], aLines[ra])
            if (s > bestScore) { bestScore = s; best = ra }
        }
        if (best >= 0) { pairBtoA[rb] = best; usedA.add(best) }
    }
    val pairAtoB = HashMap<Int, Int>()
    val pairDiff = HashMap<Int, Pair<androidx.compose.ui.text.AnnotatedString, androidx.compose.ui.text.AnnotatedString>>()
    for ((b, a) in pairBtoA) {
        pairAtoB[a] = b
        pairDiff[b] = lineWordDiff(bLines[b], aLines[a])
    }

    val beforeAnnotated = buildAnnotatedString {
        for ((idx, line) in bLines.withIndex()) {
            if (idx > 0) withStyle(SpanStyle(color = neutralColor)) { append("\n") }
            when {
                idx in lcsB -> withStyle(SpanStyle(color = neutralColor)) { append(line) }
                idx in movedB -> withStyle(SpanStyle(color = movedColor)) { append(line) }
                idx in pairDiff -> append(pairDiff[idx]!!.first)
                else -> withStyle(SpanStyle(color = removedColor)) { append(line) }
            }
        }
    }
    val afterAnnotated = buildAnnotatedString {
        for ((idx, line) in aLines.withIndex()) {
            if (idx > 0) withStyle(SpanStyle(color = neutralColor)) { append("\n") }
            when {
                idx in lcsA -> withStyle(SpanStyle(color = neutralColor)) { append(line) }
                idx in movedA -> withStyle(SpanStyle(color = movedColor)) { append(line) }
                pairAtoB.containsKey(idx) -> append(pairDiff[pairAtoB.getValue(idx)]!!.second)
                else -> withStyle(SpanStyle(color = addedColor)) { append(line) }
            }
        }
    }
    return beforeAnnotated to afterAnnotated
}

@Composable
private fun AlertGroupCard(group: InAppAlertGroup, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val eventCount = group.events.size
    val displayTitle = if (eventCount > 1) "${group.title} ($eventCount)" else group.title

    val latest = group.events.lastOrNull()
    val previewText = latest?.body?.takeIf { it.isNotBlank() }
        ?: latest?.eventTitle?.takeIf { it.isNotBlank() }
        ?: ""

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 12.dp)) {
            // Header: accent bar + title/preview (tap to expand) on the left,
            // a chevron expand affordance, then a clearly-separated dismiss button.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .padding(end = 10.dp, top = 2.dp)
                        .size(width = 3.dp, height = 30.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            shape = MaterialTheme.shapes.small
                        )
                )
                // Title + chevron together form the expand tap target.
                Row(
                    Modifier
                        .weight(1f)
                        .clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            displayTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!expanded && previewText.isNotBlank()) {
                            Text(
                                previewText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        latest?.let {
                            Text(
                                formatRelativeTime(it.timestampMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (expanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
                // Clear gap so users don't hit dismiss when reaching for expand.
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = onDismiss,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Dismiss",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 10.dp, end = 8.dp)
                ) {
                    for ((idx, event) in group.events.withIndex()) {
                        if (event.beforeText != null && event.afterText != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    if (eventCount > 1) {
                                        Text(
                                            "Change ${idx + 1}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.height(4.dp))
                                    }
                                    val (beforeDiff, afterDiff) = remember(event.beforeText, event.afterText) {
                                        wordDiff(event.beforeText, event.afterText)
                                    }
                                    Text(
                                        "BEFORE",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFEF5350)
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        beforeDiff,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Divider(
                                        Modifier.padding(vertical = 8.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant
                                    )
                                    Text(
                                        "AFTER",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF4CAF50)
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        afterDiff,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        } else if (event.body.isNotBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    if (!event.eventTitle.isNullOrBlank()) {
                                        Text(
                                            event.eventTitle,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(Modifier.height(3.dp))
                                    }
                                    Text(
                                        event.body,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        formatRelativeTime(event.timestampMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    // Per-event open link so individual announcements
                                    // / events from the same group can be opened
                                    // separately, even when fused into one card.
                                    if (event.url != null) {
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedButton(
                                            onClick = {
                                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(event.url)))
                                            },
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                            modifier = Modifier.fillMaxWidth().height(38.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.OpenInNew,
                                                contentDescription = null,
                                                modifier = Modifier.size(15.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                "Open in VRChat",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Group-level open button only when no event carries its own
                    // URL (e.g. single-event alerts or before/after change diffs).
                    if (group.url != null && group.events.none { it.url != null }) {
                        OutlinedButton(
                            onClick = {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(group.url)))
                            },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().height(40.dp)
                        ) {
                            Icon(
                                Icons.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Open in VRChat",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
