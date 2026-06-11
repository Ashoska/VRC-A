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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Group
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrca.discord.DiscordRpcState
import com.vrca.discord.DiscordRpcStatus
import com.vrca.ui.viewmodel.VrcaViewModel
import com.vrca.vrchat.InAppAlertGroup
import com.vrca.vrchat.InAppAlertState
import com.vrca.vrchat.VrchatAuthManager
import com.vrca.vrchat.VrchatPipelineService
import com.vrca.vrchat.VrchatPipelineState

@Composable
internal fun VrchatStatusPage(vm: VrcaViewModel) {
    val ctx = LocalContext.current

    // Inline sign-in takeover (the old onOpenLogin param was wired to an
    // empty lambda in VrcaScreen, so the Sign in button silently did
    // nothing). Same pattern as Settings → Accounts.
    var showLogin by rememberSaveable { mutableStateOf(false) }
    if (showLogin) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showLogin = false }) { Text("Cancel") }
            }
            Box(Modifier.weight(1f)) {
                com.vrca.vrchat.VrchatLoginScreen(pendingBanId = null) { _, _ ->
                    showLogin = false
                }
            }
        }
        return
    }

    // Reactive: vm.vrchatLoggedOut flips on the auth manager's logged-in/out
    // signals, so a Settings sign-out (or a re-login) updates this tab
    // immediately — the old one-shot remember froze the cold-start value.
    val isLinked = !vm.vrchatLoggedOut && VrchatAuthManager.isLoggedIn(ctx)
    val displayName = if (isLinked) VrchatAuthManager.getStoredDisplayName(ctx) ?: "" else ""
    val presence by VrchatPipelineState.presenceFlow.collectAsState()
    val isConnected by VrchatPipelineState.isConnectedFlow.collectAsState()

    var showLogoutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { InAppAlertState.load(ctx) }

    PageContainer {
        // =========================
        // Identity header — ONE merged card (docs/ui-revamp.md, VRChat tab):
        // avatar + name + status dot + platform/trust chips + RPC dot, with
        // Sign out / View Profile as small trailing actions. The old separate
        // "connection status" + "presence" cards are gone.
        // =========================
        val p = presence
        val friendsOnline by VrchatPipelineState.friendsOnlineFlow.collectAsState()
        val repoHdr = vm.userPreferencesRepository
        val discordSeededHdr by repoHdr.discordSessionSeeded.collectAsState(initial = false)
        val discordStatusHdr by DiscordRpcState.statusFlow.collectAsState()

        ElevatedCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!isLinked) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Not signed in", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Sign in to enable notifications and presence",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(onClick = { showLogin = true }) { Text("Sign in") }
                    }
                } else {
                    val statusColor = presenceStatusColor(p)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SelfAvatar(
                            name = (p?.displayName ?: displayName).ifBlank { "?" },
                            picUrl = p?.profilePicUrl.orEmpty(),
                            statusColor = statusColor
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                (p?.displayName ?: displayName).ifBlank { "VRChat account" },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                presenceStatusText(p),
                                style = MaterialTheme.typography.bodySmall,
                                color = statusColor
                            )
                            // The user's own status TEXT (e.g. "stars :0") —
                            // render verbatim; it is their VRChat status, not a bug.
                            if (!p?.statusDescription.isNullOrBlank()) {
                                Text(
                                    p!!.statusDescription,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        // Small trailing actions
                        if (p?.userId?.isNotBlank() == true) {
                            IconButton(onClick = {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://vrchat.com/home/user/${p.userId}"))
                                )
                            }, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    Icons.Filled.OpenInNew,
                                    contentDescription = "View VRChat profile",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        IconButton(onClick = { showLogoutDialog = true }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.AutoMirrored.Filled.Logout,
                                contentDescription = "Sign out of VRChat",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    // Chips row: platform · trust rank · Discord RPC state
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val platform = prettyPlatform(p?.platform.orEmpty())
                        if (platform.isNotBlank()) {
                            Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                                Text(platform, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        val trust = prettyTrust(p?.trustRank.orEmpty())
                        if (trust.isNotBlank()) {
                            Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                                Text(trust, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (discordSeededHdr) {
                            val rpcOn = discordStatusHdr == DiscordRpcStatus.CONNECTED
                            Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Canvas(Modifier.size(6.dp)) {
                                        drawCircle(
                                            color = if (rpcOn) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    Text("RPC", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    // World line when in-game
                    if (p?.isOnlineInVRChat == true) {
                        Divider()
                        if (p.worldName.isNotBlank()) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(p.worldName, style = MaterialTheme.typography.bodyMedium)
                                val count = if (p.instanceCapacity > 0)
                                    "${p.instancePlayerCount} / ${p.instanceCapacity} players"
                                else "${p.instancePlayerCount} players"
                                Text(
                                    count,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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

                    // Friends online — free, from the local friends cache.
                    friendsOnline?.let { (online, total) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Filled.Group,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "$online of $total friends online",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (p == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                if (isConnected) "Fetching presence..." else "Connecting...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (!isConnected) {
                        Text(
                            "Reconnecting to VRChat...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        VrchatStatusBanner()

        // In-app alerts (persistent until dismissed)
        InAppAlertCards()

        // Discord RPC management + the duplicated About/trust card are GONE
        // from this tab (docs/ui-revamp.md): the full Discord setup lives in
        // Settings → Accounts; the identity header above keeps the small RPC
        // status chip. The tab is now the live feed: identity + status banner
        // + alerts + friends count.
    }

    // Sign out confirmation
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Sign out of VRChat?") },
            text = {
                Text("Notifications and presence stop, and chatbox sending is blocked until you sign back in. Your toggles and messages are kept.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    // logout() emits loggedOutSignal → vm.vrchatLoggedOut flips
                    // (isLinked above is derived from it) and the OSC gate blocks.
                    VrchatAuthManager.logout(ctx)
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

/* =========================
   Identity header helpers
   ========================= */

@Composable
private fun presenceStatusColor(p: VrchatAuthManager.VrcUserPresence?): Color =
    if (p?.isOnlineInVRChat == true) {
        when (p.status) {
            "ask me" -> Color(0xFFFF9800)
            "busy"   -> Color(0xFFF44336)
            "join me" -> Color(0xFF2196F3)
            else     -> Color(0xFF4CAF50)
        }
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun presenceStatusText(p: VrchatAuthManager.VrcUserPresence?): String =
    if (p?.isOnlineInVRChat == true) {
        when (p.status) {
            "ask me" -> "Ask Me"
            "busy"   -> "Do Not Disturb"
            "join me" -> "Join Me"
            else     -> "Online"
        }
    } else "Offline"

private fun prettyPlatform(platform: String): String = when (platform) {
    "standalonewindows" -> "Desktop"
    "android"           -> "Android/Quest"
    "ios"               -> "iOS"
    else                -> ""
}

private fun prettyTrust(rank: String): String = when (rank) {
    "system_trust_legend"  -> "Legendary"
    "system_trust_veteran" -> "Veteran"
    "system_trust_trusted" -> "Trusted"
    "system_trust_known"   -> "Known"
    "system_trust_basic"   -> "New User"
    "" -> ""
    else -> rank.removePrefix("system_trust_").replaceFirstChar { it.uppercase() }
}

/** The user's OWN avatar: VRChat+ profile picture loaded through the
 *  session-authenticated image loader (api.vrchat.cloud 401s otherwise),
 *  falling back to an initial circle — with a status dot pinned bottom-end. */
@Composable
private fun SelfAvatar(name: String, picUrl: String, statusColor: Color, size: Int = 48) {
    val ctx = LocalContext.current
    Box {
        if (picUrl.isNotBlank()) {
            coil.compose.AsyncImage(
                model = picUrl,
                imageLoader = com.vrca.admin.VrchatImageLoader.get(ctx),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size.dp)
                    .clip(CircleShape)
            )
        } else {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                modifier = Modifier.size(size.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(14.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
        }
    }
}

/* =========================
   In-app alerts
   ========================= */

// Alert filter chips (docs/ui-revamp.md): Groups = group announcements/events,
// Bio = bio diffs, Friends = every other per-user alert. Keyed on the alert
// group id prefixes set by fireEventNotification's alertGroupKey.
private fun alertMatchesFilter(groupId: String, filter: String): Boolean = when (filter) {
    "Groups" -> groupId.startsWith("announcement_") || groupId.startsWith("event_")
    "Bio" -> groupId.startsWith("bio_")
    "Friends" -> !groupId.startsWith("announcement_") && !groupId.startsWith("event_") &&
        !groupId.startsWith("bio_")
    else -> true
}

@Composable
private fun InAppAlertCards() {
    val ctx = LocalContext.current
    val groups by InAppAlertState.groups.collectAsState()
    if (groups.isEmpty()) return

    var sectionExpanded by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("All") }

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
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("All", "Friends", "Groups", "Bio").forEach { f ->
                            FilterChip(
                                selected = filter == f,
                                onClick = { filter = f; showAll = false },
                                label = { Text(f, style = MaterialTheme.typography.labelMedium) }
                            )
                        }
                    }

                    val filtered = groups.filter { alertMatchesFilter(it.groupId, filter) }
                    if (filtered.isEmpty()) {
                        Text(
                            "No ${filter.lowercase()} alerts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                    val visible = if (showAll || filtered.size <= VISIBLE_ALERT_LIMIT) filtered
                        else filtered.take(VISIBLE_ALERT_LIMIT)
                    val hiddenCount = filtered.size - visible.size

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
